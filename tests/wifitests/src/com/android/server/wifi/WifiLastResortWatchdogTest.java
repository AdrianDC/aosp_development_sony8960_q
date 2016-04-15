/*
 * Copyright (C) 2016 The Android Open Source Project
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiSsid;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Pair;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.WifiLastResortWatchdog}.
 */
@SmallTest
public class WifiLastResortWatchdogTest {
    WifiLastResortWatchdog mLastResortWatchdog;

    @Before
    public void setUp() throws Exception {
        mLastResortWatchdog = new WifiLastResortWatchdog();
    }

    private List<Pair<ScanDetail, WifiConfiguration>> createFilteredQnsCandidates(String[] ssids,
            String[] bssids, int[] frequencies, String[] caps, int[] levels,
            boolean[] isEphemeral) {
        List<Pair<ScanDetail, WifiConfiguration>> candidates = new ArrayList<>();
        long timeStamp = System.currentTimeMillis();
        for (int index = 0; index < ssids.length; index++) {
            ScanDetail scanDetail = new ScanDetail(WifiSsid.createFromAsciiEncoded(ssids[index]),
                    bssids[index], caps[index], levels[index], frequencies[index], timeStamp, 0);
            WifiConfiguration config = null;
            if (!isEphemeral[index]) {
                config = mock(WifiConfiguration.class);
                WifiConfiguration.NetworkSelectionStatus networkSelectionStatus =
                        mock(WifiConfiguration.NetworkSelectionStatus.class);
                when(config.getNetworkSelectionStatus()).thenReturn(networkSelectionStatus);
                when(networkSelectionStatus.getHasEverConnected()).thenReturn(true);
            }
            candidates.add(Pair.create(scanDetail, config));
        }
        return candidates;
    }

    /**
     * Case #1: Test aging works in available network buffering
     * This test simulates 4 networks appearing in a scan result, and then only the first 2
     * appearing in successive scans results.
     * Expected Behavior:
     * 4 networks appear in recentAvailalbeNetworks, after N=MAX_BSSID_AGE scans, only 2 remain
     */
    @Test
    public void testAvailableNetworkBuffering_ageCullingWorks() throws Exception {
        String[] ssids = {"\"test1\"", "\"test2\"", "\"test3\"", "\"test4\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "de:ad:ba:b1:e5:55",
                "c0:ff:ee:ee:e3:ee"};
        int[] frequencies = {2437, 5180, 5180, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]",
                "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-60, -86, -50, -62};
        boolean[] isEphemeral = {false, false, false, false};

        // Buffer potential candidates 1,2,3 & 4
        List<Pair<ScanDetail, WifiConfiguration>> candidates = createFilteredQnsCandidates(ssids,
                bssids, frequencies, caps, levels, isEphemeral);
        mLastResortWatchdog.updateAvailableNetworks(candidates);
        assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().size(), 4);

