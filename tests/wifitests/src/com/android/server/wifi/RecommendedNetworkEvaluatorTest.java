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

import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.WifiSsid;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.LocalLog;

import com.android.server.wifi.util.ScanResultUtil;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link RecommendedNetworkEvaluator}.
 */
@SmallTest
public class RecommendedNetworkEvaluatorTest {
    private static final ScanDetail TRUSTED_SCAN_DETAIL = buildScanDetail("ssid");
    private static final ScanDetail UNTRUSTED_SCAN_DETAIL = buildScanDetail("ssid1");
    private static final ScanDetail EPHEMERAL_SCAN_DETAIL = buildScanDetail("ssid2");
    private static final WifiConfiguration TRUSTED_WIFI_CONFIGURATION = new WifiConfiguration();
    private static final WifiConfiguration UNTRUSTED_WIFI_CONFIGURATION = new WifiConfiguration();
    private static final WifiConfiguration EPHEMERAL_WIFI_CONFIGURATION = new WifiConfiguration();
    static {
        TRUSTED_WIFI_CONFIGURATION.networkId = 5;
        TRUSTED_WIFI_CONFIGURATION.SSID = TRUSTED_SCAN_DETAIL.getSSID();
        TRUSTED_WIFI_CONFIGURATION.BSSID = TRUSTED_SCAN_DETAIL.getBSSIDString();
        TRUSTED_WIFI_CONFIGURATION.getNetworkSelectionStatus().setCandidate(
                TRUSTED_SCAN_DETAIL.getScanResult());

        UNTRUSTED_WIFI_CONFIGURATION.SSID = UNTRUSTED_SCAN_DETAIL.getSSID();
        UNTRUSTED_WIFI_CONFIGURATION.BSSID = UNTRUSTED_SCAN_DETAIL.getBSSIDString();

        EPHEMERAL_WIFI_CONFIGURATION.SSID = EPHEMERAL_SCAN_DETAIL.getSSID();
        EPHEMERAL_WIFI_CONFIGURATION.BSSID = EPHEMERAL_SCAN_DETAIL.getBSSIDString();
        EPHEMERAL_WIFI_CONFIGURATION.ephemeral = true;
    }

    @Mock private Context mContext;
    @Mock private ContentResolver mContentResolver;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private NetworkScoreManager mNetworkScoreManager;
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private WifiNetworkScoreCache mNetworkScoreCache;
    @Mock private ExternalScoreEvaluator mExternalScoreEvaluator;

    @Captor private ArgumentCaptor<NetworkKey[]> mNetworkKeyArrayCaptor;
    @Captor private ArgumentCaptor<RecommendationRequest> mRecommendationRequestCaptor;

