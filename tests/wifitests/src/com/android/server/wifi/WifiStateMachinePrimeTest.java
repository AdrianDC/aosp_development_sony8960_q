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
import static org.mockito.Mockito.*;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.BatteryStats;
import android.os.test.TestLooper;
import android.support.test.filters.SmallTest;
import android.util.Log;

import com.android.internal.app.IBatteryStats;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link com.android.server.wifi.WifiStateMachinePrime}.
 */
@SmallTest
public class WifiStateMachinePrimeTest {
    public static final String TAG = "WifiStateMachinePrimeTest";

    private static final String CLIENT_MODE_STATE_STRING = "ClientModeActiveState";
    private static final String SCAN_ONLY_MODE_STATE_STRING = "ScanOnlyModeActiveState";
    private static final String SOFT_AP_MODE_STATE_STRING = "SoftAPModeActiveState";
    private static final String WIFI_DISABLED_STATE_STRING = "WifiDisabledState";
    private static final String WIFI_IFACE_NAME = "mockWlan";

    @Mock WifiInjector mWifiInjector;
    @Mock WifiNative mWifiNative;
    @Mock WifiApConfigStore mWifiApConfigStore;
    TestLooper mLooper;
    @Mock ScanOnlyModeManager mScanOnlyModeManager;
    @Mock SoftApManager mSoftApManager;
    @Mock DefaultModeManager mDefaultModeManager;
    @Mock IBatteryStats mBatteryStats;
    @Mock SelfRecovery mSelfRecovery;
    @Mock BaseWifiDiagnostics mWifiDiagnostics;
    ScanOnlyModeManager.Listener mScanOnlyListener;
    ScanOnlyModeCallback mScanOnlyCallback = new ScanOnlyModeCallback();
    ClientModeCallback mClientModeCallback = new ClientModeCallback();
    WifiManager.SoftApCallback mSoftApManagerCallback;
    @Mock WifiManager.SoftApCallback mSoftApStateMachineCallback;
    WifiNative.StatusListener mWifiNativeStatusListener;
    WifiStateMachinePrime mWifiStateMachinePrime;

    final ArgumentCaptor<WifiNative.StatusListener> mStatusListenerCaptor =
            ArgumentCaptor.forClass(WifiNative.StatusListener.class);

    /**
     * Set up the test environment.
     */
    @Before
    public void setUp() throws Exception {
        Log.d(TAG, "Setting up ...");

        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();

        when(mWifiInjector.getSelfRecovery()).thenReturn(mSelfRecovery);
        when(mWifiInjector.makeWifiDiagnostics(eq(mWifiNative))).thenReturn(mWifiDiagnostics);

        mWifiStateMachinePrime = createWifiStateMachinePrime();
        mLooper.dispatchAll();

        verify(mWifiNative).registerStatusListener(mStatusListenerCaptor.capture());
        mWifiNativeStatusListener = mStatusListenerCaptor.getValue();

        mWifiStateMachinePrime.registerSoftApCallback(mSoftApStateMachineCallback);
        mWifiStateMachinePrime.registerScanOnlyCallback(mScanOnlyCallback);
        mWifiStateMachinePrime.registerClientModeCallback(mClientModeCallback);
    }

    private WifiStateMachinePrime createWifiStateMachinePrime() {
        return new WifiStateMachinePrime(mWifiInjector,
                                         mLooper.getLooper(),
                                         mWifiNative,
                                         mDefaultModeManager,
                                         mBatteryStats);
    }

    /**
     * Clean up after tests - explicitly set tested object to null.
     */
    @After
    public void cleanUp() throws Exception {
        mWifiStateMachinePrime = null;
    }

    private class ClientModeCallback implements ClientModeManager.Listener {
        public int currentState = WifiManager.WIFI_STATE_UNKNOWN;

        @Override
        public void onStateChanged(int state) {
            currentState = state;
        }
    }

    private class ScanOnlyModeCallback implements ScanOnlyModeManager.Listener {
        public int currentState = WifiManager.WIFI_STATE_UNKNOWN;

        @Override
        public void onStateChanged(int state) {
            currentState = state;
        }
    }

