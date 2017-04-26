/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wifi.aware;

import android.hardware.wifi.V1_0.NanStatusType;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.Clock;
import com.android.server.wifi.nano.WifiMetricsProto;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Wi-Fi Aware metric container/processor.
 */
public class WifiAwareMetrics {
    private static final String TAG = "WifiAwareMetrics";
    private static final boolean DBG = false;

    // Histogram: 8 buckets (i=0, ..., 7) of 9 slots in range 10^i -> 10^(i+1)
    // Buckets:
    //    1 -> 10: 9 @ 1
    //    10 -> 100: 9 @ 10
    //    100 -> 1000: 9 @ 10^2
    //    10^3 -> 10^4: 9 @ 10^3
    //    10^4 -> 10^5: 9 @ 10^4
    //    10^5 -> 10^6: 9 @ 10^5
    //    10^6 -> 10^7: 9 @ 10^6
    //    10^7 -> 10^8: 9 @ 10^7 --> 10^8 ms -> 10^5s -> 28 hours
    private static final HistParms DURATION_LOG_HISTOGRAM = new HistParms(0, 1, 10, 9, 8);

    private final Object mLock = new Object();
    private final Clock mClock;

    private long mLastEnableUsage = 0;
    private long mLastEnableUsageInThisLogWindow = 0;
    private long mAvailableTime = 0;
    private SparseIntArray mHistogramAwareAvailableDurationMs = new SparseIntArray();

    public WifiAwareMetrics(Clock clock) {
        mClock = clock;
    }

    /**
     * Push usage stats for WifiAwareStateMachine.enableUsage() to
     * histogram_aware_available_duration_ms.
     */
    public void recordEnableUsage() {
        synchronized (mLock) {
            if (mLastEnableUsage != 0) {
                Log.w(TAG, "enableUsage: mLastEnableUsage* initialized!?");
            }
            mLastEnableUsage = mClock.getElapsedSinceBootMillis();
            mLastEnableUsageInThisLogWindow = mLastEnableUsage;
        }
    }

    /**
     * Push usage stats for WifiAwareStateMachine.disableUsage() to
     * histogram_aware_available_duration_ms.
     */

    public void recordDisableUsage() {
        synchronized (mLock) {
            if (mLastEnableUsage == 0) {
                Log.e(TAG, "disableUsage: mLastEnableUsage not initialized!?");
                return;
            }

            long now = mClock.getElapsedSinceBootMillis();
            addLogValueToHistogram(now - mLastEnableUsage, mHistogramAwareAvailableDurationMs,
                    DURATION_LOG_HISTOGRAM);
            mAvailableTime += now - mLastEnableUsageInThisLogWindow;
            mLastEnableUsage = 0;
            mLastEnableUsageInThisLogWindow = 0;
        }
    }

    /**
     * Consolidate all metrics into the proto.
     */
    public WifiMetricsProto.WifiAwareLog consolidateProto() {
        WifiMetricsProto.WifiAwareLog log = new WifiMetricsProto.WifiAwareLog();
        long now = mClock.getElapsedSinceBootMillis();
        synchronized (mLock) {
            log.histogramAwareAvailableDurationMs = histogramToProtoArray(
                    mHistogramAwareAvailableDurationMs, DURATION_LOG_HISTOGRAM);
            log.availableTimeMs = mAvailableTime;
            if (mLastEnableUsageInThisLogWindow != 0) {
                log.availableTimeMs += now - mLastEnableUsageInThisLogWindow;
            }
        }
        return log;
    }

    /**
     * clear Wi-Fi Aware metrics
     */
    public void clear() {
        long now = mClock.getElapsedSinceBootMillis();
        synchronized (mLock) {
            // don't clear mLastEnableUsage since could be valid for next measurement period
            mHistogramAwareAvailableDurationMs.clear();
            mAvailableTime = 0;
            if (mLastEnableUsageInThisLogWindow != 0) {
                mLastEnableUsageInThisLogWindow = now;
            }
        }
    }

