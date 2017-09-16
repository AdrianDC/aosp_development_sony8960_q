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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.wifi.V1_0.IWifiRttController;
import android.hardware.wifi.V1_0.RttConfig;
import android.hardware.wifi.V1_0.RttPeerType;
import android.hardware.wifi.V1_0.RttResult;
import android.hardware.wifi.V1_0.RttStatus;
import android.hardware.wifi.V1_0.RttType;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;

import com.android.server.wifi.HalDeviceManager;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.WifiVendorHal;

import libcore.util.HexEncoding;

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

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    @Mock
    public RttServiceImpl mockRttServiceImpl;

    @Mock
    public HalDeviceManager mockHalDeviceManager;

    @Mock
    public WifiNative mockWifiNative;

    @Mock
    public WifiVendorHal mockWifiVendorHal;

    @Mock
    public IWifiRttController mockRttController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mockWifiNative.getVendorHal()).thenReturn(mockWifiVendorHal);
        when(mockWifiVendorHal.getRttController()).thenReturn(mockRttController);

        WifiStatus status = new WifiStatus();
        status.code = WifiStatusCode.SUCCESS;
        when(mockRttController.registerEventCallback(any())).thenReturn(status);
        when(mockRttController.rangeRequest(anyInt(), any(ArrayList.class))).thenReturn(status);

        mDut = new RttNative(mockRttServiceImpl, mockHalDeviceManager, mockWifiNative);
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
                equalTo(HexEncoding.decode("000102030400".toCharArray(), false)));
        collector.checkThat("entry 0: MAC", rttConfig.type, equalTo(RttType.TWO_SIDED));
        collector.checkThat("entry 0: MAC", rttConfig.peer, equalTo(RttPeerType.AP));

        rttConfig = halRequest.get(1);
        collector.checkThat("entry 0: MAC", rttConfig.addr,
                equalTo(HexEncoding.decode("0A0B0C0D0E00".toCharArray(), false)));
        collector.checkThat("entry 0: MAC", rttConfig.type, equalTo(RttType.ONE_SIDED));
        collector.checkThat("entry 0: MAC", rttConfig.peer, equalTo(RttPeerType.AP));
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
        List<RangingResult> fwkResults = mRttResultCaptor.getValue();

        collector.checkThat("number of entries", fwkResults.size(), equalTo(1));

        RangingResult fwkRes = fwkResults.get(0);
        collector.checkThat("status", fwkRes.getStatus(),
                equalTo(RangingResultCallback.STATUS_SUCCESS));
        collector.checkThat("mac", fwkRes.getMacAddress(),
                equalTo(HexEncoding.decode("05060708090A".toCharArray(), false)));
        collector.checkThat("distanceCm", fwkRes.getDistanceCm(), equalTo(150));
        collector.checkThat("timestamp", fwkRes.getRangingTimestamp(), equalTo(666L));
    }
}
