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

import static com.android.server.wifi.ScanTestUtil.assertNativeScanSettingsEquals;
import static com.android.server.wifi.ScanTestUtil.assertScanDatasEquals;
import static com.android.server.wifi.ScanTestUtil.createRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.Protocol;
import com.android.server.wifi.MockAnswerUtil.AnswerWithArguments;
import com.android.server.wifi.scanner.ChannelHelper;
import com.android.server.wifi.scanner.KnownBandsChannelHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.WifiScanningServiceImpl}.
 */
@SmallTest
public class WifiScanningServiceTest {
    public static final String TAG = "WifiScanningServiceTest";

    @Mock Context mContext;
    @Mock WifiScannerImpl mWifiScannerImpl;
    @Mock WifiScannerImpl.WifiScannerImplFactory mWifiScannerImplFactory;
    @Mock IBatteryStats mBatteryStats;
    MockLooper mLooper;
    WifiScanningServiceImpl mWifiScanningServiceImpl;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        ChannelHelper channelHelper = new KnownBandsChannelHelper(
                new int[]{2400, 2450},
                new int[]{5150, 5175},
                new int[]{5600, 5650, 5660});

        mLooper = new MockLooper();
        when(mWifiScannerImplFactory.create(any(Context.class), any(Looper.class)))
                .thenReturn(mWifiScannerImpl);
        when(mWifiScannerImpl.getChannelHelper()).thenReturn(channelHelper);
        mWifiScanningServiceImpl = new WifiScanningServiceImpl(mContext, mLooper.getLooper(),
                mWifiScannerImplFactory, mBatteryStats);
    }

    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    /**
     * Internal BroadcastReceiver that WifiScanningServiceImpl uses to listen for broadcasts
     * this is initialized by calling startServiceAndLoadDriver
     */
    BroadcastReceiver mBroadcastReceiver;

    private WifiScanner.ScanSettings generateValidScanSettings() {
        return createRequest(WifiScanner.WIFI_BAND_BOTH, 30000, 0, 20,
                WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN);
    }

    private BidirectionalAsyncChannel connectChannel(Handler handler) {
        BidirectionalAsyncChannel controlChannel = new BidirectionalAsyncChannel();
        controlChannel.connect(mLooper.getLooper(), mWifiScanningServiceImpl.getMessenger(),
                handler);
        mLooper.dispatchAll();
        controlChannel.assertConnected();
        return controlChannel;
    }

    private Message verifyHandleMessageAndGetMessage(InOrder order, Handler handler) {
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        order.verify(handler).handleMessage(messageCaptor.capture());
        return messageCaptor.getValue();
    }

    private void verifyScanResultsRecieved(InOrder order, Handler handler, int listenerId,
            WifiScanner.ScanData... expected) {
        Message scanResultMessage = verifyHandleMessageAndGetMessage(order, handler);
        assertScanResultsMessage(listenerId, expected, scanResultMessage);
    }

    private void assertScanResultsMessage(int listenerId, WifiScanner.ScanData[] expected,
            Message scanResultMessage) {
        assertEquals("what", WifiScanner.CMD_SCAN_RESULT, scanResultMessage.what);
        assertEquals("listenerId", listenerId, scanResultMessage.arg2);
        assertScanDatasEquals(expected,
                ((WifiScanner.ParcelableScanData) scanResultMessage.obj).getResults());
    }

    private void sendBackgroundScanRequest(BidirectionalAsyncChannel controlChannel,
            int scanRequestId, WifiScanner.ScanSettings settings) {
        controlChannel.sendMessage(Message.obtain(null, WifiScanner.CMD_START_BACKGROUND_SCAN, 0,
                        scanRequestId, settings));
    }

    private void verifySuccessfulResponse(InOrder order, Handler handler, int arg2) {
        Message response = verifyHandleMessageAndGetMessage(order, handler);
        assertSuccessfulResponse(arg2, response);
    }

    private void assertSuccessfulResponse(int arg2, Message response) {
        if (response.what == WifiScanner.CMD_OP_FAILED) {
            WifiScanner.OperationResult result = (WifiScanner.OperationResult) response.obj;
            fail("response indicates failure, reason=" + result.reason
                    + ", description=" + result.description);
        } else {
            assertEquals("response.what", WifiScanner.CMD_OP_SUCCEEDED, response.what);
            assertEquals("response.arg2", arg2, response.arg2);
        }
    }

    private void verifyFailedResponse(InOrder order, Handler handler, int arg2,
            int expectedErrorReason, String expectedErrorDescription) {
        Message response = verifyHandleMessageAndGetMessage(order, handler);
        assertFailedResponse(arg2, expectedErrorReason, expectedErrorDescription, response);
    }

    private void assertFailedResponse(int arg2, int expectedErrorReason,
            String expectedErrorDescription, Message response) {
        if (response.what == WifiScanner.CMD_OP_SUCCEEDED) {
            fail("response indicates success");
        } else {
            assertEquals("response.what", WifiScanner.CMD_OP_FAILED, response.what);
            assertEquals("response.arg2", arg2, response.arg2);
            WifiScanner.OperationResult result = (WifiScanner.OperationResult) response.obj;
            assertEquals("response.obj.reason",
                    expectedErrorReason, result.reason);
            assertEquals("response.obj.description",
                    expectedErrorDescription, result.description);
        }
    }

    private void verifyStartBackgroundScan(InOrder order, WifiNative.ScanSettings expected) {
        ArgumentCaptor<WifiNative.ScanSettings> scanSettingsCaptor =
                ArgumentCaptor.forClass(WifiNative.ScanSettings.class);
        order.verify(mWifiScannerImpl).startBatchedScan(scanSettingsCaptor.capture(),
                any(WifiNative.ScanEventHandler.class));
        assertNativeScanSettingsEquals(expected, scanSettingsCaptor.getValue());
    }

    private void startServiceAndLoadDriver() {
        mWifiScanningServiceImpl.startService();
        when(mWifiScannerImpl.getScanCapabilities(any(WifiNative.ScanCapabilities.class)))
                .thenAnswer(new AnswerWithArguments() {
                        public boolean answer(WifiNative.ScanCapabilities capabilities) {
                            capabilities.max_scan_cache_size = Integer.MAX_VALUE;
                            capabilities.max_scan_buckets = 8;
                            capabilities.max_ap_cache_per_scan = 16;
                            capabilities.max_rssi_sample_size = 8;
                            capabilities.max_scan_reporting_threshold = 10;
                            capabilities.max_hotlist_bssids = 0;
                            capabilities.max_significant_wifi_change_aps = 0;
                            return true;
                        }
                    });
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(IntentFilter.class));
        mBroadcastReceiver = broadcastReceiverCaptor.getValue();
        TestUtil.sendWifiScanAvailable(broadcastReceiverCaptor.getValue(), mContext,
                WifiManager.WIFI_STATE_ENABLED);
        mLooper.dispatchAll();
    }

    @Test
    public void construct() throws Exception {
        verifyNoMoreInteractions(mContext, mWifiScannerImpl, mWifiScannerImpl,
                mWifiScannerImplFactory, mBatteryStats);
    }

    @Test
    public void startService() throws Exception {
        mWifiScanningServiceImpl.startService();
        verifyNoMoreInteractions(mWifiScannerImplFactory);

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder order = inOrder(handler);
        sendBackgroundScanRequest(controlChannel, 122, generateValidScanSettings());
        mLooper.dispatchAll();
        verifyFailedResponse(order, handler, 122, WifiScanner.REASON_UNSPECIFIED, "not available");
    }

    @Test
    public void loadDriver() throws Exception {
        startServiceAndLoadDriver();
        verify(mWifiScannerImplFactory, times(1)).create(any(Context.class), any(Looper.class));

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder order = inOrder(handler);
        when(mWifiScannerImpl.startBatchedScan(any(WifiNative.ScanSettings.class),
                        any(WifiNative.ScanEventHandler.class))).thenReturn(true);
        sendBackgroundScanRequest(controlChannel, 192, generateValidScanSettings());
        mLooper.dispatchAll();
        verifySuccessfulResponse(order, handler, 192);
    }

    @Test
    public void sendInvalidCommand() throws Exception {
        startServiceAndLoadDriver();

        Handler handler = mock(Handler.class);
        BidirectionalAsyncChannel controlChannel = connectChannel(handler);
        InOrder order = inOrder(handler, mWifiScannerImpl);
        controlChannel.sendMessage(Message.obtain(null, Protocol.BASE_WIFI_MANAGER));
        mLooper.dispatchAll();
        verifyFailedResponse(order, handler, 0, WifiScanner.REASON_INVALID_REQUEST,
                "Invalid request");
    }
}
