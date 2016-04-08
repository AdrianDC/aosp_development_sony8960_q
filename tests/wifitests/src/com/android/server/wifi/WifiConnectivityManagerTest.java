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

import static com.android.server.wifi.WifiConfigurationTestUtil.generateWifiConfig;

import static org.mockito.Mockito.*;

import android.app.AlarmManager;
import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.ScanListener;
import android.net.wifi.WifiScanner.ScanSettings;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.R;
import com.android.server.wifi.MockAnswerUtil.AnswerWithArguments;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link com.android.server.wifi.WifiConnectivityManager}.
 */
@SmallTest
public class WifiConnectivityManagerTest {

    /**
     * Called before each test
     */
    @Before
    public void setUp() throws Exception {
        mResource = getResource();
        mAlarmManager = getAlarmManager();
        mContext = getContext();
        mWifiStateMachine = getWifiStateMachine();
        mWifiConfigManager = getWifiConfigManager();
        mWifiInfo = getWifiInfo();
        mWifiScanner = getWifiScanner();
        mWifiQNS = getWifiQualifiedNetworkSelector();

        mWifiConnectivityManager = new WifiConnectivityManager(mContext, mWifiStateMachine,
                mWifiScanner, mWifiConfigManager, mWifiInfo, mWifiQNS);
        mWifiConnectivityManager.setWifiEnabled(true);
    }

    /**
     * Called after each test
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    private Resources mResource;
    private Context mContext;
    private AlarmManager mAlarmManager;
    private WifiConnectivityManager mWifiConnectivityManager;
    private WifiQualifiedNetworkSelector mWifiQNS;
    private WifiStateMachine mWifiStateMachine;
    private WifiScanner mWifiScanner;
    private WifiConfigManager mWifiConfigManager;
    private WifiInfo mWifiInfo;

    private static final int CANDIDATE_NETWORK_ID = 0;
    private static final String CANDIDATE_SSID = "\"AnSsid\"";
    private static final String CANDIDATE_BSSID = "6c:f3:7f:ae:8c:f3";
    private static final String TAG = "WifiConnectivityManager Unit Test";

    Resources getResource() {
        Resources resource = mock(Resources.class);

        when(resource.getInteger(R.integer.config_wifi_framework_SECURITY_AWARD)).thenReturn(80);
        when(resource.getInteger(R.integer.config_wifi_framework_SAME_BSSID_AWARD)).thenReturn(24);

        return resource;
    }

    AlarmManager getAlarmManager() {
        AlarmManager alarmManager = mock(AlarmManager.class);

        return alarmManager;
    }

    Context getContext() {
        Context context = mock(Context.class);

        when(context.getResources()).thenReturn(mResource);
        when(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(
                mAlarmManager);

        return context;
    }

    WifiScanner getWifiScanner() {
        WifiScanner scanner = mock(WifiScanner.class);

        // dummy scan results. QNS PeriodicScanListener bulids scanDetails from
        // the fullScanResult and doesn't really use results.
        WifiScanner.ScanData[] results = new WifiScanner.ScanData[1];

        // do a synchronous answer for the ScanListener callbacks
        doAnswer(new AnswerWithArguments() {
            public void answer(ScanSettings settings, ScanListener listener) throws Exception {
                listener.onResults(results);
            }}).when(scanner).startBackgroundScan(anyObject(), anyObject());

        doAnswer(new AnswerWithArguments() {
            public void answer(ScanSettings settings, ScanListener listener) throws Exception {
                listener.onResults(results);
            }}).when(scanner).startScan(anyObject(), anyObject());

        return scanner;
    }

    WifiStateMachine getWifiStateMachine() {
        WifiStateMachine stateMachine = mock(WifiStateMachine.class);

        when(stateMachine.getFrequencyBand()).thenReturn(1);
        when(stateMachine.isLinkDebouncing()).thenReturn(false);
        when(stateMachine.isConnected()).thenReturn(false);
        when(stateMachine.isDisconnected()).thenReturn(true);
        when(stateMachine.isSupplicantTransientState()).thenReturn(false);

        return stateMachine;
    }

    WifiQualifiedNetworkSelector getWifiQualifiedNetworkSelector() {
        WifiQualifiedNetworkSelector qns = mock(WifiQualifiedNetworkSelector.class);

        WifiConfiguration candidate = generateWifiConfig(
                0, CANDIDATE_NETWORK_ID, CANDIDATE_SSID, false, true, null, null);
        candidate.BSSID = CANDIDATE_BSSID;
        ScanResult candidateScanResult = new ScanResult();
        candidateScanResult.SSID = CANDIDATE_SSID;
        candidateScanResult.BSSID = CANDIDATE_BSSID;
        candidate.getNetworkSelectionStatus().setCandidate(candidateScanResult);

        when(qns.selectQualifiedNetwork(anyBoolean(), anyBoolean(), anyObject(),
              anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(candidate);

        return qns;
    }

    WifiInfo getWifiInfo() {
        WifiInfo wifiInfo = mock(WifiInfo.class);

        when(wifiInfo.getNetworkId()).thenReturn(WifiConfiguration.INVALID_NETWORK_ID);
        when(wifiInfo.getBSSID()).thenReturn(null);

        return wifiInfo;
    }

    WifiConfigManager getWifiConfigManager() {
        WifiConfigManager wifiConfigManager = mock(WifiConfigManager.class);

        when(wifiConfigManager.getWifiConfiguration(anyInt())).thenReturn(null);
        wifiConfigManager.mThresholdSaturatedRssi24 = new AtomicInteger(
                WifiQualifiedNetworkSelector.RSSI_SATURATION_2G_BAND);
        wifiConfigManager.mCurrentNetworkBoost = new AtomicInteger(
                WifiQualifiedNetworkSelector.SAME_NETWORK_AWARD);

        return wifiConfigManager;
    }


    /**
     *  Wifi enters disconnected state while screen is on.
     *
     * Expected behavior: WifiConnectivityManager calls
     * WifiStateMachine.autoConnectToNetwork() with the
     * expected candidate network ID and BSSID.
     */
    @Test
    public void enterWifiDisconnectedStateWhenScreenOn() {
        // Set screen to on
        mWifiConnectivityManager.handleScreenStateChanged(true);

        // Set WiFi to disconnected state
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_DISCONNECTED);

