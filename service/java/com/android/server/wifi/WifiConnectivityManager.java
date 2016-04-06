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
 * limitations under the License.
 */

package com.android.server.wifi;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.PnoSettings;
import android.net.wifi.WifiScanner.ScanSettings;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.R;
import com.android.server.wifi.util.ScanDetailUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * This class manages all the connectivity related scanning activities.
 *
 * When the screen is turned on or off, WiFi is connected or disconnected,
 * or on-demand, a scan is initiatiated and the scan results are passed
 * to QNS for it to make a recommendation on which network to connect to.
 */
public class WifiConnectivityManager {
    private static final String TAG = "WifiConnectivityManager";

    // Periodic scan interval in milli-seconds. This is the scan
    // performed when screen is on.
    private static final int PERIODIC_SCAN_INTERVAL_MS = 20000; // 20 seconds
    // PNO scan interval in milli-seconds. This is the scan
    // performed when screen is off.
    private static final int PNO_SCAN_INTERVAL_MS = 160000; // 160 seconds
    // Maximum number of retries when starting a scan failed
    private static final int MAX_SCAN_RESTART_ALLOWED = 5;
    // Number of milli-seconds to delay before retry starting
    // a previously failed scan
    private static final int RESTART_SCAN_DELAY_MS = 2000; // 2 seconds
    // When in disconnected mode, a watchdog timer will be fired
    // every WATCHDOG_INTERVAL_MS to start a single scan. This is
    // to prevent caveat from things like PNO scan.
    private static final int WATCHDOG_INTERVAL_MS = 1200000; // 20 minutes

    // WifiStateMachine has a bunch of states. From the
    // WifiConnectivityManager's perspective it only cares
    // if it is in Connected state, Disconnected state or in
    // transition between these two states.
    public static final int WIFI_STATE_UNKNOWN = 0;
    public static final int WIFI_STATE_CONNECTED = 1;
    public static final int WIFI_STATE_DISCONNECTED = 2;
    public static final int WIFI_STATE_TRANSITIONING = 3;

    private final WifiStateMachine mStateMachine;
    private final WifiScanner mScanner;
    private final WifiConfigManager mConfigManager;
    private final WifiInfo mWifiInfo;
    private final WifiQualifiedNetworkSelector mQualifiedNetworkSelector;
    private final AlarmManager mAlarmManager;
    private final LocalLog mLocalLog = new LocalLog(ActivityManager.isLowRamDeviceStatic()
                                                        ? 1024 : 16384);
    private boolean mDbg = false;
    private boolean mWifiEnabled = false;
    private boolean mForceSelectNetwork = false;
    private boolean mScreenOn = false;
    private int mWifiState = WIFI_STATE_UNKNOWN;
    private boolean mUntrustedConnectionAllowed = false;
    private int mScanRestartCount = 0;
    private int mSingleScanRestartCount = 0;
    // Due to b/28020168, timer based single scan will be scheduled every
    // PERIODIC_SCAN_INTERVAL_MS to provide periodic scan.
    private boolean mNoBackgroundScan = true;

    // PNO settings
    private int mMin5GHzRssi;
    private int mMin24GHzRssi;
    private int mInitialScoreMax;
    private int mCurrentConnectionBonus;
    private int mSameNetworkBonus;
    private int mSecureBonus;
    private int mBand5GHzBonus;

    // A helper to log debugging information in the local log buffer, which can
    // be retrieved in bugreport.
    private void localLog(String log) {
        mLocalLog.log(log);
    }

