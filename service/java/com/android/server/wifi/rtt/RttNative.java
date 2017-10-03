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

import android.hardware.wifi.V1_0.IWifiRttController;
import android.hardware.wifi.V1_0.IWifiRttControllerEventCallback;
import android.hardware.wifi.V1_0.RttBw;
import android.hardware.wifi.V1_0.RttConfig;
import android.hardware.wifi.V1_0.RttPeerType;
import android.hardware.wifi.V1_0.RttPreamble;
import android.hardware.wifi.V1_0.RttResult;
import android.hardware.wifi.V1_0.RttStatus;
import android.hardware.wifi.V1_0.RttType;
import android.hardware.wifi.V1_0.WifiChannelWidthInMhz;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.net.wifi.ScanResult;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.wifi.HalDeviceManager;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.WifiVendorHal;
import com.android.server.wifi.util.NativeUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * TBD
 */
public class RttNative extends IWifiRttControllerEventCallback.Stub {
    private static final String TAG = "RttNative";
    private static final boolean VDBG = true; // STOPSHIP if true

    private final RttServiceImpl mRttService;
    private final HalDeviceManager mHalDeviceManager;
    private final WifiVendorHal mWifiVendorHal;

    private boolean mIsInitialized = false;

    public RttNative(RttServiceImpl rttService, HalDeviceManager halDeviceManager,
            WifiNative wifiNative) {
        mRttService = rttService;
        mHalDeviceManager = halDeviceManager;
        mWifiVendorHal = wifiNative.getVendorHal();
    }

