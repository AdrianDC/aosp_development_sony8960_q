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

import static android.net.wifi.WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_FAILURE_REASON;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_MODE;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_STATE;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;

import static com.android.server.wifi.LocalOnlyHotspotRequestInfo.HOTSPOT_NO_ERROR;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.Intent;
import android.net.InterfaceConfiguration;
import android.net.wifi.IApInterface;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.INetworkManagementService;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.net.BaseNetworkObserver;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Unit tests for {@link SoftApManager}. */
@SmallTest
public class SoftApManagerTest {

    private static final String TAG = "SoftApManagerTest";

    private static final String DEFAULT_SSID = "DefaultTestSSID";
    private static final String TEST_SSID = "TestSSID";
    private static final String TEST_COUNTRY_CODE = "TestCountry";
    private static final Integer[] ALLOWED_2G_CHANNELS = {1, 2, 3, 4};
    private static final String TEST_INTERFACE_NAME = "testif0";
    private static final int TEST_NUM_CONNECTED_CLIENTS = 4;

    private final ArrayList<Integer> mAllowed2GChannels =
            new ArrayList<>(Arrays.asList(ALLOWED_2G_CHANNELS));

    private final WifiConfiguration mDefaultApConfig = createDefaultApConfig();

    @Mock Context mContext;
    TestLooper mLooper;
    @Mock WifiNative mWifiNative;
    @Mock SoftApManager.Listener mListener;
    @Mock InterfaceConfiguration mInterfaceConfiguration;
    @Mock IBinder mApInterfaceBinder;
    @Mock IApInterface mApInterface;
    @Mock INetworkManagementService mNmService;
    @Mock WifiApConfigStore mWifiApConfigStore;
    @Mock WifiMetrics mWifiMetrics;
    final ArgumentCaptor<DeathRecipient> mDeathListenerCaptor =
            ArgumentCaptor.forClass(DeathRecipient.class);
    final ArgumentCaptor<BaseNetworkObserver> mNetworkObserverCaptor =
            ArgumentCaptor.forClass(BaseNetworkObserver.class);
    final ArgumentCaptor<SoftApManager.ApInterfaceListener> mApInterfaceListenerCaptor =
            ArgumentCaptor.forClass(SoftApManager.ApInterfaceListener.class);

    SoftApManager mSoftApManager;

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();

        when(mApInterface.asBinder()).thenReturn(mApInterfaceBinder);
        when(mApInterface.startHostapd(any())).thenReturn(true);
        when(mApInterface.stopHostapd()).thenReturn(true);
        when(mApInterface.writeHostapdConfig(
                any(), anyBoolean(), anyInt(), anyInt(), any())).thenReturn(true);
        when(mApInterface.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
    }

    private WifiConfiguration createDefaultApConfig() {
        WifiConfiguration defaultConfig = new WifiConfiguration();
        defaultConfig.SSID = DEFAULT_SSID;
        return defaultConfig;
    }

    private SoftApManager createSoftApManager(SoftApModeConfiguration config) throws Exception {
        when(mApInterface.asBinder()).thenReturn(mApInterfaceBinder);
        when(mApInterface.startHostapd(any())).thenReturn(true);
        when(mApInterface.stopHostapd()).thenReturn(true);
        if (config.getWifiConfiguration() == null) {
            when(mWifiApConfigStore.getApConfiguration()).thenReturn(mDefaultApConfig);
        }
        SoftApManager newSoftApManager = new SoftApManager(mContext,
                                                           mLooper.getLooper(),
                                                           mWifiNative,
                                                           TEST_COUNTRY_CODE,
                                                           mListener,
                                                           mApInterface,
                                                           TEST_INTERFACE_NAME,
                                                           mNmService,
                                                           mWifiApConfigStore,
                                                           config,
                                                           mWifiMetrics);
        mLooper.dispatchAll();
        return newSoftApManager;
    }

