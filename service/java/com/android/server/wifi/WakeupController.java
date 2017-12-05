/*
 * Copyright 2017 The Android Open Source Project
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
import android.database.ContentObserver;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * WakeupController is responsible managing Auto Wifi.
 *
 * <p>It determines if and when to re-enable wifi after it has been turned off by the user.
 */
public class WakeupController {

    private static final String TAG = "WakeupController";

    // TODO(b/69624403) propagate this to Settings
    private static final boolean USE_PLATFORM_WIFI_WAKE = false;

    private final Context mContext;
    private final Handler mHandler;
    private final FrameworkFacade mFrameworkFacade;
    private final ContentObserver mContentObserver;
    private final WakeupLock mWakeupLock;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiInjector mWifiInjector;

    private final WifiScanner.ScanListener mScanListener = new WifiScanner.ScanListener() {
        @Override
        public void onPeriodChanged(int periodInMs) {
            // no-op
        }

        @Override
        public void onResults(WifiScanner.ScanData[] results) {
            // TODO(easchwar) handle scan results
        }

        @Override
        public void onFullResult(ScanResult fullScanResult) {
            // no-op
        }

        @Override
        public void onSuccess() {
            // no-op
        }

        @Override
        public void onFailure(int reason, String description) {
            Log.e(TAG, "ScanListener onFailure: " + reason + ": " + description);
        }
    };

    /** Whether this feature is enabled in Settings. */
    private boolean mWifiWakeupEnabled;

    /** Whether the WakeupController is currently active. */
    private boolean mIsActive = false;

    public WakeupController(
            Context context,
            Looper looper,
            WakeupLock wakeupLock,
            WifiConfigManager wifiConfigManager,
            WifiConfigStore wifiConfigStore,
            WifiInjector wifiInjector,
            FrameworkFacade frameworkFacade) {
        mContext = context;
        mHandler = new Handler(looper);
        mWakeupLock = wakeupLock;
        mWifiConfigManager = wifiConfigManager;
        mFrameworkFacade = frameworkFacade;
        mWifiInjector = wifiInjector;
        mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mWifiWakeupEnabled = mFrameworkFacade.getIntegerSetting(
                                mContext, Settings.Global.WIFI_WAKEUP_ENABLED, 0) == 1;
            }
        };
        mFrameworkFacade.registerContentObserver(mContext, Settings.Global.getUriFor(
                Settings.Global.WIFI_WAKEUP_ENABLED), true, mContentObserver);
        mContentObserver.onChange(false /* selfChange */);

        // registering the store data here has the effect of reading the persisted value of the
        // data sources after system boot finishes
        WakeupConfigStoreData wakeupConfigStoreData =
                new WakeupConfigStoreData(new IsActiveDataSource(), mWakeupLock.getDataSource());
        wifiConfigStore.registerStoreData(wakeupConfigStoreData);
    }

    private void setActive(boolean isActive) {
        if (mIsActive != isActive) {
            mIsActive = isActive;
            mWifiConfigManager.saveToStore(false /* forceWrite */);
        }
    }

    /**
     * Starts listening for incoming scans.
     *
     * <p>Should only be called upon entering ScanMode. WakeupController registers its listener with
     * the WifiScanner. If the WakeupController is already active, then it returns early. Otherwise
     * it performs its initialization steps and sets {@link #mIsActive} to true.
     */
    public void start() {
        mWifiInjector.getWifiScanner().registerScanListener(mScanListener);

        // If already active, we don't want to re-initialize the lock, so return early.
        if (mIsActive) {
            return;
        }
        setActive(true);

        if (mWifiWakeupEnabled) {
            mWakeupLock.initialize(getMostRecentSavedScanResults());
        }
    }

    /**
     * Stops listening for scans.
     *
     * <p>Should only be called upon leaving ScanMode. It deregisters the listener from
     * WifiScanner.
     */
    public void stop() {
        mWifiInjector.getWifiScanner().deregisterScanListener(mScanListener);
    }

    /** Resets the WakeupController, setting {@link #mIsActive} to false. */
    public void reset() {
        setActive(false);
    }

    /** Returns a list of saved networks from the last full scan. */
    private Set<ScanResultMatchInfo> getMostRecentSavedScanResults() {
        Set<ScanResultMatchInfo> goodSavedNetworks = getGoodSavedNetworks();

        List<ScanResult> scanResults = mWifiInjector.getWifiScanner().getSingleScanResults();
        Set<ScanResultMatchInfo> lastSeenNetworks = new HashSet<>(scanResults.size());
        for (ScanResult scanResult : scanResults) {
            lastSeenNetworks.add(ScanResultMatchInfo.fromScanResult(scanResult));
        }

        lastSeenNetworks.retainAll(goodSavedNetworks);
        return lastSeenNetworks;
    }

    /** Returns a filtered list of saved networks from WifiConfigManager. */
    private Set<ScanResultMatchInfo> getGoodSavedNetworks() {
        List<WifiConfiguration> savedNetworks = mWifiConfigManager.getSavedNetworks();

        Set<ScanResultMatchInfo> goodSavedNetworks = new HashSet<>(savedNetworks.size());
        for (WifiConfiguration config : savedNetworks) {
            if (isWideAreaNetwork(config)
                    || config.hasNoInternetAccess()
                    || config.noInternetAccessExpected
                    || !config.getNetworkSelectionStatus().getHasEverConnected()) {
                continue;
            }
            goodSavedNetworks.add(ScanResultMatchInfo.fromWifiConfiguration(config));
        }

        Log.d(TAG, "getGoodSavedNetworks: " + goodSavedNetworks.size());
        return goodSavedNetworks;
    }

    //TODO(b/69271702) implement WAN filtering
    private boolean isWideAreaNetwork(WifiConfiguration wifiConfiguration) {
        return false;
    }

    /**
     * Whether the feature is enabled in settings.
     *
     * <p>Note: This method is only used to determine whether or not to actually enable wifi. All
     * other aspects of the WakeupController lifecycle operate normally irrespective of this.
     */
    @VisibleForTesting
    boolean isEnabled() {
        return mWifiWakeupEnabled;
    }

    /** Dumps wakeup controller state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WakeupController");
        pw.println("mWifiWakeupEnabled: " + mWifiWakeupEnabled);
        pw.println("USE_PLATFORM_WIFI_WAKE: " + USE_PLATFORM_WIFI_WAKE);
        pw.println("mIsActive: " + mIsActive);
        mWakeupLock.dump(fd, pw, args);
    }

    private class IsActiveDataSource implements WakeupConfigStoreData.DataSource<Boolean> {

        @Override
        public Boolean getData() {
            return mIsActive;
        }

        @Override
        public void setData(Boolean data) {
            mIsActive = data;
        }
    }
}
