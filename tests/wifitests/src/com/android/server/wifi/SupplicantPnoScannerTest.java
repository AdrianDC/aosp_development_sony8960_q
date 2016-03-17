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
 * limitations under the License
 */

package com.android.server.wifi;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiScanner;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.R;
import com.android.server.wifi.scanner.ChannelHelper.ChannelCollection;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.android.server.wifi.ScanTestUtil.NativeScanSettingsBuilder;
import static com.android.server.wifi.ScanTestUtil.assertScanDataEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link com.android.server.wifi.SupplicantWifiScannerImpl.setPnoList}.
 */
@SmallTest
public class SupplicantPnoScannerTest {

    @Mock Context mContext;
    MockAlarmManager mAlarmManager;
    MockWifiMonitor mWifiMonitor;
    MockLooper mLooper;
    @Mock WifiNative mWifiNative;
    MockResources mResources;
    SupplicantWifiScannerImpl mScanner;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        mLooper = new MockLooper();
        mAlarmManager = new MockAlarmManager();
        mWifiMonitor = new MockWifiMonitor();
        mResources = new MockResources();

        when(mWifiNative.getInterfaceName()).thenReturn("a_test_interface_name");
        when(mContext.getSystemService(Context.ALARM_SERVICE))
                .thenReturn(mAlarmManager.getAlarmManager());
        when(mContext.getResources()).thenReturn(mResources);
    }

    /**
     * Verify that the HW PNO scan triggers a supplicant PNO scan and invokes the OnPnoNetworkFound
     * callback when the scan results are received.
     */
    @Test
    public void startHwPnoScan() {
        createScannerWithHwPnoScanSupport();

        WifiNative.PnoEventHandler pnoEventHandler = mock(WifiNative.PnoEventHandler.class);
        WifiNative.PnoSettings pnoSettings = createDummyPnoSettings();
        ScanResults scanResults = createDummyScanResults();

        InOrder order = inOrder(pnoEventHandler, mWifiNative);

        // Start PNO scan
        startSuccessfulPnoScan(null, pnoSettings, null, pnoEventHandler);

        expectSuccessfulHwPnoScan(order, pnoSettings, pnoEventHandler, scanResults);

        verifyNoMoreInteractions(pnoEventHandler);
    }

    /**
     * Verify that we pause & resume HW PNO scan when a single scan is scheduled.
     */
    @Test
    public void pauseResumeHwPnoScanForSingleScan() {
        createScannerWithHwPnoScanSupport();

        WifiNative.PnoEventHandler pnoEventHandler = mock(WifiNative.PnoEventHandler.class);
        WifiNative.PnoSettings pnoSettings = createDummyPnoSettings();
        WifiNative.ScanEventHandler eventHandler = mock(WifiNative.ScanEventHandler.class);
        WifiNative.ScanSettings settings = createDummyScanSettings();
        ScanResults scanResults = createDummyScanResults();

        InOrder order = inOrder(eventHandler, mWifiNative);

        // Start PNO scan
        startSuccessfulPnoScan(null, pnoSettings, null, pnoEventHandler);

        // Start single scan
        assertTrue(mScanner.startSingleScan(settings, eventHandler));
        // Verify that the PNO scan was paused and single scan runs successfully
        expectSuccessfulSingleScanWithHwPnoEnabled(order, eventHandler,
                expectedBandScanFreqs(WifiScanner.WIFI_BAND_24_GHZ), new HashSet<Integer>(),
                scanResults);
        verifyNoMoreInteractions(eventHandler);

        order = inOrder(pnoEventHandler, mWifiNative);

        // Now verify that PNO scan is resumed successfully
        expectSuccessfulHwPnoScan(order, pnoSettings, pnoEventHandler, scanResults);
        verifyNoMoreInteractions(pnoEventHandler);
    }

    /**
     * Verify that the SW PNO scan triggers a background scan and invokes the OnPnoNetworkFound
     * callback when the scan results are received.
     */
    @Test
    public void startSwPnoScan() {
        createScannerWithSwPnoScanSupport();

        WifiNative.PnoEventHandler pnoEventHandler = mock(WifiNative.PnoEventHandler.class);
        WifiNative.PnoSettings pnoSettings = createDummyPnoSettings();
        WifiNative.ScanEventHandler scanEventHandler = mock(WifiNative.ScanEventHandler.class);
        WifiNative.ScanSettings scanSettings = createDummyScanSettings();
        ScanResults scanResults = createDummyScanResults();

        InOrder order = inOrder(pnoEventHandler, mWifiNative);

        // Start PNO scan
        startSuccessfulPnoScan(scanSettings, pnoSettings, scanEventHandler, pnoEventHandler);

        expectSuccessfulSwPnoScan(order, pnoEventHandler, scanResults);

        verifyNoMoreInteractions(pnoEventHandler);
    }

    private void createScannerWithHwPnoScanSupport() {
        mResources.setBoolean(R.bool.config_wifi_background_scan_support, true);
        mScanner = new SupplicantWifiScannerImpl(mContext, mWifiNative, mLooper.getLooper());
    }

    private void createScannerWithSwPnoScanSupport() {
        mResources.setBoolean(R.bool.config_wifi_background_scan_support, false);
        mScanner = new SupplicantWifiScannerImpl(mContext, mWifiNative, mLooper.getLooper());
    }

    private WifiNative.PnoSettings createDummyPnoSettings() {
        WifiNative.PnoSettings pnoSettings = new WifiNative.PnoSettings();
        pnoSettings.networkList = new WifiNative.PnoNetwork[2];
        pnoSettings.networkList[0] = new WifiNative.PnoNetwork();
        pnoSettings.networkList[0].ssid = "ssid_pno_1";
        pnoSettings.networkList[0].networkId = 1;
        pnoSettings.networkList[0].priority = 1;
        pnoSettings.networkList[1] = new WifiNative.PnoNetwork();
        pnoSettings.networkList[1].ssid = "ssid_pno_2";
        pnoSettings.networkList[1].networkId = 2;
        pnoSettings.networkList[1].priority = 2;
        return pnoSettings;
    }

    private WifiNative.ScanSettings createDummyScanSettings() {
        WifiNative.ScanSettings settings = new NativeScanSettingsBuilder()
                .withBasePeriod(10000)
                .withMaxApPerScan(10)
                .addBucketWithBand(10000, WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN,
                        WifiScanner.WIFI_BAND_24_GHZ)
                .build();
        return settings;
    }

    private ScanResults createDummyScanResults() {
        return ScanResults.create(0, 2400, 2450, 2450, 2400, 2450, 2450, 2400, 2450, 2450);
    }

    private void startSuccessfulPnoScan(WifiNative.ScanSettings scanSettings,
            WifiNative.PnoSettings pnoSettings, WifiNative.ScanEventHandler scanEventHandler,
            WifiNative.PnoEventHandler pnoEventHandler) {
        when(mWifiNative.setNetworkVariable(anyInt(), anyString(), anyString())).thenReturn(true);
        when(mWifiNative.enableNetworkWithoutConnect(anyInt())).thenReturn(true);
        // Scans succeed
        when(mWifiNative.scan(any(Set.class), any(Set.class))).thenReturn(true);
        when(mWifiNative.enableBackgroundScan(anyBoolean(), anyObject())).thenReturn(true);

        assertTrue(mScanner.setPnoList(pnoSettings, pnoEventHandler));
        // This should happen only for SW PNO scan
        if (mScanner.shouldScheduleBackgroundScanForPno()) {
            assertTrue(mScanner.startBatchedScan(scanSettings, scanEventHandler));
        }
    }

    private Set<Integer> expectedBandScanFreqs(int band) {
        ChannelCollection collection = mScanner.getChannelHelper().createChannelCollection();
        collection.addBand(band);
        return collection.getSupplicantScanFreqs();
    }

    /**
     * Verify that the PNO scan was successfully started.
     */
    private void expectSuccessfulHwPnoScan(InOrder order, WifiNative.PnoSettings pnoSettings,
            WifiNative.PnoEventHandler eventHandler, ScanResults scanResults) {
        for (int i = 0; i < pnoSettings.networkList.length; i++) {
            WifiNative.PnoNetwork network = pnoSettings.networkList[i];
            order.verify(mWifiNative).setNetworkVariable(network.networkId,
                    WifiConfiguration.priorityVarName, Integer.toString(network.priority));
            order.verify(mWifiNative).enableNetworkWithoutConnect(network.networkId);
        }
        // Verify  HW PNO scan started
        order.verify(mWifiNative).enableBackgroundScan(true, null);

        // Setup scan results
        when(mWifiNative.getScanResults()).thenReturn(scanResults.getScanDetailArrayList());

        // Notify scan has finished
        mWifiMonitor.sendMessage(mWifiNative.getInterfaceName(), WifiMonitor.SCAN_RESULTS_EVENT);
        assertEquals("dispatch message after results event", 1, mLooper.dispatchAll());

        order.verify(eventHandler).onPnoNetworkFound(scanResults.getRawScanResults());
    }

    /**
     * Verify that the single scan results were delivered and that the PNO scan was paused and
     * resumed either side of it.
     */
    private void expectSuccessfulSingleScanWithHwPnoEnabled(InOrder order,
            WifiNative.ScanEventHandler eventHandler, Set<Integer> expectedScanFreqs,
            Set<Integer> expectedHiddenNetIds, ScanResults scanResults) {
        // Pause PNO scan first
        order.verify(mWifiNative).enableBackgroundScan(false, null);

        order.verify(mWifiNative).scan(eq(expectedScanFreqs), eq(expectedHiddenNetIds));

        when(mWifiNative.getScanResults()).thenReturn(scanResults.getScanDetailArrayList());

        // Notify scan has finished
        mWifiMonitor.sendMessage(mWifiNative.getInterfaceName(), WifiMonitor.SCAN_RESULTS_EVENT);
        assertEquals("dispatch message after results event", 1, mLooper.dispatchAll());

        order.verify(eventHandler).onScanStatus(WifiNative.WIFI_SCAN_RESULTS_AVAILABLE);
        assertScanDataEquals(scanResults.getScanData(), mScanner.getLatestSingleScanResults());

        // Resume PNO scan after the single scan results are received
        order.verify(mWifiNative).enableBackgroundScan(true, null);
    }

    /**
     * Verify that the SW PNO scan was successfully started.
     * This is basically ensuring that the background scan runs successfully and invokes the
     * onPnoNetworkFound callback.
     */
    private void expectSuccessfulSwPnoScan(InOrder order,
            WifiNative.PnoEventHandler pnoEventHandler, ScanResults scanResults) {

        // Verify scan started
        order.verify(mWifiNative).scan(any(Set.class), any(Set.class));

        // Make sure that HW PNO scan was not started
        verify(mWifiNative, never()).enableBackgroundScan(anyBoolean(), anyObject());

        // Setup scan results
        when(mWifiNative.getScanResults()).thenReturn(scanResults.getScanDetailArrayList());

        // Notify scan has finished
        mWifiMonitor.sendMessage(mWifiNative.getInterfaceName(), WifiMonitor.SCAN_RESULTS_EVENT);
        assertEquals("dispatch message after results event", 1, mLooper.dispatchAll());

        // Scan results are sorted when using background scan
        List<ScanResult> scanResultList = Arrays.asList(scanResults.getRawScanResults());
        Collections.sort(scanResultList, WifiScannerImpl.SCAN_RESULT_SORT_COMPARATOR);
        ScanResult[] scanResultArray = new ScanResult[scanResultList.size()];
        scanResultList.toArray(scanResultArray);

        order.verify(pnoEventHandler).onPnoNetworkFound(scanResultArray);
    }
}