        verify(mWifiStateMachine).autoConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }

    /**
     *  Wifi enters connected state while screen is on.
     *
     * Expected behavior: WifiConnectivityManager calls
     * WifiStateMachine.autoConnectToNetwork() with the
     * expected candidate network ID and BSSID.
     */
    @Test
    public void enterWifiConnectedStateWhenScreenOn() {
        // Set screen to on
        mWifiConnectivityManager.handleScreenStateChanged(true);

        // Set WiFi to connected state
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_CONNECTED);

        verify(mWifiStateMachine).autoConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }

    /**
     *  Screen turned on while WiFi in disconnected state.
     *
     * Expected behavior: WifiConnectivityManager calls
     * WifiStateMachine.autoConnectToNetwork() with the
     * expected candidate network ID and BSSID.
     */
    @Test
    public void turnScreenOnWhenWifiInDisconnectedState() {
        // Set WiFi to disconnected state
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_DISCONNECTED);

        // Set screen to on
        mWifiConnectivityManager.handleScreenStateChanged(true);

        verify(mWifiStateMachine).autoConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }

    /**
     *  Screen turned on while WiFi in connected state.
     *
     * Expected behavior: WifiConnectivityManager calls
     * WifiStateMachine.autoConnectToNetwork() with the
     * expected candidate network ID and BSSID.
     */
    @Test
    public void turnScreenOnWhenWifiInConnectedState() {
        // Set WiFi to connected state
        mWifiConnectivityManager.handleConnectionStateChanged(
                WifiConnectivityManager.WIFI_STATE_CONNECTED);

        // Set screen to on
        mWifiConnectivityManager.handleScreenStateChanged(true);

        verify(mWifiStateMachine).autoConnectToNetwork(
                CANDIDATE_NETWORK_ID, CANDIDATE_BSSID);
    }
}
