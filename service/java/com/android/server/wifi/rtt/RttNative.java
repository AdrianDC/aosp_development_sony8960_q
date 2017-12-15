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
import android.hardware.wifi.V1_0.RttType;
import android.hardware.wifi.V1_0.WifiChannelWidthInMhz;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.ResponderConfig;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.wifi.HalDeviceManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * TBD
 */
public class RttNative extends IWifiRttControllerEventCallback.Stub {
    private static final String TAG = "RttNative";
    private static final boolean VDBG = false; // STOPSHIP if true
    /* package */ boolean mDbg = false;

    private final RttServiceImpl mRttService;
    private final HalDeviceManager mHalDeviceManager;

    private Object mLock = new Object();

    private IWifiRttController mIWifiRttController;

    public RttNative(RttServiceImpl rttService, HalDeviceManager halDeviceManager) {
        mRttService = rttService;
        mHalDeviceManager = halDeviceManager;
    }

    /**
     * Initialize the object - registering with the HAL device manager.
     */
    public void start() {
        synchronized (mLock) {
            mHalDeviceManager.initialize();
            mHalDeviceManager.registerStatusListener(() -> {
                if (VDBG) Log.d(TAG, "hdm.onStatusChanged");
                updateController();
            }, null);
            updateController();
        }
    }

    /**
     * Returns true if Wi-Fi is ready for RTT requests, false otherwise.
     */
    public boolean isReady() {
        synchronized (mLock) {
            return mIWifiRttController != null;
        }
    }

    private void updateController() {
        if (mDbg) Log.v(TAG, "updateController: mIWifiRttController=" + mIWifiRttController);

        // only care about isStarted (Wi-Fi started) not isReady - since if not
        // ready then Wi-Fi will also be down.
        synchronized (mLock) {
            if (mHalDeviceManager.isStarted()) {
                if (mIWifiRttController == null) {
                    mIWifiRttController = mHalDeviceManager.createRttController();
                    if (mIWifiRttController == null) {
                        Log.e(TAG, "updateController: Failed creating RTT controller - but Wifi is "
                                + "started!");
                    } else {
                        try {
                            mIWifiRttController.registerEventCallback(this);
                        } catch (RemoteException e) {
                            Log.e(TAG, "updateController: exception registering callback: " + e);
                            mIWifiRttController = null;
                        }
                    }
                }
            } else {
                mIWifiRttController = null;
            }

            if (mIWifiRttController == null) {
                mRttService.disable();
            } else {
                mRttService.enable();
            }
        }
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
        if (mDbg) {
            Log.v(TAG,
                    "rangeRequest: cmdId=" + cmdId + ", # of requests=" + request.mRttPeers.size());
        }
        if (VDBG) Log.v(TAG, "rangeRequest: request=" + request);
        synchronized (mLock) {
            if (!isReady()) {
                Log.e(TAG, "rangeRequest: RttController is null");
                return false;
            }

            ArrayList<RttConfig> rttConfig = convertRangingRequestToRttConfigs(request);
            if (rttConfig == null) {
                Log.e(TAG, "rangeRequest: invalid request parameters");
                return false;
            }

            try {
                WifiStatus status = mIWifiRttController.rangeRequest(cmdId, rttConfig);
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
    }

    /**
     * Cancel an outstanding ranging request: no guarantees of execution - we will ignore any
     * results which are returned for the canceled request.
     *
     * @param cmdId The cmdId issued with the original rangeRequest command.
     * @param macAddresses A list of MAC addresses for which to cancel the operation.
     * @return Success status: true for success, false for failure.
     */
    public boolean rangeCancel(int cmdId, ArrayList<byte[]> macAddresses) {
        if (mDbg) Log.v(TAG, "rangeCancel: cmdId=" + cmdId);
        synchronized (mLock) {
            if (!isReady()) {
                Log.e(TAG, "rangeCancel: RttController is null");
                return false;
            }

            try {
                WifiStatus status = mIWifiRttController.rangeCancel(cmdId, macAddresses);
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "rangeCancel: cannot issue range cancel -- code=" + status.code);
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "rangeCancel: exception issuing range cancel: " + e);
                return false;
            }

            return true;
        }
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
        if (mDbg) Log.v(TAG, "onResults: cmdId=" + cmdId + ", # of results=" + halResults.size());
        List<RangingResult> results = new ArrayList<>(halResults.size());

        mRttService.onRangingResults(cmdId, halResults);
    }

