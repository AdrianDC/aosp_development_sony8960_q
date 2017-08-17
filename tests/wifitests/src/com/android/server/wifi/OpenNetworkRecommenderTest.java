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

package com.android.server.wifi;

import static org.junit.Assert.assertEquals;

import android.net.wifi.ScanResult;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link OpenNetworkRecommender}.
 */
public class OpenNetworkRecommenderTest {

    private static final String TEST_SSID_1 = "Test SSID 1";
    private static final String TEST_SSID_2 = "Test SSID 2";
    private static final int MIN_RSSI_LEVEL = -127;

    private OpenNetworkRecommender mOpenNetworkRecommender;

    @Before
    public void setUp() throws Exception {
        mOpenNetworkRecommender = new OpenNetworkRecommender();
    }

    private List<ScanDetail> createOpenScanResults(String... ssids) {
        List<ScanDetail> scanResults = new ArrayList<>();
        for (String ssid : ssids) {
            ScanResult scanResult = new ScanResult();
            scanResult.SSID = ssid;
            scanResult.capabilities = "[ESS]";
            scanResults.add(new ScanDetail(scanResult, null /* networkDetail */));
        }
        return scanResults;
    }

    /** If list of open networks contain only one network, that network should be returned. */
    @Test
    public void onlyNetworkIsRecommended() {
        List<ScanDetail> scanResults = createOpenScanResults(TEST_SSID_1);
        scanResults.get(0).getScanResult().level = MIN_RSSI_LEVEL;

        ScanResult actual = mOpenNetworkRecommender.recommendNetwork(scanResults, null);
        ScanResult expected = scanResults.get(0).getScanResult();
        assertEquals(expected, actual);
    }

    /** Verifies that the network with the highest rssi is recommended. */
    @Test
    public void networkWithHighestRssiIsRecommended() {
        List<ScanDetail> scanResults = createOpenScanResults(TEST_SSID_1, TEST_SSID_2);
        scanResults.get(0).getScanResult().level = MIN_RSSI_LEVEL;
        scanResults.get(1).getScanResult().level = MIN_RSSI_LEVEL + 1;

        ScanResult actual = mOpenNetworkRecommender.recommendNetwork(scanResults, null);
        ScanResult expected = scanResults.get(1).getScanResult();
        assertEquals(expected, actual);
    }

    /**
     * If the current recommended network is present in the list for the next recommendation and has
     * an equal RSSI, the recommendation should not change.
     */
    @Test
    public void currentRecommendationHasEquallyHighRssi_shouldNotChangeRecommendation() {
        List<ScanDetail> scanResults = createOpenScanResults(TEST_SSID_1, TEST_SSID_2);
        scanResults.get(0).getScanResult().level = MIN_RSSI_LEVEL + 1;
        scanResults.get(1).getScanResult().level = MIN_RSSI_LEVEL + 1;

        ScanResult currentRecommendation = new ScanResult(scanResults.get(1).getScanResult());
        // next recommendation does not depend on the rssi of the input recommendation.
        currentRecommendation.level = MIN_RSSI_LEVEL;

        ScanResult expected = scanResults.get(1).getScanResult();
        ScanResult actual = mOpenNetworkRecommender.recommendNetwork(
                scanResults, currentRecommendation);
        assertEquals(expected, actual);
    }
}
