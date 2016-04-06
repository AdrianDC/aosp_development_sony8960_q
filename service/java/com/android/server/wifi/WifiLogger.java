/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import android.util.Base64;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ByteArrayRingBuffer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.zip.Deflater;

/**
 * Tracks various logs for framework
 */
class WifiLogger extends BaseWifiLogger {

    private static final String TAG = "WifiLogger";
    private static final boolean DBG = false;

    /** log level flags; keep these consistent with wifi_logger.h */

    /** No logs whatsoever */
    public static final int VERBOSE_NO_LOG = 0;
    /** No logs whatsoever */
    public static final int VERBOSE_NORMAL_LOG = 1;
    /** Be careful since this one can affect performance and power */
    public static final int VERBOSE_LOG_WITH_WAKEUP  = 2;
    /** Be careful since this one can affect performance and power and memory */
    public static final int VERBOSE_DETAILED_LOG_WITH_WAKEUP  = 3;

    /** ring buffer flags; keep these consistent with wifi_logger.h */
    public static final int RING_BUFFER_FLAG_HAS_BINARY_ENTRIES     = 0x00000001;
    public static final int RING_BUFFER_FLAG_HAS_ASCII_ENTRIES      = 0x00000002;
    public static final int RING_BUFFER_FLAG_HAS_PER_PACKET_ENTRIES = 0x00000004;

    /** various reason codes */
    public static final int REPORT_REASON_NONE                      = 0;
    public static final int REPORT_REASON_ASSOC_FAILURE             = 1;
    public static final int REPORT_REASON_AUTH_FAILURE              = 2;
    public static final int REPORT_REASON_AUTOROAM_FAILURE          = 3;
    public static final int REPORT_REASON_DHCP_FAILURE              = 4;
    public static final int REPORT_REASON_UNEXPECTED_DISCONNECT     = 5;
    public static final int REPORT_REASON_SCAN_FAILURE              = 6;
    public static final int REPORT_REASON_USER_ACTION               = 7;

    /** number of bug reports to hold */
    public static final int MAX_BUG_REPORTS                         = 4;

    /** number of alerts to hold */
    public static final int MAX_ALERT_REPORTS                       = 1;

    /** minimum wakeup interval for each of the log levels */
    private static final int MinWakeupIntervals[] = new int[] { 0, 3600, 60, 10 };
    /** minimum buffer size for each of the log levels */
    private static final int MinBufferSizes[] = new int[] { 0, 16384, 16384, 65536 };

    private int mLogLevel = VERBOSE_NO_LOG;
    private WifiNative.RingBufferStatus[] mRingBuffers;
    private WifiNative.RingBufferStatus mPerPacketRingBuffer;
    private WifiStateMachine mWifiStateMachine;
    private final WifiNative mWifiNative;
    private final int mMaxRingBufferSizeBytes;

    public WifiLogger(
            WifiStateMachine wifiStateMachine, WifiNative wifiNative, int maxRingBufferSizeBytes) {
        mWifiStateMachine = wifiStateMachine;
        mWifiNative = wifiNative;
        mMaxRingBufferSizeBytes = maxRingBufferSizeBytes;
    }

    @Override
    public synchronized void startLogging(boolean verboseEnabled) {
        mFirmwareVersion = mWifiNative.getFirmwareVersion();
        mDriverVersion = mWifiNative.getDriverVersion();
        mSupportedFeatureSet = mWifiNative.getSupportedLoggerFeatureSet();

        if (mLogLevel == VERBOSE_NO_LOG)
            mWifiNative.setLoggingEventHandler(mHandler);

        if (verboseEnabled) {
            mLogLevel = VERBOSE_LOG_WITH_WAKEUP;
        } else {
            mLogLevel = VERBOSE_NORMAL_LOG;
        }

        if (mRingBuffers == null) {
            fetchRingBuffers();
        }

        if (mRingBuffers != null) {
            /* log level may have changed, so restart logging with new levels */
            stopLoggingAllBuffers();
            startLoggingAllExceptPerPacketBuffers();
        }

        if (verboseEnabled && !mWifiNative.startPktFateMonitoring()) {
            Log.e(TAG, "Failed to start packet fate monitoring");
        }
    }