    /**
     * Issue a range request to the HAL.
     *
     * @param cmdId Command ID for the request. Will be used in the corresponding
     * {@link #onResults(int, ArrayList)}.
     * @param request Range request.
     * @return Success status: true for success, false for failure.
     */
    public boolean rangeRequest(int cmdId, RangingRequest request) {
        if (VDBG) Log.v(TAG, "rangeRequest: cmdId=" + cmdId);
        // TODO: b/65014872 replace by direct access to HalDeviceManager
        IWifiRttController rttController = mWifiVendorHal.getRttController();
        if (rttController == null) {
            Log.e(TAG, "rangeRequest: RttController is null");
            return false;
        }
        if (!mIsInitialized) {
            try {
                WifiStatus status = rttController.registerEventCallback(this);
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG,
                            "rangeRequest: cannot register event callback -- code=" + status.code);
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "rangeRequest: exception registering callback: " + e);
                return false;
            }
            mIsInitialized = true;
        }

        ArrayList<RttConfig> rttConfig = convertRangingRequestToRttConfigs(request);
        if (rttConfig == null) {
            Log.e(TAG, "rangeRequest: invalid request parameters");
            return false;
        }

        try {
            WifiStatus status = rttController.rangeRequest(cmdId, rttConfig);
            if (status.code != WifiStatusCode.SUCCESS) {
                Log.e(TAG, "rangeRequest: cannot issue range request -- code=" + status.code);
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "rangeRequest: exception issuing range request: " + e);
            return false;
        }

        return true;
    }

    /**
     * Callback from HAL with range results.
     *
     * @param cmdId Command ID specified in the original request
     * {@link #rangeRequest(int, RangingRequest)}.
     * @param halResults A list of range results.
     */
    @Override
    public void onResults(int cmdId, ArrayList<RttResult> halResults) {
        if (VDBG) Log.v(TAG, "onResults: cmdId=" + cmdId + ", # of results=" + halResults.size());
        List<RangingResult> results = new ArrayList<>(halResults.size());

        for (RttResult halResult: halResults) {
            results.add(new RangingResult(
                    halResult.status == RttStatus.SUCCESS ? RangingResultCallback.STATUS_SUCCESS
                            : RangingResultCallback.STATUS_FAIL, halResult.addr,
                    halResult.distanceInMm / 10, halResult.distanceSdInMm / 10, halResult.rssi,
                    halResult.timeStampInUs));
        }

        mRttService.onRangingResults(cmdId, results);
    }

    private static ArrayList<RttConfig> convertRangingRequestToRttConfigs(RangingRequest request) {
        ArrayList<RttConfig> rttConfigs = new ArrayList<>(request.mRttPeers.size());

        // Skipping any configurations which have an error (printing out a message).
        // The caller will only get results for valid configurations.
        for (RangingRequest.RttPeer peer: request.mRttPeers) {
            if (peer instanceof RangingRequest.RttPeerAp) {
                ScanResult scanResult = ((RangingRequest.RttPeerAp) peer).scanResult;
                RttConfig config = new RttConfig();

                byte[] addr = NativeUtil.macAddressToByteArray(scanResult.BSSID);
                if (addr.length != config.addr.length) {
                    Log.e(TAG, "Invalid configuration: unexpected BSSID length -- " + scanResult);
                    continue;
                }
                for (int i = 0; i < config.addr.length; ++i) {
                    config.addr[i] = addr[i];
                }

                try {
                    config.type =
                            scanResult.is80211mcResponder() ? RttType.TWO_SIDED : RttType.ONE_SIDED;
                    config.peer = RttPeerType.AP;
                    config.channel.width = halChannelWidthFromScanResult(
                            scanResult.channelWidth);
                    config.channel.centerFreq = scanResult.frequency;
                    if (scanResult.centerFreq0 > 0) {
                        config.channel.centerFreq0 = scanResult.centerFreq0;
                    }
                    if (scanResult.centerFreq1 > 0) {
                        config.channel.centerFreq1 = scanResult.centerFreq1;
                    }
                    config.burstPeriod = 0;
                    config.numBurst = 0;
                    config.numFramesPerBurst = 8;
                    config.numRetriesPerRttFrame = 0;
                    config.numRetriesPerFtmr = 0;
                    config.mustRequestLci = false;
                    config.mustRequestLcr = false;
                    config.burstDuration = 15;
                    if (config.channel.centerFreq > 5000) {
                        config.preamble = RttPreamble.VHT;
                    } else {
                        config.preamble = RttPreamble.HT;
                    }
                    config.bw = halChannelBandwidthFromScanResult(scanResult.channelWidth);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Invalid configuration: " + e.getMessage());
                    continue;
                }

                rttConfigs.add(config);
            } else {
                Log.e(TAG, "convertRangingRequestToRttConfigs: unknown request type -- "
                        + peer.getClass().getCanonicalName());
                return null;
            }
        }


        return rttConfigs;
    }

    static int halChannelWidthFromScanResult(int scanResultChannelWidth) {
        switch (scanResultChannelWidth) {
            case ScanResult.CHANNEL_WIDTH_20MHZ:
                return WifiChannelWidthInMhz.WIDTH_20;
            case ScanResult.CHANNEL_WIDTH_40MHZ:
                return WifiChannelWidthInMhz.WIDTH_40;
            case ScanResult.CHANNEL_WIDTH_80MHZ:
                return WifiChannelWidthInMhz.WIDTH_80;
            case ScanResult.CHANNEL_WIDTH_160MHZ:
                return WifiChannelWidthInMhz.WIDTH_160;
            case ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                return WifiChannelWidthInMhz.WIDTH_80P80;
            default:
                throw new IllegalArgumentException(
                        "halChannelWidthFromScanResult: bad " + scanResultChannelWidth);
        }
    }

    static int halChannelBandwidthFromScanResult(int scanResultChannelWidth) {
        switch (scanResultChannelWidth) {
            case ScanResult.CHANNEL_WIDTH_20MHZ:
                return RttBw.BW_20MHZ;
            case ScanResult.CHANNEL_WIDTH_40MHZ:
                return RttBw.BW_40MHZ;
            case ScanResult.CHANNEL_WIDTH_80MHZ:
                return RttBw.BW_80MHZ;
            case ScanResult.CHANNEL_WIDTH_160MHZ:
                return RttBw.BW_160MHZ;
            case ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                return RttBw.BW_160MHZ;
            default:
                throw new IllegalArgumentException(
                        "halChannelBandwidthFromScanResult: bad " + scanResultChannelWidth);
        }
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("RttNative:");
        pw.println("  mIsInitialized: " + mIsInitialized);
    }
}
