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

package com.android.server.wifi.hotspot2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Pair;

import com.android.server.wifi.NetworkUpdateResult;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.util.ScanResultUtil;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.PasspointNetworkEvaluator}.
 */
@SmallTest
public class PasspointNetworkEvaluatorTest {
    private static final int TEST_NETWORK_ID = 1;
    private static final String TEST_SSID1 = "ssid1";
    private static final String TEST_SSID2 = "ssid2";
    private static final String TEST_FQDN1 = "test1.com";
    private static final String TEST_FQDN2 = "test2.com";
    private static final WifiConfiguration TEST_CONFIG1 = generateWifiConfig(TEST_FQDN1);
    private static final WifiConfiguration TEST_CONFIG2 = generateWifiConfig(TEST_FQDN2);
    private static final PasspointProvider TEST_PROVIDER1 = generateProvider(TEST_CONFIG1);
    private static final PasspointProvider TEST_PROVIDER2 = generateProvider(TEST_CONFIG2);

    @Mock PasspointManager mPasspointManager;
    @Mock WifiConfigManager mWifiConfigManager;
    PasspointNetworkEvaluator mEvaluator;

    /**
     * Helper function for generating {@link WifiConfiguration} for testing.
     *
     * @param fqdn The FQDN associated with the configuration
     * @return {@link WifiConfiguration}
     */
    private static WifiConfiguration generateWifiConfig(String fqdn) {
        WifiConfiguration config = new WifiConfiguration();
        config.FQDN = fqdn;
        return config;
    }

    /**
     * Helper function for generating {@link PasspointProvider} for testing.
     *
     * @param config The WifiConfiguration associated with the provider
     * @return {@link PasspointProvider}
     */
    private static PasspointProvider generateProvider(WifiConfiguration config) {
        PasspointProvider provider = mock(PasspointProvider.class);
        when(provider.getWifiConfig()).thenReturn(config);
        return provider;
    }

    /**
     * Helper function for generating {@link ScanDetail} for testing.
     *
     * @param ssid The SSID associated with the scan
     * @param rssiLevel The RSSI level associated with the scan
     * @return {@link ScanDetail}
     */
    private static ScanDetail generateScanDetail(String ssid) {
        NetworkDetail networkDetail = mock(NetworkDetail.class);
        when(networkDetail.isInterworking()).thenReturn(true);
        when(networkDetail.getAnt()).thenReturn(NetworkDetail.Ant.FreePublic);

        ScanDetail scanDetail = mock(ScanDetail.class);
        when(scanDetail.getSSID()).thenReturn(ssid);
        when(scanDetail.getScanResult()).thenReturn(new ScanResult());
        when(scanDetail.getNetworkDetail()).thenReturn(networkDetail);
        return scanDetail;
    }