    /**
     * Dump all WifiAwareMetrics to console (pw) - this method is never called to dump the
     * serialized metrics (handled by parent WifiMetrics).
     *
     * @param fd   unused
     * @param pw   PrintWriter for writing dump to
     * @param args unused
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            pw.println("mLastEnableUsage:" + mLastEnableUsage);
            pw.println("mLastEnableUsageInThisLogWindow:" + mLastEnableUsageInThisLogWindow);
            pw.println("mAvailableTime:" + mAvailableTime);
            pw.println("mHistogramAwareAvailableDurationMs:");
            for (int i = 0; i < mHistogramAwareAvailableDurationMs.size(); ++i) {
                pw.println("  " + mHistogramAwareAvailableDurationMs.keyAt(i) + ": "
                        + mHistogramAwareAvailableDurationMs.valueAt(i));
            }
        }
    }

    // histogram utilities

    /**
     * Specifies a ~log histogram consisting of two levels of buckets - a set of N big buckets:
     *
     * Buckets starts at: B + P * M^i, where i=0, ... , N-1 (N big buckets)
     * Each big bucket is divided into S sub-buckets
     *
     * Each (big) bucket is M times bigger than the previous one.
     *
     * The buckets are then:
     * #0: B + P * M^0 with S buckets each of width (P*M^1-P*M^0)/S
     * #1: B + P * M^1 with S buckets each of width (P*M^2-P*M^1)/S
     * ...
     * #N-1: B + P * M^(N-1) with S buckets each of width (P*M^N-P*M^(N-1))/S
     */
    @VisibleForTesting
    public static class HistParms {
        public HistParms(int b, int p, int m, int s, int n) {
            this.b = b;
            this.p = p;
            this.m = m;
            this.s = s;
            this.n = n;

            // derived values
            mLog = Math.log(m);
            bb = new double[n];
            sbw = new double[n];
            bb[0] = b + p;
            sbw[0] = p * (m - 1.0) / (double) s;
            for (int i = 1; i < n; ++i) {
                bb[i] = m * (bb[i - 1] - b) + b;
                sbw[i] = m * sbw[i - 1];
            }
        }

        // spec
        public int b;
        public int p;
        public int m;
        public int s;
        public int n;

        // derived
        public double mLog;
        public double[] bb; // bucket base
        public double[] sbw; // sub-bucket width
    }

    /**
     * Adds the input value to the histogram based on the histogram parameters.
     */
    @VisibleForTesting
    public static int addLogValueToHistogram(long x, SparseIntArray histogram, HistParms hp) {
        double logArg = (double) (x - hp.b) / (double) hp.p;
        int bigBucketIndex = -1;
        if (logArg > 0) {
            bigBucketIndex = (int) (Math.log(logArg) / hp.mLog);
        }
        int subBucketIndex;
        if (bigBucketIndex < 0) {
            bigBucketIndex = 0;
            subBucketIndex = 0;
        } else if (bigBucketIndex >= hp.n) {
            bigBucketIndex = hp.n - 1;
            subBucketIndex = hp.s - 1;
        } else {
            subBucketIndex = (int) ((x - hp.bb[bigBucketIndex]) / hp.sbw[bigBucketIndex]);
            if (subBucketIndex >= hp.s) { // probably a rounding error so move to next big bucket
                bigBucketIndex++;
                if (bigBucketIndex >= hp.n) {
                    bigBucketIndex = hp.n - 1;
                    subBucketIndex = hp.s - 1;
                } else {
                    subBucketIndex = (int) ((x - hp.bb[bigBucketIndex]) / hp.sbw[bigBucketIndex]);
                }
            }
        }
        int key = bigBucketIndex * hp.s + subBucketIndex;

        // note that get() returns 0 if index not there already
        int newValue = histogram.get(key) + 1;
        histogram.put(key, newValue);

        return newValue;
    }

