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

import android.hardware.wifi.V1_0.RttResult;
import android.hardware.wifi.V1_0.RttStatus;
import android.net.wifi.ScanResult;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.util.Pair;

import libcore.util.HexEncoding;

import java.util.ArrayList;
import java.util.List;

/**
 * Utilities for the Rtt unit test suite.
 */
public class RttTestUtils {
    /**
     * Compare the two lists and return true for equality, false otherwise. The two lists are
     * considered identical if they have the same number of elements and contain equal elements
     * (equality of elements using the equal() operator of the component objects).
     *
     * Note: null != empty list
     */
    public static boolean compareListContentsNoOrdering(List a, List b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false; // at this point they're not both null
        }
        if (a.size() != b.size()) { // at this point neither is null
            return false;
        }
        return a.containsAll(b) && b.containsAll(a);
    }

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
        byte[] mac1 = HexEncoding.decode("080908070605".toCharArray(), false);

        builder.addAccessPoint(scan1);
        builder.addAccessPoint(scan2);
        builder.addWifiAwarePeer(mac1);

        return builder.build();
    }

    /**
     * Returns a matched set of dummy ranging results: HAL RttResult and the public API
     * RangingResult.
     *
     * @param request If non-null will be used as a template (BSSID) for the range results.
     */
    public static Pair<List<RttResult>, List<RangingResult>> getDummyRangingResults(
            RangingRequest request) {
        int rangeCmBase = 15;
        int rangeStdDevCmBase = 3;
        int rssiBase = -20;
        long rangeTimestampBase = 666;
        List<RttResult> halResults = new ArrayList<>();
        List<RangingResult> results = new ArrayList<>();

        if (request != null) {
            for (RangingRequest.RttPeer peer: request.mRttPeers) {
                RangingResult rangingResult = null;
                byte[] overrideMac = null;
                if (peer instanceof RangingRequest.RttPeerAp) {
                    rangingResult = new RangingResult(RangingResult.STATUS_SUCCESS,
                            macAddressToByteArray(
                                    ((RangingRequest.RttPeerAp) peer).scanResult.BSSID),
                            rangeCmBase++, rangeStdDevCmBase++, rssiBase++, rangeTimestampBase++);
                } else if (peer instanceof RangingRequest.RttPeerAware) {
                    RangingRequest.RttPeerAware awarePeer = (RangingRequest.RttPeerAware) peer;
                    if (awarePeer.peerHandle != null) {
                        rangingResult = new RangingResult(RangingResult.STATUS_SUCCESS,
                                awarePeer.peerHandle, rangeCmBase++, rangeStdDevCmBase++,
                                rssiBase++, rangeTimestampBase++);
                        overrideMac = awarePeer.peerMacAddress;
                    } else {
                        rangingResult = new RangingResult(RangingResult.STATUS_SUCCESS,
                                awarePeer.peerMacAddress, rangeCmBase++, rangeStdDevCmBase++,
                                rssiBase++, rangeTimestampBase++);
                    }
                }
                results.add(rangingResult);
                halResults.add(getMatchingRttResult(rangingResult, overrideMac));
            }
        } else {
            results.add(new RangingResult(RangingResult.STATUS_SUCCESS,
                    HexEncoding.decode("100102030405".toCharArray(), false), rangeCmBase++,
                    rangeStdDevCmBase++, rssiBase++, rangeTimestampBase++));
            results.add(new RangingResult(RangingResult.STATUS_SUCCESS,
                    HexEncoding.decode("1A0B0C0D0E0F".toCharArray(), false), rangeCmBase++,
                    rangeStdDevCmBase++, rssiBase++, rangeTimestampBase++));
            results.add(new RangingResult(RangingResult.STATUS_SUCCESS,
                    HexEncoding.decode("080908070605".toCharArray(), false), rangeCmBase++,
                    rangeStdDevCmBase++, rssiBase++, rangeTimestampBase++));
            halResults.add(getMatchingRttResult(results.get(0), null));
            halResults.add(getMatchingRttResult(results.get(1), null));
            halResults.add(getMatchingRttResult(results.get(2), null));
        }

        return new Pair<>(halResults, results);
    }

    private static RttResult getMatchingRttResult(RangingResult rangingResult, byte[] overrideMac) {
        RttResult rttResult = new RttResult();
        rttResult.status = rangingResult.getStatus() == RangingResult.STATUS_SUCCESS
                ? RttStatus.SUCCESS : RttStatus.FAILURE;
        System.arraycopy(overrideMac == null ? rangingResult.getMacAddress() : overrideMac, 0,
                rttResult.addr, 0, 6);
        rttResult.distanceInMm = rangingResult.getDistanceMm();
        rttResult.distanceSdInMm = rangingResult.getDistanceStdDevMm();
        rttResult.rssi = rangingResult.getRssi();
        rttResult.timeStampInUs = rangingResult.getRangingTimestampUs();

        return rttResult;
    }
}
