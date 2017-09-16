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

import static com.android.server.wifi.util.NativeUtil.macAddressToByteArray;

import android.net.wifi.ScanResult;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;

import libcore.util.HexEncoding;

import java.util.ArrayList;
import java.util.List;

/**
 * Utilities for the Rtt unit test suite.
 */
public class RttTestUtils {
    /**
     * Returns a dummy ranging request with 2 requests:
     * - First: 802.11mc capable
     * - Second: 802.11mc not capable
     */
    public static RangingRequest getDummyRangingRequest(byte lastMacByte) {
        RangingRequest.Builder builder = new RangingRequest.Builder();

        ScanResult scan1 = new ScanResult();
        scan1.BSSID = "00:01:02:03:04:" + String.format("%02d", lastMacByte);
        scan1.setFlag(ScanResult.FLAG_80211mc_RESPONDER);
        ScanResult scan2 = new ScanResult();
        scan2.BSSID = "0A:0B:0C:0D:0E:" + String.format("%02d", lastMacByte);

        builder.addAp(scan1);
        builder.addAp(scan2);

        return builder.build();
    }

    /**
     * Returns a set of dummy ranging results.
     *
     * @param request If non-null will be used as a template (BSSID) for the range results.
     */
    public static List<RangingResult> getDummyRangingResults(RangingRequest request) {
        int rangeCmBase = 15;
        int rangeStdDevCmBase = 3;
        int rssiBase = -20;
        long rangeTimestampBase = 666;
        List<RangingResult> results = new ArrayList<>();

        if (request != null) {
            for (RangingRequest.RttPeer peer: request.mRttPeers) {
                results.add(new RangingResult(RangingResultCallback.STATUS_SUCCESS,
                        macAddressToByteArray(((RangingRequest.RttPeerAp) peer).scanResult.BSSID),
                        rangeCmBase++, rangeStdDevCmBase++, rssiBase++, rangeTimestampBase++));
            }
        } else {
            results.add(new RangingResult(RangingResultCallback.STATUS_SUCCESS,
                    HexEncoding.decode("100102030405".toCharArray(), false), rangeCmBase++,
                    rangeStdDevCmBase++, rssiBase++, rangeTimestampBase++));
            results.add(new RangingResult(RangingResultCallback.STATUS_SUCCESS,
                    HexEncoding.decode("1A0B0C0D0E0F".toCharArray(), false), rangeCmBase++,
                    rangeStdDevCmBase++, rssiBase++, rangeTimestampBase++));
        }

        return results;
    }
}