    /** Verifies startSoftAp will use default config if AP configuration is not provided. */
    @Test
    public void startSoftApWithoutConfig() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);
    }

    /** Verifies startSoftAp will use provided config and start AP. */
    @Test
    public void startSoftApWithConfig() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_2GHZ;
        config.SSID = TEST_SSID;
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);
        startSoftApAndVerifyEnabled(apConfig);
    }


    /**
     * Verifies startSoftAp will start with the hiddenSSID param set when it is set to true in the
     * supplied config.
     */
    @Test
    public void startSoftApWithHiddenSsidTrueInConfig() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_2GHZ;
        config.SSID = TEST_SSID;
        config.hiddenSSID = true;
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);
        startSoftApAndVerifyEnabled(apConfig);
    }

    /** Tests softap startup if default config fails to load. **/
    @Test
    public void startSoftApDefaultConfigFailedToLoad() throws Exception {
        when(mApInterface.asBinder()).thenReturn(mApInterfaceBinder);
        when(mApInterface.startHostapd(any())).thenReturn(true);
        when(mApInterface.stopHostapd()).thenReturn(true);
        when(mWifiApConfigStore.getApConfiguration()).thenReturn(null);
        SoftApModeConfiguration nullApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        SoftApManager newSoftApManager = new SoftApManager(mContext,
                                                           mLooper.getLooper(),
                                                           mWifiNative,
                                                           TEST_COUNTRY_CODE,
                                                           mListener,
                                                           mApInterface,
                                                           TEST_INTERFACE_NAME,
                                                           mNmService,
                                                           mWifiApConfigStore,
                                                           nullApConfig,
                                                           mWifiMetrics);
        mLooper.dispatchAll();
        newSoftApManager.start();
        mLooper.dispatchAll();
        verify(mListener).onStateChanged(WifiManager.WIFI_AP_STATE_FAILED,
                WifiManager.SAP_START_FAILURE_GENERAL);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_ENABLING,
                WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                nullApConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_ENABLING, WifiManager.SAP_START_FAILURE_GENERAL, TEST_INTERFACE_NAME,
                nullApConfig.getTargetMode());
    }

    /**
     * Tests that the generic error is propagated and properly reported when starting softap and the
     * specified channel cannot be used.
     */
    @Test
    public void startSoftApFailGeneralErrorForConfigChannel() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = WifiConfiguration.AP_BAND_5GHZ;
        config.SSID = TEST_SSID;
        SoftApModeConfiguration softApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);

        when(mApInterface.asBinder()).thenReturn(mApInterfaceBinder);
        when(mApInterface.startHostapd(any())).thenReturn(true);
        when(mApInterface.stopHostapd()).thenReturn(true);
        when(mWifiNative.isHalStarted()).thenReturn(true);

        SoftApManager newSoftApManager = new SoftApManager(mContext,
                                                           mLooper.getLooper(),
                                                           mWifiNative,
                                                           null,
                                                           mListener,
                                                           mApInterface,
                                                           TEST_INTERFACE_NAME,
                                                           mNmService,
                                                           mWifiApConfigStore,
                                                           softApConfig,
                                                           mWifiMetrics);
        mLooper.dispatchAll();
        newSoftApManager.start();
        mLooper.dispatchAll();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_ENABLING,
                WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_ENABLING, WifiManager.SAP_START_FAILURE_GENERAL, TEST_INTERFACE_NAME,
                softApConfig.getTargetMode());
    }

    /**
     * Tests that the NO_CHANNEL error is propagated and properly reported when starting softap and
     * a valid channel cannot be determined.
     */
    @Test
    public void startSoftApFailNoChannel() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.apBand = -2;
        config.apChannel = 0;
        config.SSID = TEST_SSID;
        SoftApModeConfiguration softApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);

        when(mApInterface.asBinder()).thenReturn(mApInterfaceBinder);
        when(mApInterface.startHostapd(any())).thenReturn(true);
        when(mApInterface.stopHostapd()).thenReturn(true);
        when(mWifiNative.isHalStarted()).thenReturn(true);

        SoftApManager newSoftApManager = new SoftApManager(mContext,
                                                           mLooper.getLooper(),
                                                           mWifiNative,
                                                           TEST_COUNTRY_CODE,
                                                           mListener,
                                                           mApInterface,
                                                           TEST_INTERFACE_NAME,
                                                           mNmService,
                                                           mWifiApConfigStore,
                                                           softApConfig,
                                                           mWifiMetrics);
        mLooper.dispatchAll();
        newSoftApManager.start();
        mLooper.dispatchAll();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_ENABLING,
                WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_ENABLING, WifiManager.SAP_START_FAILURE_NO_CHANNEL,
                TEST_INTERFACE_NAME, softApConfig.getTargetMode());
    }

    /**
     * Tests startup when Ap Interface fails to start successfully.
     */
    @Test
    public void startSoftApApInterfaceFailedToStart() throws Exception {
        when(mApInterface.asBinder()).thenReturn(mApInterfaceBinder);
        when(mApInterface.startHostapd(any())).thenReturn(false);
        when(mApInterface.stopHostapd()).thenReturn(true);
        SoftApModeConfiguration softApModeConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, mDefaultApConfig);
        SoftApManager newSoftApManager = new SoftApManager(mContext,
                                                           mLooper.getLooper(),
                                                           mWifiNative,
                                                           TEST_COUNTRY_CODE,
                                                           mListener,
                                                           mApInterface,
                                                           TEST_INTERFACE_NAME,
                                                           mNmService,
                                                           mWifiApConfigStore,
                                                           softApModeConfig,
                                                           mWifiMetrics);

        mLooper.dispatchAll();
        newSoftApManager.start();
        mLooper.dispatchAll();
        verify(mListener).onStateChanged(WifiManager.WIFI_AP_STATE_FAILED,
                WifiManager.SAP_START_FAILURE_GENERAL);
    }

    /**
     * Tests the handling of stop command when soft AP is not started.
     */
    @Test
    public void stopWhenNotStarted() throws Exception {
        mSoftApManager = createSoftApManager(
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null));
        mSoftApManager.stop();
        mLooper.dispatchAll();
        /* Verify no state changes. */
        verify(mListener, never()).onStateChanged(anyInt(), anyInt());
        verify(mContext, never()).sendStickyBroadcastAsUser(any(), any());
    }

    /**
     * Tests the handling of stop command when soft AP is started.
     */
    @Test
    public void stopWhenStarted() throws Exception {
        SoftApModeConfiguration softApModeConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(softApModeConfig);

        // reset to clear verified Intents for ap state change updates
        reset(mContext);

        InOrder order = inOrder(mListener);

        mSoftApManager.stop();
        mLooper.dispatchAll();

        verify(mApInterface).stopHostapd();
        order.verify(mListener).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLING, 0);
        order.verify(mListener).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLED, 0);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_DISABLING,
                WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_DISABLED,
                WIFI_AP_STATE_DISABLING, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());
    }

    /**
     * Verify that SoftAp mode shuts down if wificond dies.
     */
    @Test
    public void handlesWificondInterfaceDeath() throws Exception {
        SoftApModeConfiguration softApModeConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(softApModeConfig);

        // reset to clear verified Intents for ap state change updates
        reset(mContext);

        mDeathListenerCaptor.getValue().binderDied();
        mLooper.dispatchAll();
        InOrder order = inOrder(mListener);
        order.verify(mListener).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLING, 0);
        order.verify(mListener).onStateChanged(WifiManager.WIFI_AP_STATE_FAILED,
                WifiManager.SAP_START_FAILURE_GENERAL);
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_DISABLING,
                WIFI_AP_STATE_ENABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_FAILED,
                WIFI_AP_STATE_DISABLING, WifiManager.SAP_START_FAILURE_GENERAL, TEST_INTERFACE_NAME,
                softApModeConfig.getTargetMode());
    }

    @Test
    public void updatesNumAssociatedStations() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mApInterfaceListenerCaptor.getValue().onNumAssociatedStationsChanged(
                TEST_NUM_CONNECTED_CLIENTS);
        mLooper.dispatchAll();
        assertEquals(TEST_NUM_CONNECTED_CLIENTS, mSoftApManager.getNumAssociatedStations());
    }

    @Test
    public void handlesNumAssociatedStationsWhenNotStarted() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);
        mSoftApManager.stop();
        mLooper.dispatchAll();

        mApInterfaceListenerCaptor.getValue().onNumAssociatedStationsChanged(
                TEST_NUM_CONNECTED_CLIENTS);
        mLooper.dispatchAll();
        /* Verify numAssociatedStations is not updated when soft AP is not started */
        assertEquals(0, mSoftApManager.getNumAssociatedStations());
    }

    @Test
    public void handlesInvalidNumAssociatedStations() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mApInterfaceListenerCaptor.getValue().onNumAssociatedStationsChanged(
                TEST_NUM_CONNECTED_CLIENTS);
        /* Invalid values should be ignored */
        mApInterfaceListenerCaptor.getValue().onNumAssociatedStationsChanged(-1);
        mLooper.dispatchAll();
        assertEquals(TEST_NUM_CONNECTED_CLIENTS, mSoftApManager.getNumAssociatedStations());
    }

    @Test
    public void resetsNumAssociatedStationsWhenStopped() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mApInterfaceListenerCaptor.getValue().onNumAssociatedStationsChanged(
                TEST_NUM_CONNECTED_CLIENTS);
        mSoftApManager.stop();
        mLooper.dispatchAll();
        assertEquals(0, mSoftApManager.getNumAssociatedStations());
    }

    @Test
    public void resetsNumAssociatedStationsOnFailure() throws Exception {
        SoftApModeConfiguration apConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null);
        startSoftApAndVerifyEnabled(apConfig);

        mApInterfaceListenerCaptor.getValue().onNumAssociatedStationsChanged(
                TEST_NUM_CONNECTED_CLIENTS);
        /* Force soft AP to fail */
        mDeathListenerCaptor.getValue().binderDied();
        mLooper.dispatchAll();
        verify(mListener).onStateChanged(WifiManager.WIFI_AP_STATE_FAILED,
                WifiManager.SAP_START_FAILURE_GENERAL);

        assertEquals(0, mSoftApManager.getNumAssociatedStations());
    }

    /** Starts soft AP and verifies that it is enabled successfully. */
    protected void startSoftApAndVerifyEnabled(
            SoftApModeConfiguration softApConfig) throws Exception {
        String expectedSSID;
        boolean expectedHiddenSsid;
        InOrder order = inOrder(mListener, mApInterfaceBinder, mApInterface, mNmService);

        when(mWifiNative.isHalStarted()).thenReturn(false);
        when(mWifiNative.setCountryCodeHal(TEST_COUNTRY_CODE.toUpperCase(Locale.ROOT)))
                .thenReturn(true);

        mSoftApManager = createSoftApManager(softApConfig);
        WifiConfiguration config = softApConfig.getWifiConfiguration();
        if (config == null) {
            when(mWifiApConfigStore.getApConfiguration()).thenReturn(mDefaultApConfig);
            expectedSSID = mDefaultApConfig.SSID;
            expectedHiddenSsid = mDefaultApConfig.hiddenSSID;
        } else {
            expectedSSID = config.SSID;
            expectedHiddenSsid = config.hiddenSSID;
        }

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);

        mSoftApManager.start();
        mLooper.dispatchAll();
        order.verify(mListener).onStateChanged(WifiManager.WIFI_AP_STATE_ENABLING, 0);
        order.verify(mApInterfaceBinder).linkToDeath(mDeathListenerCaptor.capture(), eq(0));
        order.verify(mNmService).registerObserver(mNetworkObserverCaptor.capture());
        order.verify(mApInterface).writeHostapdConfig(
                eq(expectedSSID.getBytes(StandardCharsets.UTF_8)), eq(expectedHiddenSsid),
                anyInt(), anyInt(), any());
        order.verify(mApInterface).startHostapd(mApInterfaceListenerCaptor.capture());
        mNetworkObserverCaptor.getValue().interfaceLinkStateChanged(TEST_INTERFACE_NAME, true);
        mLooper.dispatchAll();
        order.verify(mListener).onStateChanged(WifiManager.WIFI_AP_STATE_ENABLED, 0);
        verify(mContext, times(2)).sendStickyBroadcastAsUser(intentCaptor.capture(),
                                                             eq(UserHandle.ALL));
        List<Intent> capturedIntents = intentCaptor.getAllValues();
        checkApStateChangedBroadcast(capturedIntents.get(0), WIFI_AP_STATE_ENABLING,
                WIFI_AP_STATE_DISABLED, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApConfig.getTargetMode());
        checkApStateChangedBroadcast(capturedIntents.get(1), WIFI_AP_STATE_ENABLED,
                WIFI_AP_STATE_ENABLING, HOTSPOT_NO_ERROR, TEST_INTERFACE_NAME,
                softApConfig.getTargetMode());
        assertEquals(0, mSoftApManager.getNumAssociatedStations());
    }

    /** Verifies that soft AP was not disabled. */
    protected void verifySoftApNotDisabled() throws Exception {
        verify(mListener, never()).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLING, 0);
        verify(mListener, never()).onStateChanged(WifiManager.WIFI_AP_STATE_DISABLED, 0);
    }

    private void checkApStateChangedBroadcast(Intent intent, int expectedCurrentState,
                                              int expectedPrevState, int expectedErrorCode,
                                              String expectedIfaceName, int expectedMode) {
        int currentState = intent.getIntExtra(EXTRA_WIFI_AP_STATE, WIFI_AP_STATE_DISABLED);
        int prevState = intent.getIntExtra(EXTRA_PREVIOUS_WIFI_AP_STATE, WIFI_AP_STATE_DISABLED);
        int errorCode = intent.getIntExtra(EXTRA_WIFI_AP_FAILURE_REASON, HOTSPOT_NO_ERROR);
        String ifaceName = intent.getStringExtra(EXTRA_WIFI_AP_INTERFACE_NAME);
        int mode = intent.getIntExtra(EXTRA_WIFI_AP_MODE, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        assertEquals(expectedCurrentState, currentState);
        assertEquals(expectedPrevState, prevState);
        assertEquals(expectedErrorCode, errorCode);
        assertEquals(expectedIfaceName, ifaceName);
        assertEquals(expectedMode, mode);
    }
}