    @Override
    public synchronized void startPacketLog() {
        if (mPerPacketRingBuffer != null) {
            startLoggingRingBuffer(mPerPacketRingBuffer);
        } else {
            if (DBG) Log.d(TAG, "There is no per packet ring buffer");
        }
    }

    @Override
    public synchronized void stopPacketLog() {
        if (mPerPacketRingBuffer != null) {
            stopLoggingRingBuffer(mPerPacketRingBuffer);
        } else {
            if (DBG) Log.d(TAG, "There is no per packet ring buffer");
        }
    }

    @Override
    public synchronized void stopLogging() {
        if (mLogLevel != VERBOSE_NO_LOG) {
            //resetLogHandler only can be used when you terminate all logging since all handler will
            //be removed. This also stop alert logging
            if(!mWifiNative.resetLogHandler()) {
                Log.e(TAG, "Fail to reset log handler");
            } else {
                if (DBG) Log.d(TAG,"Reset log handler");
            }
            stopLoggingAllBuffers();
            mRingBuffers = null;
            mLogLevel = VERBOSE_NO_LOG;
        }
    }

    @Override
    synchronized void reportConnectionFailure() {
        if (mLogLevel <= VERBOSE_NORMAL_LOG) {
            return;
        }

        mPacketFatesForLastFailure = fetchPacketFates();
    }

    @Override
    public synchronized void captureBugReportData(int reason) {
        BugReport report = captureBugreport(reason, true);
        mLastBugReports.addLast(report);
    }

    @Override
    public synchronized void captureAlertData(int errorCode, byte[] alertData) {
        BugReport report = captureBugreport(errorCode, /* captureFWDump = */ true);
        report.alertData = alertData;
        mLastAlerts.addLast(report);
    }

    @Override
    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(pw);

        for (int i = 0; i < mLastAlerts.size(); i++) {
            pw.println("--------------------------------------------------------------------");
            pw.println("Alert dump " + i);
            pw.print(mLastAlerts.get(i));
            pw.println("--------------------------------------------------------------------");
        }

        for (int i = 0; i < mLastBugReports.size(); i++) {
            pw.println("--------------------------------------------------------------------");
            pw.println("Bug dump " + i);
            pw.print(mLastBugReports.get(i));
            pw.println("--------------------------------------------------------------------");
        }

        dumpPacketFates(pw);