    private RecommendedNetworkEvaluator mRecommendedNetworkEvaluator;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0))
                .thenReturn(1);
        mRecommendedNetworkEvaluator = new RecommendedNetworkEvaluator(mContext, mContentResolver,
                Looper.getMainLooper(), mFrameworkFacade, mNetworkScoreCache, mNetworkScoreManager,
                mWifiConfigManager, new LocalLog(0), mExternalScoreEvaluator);

        when(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(TRUSTED_SCAN_DETAIL))
                .thenReturn(TRUSTED_WIFI_CONFIGURATION);
        when(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(UNTRUSTED_SCAN_DETAIL))
                .thenReturn(null);
        when(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(EPHEMERAL_SCAN_DETAIL))
                .thenReturn(EPHEMERAL_WIFI_CONFIGURATION);
        when(mWifiConfigManager.getConfiguredNetwork(TRUSTED_WIFI_CONFIGURATION.networkId))
                .thenReturn(TRUSTED_WIFI_CONFIGURATION);
    }

    @Test
    public void testUpdate_recommendationsDisabled() {
        ArrayList<ScanDetail> scanDetails = new ArrayList<>();
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0))
                .thenReturn(0);

        mRecommendedNetworkEvaluator.mContentObserver.onChange(false /* unused */);

        mRecommendedNetworkEvaluator.update(scanDetails);

        verify(mExternalScoreEvaluator).update(scanDetails);
        verifyZeroInteractions(mNetworkScoreManager, mNetworkScoreManager);
    }

    @Test
    public void testUpdate_emptyScanList() {
        mRecommendedNetworkEvaluator.update(new ArrayList<ScanDetail>());

        verifyZeroInteractions(mNetworkScoreManager);
    }

    @Test
    public void testUpdate_allNetworksScored() {
        when(mNetworkScoreCache.isScoredNetwork(TRUSTED_SCAN_DETAIL.getScanResult()))
                .thenReturn(true);
        when(mNetworkScoreCache.isScoredNetwork(UNTRUSTED_SCAN_DETAIL.getScanResult()))
                .thenReturn(true);

        mRecommendedNetworkEvaluator.update(Lists.newArrayList(TRUSTED_SCAN_DETAIL,
                UNTRUSTED_SCAN_DETAIL));

        verifyZeroInteractions(mNetworkScoreManager);
    }

    @Test
    public void testUpdate_oneScored_oneUnscored() {
        when(mNetworkScoreCache.isScoredNetwork(TRUSTED_SCAN_DETAIL.getScanResult()))
                .thenReturn(true);
        when(mNetworkScoreCache.isScoredNetwork(UNTRUSTED_SCAN_DETAIL.getScanResult()))
                .thenReturn(false);

        mRecommendedNetworkEvaluator.update(Lists.newArrayList(TRUSTED_SCAN_DETAIL,
                UNTRUSTED_SCAN_DETAIL));

        verify(mNetworkScoreManager).requestScores(mNetworkKeyArrayCaptor.capture());

        assertEquals(1, mNetworkKeyArrayCaptor.getValue().length);
        NetworkKey expectedNetworkKey = new NetworkKey(new WifiKey(ScanResultUtil.createQuotedSSID(
                UNTRUSTED_SCAN_DETAIL.getSSID()), UNTRUSTED_SCAN_DETAIL.getBSSIDString()));
        assertEquals(expectedNetworkKey, mNetworkKeyArrayCaptor.getValue()[0]);
    }

    @Test
    public void testEvaluateNetworks_recommendationsDisabled() {
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0))
                .thenReturn(0);

        mRecommendedNetworkEvaluator.mContentObserver.onChange(false /* unused */);

        mRecommendedNetworkEvaluator.evaluateNetworks(null, null, null, false, false, null);

        verify(mExternalScoreEvaluator).evaluateNetworks(null, null, null, false, false, null);

        verifyZeroInteractions(mWifiConfigManager, mNetworkScoreManager);
    }

    @Test
    public void testEvaluateNetworks_emptyScanList() {
        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                new ArrayList<ScanDetail>(), null, null, false,
                false /* untrustedNetworkAllowed */, null);

        assertNull(result);
        verifyZeroInteractions(mWifiConfigManager, mNetworkScoreManager);
    }

    @Test
    public void testEvaluateNetworks_onlyTrustedNetworksAllowed_noTrustedInScanList() {
        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(UNTRUSTED_SCAN_DETAIL), null, null, false,
                false /* untrustedNetworkAllowed */, null);

        assertNull(result);
        verifyZeroInteractions(mNetworkScoreManager);
    }

    @Test
    public void testEvaluateNetworks_untrustedNetworksAllowed_onlyDeletedEphemeral() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(ScanResultUtil
                .createQuotedSSID(UNTRUSTED_SCAN_DETAIL.getScanResult().SSID)))
                .thenReturn(true);

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(UNTRUSTED_SCAN_DETAIL), null, null, false,
                true /* untrustedNetworkAllowed */, null);

        assertNull(result);
        verifyZeroInteractions(mNetworkScoreManager);
    }

    @Test
    public void testEvaluateNetworks_recommendation_onlyTrustedNetworkAllowed() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult
                    .createConnectRecommendation(TRUSTED_WIFI_CONFIGURATION));
        when(mWifiConfigManager.addOrUpdateNetwork(TRUSTED_WIFI_CONFIGURATION, Process.WIFI_UID))
                .thenReturn(new NetworkUpdateResult(TRUSTED_WIFI_CONFIGURATION.networkId));

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(TRUSTED_SCAN_DETAIL, UNTRUSTED_SCAN_DETAIL),
                null, null, false, false /* untrustedNetworkAllowed */, null);

        assertEquals(TRUSTED_WIFI_CONFIGURATION, result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(1, mRecommendationRequestCaptor.getValue().getScanResults().length);
        assertEquals(TRUSTED_SCAN_DETAIL.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[0]);
        verify(mWifiConfigManager).setNetworkCandidateScanResult(
                TRUSTED_WIFI_CONFIGURATION.networkId, TRUSTED_SCAN_DETAIL.getScanResult(), 0);
    }

    @Test
    public void testEvaluateNetworks_trustedRecommendation_untrustedNetworksAllowed() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult
                    .createConnectRecommendation(TRUSTED_WIFI_CONFIGURATION));

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(TRUSTED_SCAN_DETAIL, UNTRUSTED_SCAN_DETAIL),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        assertEquals(TRUSTED_WIFI_CONFIGURATION, result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(2, mRecommendationRequestCaptor.getValue().getScanResults().length);
        assertEquals(TRUSTED_SCAN_DETAIL.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[0]);
        assertEquals(UNTRUSTED_SCAN_DETAIL.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[1]);
        verify(mWifiConfigManager).setNetworkCandidateScanResult(
                TRUSTED_WIFI_CONFIGURATION.networkId, TRUSTED_SCAN_DETAIL.getScanResult(), 0);
    }

    @Test
    public void testEvaluateNetworks_untrustedRecommendation_untrustedNetworksAllowed() {
        NetworkUpdateResult networkUpdateResult = new NetworkUpdateResult(10);
        when(mWifiConfigManager.addOrUpdateNetwork(UNTRUSTED_WIFI_CONFIGURATION, Process.WIFI_UID))
                .thenReturn(networkUpdateResult);
        when(mWifiConfigManager.getConfiguredNetwork(networkUpdateResult.getNetworkId()))
                .thenReturn(UNTRUSTED_WIFI_CONFIGURATION);
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult
                    .createConnectRecommendation(UNTRUSTED_WIFI_CONFIGURATION));

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(TRUSTED_SCAN_DETAIL, UNTRUSTED_SCAN_DETAIL),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        assertEquals(UNTRUSTED_WIFI_CONFIGURATION, result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(2, mRecommendationRequestCaptor.getValue().getScanResults().length);
        assertEquals(TRUSTED_SCAN_DETAIL.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[0]);
        assertEquals(UNTRUSTED_SCAN_DETAIL.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[1]);
        verify(mWifiConfigManager).setNetworkCandidateScanResult(
                networkUpdateResult.getNetworkId(), UNTRUSTED_SCAN_DETAIL.getScanResult(), 0);
    }

    @Test
    public void testEvaluateNetworks_untrustedRecommendation_updateFailed() {
        NetworkUpdateResult networkUpdateResult = new NetworkUpdateResult(
                WifiConfiguration.INVALID_NETWORK_ID);
        when(mWifiConfigManager.addOrUpdateNetwork(UNTRUSTED_WIFI_CONFIGURATION, Process.WIFI_UID))
                .thenReturn(networkUpdateResult);
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult
                    .createConnectRecommendation(UNTRUSTED_WIFI_CONFIGURATION));

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(TRUSTED_SCAN_DETAIL, UNTRUSTED_SCAN_DETAIL),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        assertNull(result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(2, mRecommendationRequestCaptor.getValue().getScanResults().length);
        assertEquals(TRUSTED_SCAN_DETAIL.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[0]);
        assertEquals(UNTRUSTED_SCAN_DETAIL.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[1]);
        verify(mWifiConfigManager, never())
                .setNetworkCandidateScanResult(anyInt(), any(ScanResult.class), anyInt());
    }

    @Test
    public void testEvaluateNetworks_doNotConnectRecommendation_untrustedNetworksAllowed() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult.createDoNotConnectRecommendation());

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(TRUSTED_SCAN_DETAIL, UNTRUSTED_SCAN_DETAIL),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        assertNull(result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(2, mRecommendationRequestCaptor.getValue().getScanResults().length);
        assertEquals(TRUSTED_SCAN_DETAIL.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[0]);
        assertEquals(UNTRUSTED_SCAN_DETAIL.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[1]);
    }

    @Test
    public void testEvaluateNetworks_nullRecommendation() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(null);

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(TRUSTED_SCAN_DETAIL, UNTRUSTED_SCAN_DETAIL),
                null, null, false, false /* untrustedNetworkAllowed */, null);

        assertNull(result);
        verify(mNetworkScoreManager).requestRecommendation(any(RecommendationRequest.class));
    }

    @Test
    public void testEvaluateNetworks_requestContainsCurrentNetwork() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult.createDoNotConnectRecommendation());

        mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(TRUSTED_SCAN_DETAIL, UNTRUSTED_SCAN_DETAIL),
                TRUSTED_WIFI_CONFIGURATION, null, false, true /* untrustedNetworkAllowed */, null);

        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertSame(TRUSTED_WIFI_CONFIGURATION,
                mRecommendationRequestCaptor.getValue().getConnectedConfig());
    }

    @Test
    public void testEvaluateNetworks_requestConnectableNetworks() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult.createDoNotConnectRecommendation());

        mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(TRUSTED_SCAN_DETAIL, UNTRUSTED_SCAN_DETAIL,
                        EPHEMERAL_SCAN_DETAIL),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        RecommendationRequest request = mRecommendationRequestCaptor.getValue();
        assertEquals(2, request.getConnectableConfigs().length);
        List<String> ssids = new ArrayList<>();
        for (WifiConfiguration config : request.getConnectableConfigs()) {
            ssids.add(config.SSID);
        }
        List<String> expectedSsids = new ArrayList<>();
        expectedSsids.add(TRUSTED_WIFI_CONFIGURATION.SSID);
        expectedSsids.add(EPHEMERAL_WIFI_CONFIGURATION.SSID);
        assertEquals(expectedSsids, ssids);
    }

    @Test
    public void testEvaluateNetworks_scanResultMarkedAsUntrusted_configIsEphemeral() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult.createDoNotConnectRecommendation());

        mRecommendedNetworkEvaluator.evaluateNetworks(Lists.newArrayList(EPHEMERAL_SCAN_DETAIL),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        RecommendationRequest request = mRecommendationRequestCaptor.getValue();
        assertEquals(1, request.getScanResults().length);
        assertTrue(request.getScanResults()[0].untrusted);
    }

    @Test
    public void testEvaluateNetworks_requestContainsLastSelectedNetwork() {
        int lastSelectedNetworkId = 5;
        long lastSelectedTimestamp = 1000;
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(lastSelectedNetworkId);
        when(mWifiConfigManager.getLastSelectedTimeStamp()).thenReturn(lastSelectedTimestamp);

        mRecommendedNetworkEvaluator.evaluateNetworks(Lists.newArrayList(TRUSTED_SCAN_DETAIL),
                null, null, false, false /* untrustedNetworkAllowed */, null);

        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        RecommendationRequest request = mRecommendationRequestCaptor.getValue();
        assertEquals(lastSelectedNetworkId, request.getLastSelectedNetworkId());
        assertEquals(lastSelectedTimestamp, request.getLastSelectedNetworkTimestamp());
    }

    private static ScanDetail buildScanDetail(String ssid) {
        return new ScanDetail(WifiSsid.createFromAsciiEncoded(ssid),
                "00:00:00:00:00:00", "", 0, 0, 0, 0);
    }
}
