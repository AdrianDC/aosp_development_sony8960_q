/*
 * Copyright 2017 The Android Open Source Project
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

import static org.junit.Assert.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.Handler;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.TestUtil;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.PasspointProvisioner}.
 */
@SmallTest
public class OsuNetworkConnectionTest {
    private static final String TAG = "OsuNetworkConnectionTest";
    private static final int ENABLE_LOGGING = 1;
    private static final int DISABLE_LOGGING = 0;

    private static final int TEST_NETWORK_ID = 6;
    private static final String TEST_NAI = null;
    private static final String TEST_NAI_OSEN = "access.test.com";
    private static final WifiSsid TEST_SSID = WifiSsid.createFromAsciiEncoded("Test SSID");

    private BroadcastReceiver mBroadcastReceiver;
    private OsuNetworkConnection mNetworkConnection;
    private TestLooper mLooper;
    private Handler mHandler;

    @Mock Context mContext;
    @Mock WifiManager mWifiManager;
    @Mock OsuNetworkConnection.Callbacks mNetworkCallbacks;
    @Mock NetworkInfo mNwInfo;
    @Mock WifiInfo mWifiInfo;
    @Mock Network mCurrentNetwork;

    ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor = ArgumentCaptor.forClass(
            BroadcastReceiver.class);
    ArgumentCaptor<IntentFilter> mIntentFilterCaptor = ArgumentCaptor.forClass(
            IntentFilter.class);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doReturn(mWifiManager).when(mContext)
                .getSystemService(eq(Context.WIFI_SERVICE));
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        when(mWifiManager.enableNetwork(TEST_NETWORK_ID, true)).thenReturn(true);
        when(mWifiManager.addNetwork(any(WifiConfiguration.class))).thenReturn(TEST_NETWORK_ID);
        when(mWifiManager.getCurrentNetwork()).thenReturn(mCurrentNetwork);
        when(mWifiInfo.getNetworkId()).thenReturn(TEST_NETWORK_ID);
        mLooper = new TestLooper();
        mHandler = new Handler(mLooper.getLooper());
        mNetworkConnection = new OsuNetworkConnection(mContext);
        mNetworkConnection.enableVerboseLogging(ENABLE_LOGGING);
    }

    /**
     * Verify that the class registers for receiving the necessary broadcast intents upon init.
     * Verify that the initialization only occurs once even if init() is called  multiple times.
     */
    @Test
    public void verifyBroadcastIntentRegistration() {
        mNetworkConnection.init(mHandler);
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                mIntentFilterCaptor.capture(), any(), eq(mHandler));
        verify(mWifiManager).isWifiEnabled();
        mLooper.dispatchAll();
        IntentFilter intentFilter = mIntentFilterCaptor.getValue();
        assertEquals(intentFilter.countActions(), 2);
    }

    /**
     * Verifies that onWifiEnabled() callback is invoked when the relevant intent is
     * received and the caller is subscribed to receive the callback.
     */
    @Test
    public void verifyWifiStateCallbacks() {
        when(mWifiManager.isWifiEnabled()).thenReturn(false);
        mNetworkConnection.init(mHandler);
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(IntentFilter.class), any(), eq(mHandler));
        mBroadcastReceiver = mBroadcastReceiverCaptor.getValue();
        mLooper.dispatchAll();
        mNetworkConnection.setEventCallback(mNetworkCallbacks);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        mNetworkConnection.setEventCallback(null);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        verify(mNetworkCallbacks, times(1)).onWifiEnabled();
        verify(mNetworkCallbacks, times(1)).onWifiDisabled();
    }

    /**
     * Verifies that connect() API returns false when Wifi is not enabled
     */
    @Test
    public void verifyNetworkConnectionWhenWifiIsDisabled() {
        when(mWifiManager.isWifiEnabled()).thenReturn(false);
        mNetworkConnection.init(mHandler);
        assertEquals(false, mNetworkConnection.connect(TEST_SSID, TEST_NAI));
    }

    /**
     * Verifies that connect() API returns false when OSU AP is a part of an OSEN
     */
    @Test
    public void verifyOSENUnsupported() {
        mNetworkConnection.init(mHandler);
        assertEquals(false, mNetworkConnection.connect(TEST_SSID, TEST_NAI_OSEN));
    }

    /**
     * Verifies that connect() API returns false when WifiManager's addNetwork()
     * returns an invalid network ID
     */
    @Test
    public void verifyNetworkConnectionWhenAddNetworkFails() {
        when(mWifiManager.addNetwork(any(WifiConfiguration.class))).thenReturn(-1);
        mNetworkConnection.init(mHandler);
        assertEquals(false, mNetworkConnection.connect(TEST_SSID, TEST_NAI));
    }

    /**
     * Verifies that connect() API returns false when WifiManager's enableNetwork()
     * fails for the given network ID corresponding to the OSU AP
     */
    @Test
    public void verifyNetworkConnectionWhenEnableNetworkFails() {
        when(mWifiManager.enableNetwork(TEST_NETWORK_ID, true)).thenReturn(false);
        mNetworkConnection.init(mHandler);
        assertEquals(false, mNetworkConnection.connect(TEST_SSID, TEST_NAI));
        verify(mWifiManager).removeNetwork(TEST_NETWORK_ID);
    }

    /**
     * Verifies that network state callbacks are invoked when the NETWORK_STATE_CHANGED intent
     * is received and when WifiManager has successfully requested connection to the OSU AP.
     */
    @Test
    public void verifyNetworkCallbackInvokedWhenRegistered() {
        mNetworkConnection.init(mHandler);
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(IntentFilter.class), any(), eq(mHandler));
        mBroadcastReceiver = mBroadcastReceiverCaptor.getValue();

        mNetworkConnection.setEventCallback(mNetworkCallbacks);
        assertEquals(true, mNetworkConnection.connect(TEST_SSID, TEST_NAI));
        mLooper.dispatchAll();

        when(mNwInfo.getDetailedState()).thenReturn(NetworkInfo.DetailedState.CONNECTED);
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                mNwInfo, mWifiInfo);
        verify(mNetworkCallbacks).onConnected(mCurrentNetwork);

        when(mNwInfo.getDetailedState()).thenReturn(NetworkInfo.DetailedState.DISCONNECTED);
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext, mNwInfo, mWifiInfo);
        verify(mNetworkCallbacks).onDisconnected();
        verify(mWifiManager).removeNetwork(TEST_NETWORK_ID);
    }

    /**
     * Verifies that the onConnected() callback is not invoked when the Network State Changed
     * intent is called when WifiManager has successfully connected to a network that's not the
     * OSU AP.
     */
    @Test
    public void verifyNetworkDisconnectedCallbackConnectedToAnotherNetwork() {
        when(mWifiInfo.getNetworkId()).thenReturn(TEST_NETWORK_ID + 1);
        when(mNwInfo.getDetailedState()).thenReturn(NetworkInfo.DetailedState.CONNECTED);
        mNetworkConnection.init(mHandler);
        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(IntentFilter.class), any(), eq(mHandler));
        mLooper.dispatchAll();

        mBroadcastReceiver = mBroadcastReceiverCaptor.getValue();
        mNetworkConnection.setEventCallback(mNetworkCallbacks);
        assertEquals(true, mNetworkConnection.connect(TEST_SSID, TEST_NAI));
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                mNwInfo, mWifiInfo);
        verify(mNetworkCallbacks, never()).onConnected(any(Network.class));
        verify(mWifiManager).removeNetwork(TEST_NETWORK_ID);
    }

    /**
     * Verifies that WifiManager's removeNetwork() is called when disconnectIfNeeded() is called
     * on the OSU AP's network ID.
     */
    @Test
    public void verifyNetworkTearDown() {
        mNetworkConnection.init(mHandler);
        assertEquals(true, mNetworkConnection.connect(TEST_SSID, TEST_NAI));
        mNetworkConnection.disconnectIfNeeded();
        verify(mWifiManager).removeNetwork(TEST_NETWORK_ID);
    }
}

