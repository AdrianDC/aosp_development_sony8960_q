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

import static com.android.server.wifi.WifiController.CMD_WIFI_TOGGLED;

import android.content.Context;
import android.database.ContentObserver;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
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

    private static final boolean USE_PLATFORM_WIFI_WAKE = true;

    private final Context mContext;
    private final Handler mHandler;
    private final FrameworkFacade mFrameworkFacade;
    private final ContentObserver mContentObserver;
    private final WakeupLock mWakeupLock;
    private final WakeupEvaluator mWakeupEvaluator;
    private final WakeupOnboarding mWakeupOnboarding;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiInjector mWifiInjector;
    private final WakeupConfigStoreData mWakeupConfigStoreData;
    private final WifiWakeMetrics mWifiWakeMetrics;

    private final WifiScanner.ScanListener mScanListener = new WifiScanner.ScanListener() {
        @Override
        public void onPeriodChanged(int periodInMs) {
            // no-op
        }

        @Override
        public void onResults(WifiScanner.ScanData[] results) {
            if (results.length == 1 && results[0].isAllChannelsScanned()) {
                handleScanResults(Arrays.asList(results[0].getResults()));
            }
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

    /** The number of scans that have been handled by the controller since last {@link #reset()}. */
    private int mNumScansHandled = 0;

    /** Whether Wifi verbose logging is enabled. */
    private boolean mVerboseLoggingEnabled;

    public WakeupController(
            Context context,
            Looper looper,
            WakeupLock wakeupLock,
            WakeupEvaluator wakeupEvaluator,
            WakeupOnboarding wakeupOnboarding,
            WifiConfigManager wifiConfigManager,
            WifiConfigStore wifiConfigStore,
            WifiWakeMetrics wifiWakeMetrics,
            WifiInjector wifiInjector,
            FrameworkFacade frameworkFacade) {
        mContext = context;
        mHandler = new Handler(looper);
        mWakeupLock = wakeupLock;
        mWakeupEvaluator = wakeupEvaluator;
        mWakeupOnboarding = wakeupOnboarding;
        mWifiConfigManager = wifiConfigManager;
        mWifiWakeMetrics = wifiWakeMetrics;
        mFrameworkFacade = frameworkFacade;
        mWifiInjector = wifiInjector;
        mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mWifiWakeupEnabled = mFrameworkFacade.getIntegerSetting(
                                mContext, Settings.Global.WIFI_WAKEUP_ENABLED, 0) == 1;
                Log.d(TAG, "WifiWake " + (mWifiWakeupEnabled ? "enabled" : "disabled"));
            }
        };
        mFrameworkFacade.registerContentObserver(mContext, Settings.Global.getUriFor(
                Settings.Global.WIFI_WAKEUP_ENABLED), true, mContentObserver);
        mContentObserver.onChange(false /* selfChange */);

        // registering the store data here has the effect of reading the persisted value of the
        // data sources after system boot finishes
        mWakeupConfigStoreData = new WakeupConfigStoreData(
                new IsActiveDataSource(),
                mWakeupOnboarding.getDataSource(),
                mWakeupLock.getDataSource());
        wifiConfigStore.registerStoreData(mWakeupConfigStoreData);
    }

    private void setActive(boolean isActive) {
        if (mIsActive != isActive) {
            Log.d(TAG, "Setting active to " + isActive);
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
        Log.d(TAG, "start()");
        mWifiInjector.getWifiScanner().registerScanListener(mScanListener);

        // If already active, we don't want to re-initialize the lock, so return early.
        if (mIsActive) {
            // TODO record metric for calls to start() when already active
            return;
        }
        setActive(true);

        // ensure feature is enabled and store data has been read before performing work
        if (isEnabled()) {
            mWakeupOnboarding.maybeShowNotification();

            Set<ScanResultMatchInfo> mostRecentSavedScanResults = getMostRecentSavedScanResults();

            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Saved networks in most recent scan:" + mostRecentSavedScanResults);
            }

            mWifiWakeMetrics.recordStartEvent(mostRecentSavedScanResults.size());
            mWakeupLock.initialize(mostRecentSavedScanResults);
        }
    }

    /**
     * Stops listening for scans.
     *
     * <p>Should only be called upon leaving ScanMode. It deregisters the listener from
     * WifiScanner.
     */
    public void stop() {
        Log.d(TAG, "stop()");
        mWifiInjector.getWifiScanner().deregisterScanListener(mScanListener);
        mWakeupOnboarding.onStop();
    }

    /** Resets the WakeupController, setting {@link #mIsActive} to false. */
    public void reset() {
        Log.d(TAG, "reset()");
        mWifiWakeMetrics.recordResetEvent(mNumScansHandled);
        mNumScansHandled = 0;
        setActive(false);
    }

    /** Sets verbose logging flag based on verbose level. */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0;
        mWakeupLock.enableVerboseLogging(mVerboseLoggingEnabled);
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

        return goodSavedNetworks;
    }

    //TODO(b/69271702) implement WAN filtering
    private boolean isWideAreaNetwork(WifiConfiguration wifiConfiguration) {
        return false;
    }

    /**
     * Handles incoming scan results.
     *
     * <p>The controller updates the WakeupLock with the incoming scan results. If WakeupLock is
     * empty, it evaluates scan results for a match with saved networks. If a match exists, it
     * enables wifi.
     *
     * <p>The feature must be enabled and the store data must be loaded in order for the controller
     * to handle scan results.
     *
     * @param scanResults The scan results with which to update the controller
     */
    private void handleScanResults(Collection<ScanResult> scanResults) {
        if (!isEnabled()) {
            Log.d(TAG, "Attempted to handleScanResults while not enabled");
            return;
        }

        // only count scan as handled if isEnabled
        mNumScansHandled++;

        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "Incoming scan. Total scans handled: " + mNumScansHandled);
            Log.d(TAG, "ScanResults: " + scanResults);
        }

        // need to show notification here in case user enables Wifi Wake when Wifi is off
        mWakeupOnboarding.maybeShowNotification();
        if (!mWakeupOnboarding.isOnboarded()) {
            return;
        }

        // only update the wakeup lock if it's not already empty
        if (!mWakeupLock.isEmpty()) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "WakeupLock not empty. Updating.");
            }

            Set<ScanResultMatchInfo> networks = new ArraySet<>();
            for (ScanResult scanResult : scanResults) {
                networks.add(ScanResultMatchInfo.fromScanResult(scanResult));
            }
            mWakeupLock.update(networks);

            // if wakeup lock is still not empty, return
            if (!mWakeupLock.isEmpty()) {
                return;
            }

            Log.d(TAG, "WakeupLock emptied");
            mWifiWakeMetrics.recordUnlockEvent(mNumScansHandled);
        }

        ScanResult network =
                mWakeupEvaluator.findViableNetwork(scanResults, getGoodSavedNetworks());

        if (network != null) {
            Log.d(TAG, "Enabling wifi for network: " + network.SSID);
            enableWifi();
        }
    }

    /**
     * Enables wifi.
     *
     * <p>This method ignores all checks and assumes that {@link WifiStateMachine} is currently
     * in ScanModeState.
     */
    private void enableWifi() {
        if (USE_PLATFORM_WIFI_WAKE) {
            // TODO(b/72180295): ensure that there is no race condition with WifiServiceImpl here
            if (mWifiInjector.getWifiSettingsStore().handleWifiToggled(true /* wifiEnabled */)) {
                mWifiInjector.getWifiController().sendMessage(CMD_WIFI_TOGGLED);
                mWifiWakeMetrics.recordWakeupEvent(mNumScansHandled);
            }
        }
    }

    /**
     * Whether the feature is currently enabled.
     *
     * <p>This method checks both the Settings value and the store data to ensure that it has been
     * read.
     */
    @VisibleForTesting
    boolean isEnabled() {
        return mWifiWakeupEnabled && mWakeupConfigStoreData.hasBeenRead();
    }

    /** Dumps wakeup controller state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WakeupController");
        pw.println("USE_PLATFORM_WIFI_WAKE: " + USE_PLATFORM_WIFI_WAKE);
        pw.println("mWifiWakeupEnabled: " + mWifiWakeupEnabled);
        pw.println("isOnboarded: " + mWakeupOnboarding.isOnboarded());
        pw.println("configStore hasBeenRead: " + mWakeupConfigStoreData.hasBeenRead());
        pw.println("mIsActive: " + mIsActive);
        pw.println("mNumScansHandled: " + mNumScansHandled);

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