    /**
     * Test setup.
     */
    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mEvaluator = new PasspointNetworkEvaluator(mPasspointManager, mWifiConfigManager, null);
    }

    /**
     * Verify that null will be returned when evaluating scans without any matching providers.
     *
     * @throws Exception
     */
    @Test
    public void evaluateScansWithNoMatch() throws Exception {
        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {
                generateScanDetail(TEST_SSID1), generateScanDetail(TEST_SSID2)});
        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        List<Pair<PasspointProvider, PasspointMatch>> matchedProviders = new ArrayList<>();
        when(mPasspointManager.matchProvider(any(ScanDetail.class))).thenReturn(matchedProviders);
        assertEquals(null, mEvaluator.evaluateNetworks(
                scanDetails, null, null, false, false, connectableNetworks));
        assertTrue(connectableNetworks.isEmpty());
    }

    /**
     * Verify that provider matching will not be performed when evaluating scans with no
     * interworking support, and null will be returned.
     *
     * @throws Exception
     */
    @Test
    public void evaulateScansWithNoInterworkingAP() throws Exception {
        NetworkDetail networkDetail = mock(NetworkDetail.class);
        when(networkDetail.isInterworking()).thenReturn(false);
        ScanDetail scanDetail = mock(ScanDetail.class);
        when(scanDetail.getNetworkDetail()).thenReturn(networkDetail);

        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {scanDetail});
        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        assertEquals(null, mEvaluator.evaluateNetworks(
                scanDetails, null, null, false, false, connectableNetworks));
        assertTrue(connectableNetworks.isEmpty());
        // Verify that no provider matching is performed.
        verify(mPasspointManager, never()).matchProvider(any(ScanDetail.class));
    }

    /**
     * Verify that when both home provider and roaming provider is found for the same network,
     * home provider is preferred.
     *
     * @throws Exception
     */
    @Test
    public void evaluateScansWithNetworkMatchingHomeAndRoamingProvider() throws Exception {
        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {
                generateScanDetail(TEST_SSID1), generateScanDetail(TEST_SSID2)});

        // Setup matching providers for ScanDetail with TEST_SSID1.
        Pair<PasspointProvider, PasspointMatch> homeProvider = Pair.create(
                TEST_PROVIDER1, PasspointMatch.HomeProvider);
        Pair<PasspointProvider, PasspointMatch> roamingProvider = Pair.create(
                TEST_PROVIDER2, PasspointMatch.RoamingProvider);
        List<Pair<PasspointProvider, PasspointMatch>> matchedProviders = new ArrayList<>();
        matchedProviders.add(homeProvider);
        matchedProviders.add(roamingProvider);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();

        // Return matchedProviders for the first ScanDetail (TEST_SSID1) and an empty list for
        // for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanDetail.class))).thenReturn(matchedProviders)
                .thenReturn(new ArrayList<Pair<PasspointProvider, PasspointMatch>>());
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        assertNotNull(mEvaluator.evaluateNetworks(scanDetails, null, null, false,
                false, connectableNetworks));
        assertEquals(1, connectableNetworks.size());

        // Verify the content of the WifiConfiguration that was added to WifiConfigManager.
        ArgumentCaptor<WifiConfiguration> addedConfig =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mWifiConfigManager).addOrUpdateNetwork(addedConfig.capture(), anyInt());
        assertEquals(ScanResultUtil.createQuotedSSID(TEST_SSID1), addedConfig.getValue().SSID);
        assertEquals(TEST_FQDN1, addedConfig.getValue().FQDN);
    }

    /**
     * Verify that when a network matches a roaming provider is found, the correct network
     * information (WifiConfiguration) is setup and returned.
     *
     * @throws Exception
     */
    @Test
    public void evaluateScansWithNetworkMatchingRoamingProvider() throws Exception {
        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {
                generateScanDetail(TEST_SSID1), generateScanDetail(TEST_SSID2)});

        // Setup matching providers for ScanDetail with TEST_SSID1.
        Pair<PasspointProvider, PasspointMatch> roamingProvider = Pair.create(
                TEST_PROVIDER1, PasspointMatch.RoamingProvider);
        List<Pair<PasspointProvider, PasspointMatch>> matchedProviders = new ArrayList<>();
        matchedProviders.add(roamingProvider);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();

        // Return matchedProviders for the first ScanDetail (TEST_SSID1) and an empty list for
        // for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanDetail.class))).thenReturn(matchedProviders)
                .thenReturn(new ArrayList<Pair<PasspointProvider, PasspointMatch>>());
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        assertNotNull(mEvaluator.evaluateNetworks(scanDetails, null, null, false,
                false, connectableNetworks));
        assertEquals(1, connectableNetworks.size());

        // Verify the content of the WifiConfiguration that was added to WifiConfigManager.
        ArgumentCaptor<WifiConfiguration> addedConfig =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mWifiConfigManager).addOrUpdateNetwork(addedConfig.capture(), anyInt());
        assertEquals(ScanResultUtil.createQuotedSSID(TEST_SSID1), addedConfig.getValue().SSID);
        assertEquals(TEST_FQDN1, addedConfig.getValue().FQDN);
    }

    /**
     * Verify that when a network matches a home provider and another network matches a roaming
     * provider are found, the network that matched to a home provider is preferred.
     *
     * @throws Exception
     */
    @Test
    public void evaluateScansWithHomeProviderNewtorkAndRoamingProviderNetwork() throws Exception {
        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {
                generateScanDetail(TEST_SSID1), generateScanDetail(TEST_SSID2)});

        // Setup matching providers for ScanDetail with TEST_SSID1.
        Pair<PasspointProvider, PasspointMatch> homeProvider = Pair.create(
                TEST_PROVIDER1, PasspointMatch.HomeProvider);
        Pair<PasspointProvider, PasspointMatch> roamingProvider = Pair.create(
                TEST_PROVIDER2, PasspointMatch.RoamingProvider);
        List<Pair<PasspointProvider, PasspointMatch>> providerForScanDetail1 = new ArrayList<>();
        providerForScanDetail1.add(homeProvider);
        List<Pair<PasspointProvider, PasspointMatch>> providerForScanDetail2 = new ArrayList<>();
        providerForScanDetail2.add(roamingProvider);

        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();

        // Return providerForScanDetail1 for the first ScanDetail (TEST_SSID1) and
        // providerForScanDetail2 for the second (TEST_SSID2);
        when(mPasspointManager.matchProvider(any(ScanDetail.class)))
                .thenReturn(providerForScanDetail1).thenReturn(providerForScanDetail2);
        when(mWifiConfigManager.addOrUpdateNetwork(any(WifiConfiguration.class), anyInt()))
                .thenReturn(new NetworkUpdateResult(TEST_NETWORK_ID));
        when(mWifiConfigManager.getConfiguredNetwork(TEST_NETWORK_ID)).thenReturn(TEST_CONFIG1);
        assertNotNull(mEvaluator.evaluateNetworks(scanDetails, null, null, false,
                false, connectableNetworks));
        assertEquals(1, connectableNetworks.size());

        // Verify the content of the WifiConfiguration that was added to WifiConfigManager.
        ArgumentCaptor<WifiConfiguration> addedConfig =
                ArgumentCaptor.forClass(WifiConfiguration.class);
        verify(mWifiConfigManager).addOrUpdateNetwork(addedConfig.capture(), anyInt());
        assertEquals(ScanResultUtil.createQuotedSSID(TEST_SSID1), addedConfig.getValue().SSID);
        assertEquals(TEST_FQDN1, addedConfig.getValue().FQDN);
    }

    /**
     * Verify that when two networks both matches a home provider, with one of them being the
     * active network, the active network is preferred.
     *
     * @throws Exception
     */
    @Test
    public void evaluateScansWithActiveNetworkMatchingHomeProvider() throws Exception {
        List<ScanDetail> scanDetails = Arrays.asList(new ScanDetail[] {
                generateScanDetail(TEST_SSID1), generateScanDetail(TEST_SSID2)});

        // Setup matching providers for both ScanDetail.
        Pair<PasspointProvider, PasspointMatch> homeProvider = Pair.create(
                TEST_PROVIDER1, PasspointMatch.HomeProvider);
        List<Pair<PasspointProvider, PasspointMatch>> matchedProviders = new ArrayList<>();
        matchedProviders.add(homeProvider);

        // Setup currently connected network
        WifiConfiguration currentNetwork = new WifiConfiguration();
        currentNetwork.networkId = TEST_NETWORK_ID;
        currentNetwork.SSID = ScanResultUtil.createQuotedSSID(TEST_SSID2);
        String currentBssid = "12:23:34:45:12:0F";

        // Returning the same matching provider for both ScanDetail.
        List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks = new ArrayList<>();
        when(mPasspointManager.matchProvider(any(ScanDetail.class)))
                .thenReturn(matchedProviders).thenReturn(matchedProviders);
        WifiConfiguration config = mEvaluator.evaluateNetworks(scanDetails, currentNetwork,
                currentBssid, true, false, connectableNetworks);
        assertEquals(1, connectableNetworks.size());

        // Verify no new network is added to WifiConfigManager.
        verify(mWifiConfigManager, never()).addOrUpdateNetwork(
                any(WifiConfiguration.class), anyInt());

        // Verify current active network is returned.
        assertEquals(ScanResultUtil.createQuotedSSID(TEST_SSID2), config.SSID);
        assertEquals(TEST_NETWORK_ID, config.networkId);
    }
}