    private static ArrayList<RttConfig> convertRangingRequestToRttConfigs(RangingRequest request) {
        ArrayList<RttConfig> rttConfigs = new ArrayList<>(request.mRttPeers.size());

        // Skipping any configurations which have an error (printing out a message).
        // The caller will only get results for valid configurations.
        for (ResponderConfig responder: request.mRttPeers) {
            RttConfig config = new RttConfig();

            System.arraycopy(responder.macAddress.toByteArray(), 0, config.addr, 0,
                    config.addr.length);

            try {
                config.type = responder.supports80211mc ? RttType.TWO_SIDED : RttType.ONE_SIDED;
                config.peer = halRttPeerTypeFromResponderType(responder.responderType);
                config.channel.width = halChannelWidthFromResponderChannelWidth(
                        responder.channelWidth);
                config.channel.centerFreq = responder.frequency;
                config.channel.centerFreq0 = responder.centerFreq0;
                config.channel.centerFreq1 = responder.centerFreq1;
                config.bw = halRttChannelBandwidthFromResponderChannelWidth(responder.channelWidth);
                config.preamble = halRttPreambleFromResponderPreamble(responder.preamble);

                config.mustRequestLci = false;
                config.mustRequestLcr = false;
                if (config.peer == RttPeerType.NAN) {
                    config.burstPeriod = 0;
                    config.numBurst = 0;
                    config.numFramesPerBurst = 5;
                    config.numRetriesPerRttFrame = 3;
                    config.numRetriesPerFtmr = 3;
                    config.burstDuration = 15;
                } else { // AP + all non-NAN requests
                    config.burstPeriod = 0;
                    config.numBurst = 0;
                    config.numFramesPerBurst = 8;
                    config.numRetriesPerRttFrame = 0;
                    config.numRetriesPerFtmr = 0;
                    config.burstDuration = 15;
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid configuration: " + e.getMessage());
                continue;
            }

            rttConfigs.add(config);
        }

        return rttConfigs;
    }

    static int halRttPeerTypeFromResponderType(int responderType) {
        switch (responderType) {
            case ResponderConfig.RESPONDER_AP:
                return RttPeerType.AP;
            case ResponderConfig.RESPONDER_STA:
                return RttPeerType.STA;
            case ResponderConfig.RESPONDER_P2P_GO:
                return RttPeerType.P2P_GO;
            case ResponderConfig.RESPONDER_P2P_CLIENT:
                return RttPeerType.P2P_CLIENT;
            case ResponderConfig.RESPONDER_AWARE:
                return RttPeerType.NAN;
            default:
                throw new IllegalArgumentException(
                        "halRttPeerTypeFromResponderType: bad " + responderType);
        }
    }

    static int halChannelWidthFromResponderChannelWidth(int responderChannelWidth) {
        switch (responderChannelWidth) {
            case ResponderConfig.CHANNEL_WIDTH_20MHZ:
                return WifiChannelWidthInMhz.WIDTH_20;
            case ResponderConfig.CHANNEL_WIDTH_40MHZ:
                return WifiChannelWidthInMhz.WIDTH_40;
            case ResponderConfig.CHANNEL_WIDTH_80MHZ:
                return WifiChannelWidthInMhz.WIDTH_80;
            case ResponderConfig.CHANNEL_WIDTH_160MHZ:
                return WifiChannelWidthInMhz.WIDTH_160;
            case ResponderConfig.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                return WifiChannelWidthInMhz.WIDTH_80P80;
            default:
                throw new IllegalArgumentException(
                        "halChannelWidthFromResponderChannelWidth: bad " + responderChannelWidth);
        }
    }

    static int halRttChannelBandwidthFromResponderChannelWidth(int responderChannelWidth) {
        switch (responderChannelWidth) {
            case ResponderConfig.CHANNEL_WIDTH_20MHZ:
                return RttBw.BW_20MHZ;
            case ResponderConfig.CHANNEL_WIDTH_40MHZ:
                return RttBw.BW_40MHZ;
            case ResponderConfig.CHANNEL_WIDTH_80MHZ:
                return RttBw.BW_80MHZ;
            case ResponderConfig.CHANNEL_WIDTH_160MHZ:
                return RttBw.BW_160MHZ;
            case ResponderConfig.CHANNEL_WIDTH_80MHZ_PLUS_MHZ:
                return RttBw.BW_160MHZ;
            default:
                throw new IllegalArgumentException(
                        "halRttChannelBandwidthFromHalBandwidth: bad " + responderChannelWidth);
        }
    }

    static int halRttPreambleFromResponderPreamble(int responderPreamble) {
        switch (responderPreamble) {
            case ResponderConfig.PREAMBLE_LEGACY:
                return RttPreamble.LEGACY;
            case ResponderConfig.PREAMBLE_HT:
                return RttPreamble.HT;
            case ResponderConfig.PREAMBLE_VHT:
                return RttPreamble.VHT;
            default:
                throw new IllegalArgumentException(
                        "halRttPreambleFromResponderPreamble: bad " + responderPreamble);
        }
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("RttNative:");
        pw.println("  mHalDeviceManager: " + mHalDeviceManager);
        pw.println("  mIWifiRttController: " + mIWifiRttController);
    }
}
