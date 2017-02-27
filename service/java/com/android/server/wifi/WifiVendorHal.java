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

import android.hardware.wifi.V1_0.IWifiApIface;
import android.hardware.wifi.V1_0.IWifiChip;
import android.hardware.wifi.V1_0.IWifiChipEventCallback;
import android.hardware.wifi.V1_0.IWifiIface;
import android.hardware.wifi.V1_0.IWifiRttController;
import android.hardware.wifi.V1_0.IWifiRttControllerEventCallback;
import android.hardware.wifi.V1_0.IWifiStaIface;
import android.hardware.wifi.V1_0.IWifiStaIfaceEventCallback;
import android.hardware.wifi.V1_0.IfaceType;
import android.hardware.wifi.V1_0.RttBw;
import android.hardware.wifi.V1_0.RttConfig;
import android.hardware.wifi.V1_0.RttPeerType;
import android.hardware.wifi.V1_0.RttPreamble;
import android.hardware.wifi.V1_0.RttResponder;
import android.hardware.wifi.V1_0.RttResult;
import android.hardware.wifi.V1_0.RttType;
import android.hardware.wifi.V1_0.StaBackgroundScanBucketEventReportSchemeMask;
import android.hardware.wifi.V1_0.StaBackgroundScanBucketParameters;
import android.hardware.wifi.V1_0.StaBackgroundScanParameters;
import android.hardware.wifi.V1_0.StaRoamingConfig;
import android.hardware.wifi.V1_0.StaRoamingState;
import android.hardware.wifi.V1_0.StaScanData;
import android.hardware.wifi.V1_0.StaScanResult;
import android.hardware.wifi.V1_0.WifiBand;
import android.hardware.wifi.V1_0.WifiChannelWidthInMhz;
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
import android.net.wifi.ScanResult;
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
    private final IWifiStaIfaceEventCallback mIWifiStaIfaceEventCallback;
    private final IWifiChipEventCallback mIWifiChipEventCallback;

    public WifiVendorHal(HalDeviceManager halDeviceManager,
                         HandlerThread wifiStateMachineHandlerThread) {
        mHalDeviceManager = halDeviceManager;
        mWifiStateMachineHandlerThread = wifiStateMachineHandlerThread;
        mHalDeviceManagerStatusCallbacks = new HalDeviceManagerStatusListener();
        mIWifiStaIfaceEventCallback = new StaIfaceEventCallback();
        mIWifiChipEventCallback = new ChipEventCallback();
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
     *
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
     *
     * @return true for success
     */
    public boolean startVendorHalAp() {
        return startVendorHal(AP_MODE);
    }

    /**
     * Bring up the HIDL Vendor HAL and configure for STA (Station) mode
     *
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
        if (!registerChipCallback()) {
            Log.e(TAG, "Failed to register chip callback");
            mHalDeviceManager.stop();
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
     * Registers the sta iface callback.
     */
    private boolean registerChipCallback() {
        synchronized (sLock) {
            if (mIWifiChip == null || mIWifiChipEventCallback == null) return false;
            try {
                kilroy();
                WifiStatus status = mIWifiChip.registerEventCallback(mIWifiChipEventCallback);
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
        synchronized (sLock) {
            if (mIWifiStaIface == null) return false;
            try {
                MutableBoolean ok = new MutableBoolean(false);
                WifiNative.ScanCapabilities out = capabilities;
                mIWifiStaIface.getBackgroundScanCapabilities((status, cap) -> {
                            kilroy();
                            if (status.code != WifiStatusCode.SUCCESS) return;
                            out.max_scan_cache_size = cap.maxCacheSize;
                            out.max_ap_cache_per_scan = cap.maxApCachePerScan;
                            out.max_scan_buckets = cap.maxBuckets;
                            out.max_rssi_sample_size = 0;
                            out.max_scan_reporting_threshold = cap.maxReportingThreshold;
                            out.max_hotlist_bssids = 0;
                            out.max_significant_wifi_change_aps = 0;
                            out.max_bssid_history_entries = 0;
                            out.max_number_epno_networks = 0;
                            out.max_number_epno_networks_by_ssid = 0;
                            out.max_number_of_white_listed_ssid = 0;
                            ok.value = true;
                        }
                );
                return ok.value;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return false;
            }
        }
    }

    /**
     * Holds the current background scan state, to implement pause and restart
     */
    @VisibleForTesting
    class CurrentBackgroundScan {
        public int cmdId;
        public StaBackgroundScanParameters param;
        public WifiNative.ScanEventHandler eventHandler = null;
        public boolean paused = false;

        CurrentBackgroundScan(int id, WifiNative.ScanSettings settings) {
            cmdId = id;
            param = new StaBackgroundScanParameters();
            param.basePeriodInMs = settings.base_period_ms;
            param.maxApPerScan = settings.max_ap_per_scan;
            param.reportThresholdPercent = settings.report_threshold_percent;
            param.reportThresholdNumScans = settings.report_threshold_num_scans;
            for (WifiNative.BucketSettings bs : settings.buckets) {
                param.buckets.add(makeStaBackgroundScanBucketParametersFromBucketSettings(bs));
            }
        }
    }

    /**
     * Makes the Hal flavor of WifiNative.BucketSettings
     * @param bs WifiNative.BucketSettings
     * @return Hal flavor of bs
     * @throws IllegalArgumentException if band value is not recognized
     */
    private StaBackgroundScanBucketParameters
            makeStaBackgroundScanBucketParametersFromBucketSettings(WifiNative.BucketSettings bs) {
        StaBackgroundScanBucketParameters pa = new StaBackgroundScanBucketParameters();
        pa.band = makeWifiBandFromFrameworkBand(bs.band);
        if (bs.channels != null) {
            for (WifiNative.ChannelSettings cs : bs.channels) {
                pa.frequencies.add(cs.frequency);
            }
        }
        pa.periodInMs = bs.period_ms;
        pa.eventReportScheme = makeReportSchemeFromBucketSettingsReportEvents(bs.report_events);
        pa.exponentialMaxPeriodInMs = bs.max_period_ms;
        // Although HAL API allows configurable base value for the truncated
        // exponential back off scan. Native API and above support only
        // truncated binary exponential back off scan.
        // Hard code value of base to 2 here.
        pa.exponentialBase = 2;
        pa.exponentialStepCount = bs.step_count;
        return pa;
    }

    /**
     * Makes the Hal flavor of WifiScanner's band indication
     * @param frameworkBand one of WifiScanner.WIFI_BAND_*
     * @return A WifiBand value
     * @throws IllegalArgumentException if frameworkBand is not recognized
     */
    private int makeWifiBandFromFrameworkBand(int frameworkBand) {
        switch (frameworkBand) {
            case WifiScanner.WIFI_BAND_UNSPECIFIED:
                return WifiBand.BAND_UNSPECIFIED;
            case WifiScanner.WIFI_BAND_24_GHZ:
                return WifiBand.BAND_24GHZ;
            case WifiScanner.WIFI_BAND_5_GHZ:
                return WifiBand.BAND_5GHZ;
            case WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY:
                return WifiBand.BAND_5GHZ_DFS;
            case WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS:
                return WifiBand.BAND_5GHZ_WITH_DFS;
            case WifiScanner.WIFI_BAND_BOTH:
                return WifiBand.BAND_24GHZ_5GHZ;
            case WifiScanner.WIFI_BAND_BOTH_WITH_DFS:
                return WifiBand.BAND_24GHZ_5GHZ_WITH_DFS;
            default:
                throw new IllegalArgumentException("bad band " + frameworkBand);
        }
    }

    /**
     * Makes the Hal flavor of WifiScanner's report event mask
     *
     * @param reportUnderscoreEvents is logical OR of WifiScanner.REPORT_EVENT_* values
     * @return Corresponding StaBackgroundScanBucketEventReportSchemeMask value
     * @throws IllegalArgumentException if a mask bit is not recognized
     */
    private int makeReportSchemeFromBucketSettingsReportEvents(int reportUnderscoreEvents) {
        int ans = 0;
        BitMask in = new BitMask(reportUnderscoreEvents);
        if (in.testAndClear(WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN)) {
            ans |= StaBackgroundScanBucketEventReportSchemeMask.EACH_SCAN;
        }
        if (in.testAndClear(WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT)) {
            ans |= StaBackgroundScanBucketEventReportSchemeMask.FULL_RESULTS;
        }
        if (in.testAndClear(WifiScanner.REPORT_EVENT_NO_BATCH)) {
            ans |= StaBackgroundScanBucketEventReportSchemeMask.NO_BATCH;
        }
        if (in.value != 0) throw new IllegalArgumentException("bad " + reportUnderscoreEvents);
        return ans;
    }

    private int mLastScanCmdId; // For assigning cmdIds to scans

    @VisibleForTesting
    CurrentBackgroundScan mScan = null;

    /**
     * Starts a background scan
     *
     * Any ongoing scan will be stopped first
     *
     * @param settings to control the scan
     * @param eventHandler to call with the results
     * @return true for success
     */
    public boolean startScan(WifiNative.ScanSettings settings,
                             WifiNative.ScanEventHandler eventHandler) {
        WifiStatus status;
        kilroy();
        if (eventHandler == null) return false;
        synchronized (sLock) {
            if (mIWifiStaIface == null) return false;
            try {
                if (mScan != null && !mScan.paused) {
                    status = mIWifiStaIface.stopBackgroundScan(mScan.cmdId);
                    if (status.code != WifiStatusCode.SUCCESS) {
                        kilroy();
                    }
                    mScan = null;
                }
                mLastScanCmdId = (mLastScanCmdId % 9) + 1; // cycle through non-zero single digits
                CurrentBackgroundScan scan = new CurrentBackgroundScan(mLastScanCmdId, settings);
                status = mIWifiStaIface.startBackgroundScan(scan.cmdId, scan.param);
                if (status.code != WifiStatusCode.SUCCESS) return false;
                kilroy();
                scan.eventHandler = eventHandler;
                mScan = scan;
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return false;
            }
        }
    }


    /**
     * Stops any ongoing backgound scan
     */
    public void stopScan() {
        WifiStatus status;
        synchronized (sLock) {
            if (mIWifiStaIface == null) return;
            try {
                if (mScan != null) {
                    mIWifiStaIface.stopBackgroundScan(mScan.cmdId);
                    mScan = null;
                }
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    /**
     * Pauses an ongoing backgound scan
     */
    public void pauseScan() {
        WifiStatus status;
        kilroy();
        synchronized (sLock) {
            try {
                if (mIWifiStaIface == null) return;
                if (mScan != null && !mScan.paused) {
                    status = mIWifiStaIface.stopBackgroundScan(mScan.cmdId);
                    if (status.code != WifiStatusCode.SUCCESS) return;
                    kilroy();
                    mScan.paused = true;
                }
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    /**
     * Restarts a paused scan
     */
    public void restartScan() {
        WifiStatus status;
        kilroy();
        synchronized (sLock) {
            if (mIWifiStaIface == null) return;
            try {
                if (mScan != null && mScan.paused) {
                    status = mIWifiStaIface.startBackgroundScan(mScan.cmdId, mScan.param);
                    if (status.code != WifiStatusCode.SUCCESS) return;
                    kilroy();
                    mScan.paused = false;
                }
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
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
     * RTT (Round Trip Time) measurement capabilities of the device.
     */
    public RttManager.RttCapabilities getRttCapabilities() {
        kilroy();
        class AnswerBox {
            public RttManager.RttCapabilities value = null;
        }
        synchronized (sLock) {
            if (mIWifiRttController == null) return null;
            try {
                AnswerBox box = new AnswerBox();
                mIWifiRttController.getCapabilities((status, capabilities) -> {
                    if (status.code != WifiStatusCode.SUCCESS) return;
                    RttManager.RttCapabilities ans = new RttManager.RttCapabilities();
                    ans.oneSidedRttSupported = capabilities.rttOneSidedSupported;
                    ans.twoSided11McRttSupported = capabilities.rttFtmSupported;
                    ans.lciSupported = capabilities.lciSupported;
                    ans.lcrSupported = capabilities.lcrSupported;
                    ans.preambleSupported = frameworkPreambleFromHalPreamble(
                            capabilities.preambleSupport);
                    ans.bwSupported = frameworkBwFromHalBw(capabilities.bwSupport);
                    ans.responderSupported = capabilities.responderSupported;
                    ans.secureRttSupported = false;
                    ans.mcVersion = ((int) capabilities.mcVersion) & 0xff;
                    kilroy();
                    box.value = ans;
                });
                return box.value;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return null;
            }
        }
    }

    private int mRttCmdIdNext = 1;              // used to generate new command ids
    private int mRttCmdId;                      // id of currently active request
    private RttEventCallback mRttEventCallback; // currently active RTT callback

    /**
     * Receives a callback from the Hal and passes it along to our client using RttEventHandler
     */
    private class RttEventCallback extends IWifiRttControllerEventCallback.Stub {
        WifiNative.RttEventHandler mRttEventHandler;
        int mRttCmdId;

        RttEventCallback(int cmdId, WifiNative.RttEventHandler rttEventHandler) {
            kilroy();
            mRttCmdId = cmdId;
            mRttEventHandler = rttEventHandler;
        }

        @Override
        public void onResults(int cmdId, java.util.ArrayList<RttResult> results) {
            kilroy();
            synchronized (sLock) {
                kilroy();
                if (cmdId != mRttCmdId || mRttEventHandler == null) return;
                RttManager.RttResult[] rtt = new RttManager.RttResult[results.size()];
                for (int i = 0; i < rtt.length; i++) {
                    kilroy();
                    rtt[i] = frameworkRttResultFromHalRttResult(results.get(i));
                }
                mRttEventHandler.onRttResults(rtt);
            }
        }
    }

    /**
     * Converts a Hal RttResult to a RttManager.RttResult
     */
    @VisibleForTesting
    static RttManager.RttResult frameworkRttResultFromHalRttResult(RttResult result) {
        RttManager.RttResult ans = new RttManager.RttResult();
        ans.bssid = NativeUtil.macAddressFromByteArray(result.addr);
        ans.burstNumber = result.burstNum;
        ans.measurementFrameNumber = result.measurementNumber;
        ans.successMeasurementFrameNumber = result.successNumber;
        ans.frameNumberPerBurstPeer = result.numberPerBurstPeer;
        ans.status = result.status; //TODO(b/34901744) - don't assume identity translation
        ans.retryAfterDuration = result.retryAfterDuration;
        ans.measurementType = result.type;
        ans.rssi = result.rssi;
        ans.rssiSpread = result.rssiSpread;
        //TODO(b/35138520) Fix HAL and framework to use the same units
        ans.txRate = result.txRate.bitRateInKbps;
        ans.rxRate = result.rxRate.bitRateInKbps;
        ans.rtt = result.rtt;
        ans.rttStandardDeviation = result.rttSd;
        ans.rttSpread = result.rttSpread;
        //TODO(b/35138520) These divide-by-10s were in the legacy Hal
        ans.distance = result.distanceInMm / 10; // Convert cm to mm
        ans.distanceStandardDeviation = result.distanceSdInMm / 10; // Convert cm to mm
        ans.distanceSpread = result.distanceSpreadInMm / 10;

        ans.ts = result.timeStampInUs;
        ans.burstDuration = result.burstDurationInMs;
        ans.negotiatedBurstNum = result.negotiatedBurstNum;
        ans.LCI = ieFromHal(result.lci);
        ans.LCR = ieFromHal(result.lcr);
        ans.secure = false; // Not present in HIDL HAL
        return ans;
    }

    /**
     * Convert a Hal WifiInformationElement to its RttManager equivalent
     */
    @VisibleForTesting
    static RttManager.WifiInformationElement ieFromHal(
            android.hardware.wifi.V1_0.WifiInformationElement ie) {
        if (ie == null) return null;
        RttManager.WifiInformationElement ans = new RttManager.WifiInformationElement();
        ans.id = ie.id;
        ans.data = NativeUtil.byteArrayFromArrayList(ie.data);
        return ans;
    }

    @VisibleForTesting
    static RttConfig halRttConfigFromFrameworkRttParams(RttManager.RttParams params) {
        RttConfig rttConfig = new RttConfig();
        if (params.bssid != null) {
            byte[] addr = NativeUtil.macAddressToByteArray(params.bssid);
            for (int i = 0; i < rttConfig.addr.length; i++) {
                rttConfig.addr[i] = addr[i];
            }
        }
        rttConfig.type = halRttTypeFromFrameworkRttType(params.requestType);
        rttConfig.peer = halPeerFromFrameworkPeer(params.deviceType);
        rttConfig.channel.width = halChannelWidthFromFrameworkChannelWidth(params.channelWidth);
        rttConfig.channel.centerFreq = params.frequency;
        rttConfig.channel.centerFreq0 = params.centerFreq0;
        rttConfig.channel.centerFreq1 = params.centerFreq1;
        rttConfig.burstPeriod = params.interval; // In 100ms units, 0 means no specific
        rttConfig.numBurst = params.numberBurst;
        rttConfig.numFramesPerBurst = params.numSamplesPerBurst;
        rttConfig.numRetriesPerRttFrame = params.numRetriesPerMeasurementFrame;
        rttConfig.numRetriesPerFtmr = params.numRetriesPerFTMR;
        rttConfig.mustRequestLci = params.LCIRequest;
        rttConfig.mustRequestLcr = params.LCRRequest;
        rttConfig.burstDuration = params.burstTimeout;
        rttConfig.preamble = halPreambleFromFrameworkPreamble(params.preamble);
        rttConfig.bw = halBwFromFrameworkBw(params.bandwidth);
        return rttConfig;
    }

    @VisibleForTesting
    static int halRttTypeFromFrameworkRttType(int frameworkRttType) {
        switch (frameworkRttType) {
            case RttManager.RTT_TYPE_ONE_SIDED:
                return RttType.ONE_SIDED;
            case RttManager.RTT_TYPE_TWO_SIDED:
                return RttType.TWO_SIDED;
            default:
                throw new IllegalArgumentException("bad " + frameworkRttType);
        }
    }

    @VisibleForTesting
    static int frameworkRttTypeFromHalRttType(int halType) {
        switch (halType) {
            case RttType.ONE_SIDED:
                return RttManager.RTT_TYPE_ONE_SIDED;
            case RttType.TWO_SIDED:
                return RttManager.RTT_TYPE_TWO_SIDED;
            default:
                throw new IllegalArgumentException("bad " + halType);
        }
    }

    @VisibleForTesting
    static int halPeerFromFrameworkPeer(int frameworkPeer) {
        switch (frameworkPeer) {
            case RttManager.RTT_PEER_TYPE_AP:
                return RttPeerType.AP;
            case RttManager.RTT_PEER_TYPE_STA:
                return RttPeerType.STA;
            case RttManager.RTT_PEER_P2P_GO:
                return RttPeerType.P2P_GO;
            case RttManager.RTT_PEER_P2P_CLIENT:
                return RttPeerType.P2P_CLIENT;
            case RttManager.RTT_PEER_NAN:
                return RttPeerType.NAN;
            default:
                throw new IllegalArgumentException("bad " + frameworkPeer);
        }
    }

    @VisibleForTesting
    static int frameworkPeerFromHalPeer(int halPeer) {
        switch (halPeer) {
            case RttPeerType.AP:
                return RttManager.RTT_PEER_TYPE_AP;
            case RttPeerType.STA:
                return RttManager.RTT_PEER_TYPE_STA;
            case RttPeerType.P2P_GO:
                return RttManager.RTT_PEER_P2P_GO;
            case RttPeerType.P2P_CLIENT:
                return RttManager.RTT_PEER_P2P_CLIENT;
            case RttPeerType.NAN:
                return RttManager.RTT_PEER_NAN;
            default:
                throw new IllegalArgumentException("bad " + halPeer);

        }
    }

    @VisibleForTesting
    static int halChannelWidthFromFrameworkChannelWidth(int frameworkChannelWidth) {
        switch (frameworkChannelWidth) {
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
                throw new IllegalArgumentException("bad " + frameworkChannelWidth);
        }
    }

    @VisibleForTesting
    static int frameworkChannelWidthFromHalChannelWidth(int halChannelWidth) {
        switch (halChannelWidth) {
            case WifiChannelWidthInMhz.WIDTH_20:
                return ScanResult.CHANNEL_WIDTH_20MHZ;
            case WifiChannelWidthInMhz.WIDTH_40:
                return ScanResult.CHANNEL_WIDTH_40MHZ;
            case WifiChannelWidthInMhz.WIDTH_80:
                return ScanResult.CHANNEL_WIDTH_80MHZ;
            case WifiChannelWidthInMhz.WIDTH_160:
                return ScanResult.CHANNEL_WIDTH_160MHZ;
            case WifiChannelWidthInMhz.WIDTH_80P80:
                return ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ;
            default:
                throw new IllegalArgumentException("bad " + halChannelWidth);
        }
    }

    @VisibleForTesting
    static int halPreambleFromFrameworkPreamble(int rttManagerPreamble) {
        BitMask checkoff = new BitMask(rttManagerPreamble);
        int flags = 0;
        if (checkoff.testAndClear(RttManager.PREAMBLE_LEGACY)) {
            flags |= RttPreamble.LEGACY;
        }
        if (checkoff.testAndClear(RttManager.PREAMBLE_HT)) {
            flags |= RttPreamble.HT;
        }
        if (checkoff.testAndClear(RttManager.PREAMBLE_VHT)) {
            flags |= RttPreamble.VHT;
        }
        if (checkoff.value != 0) {
            throw new IllegalArgumentException("bad " + rttManagerPreamble);
        }
        return flags;
    }

    @VisibleForTesting
    static int frameworkPreambleFromHalPreamble(int halPreamble) {
        BitMask checkoff = new BitMask(halPreamble);
        int flags = 0;
        if (checkoff.testAndClear(RttPreamble.LEGACY)) {
            flags |= RttManager.PREAMBLE_LEGACY;
        }
        if (checkoff.testAndClear(RttPreamble.HT)) {
            flags |= RttManager.PREAMBLE_HT;
        }
        if (checkoff.testAndClear(RttPreamble.VHT)) {
            flags |= RttManager.PREAMBLE_VHT;
        }
        if (checkoff.value != 0) {
            throw new IllegalArgumentException("bad " + halPreamble);
        }
        return flags;
    }

    @VisibleForTesting
    static int halBwFromFrameworkBw(int rttManagerBandwidth) {
        BitMask checkoff = new BitMask(rttManagerBandwidth);
        int flags = 0;
        if (checkoff.testAndClear(RttManager.RTT_BW_5_SUPPORT)) {
            flags |= RttBw.BW_5MHZ;
        }
        if (checkoff.testAndClear(RttManager.RTT_BW_10_SUPPORT)) {
            flags |= RttBw.BW_10MHZ;
        }
        if (checkoff.testAndClear(RttManager.RTT_BW_20_SUPPORT)) {
            flags |= RttBw.BW_20MHZ;
        }
        if (checkoff.testAndClear(RttManager.RTT_BW_40_SUPPORT)) {
            flags |= RttBw.BW_40MHZ;
        }
        if (checkoff.testAndClear(RttManager.RTT_BW_80_SUPPORT)) {
            flags |= RttBw.BW_80MHZ;
        }
        if (checkoff.testAndClear(RttManager.RTT_BW_160_SUPPORT)) {
            flags |= RttBw.BW_160MHZ;
        }
        if (checkoff.value != 0) {
            throw new IllegalArgumentException("bad " + rttManagerBandwidth);
        }
        return flags;
    }

    @VisibleForTesting
    static int frameworkBwFromHalBw(int rttBw) {
        BitMask checkoff = new BitMask(rttBw);
        int flags = 0;
        if (checkoff.testAndClear(RttBw.BW_5MHZ)) {
            flags |= RttManager.RTT_BW_5_SUPPORT;
        }
        if (checkoff.testAndClear(RttBw.BW_10MHZ)) {
            flags |= RttManager.RTT_BW_10_SUPPORT;
        }
        if (checkoff.testAndClear(RttBw.BW_20MHZ)) {
            flags |= RttManager.RTT_BW_20_SUPPORT;
        }
        if (checkoff.testAndClear(RttBw.BW_40MHZ)) {
            flags |= RttManager.RTT_BW_40_SUPPORT;
        }
        if (checkoff.testAndClear(RttBw.BW_80MHZ)) {
            flags |= RttManager.RTT_BW_80_SUPPORT;
        }
        if (checkoff.testAndClear(RttBw.BW_160MHZ)) {
            flags |= RttManager.RTT_BW_160_SUPPORT;
        }
        if (checkoff.value != 0) {
            throw new IllegalArgumentException("bad " + rttBw);
        }
        return flags;
    }

    @VisibleForTesting
    static ArrayList<RttConfig> halRttConfigArrayFromFrameworkRttParamsArray(
            RttManager.RttParams[] params) {
        final int length = params.length;
        ArrayList<RttConfig> config = new ArrayList<RttConfig>(length);
        for (int i = 0; i < length; i++) {
            config.add(halRttConfigFromFrameworkRttParams(params[i]));
        }
        return config;
    }

    /**
     * Starts a new rtt request
     *
     * @param params
     * @param handler
     * @return success indication
     */
    public boolean requestRtt(RttManager.RttParams[] params, WifiNative.RttEventHandler handler) {
        kilroy();
        ArrayList<RttConfig> rttConfigs = halRttConfigArrayFromFrameworkRttParamsArray(params);
        synchronized (sLock) {
            if (mIWifiRttController == null) return false;
            if (mRttCmdId != 0) return false;
            mRttCmdId = mRttCmdIdNext++;
            if (mRttCmdIdNext <= 0) mRttCmdIdNext = 1;
            try {
                mRttEventCallback = new RttEventCallback(mRttCmdId, handler);
                WifiStatus status = mIWifiRttController.rangeRequest(mRttCmdId, rttConfigs);
                if (status.code == WifiStatusCode.SUCCESS) {
                    kilroy();
                    status = mIWifiRttController.registerEventCallback(mRttEventCallback);
                }
                if (status.code == WifiStatusCode.SUCCESS) {
                    kilroy();
                    return true;
                }
                noteHidlError(status, "requestRtt");
                mRttCmdId = 0;
                return false;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return false;
            }
        }
    }

    /**
     * Cancels an outstanding rtt request
     *
     * @param params
     * @return true if there was an outstanding request and it was successfully cancelled
     */
    public boolean cancelRtt(RttManager.RttParams[] params) {
        kilroy();
        ArrayList<RttConfig> rttConfigs = halRttConfigArrayFromFrameworkRttParamsArray(params);
        synchronized (sLock) {
            if (mIWifiRttController == null) return false;
            if (mRttCmdId == 0) return false;
            ArrayList<byte[/* 6 */]> addrs = new ArrayList<byte[]>(rttConfigs.size());
            for (RttConfig x : rttConfigs) addrs.add(x.addr);
            try {
                WifiStatus status = mIWifiRttController.rangeCancel(mRttCmdId, addrs);
                mRttCmdId = 0;
                if (status.code != WifiStatusCode.SUCCESS) return false;
                kilroy();
                return true;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return false;
            }
        }
    }

    private int mRttResponderCmdId = 0;

    /**
     * Get RTT responder information e.g. WiFi channel to enable responder on.
     * @return info Instance of |RttResponder|, or null for error.
     */
    private RttResponder getRttResponder() {
        kilroy();
        class AnswerBox {
            public RttResponder value = null;
        }
        synchronized (sLock) {
            if (mIWifiRttController == null) return null;
            AnswerBox answer = new AnswerBox();
            try {
                mIWifiRttController.getResponderInfo((status, info) -> {
                    if (status.code != WifiStatusCode.SUCCESS) return;
                    kilroy();
                    answer.value = info;
                });
                return answer.value;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return null;
            }
        }
    }

    /**
     * Convert Hal RttResponder to a framework ResponderConfig
     * @param info Instance of |RttResponder|
     * @return framework version of same
     */
    private ResponderConfig frameworkResponderConfigFromHalRttResponder(RttResponder info) {
        ResponderConfig config = new ResponderConfig();
        config.frequency = info.channel.centerFreq;
        config.centerFreq0 = info.channel.centerFreq0;
        config.centerFreq1 = info.channel.centerFreq1;
        config.channelWidth = frameworkChannelWidthFromHalChannelWidth(info.channel.width);
        config.preamble = frameworkPreambleFromHalPreamble(info.preamble);
        return config;
    }

    /**
     * Enables RTT responder role on the device.
     *
     * @return {@link ResponderConfig} if the responder role is successfully enabled,
     * {@code null} otherwise.
     */
    public ResponderConfig enableRttResponder(int timeoutSeconds) {
        kilroy();
        RttResponder info = getRttResponder();
        synchronized (sLock) {
            if (mIWifiRttController == null) return null;
            if (mRttResponderCmdId != 0) {
                Log.e(TAG, "responder mode already enabled - this shouldn't happen");
                return null;
            }
            ResponderConfig config = null;
            int id = mRttCmdIdNext++;
            if (mRttCmdIdNext <= 0) mRttCmdIdNext = 1;
            try {
                WifiStatus status = mIWifiRttController.enableResponder(
                        /* cmdId */id,
                        /* WifiChannelInfo channelHint */null,
                        timeoutSeconds, info);
                if (status.code == WifiStatusCode.SUCCESS) {
                    mRttResponderCmdId = id;
                    config = frameworkResponderConfigFromHalRttResponder(info);
                    Log.d(TAG, "enabling rtt " + mRttResponderCmdId);
                } else {
                    noteHidlError(status, "enableRttResponder");
                }
                return config;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return null;
            }
        }
    }

    /**
     * Disables RTT responder role.
     *
     * @return {@code true} if responder role is successfully disabled,
     * {@code false} otherwise.
     */
    public boolean disableRttResponder() {
        kilroy();
        synchronized (sLock) {
            if (mIWifiRttController == null) return false;
            if (mRttResponderCmdId == 0) return false;
            try {
                WifiStatus status = mIWifiRttController.disableResponder(mRttResponderCmdId);
                mRttResponderCmdId = 0;
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
     * Query the list of valid frequencies for the provided band.
     *
     * The result depends on the on the country code that has been set.
     *
     * @param band as specified by one of the WifiScanner.WIFI_BAND_* constants.
     * @return frequencies vector of valid frequencies (MHz), or null for error.
     * @throws IllegalArgumentException if band is not recognized.
     */
    public int[] getChannelsForBand(int band) {
        kilroy();
        class AnswerBox {
            public int[] value = null;
        }
        synchronized (sLock) {
            try {
                AnswerBox box = new AnswerBox();
                int hb = makeWifiBandFromFrameworkBand(band);
                if (mIWifiStaIface != null) {
                    mIWifiStaIface.getValidFrequenciesForBand(hb, (status, frequencies) -> {
                        if (status.code == WifiStatusCode.ERROR_NOT_SUPPORTED) {
                            kilroy();
                            mChannelsForBandSupport = false;
                        }
                        if (status.code != WifiStatusCode.SUCCESS) return;
                        mChannelsForBandSupport = true;
                        kilroy();
                        box.value = intArrayFromArrayList(frequencies);
                    });
                } else if (mIWifiApIface != null) {
                    mIWifiApIface.getValidFrequenciesForBand(hb, (status, frequencies) -> {
                        if (status.code == WifiStatusCode.ERROR_NOT_SUPPORTED) {
                            kilroy();
                            mChannelsForBandSupport = false;
                        }
                        if (status.code != WifiStatusCode.SUCCESS) return;
                        mChannelsForBandSupport = true;
                        kilroy();
                        box.value = intArrayFromArrayList(frequencies);
                    });
                }
                return box.value;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return null;
            }
        }
    }

    private int[] intArrayFromArrayList(ArrayList<Integer> in) {
        int[] ans = new int[in.size()];
        int i = 0;
        for (Integer e : in) ans[i++] = e;
        return ans;
    }

    /**
     * This holder is null until we know whether or not there is frequency-for-band support.
     *
     * Set as a side-effect of getChannelsForBand.
     */
    @VisibleForTesting
    Boolean mChannelsForBandSupport = null;

    /**
     * Indicates whether getChannelsForBand is supported.
     *
     * @return true if it is.
     */
    public boolean isGetChannelsForBandSupported() {
        if (mChannelsForBandSupport != null) return mChannelsForBandSupport;
        getChannelsForBand(WifiBand.BAND_24GHZ);
        if (mChannelsForBandSupport != null) return mChannelsForBandSupport;
        return false;
    }

    /**
     * Set DFS - actually, this is always on.
     *
     * @param dfsOn
     * @return success indication
     */
    public boolean setDfsFlag(boolean dfsOn) {
        return dfsOn;
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
     * A fixed cmdId for our RssiMonitoring (we only do one at a time)
     */
    @VisibleForTesting
    static final int sRssiMonCmdId = 7551;

    /**
     * Our client's handler
     */
    private WifiNative.WifiRssiEventHandler mWifiRssiEventHandler;

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
        if (maxRssi <= minRssi) return -1;
        if (rssiEventHandler == null) return -1;
        synchronized (sLock) {
            if (mIWifiStaIface == null) return -1;
            try {
                mIWifiStaIface.stopRssiMonitoring(sRssiMonCmdId);
                WifiStatus status;
                status = mIWifiStaIface.startRssiMonitoring(sRssiMonCmdId, maxRssi, minRssi);
                if (status.code != WifiStatusCode.SUCCESS) return -1;
                mWifiRssiEventHandler = rssiEventHandler;
                kilroy();
                return 0;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return -1;
            }
        }
    }

    /**
     * Stop RSSI monitoring
     *
     * @return 0 for success, -1 for failure
     */
    public int stopRssiMonitoring() {
        kilroy();
        synchronized (sLock) {
            mWifiRssiEventHandler = null;
            if (mIWifiStaIface == null) return -1;
            try {
                mIWifiStaIface.stopRssiMonitoring(sRssiMonCmdId);
                WifiStatus status = mIWifiStaIface.stopRssiMonitoring(sRssiMonCmdId);
                if (status.code != WifiStatusCode.SUCCESS) return -1;
                kilroy();
                return 0;
            } catch (RemoteException e) {
                handleRemoteException(e);
                return -1;
            }
        }
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
    private class StaIfaceEventCallback extends IWifiStaIfaceEventCallback.Stub {
        @Override
        public void onBackgroundScanFailure(int cmdId) {
            kilroy();
            Log.d(TAG, "onBackgroundScanFailure " + cmdId);
        }

        @Override
        public void onBackgroundFullScanResult(int cmdId, StaScanResult result) {
            kilroy();
            Log.d(TAG, "onBackgroundFullScanResult " + cmdId);
        }

        @Override
        public void onBackgroundScanResults(int cmdId, ArrayList<StaScanData> scanDatas) {
            kilroy();
            Log.d(TAG, "onBackgroundScanResults " + cmdId);
        }

        @Override
        public void onRssiThresholdBreached(int cmdId, byte[/* 6 */] currBssid, int currRssi) {
            Log.d(TAG, "onRssiThresholdBreached " + cmdId + "currRssi " + currRssi);
            WifiNative.WifiRssiEventHandler handler;
            synchronized (sLock) {
                handler = mWifiRssiEventHandler;
                if (mWifiRssiEventHandler == null) return;
                if (cmdId != sRssiMonCmdId) return;
                kilroy();
            }
            handler.onRssiThresholdBreached((byte) currRssi);
        }
    }

    /**
     * Callback for events on the STA interface.
     */
    private class ChipEventCallback extends IWifiChipEventCallback.Stub {
        @Override
        public void onChipReconfigured(int modeId) {
            kilroy();
            Log.d(TAG, "onChipReconfigured " + modeId);
        }

        @Override
        public void onChipReconfigureFailure(WifiStatus status) {
            kilroy();
            Log.d(TAG, "onChipReconfigureFailure " + status);
        }

        public void onIfaceAdded(int type, String name) {
            kilroy();
            Log.d(TAG, "onIfaceAdded " + type + ", name: " + name);
        }

        @Override
        public void onIfaceRemoved(int type, String name) {
            kilroy();
            Log.d(TAG, "onIfaceRemoved " + type + ", name: " + name);
        }

        @Override
        public void onDebugRingBufferDataAvailable(
                WifiDebugRingBufferStatus status, java.util.ArrayList<Byte> data) {
            kilroy();
            Log.d(TAG, "onDebugRingBufferDataAvailable " + status);
        }

        @Override
        public void onDebugErrorAlert(int errorCode, java.util.ArrayList<Byte> debugData) {
            kilroy();
            Log.d(TAG, "onDebugErrorAlert " + errorCode);
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
            if (!isReady || !isStarted) {
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
