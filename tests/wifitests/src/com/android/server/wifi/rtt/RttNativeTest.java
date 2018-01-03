/*
 * Copyright (C) 2017 The Android Open Source Project
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


package com.android.server.wifi.rtt;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.hardware.wifi.V1_0.IWifiRttController;
import android.hardware.wifi.V1_0.RttConfig;
import android.hardware.wifi.V1_0.RttPeerType;
import android.hardware.wifi.V1_0.RttResult;
import android.hardware.wifi.V1_0.RttStatus;
import android.hardware.wifi.V1_0.RttType;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.net.MacAddress;
import android.net.wifi.rtt.RangingRequest;

import com.android.server.wifi.HalDeviceManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit test harness for the RttNative class.
 */
public class RttNativeTest {
    private RttNative mDut;

    private ArgumentCaptor<ArrayList> mRttConfigCaptor = ArgumentCaptor.forClass(ArrayList.class);
    private ArgumentCaptor<List> mRttResultCaptor = ArgumentCaptor.forClass(List.class);
    private ArgumentCaptor<HalDeviceManager.ManagerStatusListener> mHdmStatusListener =
            ArgumentCaptor.forClass(HalDeviceManager.ManagerStatusListener.class);

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    @Mock
    public RttServiceImpl mockRttServiceImpl;

    @Mock
    public HalDeviceManager mockHalDeviceManager;

    @Mock
    public IWifiRttController mockRttController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mockHalDeviceManager.isStarted()).thenReturn(true);
        when(mockHalDeviceManager.createRttController()).thenReturn(mockRttController);

        WifiStatus status = new WifiStatus();
        status.code = WifiStatusCode.SUCCESS;
        when(mockRttController.registerEventCallback(any())).thenReturn(status);
        when(mockRttController.rangeRequest(anyInt(), any(ArrayList.class))).thenReturn(status);
        when(mockRttController.rangeCancel(anyInt(), any(ArrayList.class))).thenReturn(status);

        mDut = new RttNative(mockRttServiceImpl, mockHalDeviceManager);
        mDut.start();
        verify(mockHalDeviceManager).registerStatusListener(mHdmStatusListener.capture(), any());
        verify(mockRttController).registerEventCallback(any());
        verify(mockRttServiceImpl).enableIfPossible();
        assertTrue(mDut.isReady());
    }

    /**
     * Validate successful ranging flow.
     */
    @Test
    public void testRangeRequest() throws Exception {
        int cmdId = 55;
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);

        // (1) issue range request
        mDut.rangeRequest(cmdId, request);

        // (2) verify HAL call and parameters
        verify(mockRttController).rangeRequest(eq(cmdId), mRttConfigCaptor.capture());

        // verify contents of HAL request (hard codes knowledge from getDummyRangingRequest()).
        ArrayList<RttConfig> halRequest = mRttConfigCaptor.getValue();

        collector.checkThat("number of entries", halRequest.size(),
                equalTo(request.mRttPeers.size()));

        RttConfig rttConfig = halRequest.get(0);
        collector.checkThat("entry 0: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("00:01:02:03:04:00").toByteArray()));
        collector.checkThat("entry 0: MAC", rttConfig.type, equalTo(RttType.TWO_SIDED));
        collector.checkThat("entry 0: MAC", rttConfig.peer, equalTo(RttPeerType.AP));

        rttConfig = halRequest.get(1);
        collector.checkThat("entry 1: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("0A:0B:0C:0D:0E:00").toByteArray()));
        collector.checkThat("entry 1: MAC", rttConfig.type, equalTo(RttType.ONE_SIDED));
        collector.checkThat("entry 1: MAC", rttConfig.peer, equalTo(RttPeerType.AP));

        rttConfig = halRequest.get(2);
        collector.checkThat("entry 2: MAC", rttConfig.addr,
                equalTo(MacAddress.fromString("08:09:08:07:06:05").toByteArray()));
        collector.checkThat("entry 2: MAC", rttConfig.type, equalTo(RttType.TWO_SIDED));
        collector.checkThat("entry 2: MAC", rttConfig.peer, equalTo(RttPeerType.NAN));

        verifyNoMoreInteractions(mockRttController);
    }

    /**
     * Validate no range request when Wi-Fi is down
     */
    @Test
    public void testWifiDown() throws Exception {
        int cmdId = 55;
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);

        // (1) configure Wi-Fi down and send a status change indication
        when(mockHalDeviceManager.isStarted()).thenReturn(false);
        mHdmStatusListener.getValue().onStatusChanged();
        verify(mockRttServiceImpl).disable();
        assertFalse(mDut.isReady());

        // (2) issue range request
        mDut.rangeRequest(cmdId, request);

        verifyNoMoreInteractions(mockRttServiceImpl, mockRttController);
    }

    /**
     * Validate ranging cancel flow.
     */
    @Test
    public void testRangeCancel() throws Exception {
        int cmdId = 66;
        ArrayList<byte[]> macAddresses = new ArrayList<>();
        byte[] mac1 = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] mac2 = {0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F};
        macAddresses.add(mac1);
        macAddresses.add(mac2);

        // (1) issue cancel request
        mDut.rangeCancel(cmdId, macAddresses);

        // (2) verify HAL call and parameters
        verify(mockRttController).rangeCancel(cmdId, macAddresses);

        verifyNoMoreInteractions(mockRttController);
    }

    /**
     * Validate correct result conversion from HAL to framework.
     */
    @Test
    public void testRangeResults() throws Exception {
        int cmdId = 55;
        ArrayList<RttResult> results = new ArrayList<>();
        RttResult res = new RttResult();
        res.addr[0] = 5;
        res.addr[1] = 6;
        res.addr[2] = 7;
        res.addr[3] = 8;
        res.addr[4] = 9;
        res.addr[5] = 10;
        res.status = RttStatus.SUCCESS;
        res.distanceInMm = 1500;
        res.timeStampInUs = 666;
        results.add(res);

        // (1) have HAL call native with results
        mDut.onResults(cmdId, results);

        // (2) verify call to framework
        verify(mockRttServiceImpl).onRangingResults(eq(cmdId), mRttResultCaptor.capture());

        // verify contents of the framework results
        List<RttResult> rttR = mRttResultCaptor.getValue();

        collector.checkThat("number of entries", rttR.size(), equalTo(1));

        RttResult rttResult = rttR.get(0);
        collector.checkThat("status", rttResult.status,
                equalTo(RttStatus.SUCCESS));
        collector.checkThat("mac", rttResult.addr,
                equalTo(MacAddress.fromString("05:06:07:08:09:0A").toByteArray()));
        collector.checkThat("distanceCm", rttResult.distanceInMm, equalTo(1500));
        collector.checkThat("timestamp", rttResult.timeStampInUs, equalTo(666L));

        verifyNoMoreInteractions(mockRttController);
    }
}