    /**
     * Converts the histogram (with the specified histogram parameters) to an array of proto
     * histogram buckets.
     */
    @VisibleForTesting
    public static WifiMetricsProto.WifiAwareLog.HistogramBucket[] histogramToProtoArray(
            SparseIntArray histogram, HistParms hp) {
        WifiMetricsProto.WifiAwareLog.HistogramBucket[] protoArray =
                new WifiMetricsProto.WifiAwareLog.HistogramBucket[histogram.size()];
        for (int i = 0; i < histogram.size(); ++i) {
            int key = histogram.keyAt(i);

            protoArray[i] = new WifiMetricsProto.WifiAwareLog.HistogramBucket();
            protoArray[i].start = (long) (hp.bb[key / hp.s] + hp.sbw[key / hp.s] * (key % hp.s));
            protoArray[i].end = (long) (protoArray[i].start + hp.sbw[key / hp.s]);
            protoArray[i].count = histogram.valueAt(i);
        }

        return protoArray;
    }

    /**
     * Adds the NanStatusType to the histogram (translating to the proto enumeration of the status).
     */
    public static void addNanHalStatusToHistogram(int halStatus, SparseIntArray histogram) {
        int protoStatus = convertNanStatusTypeToProtoEnum(halStatus);
        int newValue = histogram.get(protoStatus) + 1;
        histogram.put(protoStatus, newValue);
    }

    /**
     * Converts a histogram of proto NanStatusTypeEnum to a raw proto histogram.
     */
    @VisibleForTesting
    public static WifiMetricsProto.WifiAwareLog.NanStatusHistogramBucket[] histogramToProtoArray(
            SparseIntArray histogram) {
        WifiMetricsProto.WifiAwareLog.NanStatusHistogramBucket[] protoArray =
                new WifiMetricsProto.WifiAwareLog.NanStatusHistogramBucket[histogram.size()];

        for (int i = 0; i < histogram.size(); ++i) {
            protoArray[i] = new WifiMetricsProto.WifiAwareLog.NanStatusHistogramBucket();
            protoArray[i].nanStatusType = histogram.keyAt(i);
            protoArray[i].count = histogram.valueAt(i);
        }

        return protoArray;
    }

    /**
     * Convert a HAL NanStatusType enum to a Metrics proto enum NanStatusTypeEnum.
     */
    public static int convertNanStatusTypeToProtoEnum(int nanStatusType) {
        switch (nanStatusType) {
            case NanStatusType.SUCCESS:
                return WifiMetricsProto.WifiAwareLog.SUCCESS;
            case NanStatusType.INTERNAL_FAILURE:
                return WifiMetricsProto.WifiAwareLog.INTERNAL_FAILURE;
            case NanStatusType.PROTOCOL_FAILURE:
                return WifiMetricsProto.WifiAwareLog.PROTOCOL_FAILURE;
            case NanStatusType.INVALID_SESSION_ID:
                return WifiMetricsProto.WifiAwareLog.INVALID_SESSION_ID;
            case NanStatusType.NO_RESOURCES_AVAILABLE:
                return WifiMetricsProto.WifiAwareLog.NO_RESOURCES_AVAILABLE;
            case NanStatusType.INVALID_ARGS:
                return WifiMetricsProto.WifiAwareLog.INVALID_ARGS;
            case NanStatusType.INVALID_PEER_ID:
                return WifiMetricsProto.WifiAwareLog.INVALID_PEER_ID;
            case NanStatusType.INVALID_NDP_ID:
                return WifiMetricsProto.WifiAwareLog.INVALID_NDP_ID;
            case NanStatusType.NAN_NOT_ALLOWED:
                return WifiMetricsProto.WifiAwareLog.NAN_NOT_ALLOWED;
            case NanStatusType.NO_OTA_ACK:
                return WifiMetricsProto.WifiAwareLog.NO_OTA_ACK;
            case NanStatusType.ALREADY_ENABLED:
                return WifiMetricsProto.WifiAwareLog.ALREADY_ENABLED;
            case NanStatusType.FOLLOWUP_TX_QUEUE_FULL:
                return WifiMetricsProto.WifiAwareLog.FOLLOWUP_TX_QUEUE_FULL;
            case NanStatusType.UNSUPPORTED_CONCURRENCY_NAN_DISABLED:
                return WifiMetricsProto.WifiAwareLog.UNSUPPORTED_CONCURRENCY_NAN_DISABLED;
            default:
                Log.e(TAG, "Unrecognized NanStatusType: " + nanStatusType);
                return WifiMetricsProto.WifiAwareLog.UNKNOWN_HAL_STATUS;
        }
    }
}
