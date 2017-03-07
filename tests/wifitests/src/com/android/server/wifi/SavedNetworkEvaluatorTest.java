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

import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_NONE;
import static com.android.server.wifi.WifiConfigurationTestUtil.SECURITY_PSK;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.R;
import com.android.server.wifi.WifiNetworkSelectorTestUtil.ScanDetailsAndWifiConfigs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.SavedNetworkEvaluator}.
 */
@SmallTest
public class SavedNetworkEvaluatorTest {

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        setupContext();
        setupResource();
        setupWifiConfigManager();

        when(mClock.getElapsedSinceBootMillis()).thenReturn(SystemClock.elapsedRealtime());
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.CURATE_SAVED_OPEN_NETWORKS, 0)).thenReturn(0);
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0)).thenReturn(0);

        mThresholdMinimumRssi2G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz);
        mThresholdMinimumRssi5G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz);
        mThresholdQualifiedRssi2G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        mThresholdQualifiedRssi5G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
        mThresholdSaturatedRssi2G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz);
        mThresholdSaturatedRssi5G = mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz);

        ArgumentCaptor<ContentObserver> observerCaptor =
                ArgumentCaptor.forClass(ContentObserver.class);

        mSavedNetworkEvaluator = new SavedNetworkEvaluator(mContext, mWifiConfigManager,
                mClock, null, Looper.getMainLooper(), mFrameworkFacade);
        verify(mFrameworkFacade, times(2)).registerContentObserver(eq(mContext), any(Uri.class),
                eq(false), observerCaptor.capture());
        // SavedNetworkEvaluator uses a single ContentObserver for two registrations, we only need
        // to get this object once.
        mContentObserver = observerCaptor.getValue();
    }

    /** Cleans up test. */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    private SavedNetworkEvaluator mSavedNetworkEvaluator;
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private Context mContext;
    @Mock private ContentResolver mContentResolver;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private Resources mResource;
    @Mock private Clock mClock;
    private int mThresholdMinimumRssi2G;
    private int mThresholdMinimumRssi5G;
    private int mThresholdQualifiedRssi2G;
    private int mThresholdQualifiedRssi5G;
    private int mThresholdSaturatedRssi2G;
    private int mThresholdSaturatedRssi5G;
    private ContentObserver mContentObserver;
    private static final String TAG = "Saved Network Evaluator Unit Test";

    private void setupContext() {
        when(mContext.getResources()).thenReturn(mResource);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
    }

    private void setupResource() {
        when(mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz))
                .thenReturn(-70);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz))
                .thenReturn(-73);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz))
                .thenReturn(-70);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz))
                .thenReturn(-73);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz))
                .thenReturn(-82);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz))
                .thenReturn(-85);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_SLOPE))
                .thenReturn(4);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_OFFSET))
                .thenReturn(85);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_SAME_BSSID_AWARD))
                .thenReturn(24);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_SECURITY_AWARD))
                .thenReturn(80);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_5GHz_preference_boost_factor))
                .thenReturn(16);
        when(mResource.getInteger(
                R.integer.config_wifi_framework_current_network_boost))
                .thenReturn(16);
    }

    private void setupWifiConfigManager() {
        when(mWifiConfigManager.getLastSelectedNetwork())
                .thenReturn(WifiConfiguration.INVALID_NETWORK_ID);
    }

    /**
     * Do not evaluate networks that {@link WifiConfiguration#useExternalScores}.
     */
    @Test
    public void ignoreNetworksIfUseExternalScores() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi2G + 8, mThresholdQualifiedRssi2G + 10};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();
        for (WifiConfiguration wifiConfiguration : savedConfigs) {
            wifiConfiguration.useExternalScores = true;
        }

        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                null, null, true, false, null);

        assertNull(candidate);
    }

    /**
     * Do not evaluate open networks when {@link Settings.Global.CURATE_SAVED_OPEN_NETWORKS} and
     * {@link Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED} are enabled.
     */
    @Test
    public void ignoreOpensNetworksIfCurateSavedNetworksEnabled() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {5200, 5240};
        String[] caps = {"[ESS]", "[ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G, mThresholdQualifiedRssi5G};
        int[] securities = {SECURITY_NONE, SECURITY_NONE};

        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.CURATE_SAVED_OPEN_NETWORKS, 0)).thenReturn(1);
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0)).thenReturn(1);
        mContentObserver.onChange(false);

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                        freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();

        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                null, null, true, false, null);

        assertNull(candidate);
    }


    /**
     * Between two 2G networks, choose the one with stronger RSSI value if other conditions
     * are the same and the RSSI values are not satuarted.
     */
    @Test
    public void chooseStrongerRssi2GNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2470, 2437};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi2G + 8, mThresholdQualifiedRssi2G + 10};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                null, null, true, false, null);

        ScanResult chosenScanResult = scanDetails.get(1).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[1], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * Between two 5G networks, choose the one with stronger RSSI value if other conditions
     * are the same and the RSSI values are not saturated.
     * {@link Settings.Global.CURATE_SAVED_OPEN_NETWORKS} and
     * {@link Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED} will no affect on the outcome
     * because both networks are secure.
     */
    @Test
    public void chooseStrongerRssi5GNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {5200, 5240};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G + 8, mThresholdQualifiedRssi5G + 10};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.CURATE_SAVED_OPEN_NETWORKS, 0)).thenReturn(1);
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0)).thenReturn(1);
        mContentObserver.onChange(false);

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                null, null, true, false, null);

        ScanResult chosenScanResult = scanDetails.get(1).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[1], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * Choose secure network over open network if other conditions are the same.
     */
    @Test
    public void chooseSecureNetworkOverOpenNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {5200, 5240};
        String[] caps = {"[ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi5G, mThresholdQualifiedRssi5G};
        int[] securities = {SECURITY_NONE, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                null, null, true, false, null);

        ScanResult chosenScanResult = scanDetails.get(1).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[1], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * Choose 5G network over 2G network if other conditions are the same.
     */
    @Test
    public void choose5GNetworkOver2GNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {2437, 5240};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        int[] levels = {mThresholdQualifiedRssi2G, mThresholdQualifiedRssi5G};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                null, null, true, false, null);

        ScanResult chosenScanResult = scanDetails.get(1).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[1], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * Verify that we stick to the currently connected network if the other one is
     * just slightly better scored.
     */
    @Test
    public void stickToCurrentNetwork() {
        String[] ssids = {"\"test1\"", "\"test2\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {5200, 5240};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        // test2 has slightly stronger RSSI value than test1
        int[] levels = {mThresholdMinimumRssi5G + 2, mThresholdMinimumRssi5G + 4};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        // Simuluate we are connected to SSID test1 already.
        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                savedConfigs[0], null, true, false, null);

        // Even though test2 has higher RSSI value, test1 is chosen because of the
        // currently connected network bonus.
        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
        WifiNetworkSelectorTestUtil.verifySelectedScanResult(mWifiConfigManager,
                chosenScanResult, candidate);
    }

    /**
     * Verify that we stick to the currently connected BSSID if the other one is
     * just slightly better scored.
     */
    @Test
    public void stickToCurrentBSSID() {
        // Same SSID
        String[] ssids = {"\"test1\"", "\"test1\""};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4"};
        int[] freqs = {5200, 5240};
        String[] caps = {"[WPA2-EAP-CCMP][ESS]", "[WPA2-EAP-CCMP][ESS]"};
        // test2 has slightly stronger RSSI value than test1
        int[] levels = {mThresholdMinimumRssi5G + 2, mThresholdMinimumRssi5G + 6};
        int[] securities = {SECURITY_PSK, SECURITY_PSK};

        ScanDetailsAndWifiConfigs scanDetailsAndConfigs =
                WifiNetworkSelectorTestUtil.setupScanDetailsAndConfigStore(ssids, bssids,
                    freqs, caps, levels, securities, mWifiConfigManager, mClock);
        List<ScanDetail> scanDetails = scanDetailsAndConfigs.getScanDetails();
        WifiConfiguration[] savedConfigs = scanDetailsAndConfigs.getWifiConfigs();

        // Simuluate we are connected to BSSID "6c:f3:7f:ae:8c:f3" already
        WifiConfiguration candidate = mSavedNetworkEvaluator.evaluateNetworks(scanDetails,
                null, bssids[0], true, false, null);

        // Even though test2 has higher RSSI value, test1 is chosen because of the
        // currently connected BSSID bonus.
        ScanResult chosenScanResult = scanDetails.get(0).getScanResult();
        WifiConfigurationTestUtil.assertConfigurationEqual(savedConfigs[0], candidate);
    }
}
