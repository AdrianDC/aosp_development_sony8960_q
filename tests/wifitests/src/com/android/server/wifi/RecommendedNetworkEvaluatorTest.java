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

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.WifiKey;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.WifiSsid;
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

/**
 * Unit tests for {@link RecommendedNetworkEvaluator}.
 */
@SmallTest
public class RecommendedNetworkEvaluatorTest {
    private static final ScanDetail TRUSTED_SCAN_DETAIL = buildScanDetail("ssid");
    private static final ScanDetail UNTRUSTED_SCAN_DETAIL = buildScanDetail("ssid1");
    private static final WifiConfiguration TRUSTED_WIFI_CONFIGURATION = new WifiConfiguration();
    static {
        TRUSTED_WIFI_CONFIGURATION.networkId = 5;
        TRUSTED_WIFI_CONFIGURATION.SSID = TRUSTED_SCAN_DETAIL.getSSID();
        TRUSTED_WIFI_CONFIGURATION.getNetworkSelectionStatus().setCandidate(
                TRUSTED_SCAN_DETAIL.getScanResult());
    }

    @Mock private NetworkScoreManager mNetworkScoreManager;
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private NetworkScoreManager mScoreManager;
    @Mock private WifiNetworkScoreCache mNetworkScoreCache;

    @Captor private ArgumentCaptor<NetworkKey[]> mNetworkKeyArrayCaptor;
    @Captor private ArgumentCaptor<RecommendationRequest> mRecommendationRequestCaptor;

    private RecommendedNetworkEvaluator mRecommendedNetworkEvaluator;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mRecommendedNetworkEvaluator = new RecommendedNetworkEvaluator(
                mNetworkScoreCache, mNetworkScoreManager, mWifiConfigManager, new LocalLog(0));

        when(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(TRUSTED_SCAN_DETAIL))
                .thenReturn(TRUSTED_WIFI_CONFIGURATION);
        when(mWifiConfigManager.getSavedNetworkForScanDetailAndCache(UNTRUSTED_SCAN_DETAIL))
                .thenReturn(null);
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

        WifiConfiguration result = mRecommendedNetworkEvaluator.evaluateNetworks(
                Lists.newArrayList(TRUSTED_SCAN_DETAIL, UNTRUSTED_SCAN_DETAIL),
                null, null, false, false /* untrustedNetworkAllowed */, null);

        assertEquals(TRUSTED_WIFI_CONFIGURATION, result);
        verify(mNetworkScoreManager).requestRecommendation(mRecommendationRequestCaptor.capture());
        assertEquals(1, mRecommendationRequestCaptor.getValue().getScanResults().length);
        assertEquals(TRUSTED_SCAN_DETAIL.getScanResult(),
                mRecommendationRequestCaptor.getValue().getScanResults()[0]);
    }

    @Test
    public void testEvaluateNetworks_recommendation_untrustedNetworksAllowed() {
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

    private static ScanDetail buildScanDetail(String ssid) {
        return new ScanDetail(WifiSsid.createFromAsciiEncoded(ssid),
                "00:00:00:00:00:00", "", 0, 0, 0, 0);
    }
}
