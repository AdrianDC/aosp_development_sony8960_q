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
import android.util.Pair;

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
    private ScanDetail mTrustedScanDetail;
    private ScanDetail mUntrustedScanDetail;
    private ScanDetail mEphemeralScanDetail;
    private WifiConfiguration mTrustedWifiConfiguration;
    private WifiConfiguration mUntrustedWifiConfiguration;
    private WifiConfiguration mEphemeralWifiConfiguration;

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
        mTrustedScanDetail = buildScanDetail("ssid");
        mUntrustedScanDetail = buildScanDetail("ssid1");
        mEphemeralScanDetail = buildScanDetail("ssid2");

        mTrustedWifiConfiguration = new WifiConfiguration();
        mTrustedWifiConfiguration.networkId = 5;
        mTrustedWifiConfiguration.SSID = mTrustedScanDetail.getSSID();
        mTrustedWifiConfiguration.BSSID = mTrustedScanDetail.getBSSIDString();
        mTrustedWifiConfiguration.getNetworkSelectionStatus().setCandidate(
                mTrustedScanDetail.getScanResult());

        mUntrustedWifiConfiguration = new WifiConfiguration();
        mUntrustedWifiConfiguration.SSID = mUntrustedScanDetail.getSSID();
        mUntrustedWifiConfiguration.BSSID = mUntrustedScanDetail.getBSSIDString();

        mEphemeralWifiConfiguration = new WifiConfiguration();
        mEphemeralWifiConfiguration.SSID = mEphemeralScanDetail.getSSID();
        mEphemeralWifiConfiguration.BSSID = mEphemeralScanDetail.getBSSIDString();
        mEphemeralWifiConfiguration.ephemeral = true;

        MockitoAnnotations.initMocks(this);

        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0))
                .thenReturn(1);
        mRecommendedNetworkEvaluator = new RecommendedNetworkEvaluator(mContext, mContentResolver,
                Looper.getMainLooper(), mFrameworkFacade, mNetworkScoreCache, mNetworkScoreManager,
                mWifiConfigManager, new LocalLog(0), mExternalScoreEvaluator);

        when(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(mTrustedScanDetail))
                .thenReturn(mTrustedWifiConfiguration);
        when(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(mUntrustedScanDetail))
                .thenReturn(null);
        when(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(mEphemeralScanDetail))
                .thenReturn(mEphemeralWifiConfiguration);
        when(mWifiConfigManager.getConfiguredNetwork(mTrustedWifiConfiguration.networkId))
                .thenReturn(mTrustedWifiConfiguration);
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
        when(mNetworkScoreCache.isScoredNetwork(mTrustedScanDetail.getScanResult()))
                .thenReturn(true);
        when(mNetworkScoreCache.isScoredNetwork(mUntrustedScanDetail.getScanResult()))
                .thenReturn(true);

        mRecommendedNetworkEvaluator.update(Lists.newArrayList(mTrustedScanDetail,
                mUntrustedScanDetail));

        verifyZeroInteractions(mNetworkScoreManager);
    }

    @Test
    public void testUpdate_oneScored_oneUnscored() {
        when(mNetworkScoreCache.isScoredNetwork(mTrustedScanDetail.getScanResult()))
                .thenReturn(true);
        when(mNetworkScoreCache.isScoredNetwork(mUntrustedScanDetail.getScanResult()))
                .thenReturn(false);

        mRecommendedNetworkEvaluator.update(Lists.newArrayList(mTrustedScanDetail,
                mUntrustedScanDetail));

        verify(mNetworkScoreManager).requestScores(mNetworkKeyArrayCaptor.capture());

        assertEquals(1, mNetworkKeyArrayCaptor.getValue().length);
        NetworkKey expectedNetworkKey = new NetworkKey(new WifiKey(ScanResultUtil.createQuotedSSID(
                mUntrustedScanDetail.getSSID()), mUntrustedScanDetail.getBSSIDString()));
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
                Lists.newArrayList(mUntrustedScanDetail), null, null, false,
                false /* untrustedNetworkAllowed */, null);

        assertNull(result);
        verifyZeroInteractions(mNetworkScoreManager);
    }

    @Test
    public void testEvaluateNetworks_untrustedNetworksAllowed_onlyDeletedEphemeral() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(ScanResultUtil
                .createQuotedSSID(mUntrustedScanDetail.getScanResult().SSID)))
                .thenReturn(true);

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(mUntrustedScanDetail), null, null, false,
                true /* untrustedNetworkAllowed */, null);

        assertNull(result);
        verifyZeroInteractions(mNetworkScoreManager);
    }

    @Test
    public void testEvaluateNetworks_recommendation_onlyTrustedNetworkAllowed() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult
                    .createConnectRecommendation(mTrustedWifiConfiguration));

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(mTrustedScanDetail, mUntrustedScanDetail),
                null, null, false, false /* untrustedNetworkAllowed */, null);

        assertEquals(mTrustedWifiConfiguration, result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(1, mRecommendationRequestCaptor.getValue().getScanResults().length);
        assertEquals(mTrustedScanDetail.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[0]);
        verify(mWifiConfigManager).setNetworkCandidateScanResult(
                mTrustedWifiConfiguration.networkId, mTrustedScanDetail.getScanResult(), 0);
    }

    @Test
    public void testEvaluateNetworks_trustedRecommendation_untrustedNetworksAllowed() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult
                    .createConnectRecommendation(mTrustedWifiConfiguration));

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(mTrustedScanDetail, mUntrustedScanDetail),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        assertEquals(mTrustedWifiConfiguration, result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(2, mRecommendationRequestCaptor.getValue().getScanResults().length);
        assertEquals(mTrustedScanDetail.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[0]);
        assertEquals(mUntrustedScanDetail.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[1]);
        verify(mWifiConfigManager).setNetworkCandidateScanResult(
                mTrustedWifiConfiguration.networkId, mTrustedScanDetail.getScanResult(), 0);
    }

    @Test
    public void testEvaluateNetworks_trustedRecommendation_anyBssidSpecified() {
        WifiConfiguration recommendedNetwork = new WifiConfiguration();
        recommendedNetwork.SSID = mTrustedWifiConfiguration.SSID;
        recommendedNetwork.BSSID = "any";

        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult.createConnectRecommendation(recommendedNetwork));

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(mTrustedScanDetail),
                null, null, false, false /* untrustedNetworkAllowed */, null);

        assertEquals(mTrustedWifiConfiguration, result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(1, mRecommendationRequestCaptor.getValue().getScanResults().length);
        assertEquals(mTrustedScanDetail.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[0]);
        verify(mWifiConfigManager).setNetworkCandidateScanResult(
                mTrustedWifiConfiguration.networkId, mTrustedScanDetail.getScanResult(), 0);
    }

    @Test
    public void testEvaluateNetworks_untrustedRecommendation_untrustedNetworksAllowed() {
        NetworkUpdateResult networkUpdateResult = new NetworkUpdateResult(10);
        when(mWifiConfigManager.addOrUpdateNetwork(mUntrustedWifiConfiguration, Process.WIFI_UID))
                .thenReturn(networkUpdateResult);
        when(mWifiConfigManager.getConfiguredNetwork(networkUpdateResult.getNetworkId()))
                .thenReturn(mUntrustedWifiConfiguration);
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult
                    .createConnectRecommendation(mUntrustedWifiConfiguration));

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(mTrustedScanDetail, mUntrustedScanDetail),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        assertEquals(mUntrustedWifiConfiguration, result);
        assertNull(result.BSSID);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(2, mRecommendationRequestCaptor.getValue().getScanResults().length);
        assertEquals(mTrustedScanDetail.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[0]);
        assertEquals(mUntrustedScanDetail.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[1]);
        verify(mWifiConfigManager).setNetworkCandidateScanResult(
                networkUpdateResult.getNetworkId(), mUntrustedScanDetail.getScanResult(), 0);
    }

    @Test
    public void testEvaluateNetworks_untrustedRecommendation_updateFailed() {
        NetworkUpdateResult networkUpdateResult = new NetworkUpdateResult(
                WifiConfiguration.INVALID_NETWORK_ID);
        when(mWifiConfigManager.addOrUpdateNetwork(mUntrustedWifiConfiguration, Process.WIFI_UID))
                .thenReturn(networkUpdateResult);
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult
                    .createConnectRecommendation(mUntrustedWifiConfiguration));

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(mTrustedScanDetail, mUntrustedScanDetail),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        assertNull(result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(2, mRecommendationRequestCaptor.getValue().getScanResults().length);
        assertEquals(mTrustedScanDetail.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[0]);
        assertEquals(mUntrustedScanDetail.getScanResult(),
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
                Lists.newArrayList(mTrustedScanDetail, mUntrustedScanDetail),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        assertNull(result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(2, mRecommendationRequestCaptor.getValue().getScanResults().length);
        assertEquals(mTrustedScanDetail.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[0]);
        assertEquals(mUntrustedScanDetail.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[1]);
    }

    @Test
    public void testEvaluateNetworks_nullRecommendation() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(null);

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(mTrustedScanDetail, mUntrustedScanDetail),
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
                Lists.newArrayList(mTrustedScanDetail, mUntrustedScanDetail),
                mTrustedWifiConfiguration, null, false, true /* untrustedNetworkAllowed */, null);

        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertSame(mTrustedWifiConfiguration,
                mRecommendationRequestCaptor.getValue().getConnectedConfig());
    }

    @Test
    public void testEvaluateNetworks_requestConnectableNetworks() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult.createDoNotConnectRecommendation());

        mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(mTrustedScanDetail, mUntrustedScanDetail,
                        mEphemeralScanDetail),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        RecommendationRequest request = mRecommendationRequestCaptor.getValue();
        assertEquals(2, request.getConnectableConfigs().length);
        List<String> ssids = new ArrayList<>();
        for (WifiConfiguration config : request.getConnectableConfigs()) {
            ssids.add(config.SSID);
        }
        List<String> expectedSsids = new ArrayList<>();
        expectedSsids.add(mTrustedWifiConfiguration.SSID);
        expectedSsids.add(mEphemeralWifiConfiguration.SSID);
        assertEquals(expectedSsids, ssids);
    }

    @Test
    public void testEvaluateNetworks_scanResultMarkedAsUntrusted_configIsEphemeral() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult.createDoNotConnectRecommendation());

        mRecommendedNetworkEvaluator.evaluateNetworks(Lists.newArrayList(mEphemeralScanDetail),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        RecommendationRequest request = mRecommendationRequestCaptor.getValue();
        assertEquals(1, request.getScanResults().length);
        assertTrue(request.getScanResults()[0].untrusted);
    }

    @Test
    public void testEvaluateNetworks_potentialConnectableNetworksPopulated() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult.createDoNotConnectRecommendation());

        List<Pair<ScanDetail, WifiConfiguration>> potentialConnectableNetworks = new ArrayList<>();
        mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(mTrustedScanDetail, mEphemeralScanDetail,
                        mUntrustedScanDetail), null, null, false,
                true /* untrustedNetworkAllowed */, potentialConnectableNetworks);

        assertEquals(3, potentialConnectableNetworks.size());
        Pair<ScanDetail, WifiConfiguration> expectedTrustedPair =
                Pair.create(mTrustedScanDetail, mTrustedWifiConfiguration);
        Pair<ScanDetail, WifiConfiguration> expectedUntrustedPair =
                Pair.create(mUntrustedScanDetail, null);
        Pair<ScanDetail, WifiConfiguration> expectedEphemPair =
                Pair.create(mEphemeralScanDetail, mEphemeralWifiConfiguration);
        assertTrue(potentialConnectableNetworks.contains(expectedTrustedPair));
        assertTrue(potentialConnectableNetworks.contains(expectedUntrustedPair));
        assertTrue(potentialConnectableNetworks.contains(expectedEphemPair));
    }

    @Test
    public void testEvaluateNetworks_potentialConnectableNetworksIsNull() {
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mNetworkScoreManager.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult.createDoNotConnectRecommendation());

        mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(mTrustedScanDetail, mEphemeralScanDetail,
                        mUntrustedScanDetail),
                null, null, false, true /* untrustedNetworkAllowed */, null);

        // should not throw a NPE.
    }

    @Test
    public void testEvaluateNetworks_requestContainsLastSelectedNetwork() {
        int lastSelectedNetworkId = 5;
        long lastSelectedTimestamp = 1000;
        when(mWifiConfigManager.wasEphemeralNetworkDeleted(anyString())).thenReturn(false);
        when(mWifiConfigManager.getLastSelectedNetwork()).thenReturn(lastSelectedNetworkId);
        when(mWifiConfigManager.getLastSelectedTimeStamp()).thenReturn(lastSelectedTimestamp);

        mRecommendedNetworkEvaluator.evaluateNetworks(Lists.newArrayList(mTrustedScanDetail),
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
