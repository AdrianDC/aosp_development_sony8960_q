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

import static com.android.server.wifi.ScanTestUtil.channelsToSpec;
import static com.android.server.wifi.ScanTestUtil.createRequest;
import static com.android.server.wifi.ScanTestUtil.createScanDatas;
import static com.android.server.wifi.ScanTestUtil.createScanResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiScanner.ScanSettings;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for filtering of scan results in
 * {@link com.android.server.wifi.scanner.ScanScheduleUtil}.
 */
@SmallTest
public class ScanScheduleUtilFilterTest {

    private ChannelHelper mChannelHelper;

    @Before
    public void setUp() throws Exception {
        mChannelHelper = new PresetKnownBandsChannelHelper(
                new int[]{2400, 2450},
                new int[]{5150, 5175},
                new int[]{5600, 5650});
    }

    @Test
    public void reportFullResultTrueForBands() {
        ScanSettings settings = createRequest(
                WifiScanner.WIFI_BAND_24_GHZ, 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        assertTrue(ScanScheduleUtil.shouldReportFullScanResultForSettings(mChannelHelper,
                        createScanResult(2400), settings));
    }

    @Test
    public void reportFullResultFalseForBands() {
        ScanSettings settings = createRequest(
                WifiScanner.WIFI_BAND_24_GHZ, 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        assertFalse(ScanScheduleUtil.shouldReportFullScanResultForSettings(mChannelHelper,
                createScanResult(5150), settings));
    }

    @Test
    public void reportFullResultTrueForChannels() {
        ScanSettings settings = createRequest(
                channelsToSpec(2400, 5150), 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        assertTrue(ScanScheduleUtil.shouldReportFullScanResultForSettings(mChannelHelper,
                createScanResult(2400), settings));
    }

    @Test
    public void reportFullResultFalseForChannels() {
        ScanSettings settings = createRequest(
                channelsToSpec(2400, 5150), 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        assertFalse(ScanScheduleUtil.shouldReportFullScanResultForSettings(mChannelHelper,
                createScanResult(5175), settings));
    }

    @Test
    public void filterScanDataEmpty() {
        ScanSettings settings = createRequest(
                channelsToSpec(2400, 5150), 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        ScanData[] results = ScanScheduleUtil.filterResultsForSettings(mChannelHelper,
                new ScanData[0], settings);
        assertScanDataFreqsEquals(null, results);
    }

    @Test
    public void filterScanDataSingleNotMatching() {
        ScanSettings settings = createRequest(
                channelsToSpec(2400, 5150), 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        ScanData[] results = ScanScheduleUtil.filterResultsForSettings(mChannelHelper,
                createScanDatas(new int[][]{ { 2450 } }), settings);
        assertScanDataFreqsEquals(null, results);
    }

    @Test
    public void filterScanDataSingleMatching() {
        ScanSettings settings = createRequest(
                channelsToSpec(2400, 5150), 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        ScanData[] results = ScanScheduleUtil.filterResultsForSettings(mChannelHelper,
                createScanDatas(new int[][]{ { 2400 } }), settings);

        assertScanDataFreqsEquals(new int[][]{ { 2400 } }, results);
    }

    @Test
    public void filterScanDataSinglePartialMatching() {
        ScanSettings settings = createRequest(
                channelsToSpec(2400, 5150), 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        ScanData[] results = ScanScheduleUtil.filterResultsForSettings(mChannelHelper,
                createScanDatas(new int[][]{ { 2400, 2450, 5150, 5175 } }), settings);

        assertScanDataFreqsEquals(new int[][]{ { 2400, 5150 } }, results);
    }

    @Test
    public void filterScanDataMultipleNotMatching() {
        ScanSettings settings = createRequest(
                channelsToSpec(2400, 5150), 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        ScanData[] results = ScanScheduleUtil.filterResultsForSettings(mChannelHelper,
                createScanDatas(new int[][]{ { 2450 }, { 2450, 5175 } }), settings);
        assertScanDataFreqsEquals(null, results);
    }

    @Test
    public void filterScanDataMultipleMatching() {
        ScanSettings settings = createRequest(
                channelsToSpec(2400, 5150), 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        ScanData[] results = ScanScheduleUtil.filterResultsForSettings(mChannelHelper,
                createScanDatas(new int[][]{ { 2400 }, {2400, 5150} }), settings);

        assertScanDataFreqsEquals(new int[][]{ { 2400 }, {2400, 5150} }, results);
    }

    @Test
    public void filterScanDataMultiplePartialMatching() {
        ScanSettings settings = createRequest(
                channelsToSpec(2400, 5150), 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        ScanData[] results = ScanScheduleUtil.filterResultsForSettings(mChannelHelper,
                createScanDatas(new int[][]{ { 2400, 2450, 5150, 5175 }, { 2400, 2450, 5175 } }),
                settings);

        assertScanDataFreqsEquals(new int[][]{ { 2400, 5150 }, { 2400 } }, results);
    }

    @Test
    public void filterScanDataMultipleDuplicateFrequencies() {
        ScanSettings settings = createRequest(
                channelsToSpec(2400, 5150), 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        ScanData[] results = ScanScheduleUtil.filterResultsForSettings(mChannelHelper,
                createScanDatas(new int[][]{
                        { 2400, 2450, 5150, 5175, 2400 },
                        { 2400, 2450, 5175 },
                        { 5175, 5175, 5150 } }), settings);

        assertScanDataFreqsEquals(new int[][]{ { 2400, 5150, 2400 }, { 2400 }, { 5150 } }, results);
    }

    @Test
    public void filterScanDataMultipleSomeNotMatching() {
        ScanSettings settings = createRequest(
                channelsToSpec(2400, 5150), 30000, 0, 20,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        ScanData[] results = ScanScheduleUtil.filterResultsForSettings(mChannelHelper,
                createScanDatas(new int[][]{
                        { 2400, 2450, 5150, 5175, 2400 },
                        { 5175 },
                        { 5175, 5175, 5150 } }), settings);

        assertScanDataFreqsEquals(new int[][]{ { 2400, 5150, 2400 }, { 5150 } }, results);
    }

    @Test
    public void filterScanDataExceedMaxBssidsPerScan() {
        ScanSettings settings = createRequest(
                channelsToSpec(2400, 5150), 30000, 0, 3,
                WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
        );

        ScanData[] results = ScanScheduleUtil.filterResultsForSettings(mChannelHelper,
                createScanDatas(new int[][]{
                        { 2400, 2450, 5150, 5175, 2400, 2400},
                        { 5175 },
                        { 5175, 5175, 5150, 2400, 2400, 5150 } }), settings);

        assertScanDataFreqsEquals(new int[][]{ { 2400, 5150, 2400 }, { 5150, 2400, 2400 } },
                results);
    }


    private static void assertScanDataFreqsEquals(int[][] expected, ScanData[] results) {
        if (expected == null) {
            assertNull(results);
        } else {
            assertNotNull(results);
            assertEquals("num scans", expected.length, results.length);
            for (int i = 0; i < expected.length; ++i) {
                assertNotNull("scan[" + i + "] was null", results[i]);
                assertEquals("num aps in scan[" + i + "]", expected[i].length,
                        results[i].getResults().length);
                for (int j = 0; j < expected[i].length; ++j) {
                    assertNotNull("ap result[" + i + "][" + j + "] was null",
                            results[i].getResults()[j]);
                    assertEquals("ap freq in result[" + i + "][" + j + "]", expected[i][j],
                            results[i].getResults()[j].frequency);
                }
            }
        }
    }
}
