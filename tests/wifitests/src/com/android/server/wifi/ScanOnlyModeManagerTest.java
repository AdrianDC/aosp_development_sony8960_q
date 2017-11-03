/*
 * Copyright 2018 The Android Open Source Project
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

import static android.net.wifi.WifiManager.EXTRA_SCAN_AVAILABLE;
import static android.net.wifi.WifiManager.WIFI_SCAN_AVAILABLE;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.IClientInterface;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Pair;

import com.android.server.net.BaseNetworkObserver;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link ScanOnlyModeManager}.
 */
@SmallTest
public class ScanOnlyModeManagerTest {
    private static final String TAG = "ScanOnlyModeManagerTest";
    private static final String TEST_INTERFACE_NAME = "testif0";
    private static final String OTHER_INTERFACE_NAME = "notTestIf";

    TestLooper mLooper;

    ScanOnlyModeManager mScanOnlyModeManager;
    BaseNetworkObserver mNetworkObserver;

    @Mock Context mContext;
    @Mock IClientInterface mClientInterface;
    @Mock IBinder mClientInterfaceBinder;
    @Mock WifiMetrics mWifiMetrics;
    @Mock WifiNative mWifiNative;
    @Mock INetworkManagementService mNmService;

    final ArgumentCaptor<BaseNetworkObserver> mNetworkObserverCaptor =
            ArgumentCaptor.forClass(BaseNetworkObserver.class);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
        when(mClientInterface.asBinder()).thenReturn(mClientInterfaceBinder);