    // A periodic/PNO scan will be rescheduled up to MAX_SCAN_RESTART_ALLOWED times
    // if the start scan command failed. An timer is used here to make it a deferred retry.
    private final AlarmManager.OnAlarmListener mRestartScanListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    startConnectivityScan(mForceSelectNetwork);
                }
            };

    // A single scan will be rescheduled up to MAX_SCAN_RESTART_ALLOWED times
    // if the start scan command failed. An timer is used here to make it a deferred retry.
    private final AlarmManager.OnAlarmListener mRestartSingleScanListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    startSingleScan();
                }
            };

    // As a watchdog mechanism, a single scan will be scheduled every WATCHDOG_INTERVAL_MS
    // if it is in the WIFI_STATE_DISCONNECTED state.
    private final AlarmManager.OnAlarmListener mWatchdogListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    watchdogHandler();
                }
            };

    // Due to b/28020168, timer based single scan will be scheduled every
    // PERIODIC_SCAN_INTERVAL_MS to provide periodic scan.
    private final AlarmManager.OnAlarmListener mPeriodicScanTimerListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    periodicScanTimerHandler();
                }
            };

    // Periodic scan results listener. A periodic scan is initiated when
    // screen is on.
    private class PeriodicScanListener implements WifiScanner.ScanListener {
        private List<ScanDetail> mScanDetails = new ArrayList<ScanDetail>();

        public void clearScanDetails() {
            mScanDetails.clear();
        }

        @Override
        public void onSuccess() {
            localLog("PeriodicScanListener onSuccess");

            // reset the count
            mScanRestartCount = 0;
        }

        @Override
        public void onFailure(int reason, String description) {
            Log.e(TAG, "PeriodicScanListener onFailure:"
                          + " reason: " + reason
                          + " description: " + description);

            // reschedule the scan
            if (mScanRestartCount++ < MAX_SCAN_RESTART_ALLOWED) {
                scheduleDelayedConnectivityScan();
            } else {
                mScanRestartCount = 0;
                Log.e(TAG, "Failed to successfully start periodic scan for "
                          + MAX_SCAN_RESTART_ALLOWED + " times");
            }
        }

        @Override
        public void onPeriodChanged(int periodInMs) {
            localLog("PeriodicScanListener onPeriodChanged: "
                          + "actual scan period " + periodInMs + "ms");
        }

        @Override
        public void onResults(WifiScanner.ScanData[] results) {
            localLog("PeriodicScanListener onResults: start QNS");

            WifiConfiguration candidate =
                    mQualifiedNetworkSelector.selectQualifiedNetwork(mForceSelectNetwork,
                        mUntrustedConnectionAllowed, mScanDetails,
                        mStateMachine.isLinkDebouncing(), mStateMachine.isConnected(),
                        mStateMachine.isDisconnected(),
                        mStateMachine.isSupplicantTransientState());

            if (candidate != null) {
                localLog("PeriodicScanListener: QNS candidate-" + candidate.SSID);

                connectToNetwork(candidate);
            }

            clearScanDetails();
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
            if (mDbg) {
                localLog("PeriodicScanListener onFullResult: "
                            + fullScanResult.SSID + " capabilities "
                            + fullScanResult.capabilities);
            }

            mScanDetails.add(ScanDetailUtil.toScanDetail(fullScanResult));
        }
    }

    private final PeriodicScanListener mPeriodicScanListener = new PeriodicScanListener();

    // Single scan results listener. A single scan is initiated when
    // Disconnected/ConnectedPNO scan found a valid network and woke up
    // the system, or by the watchdog timer.
    private class SingleScanListener implements WifiScanner.ScanListener {
        private List<ScanDetail> mScanDetails = new ArrayList<ScanDetail>();

        public void clearScanDetails() {
            mScanDetails.clear();
        }

        @Override
        public void onSuccess() {
            localLog("SingleScanListener onSuccess");

            // reset the count
            mSingleScanRestartCount = 0;
        }

        @Override
        public void onFailure(int reason, String description) {
            Log.e(TAG, "SingleScanListener onFailure:"
                          + " reason: " + reason
                          + " description: " + description);

            // reschedule the scan
            if (mSingleScanRestartCount++ < MAX_SCAN_RESTART_ALLOWED) {
                scheduleDelayedSingleScan();
            } else {
                mSingleScanRestartCount = 0;
                Log.e(TAG, "Failed to successfully start single scan for "
                          + MAX_SCAN_RESTART_ALLOWED + " times");
            }
        }

        @Override
        public void onPeriodChanged(int periodInMs) {
            localLog("SingleScanListener onPeriodChanged: "
                          + "actual scan period " + periodInMs + "ms");
        }

        @Override
        public void onResults(WifiScanner.ScanData[] results) {
            localLog("SingleScanListener onResults: start QNS");

            WifiConfiguration candidate =
                    mQualifiedNetworkSelector.selectQualifiedNetwork(mForceSelectNetwork,
                        mUntrustedConnectionAllowed, mScanDetails,
                        mStateMachine.isLinkDebouncing(), mStateMachine.isConnected(),
                        mStateMachine.isDisconnected(),
                        mStateMachine.isSupplicantTransientState());

            if (candidate != null) {
                localLog("SingleScanListener: QNS candidate-" + candidate.SSID);
                connectToNetwork(candidate);
            }
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
            if (mDbg) {
                localLog("SingleScanListener onFullResult: "
                            + fullScanResult.SSID + " capabilities "
                            + fullScanResult.capabilities);
            }

            mScanDetails.add(ScanDetailUtil.toScanDetail(fullScanResult));
        }
    }

    // re-enable this when b/27695292 is fixed
    // private final SingleScanListener mSingleScanListener = new SingleScanListener();

    // PNO scan results listener for both disconected and connected PNO scanning.
    // A PNO scan is initiated when screen is off.
    private class PnoScanListener implements WifiScanner.PnoScanListener {
        private List<ScanDetail> mScanDetails = new ArrayList<ScanDetail>();

        public void clearScanDetails() {
            mScanDetails.clear();
        }

        @Override
        public void onSuccess() {
            localLog("PnoScanListener onSuccess");

            // reset the count
            mScanRestartCount = 0;
        }

        @Override
        public void onFailure(int reason, String description) {
            Log.e(TAG, "PnoScanListener onFailure:"
                          + " reason: " + reason
                          + " description: " + description);

            // reschedule the scan
            if (mScanRestartCount++ < MAX_SCAN_RESTART_ALLOWED) {
                scheduleDelayedConnectivityScan();
            } else {
                mScanRestartCount = 0;
                Log.e(TAG, "Failed to successfully start PNO scan for "
                          + MAX_SCAN_RESTART_ALLOWED + " times");
            }
        }

        @Override
        public void onPeriodChanged(int periodInMs) {
            localLog("PnoScanListener onPeriodChanged: "
                          + "actual scan period " + periodInMs + "ms");
        }

        // Currently the PNO scan results doesn't include IE,
        // which contains information required by QNS. Ignore them
        // for now.
        @Override
        public void onResults(WifiScanner.ScanData[] results) {
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
        }

        @Override
        public void onPnoNetworkFound(ScanResult[] results) {
            localLog("PnoScanListener: onPnoNetworkFound: results len = " + results.length);

            for (ScanResult result: results) {
                mScanDetails.add(ScanDetailUtil.toScanDetail(result));
            }

            localLog("PnoScanListener: onPnoNetworkFound: start QNS");

            WifiConfiguration candidate =
                        mQualifiedNetworkSelector.selectQualifiedNetwork(mForceSelectNetwork,
                        mUntrustedConnectionAllowed, mScanDetails,
                        mStateMachine.isLinkDebouncing(), mStateMachine.isConnected(),
                        mStateMachine.isDisconnected(),
                        mStateMachine.isSupplicantTransientState());

            if (candidate != null) {
                localLog("PnoScanListener: OnPnoNetworkFound: QNS candidate-" + candidate.SSID);
                connectToNetwork(candidate);
            }
        }
    }

    private final PnoScanListener mPnoScanListener = new PnoScanListener();

    /**
     * WifiConnectivityManager constructor
     */
    public WifiConnectivityManager(Context context, WifiStateMachine stateMachine,
                WifiScanner scanner, WifiConfigManager configManager, WifiInfo wifiInfo,
                WifiQualifiedNetworkSelector qualifiedNetworkSelector) {
        mStateMachine = stateMachine;
        mScanner = scanner;
        mConfigManager = configManager;
        mWifiInfo = wifiInfo;
        mQualifiedNetworkSelector =  qualifiedNetworkSelector;
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);

        mMin5GHzRssi = WifiQualifiedNetworkSelector.MINIMUM_5G_ACCEPT_RSSI;
        mMin24GHzRssi = WifiQualifiedNetworkSelector.MINIMUM_2G_ACCEPT_RSSI;
        mBand5GHzBonus = WifiQualifiedNetworkSelector.BAND_AWARD_5GHz;
        mCurrentConnectionBonus = mConfigManager.currentNetworkBoost.get();
        mSameNetworkBonus = context.getResources().getInteger(
                                R.integer.config_wifi_framework_SAME_BSSID_AWARD);
        mSecureBonus = context.getResources().getInteger(
                            R.integer.config_wifi_framework_SECURITY_AWARD);
        mInitialScoreMax = (mConfigManager.thresholdSaturatedRssi24.get()
                            + WifiQualifiedNetworkSelector.RSSI_SCORE_OFFSET)
                            * WifiQualifiedNetworkSelector.RSSI_SCORE_SLOPE;

        Log.i(TAG, "PNO settings:" + " min5GHzRssi " + mMin5GHzRssi
                    + " min24GHzRssi " + mMin24GHzRssi
                    + " currentConnectionBonus " + mCurrentConnectionBonus
                    + " sameNetworkBonus " + mSameNetworkBonus
                    + " secureNetworkBonus " + mSecureBonus
                    + " initialScoreMax " + mInitialScoreMax);

        Log.i(TAG, "ConnectivityScanManager initialized ");
    }

    /**
     * Attempt to connect to a network candidate.
     *
     * Based on the currently connected network, this menthod determines whether we should
     * connect or roam to the network candidate recommended by QNS.
     */
    private void connectToNetwork(WifiConfiguration candidate) {
        ScanResult scanResultCandidate = candidate.getNetworkSelectionStatus().getCandidate();
        if (scanResultCandidate == null) {
            Log.e(TAG, "connectToNetwork: bad candidate - "  + candidate
                    + " scanResult: " + scanResultCandidate);
            return;
        }

        String targetBssid = scanResultCandidate.BSSID;
        String targetAssociationId = candidate.SSID + " : " + targetBssid;
        if (targetBssid != null && targetBssid.equals(mWifiInfo.getBSSID())) {
            localLog("connectToNetwork: Already connected to" + targetAssociationId);
            return;
        }

        WifiConfiguration currentConnectedNetwork = mConfigManager
                .getWifiConfiguration(mWifiInfo.getNetworkId());
        String currentAssociationId = (currentConnectedNetwork == null) ? "Disconnected" :
                (mWifiInfo.getSSID() + " : " + mWifiInfo.getBSSID());

        if (currentConnectedNetwork != null
                && (currentConnectedNetwork.networkId == candidate.networkId
                || currentConnectedNetwork.isLinked(candidate))) {
            localLog("connectToNetwork: Roaming from " + currentAssociationId + " to "
                        + targetAssociationId);
            mStateMachine.autoRoamToNetwork(candidate.networkId, scanResultCandidate);
        } else {
            localLog("connectToNetwork: Reconnect from " + currentAssociationId + " to "
                        + targetAssociationId);
            mStateMachine.autoConnectToNetwork(candidate.networkId, scanResultCandidate.BSSID);
        }
    }

    // Helper for selecting the band for connectivity scan
    private int getScanBand() {
        int freqBand = mStateMachine.getFrequencyBand();
        if (freqBand == WifiManager.WIFI_FREQUENCY_BAND_5GHZ) {
            return WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS;
        } else if (freqBand == WifiManager.WIFI_FREQUENCY_BAND_2GHZ) {
            return WifiScanner.WIFI_BAND_24_GHZ;
        } else {
            return WifiScanner.WIFI_BAND_BOTH_WITH_DFS;
        }
    }

    // Watchdog timer handler
    private void watchdogHandler() {
        localLog("watchdogHandler");

        // Schedule the next timer and start a single scan if we are in disconnected state.
        // Otherwise, the watchdog timer will be scheduled when entering disconnected
        // state.
        if (mWifiState == WIFI_STATE_DISCONNECTED) {
            Log.i(TAG, "start a single scan from watchdogHandler");

            scheduleWatchdogTimer();
            startSingleScan();
        }
    }

    // Periodic scan timer handler
    private void periodicScanTimerHandler() {
        localLog("periodicScanTimerHandler");

        // Schedule the next timer and start a single scan if screen is on.
        if (mScreenOn) {
            schedulePeriodicScanTimer();
            startSingleScan();
        }
    }

    // Start a single scan for watchdog
    private void startSingleScan() {
        if (!mWifiEnabled) {
            return;
        }

        ScanSettings settings = new ScanSettings();
        settings.band = getScanBand();
        settings.reportEvents = WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
                            | WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
        settings.numBssidsPerScan = 0;

        //Retrieve the list of hidden networkId's to scan for.
        Set<Integer> hiddenNetworkIds = mConfigManager.getHiddenConfiguredNetworkIds();
        if (hiddenNetworkIds != null && hiddenNetworkIds.size() > 0) {
            int i = 0;
            settings.hiddenNetworkIds = new int[hiddenNetworkIds.size()];
            for (Integer netId : hiddenNetworkIds) {
                settings.hiddenNetworkIds[i++] = netId;
            }
        }

        // re-enable this when b/27695292 is fixed
        // mSingleScanListener.clearScanDetails();
        // mScanner.startScan(settings, mSingleScanListener);
        SingleScanListener singleScanListener = new SingleScanListener();
        mScanner.startScan(settings, singleScanListener);
    }

    // Start a periodic scan when screen is on
    private void startPeriodicScan() {
        // Due to b/28020168, timer based single scan will be scheduled every
        // PERIODIC_SCAN_INTERVAL_MS to provide periodic scan.
        if (mNoBackgroundScan) {
            startSingleScan();
            schedulePeriodicScanTimer();
        } else {
            ScanSettings settings = new ScanSettings();
            settings.band = getScanBand();
            settings.reportEvents = WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT
                                | WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;
            settings.numBssidsPerScan = 0;
            settings.periodInMs = PERIODIC_SCAN_INTERVAL_MS;

            mPeriodicScanListener.clearScanDetails();
            mScanner.startBackgroundScan(settings, mPeriodicScanListener);
        }
    }

    // Stop a PNO scan
    private void stopPnoScan() {
        // Initialize PNO settings
        PnoSettings pnoSettings = new PnoSettings();
        ArrayList<PnoSettings.PnoNetwork> pnoNetworkList =
                mConfigManager.retrieveDisconnectedPnoNetworkList(false);
        int listSize = pnoNetworkList.size();

        pnoSettings.networkList = new PnoSettings.PnoNetwork[listSize];
        pnoSettings.networkList = pnoNetworkList.toArray(pnoSettings.networkList);

        mScanner.stopPnoScan(pnoSettings, mPnoScanListener);
    }

    // Start a DisconnectedPNO scan when screen is off and Wifi is disconnected
    private void startDisconnectedPnoScan() {
        // Initialize PNO settings
        PnoSettings pnoSettings = new PnoSettings();
        ArrayList<PnoSettings.PnoNetwork> pnoNetworkList =
                mConfigManager.retrieveDisconnectedPnoNetworkList(true);
        int listSize = pnoNetworkList.size();

        if (listSize == 0) {
            // No saved network
            localLog("No saved network for starting disconnected PNO.");
            return;
        }

        pnoSettings.networkList = new PnoSettings.PnoNetwork[listSize];
        pnoSettings.networkList = pnoNetworkList.toArray(pnoSettings.networkList);
        pnoSettings.min5GHzRssi = mMin5GHzRssi;
        pnoSettings.min24GHzRssi = mMin24GHzRssi;
        pnoSettings.initialScoreMax = mInitialScoreMax;
        pnoSettings.currentConnectionBonus = mCurrentConnectionBonus;
        pnoSettings.sameNetworkBonus = mSameNetworkBonus;
        pnoSettings.secureBonus = mSecureBonus;
        pnoSettings.band5GHzBonus = mBand5GHzBonus;

        // Initialize scan settings
        ScanSettings scanSettings = new ScanSettings();
        scanSettings.band = getScanBand();
        scanSettings.reportEvents = WifiScanner.REPORT_EVENT_NO_BATCH;
        scanSettings.numBssidsPerScan = 0;
        scanSettings.periodInMs = PNO_SCAN_INTERVAL_MS;
        // TODO: enable exponential back off scan later to further save energy
        // scanSettings.maxPeriodInMs = 8 * scanSettings.periodInMs;

        mPnoScanListener.clearScanDetails();

        mScanner.startDisconnectedPnoScan(scanSettings, pnoSettings, mPnoScanListener);
    }

    // Start a ConnectedPNO scan when screen is off and Wifi is connected
    private void startConnectedPnoScan() {
        // Disable ConnectedPNO for now due to b/28020168
        if (mNoBackgroundScan) {
            return;
        }

        // Initialize PNO settings
        PnoSettings pnoSettings = new PnoSettings();
        ArrayList<PnoSettings.PnoNetwork> pnoNetworkList =
                mConfigManager.retrieveConnectedPnoNetworkList();
        int listSize = pnoNetworkList.size();

        if (listSize == 0) {
            // No saved network
            localLog("No saved network for starting connected PNO.");
            return;
        }

        pnoSettings.networkList = new PnoSettings.PnoNetwork[listSize];
        pnoSettings.networkList = pnoNetworkList.toArray(pnoSettings.networkList);
        pnoSettings.min5GHzRssi = mMin5GHzRssi;
        pnoSettings.min24GHzRssi = mMin24GHzRssi;
        pnoSettings.initialScoreMax = mInitialScoreMax;
        pnoSettings.currentConnectionBonus = mCurrentConnectionBonus;
        pnoSettings.sameNetworkBonus = mSameNetworkBonus;
        pnoSettings.secureBonus = mSecureBonus;
        pnoSettings.band5GHzBonus = mBand5GHzBonus;

        // Initialize scan settings
        ScanSettings scanSettings = new ScanSettings();
        scanSettings.band = getScanBand();
        scanSettings.reportEvents = WifiScanner.REPORT_EVENT_NO_BATCH;
        scanSettings.numBssidsPerScan = 0;
        scanSettings.periodInMs = PNO_SCAN_INTERVAL_MS;
        // TODO: enable exponential back off scan later to further save energy
        // scanSettings.maxPeriodInMs = 8 * scanSettings.periodInMs;

        mPnoScanListener.clearScanDetails();

        mScanner.startConnectedPnoScan(scanSettings, pnoSettings, mPnoScanListener);
    }

    // Set up watchdog timer
    private void scheduleWatchdogTimer() {
        Log.i(TAG, "scheduleWatchdogTimer");

        mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                            "WifiConnectivityManager Schedule Watchdog Timer",
                            mWatchdogListener, null);
    }

    // Set up periodic scan timer
    private void schedulePeriodicScanTimer() {
        mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + PERIODIC_SCAN_INTERVAL_MS,
                            "WifiConnectivityManager Schedule Periodic Scan Timer",
                            mPeriodicScanTimerListener, null);
    }

    // Set up timer to start a delayed single scan after RESTART_SCAN_DELAY_MS
    private void scheduleDelayedSingleScan() {
        localLog("scheduleDelayedSingleScan");

        mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + RESTART_SCAN_DELAY_MS,
                            "WifiConnectivityManager Restart Single Scan",
                            mRestartSingleScanListener, null);
    }

    // Set up timer to start a delayed scan after RESTART_SCAN_DELAY_MS
    private void scheduleDelayedConnectivityScan() {
        localLog("scheduleDelayedConnectivityScan");

        mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + RESTART_SCAN_DELAY_MS,
                            "WifiConnectivityManager Restart Scan",
                            mRestartScanListener, null);

    }

    // Start a connectivity scan. The scan method is chosen according to
    // the current screen state and WiFi state.
    private void startConnectivityScan(boolean forceSelectNetwork) {
        localLog("startConnectivityScan: screenOn=" + mScreenOn
                        + " wifiState=" + mWifiState
                        + " forceSelectNetwork=" + forceSelectNetwork
                        + " wifiEnabled=" + mWifiEnabled);

        if (!mWifiEnabled) {
            return;
        }

        // Always stop outstanding connecivity scan if there is any
        stopConnectivityScan();

        // Don't start a connectivity scan while Wifi is in the transition
        // between connected and disconnected states.
        if (mWifiState != WIFI_STATE_CONNECTED && mWifiState != WIFI_STATE_DISCONNECTED) {
            return;
        }

        mForceSelectNetwork = forceSelectNetwork;

        if (mScreenOn) {
            startPeriodicScan();
        } else { // screenOff
            if (mWifiState == WIFI_STATE_CONNECTED) {
                startConnectedPnoScan();
            } else {
                startDisconnectedPnoScan();
            }
        }
    }

    // Stop connectivity scan if there is any.
    private void stopConnectivityScan() {
        // Due to b/28020168, timer based single scan will be scheduled every
        // PERIODIC_SCAN_INTERVAL_MS to provide periodic scan.
        if (mNoBackgroundScan) {
            mAlarmManager.cancel(mPeriodicScanTimerListener);
        } else {
            mScanner.stopBackgroundScan(mPeriodicScanListener);
        }
        stopPnoScan();
        mScanRestartCount = 0;
    }

    /**
     * Handler for screen state (on/off) changes
     */
    public void handleScreenStateChanged(boolean screenOn) {
        localLog("handleScreenStateChanged: screenOn=" + screenOn);

        mScreenOn = screenOn;

        startConnectivityScan(false);
    }

    /**
     * Handler for WiFi state (connected/disconnected) changes
     */
    public void handleConnectionStateChanged(int state) {
        localLog("handleConnectionStateChanged: state=" + state);

        mWifiState = state;

        // Kick off the watchdog timer if entering disconnected state
        if (mWifiState == WIFI_STATE_DISCONNECTED) {
            scheduleWatchdogTimer();
        }

        startConnectivityScan(false);
    }

    /**
     * Handler when user toggles whether untrusted connection is allowed
     */
    public void setUntrustedConnectionAllowed(boolean allowed) {
        Log.i(TAG, "setUntrustedConnectionAllowed: allowed=" + allowed);

        if (mUntrustedConnectionAllowed != allowed) {
            mUntrustedConnectionAllowed = allowed;
            startConnectivityScan(false);
        }
    }

    /**
     * Handler when user specifies a particular network to connect to
     */
    public void connectToUserSelectNetwork(int netId, boolean persistent) {
        Log.i(TAG, "connectToUserSelectNetwork: netId=" + netId
                   + " persist=" + persistent);

        mQualifiedNetworkSelector.userSelectNetwork(netId, persistent);

        // Initiate a scan which will trigger the connection to the user selected
        // network when scan result is available.
        startConnectivityScan(true);
    }

    /**
     * Handler for on-demand connectivity scan
     */
    public void forceConnectivityScan() {
        Log.i(TAG, "forceConnectivityScan");

        startConnectivityScan(false);
    }

    /**
     * Track whether a BSSID should be enabled or disabled for QNS
     */
    public boolean trackBssid(String bssid, boolean enable) {
        Log.i(TAG, "trackBssid: " + (enable ? "enable " : "disable ") + bssid);

        boolean ret = mQualifiedNetworkSelector
                            .enableBssidForQualityNetworkSelection(bssid, enable);

        if (ret && !enable) {
            // Disabling a BSSID can happen when the AP candidate to connect to has
            // no capacity for new stations. We start another scan immediately so that QNS
            // can give us another candidate to connect to.
            startConnectivityScan(false);
        }

        return ret;
    }

    /**
     * Set band preference when doing scan and making connection
     */
    public void setUserPreferredBand(int band) {
        Log.i(TAG, "User band preference: " + band);

        mQualifiedNetworkSelector.setUserPreferredBand(band);
        startConnectivityScan(false);
    }

    /**
     * Inform WiFi is enabled for connection or not
     */
    public void setWifiEnabled(boolean enable) {
        Log.i(TAG, "Set WiFi " + (enable ? "enabled" : "disabled"));

        mWifiEnabled = enable;

        if (!mWifiEnabled) {
            stopConnectivityScan();
        }
    }

    /**
     * Enable/disable verbose logging
     */
    public void enableVerboseLogging(int verbose) {
        mDbg = verbose > 0;
    }

    /**
     * Dump the local log buffer
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiConnectivityManager");
        pw.println("WifiConnectivityManager - Log Begin ----");
        mLocalLog.dump(fd, pw, args);
        pw.println("WifiConnectivityManager - Log End ----");
    }
}