        pw.println("--------------------------------------------------------------------");
    }

    /* private methods and data */
    class BugReport {
        long systemTimeMs;
        long kernelTimeNanos;
        int errorCode;
        HashMap<String, byte[][]> ringBuffers = new HashMap();
        byte[] fwMemoryDump;
        byte[] alertData;
        LimitedCircularArray<String> kernelLogLines;
        LimitedCircularArray<String> logcatLines;

        public String toString() {
            StringBuilder builder = new StringBuilder();

            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(systemTimeMs);
            builder.append("system time = ").append(
                    String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c)).append("\n");

            long kernelTimeMs = kernelTimeNanos/(1000*1000);
            builder.append("kernel time = ").append(kernelTimeMs/1000).append(".").append
                    (kernelTimeMs%1000).append("\n");

            if (alertData == null)
                builder.append("reason = ").append(errorCode).append("\n");
            else {
                builder.append("errorCode = ").append(errorCode);
                builder.append("data \n");
                builder.append(compressToBase64(alertData)).append("\n");
            }

            if (kernelLogLines != null) {
                builder.append("kernel log: \n");
                for (int i = 0; i < kernelLogLines.size(); i++) {
                    builder.append(kernelLogLines.get(i)).append("\n");
                }
                builder.append("\n");
            }

            if (logcatLines != null) {
                builder.append("system log: \n");
                for (int i = 0; i < logcatLines.size(); i++) {
                    builder.append(logcatLines.get(i)).append("\n");
                }
                builder.append("\n");
            }

            for (HashMap.Entry<String, byte[][]> e : ringBuffers.entrySet()) {
                String ringName = e.getKey();
                byte[][] buffers = e.getValue();
                builder.append("ring-buffer = ").append(ringName).append("\n");

                int size = 0;
                for (int i = 0; i < buffers.length; i++) {
                    size += buffers[i].length;
                }

                byte[] buffer = new byte[size];
                int index = 0;
                for (int i = 0; i < buffers.length; i++) {
                    System.arraycopy(buffers[i], 0, buffer, index, buffers[i].length);
                    index += buffers[i].length;
                }

                builder.append(compressToBase64(buffer));
                builder.append("\n");
            }

            if (fwMemoryDump != null) {
                builder.append("FW Memory dump \n");
                builder.append(compressToBase64(fwMemoryDump));
            }

            return builder.toString();
        }
    }

    class LimitedCircularArray<E> {
        private ArrayList<E> mArrayList;
        private int mMax;
        LimitedCircularArray(int max) {
            mArrayList = new ArrayList<E>(max);
            mMax = max;
        }

        public final void addLast(E e) {
            if (mArrayList.size() >= mMax)
                mArrayList.remove(0);
            mArrayList.add(e);
        }

        public final int size() {
            return mArrayList.size();
        }

        public final E get(int i) {
            return mArrayList.get(i);
        }
    }

    private final LimitedCircularArray<BugReport> mLastAlerts =
            new LimitedCircularArray<BugReport>(MAX_ALERT_REPORTS);
    private final LimitedCircularArray<BugReport> mLastBugReports =
            new LimitedCircularArray<BugReport>(MAX_BUG_REPORTS);
    private final HashMap<String, ByteArrayRingBuffer> mRingBufferData = new HashMap();

    private final WifiNative.WifiLoggerEventHandler mHandler =
            new WifiNative.WifiLoggerEventHandler() {
        @Override
        public void onRingBufferData(WifiNative.RingBufferStatus status, byte[] buffer) {
            WifiLogger.this.onRingBufferData(status, buffer);
        }

        @Override
        public void onWifiAlert(int errorCode, byte[] buffer) {
            WifiLogger.this.onWifiAlert(errorCode, buffer);
        }
    };

    synchronized void onRingBufferData(WifiNative.RingBufferStatus status, byte[] buffer) {
        ByteArrayRingBuffer ring = mRingBufferData.get(status.name);
        if (ring != null) {
            ring.appendBuffer(buffer);
        }
    }

    synchronized void onWifiAlert(int errorCode, byte[] buffer) {
        if (mWifiStateMachine != null) {
            mWifiStateMachine.sendMessage(
                    WifiStateMachine.CMD_FIRMWARE_ALERT, errorCode, 0, buffer);
        }
    }

    private boolean fetchRingBuffers() {
        if (mRingBuffers != null) return true;

        mRingBuffers = mWifiNative.getRingBufferStatus();
        if (mRingBuffers != null) {
            for (WifiNative.RingBufferStatus buffer : mRingBuffers) {
                if (DBG) Log.d(TAG, "RingBufferStatus is: \n" + buffer.name);
                if (mRingBufferData.containsKey(buffer.name) == false) {
                    mRingBufferData.put(buffer.name,
                            new ByteArrayRingBuffer(mMaxRingBufferSizeBytes));
                }
                if ((buffer.flag & RING_BUFFER_FLAG_HAS_PER_PACKET_ENTRIES) != 0) {
                    mPerPacketRingBuffer = buffer;
                }
            }
        } else {
            Log.e(TAG, "no ring buffers found");
        }

        return mRingBuffers != null;
    }

    private boolean startLoggingAllExceptPerPacketBuffers() {

        if (mRingBuffers == null) {
            if (DBG) Log.d(TAG, "No ring buffers to log anything!");
            return false;
        }

        for (WifiNative.RingBufferStatus buffer : mRingBuffers){

            if ((buffer.flag & RING_BUFFER_FLAG_HAS_PER_PACKET_ENTRIES) != 0) {
                /* skip per-packet-buffer */
                if (DBG) Log.d(TAG, "skipped per packet logging ring " + buffer.name);
                continue;
            }

            startLoggingRingBuffer(buffer);
        }

        return true;
    }

    private boolean startLoggingRingBuffer(WifiNative.RingBufferStatus buffer) {

        int minInterval = MinWakeupIntervals[mLogLevel];
        int minDataSize = MinBufferSizes[mLogLevel];

        if (mWifiNative.startLoggingRingBuffer(
                mLogLevel, 0, minInterval, minDataSize, buffer.name) == false) {
            if (DBG) Log.e(TAG, "Could not start logging ring " + buffer.name);
            return false;
        }

        return true;
    }

    private boolean stopLoggingRingBuffer(WifiNative.RingBufferStatus buffer) {
        if (mWifiNative.startLoggingRingBuffer(0, 0, 0, 0, buffer.name) == false) {
            if (DBG) Log.e(TAG, "Could not stop logging ring " + buffer.name);
        }
        return true;
    }

    private boolean stopLoggingAllBuffers() {
        if (mRingBuffers != null) {
            for (WifiNative.RingBufferStatus buffer : mRingBuffers) {
                stopLoggingRingBuffer(buffer);
            }
        }
        return true;
    }

    private boolean getAllRingBufferData() {
        if (mRingBuffers == null) {
            Log.e(TAG, "Not ring buffers available to collect data!");
            return false;
        }

        for (WifiNative.RingBufferStatus element : mRingBuffers){
            boolean result = mWifiNative.getRingBufferData(element.name);
            if (!result) {
                Log.e(TAG, "Fail to get ring buffer data of: " + element.name);
                return false;
            }
        }

        Log.d(TAG, "getAllRingBufferData Successfully!");
        return true;
    }

    private BugReport captureBugreport(int errorCode, boolean captureFWDump) {
        BugReport report = new BugReport();
        report.errorCode = errorCode;
        report.systemTimeMs = System.currentTimeMillis();
        report.kernelTimeNanos = System.nanoTime();

        if (mRingBuffers != null) {
            for (WifiNative.RingBufferStatus buffer : mRingBuffers) {
                /* this will push data in mRingBuffers */
                mWifiNative.getRingBufferData(buffer.name);
                ByteArrayRingBuffer data = mRingBufferData.get(buffer.name);
                byte[][] buffers = new byte[data.getNumBuffers()][];
                for (int i = 0; i < data.getNumBuffers(); i++) {
                    buffers[i] = data.getBuffer(i).clone();
                }
                report.ringBuffers.put(buffer.name, buffers);
            }
        }

        report.logcatLines = getOutput("logcat -d", 127);
        report.kernelLogLines = getKernelLog(127);

        if (captureFWDump) {
            report.fwMemoryDump = mWifiNative.getFwMemoryDump();
        }
        return report;
    }

    @VisibleForTesting
    LimitedCircularArray<BugReport> getBugReports() {
        return mLastBugReports;
    }

    private static String compressToBase64(byte[] input) {
        String result;
        //compress
        Deflater compressor = new Deflater();
        compressor.setLevel(Deflater.BEST_COMPRESSION);
        compressor.setInput(input);
        compressor.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);
        final byte[] buf = new byte[1024];

        while (!compressor.finished()) {
            int count = compressor.deflate(buf);
            bos.write(buf, 0, count);
        }

        try {
            compressor.end();
            bos.close();
        } catch (IOException e) {
            Log.e(TAG, "ByteArrayOutputStream close error");
            result =  android.util.Base64.encodeToString(input, Base64.DEFAULT);
            return result;
        }

        byte[] compressed = bos.toByteArray();
        if (DBG) {
            Log.d(TAG," length is:" + (compressed == null? "0" : compressed.length));
        }

        //encode
        result = android.util.Base64.encodeToString(
                compressed.length < input.length ? compressed : input , Base64.DEFAULT);

        if (DBG) {
            Log.d(TAG, "FwMemoryDump length is :" + result.length());
        }

        return result;
    }

    private LimitedCircularArray<String> getOutput(String cmdLine, int maxLines) {
        LimitedCircularArray<String> lines = new LimitedCircularArray<String>(maxLines);
        try {
            if (DBG) Log.d(TAG, "Executing '" + cmdLine + "' ...");
            Process process = Runtime.getRuntime().exec(cmdLine);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
            }
            reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
            }
            process.waitFor();
            if (DBG) Log.d(TAG, "Lines added for '" + cmdLine + "'.");
        } catch (InterruptedException e) {
            Log.e(TAG, "Could not wait for '" + cmdLine + "': " + e);
        } catch (IOException e) {
            Log.e(TAG, "Could not open '" + cmdLine + "': " + e);
        }
        return lines;
    }

    private LimitedCircularArray<String> getKernelLog(int maxLines) {
        if (DBG) Log.d(TAG, "Reading kernel log ...");
        LimitedCircularArray<String> lines = new LimitedCircularArray<String>(maxLines);
        String log = mWifiNative.readKernelLog();
        String logLines[] = log.split("\n");
        for (int i = 0; i < logLines.length; i++) {
            lines.addLast(logLines[i]);
        }
        if (DBG) Log.d(TAG, "Added " + logLines.length + " lines");
        return lines;
    }

    /** Packet fate reporting */
    private ArrayList<WifiNative.FateReport> mPacketFatesForLastFailure;

    private ArrayList<WifiNative.FateReport> fetchPacketFates() {
        ArrayList<WifiNative.FateReport> mergedFates = new ArrayList<WifiNative.FateReport>();
        WifiNative.TxFateReport[] txFates =
                new WifiNative.TxFateReport[WifiLoggerHal.MAX_FATE_LOG_LEN];
        if (mWifiNative.getTxPktFates(txFates)) {
            for (int i = 0; i < txFates.length && txFates[i] != null; i++) {
                mergedFates.add(txFates[i]);
            }
        }

        WifiNative.RxFateReport[] rxFates =
                new WifiNative.RxFateReport[WifiLoggerHal.MAX_FATE_LOG_LEN];
        if (mWifiNative.getRxPktFates(rxFates)) {
            for (int i = 0; i < rxFates.length && rxFates[i] != null; i++) {
                mergedFates.add(rxFates[i]);
            }
        }

        Collections.sort(mergedFates, new Comparator<WifiNative.FateReport>() {
            @Override
            public int compare(WifiNative.FateReport lhs, WifiNative.FateReport rhs) {
                return Long.compare(lhs.mDriverTimestampUSec, rhs.mDriverTimestampUSec);
            }
        });

        return mergedFates;
    }

    private void dumpPacketFates(PrintWriter pw) {
        dumpPacketFatesInternal(pw, "Last failed connection fates", mPacketFatesForLastFailure);
        if (DBG) {
            dumpPacketFatesInternal(pw, "Latest fates", fetchPacketFates());
        }
    }

    private static void dumpPacketFatesInternal(
            PrintWriter pw, String description, ArrayList<WifiNative.FateReport> fates) {
        if (fates == null) {
            pw.format("No fates fetched for \"%s\"\n", description);
            return;
        }

        if (fates.size() == 0) {
            pw.format("HAL provided zero fates for \"%s\"\n", description);
            return;
        }

        int i = 0;
        pw.format("--------------------- %s ----------------------\n", description);
        for (WifiNative.FateReport fate : fates) {
            pw.format("Frame number: %d\n", i + 1);
            pw.print(fate);
            pw.print("\n");
            ++i;
        }
        pw.println("--------------------------------------------------------------------");
    }
}