        // Repeatedly buffer candidates 1 & 2, MAX_BSSID_AGE - 1 times
        candidates = createFilteredQnsCandidates(Arrays.copyOfRange(ssids, 0, 2),
                Arrays.copyOfRange(bssids, 0, 2),
                Arrays.copyOfRange(frequencies, 0, 2),
                Arrays.copyOfRange(caps, 0, 2),
                Arrays.copyOfRange(levels, 0, 2),
                Arrays.copyOfRange(isEphemeral, 0, 2));
        for (int i = 0; i < WifiLastResortWatchdog.MAX_BSSID_AGE - 1; i++) {
            mLastResortWatchdog.updateAvailableNetworks(candidates);
            assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().get(bssids[0]).age, 0);
            assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().get(bssids[1]).age, 0);
            assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().get(bssids[2]).age, i+1);
            assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().get(bssids[3]).age, i+1);
        }
        assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().size(), 4);

        // One more buffering should age and cull candidates 2 & 3
        mLastResortWatchdog.updateAvailableNetworks(candidates);
        assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().size(), 2);
    };

    /**
     * Case #2: Culling of old networks
     * Part 1:
     * This test starts with 4 networks seen, it then buffers N=MAX_BSSID_AGE empty scans
     * Expected behaviour: All networks are culled from recentAvailableNetworks
     *
     * Part 2:
     * Buffer some more empty scans just to make sure nothing breaks
     */
    @Test
    public void testAvailableNetworkBuffering_emptyBufferWithEmptyScanResults() throws Exception {
        String[] ssids = {"\"test1\"", "\"test2\"", "\"test3\"", "\"test4\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "de:ad:ba:b1:e5:55",
                "c0:ff:ee:ee:e3:ee"};
        int[] frequencies = {2437, 5180, 5180, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]",
                "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-60, -86, -50, -62};
        boolean[] isEphemeral = {false, false, false, false};

        // Buffer potential candidates 1,2,3 & 4
        List<Pair<ScanDetail, WifiConfiguration>> candidates = createFilteredQnsCandidates(ssids,
                bssids, frequencies, caps, levels, isEphemeral);
        mLastResortWatchdog.updateAvailableNetworks(candidates);
        assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().size(), 4);

        // Repeatedly buffer with no candidates
        candidates = createFilteredQnsCandidates(Arrays.copyOfRange(ssids, 0, 0),
                Arrays.copyOfRange(bssids, 0, 0),
                Arrays.copyOfRange(frequencies, 0, 0),
                Arrays.copyOfRange(caps, 0, 0),
                Arrays.copyOfRange(levels, 0, 0),
                Arrays.copyOfRange(isEphemeral, 0, 0));
        for (int i = 0; i < WifiLastResortWatchdog.MAX_BSSID_AGE; i++) {
            mLastResortWatchdog.updateAvailableNetworks(candidates);
        }
        assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().size(), 0);
        for (int i = 0; i < WifiLastResortWatchdog.MAX_BSSID_AGE; i++) {
            mLastResortWatchdog.updateAvailableNetworks(candidates);
        }
        assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().size(), 0);
    };

    /**
     *  Case 3: Adding more networks over time
     *  In this test, each successive (4 total) scan result buffers one more network.
     *  Expected behavior: recentAvailableNetworks grows with number of scan results
     */
    @Test
    public void testAvailableNetworkBuffering_addNewNetworksOverTime() throws Exception {
        String[] ssids = {"\"test1\"", "\"test2\"", "\"test3\"", "\"test4\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "de:ad:ba:b1:e5:55",
                "c0:ff:ee:ee:e3:ee"};
        int[] frequencies = {2437, 5180, 5180, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]",
                "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-60, -86, -50, -62};
        boolean[] isEphemeral = {false, false, false, false};
        List<Pair<ScanDetail, WifiConfiguration>> candidates;
        // Buffer (i) scan results with each successive scan result
        for (int i = 1; i <= ssids.length; i++) {
            candidates = createFilteredQnsCandidates(Arrays.copyOfRange(ssids, 0, i),
                    Arrays.copyOfRange(bssids, 0, i),
                    Arrays.copyOfRange(frequencies, 0, i),
                    Arrays.copyOfRange(caps, 0, i),
                    Arrays.copyOfRange(levels, 0, i),
                    Arrays.copyOfRange(isEphemeral, 0, i));
            mLastResortWatchdog.updateAvailableNetworks(candidates);
            assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().size(), i);
            for (int j = 0; j < i; j++) {
                assertEquals(
                        mLastResortWatchdog.getRecentAvailableNetworks().get(bssids[j]).age, 0);
            }
        }
    };

    /**
     *  Case 4: Test buffering with ephemeral networks & toString()
     *  This test is the same as Case 1, but it also includes ephemeral networks. toString is also
     *  smoke tested at various places in this test
     *  Expected behaviour: 4 networks added initially (2 ephemeral). After MAX_BSSID_AGE more
     *  bufferings, 2 are culled (leaving 1 ephemeral, one normal). toString method should execute
     *  without breaking anything.
     */
    @Test
    public void testAvailableNetworkBuffering_multipleNetworksSomeEphemeral() throws Exception {
        String[] ssids = {"\"test1\"", "\"test2\"", "\"test3\"", "\"test4\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "de:ad:ba:b1:e5:55",
                "c0:ff:ee:ee:e3:ee"};
        int[] frequencies = {2437, 5180, 5180, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]",
                "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {-60, -86, -50, -62};
        boolean[] isEphemeral = {true, false, true, false};

        // Buffer potential candidates 1,2,3 & 4
        List<Pair<ScanDetail, WifiConfiguration>> candidates = createFilteredQnsCandidates(ssids,
                bssids, frequencies, caps, levels, isEphemeral);
        mLastResortWatchdog.updateAvailableNetworks(candidates);
        assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().size(), 4);

        // Repeatedly buffer candidates 1 & 2, MAX_BSSID_AGE - 1 times
        candidates = createFilteredQnsCandidates(Arrays.copyOfRange(ssids, 0, 2),
                Arrays.copyOfRange(bssids, 0, 2),
                Arrays.copyOfRange(frequencies, 0, 2),
                Arrays.copyOfRange(caps, 0, 2),
                Arrays.copyOfRange(levels, 0, 2),
                Arrays.copyOfRange(isEphemeral, 0, 2));
        for (int i = 0; i < WifiLastResortWatchdog.MAX_BSSID_AGE - 1; i++) {
            mLastResortWatchdog.updateAvailableNetworks(candidates);
            mLastResortWatchdog.toString();
            assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().get(bssids[0]).age, 0);
            assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().get(bssids[1]).age, 0);
            assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().get(bssids[2]).age, i+1);
            assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().get(bssids[3]).age, i+1);
        }
        assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().size(), 4);

        // One more buffering should age and cull candidates 2 & 3
        mLastResortWatchdog.updateAvailableNetworks(candidates);
        assertEquals(mLastResortWatchdog.getRecentAvailableNetworks().size(), 2);
        mLastResortWatchdog.toString();
    };
}
