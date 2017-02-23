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
package com.android.server.wifi;

import android.annotation.Nullable;
import android.hardware.wifi.V1_0.IWifiApIface;
import android.hardware.wifi.V1_0.IWifiChip;
import android.hardware.wifi.V1_0.IWifiIface;
import android.hardware.wifi.V1_0.IWifiRttController;
import android.hardware.wifi.V1_0.IWifiStaIface;
import android.hardware.wifi.V1_0.IWifiStaIfaceEventCallback;
import android.hardware.wifi.V1_0.IfaceType;
import android.hardware.wifi.V1_0.StaRoamingConfig;
import android.hardware.wifi.V1_0.StaRoamingState;
import android.hardware.wifi.V1_0.StaScanData;
import android.hardware.wifi.V1_0.StaScanResult;
import android.hardware.wifi.V1_0.WifiDebugHostWakeReasonStats;
import android.hardware.wifi.V1_0.WifiDebugPacketFateFrameType;
import android.hardware.wifi.V1_0.WifiDebugRingBufferFlags;
import android.hardware.wifi.V1_0.WifiDebugRingBufferStatus;
import android.hardware.wifi.V1_0.WifiDebugRxPacketFate;
import android.hardware.wifi.V1_0.WifiDebugRxPacketFateReport;
import android.hardware.wifi.V1_0.WifiDebugTxPacketFate;
import android.hardware.wifi.V1_0.WifiDebugTxPacketFateReport;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.net.apf.ApfCapabilities;
import android.net.wifi.RttManager;
import android.net.wifi.RttManager.ResponderConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiLinkLayerStats;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiWakeReasonAndCounts;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;
import android.util.MutableBoolean;
import android.util.MutableInt;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.connectivity.KeepalivePacketData;
import com.android.server.wifi.util.BitMask;
import com.android.server.wifi.util.NativeUtil;

import java.util.ArrayList;
import java.util.Set;

/**
 * Vendor HAL via HIDL
 */
public class WifiVendorHal {

    private static final String TAG = "WifiVendorHal";

    // Vendor HAL HIDL interface objects.
    private IWifiChip mIWifiChip;
    private IWifiStaIface mIWifiStaIface;
    private IWifiApIface mIWifiApIface;
    private IWifiRttController mIWifiRttController;
    private final HalDeviceManager mHalDeviceManager;
    private final HalDeviceManagerStatusListener mHalDeviceManagerStatusCallbacks;
    private final HandlerThread mWifiStateMachineHandlerThread;
    /**
     * Callback object instance for events on the interface
     */
    private final IWifiStaIfaceEventCallback mIWifiStaIfaceEventCallback;

    public WifiVendorHal(HalDeviceManager halDeviceManager,
                         HandlerThread wifiStateMachineHandlerThread) {
        mHalDeviceManager = halDeviceManager;
        mWifiStateMachineHandlerThread = wifiStateMachineHandlerThread;
        mHalDeviceManagerStatusCallbacks = new HalDeviceManagerStatusListener();
        mIWifiStaIfaceEventCallback = new EventCallback();
    }

    // TODO(mplass): figure out where we need locking in hidl world. b/33383725
    public static final Object sLock = new Object();

    private void handleRemoteException(RemoteException e) {
        kilroy();
        Log.e(TAG, "RemoteException in HIDL call " + e);
    }

    private void noteHidlError(WifiStatus status, String culprit) {
        kilroy();
        Log.e(TAG, "Error in " + culprit + " code: " + status.code
                + " (" + status.description + ")");
    }

    /**
     * Initialize the Hal device manager and register for status callbacks.
     * @return
     */
    public boolean initialize() {
        mHalDeviceManager.initialize();
        mHalDeviceManager.registerStatusListener(
                mHalDeviceManagerStatusCallbacks, mWifiStateMachineHandlerThread.getLooper());
        return true;
    }

    /**
     * Bring up the HIDL Vendor HAL and configure for AP (Access Point) mode
     * @return true for success
     */
    public boolean startVendorHalAp() {
        return startVendorHal(AP_MODE);
    }

     /**
     * Bring up the HIDL Vendor HAL and configure for STA (Station) mode
     * @return true for success
     */
    public boolean startVendorHalSta() {
        return startVendorHal(STA_MODE);
    }


    public static final boolean STA_MODE = true;
    public static final boolean AP_MODE = false;

    /**
     * Bring up the HIDL Vendor HAL and configure for STA mode or AP mode.
     *
     * @param isStaMode true to start HAL in STA mode, false to start in AP mode.
     */
    public boolean startVendorHal(boolean isStaMode) {
        if (!mHalDeviceManager.start()) {
            Log.e(TAG, "Failed to start the vendor HAL");
            return false;
        }
        IWifiIface iface;
        if (isStaMode) {
            mIWifiStaIface = mHalDeviceManager.createStaIface(null, null);
            if (mIWifiStaIface == null) {
                Log.e(TAG, "Failed to create STA Iface. Vendor Hal start failed");
                mHalDeviceManager.stop();
                return false;
            }
            iface = (IWifiIface) mIWifiStaIface;
            if (!registerStaIfaceCallback()) {
                Log.e(TAG, "Failed to register sta iface callback");
                mHalDeviceManager.stop();
                return false;
            }
            mIWifiRttController = mHalDeviceManager.createRttController(iface);
            if (mIWifiRttController == null) {
                Log.e(TAG, "Failed to create RTT controller. Vendor Hal start failed");
                stopVendorHal();
                return false;
            }
            enableLinkLayerStats();
        } else {
            mIWifiApIface = mHalDeviceManager.createApIface(null, null);
            if (mIWifiApIface == null) {
                Log.e(TAG, "Failed to create AP Iface. Vendor Hal start failed");
                stopVendorHal();
                return false;
            }
            iface = (IWifiIface) mIWifiApIface;
        }
        mIWifiChip = mHalDeviceManager.getChip(iface);
        if (mIWifiChip == null) {
            Log.e(TAG, "Failed to get the chip created for the Iface. Vendor Hal start failed");
            stopVendorHal();
            return false;
        }
        Log.i(TAG, "Vendor Hal started successfully");
        return true;
    }

