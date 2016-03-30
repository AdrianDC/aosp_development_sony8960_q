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

package com.android.server.wifi.scanner;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiScanner.ScanSettings;
import android.util.Rational;
import android.util.Slog;

import com.android.server.wifi.WifiNative;
import com.android.server.wifi.scanner.ChannelHelper.ChannelCollection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * <p>This class takes a series of scan requests and formulates the best hardware level scanning
 * schedule it can to try and satisfy requests. The hardware level accepts a series of buckets,
 * where each bucket represents a set of channels and an interval to scan at. This
 * scheduler operates as follows:</p>
 *
 * <p>Each new request is placed in the best predefined bucket. Once all requests have been added
 * the last buckets (lower priority) are placed in the next best bucket until the number of buckets
 * is less than the number supported by the hardware.
 *
 * <p>Finally, the scheduler creates a WifiNative.ScanSettings from the list of buckets which may be
 * passed through the Wifi HAL.</p>
 *
 * <p>This class is not thread safe.</p>
 */
public class BackgroundScanScheduler {

    private static final String TAG = "BackgroundScanScheduler";
    private static final boolean DBG = false;

    public static final int DEFAULT_MAX_BUCKETS = 8;
    public static final int DEFAULT_MAX_CHANNELS = 32;
    // anecdotally, some chipsets will fail without explanation with a higher batch size, and
    // there is apparently no way to retrieve the maximum batch size
    public static final int DEFAULT_MAX_SCANS_TO_BATCH = 10;
    public static final int DEFAULT_MAX_AP_PER_SCAN = 32;

    /**
     * Value that all scan periods must be an integer multiple of
     */
    private static final int PERIOD_MIN_GCD_MS = 10000;
    /**
     * Default period to use if no buckets are being scheduled
     */
    private static final int DEFAULT_PERIOD_MS = 40000;
    /**
     * Scan report threshold percentage to assign to the schedule by default
     * @see com.android.server.wifi.WifiNative.ScanSettings#report_threshold_percent
     */
    private static final int DEFAULT_REPORT_THRESHOLD_PERCENTAGE = 100;

    /**
     * List of predefined periods (in ms) that buckets can be scheduled at. Ordered by preference
     * if there are not enough buckets for all periods. All periods MUST be 2^N * PERIOD_MIN_GCD_MS.
     * This requirement allows scans to be scheduled more efficiently because scan requests with
     * intersecting channels will result in those channels being scanned exactly once at the smaller
     * period and no unnecessary scan being scheduled. If this was not the case and two requests
     * had channel 5 with periods of 15 seconds and 25 seconds then channel 5 would be scanned
     * 296  (3600/15 + 3600/25 - 3500/75) times an hour instead of 240 times an hour (3600/15) if
     * the 25s scan is rescheduled at 30s. This is less important with higher periods as it has
     * significantly less impact. Ranking could be done by favoring shorter or longer; however,
     * this would result in straying further from the requested period and possibly power
     * implications if the scan is scheduled at a significantly lower period.
     *
     * For example if the hardware only supports 2 buckets and scans are requested with periods of
     * 40s, 20s and 10s then the two buckets scheduled will have periods 40s and 20s and the 10s
     * scan will be placed in the 20s bucket.
     *
     * If there are special scan requests such as exponential back off, we always dedicate a bucket
     * for each type. Regular scan requests will be packed into the remaining buckets.
     */
    private static final int[] PREDEFINED_BUCKET_PERIODS = {
        4 * PERIOD_MIN_GCD_MS,   // 40s
        2 * PERIOD_MIN_GCD_MS,   // 20s
        16 * PERIOD_MIN_GCD_MS,  // 160s
        32 * PERIOD_MIN_GCD_MS,  // 320s
        1 * PERIOD_MIN_GCD_MS,   // 10s
        128 * PERIOD_MIN_GCD_MS, // 1280s
        64 * PERIOD_MIN_GCD_MS,  // 640s
        256 * PERIOD_MIN_GCD_MS, // 2560s
        -1,                      // place holder for exponential back off scan
    };

    private static final int EXPONENTIAL_BACK_OFF_BUCKET_IDX =
            (PREDEFINED_BUCKET_PERIODS.length - 1);
    private static final int NUM_OF_REGULAR_BUCKETS =
            (PREDEFINED_BUCKET_PERIODS.length - 1);

