/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.content.Context;
import android.content.Intent;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Binder;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * This class manages all scan requests originating from external apps using the
 * {@link WifiManager#startScan()}.
 *
 * This class is responsible for:
 * a) Forwarding scan requests from {@link WifiManager#startScan()} to
 * {@link WifiScanner#startScan(WifiScanner.ScanSettings, WifiScanner.ScanListener)}.
 * Will essentially proxy scan requests from WifiService to WifiScanningService.
 * b) Cache the results of these scan requests and return them when
 * {@link WifiManager#getScanResults()} is invoked.
 * c) Will send out the {@link WifiManager#SCAN_RESULTS_AVAILABLE_ACTION} broadcast when new
 * scan results are available.
 * Note: This class is not thread-safe. It needs to be invoked from WifiStateMachine thread only.
 * TODO (b/68987915): Port over scan throttling logic from WifiService for all apps.
 * TODO: Port over idle mode handling from WifiService.
 */
@NotThreadSafe
public class ScanRequestProxy {
    private static final String TAG = "ScanRequestProxy";

    private final Context mContext;
    private final WifiInjector mWifiInjector;
    private final WifiConfigManager mWifiConfigManager;
    private WifiScanner mWifiScanner;

    // Verbose logging flag.
    private boolean mVerboseLoggingEnabled = false;
    // Flag to decide if we need to scan for hidden networks or not.
    private boolean mScanningForHiddenNetworksEnabled = false;
    // Scan results cached from the last full single scan request.
    private final List<ScanResult> mLastScanResults = new ArrayList<>();
    // Common scan listener for scan requests.
    private final WifiScanner.ScanListener mScanListener = new WifiScanner.ScanListener() {
        @Override
        public void onSuccess() {
            // Scan request succeeded, wait for results to report to external clients.
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Scan request succeeded");
            }
        }

        @Override
        public void onFailure(int reason, String description) {
            Log.e(TAG, "Scan failure received");
            sendScanResultBroadcast(false);
        }

        @Override
        public void onResults(WifiScanner.ScanData[] scanDatas) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Scan results received");
            }
            // For single scans, the array size should always be 1.
            if (scanDatas.length != 1) {
                Log.e(TAG, "Found more than 1 batch of scan results, Ignoring...");
                sendScanResultBroadcast(false);
            }
            WifiScanner.ScanData scanData = scanDatas[0];
            ScanResult[] scanResults = scanData.getResults();
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Received " + scanResults.length + " scan results");
            }
            // Store the last scan results & send out the scan completion broadcast.
            mLastScanResults.clear();
            mLastScanResults.addAll(Arrays.asList(scanResults));
            sendScanResultBroadcast(true);
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
            // Ignore for single scans.
        }

        @Override
        public void onPeriodChanged(int periodInMs) {
            // Ignore for single scans.
        }
    };

    ScanRequestProxy(Context context, WifiInjector wifiInjector, WifiConfigManager configManager) {
        mContext = context;
        mWifiInjector = wifiInjector;
        mWifiConfigManager = configManager;
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = (verbose > 0);
    }

    /**
     * Enable/disable scanning for hidden networks.
     * @param enable true to enable, false to disable.
     */
    public void enableScanningForHiddenNetworks(boolean enable) {
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "Scanning for hidden networks is " + (enable ? "enabled" : "disabled"));
        }
        mScanningForHiddenNetworksEnabled = enable;
    }

    /**
     * Helper method to populate WifiScanner handle. This is done lazily because
     * WifiScanningService is started after WifiService.
     */
    private boolean retrieveWifiScannerIfNecessary() {
        if (mWifiScanner == null) {
            mWifiScanner = mWifiInjector.getWifiScanner();
        }
        return mWifiScanner != null;
    }

    /**
     * Helper method to send the scan request status broadcast.
     */
    private void sendScanResultBroadcast(boolean scanSucceeded) {
        // clear calling identity to send broadcast
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiManager.EXTRA_RESULTS_UPDATED, scanSucceeded);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            // restore calling identity
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    /**
     * Initiate a wifi scan.
     *
     * @param callingUid The uid initiating the wifi scan. Blame will be given to this uid.
     * @return true if the scan request was placed, false otherwise.
     */
    public boolean startScan(int callingUid) {
        if (!retrieveWifiScannerIfNecessary()) {
            Log.e(TAG, "Failed to retrieve wifiscanner");
            sendScanResultBroadcast(false);
            return false;
        }
        // Create a worksource using the caller's UID.
        WorkSource workSource = new WorkSource(callingUid);

        // Create the scan settings.
        WifiScanner.ScanSettings settings = new WifiScanner.ScanSettings();
        // always do full scans
        settings.band = WifiScanner.WIFI_BAND_BOTH_WITH_DFS;
        settings.reportEvents = WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN
                | WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT;
        if (mScanningForHiddenNetworksEnabled) {
            // retrieve the list of hidden network SSIDs to scan for, if enabled.
            List<WifiScanner.ScanSettings.HiddenNetwork> hiddenNetworkList =
                    mWifiConfigManager.retrieveHiddenNetworkList();
            settings.hiddenNetworks = hiddenNetworkList.toArray(
                    new WifiScanner.ScanSettings.HiddenNetwork[hiddenNetworkList.size()]);
        }
        mWifiScanner.startScan(settings, mScanListener, workSource);
        return true;
    }

    /**
     * Return the results of the most recent access point scan, in the form of
     * a list of {@link ScanResult} objects.
     * @return the list of results
     */
    public List<ScanResult> getScanResults() {
        return mLastScanResults;
    }

    /**
     * Clear the stored scan results.
     */
    public void clearScanResults() {
        mLastScanResults.clear();
    }
}
