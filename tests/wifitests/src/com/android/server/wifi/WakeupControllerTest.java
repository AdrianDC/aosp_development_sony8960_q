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

package com.android.server.wifi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiScanner;
import android.os.test.TestLooper;
import android.provider.Settings;

import com.android.server.wifi.util.ScanResultUtil;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * Unit tests for {@link WakeupController}.
 */
public class WakeupControllerTest {

    @Mock private Context mContext;
    @Mock private WakeupLock mWakeupLock;
    @Mock private WakeupEvaluator mWakeupEvaluator;
    @Mock private WakeupOnboarding mWakeupOnboarding;
    @Mock private WifiConfigStore mWifiConfigStore;
    @Mock private WifiInjector mWifiInjector;
    @Mock private WifiScanner mWifiScanner;
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private WifiSettingsStore mWifiSettingsStore;
    @Mock private WifiWakeMetrics mWifiWakeMetrics;
    @Mock private WifiController mWifiController;

    private TestLooper mLooper;
    private WakeupController mWakeupController;
    private WakeupConfigStoreData mWakeupConfigStoreData;
    private WifiScanner.ScanData[] mTestScanDatas;
    private ScanResult mTestScanResult;

    /** Initialize objects before each test run. */
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mWifiInjector.getWifiScanner()).thenReturn(mWifiScanner);
        when(mWifiInjector.getWifiSettingsStore()).thenReturn(mWifiSettingsStore);
        when(mWifiInjector.getWifiController()).thenReturn(mWifiController);
        when(mWifiSettingsStore.handleWifiToggled(anyBoolean())).thenReturn(true);

        mLooper = new TestLooper();

        // scanlistener input
        mTestScanResult = new ScanResult();
        mTestScanResult.SSID = "open ssid 1";
        mTestScanResult.capabilities = "";
        ScanResult[] scanResults = new ScanResult[1];
        scanResults[0] = mTestScanResult;
        mTestScanDatas = new WifiScanner.ScanData[1];
        mTestScanDatas[0] = new WifiScanner.ScanData(0 /* id */, 0 /* flags */,
                0 /* bucketsScanned */, true /* allChannelsScanned */, scanResults);
    }

    /** Initializes the wakeupcontroller in the given {@code enabled} state. */
    private void initializeWakeupController(boolean enabled) {
        initializeWakeupController(enabled, true /* isRead */);
    }

    private void initializeWakeupController(boolean enabled, boolean isRead) {
        int settingsValue = enabled ? 1 : 0;
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_WAKEUP_ENABLED, 0)).thenReturn(settingsValue);
        when(mWakeupOnboarding.isOnboarded()).thenReturn(true);
        mWakeupController = new WakeupController(mContext,
                mLooper.getLooper(),
                mWakeupLock,
                mWakeupEvaluator,
                mWakeupOnboarding,
                mWifiConfigManager,
                mWifiConfigStore,
                mWifiWakeMetrics,
                mWifiInjector,
                mFrameworkFacade);

        ArgumentCaptor<WakeupConfigStoreData> captor =
                ArgumentCaptor.forClass(WakeupConfigStoreData.class);
        verify(mWifiConfigStore).registerStoreData(captor.capture());
        mWakeupConfigStoreData = captor.getValue();
        if (isRead) {
            readUserStore();
        }
    }

    private void readUserStore() {
        try {
            mWakeupConfigStoreData.deserializeData(null, 0, false);
        } catch (XmlPullParserException | IOException e) {
            // unreachable
        }
    }

    private ScanResult createOpenScanResult(String ssid) {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = ssid;
        scanResult.capabilities = "";
        return scanResult;
    }

    private void verifyDoesNotEnableWifi() {
        verify(mWifiSettingsStore, never()).handleWifiToggled(true /* wifiEnabled */);
    }

    /**
     * Verify WakeupController is enabled when the settings toggle is true.
     */
    @Test
    public void verifyEnabledWhenToggledOn() {
        initializeWakeupController(true /* enabled */);

        assertTrue(mWakeupController.isEnabled());
    }

    /**
     * Verify WakeupController is disabled when the settings toggle is false.
     */
    @Test
    public void verifyDisabledWhenToggledOff() {
        initializeWakeupController(false /* enabled */);

        assertFalse(mWakeupController.isEnabled());
    }

    /**
     * Verify WakeupController registers its store data with the WifiConfigStore on construction.
     */
    @Test
    public void registersWakeupConfigStoreData() {
        initializeWakeupController(true /* enabled */);
        verify(mWifiConfigStore).registerStoreData(any(WakeupConfigStoreData.class));
    }

    /**
     * Verify that dump calls also dump the state of the WakeupLock.
     */
    @Test
    public void dumpIncludesWakeupLock() {
        initializeWakeupController(true /* enabled */);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        mWakeupController.dump(null, writer, null);

        verify(mWakeupLock).dump(null, writer, null);
    }

    /**
     * Verify that start initializes the wakeup lock.
     */
    @Test
    public void startInitializesWakeupLock() {
        initializeWakeupController(true /* enabled */);
        mWakeupController.start();
        verify(mWakeupLock).initialize(any());
        verify(mWifiWakeMetrics).recordStartEvent(anyInt());
    }

    /**
     * Verify that start does not initialize the wakeup lock when feature is disabled.
     */
    @Test
    public void startDoesNotInitializeWakeupLockWhenDisabled() {
        initializeWakeupController(false /* enabled */);
        mWakeupController.start();
        verify(mWakeupLock, never()).initialize(any());
        verify(mWifiWakeMetrics, never()).recordStartEvent(anyInt());
    }

    /**
     * Verify that start does not re-initialize the wakeup lock if the controller is already active.
     */
    @Test
    public void startDoesNotInitializeWakeupLockIfAlreadyActive() {
        initializeWakeupController(true /* enabled */);
        InOrder lockInOrder = Mockito.inOrder(mWakeupLock);
        InOrder metricsInOrder = Mockito.inOrder(mWifiWakeMetrics);

        mWakeupController.start();
        lockInOrder.verify(mWakeupLock).initialize(any());
        metricsInOrder.verify(mWifiWakeMetrics).recordStartEvent(anyInt());

        mWakeupController.stop();
        mWakeupController.start();
        lockInOrder.verify(mWakeupLock, never()).initialize(any());
        metricsInOrder.verify(mWifiWakeMetrics, never()).recordStartEvent(anyInt());
    }

    /**
     * Verify that start registers the scan listener on the wifi scanner.
     */
    @Test
    public void startRegistersScanListener() {
        initializeWakeupController(true /* enabled */);
        mWakeupController.start();
        verify(mWifiScanner).registerScanListener(any());
    }

    /**
     * Verify that stop deregisters the scan listener from the wifi scanner.
     */
    @Test
    public void stopDeresgistersScanListener() {
        initializeWakeupController(true /* enabled */);
        mWakeupController.start();
        mWakeupController.stop();
        verify(mWifiScanner).deregisterScanListener(any());
    }

    /**
     * Verify that reset sets active to false.
     *
     * <p>This is accomplished by initiating another call to start and verifying that the wakeup
     * lock is re-initialized.
     */
    @Test
    public void resetSetsActiveToFalse() {
        initializeWakeupController(true /* enabled */);
        InOrder lockInOrder = Mockito.inOrder(mWakeupLock);
        InOrder metricsInOrder = Mockito.inOrder(mWifiWakeMetrics);

        mWakeupController.start();
        lockInOrder.verify(mWakeupLock).initialize(any());
        metricsInOrder.verify(mWifiWakeMetrics).recordStartEvent(anyInt());

        mWakeupController.stop();
        mWakeupController.reset();
        metricsInOrder.verify(mWifiWakeMetrics).recordResetEvent(0 /* numScans */);

        mWakeupController.start();
        lockInOrder.verify(mWakeupLock).initialize(any());
        metricsInOrder.verify(mWifiWakeMetrics).recordStartEvent(anyInt());
    }

    /**
     * Verify that the wakeup lock is initialized with the intersection of ScanResults and saved
     * networks.
     */
    @Test
    public void startInitializesWakeupLockWithSavedScanResults() {
        String ssid1 = "ssid 1";
        String ssid2 = "ssid 2";
        String quotedSsid = ScanResultUtil.createQuotedSSID(ssid1);

        // saved configs
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork(quotedSsid);
        openNetwork.getNetworkSelectionStatus().setHasEverConnected(true);
        WifiConfiguration wepNetwork = WifiConfigurationTestUtil.createWepNetwork();
        wepNetwork.getNetworkSelectionStatus().setHasEverConnected(true);
        when(mWifiConfigManager.getSavedNetworks())
                .thenReturn(Arrays.asList(openNetwork, wepNetwork));

        // scan results from most recent scan
        ScanResult savedScanResult = createOpenScanResult(ssid1);
        ScanResult unsavedScanResult = createOpenScanResult(ssid2);
        when(mWifiScanner.getSingleScanResults())
                .thenReturn(Arrays.asList(savedScanResult, unsavedScanResult));

        // intersection of most recent scan + saved configs
        Collection<ScanResultMatchInfo> expectedMatchInfos =
                Collections.singleton(ScanResultMatchInfo.fromScanResult(savedScanResult));

        initializeWakeupController(true /* enabled */);
        mWakeupController.start();
        verify(mWakeupLock).initialize(eq(expectedMatchInfos));
        verify(mWifiWakeMetrics).recordStartEvent(expectedMatchInfos.size());
    }

    /**
     * Verify that onResults updates the WakeupLock.
     */
    @Test
    public void onResultsUpdatesWakeupLock() {
        initializeWakeupController(true /* enabled */);
        mWakeupController.start();

        ArgumentCaptor<WifiScanner.ScanListener> scanListenerArgumentCaptor =
                ArgumentCaptor.forClass(WifiScanner.ScanListener.class);

        verify(mWifiScanner).registerScanListener(scanListenerArgumentCaptor.capture());
        WifiScanner.ScanListener scanListener = scanListenerArgumentCaptor.getValue();

        // incoming scan results
        scanListener.onResults(mTestScanDatas);

        ScanResultMatchInfo expectedMatchInfo = ScanResultMatchInfo.fromScanResult(mTestScanResult);
        verify(mWakeupLock).update(eq(Collections.singleton(expectedMatchInfo)));
    }

    /**
     * Verify that WifiWakeMetrics logs the unlock event when the WakeupLock is emptied.
     */
    @Test
    public void recordMetricsWhenWakeupLockIsEmptied() {
        initializeWakeupController(true /* enabled */);
        mWakeupController.start();

        // Simulates emptying the lock: first returns false then returns true
        when(mWakeupLock.isEmpty()).thenReturn(false).thenReturn(true);

        ArgumentCaptor<WifiScanner.ScanListener> scanListenerArgumentCaptor =
                ArgumentCaptor.forClass(WifiScanner.ScanListener.class);

        verify(mWifiScanner).registerScanListener(scanListenerArgumentCaptor.capture());
        WifiScanner.ScanListener scanListener = scanListenerArgumentCaptor.getValue();

        // incoming scan results
        scanListener.onResults(mTestScanDatas);

        verify(mWakeupLock, times(2)).isEmpty();
        verify(mWifiWakeMetrics).recordUnlockEvent(1 /* numScans */);
    }

    /**
     * Verify that the controller searches for viable networks during onResults when WakeupLock is
     * empty.
     */
    @Test
    public void onResultsSearchesForViableNetworkWhenWakeupLockIsEmpty() {
        // empty wakeup lock
        when(mWakeupLock.isEmpty()).thenReturn(true);
        // do not find viable network
        when(mWakeupEvaluator.findViableNetwork(any(), any())).thenReturn(null);

        initializeWakeupController(true /* enabled */);
        mWakeupController.start();

        ArgumentCaptor<WifiScanner.ScanListener> scanListenerArgumentCaptor =
                ArgumentCaptor.forClass(WifiScanner.ScanListener.class);

        verify(mWifiScanner).registerScanListener(scanListenerArgumentCaptor.capture());
        WifiScanner.ScanListener scanListener = scanListenerArgumentCaptor.getValue();

        // incoming scan results
        scanListener.onResults(mTestScanDatas);

        verify(mWakeupEvaluator).findViableNetwork(any(), any());
        verifyDoesNotEnableWifi();
    }

    /**
     * Verify that the controller only updates the WakeupLock if the user is onboarded.
     */
    @Test
    public void onResultsDoesNotUpdateIfNotOnboarded() {
        initializeWakeupController(true /* enabled */);
        when(mWakeupOnboarding.isOnboarded()).thenReturn(false);
        mWakeupController.start();

        ArgumentCaptor<WifiScanner.ScanListener> scanListenerArgumentCaptor =
                ArgumentCaptor.forClass(WifiScanner.ScanListener.class);

        verify(mWifiScanner).registerScanListener(scanListenerArgumentCaptor.capture());
        WifiScanner.ScanListener scanListener = scanListenerArgumentCaptor.getValue();

        // incoming scan results
        scanListener.onResults(mTestScanDatas);

        verify(mWakeupLock, never()).isEmpty();
        verify(mWakeupLock, never()).update(any());

        verifyDoesNotEnableWifi();
    }

    /**
     * Verify that the controller enables wifi and notifies user when all criteria are met.
     */
    @Test
    public void onResultsEnablesWifi() {
        // empty wakeup lock
        when(mWakeupLock.isEmpty()).thenReturn(true);
        // find viable network
        when(mWakeupEvaluator.findViableNetwork(any(), any())).thenReturn(mTestScanResult);

        initializeWakeupController(true /* enabled */);
        mWakeupController.start();

        ArgumentCaptor<WifiScanner.ScanListener> scanListenerArgumentCaptor =
                ArgumentCaptor.forClass(WifiScanner.ScanListener.class);

        verify(mWifiScanner).registerScanListener(scanListenerArgumentCaptor.capture());
        WifiScanner.ScanListener scanListener = scanListenerArgumentCaptor.getValue();

        // incoming scan results
        scanListener.onResults(mTestScanDatas);

        verify(mWakeupEvaluator).findViableNetwork(any(), any());
        verify(mWifiSettingsStore).handleWifiToggled(true /* wifiEnabled */);
        verify(mWifiWakeMetrics).recordWakeupEvent(1 /* numScans */);
    }

    /**
     * Verify that the controller will not do any work if the user store has not been read.
     */
    @Test
    public void controllerDoesNoWorkIfUserStoreIsNotRead() {
        initializeWakeupController(true /* enabled */, false /* isRead */);
        mWakeupController.start();

        ArgumentCaptor<WifiScanner.ScanListener> scanListenerArgumentCaptor =
                ArgumentCaptor.forClass(WifiScanner.ScanListener.class);

        verify(mWifiScanner).registerScanListener(scanListenerArgumentCaptor.capture());
        WifiScanner.ScanListener scanListener = scanListenerArgumentCaptor.getValue();

        // incoming scan results
        scanListener.onResults(mTestScanDatas);

        verify(mWakeupLock, never()).initialize(any());
        verify(mWakeupLock, never()).update(any());
        verify(mWakeupLock, never()).isEmpty();
        verify(mWakeupOnboarding, never()).maybeShowNotification();
        verify(mWakeupEvaluator, never()).findViableNetwork(any(), any());
    }
}
