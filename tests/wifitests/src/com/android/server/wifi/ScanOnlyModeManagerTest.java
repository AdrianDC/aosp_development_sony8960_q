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

import static org.mockito.Mockito.*;

import android.net.wifi.IClientInterface;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Pair;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link ScanOnlyModeManager}.
 */
@SmallTest
public class ScanOnlyModeManagerTest {
    private static final String TAG = "ScanOnlyModeManagerTest";
    private static final String TEST_INTERFACE_NAME = "testif0";

    TestLooper mLooper;

    ScanOnlyModeManager mScanOnlyModeManager;

    @Mock IClientInterface mClientInterface;
    @Mock IBinder mClientInterfaceBinder;
    @Mock WifiMetrics mWifiMetrics;
    @Mock WifiNative mWifiNative;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
        when(mClientInterface.asBinder()).thenReturn(mClientInterfaceBinder);

        mScanOnlyModeManager = createScanOnlyModeManager();
        mLooper.dispatchAll();
    }

    private ScanOnlyModeManager createScanOnlyModeManager() {
        return new ScanOnlyModeManager(mLooper.getLooper(), mWifiNative, mWifiMetrics);
    }

    private void startScanOnlyModeAndVerifyEnabled() throws Exception {
        when(mWifiNative.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
        when(mClientInterface.getInterfaceName()).thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.setupForClientMode(eq(TEST_INTERFACE_NAME)))
                .thenReturn(Pair.create(WifiNative.SETUP_SUCCESS, mClientInterface));
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();

        // Verification will be added when the ScanStateChangedBroadcast is moved over to
        // ScanModeManager
    }

    /**
     * ScanMode start sets up an interface in ClientMode for scanning.
     */
    @Test
    public void scanModeStartCreatesClientInterface() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        verify(mWifiNative).setupForClientMode(eq(TEST_INTERFACE_NAME));
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
     * This is a basic test that will be enhanced as funtionality is added to the class.
     */
    @Test
    public void scanModeStopDoesNotCrash() throws Exception {
        startScanOnlyModeAndVerifyEnabled();
        mScanOnlyModeManager.stop();
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
     * Calling stop when ScanMode is not started should not crash.
     */
    @Test
    public void scanModeStopWhenNotStartedDoesNotCrash() throws Exception {
        mScanOnlyModeManager.stop();
        mLooper.dispatchAll();
    }
}