        mScanOnlyModeManager = createScanOnlyModeManager();
        mLooper.dispatchAll();
    }

    private ScanOnlyModeManager createScanOnlyModeManager() {
        return new ScanOnlyModeManager(mContext, mLooper.getLooper(), mWifiNative,
                mNmService, mWifiMetrics);
    }

    private void startScanOnlyModeAndVerifyEnabled() throws Exception {
        when(mWifiNative.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
        when(mClientInterface.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.setupForClientMode(eq(TEST_INTERFACE_NAME)))
                .thenReturn(Pair.create(WifiNative.SETUP_SUCCESS, mClientInterface));
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();

        verify(mWifiNative).setupForClientMode(eq(TEST_INTERFACE_NAME));
        verify(mNmService).registerObserver(mNetworkObserverCaptor.capture());
        mNetworkObserver = (BaseNetworkObserver) mNetworkObserverCaptor.getValue();

        // now mark the interface as up
        mNetworkObserver.interfaceLinkStateChanged(TEST_INTERFACE_NAME, true);
        mLooper.dispatchAll();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_ENABLED);

        // TODO: verify callback is notified that scan mode is active
    }

    private void checkWifiScanStateChangedBroadcast(Intent intent, int expectedCurrentState) {
        String action = intent.getAction();
        assertEquals(WIFI_SCAN_AVAILABLE, action);
        int currentState = intent.getIntExtra(EXTRA_SCAN_AVAILABLE, WIFI_STATE_UNKNOWN);
        assertEquals(expectedCurrentState, currentState);
    }

    /**
     * ScanMode start sets up an interface in ClientMode for scanning.
     */
    @Test
    public void scanModeStartCreatesClientInterface() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
    }

    /**
     * ScanMode increments failure metrics when failing to setup client mode interface due to hal
     * error.
     */
    @Test
    public void detectAndReportErrorWhenSetupForClientModeHalFailure() throws Exception {
        when(mWifiNative.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.setupForClientMode(eq(TEST_INTERFACE_NAME)))
                .thenReturn(Pair.create(WifiNative.SETUP_FAILURE_HAL, null));
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();

        verify(mWifiMetrics).incrementNumWifiOnFailureDueToHal();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_DISABLED);
    }

    /**
     * ScanMode increments failure metrics when failing to setup client mode interface due to
     * wificond error.
     */
    @Test
    public void detectAndReportErrorWhenSetupForClientModeWificondFailure() throws Exception {
        when(mWifiNative.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.setupForClientMode(eq(TEST_INTERFACE_NAME)))
              .thenReturn(Pair.create(WifiNative.SETUP_FAILURE_WIFICOND, null));
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();

        verify(mWifiMetrics).incrementNumWifiOnFailureDueToWificond();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_DISABLED);
    }

    /**
     * ScanMode start does not crash when interface creation reports success but returns a null
     * interface.
     */
    @Test
    public void scanModeStartDoesNotCrashWhenClientInterfaceIsNull() throws Exception {
        when(mWifiNative.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.setupForClientMode(eq(TEST_INTERFACE_NAME)))
                .thenReturn(Pair.create(WifiNative.SETUP_SUCCESS, null));
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_DISABLED);
    }

    /**
     * ScanMode start does not crash when the call to get the interface name throws a
     * RemoteException.
     */
    @Test
    public void scanModeStartDoesNotCrashWhenGetClientInterfaceNameThrowsException()
            throws Exception {
        when(mWifiNative.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
        doThrow(new RemoteException()).when(mClientInterface).getInterfaceName();
        when(mWifiNative.setupForClientMode(eq(TEST_INTERFACE_NAME)))
                .thenReturn(Pair.create(WifiNative.SETUP_SUCCESS, mClientInterface));
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_DISABLED);
    }

    /**
     * ScanMode start does not crash when the interface name is null.
     */
    @Test
    public void scanModeStartDoesNotCrashWhenClientInterfaceNameNull() throws Exception {
        when(mWifiNative.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
        when(mClientInterface.getInterfaceName()).thenReturn(null);
        when(mWifiNative.setupForClientMode(eq(TEST_INTERFACE_NAME)))
                .thenReturn(Pair.create(WifiNative.SETUP_SUCCESS, mClientInterface));
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();
    }

    /**
     * ScanMode start does not crash when the interface name is empty.
     */
    @Test
    public void scanModeStartDoesNotCrashWhenClientInterfaceNameIsEmpty() throws Exception {
        when(mWifiNative.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
        when(mClientInterface.getInterfaceName()).thenReturn("");
        when(mWifiNative.setupForClientMode(eq(TEST_INTERFACE_NAME)))
                .thenReturn(Pair.create(WifiNative.SETUP_SUCCESS, mClientInterface));
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();
    }

    /**
     * Calling ScanOnlyModeManager.start twice does not crash or restart scan mode.
     */
    @Test
    public void scanOnlyModeStartCalledTwice() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        reset(mWifiNative);
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();
        verifyNoMoreInteractions(mWifiNative);
    }

    /**
     * ScanMode stop properly cleans up state
     */
    @Test
    public void scanModeStopCleansUpState() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        reset(mContext);
        mScanOnlyModeManager.stop();
        mLooper.dispatchAll();
        verify(mNmService).unregisterObserver(eq(mNetworkObserver));
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_DISABLED);

        //TODO: verify callback is triggered on stop
    }

    /**
     * Calling stop when ScanMode is not started should not crash.
     */
    @Test
    public void scanModeStopWhenNotStartedDoesNotCrash() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        mScanOnlyModeManager.stop();
        mLooper.dispatchAll();
        reset(mNmService, mContext);

        // now call stop again
        mScanOnlyModeManager.stop();
        mLooper.dispatchAll();
        verify(mContext, never()).sendStickyBroadcastAsUser(any(), any());
    }

    /**
     * Triggering interface down when ScanOnlyMode is active properly exits the active state.
     */
    @Test
    public void scanModeStartedStopsWhenInterfaceDown() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        reset(mContext);
        mNetworkObserver.interfaceLinkStateChanged(TEST_INTERFACE_NAME, false);
        mLooper.dispatchAll();
        verify(mNmService).unregisterObserver(eq(mNetworkObserver));
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendStickyBroadcastAsUser(intentCaptor.capture(), eq(UserHandle.ALL));
        checkWifiScanStateChangedBroadcast(intentCaptor.getValue(), WIFI_STATE_DISABLED);
    }

    /**
     * This is a basic test that will be enhanced as functionality is added to the class.
     * Test that failing to register the Networkobserver for the interface does not crash.  Later
     * CLs will expand the test to verify proper failure reporting and other error signals.
     */
    @Test
    public void startScanModeNetworkObserverFailureReportsError() throws Exception {
        when(mWifiNative.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
        when(mClientInterface.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.setupForClientMode(eq(TEST_INTERFACE_NAME)))
                .thenReturn(Pair.create(WifiNative.SETUP_SUCCESS, mClientInterface));
        doThrow(new RemoteException()).when(mNmService).registerObserver(any());

        mScanOnlyModeManager.start();
        mLooper.dispatchAll();
        verify(mNmService).unregisterObserver(any());
    }


    /**
     * Triggering interface down for a different interface when ScanOnlyMode is active dows not exit
     * the active state.
     */
    @Test
    public void scanModeStartedDoesNotStopForDifferentInterfaceDown() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        reset(mContext);
        mNetworkObserver.interfaceLinkStateChanged(OTHER_INTERFACE_NAME, false);
        mLooper.dispatchAll();
        verify(mNmService, never()).unregisterObserver(eq(mNetworkObserver));
        verify(mContext, never()).sendStickyBroadcastAsUser(any(), eq(UserHandle.ALL));
    }

    /**
     * Triggering an interface up for a different interface does not trigger a notification to
     * Scanning service that ScanOnlyMode is ready.
     * This test will be enhanced when the broadcast is added.
     */
    @Test
    public void scanModeStartDoesNotNotifyScanningServiceForDifferentInterfaceUp()
            throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        reset(mContext);

        // now mark a different interface as up
        mNetworkObserver.interfaceLinkStateChanged(OTHER_INTERFACE_NAME, true);
        mLooper.dispatchAll();

        verify(mContext, never()).sendStickyBroadcastAsUser(any(), any());

        //TODO: verify listener is not called when it is added in a followon CL
    }

    /**
     * Callbacks from an old NetworkObserver do not trigger changes with a current NetworkObserver.
     */
    @Test
    public void scanModeNetworkObserverCallbacksOnlyForCurrentObserver() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        BaseNetworkObserver firstObserver = mNetworkObserver;
        mScanOnlyModeManager.stop();
        mLooper.dispatchAll();
        reset(mNmService, mClientInterfaceBinder, mWifiNative, mContext);
        startScanOnlyModeAndVerifyEnabled();
        reset(mContext);

        firstObserver.interfaceLinkStateChanged(TEST_INTERFACE_NAME, false);
        mLooper.dispatchAll();
        verify(mNmService, never()).unregisterObserver(eq(mNetworkObserver));
        verify(mContext, never()).sendStickyBroadcastAsUser(any(), any());
    }
}