    /**
     * this class is an intermediate representation for scheduling
     */
    private class Bucket {
        public int period;
        public final List<ScanSettings> settings = new ArrayList<>();

        Bucket(int period) {
            this.period = period;
        }

        /**
         * convert ChannelSpec to native representation
         */
        private WifiNative.ChannelSettings createChannelSettings(int frequency) {
            WifiNative.ChannelSettings channelSettings = new WifiNative.ChannelSettings();
            channelSettings.frequency = frequency;
            return channelSettings;
        }

        /**
         * convert the setting for this bucket to HAL representation
         */
        public WifiNative.BucketSettings createBucketSettings(int bucketId,
                int maxChannels) {
            int reportEvents = WifiScanner.REPORT_EVENT_NO_BATCH;
            int maxPeriodInMs = 0;
            int stepCount = 0;
            int bucketIndex = 0;

            mChannelCollection.clear();

            for (int i = 0; i < settings.size(); ++i) {
                WifiScanner.ScanSettings setting = settings.get(i);
                int requestedReportEvents = setting.reportEvents;
                if ((requestedReportEvents & WifiScanner.REPORT_EVENT_NO_BATCH) == 0) {
                    reportEvents &= ~WifiScanner.REPORT_EVENT_NO_BATCH;
                }
                if ((requestedReportEvents & WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN) != 0) {
                    reportEvents |= WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
                }
                if ((requestedReportEvents & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0) {
                    reportEvents |= WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT;
                }

                mChannelCollection.addChannels(setting);

                // For the bucket allocated to exponential back off scan, the values of
                // the exponential back off scan related parameters from the very first
                // setting in the settings list will be used to configure this bucket.
                //
                if (i == 0 && setting.maxPeriodInMs != 0
                        && setting.maxPeriodInMs != setting.periodInMs) {
                    // Align the starting period with one of the pre-defined regular
                    // scan periods. This will optimize the scan schedule when it has
                    // both exponential back off scan and regular scan(s).
                    bucketIndex = findBestRegularBucketIndex(setting.periodInMs,
                                                     NUM_OF_REGULAR_BUCKETS);
                    period = PREDEFINED_BUCKET_PERIODS[bucketIndex];
                    maxPeriodInMs = (setting.maxPeriodInMs < period)
                                    ? period
                                    : setting.maxPeriodInMs;
                    stepCount = setting.stepCount;
                }

            }

            WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
            bucketSettings.bucket = bucketId;
            bucketSettings.report_events = reportEvents;
            bucketSettings.period_ms = period;
            bucketSettings.max_period_ms = maxPeriodInMs;
            bucketSettings.step_count = stepCount;
            mChannelCollection.fillBucketSettings(bucketSettings, maxChannels);
            return bucketSettings;
        }
    }

    /**
     * Maintains a list of buckets and the number that are active (non-null)
     */
    private class BucketList {
        private final Bucket[] mBuckets;
        private int mActiveBucketCount = 0;

        BucketList() {
            mBuckets = new Bucket[PREDEFINED_BUCKET_PERIODS.length];
        }

        public void clearAll() {
            Arrays.fill(mBuckets, null);
            mActiveBucketCount = 0;
        }

        public void clear(int index) {
            if (mBuckets[index] != null) {
                --mActiveBucketCount;
                mBuckets[index] = null;
            }
        }

        public Bucket getOrCreate(int index) {
            Bucket bucket = mBuckets[index];
            if (bucket == null) {
                ++mActiveBucketCount;
                bucket = mBuckets[index] = new Bucket(PREDEFINED_BUCKET_PERIODS[index]);
            }
            return bucket;
        }

        public boolean isActive(int index) {
            return mBuckets[index] != null;
        }

        public Bucket get(int index) {
            return mBuckets[index];
        }

        public int size() {
            return mBuckets.length;
        }

        public int getActiveCount() {
            return mActiveBucketCount;
        }

        public int getActiveRegularBucketCount() {
            if (isActive(EXPONENTIAL_BACK_OFF_BUCKET_IDX)) {
                return mActiveBucketCount - 1;
            } else {
                return mActiveBucketCount;
            }
        }
    }

    private int mMaxBuckets = DEFAULT_MAX_BUCKETS;
    private int mMaxChannels = DEFAULT_MAX_CHANNELS;
    private int mMaxBatch = DEFAULT_MAX_SCANS_TO_BATCH;
    private int mMaxApPerScan = DEFAULT_MAX_AP_PER_SCAN;