    private void enterSoftApActiveMode() throws Exception {
        enterSoftApActiveMode(
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, null));
    }

    /**
     * Helper method to enter the ScanOnlyModeActiveState for WifiStateMachinePrime.
     */
    private void enterScanOnlyModeActiveState() throws Exception {
        String fromState = mWifiStateMachinePrime.getCurrentMode();
        doAnswer(
                new Answer<Object>() {
                        public ScanOnlyModeManager answer(InvocationOnMock invocation) {
                            Object[] args = invocation.getArguments();
                            mScanOnlyListener = (ScanOnlyModeManager.Listener) args[0];
                            return mScanOnlyModeManager;
                        }
                }).when(mWifiInjector).makeScanOnlyModeManager(
                        any(ScanOnlyModeManager.Listener.class));
        mWifiStateMachinePrime.enterScanOnlyMode();
        mLooper.dispatchAll();
        mScanOnlyListener.onStateChanged(WifiManager.WIFI_STATE_ENABLED);
        mLooper.dispatchAll();

        assertEquals(SCAN_ONLY_MODE_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mScanOnlyModeManager).start();
        verify(mBatteryStats).noteWifiOn();
        verify(mBatteryStats).noteWifiState(eq(BatteryStats.WIFI_STATE_OFF_SCANNING), eq(null));
    }

    /**
     * Helper method to enter the SoftApActiveMode for WifiStateMachinePrime.
     *
     * This method puts the test object into the correct state and verifies steps along the way.
     */
    private void enterSoftApActiveMode(SoftApModeConfiguration softApConfig) throws Exception {
        String fromState = mWifiStateMachinePrime.getCurrentMode();
        doAnswer(
                new Answer<Object>() {
                    public SoftApManager answer(InvocationOnMock invocation) {
                        Object[] args = invocation.getArguments();
                        mSoftApManagerCallback = (WifiManager.SoftApCallback) args[0];
                        assertEquals(softApConfig, (SoftApModeConfiguration) args[1]);
                        return mSoftApManager;
                    }
                }).when(mWifiInjector).makeSoftApManager(any(WifiManager.SoftApCallback.class),
                                                         any());
        mWifiStateMachinePrime.enterSoftAPMode(softApConfig);
        mLooper.dispatchAll();
        assertEquals(SOFT_AP_MODE_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mSoftApManager).start();
        verify(mBatteryStats).noteWifiOn();
    }

    private void verifyCleanupCalled() {
        // for now, this is a single call, but make a helper to avoid adding any additional cleanup
        // checks
        verify(mWifiNative).teardownAllInterfaces();
    }

    /**
     * Test that after starting up, WSMP is in the Disabled State.
     */
    @Test
    public void testWifiDisabledAtStartup() throws Exception {
        verify(mWifiNative).teardownAllInterfaces();
        assertEquals(WIFI_DISABLED_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
    }

    /**
     * Test that WifiStateMachinePrime properly enters the ScanOnlyModeActiveState from the
     * WifiDisabled state.
     */
    @Test
    public void testEnterScanOnlyModeFromDisabled() throws Exception {
        enterScanOnlyModeActiveState();
        verify(mWifiNative, times(2)).teardownAllInterfaces();
    }

    /**
     * Test that WifiStateMachinePrime properly enters the SoftApModeActiveState from the
     * WifiDisabled state.
     */
    @Test
    public void testEnterSoftApModeFromDisabled() throws Exception {
        enterSoftApActiveMode();
        verify(mWifiNative, times(2)).teardownAllInterfaces();
    }

    /**
     * Test that WifiStateMachinePrime properly enters the SoftApModeActiveState from another state.
     * Expectations: When going from one state to another, cleanup will be called
     */
    @Test
    public void testEnterSoftApModeFromDifferentState() throws Exception {
        mWifiStateMachinePrime.enterClientMode();
        mLooper.dispatchAll();
        assertEquals(CLIENT_MODE_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        reset(mBatteryStats);
        enterSoftApActiveMode();
        // still only two times since we do not control the interface yet in client mode
        verify(mWifiNative, times(2)).teardownAllInterfaces();
    }

    /**
     * Test that we can disable wifi fully from the ScanOnlyModeActiveState.
     */
    @Test
    public void testDisableWifiFromScanOnlyModeActiveState() throws Exception {
        enterScanOnlyModeActiveState();

        mWifiStateMachinePrime.disableWifi();
        mLooper.dispatchAll();
        verify(mScanOnlyModeManager).stop();
        verify(mBatteryStats).noteWifiOff();
        assertEquals(WIFI_DISABLED_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mWifiNative, times(3)).teardownAllInterfaces();
    }

    /**
     * Test that we can disable wifi fully from the SoftApModeActiveState.
     */
    @Test
    public void testDisableWifiFromSoftApModeActiveState() throws Exception {
        enterSoftApActiveMode();

        mWifiStateMachinePrime.disableWifi();
        mLooper.dispatchAll();
        verify(mSoftApManager).stop();
        verify(mBatteryStats).noteWifiOff();
        assertEquals(WIFI_DISABLED_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mWifiNative, times(3)).teardownAllInterfaces();
    }

    /**
     * Test that we can disable wifi fully from the SoftApModeState.
     */
    @Test
    public void testDisableWifiFromSoftApModeState() throws Exception {
        enterSoftApActiveMode();
        // now inject failure through the SoftApManager.Listener
        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_FAILED, 0);
        mLooper.dispatchAll();
        assertEquals(WIFI_DISABLED_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());

        mWifiStateMachinePrime.disableWifi();
        mLooper.dispatchAll();
        verify(mBatteryStats).noteWifiOff();

        assertEquals(WIFI_DISABLED_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mWifiNative, times(4)).teardownAllInterfaces();
    }

    /**
     * Thest that we can switch from ScanOnlyActiveMode to another mode.
     * Expectation: When switching out of ScanOlyModeActivState we stop the ScanOnlyModeManager.
     */
    @Test
    public void testSwitchModeWhenScanOnlyModeActiveState() throws Exception {
        enterScanOnlyModeActiveState();

        mWifiStateMachinePrime.enterClientMode();
        mLooper.dispatchAll();
        verify(mScanOnlyModeManager).stop();
        assertEquals(CLIENT_MODE_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mWifiNative, times(2)).teardownAllInterfaces();
    }

    /**
     * Test that we can switch from SoftApActiveMode to another mode.
     * Expectation: When switching out of SoftApModeActiveState we stop the SoftApManager and tear
     * down existing interfaces.
     */
    @Test
    public void testSwitchModeWhenSoftApActiveMode() throws Exception {
        enterSoftApActiveMode();

        mWifiStateMachinePrime.enterClientMode();
        mLooper.dispatchAll();
        verify(mSoftApManager).stop();
        assertEquals(CLIENT_MODE_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mWifiNative, times(2)).teardownAllInterfaces();
    }

    /**
     * Test that we do enter the SoftApModeActiveState if we are already in WifiDisabledState due to
     * a failure.
     * Expectations: We should exit the current WifiDisabledState and re-enter before successfully
     * entering the SoftApModeActiveState.
     */
    @Test
    public void testEnterSoftApModeActiveWhenAlreadyInSoftApMode() throws Exception {
        enterSoftApActiveMode();
        // now inject failure through the SoftApManager.Listener
        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_FAILED, 0);
        mLooper.dispatchAll();
        assertEquals(WIFI_DISABLED_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        // clear the first call to start SoftApManager
        reset(mSoftApManager, mBatteryStats);

        enterSoftApActiveMode();
        verify(mWifiNative, times(4)).teardownAllInterfaces();
    }

    /**
     * Test that we return to the WifiDisabledState after a failure is reported when in the
     * ScanOnlyModeActiveState.
     * Expectations: we should exit the ScanOnlyModeActiveState and stop the ScanOnlyModeManager.
     */
    @Test
    public void testScanOnlyModeFailureWhenActive() throws Exception {
        enterScanOnlyModeActiveState();
        // now inject a failure through the ScanOnlyModeManager.Listener
        mScanOnlyListener.onStateChanged(WifiManager.WIFI_STATE_UNKNOWN);
        mLooper.dispatchAll();
        assertEquals(WIFI_DISABLED_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mScanOnlyModeManager).stop();
        verify(mWifiNative, times(3)).teardownAllInterfaces();
        verify(mBatteryStats).noteWifiOff();
        assertEquals(WifiManager.WIFI_STATE_UNKNOWN, mScanOnlyCallback.currentState);
    }

    /**
     * Test that we return to the WifiDisabledState after a failure is reported when in the
     * SoftApModeActiveState.
     * Expectations: We should exit the SoftApModeActiveState and stop the SoftApManager.
     */
    @Test
    public void testSoftApFailureWhenActive() throws Exception {
        enterSoftApActiveMode();
        // now inject failure through the SoftApManager.Listener
        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_FAILED, 0);
        mLooper.dispatchAll();
        assertEquals(WIFI_DISABLED_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mSoftApManager).stop();
        verify(mWifiNative, times(3)).teardownAllInterfaces();
        verify(mBatteryStats).noteWifiOff();
    }

    /**
     * Test that we return to the WifiDisabledState after the ScanOnlyModeManager is stopping in the
     * ScanOnlyModeActiveState.
     * Expectations: We should exit the ScanOnlyModeActiveState and stop the ScanOnlyModeManager.
     */
    @Test
    public void testScanOnlyModeDisabledWhenActive() throws Exception {
        enterScanOnlyModeActiveState();
        // now inject the stop message through the ScanOnlyModeManager.Listener
        mScanOnlyListener.onStateChanged(WifiManager.WIFI_STATE_DISABLED);
        mLooper.dispatchAll();
        assertEquals(WIFI_DISABLED_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mScanOnlyModeManager).stop();
        verify(mWifiNative, times(3)).teardownAllInterfaces();
        verify(mBatteryStats).noteWifiOff();
    }

    /**
     * Test that we return to the WifiDisabledState after the SoftApManager is stopped in the
     * SoftApModeActiveState.
     * Expectations: We should exit the SoftApModeActiveState and stop the SoftApManager.
     */
    @Test
    public void testSoftApDisabledWhenActive() throws Exception {
        enterSoftApActiveMode();
        // now inject failure through the SoftApManager.Listener
        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_FAILED, 0);
        mLooper.dispatchAll();
        assertEquals(WIFI_DISABLED_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mSoftApManager).stop();
        verify(mWifiNative, times(3)).teardownAllInterfaces();
        verify(mBatteryStats).noteWifiOff();
    }

    /**
     * Verifies that SoftApStateChanged event is being passed from SoftApManager to WifiServiceImpl
     */
    @Test
    public void callsWifiServiceCallbackOnSoftApStateChanged() throws Exception {
        enterSoftApActiveMode();

        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_ENABLED, 0);
        mLooper.dispatchAll();

        verify(mSoftApStateMachineCallback).onStateChanged(WifiManager.WIFI_AP_STATE_ENABLED, 0);
    }

    /**
     * Verifies that triggering a state change update will not crash if the callback to
     * WifiServiceImpl is null.
     */
    @Test
    public void testNullCallbackToWifiServiceImplForStateChange() throws Exception {
        //set the callback to null
        mWifiStateMachinePrime.registerSoftApCallback(null);

        enterSoftApActiveMode();

        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_DISABLING, 0);
        mLooper.dispatchAll();

        verify(mSoftApStateMachineCallback, never()).onStateChanged(anyInt(), anyInt());
    }

    /**
     * Verifies that NumClientsChanged event is being passed from SoftApManager to WifiServiceImpl
     */
    @Test
    public void callsWifiServiceCallbackOnSoftApNumClientsChanged() throws Exception {
        final int testNumClients = 3;
        enterSoftApActiveMode();
        mSoftApManagerCallback.onNumClientsChanged(testNumClients);
        mLooper.dispatchAll();

        verify(mSoftApStateMachineCallback).onNumClientsChanged(testNumClients);
    }

    /**
     * Verifies that triggering a number of clients changed update will not crash if the callback to
     * WifiServiceImpl is null.
     */
    @Test
    public void testNullCallbackToWifiServiceImplForNumClientsChanged() throws Exception {

        final int testNumClients = 3;

        //set the callback to null
        mWifiStateMachinePrime.registerSoftApCallback(null);

        enterSoftApActiveMode();
        mSoftApManagerCallback.onNumClientsChanged(testNumClients);

        verify(mSoftApStateMachineCallback, never()).onNumClientsChanged(anyInt());
    }

    /**
     * Test that we remain in the active state when we get a state change update that scan mode is
     * active.
     * Expectations: We should remain in the ScanOnlyModeActive state.
     */
    @Test
    public void testScanOnlyModeStaysActiveOnEnabledUpdate() throws Exception {
        enterScanOnlyModeActiveState();
        // now inject failure through the SoftApManager.Listener
        mScanOnlyListener.onStateChanged(WifiManager.WIFI_STATE_ENABLED);
        mLooper.dispatchAll();
        assertEquals(SCAN_ONLY_MODE_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mScanOnlyModeManager, never()).stop();
        verify(mWifiNative, times(2)).teardownAllInterfaces();
    }

    /**
     * Test that we do not act on unepected state string messages and remain in the active state.
     * Expectations: We should remain in the ScanOnlyModeActive state.
     */
    @Test
    public void testScanOnlyModeStaysActiveOnUnexpectedStateUpdate() throws Exception {
        enterScanOnlyModeActiveState();
        // now inject failure through the SoftApManager.Listener
        mScanOnlyListener.onStateChanged(WifiManager.WIFI_AP_STATE_DISABLING);
        mLooper.dispatchAll();
        assertEquals(SCAN_ONLY_MODE_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mScanOnlyModeManager, never()).stop();
        verify(mWifiNative, times(2)).teardownAllInterfaces();
    }

    /**
     * Test that a config passed in to the call to enterSoftApMode is used to create the new
     * SoftApManager.
     * Expectations: We should create a SoftApManager in WifiInjector with the config passed in to
     * WifiStateMachinePrime to switch to SoftApMode.
     */
    @Test
    public void testConfigIsPassedToWifiInjector() throws Exception {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "ThisIsAConfig";
        SoftApModeConfiguration softApConfig =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config);
        enterSoftApActiveMode(softApConfig);
    }

    /**
     * Test that when enterSoftAPMode is called with a null config, we pass a null config to
     * WifiInjector.makeSoftApManager.
     *
     * Passing a null config to SoftApManager indicates that the default config should be used.
     *
     * Expectations: WifiInjector should be called with a null config.
     */
    @Test
    public void testNullConfigIsPassedToWifiInjector() throws Exception {
        enterSoftApActiveMode();
    }

    /**
     * Test that two calls to switch to SoftAPMode in succession ends up with the correct config.
     *
     * Expectation: we should end up in SoftAPMode state configured with the second config.
     */
    @Test
    public void testStartSoftApModeTwiceWithTwoConfigs() throws Exception {
        when(mWifiInjector.getWifiApConfigStore()).thenReturn(mWifiApConfigStore);
        WifiConfiguration config1 = new WifiConfiguration();
        config1.SSID = "ThisIsAConfig";
        SoftApModeConfiguration softApConfig1 =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config1);
        WifiConfiguration config2 = new WifiConfiguration();
        config2.SSID = "ThisIsASecondConfig";
        SoftApModeConfiguration softApConfig2 =
                new SoftApModeConfiguration(WifiManager.IFACE_IP_MODE_TETHERED, config2);

        when(mWifiInjector.makeSoftApManager(any(WifiManager.SoftApCallback.class),
                                             eq(softApConfig1)))
                .thenReturn(mSoftApManager);
        when(mWifiInjector.makeSoftApManager(any(WifiManager.SoftApCallback.class),
                eq(softApConfig2)))
                .thenReturn(mSoftApManager);


        mWifiStateMachinePrime.enterSoftAPMode(softApConfig1);
        mWifiStateMachinePrime.enterSoftAPMode(softApConfig2);
        mLooper.dispatchAll();
        assertEquals(SOFT_AP_MODE_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mBatteryStats).noteWifiOff();
    }

    /**
     * Test that when softap manager reports that softap has stopped, we return to the
     * WifiDisabledState.
     */
    @Test
    public void testSoftApStopReturnsToWifiDisabled() throws Exception {
        enterSoftApActiveMode();

        mSoftApManagerCallback.onStateChanged(WifiManager.WIFI_AP_STATE_DISABLED, 0);
        mLooper.dispatchAll();
        assertEquals(WIFI_DISABLED_STATE_STRING, mWifiStateMachinePrime.getCurrentMode());
        verify(mWifiNative, times(3)).teardownAllInterfaces();
        verify(mBatteryStats).noteWifiOff();
    }

    /**
     * Test that we safely disable wifi if it is already disabled.
     * Expectations: We should not interact with WifiNative since we should have already cleaned up
     * everything.
     */
    @Test
    public void disableWifiWhenAlreadyOff() throws Exception {
        mWifiStateMachinePrime.disableWifi();
        // since we start up in disabled, this should not re-enter the disabled state
        verify(mWifiNative).teardownAllInterfaces();
    }

    /**
     * Trigger recovery and a bug report if we see a native failure.
     */
    @Test
    public void handleWifiNativeFailure() throws Exception {
        mWifiNativeStatusListener.onStatusChanged(false);
        mLooper.dispatchAll();
        verify(mWifiDiagnostics).captureBugReportData(
                WifiDiagnostics.REPORT_REASON_WIFINATIVE_FAILURE);
        verify(mSelfRecovery).trigger(eq(SelfRecovery.REASON_WIFINATIVE_FAILURE));
    }

    /**
     * Verify an onStatusChanged callback with "true" does not trigger recovery.
     */
    @Test
    public void handleWifiNativeStatusReady() throws Exception {
        mWifiNativeStatusListener.onStatusChanged(true);
        mLooper.dispatchAll();
        verify(mWifiDiagnostics, never()).captureBugReportData(
                WifiDiagnostics.REPORT_REASON_WIFINATIVE_FAILURE);
        verify(mSelfRecovery, never()).trigger(eq(SelfRecovery.REASON_WIFINATIVE_FAILURE));
    }
}