    /**
     * Registers the sta iface callback.
     */
    private boolean registerStaIfaceCallback() {
        synchronized (sLock) {
            if (mIWifiStaIface == null || mIWifiStaIfaceEventCallback == null) return false;
            try {
                kilroy();
                WifiStatus status =
                        mIWifiStaIface.registerEventCallback(mIWifiStaIfaceEventCallback);
                return (status.code == WifiStatusCode.SUCCESS);
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return false;
            }
        }
    }

    /**
     * Stops the HAL
     */
    public void stopVendorHal() {
        mHalDeviceManager.stop();
        Log.i(TAG, "Vendor Hal stopped");
    }

    /**
     * Tests whether the HAL is running or not
     */
    public boolean isHalStarted() {
        return (mIWifiStaIface != null || mIWifiApIface != null);
    }

    /**
     * Gets the scan capabilities
     *
     * @param capabilities object to be filled in
     * @return true for success. false for failure
     */
    public boolean getScanCapabilities(WifiNative.ScanCapabilities capabilities) {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * to be implemented
     */
    public boolean startScan(WifiNative.ScanSettings settings,
                             WifiNative.ScanEventHandler eventHandler) {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * to be implemented
     */
    public void stopScan() {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * to be implemented
     */
    public void pauseScan() {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * to be implemented
     */
    public void restartScan() {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * to be implemented
     */
    public WifiScanner.ScanData[] getScanResults(boolean flush) {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * Get the link layer statistics
     *
     * Note - we always enable link layer stats on a STA interface.
     *
     * @return the statistics, or null if unable to do so
     */
    public WifiLinkLayerStats getWifiLinkLayerStats() {
        kilroy();
        synchronized (sLock) {
            try {
                if (mIWifiStaIface == null) return null;
                kilroy();
                WifiLinkLayerStats out = new WifiLinkLayerStats();
                MutableBoolean ok = new MutableBoolean(false);
                kilroy();
                mIWifiStaIface.getLinkLayerStats((status, stats) -> {
                            kilroy();
                            if (status.code != WifiStatusCode.SUCCESS) return;
                            out.status = 0; // TODO
                            out.SSID = null; // TODO
                            out.BSSID = null; // TODO
                            out.beacon_rx = stats.iface.beaconRx;
                            out.rssi_mgmt = stats.iface.avgRssiMgmt;
                        /* WME Best Effort Access Category */
                            out.rxmpdu_be = stats.iface.wmeBePktStats.rxMpdu;
                            out.txmpdu_be = stats.iface.wmeBePktStats.txMpdu;
                            out.lostmpdu_be = stats.iface.wmeBePktStats.lostMpdu;
                            out.retries_be = stats.iface.wmeBePktStats.retries;
                        /* WME Background Access Category */
                            out.rxmpdu_bk = stats.iface.wmeBkPktStats.rxMpdu;
                            out.txmpdu_bk = stats.iface.wmeBkPktStats.txMpdu;
                            out.lostmpdu_bk = stats.iface.wmeBkPktStats.lostMpdu;
                            out.retries_bk = stats.iface.wmeBkPktStats.retries;
                        /* WME Video Access Category */
                            out.rxmpdu_vi = stats.iface.wmeViPktStats.rxMpdu;
                            out.txmpdu_vi = stats.iface.wmeViPktStats.txMpdu;
                            out.lostmpdu_vi = stats.iface.wmeViPktStats.lostMpdu;
                            out.retries_vi = stats.iface.wmeViPktStats.retries;
                        /* WME Voice Access Category */
                            out.rxmpdu_vo = stats.iface.wmeVoPktStats.rxMpdu;
                            out.txmpdu_vo = stats.iface.wmeVoPktStats.txMpdu;
                            out.lostmpdu_vo = stats.iface.wmeVoPktStats.lostMpdu;
                            out.retries_vo = stats.iface.wmeVoPktStats.retries;
                            out.on_time = stats.radio.onTimeInMs;
                            out.tx_time = stats.radio.txTimeInMs;
                            out.tx_time_per_level = new int[stats.radio.txTimeInMsPerLevel.size()];
                            for (int i = 0; i < out.tx_time_per_level.length; i++) {
                                out.tx_time_per_level[i] = stats.radio.txTimeInMsPerLevel.get(i);
                            }
                            out.rx_time = stats.radio.rxTimeInMs;
                            out.on_time_scan = stats.radio.onTimeInMsForScan;
                            kilroy();
                            ok.value = true;
                        }
                );
                return ok.value ? out : null;
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return null;
            }
        }
    }

    @VisibleForTesting
    boolean mLinkLayerStatsDebug = false;  // Passed to Hal

    /**
     * Enables the linkLayerStats in the Hal.
     *
     * This is called unconditionally whenever we create a STA interface.
     *
     */
    private void enableLinkLayerStats() {
        synchronized (sLock) {
            try {
                kilroy();
                WifiStatus status;
                status = mIWifiStaIface.enableLinkLayerStatsCollection(mLinkLayerStatsDebug);
                if (status.code != WifiStatusCode.SUCCESS) {
                    kilroy();
                    Log.e(TAG, "unable to enable link layer stats collection");
                }
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
            }
        }
    }

    /**
     * Translation table used by getSupportedFeatureSet for translating IWifiStaIface caps
     */
    private static final int[][] sFeatureCapabilityTranslation = {
            {WifiManager.WIFI_FEATURE_INFRA_5G,
                    IWifiStaIface.StaIfaceCapabilityMask.STA_5G
            },
            {WifiManager.WIFI_FEATURE_PASSPOINT,
                    IWifiStaIface.StaIfaceCapabilityMask.HOTSPOT
            },
            {WifiManager.WIFI_FEATURE_SCANNER,
                    IWifiStaIface.StaIfaceCapabilityMask.BACKGROUND_SCAN,
            },
            {WifiManager.WIFI_FEATURE_PNO,
                    IWifiStaIface.StaIfaceCapabilityMask.PNO
            },
            {WifiManager.WIFI_FEATURE_TDLS,
                    IWifiStaIface.StaIfaceCapabilityMask.TDLS
            },
            {WifiManager.WIFI_FEATURE_TDLS_OFFCHANNEL,
                    IWifiStaIface.StaIfaceCapabilityMask.TDLS_OFFCHANNEL
            },
            {WifiManager.WIFI_FEATURE_LINK_LAYER_STATS,
                    IWifiStaIface.StaIfaceCapabilityMask.LINK_LAYER_STATS
            },
            {WifiManager.WIFI_FEATURE_RSSI_MONITOR,
                    IWifiStaIface.StaIfaceCapabilityMask.RSSI_MONITOR
            },
            {WifiManager.WIFI_FEATURE_MKEEP_ALIVE,
                    IWifiStaIface.StaIfaceCapabilityMask.KEEP_ALIVE
            },
            {WifiManager.WIFI_FEATURE_CONFIG_NDO,
                    IWifiStaIface.StaIfaceCapabilityMask.ND_OFFLOAD
            },
            {WifiManager.WIFI_FEATURE_CONTROL_ROAMING,
                    IWifiStaIface.StaIfaceCapabilityMask.CONTROL_ROAMING
            },
            {WifiManager.WIFI_FEATURE_IE_WHITELIST,
                    IWifiStaIface.StaIfaceCapabilityMask.PROBE_IE_WHITELIST
            },
            {WifiManager.WIFI_FEATURE_SCAN_RAND,
                    IWifiStaIface.StaIfaceCapabilityMask.SCAN_RAND
            },
    };

    /**
     * Feature bit mask translation for STAs
     *
     * @param capabilities bitmask defined IWifiStaIface.StaIfaceCapabilityMask
     * @return bitmask defined by WifiManager.WIFI_FEATURE_*
     */
    @VisibleForTesting
    int wifiFeatureMaskFromStaCapabilities(int capabilities) {
        int features = 0;
        for (int i = 0; i < sFeatureCapabilityTranslation.length; i++) {
            if ((capabilities & sFeatureCapabilityTranslation[i][1]) != 0) {
                features |= sFeatureCapabilityTranslation[i][0];
            }
        }
        return features;
    }

    /**
     * Get the supported features
     * <p>
     * Note that not all the WifiManager.WIFI_FEATURE_* bits are supplied through
     * this call. //TODO(b/34900537) fix this
     *
     * @return bitmask defined by WifiManager.WIFI_FEATURE_*
     */
    public int getSupportedFeatureSet() {
        int featureSet = 0;
        try {
            final MutableInt feat = new MutableInt(0);
            synchronized (sLock) {
                if (mIWifiStaIface != null) {
                    mIWifiStaIface.getCapabilities((status, capabilities) -> {
                        if (status.code != WifiStatusCode.SUCCESS) return;
                        feat.value = wifiFeatureMaskFromStaCapabilities(capabilities);
                    });
                }
            }
            featureSet = feat.value;
        } catch (RemoteException e) {
            handleRemoteException(e);
            return 0;
        }

        Set<Integer> supportedIfaceTypes = mHalDeviceManager.getSupportedIfaceTypes();
        if (supportedIfaceTypes.contains(IfaceType.STA)) {
            featureSet |= WifiManager.WIFI_FEATURE_INFRA;
        }
        if (supportedIfaceTypes.contains(IfaceType.AP)) {
            featureSet |= WifiManager.WIFI_FEATURE_MOBILE_HOTSPOT;
        }
        if (supportedIfaceTypes.contains(IfaceType.P2P)) {
            featureSet |= WifiManager.WIFI_FEATURE_P2P;
        }
        if (supportedIfaceTypes.contains(IfaceType.NAN)) {
            featureSet |= WifiManager.WIFI_FEATURE_AWARE;
        }

        return featureSet;
    }

    /* RTT related commands/events */

    /**
     * Starts a new rtt request
     *
     * @param params
     * @param handler
     * @return success indication
     */
    public boolean requestRtt(RttManager.RttParams[] params, WifiNative.RttEventHandler handler) {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * Cancels an outstanding rtt request
     *
     * @param params
     * @return true if there was an outstanding request and it was successfully cancelled
     */
    public boolean cancelRtt(RttManager.RttParams[] params) {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * Enables RTT responder role on the device.
     *
     * @return {@link ResponderConfig} if the responder role is successfully enabled,
     * {@code null} otherwise.
     */
    @Nullable
    public ResponderConfig enableRttResponder(int timeoutSeconds) {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * Disables RTT responder role.
     *
     * @return {@code true} if responder role is successfully disabled,
     * {@code false} otherwise.
     */
    public boolean disableRttResponder() {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * Set the MAC OUI during scanning.
     *
     * An OUI {Organizationally Unique Identifier} is a 24-bit number that
     * uniquely identifies a vendor or manufacturer.
     *
     * @param oui
     * @return true for success
     */
    public boolean setScanningMacOui(byte[] oui) {
        kilroy();
        if (oui == null) return false;
        kilroy();
        if (oui.length != 3) return false;
        kilroy();
        synchronized (sLock) {
            try {
                if (mIWifiStaIface == null) return false;
                WifiStatus status = mIWifiStaIface.setScanningMacOui(oui);
                if (status.code != WifiStatusCode.SUCCESS) return false;
                kilroy();
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return false;
            }
        }
    }

    /**
     * not supported
     */
    public int[] getChannelsForBand(int band) {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * not supported
     */
    public boolean isGetChannelsForBandSupported() {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * Set DFS - actually, this is always on.
     *
     * @param dfsOn
     * @return success indication
     */
    public boolean setDfsFlag(boolean dfsOn) {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * RTT (Round Trip Time) measurement capabilities of the device.
     */
    public RttManager.RttCapabilities getRttCapabilities() {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * Get the APF (Android Packet Filter) capabilities of the device
     */
    public ApfCapabilities getApfCapabilities() {
        class AnswerBox {
            public ApfCapabilities value = sNoApfCapabilities;
        }
        synchronized (sLock) {
            try {
                if (mIWifiStaIface == null) return sNoApfCapabilities;
                AnswerBox box = new AnswerBox();
                mIWifiStaIface.getApfPacketFilterCapabilities((status, capabilities) -> {
                    if (status.code != WifiStatusCode.SUCCESS) return;
                    box.value = new ApfCapabilities(
                        /* apfVersionSupported */   capabilities.version,
                        /* maximumApfProgramSize */ capabilities.maxLength,
                        /* apfPacketFormat */       android.system.OsConstants.ARPHRD_ETHER);
                });
                return box.value;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return sNoApfCapabilities;
            }
        }
    }

    private static final ApfCapabilities sNoApfCapabilities = new ApfCapabilities(0, 0, 0);

    /**
     * Installs an APF program on this iface, replacing any existing program.
     *
     * @param filter is the android packet filter program
     * @return true for success
     */
    public boolean installPacketFilter(byte[] filter) {
        kilroy();
        int cmdId = 0; //TODO(b/34901818) We only aspire to support one program at a time
        if (filter == null) return false;
        // Copy the program before taking the lock.
        ArrayList<Byte> program = new ArrayList<>(filter.length);
        for (byte b : filter) {
            program.add(b);
        }
        synchronized (sLock) {
            try {
                if (mIWifiStaIface == null) return false;
                WifiStatus status = mIWifiStaIface.installApfPacketFilter(cmdId, program);
                if (status.code != WifiStatusCode.SUCCESS) return false;
                kilroy();
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return false;
            }
        }
    }

    /**
     * Set country code for this AP iface.
     *
     * @param countryCode - two-letter country code (as ISO 3166)
     * @return true for success
     */
    public boolean setCountryCodeHal(String countryCode) {
        kilroy();
        if (countryCode == null) return false;
        if (countryCode.length() != 2) return false;
        byte[] code;
        try {
            code = NativeUtil.stringToByteArray(countryCode);
        } catch (IllegalArgumentException e) {
            kilroy();
            return false;
        }
        synchronized (sLock) {
            try {
                if (mIWifiApIface == null) return false;
                kilroy();
                WifiStatus status = mIWifiApIface.setCountryCode(code);
                if (status.code != WifiStatusCode.SUCCESS) return false;
                kilroy();
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return false;
            }
        }
    }

    /**
     * to be implemented TODO(b/34901821)
     */
    public boolean setLoggingEventHandler(WifiNative.WifiLoggerEventHandler handler) {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * Control debug data collection
     *
     * @param verboseLevel       0 to 3, inclusive. 0 stops logging.
     * @param flags              Ignored.
     * @param maxIntervalInSec   Maximum interval between reports; ignore if 0.
     * @param minDataSizeInBytes Minimum data size in buffer for report; ignore if 0.
     * @param ringName           Name of the ring for which data collection is to start.
     * @return true for success
     */
    public boolean startLoggingRingBuffer(int verboseLevel, int flags, int maxIntervalInSec,
                                          int minDataSizeInBytes, String ringName) {
        kilroy();
        synchronized (sLock) {
            try {
                if (mIWifiChip == null) return false;
                kilroy();
                // note - flags are not used
                WifiStatus status = mIWifiChip.startLoggingToDebugRingBuffer(
                        ringName,
                        verboseLevel,
                        maxIntervalInSec,
                        minDataSizeInBytes
                );
                return status.code == WifiStatusCode.SUCCESS;
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return false;
            }
        }
    }

    /**
     * Pointlessly fail
     *
     * @return -1
     */
    public int getSupportedLoggerFeatureSet() {
        return -1;
    }

    /**
     * to be implemented TODO(b/34901821)
     */
    public boolean resetLogHandler() {
        kilroy();
        throw new UnsupportedOperationException();
    }

    private String mDriverDescription; // Cached value filled by requestChipDebugInfo()

    /**
     * Vendor-provided wifi driver version string
     */
    public String getDriverVersion() {
        synchronized (sLock) {
            if (mDriverDescription == null) requestChipDebugInfo();
            return mDriverDescription;
        }
    }

    private String mFirmwareDescription; // Cached value filled by requestChipDebugInfo()

    /**
     * Vendor-provided wifi firmware version string
     */
    public String getFirmwareVersion() {
        synchronized (sLock) {
            if (mFirmwareDescription == null) requestChipDebugInfo();
            return mFirmwareDescription;
        }
    }

    /**
     * Refreshes our idea of the driver and firmware versions
     */
    private void requestChipDebugInfo() {
        mDriverDescription = null;
        mFirmwareDescription = null;
        try {
            if (mIWifiChip == null) return;
            mIWifiChip.requestChipDebugInfo((status, chipDebugInfo) -> {
                if (status.code != WifiStatusCode.SUCCESS) return;
                mDriverDescription = chipDebugInfo.driverDescription;
                mFirmwareDescription = chipDebugInfo.firmwareDescription;
            });
        } catch (RemoteException e) {
            handleRemoteException(e);
            return;
        }
        Log.e(TAG, "Driver: " + mDriverDescription + " Firmware: " + mFirmwareDescription);
    }

    /**
     * Creates RingBufferStatus from the Hal version
     */
    private static WifiNative.RingBufferStatus ringBufferStatus(WifiDebugRingBufferStatus h) {
        WifiNative.RingBufferStatus ans = new WifiNative.RingBufferStatus();
        ans.name = h.ringName;
        ans.flag = frameworkRingBufferFlagsFromHal(h.flags);
        ans.ringBufferId = h.ringId;
        ans.ringBufferByteSize = h.sizeInBytes;
        ans.verboseLevel = h.verboseLevel;
        // Remaining fields are unavailable
        //  writtenBytes;
        //  readBytes;
        //  writtenRecords;
        return ans;
    }

    /**
     * Translates a hal wifiDebugRingBufferFlag to the WifiNative version
     */
    private static int frameworkRingBufferFlagsFromHal(int wifiDebugRingBufferFlag) {
        BitMask checkoff = new BitMask(wifiDebugRingBufferFlag);
        int flags = 0;
        if (checkoff.testAndClear(WifiDebugRingBufferFlags.HAS_BINARY_ENTRIES)) {
            flags |= WifiNative.RingBufferStatus.HAS_BINARY_ENTRIES;
        }
        if (checkoff.testAndClear(WifiDebugRingBufferFlags.HAS_ASCII_ENTRIES)) {
            flags |= WifiNative.RingBufferStatus.HAS_ASCII_ENTRIES;
        }
        if (checkoff.testAndClear(WifiDebugRingBufferFlags.HAS_PER_PACKET_ENTRIES)) {
            flags |= WifiNative.RingBufferStatus.HAS_PER_PACKET_ENTRIES;
        }
        if (checkoff.value != 0) {
            throw new IllegalArgumentException("Unknown WifiDebugRingBufferFlag " + checkoff.value);
        }
        return flags;
    }

    /**
     * Creates array of RingBufferStatus from the Hal version
     */
    private static WifiNative.RingBufferStatus[] makeRingBufferStatusArray(
            ArrayList<WifiDebugRingBufferStatus> ringBuffers) {
        WifiNative.RingBufferStatus[] ans = new WifiNative.RingBufferStatus[ringBuffers.size()];
        int i = 0;
        for (WifiDebugRingBufferStatus b : ringBuffers) {
            ans[i++] = ringBufferStatus(b);
        }
        return ans;
    }

    /**
     * API to get the status of all ring buffers supported by driver
     */
    public WifiNative.RingBufferStatus[] getRingBufferStatus() {
        kilroy();
        class AnswerBox {
            public WifiNative.RingBufferStatus[] value = null;
        }
        AnswerBox ans = new AnswerBox();
        synchronized (sLock) {
            if (mIWifiChip == null) return null;
            try {
                kilroy();
                mIWifiChip.getDebugRingBuffersStatus((status, ringBuffers) -> {
                    kilroy();
                    if (status.code != WifiStatusCode.SUCCESS) return;
                    ans.value = makeRingBufferStatusArray(ringBuffers);
                });
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return null;
            }
        }
        return ans.value;
    }

    /**
     * indicates to driver that all
     * the data has to be uploaded urgently
     */
    public boolean getRingBufferData(String ringName) {
        kilroy();
        synchronized (sLock) {
            try {
                if (mIWifiChip == null) return false;
                kilroy();
                WifiStatus status = mIWifiChip.forceDumpToDebugRingBuffer(ringName);
                return status.code == WifiStatusCode.SUCCESS;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return false;
            }
        }
    }

    /**
     * Request vendor debug info from the firmware
     */
    public byte[] getFwMemoryDump() {
        kilroy();
        class AnswerBox {
            public byte[] value;
        }
        AnswerBox ans = new AnswerBox();
        synchronized (sLock) {
            if (mIWifiChip == null) return (null);
            try {
                kilroy();
                mIWifiChip.requestFirmwareDebugDump((status, blob) -> {
                    kilroy();
                    if (status.code != WifiStatusCode.SUCCESS) return;
                    kilroy();
                    ans.value = NativeUtil.byteArrayFromArrayList(blob);
                });
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return null;
            }
        }
        return ans.value;
    }

    /**
     * Request vendor debug info from the driver
     */
    public byte[] getDriverStateDump() {
        kilroy();
        class AnswerBox {
            public byte[] value;
        }
        AnswerBox ans = new AnswerBox();
        synchronized (sLock) {
            if (mIWifiChip == null) return (null);
            try {
                kilroy();
                mIWifiChip.requestDriverDebugDump((status, blob) -> {
                    kilroy();
                    if (status.code != WifiStatusCode.SUCCESS) return;
                    kilroy();
                    ans.value = NativeUtil.byteArrayFromArrayList(blob);
                });
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return null;
            }
        }
        return ans.value;
    }

    /**
     * Start packet fate monitoring
     *
     * Once started, monitoring remains active until HAL is unloaded.
     *
     * @return true for success
     */
    public boolean startPktFateMonitoring() {
        kilroy();
        synchronized (sLock) {
            if (mIWifiStaIface == null) return false;
            try {
                kilroy();
                WifiStatus status = mIWifiStaIface.startDebugPacketFateMonitoring();
                return status.code == WifiStatusCode.SUCCESS;
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return false;
            }
        }
    }

    private byte halToFrameworkPktFateFrameType(int type) {
        switch (type) {
            case WifiDebugPacketFateFrameType.UNKNOWN:
                return WifiLoggerHal.FRAME_TYPE_UNKNOWN;
            case WifiDebugPacketFateFrameType.ETHERNET_II:
                return WifiLoggerHal.FRAME_TYPE_ETHERNET_II;
            case WifiDebugPacketFateFrameType.MGMT_80211:
                return WifiLoggerHal.FRAME_TYPE_80211_MGMT;
            default:
                throw new IllegalArgumentException("bad " + type);
        }
    }

    private byte halToFrameworkRxPktFate(int type) {
        switch (type) {
            case WifiDebugRxPacketFate.SUCCESS:
                return WifiLoggerHal.RX_PKT_FATE_SUCCESS;
            case WifiDebugRxPacketFate.FW_QUEUED:
                return WifiLoggerHal.RX_PKT_FATE_FW_QUEUED;
            case WifiDebugRxPacketFate.FW_DROP_FILTER:
                return WifiLoggerHal.RX_PKT_FATE_FW_DROP_FILTER;
            case WifiDebugRxPacketFate.FW_DROP_INVALID:
                return WifiLoggerHal.RX_PKT_FATE_FW_DROP_INVALID;
            case WifiDebugRxPacketFate.FW_DROP_NOBUFS:
                return WifiLoggerHal.RX_PKT_FATE_FW_DROP_NOBUFS;
            case WifiDebugRxPacketFate.FW_DROP_OTHER:
                return WifiLoggerHal.RX_PKT_FATE_FW_DROP_OTHER;
            case WifiDebugRxPacketFate.DRV_QUEUED:
                return WifiLoggerHal.RX_PKT_FATE_DRV_QUEUED;
            case WifiDebugRxPacketFate.DRV_DROP_FILTER:
                return WifiLoggerHal.RX_PKT_FATE_DRV_DROP_FILTER;
            case WifiDebugRxPacketFate.DRV_DROP_INVALID:
                return WifiLoggerHal.RX_PKT_FATE_DRV_DROP_INVALID;
            case WifiDebugRxPacketFate.DRV_DROP_NOBUFS:
                return WifiLoggerHal.RX_PKT_FATE_DRV_DROP_NOBUFS;
            case WifiDebugRxPacketFate.DRV_DROP_OTHER:
                return WifiLoggerHal.RX_PKT_FATE_DRV_DROP_OTHER;
            default:
                throw new IllegalArgumentException("bad " + type);
        }
    }

    private byte halToFrameworkTxPktFate(int type) {
        switch (type) {
            case WifiDebugTxPacketFate.ACKED:
                return WifiLoggerHal.TX_PKT_FATE_ACKED;
            case WifiDebugTxPacketFate.SENT:
                return WifiLoggerHal.TX_PKT_FATE_SENT;
            case WifiDebugTxPacketFate.FW_QUEUED:
                return WifiLoggerHal.TX_PKT_FATE_FW_QUEUED;
            case WifiDebugTxPacketFate.FW_DROP_INVALID:
                return WifiLoggerHal.TX_PKT_FATE_FW_DROP_INVALID;
            case WifiDebugTxPacketFate.FW_DROP_NOBUFS:
                return WifiLoggerHal.TX_PKT_FATE_FW_DROP_NOBUFS;
            case WifiDebugTxPacketFate.FW_DROP_OTHER:
                return WifiLoggerHal.TX_PKT_FATE_FW_DROP_OTHER;
            case WifiDebugTxPacketFate.DRV_QUEUED:
                return WifiLoggerHal.TX_PKT_FATE_DRV_QUEUED;
            case WifiDebugTxPacketFate.DRV_DROP_INVALID:
                return WifiLoggerHal.TX_PKT_FATE_DRV_DROP_INVALID;
            case WifiDebugTxPacketFate.DRV_DROP_NOBUFS:
                return WifiLoggerHal.TX_PKT_FATE_DRV_DROP_NOBUFS;
            case WifiDebugTxPacketFate.DRV_DROP_OTHER:
                return WifiLoggerHal.TX_PKT_FATE_DRV_DROP_OTHER;
            default:
                throw new IllegalArgumentException("bad " + type);
        }
    }

    /**
     * Retrieve fates of outbound packets
     *
     * Reports the outbound frames for the most recent association (space allowing).
     *
     * @param reportBufs
     * @return true for success
     */
    public boolean getTxPktFates(WifiNative.TxFateReport[] reportBufs) {
        kilroy();
        if (ArrayUtils.isEmpty(reportBufs)) return false;
        synchronized (sLock) {
            if (mIWifiStaIface == null) return false;
            try {
                kilroy();
                MutableBoolean ok = new MutableBoolean(false);
                mIWifiStaIface.getDebugTxPacketFates((status, fates) -> {
                            kilroy();
                            if (status.code != WifiStatusCode.SUCCESS) return;
                            int i = 0;
                            for (WifiDebugTxPacketFateReport fate : fates) {
                                kilroy();
                                if (i >= reportBufs.length) break;
                                byte code = halToFrameworkTxPktFate(fate.fate);
                                long us = fate.frameInfo.driverTimestampUsec;
                                byte type =
                                        halToFrameworkPktFateFrameType(fate.frameInfo.frameType);
                                byte[] frame =
                                        NativeUtil.byteArrayFromArrayList(
                                                fate.frameInfo.frameContent);
                                reportBufs[i++] =
                                        new WifiNative.TxFateReport(code, us, type, frame);
                            }
                            ok.value = true;
                        }
                );
                return ok.value;
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return false;
            }
        }
    }

    /**
     * Retrieve fates of inbound packets
     *
     * Reports the inbound frames for the most recent association (space allowing).
     *
     * @param reportBufs
     * @return true for success
     */
    public boolean getRxPktFates(WifiNative.RxFateReport[] reportBufs) {
        kilroy();
        if (ArrayUtils.isEmpty(reportBufs)) return false;
        synchronized (sLock) {
            if (mIWifiStaIface == null) return false;
            try {
                kilroy();
                MutableBoolean ok = new MutableBoolean(false);
                mIWifiStaIface.getDebugRxPacketFates((status, fates) -> {
                            kilroy();
                            if (status.code != WifiStatusCode.SUCCESS) return;
                            int i = 0;
                            for (WifiDebugRxPacketFateReport fate : fates) {
                                kilroy();
                                if (i >= reportBufs.length) break;
                                byte code = halToFrameworkRxPktFate(fate.fate);
                                long us = fate.frameInfo.driverTimestampUsec;
                                byte type =
                                        halToFrameworkPktFateFrameType(fate.frameInfo.frameType);
                                byte[] frame =
                                        NativeUtil.byteArrayFromArrayList(
                                                fate.frameInfo.frameContent);
                                reportBufs[i++] =
                                        new WifiNative.RxFateReport(code, us, type, frame);
                            }
                            ok.value = true;
                        }
                );
                return ok.value;
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return false;
            }
        }
    }

    /**
     * Start sending the specified keep alive packets periodically.
     * @param slot
     * @param srcMac
     * @param keepAlivePacket
     * @param periodInMs
     * @return 0 for success, -1 for error
     */
    public int startSendingOffloadedPacket(
            int slot, byte[] srcMac, KeepalivePacketData keepAlivePacket, int periodInMs) {
        Log.d(TAG, "startSendingOffloadedPacket slot=" + slot + " periodInMs=" + periodInMs);

        ArrayList<Byte> data = NativeUtil.byteArrayToArrayList(keepAlivePacket.data);
        short protocol = (short) (keepAlivePacket.protocol);

        synchronized (sLock) {
            if (mIWifiStaIface == null) return -1;
            try {
                WifiStatus status = mIWifiStaIface.startSendingKeepAlivePackets(
                        slot,
                        data,
                        protocol,
                        srcMac,
                        keepAlivePacket.dstMac,
                        periodInMs);
                if (status.code != WifiStatusCode.SUCCESS) return -1;
                return 0;
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return -1;
            }
        }
    }

    /**
     * Stop sending the specified keep alive packets.
     *
     * @param slot id - same as startSendingOffloadedPacket call.
     * @return 0 for success, -1 for error
     */
    public int stopSendingOffloadedPacket(int slot) {
        Log.d(TAG, "stopSendingOffloadedPacket " + slot);

        synchronized (sLock) {
            if (mIWifiStaIface == null) return -1;
            try {
                WifiStatus wifiStatus = mIWifiStaIface.stopSendingKeepAlivePackets(slot);
                if (wifiStatus.code != WifiStatusCode.SUCCESS) return -1;
                kilroy();
                return 0;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return -1;
            }
        }
    }

    /**
     * Start RSSI monitoring on the currently connected access point.
     *
     * @param maxRssi          Maximum RSSI threshold.
     * @param minRssi          Minimum RSSI threshold.
     * @param rssiEventHandler Called when RSSI goes above maxRssi or below minRssi
     * @return 0 for success, -1 for failure
     */
    public int startRssiMonitoring(byte maxRssi, byte minRssi,
                                   WifiNative.WifiRssiEventHandler rssiEventHandler) {
        kilroy();
        throw new UnsupportedOperationException();
    }

    /**
     * Stop RSSI monitoring
     *
     * @return 0 for success, -1 for failure
     */
    public int stopRssiMonitoring() {
        kilroy();
        throw new UnsupportedOperationException();
    }

    //TODO - belongs in NativeUtil
    private static int[] intsFromArrayList(ArrayList<Integer> a) {
        if (a == null) return null;
        int[] b = new int[a.size()];
        int i = 0;
        for (Integer e : a) b[i++] = e;
        return b;
    }

    /**
     * Translates from Hal version of wake reason stats to the framework version of same
     *
     * @param h - Hal version of wake reason stats
     * @return framework version of same
     */
    private static WifiWakeReasonAndCounts halToFrameworkWakeReasons(
            WifiDebugHostWakeReasonStats h) {
        if (h == null) return null;
        WifiWakeReasonAndCounts ans = new WifiWakeReasonAndCounts();
        ans.totalCmdEventWake = h.totalCmdEventWakeCnt;
        ans.totalDriverFwLocalWake = h.totalDriverFwLocalWakeCnt;
        ans.totalRxDataWake = h.totalRxPacketWakeCnt;
        ans.rxUnicast = h.rxPktWakeDetails.rxUnicastCnt;
        ans.rxMulticast = h.rxPktWakeDetails.rxMulticastCnt;
        ans.rxBroadcast = h.rxPktWakeDetails.rxBroadcastCnt;
        ans.icmp = h.rxIcmpPkWakeDetails.icmpPkt;
        ans.icmp6 = h.rxIcmpPkWakeDetails.icmp6Pkt;
        ans.icmp6Ra = h.rxIcmpPkWakeDetails.icmp6Ra;
        ans.icmp6Na = h.rxIcmpPkWakeDetails.icmp6Na;
        ans.icmp6Ns = h.rxIcmpPkWakeDetails.icmp6Ns;
        ans.ipv4RxMulticast = h.rxMulticastPkWakeDetails.ipv4RxMulticastAddrCnt;
        ans.ipv6Multicast = h.rxMulticastPkWakeDetails.ipv6RxMulticastAddrCnt;
        ans.otherRxMulticast = h.rxMulticastPkWakeDetails.otherRxMulticastAddrCnt;
        ans.cmdEventWakeCntArray = intsFromArrayList(h.cmdEventWakeCntPerType);
        ans.driverFWLocalWakeCntArray = intsFromArrayList(h.driverFwLocalWakeCntPerType);
        return ans;
    }

    /**
     * Fetch the host wakeup reasons stats from wlan driver.
     *
     * @return the |WifiWakeReasonAndCounts| from the wlan driver, or null on failure.
     */
    public WifiWakeReasonAndCounts getWlanWakeReasonCount() {
        kilroy();
        class AnswerBox {
            public WifiDebugHostWakeReasonStats value = null;
        }
        AnswerBox ans = new AnswerBox();
        synchronized (sLock) {
            if (mIWifiChip == null) return null;
            try {
                kilroy();
                mIWifiChip.getDebugHostWakeReasonStats((status, stats) -> {
                    kilroy();
                    if (status.code == WifiStatusCode.SUCCESS) {
                        ans.value = stats;
                    }
                });
                kilroy();
                return halToFrameworkWakeReasons(ans.value);
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return null;
            }
        }
    }

    /**
     * Enable/Disable Neighbour discovery offload functionality in the firmware.
     *
     * @param enabled true to enable, false to disable.
     */
    public boolean configureNeighborDiscoveryOffload(boolean enabled) {
        kilroy();
        synchronized (sLock) {
            if (mIWifiStaIface == null) return false;
            kilroy();
            try {
                kilroy();
                WifiStatus wifiStatus = mIWifiStaIface.enableNdOffload(enabled);
                if (wifiStatus.code != WifiStatusCode.SUCCESS) {
                    kilroy();
                    noteHidlError(wifiStatus, "configureNeighborDiscoveryOffload");
                    return false;
                }
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return false;
            }
        }
        return true;
    }

    // Firmware roaming control.

    /**
     * Query the firmware roaming capabilities.
     *
     * @param capabilities object to be filled in
     * @return true for success; false for failure
     */
    public boolean getRoamingCapabilities(WifiNative.RoamingCapabilities capabilities) {
        kilroy();
        synchronized (sLock) {
            kilroy();
            try {
                kilroy();
                if (!isHalStarted()) return false;
                MutableBoolean ok = new MutableBoolean(false);
                WifiNative.RoamingCapabilities out = capabilities;
                mIWifiStaIface.getRoamingCapabilities((status, cap) -> {
                    kilroy();
                    if (status.code != WifiStatusCode.SUCCESS) return;
                    out.maxBlacklistSize = cap.maxBlacklistSize;
                    out.maxWhitelistSize = cap.maxWhitelistSize;
                    ok.value = true;
                });
                return ok.value;
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return false;
            }
        }
    }

    /**
     * Enable/disable firmware roaming.
     *
     * @param state the intended roaming state
     * @return SUCCESS, FAILURE, or BUSY
     */
    public int enableFirmwareRoaming(int state) {
        kilroy();
        synchronized (sLock) {
            if (mIWifiStaIface == null) return WifiStatusCode.ERROR_NOT_STARTED;
            kilroy();
            try {
                kilroy();
                byte val;
                switch (state) {
                    case WifiNative.DISABLE_FIRMWARE_ROAMING:
                        val = StaRoamingState.DISABLED;
                        break;
                    case WifiNative.ENABLE_FIRMWARE_ROAMING:
                        val = StaRoamingState.ENABLED;
                        break;
                    default:
                        Log.e(TAG, "enableFirmwareRoaming invalid argument " + state);
                        return WifiStatusCode.ERROR_INVALID_ARGS;
                }

                kilroy();
                WifiStatus status = mIWifiStaIface.setRoamingState(val);
                Log.d(TAG, "setRoamingState returned " + status.code);
                return status.code;
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return WifiStatusCode.ERROR_UNKNOWN;
            }
        }
    }

    /**
     * Set firmware roaming configurations.
     *
     * @param config new roaming configuration object
     * @return true for success; false for failure
     */
    public boolean configureRoaming(WifiNative.RoamingConfig config) {
        kilroy();
        synchronized (sLock) {
            if (mIWifiStaIface == null) return false;
            kilroy();
            try {
                kilroy();
                StaRoamingConfig roamingConfig = new StaRoamingConfig();

                // parse the blacklist BSSIDs if any
                if (config.blacklistBssids != null) {
                    kilroy();
                    for (String bssid : config.blacklistBssids) {
                        byte[] mac = NativeUtil.macAddressToByteArray(bssid);
                        roamingConfig.bssidBlacklist.add(mac);
                    }
                }

                // parse the whitelist SSIDs if any
                if (config.whitelistSsids != null) {
                    kilroy();
                    for (String ssidStr : config.whitelistSsids) {
                        String unquotedSsidStr = WifiInfo.removeDoubleQuotes(ssidStr);

                        int len = unquotedSsidStr.length();
                        if (len > 32) {
                            Log.e(TAG, "configureRoaming: skip invalid SSID " + unquotedSsidStr);
                            continue;
                        }
                        byte[] ssid = new byte[len];
                        for (int i = 0; i < len; i++) {
                            ssid[i] = (byte) unquotedSsidStr.charAt(i);
                        }
                        roamingConfig.ssidWhitelist.add(ssid);
                    }
                }

                kilroy();
                WifiStatus status = mIWifiStaIface.configureRoaming(roamingConfig);
                if (status.code != WifiStatusCode.SUCCESS) {
                    kilroy();
                    noteHidlError(status, "configureRoaming");
                    return false;
                }
            } catch (RemoteException e) {
                kilroy();
                handleRemoteException(e);
                return false;
            }
            kilroy();
            return true;
        }
    }

    StackTraceElement[] mTrace;

    private void kilroy() {
        Thread cur = Thread.currentThread();
        mTrace = cur.getStackTrace();
        StackTraceElement s = mTrace[3];
        String name = s.getMethodName();
        if (name.contains("lambda$")) {
            // Try to find a friendlier method name
            String myFile = s.getFileName();
            if (myFile != null) {
                for (int i = 4; i < mTrace.length; i++) {
                    if (myFile.equals(mTrace[i].getFileName())) {
                        name = mTrace[i].getMethodName();
                        break;
                    }
                }
            }
        }
        Log.e(TAG, "th " + cur.getId() + " line " + s.getLineNumber() + " " + name);
    }

    /**
     * Callback for events on the STA interface.
     */
    private class EventCallback extends IWifiStaIfaceEventCallback.Stub {
        public void onBackgroundScanFailure(int cmdId) {
            kilroy();
            Log.e(TAG, "onBackgroundScanFailure " + cmdId);
        }

        public void onBackgroundFullScanResult(int cmdId, StaScanResult result) {
            kilroy();
            Log.e(TAG, "onBackgroundFullScanResult " + cmdId);
        }

        public void onBackgroundScanResults(int cmdId, ArrayList<StaScanData> scanDatas) {
            kilroy();
            Log.e(TAG, "onBackgroundScanResults " + cmdId);
        }

        public void onRssiThresholdBreached(int cmdId, byte[/* 6 */] currBssid, int currRssi) {
            kilroy();
            Log.e(TAG, "onRssiThresholdBreached " + cmdId + "currRssi " + currRssi);
        }
    }

    /**
     * Hal Device Manager callbacks.
     */
    public class HalDeviceManagerStatusListener implements HalDeviceManager.ManagerStatusListener {
        @Override
        public void onStatusChanged() {
            boolean isReady = mHalDeviceManager.isReady();
            boolean isStarted = mHalDeviceManager.isStarted();

            Log.i(TAG, "Device Manager onStatusChanged. isReady(): " + isReady
                    + ", isStarted(): " + isStarted);
            // Reset all our cached handles.
            if (!isReady || !isStarted)  {
                kilroy();
                mIWifiChip = null;
                mIWifiStaIface = null;
                mIWifiApIface = null;
                mIWifiRttController = null;
                mDriverDescription = null;
                mFirmwareDescription = null;
            }
        }
    }
}