    public int getMaxBuckets() {
        return mMaxBuckets;
    }

    public void setMaxBuckets(int maxBuckets) {
        mMaxBuckets = maxBuckets;
    }

    public int getMaxChannels() {
        return mMaxChannels;
    }

    // TODO: find a way to get max channels
    public void setMaxChannels(int maxChannels) {
        mMaxChannels = maxChannels;
    }

    public int getMaxBatch() {
        return mMaxBatch;
    }

    // TODO: find a way to get max batch size
    public void setMaxBatch(int maxBatch) {
        mMaxBatch = maxBatch;
    }

    public int getMaxApPerScan() {
        return mMaxApPerScan;
    }

    public void setMaxApPerScan(int maxApPerScan) {
        mMaxApPerScan = maxApPerScan;
    }

    private final BucketList mBuckets = new BucketList();
    private final ChannelHelper mChannelHelper;
    private final ChannelCollection mChannelCollection;
    private WifiNative.ScanSettings mSchedule;
    private final Map<ScanSettings, Integer> mSettingsToScheduledBucket = new HashMap<>();

    public BackgroundScanScheduler(ChannelHelper channelHelper) {
        mChannelHelper = channelHelper;
        mChannelCollection = mChannelHelper.createChannelCollection();
        createSchedule();
    }

    /**
     * Updates the schedule from the given set of requests.
     */
    public void updateSchedule(@NonNull Collection<ScanSettings> requests) {
        // create initial schedule
        mBuckets.clearAll();
        for (ScanSettings request : requests) {
            addScanToBuckets(request);
        }

        compactBuckets(getMaxBuckets());

        createSchedule();
    }

    /**
     * Retrieves the current scanning schedule.
     */
    public @NonNull WifiNative.ScanSettings getSchedule() {
        return mSchedule;
    }

    /**
     * Returns true if the given scan result should be reported to a listener with the given
     * settings.
     */
    public boolean shouldReportFullScanResultForSettings(@NonNull ScanResult result,
            int bucketsScanned, @NonNull ScanSettings settings) {
        return ScanScheduleUtil.shouldReportFullScanResultForSettings(mChannelHelper,
                result, bucketsScanned, settings, getScheduledBucket(settings));
    }

    /**
     * Returns a filtered version of the scan results from the chip that represents only the data
     * requested in the settings. Will return null if the result should not be reported.
     */
    public @Nullable ScanData[] filterResultsForSettings(@NonNull ScanData[] scanDatas,
            @NonNull ScanSettings settings) {
        return ScanScheduleUtil.filterResultsForSettings(mChannelHelper, scanDatas, settings,
                getScheduledBucket(settings));
    }

    private int getScheduledBucket(ScanSettings settings) {
        Integer scheduledBucket = mSettingsToScheduledBucket.get(settings);
        if (scheduledBucket != null) {
            return scheduledBucket;
        } else {
            Slog.wtf(TAG, "No bucket found for settings");
            return -1;
        }
    }

