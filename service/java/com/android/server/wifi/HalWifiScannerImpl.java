/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.server.wifi.WifiNative;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * WifiScanner implementation that takes advantage of the gscan HAL API
 * The gscan API is used to perform background scans and wpa_supplicant is used for onehot scans.
 * @see com.android.server.wifi.WifiScannerImpl for more details on each method
 */
public class HalWifiScannerImpl extends WifiScannerImpl implements Handler.Callback {
    private static final String TAG = "HalWifiScannerImpl";
    private static final boolean DBG = false;

    private final WifiNative mWifiNative;
    private final Handler mEventHandler;
    private boolean mReportSingleScanFullResults = false;
    private WifiNative.ScanEventHandler mSingleScanEventHandler = null;
    private WifiScanner.ScanData mLatestSingleScanResult =
            new WifiScanner.ScanData(0, 0, new ScanResult[0]);

    public HalWifiScannerImpl(WifiNative wifiNative, Looper looper) {
        mWifiNative = wifiNative;
        mEventHandler = new Handler(looper, this);

        // We can't enable these until WifiStateMachine switches to using WifiScanner because
        //   WifiMonitor only supports sending results to one listener
        // TODO Enable these
        // Also need to fix tests again when this is enabled
        // WifiMonitor.getInstance().registerHandler(mWifiNative.getInterfaceName(),
        //         WifiMonitor.SCAN_FAILED_EVENT, mEventHandler);
        // WifiMonitor.getInstance().registerHandler(mWifiNative.getInterfaceName(),
        //         WifiMonitor.SCAN_RESULTS_EVENT, mEventHandler);
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch(msg.what) {
            case WifiMonitor.SCAN_FAILED_EVENT:
                if (mSingleScanEventHandler != null) {
                    Log.e(TAG, "Single scan failed");
                    mSingleScanEventHandler.onScanStatus(WifiNative.WIFI_SCAN_FAILED);
                    mSingleScanEventHandler = null;
                } else {
                    Log.w(TAG, "Got single scan failed event without an active scan request");
                }
                break;
            case WifiMonitor.SCAN_RESULTS_EVENT:
                pollLatestSingleScanData();
                break;
            default:
                Log.e(TAG, "Received unknown message: type=" + msg.what);
                // ignore unknown event
                return false;
        }
        return true;
    }

    @Override
    public boolean startSingleScan(WifiNative.ScanSettings settings,
            WifiNative.ScanEventHandler eventHandler) {
        if (eventHandler == null || settings == null) {
            Log.w(TAG, "Invalid arguments for startSingleScan: settings=" + settings
                    + ",eventHandler=" + eventHandler);
            return false;
        }
        if (mSingleScanEventHandler != null) {
            Log.w(TAG, "A single scan is already running");
            return false;
        }
        Set<Integer> freqs = new HashSet<>();
        mReportSingleScanFullResults = false;
        for (int i = 0; i < settings.num_buckets; ++i) {
            WifiNative.BucketSettings bucketSettings = settings.buckets[i];
            if ((bucketSettings.report_events
                            & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0) {
                mReportSingleScanFullResults = true;
            }
            if (bucketSettings.band == WifiScanner.WIFI_BAND_UNSPECIFIED) {
                for (int j = 0; j < bucketSettings.num_channels; ++j) {
                    WifiNative.ChannelSettings channel = bucketSettings.channels[j];
                    freqs.add(channel.frequency);
                }
            } else {
                WifiScanner.ChannelSpec[] channels =
                    WifiChannelHelper.getChannelsForBand(bucketSettings.band);
                for (WifiScanner.ChannelSpec channel : channels) {
                    freqs.add(channel.frequency);
                }
            }
        }

        mSingleScanEventHandler = eventHandler;
        if (!mWifiNative.scan(freqs)) {
            Log.e(TAG, "Failed to start scan, freqs=" + freqs);
            // indicate scan failure async
            mEventHandler.post(new Runnable() {
                    public void run() {
                        if (mSingleScanEventHandler != null) {
                            mSingleScanEventHandler.onScanStatus(WifiNative.WIFI_SCAN_FAILED);
                        }
                        mSingleScanEventHandler = null;
                    }
                });
        }
        return true;
    }

    @Override
    public WifiScanner.ScanData getLatestSingleScanResults() {
        return mLatestSingleScanResult;
    }


    private void pollLatestSingleScanData() {
        // convert ScanDetail from supplicant to ScanResults
        List<ScanDetail> nativeResults = mWifiNative.getScanResults();
        ScanResult[] results = new ScanResult[nativeResults.size()];
        for (int i = 0; i < results.length; ++i) {
            results[i] = nativeResults.get(i).getScanResult();
        }

        // Dispatch full results
        if (mSingleScanEventHandler != null && mReportSingleScanFullResults) {
            for (int i = 0; i < results.length; ++i) {
                mSingleScanEventHandler.onFullScanResult(results[i]);
            }
        }

        // Sort final results and dispatch event
        Arrays.sort(results, SCAN_RESULT_SORT_COMPARATOR);
        mLatestSingleScanResult = new WifiScanner.ScanData(0, 0, results);
        if (mSingleScanEventHandler != null) {
            mSingleScanEventHandler.onScanStatus(WifiNative.WIFI_SCAN_RESULTS_AVAILABLE);
            mSingleScanEventHandler = null;
        }
    }

    @Override
    public boolean getScanCapabilities(WifiNative.ScanCapabilities capabilities) {
        return mWifiNative.getScanCapabilities(capabilities);
    }

    @Override
    public boolean startBatchedScan(WifiNative.ScanSettings settings,
            WifiNative.ScanEventHandler eventHandler) {
        if (settings == null || eventHandler == null) {
            Log.w(TAG, "Invalid arguments for startBatched: settings=" + settings
                    + ",eventHandler=" + eventHandler);
            return false;
        }
        return mWifiNative.startScan(settings, eventHandler);
    }

    @Override
    public void stopBatchedScan() {
        mWifiNative.stopScan();
    }

    @Override
    public void pauseBatchedScan() {
        mWifiNative.pauseScan();
    }

    @Override
    public void restartBatchedScan() {
        mWifiNative.restartScan();
    }

    @Override
    public WifiScanner.ScanData[] getLatestBatchedScanResults(boolean flush) {
        return mWifiNative.getScanResults(flush);
    }

    @Override
    public boolean setHotlist(WifiScanner.HotlistSettings settings,
            WifiNative.HotlistEventHandler eventHandler) {
        return mWifiNative.setHotlist(settings, eventHandler);
    }

    @Override
    public void resetHotlist() {
        mWifiNative.resetHotlist();
    }

    @Override
    public boolean trackSignificantWifiChange(WifiScanner.WifiChangeSettings settings,
            WifiNative.SignificantWifiChangeEventHandler handler) {
        return mWifiNative.trackSignificantWifiChange(settings, handler);
    }

    @Override
    public void untrackSignificantWifiChange() {
        mWifiNative.untrackSignificantWifiChange();
    }
}
