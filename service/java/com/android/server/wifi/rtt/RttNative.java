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
import android.net.wifi.ScanResult;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.wifi.HalDeviceManager;
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
        if (VDBG) Log.v(TAG, "updateController: mIWifiRttController=" + mIWifiRttController);

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
        if (VDBG) Log.v(TAG, "rangeRequest: cmdId=" + cmdId + ", request=" + request);
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
        if (VDBG) Log.v(TAG, "rangeCancel: cmdId=" + cmdId);
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
        if (VDBG) Log.v(TAG, "onResults: cmdId=" + cmdId + ", # of results=" + halResults.size());
        List<RangingResult> results = new ArrayList<>(halResults.size());

        mRttService.onRangingResults(cmdId, halResults);
    }

    private static ArrayList<RttConfig> convertRangingRequestToRttConfigs(RangingRequest request) {
        ArrayList<RttConfig> rttConfigs = new ArrayList<>(request.mRttPeers.size());

        // Skipping any configurations which have an error (printing out a message).
        // The caller will only get results for valid configurations.
        for (RangingRequest.RttPeer peer: request.mRttPeers) {
            RttConfig config = new RttConfig();

            if (peer instanceof RangingRequest.RttPeerAp) {
                ScanResult scanResult = ((RangingRequest.RttPeerAp) peer).scanResult;

                byte[] addr = NativeUtil.macAddressToByteArray(scanResult.BSSID);
                if (addr.length != config.addr.length) {
                    Log.e(TAG, "Invalid configuration: unexpected BSSID length -- " + scanResult);
                    continue;
                }
                System.arraycopy(addr, 0, config.addr, 0, config.addr.length);

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
                    config.bw = halChannelBandwidthFromScanResult(scanResult.channelWidth);
                    if (config.bw == RttBw.BW_80MHZ || config.bw == RttBw.BW_160MHZ) {
                        config.preamble = RttPreamble.VHT;
                    } else {
                        config.preamble = RttPreamble.HT;
                    }
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Invalid configuration: " + e.getMessage());
                    continue;
                }
            } else if (peer instanceof RangingRequest.RttPeerAware) {
                RangingRequest.RttPeerAware rttPeerAware = (RangingRequest.RttPeerAware) peer;

                if (rttPeerAware.peerMacAddress == null
                        || rttPeerAware.peerMacAddress.length != config.addr.length) {
                    Log.e(TAG, "Invalid configuration: null MAC or incorrect length");
                    continue;
                }
                System.arraycopy(rttPeerAware.peerMacAddress, 0, config.addr, 0,
                        config.addr.length);

                config.type = RttType.TWO_SIDED;
                config.peer = RttPeerType.NAN;
                config.channel.width = WifiChannelWidthInMhz.WIDTH_80;
                config.channel.centerFreq = 5200;
                config.channel.centerFreq0 = 5210;
                config.channel.centerFreq1 = 0;
                config.burstPeriod = 0;
                config.numBurst = 0;
                config.numFramesPerBurst = 5;
                config.numRetriesPerRttFrame = 3;
                config.numRetriesPerFtmr = 3;
                config.mustRequestLci = false;
                config.mustRequestLcr = false;
                config.burstDuration = 15;
                config.preamble = RttPreamble.VHT;
                config.bw = RttBw.BW_80MHZ;
            } else {
                Log.e(TAG, "convertRangingRequestToRttConfigs: unknown request type -- "
                        + peer.getClass().getCanonicalName());
                return null;
            }

            rttConfigs.add(config);
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
        pw.println("  mHalDeviceManager: " + mHalDeviceManager);
        pw.println("  mIWifiRttController: " + mIWifiRttController);
    }
}