    /**
     * creates a schedule for the current buckets
     */
    private void createSchedule() {
        mSettingsToScheduledBucket.clear();
        WifiNative.ScanSettings schedule = new WifiNative.ScanSettings();
        schedule.num_buckets = mBuckets.getActiveCount();
        schedule.buckets = new WifiNative.BucketSettings[mBuckets.getActiveCount()];

        schedule.max_ap_per_scan = 0;
        schedule.report_threshold_num_scans = getMaxBatch();
        HashSet<Integer> hiddenNetworkIdSet = new HashSet<>();

        // set all buckets in schedule
        int bucketId = 0;
        for (int i = 0; i < mBuckets.size(); ++i) {
            if (mBuckets.isActive(i)) {
                schedule.buckets[bucketId] =
                        mBuckets.get(i).createBucketSettings(bucketId, getMaxChannels());

                for (ScanSettings settings : mBuckets.get(i).settings) {
                    mSettingsToScheduledBucket.put(settings, bucketId);

                    // set APs per scan
                    if (settings.numBssidsPerScan > schedule.max_ap_per_scan) {
                        schedule.max_ap_per_scan = settings.numBssidsPerScan;
                    }

                    // set batching
                    if (settings.maxScansToCache != 0
                            && settings.maxScansToCache < schedule.report_threshold_num_scans) {
                        schedule.report_threshold_num_scans = settings.maxScansToCache;
                    }

                    // note hidden networks
                    if (settings.hiddenNetworkIds != null) {
                        for (int j = 0; j < settings.hiddenNetworkIds.length; j++) {
                            hiddenNetworkIdSet.add(settings.hiddenNetworkIds[j]);
                        }
                    }
                }
                bucketId++;
            }
        }

        schedule.report_threshold_percent = DEFAULT_REPORT_THRESHOLD_PERCENTAGE;

        if (schedule.max_ap_per_scan == 0 || schedule.max_ap_per_scan > getMaxApPerScan()) {
            schedule.max_ap_per_scan = getMaxApPerScan();
        }
        if (hiddenNetworkIdSet.size() > 0) {
            schedule.hiddenNetworkIds = new int[hiddenNetworkIdSet.size()];
            int numHiddenNetworks = 0;
            for (Integer hiddenNetworkId : hiddenNetworkIdSet) {
                schedule.hiddenNetworkIds[numHiddenNetworks++] = hiddenNetworkId;
            }
        }

        // update base period as gcd of periods
        if (schedule.num_buckets > 0) {
            int gcd = schedule.buckets[0].period_ms;
            for (int b = 1; b < schedule.num_buckets; b++) {
                gcd = Rational.gcd(schedule.buckets[b].period_ms, gcd);
            }

            if (gcd < PERIOD_MIN_GCD_MS) {
                Slog.wtf(TAG, "found gcd less than min gcd");
                gcd = PERIOD_MIN_GCD_MS;
            }

            schedule.base_period_ms = gcd;
        } else {
            schedule.base_period_ms = DEFAULT_PERIOD_MS;
        }

        mSchedule = schedule;
    }

    /**
     * Add a scan to the most appropriate bucket, creating the bucket if necessary.
     */
    private void addScanToBuckets(ScanSettings settings) {
        int bucketIndex;

        if (settings.maxPeriodInMs != 0
                && settings.maxPeriodInMs != settings.periodInMs) {
            // exponential back off scan has a dedicated bucket
            bucketIndex = EXPONENTIAL_BACK_OFF_BUCKET_IDX;
        } else {
            bucketIndex = findBestRegularBucketIndex(settings.periodInMs,
                                                     NUM_OF_REGULAR_BUCKETS);
        }

        mBuckets.getOrCreate(bucketIndex).settings.add(settings);
    }

    /**
     * find closest bucket period to the requested period in all predefined buckets
     */
    private static int findBestRegularBucketIndex(int requestedPeriod, int maxNumBuckets) {
        maxNumBuckets = Math.min(maxNumBuckets, NUM_OF_REGULAR_BUCKETS);
        int index = -1;
        int minDiff = Integer.MAX_VALUE;
        for (int i = 0; i < maxNumBuckets; ++i) {
            int diff = Math.abs(PREDEFINED_BUCKET_PERIODS[i] - requestedPeriod);
            if (diff < minDiff) {
                minDiff = diff;
                index = i;
            }
        }
        if (index == -1) {
            Slog.wtf(TAG, "Could not find best bucket for period " + requestedPeriod + " in "
                     + maxNumBuckets + " buckets");
        }
        return index;
    }

    /**
     * Reduce the number of required buckets by reassigning lower priority buckets to the next
     * closest period bucket.
     */
    private void compactBuckets(int maxBuckets) {
        int maxRegularBuckets = maxBuckets;

        // reserve one bucket for exponential back off scan if there is
        // such request(s)
        if (mBuckets.isActive(EXPONENTIAL_BACK_OFF_BUCKET_IDX)) {
            maxRegularBuckets--;
        }
        for (int i = NUM_OF_REGULAR_BUCKETS - 1;
                i >= 0 && mBuckets.getActiveRegularBucketCount() > maxRegularBuckets; --i) {
            if (mBuckets.isActive(i)) {
                for (ScanSettings scanRequest : mBuckets.get(i).settings) {
                    int newBucketIndex = findBestRegularBucketIndex(scanRequest.periodInMs, i);
                    mBuckets.getOrCreate(newBucketIndex).settings.add(scanRequest);
                }
                mBuckets.clear(i);
            }
        }
    }
}
