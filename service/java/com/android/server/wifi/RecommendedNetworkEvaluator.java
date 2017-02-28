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

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.INetworkScoreCache;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.LruCache;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ScanResultUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.concurrent.GuardedBy;

/**
 * {@link WifiNetworkSelector.NetworkEvaluator} implementation that uses
 * {@link NetworkScoreManager#requestRecommendation(RecommendationRequest)}.
 */
public class RecommendedNetworkEvaluator implements WifiNetworkSelector.NetworkEvaluator {
    private static final String TAG = "RecNetEvaluator";
    private final NetworkScoreManager mNetworkScoreManager;
    private final WifiConfigManager mWifiConfigManager;
    private final LocalLog mLocalLog;
    @VisibleForTesting final ContentObserver mContentObserver;
    private final RequestedScoreCache mRequestedScoreCache;
    private boolean mNetworkRecommendationsEnabled;

    RecommendedNetworkEvaluator(final Context context, ContentResolver contentResolver,
            Looper looper, final FrameworkFacade frameworkFacade,
            NetworkScoreManager networkScoreManager, WifiConfigManager wifiConfigManager,
            LocalLog localLog) {
        mRequestedScoreCache = new RequestedScoreCache(frameworkFacade.getLongSetting(
                context, Settings.Global.RECOMMENDED_NETWORK_EVALUATOR_CACHE_EXPIRY_MS,
                TimeUnit.DAYS.toMillis(1)));
        mNetworkScoreManager = networkScoreManager;
        mWifiConfigManager = wifiConfigManager;
        mLocalLog = localLog;
        mContentObserver = new ContentObserver(new Handler(looper)) {
            @Override
            public void onChange(boolean selfChange) {
                mNetworkRecommendationsEnabled = frameworkFacade.getIntegerSetting(context,
                        Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED, 0) == 1;
            }
        };
        contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.NETWORK_RECOMMENDATIONS_ENABLED),
                false /* notifyForDescendents */, mContentObserver);
        mContentObserver.onChange(false /* unused */);
        mNetworkScoreManager.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mRequestedScoreCache,
                NetworkScoreManager.CACHE_FILTER_NONE);
        mLocalLog.log("RecommendedNetworkEvaluator constructed. mNetworkRecommendationsEnabled: "
                + mNetworkRecommendationsEnabled);
    }

    @Override
    public void update(List<ScanDetail> scanDetails) {
        if (mNetworkRecommendationsEnabled) {
            updateNetworkScoreCache(scanDetails);
        }
        clearNotRecommendedFlag();
    }

    private void updateNetworkScoreCache(List<ScanDetail> scanDetails) {
        ArrayList<NetworkKey> unscoredNetworks = new ArrayList<NetworkKey>();

        for (int i = 0; i < scanDetails.size(); i++) {
            ScanResult scanResult = scanDetails.get(i).getScanResult();
            try {
                WifiKey wifiKey = new WifiKey(
                        ScanResultUtil.createQuotedSSID(scanResult.SSID), scanResult.BSSID);
                // Have we requested a score for this network? If not, request a score.
                if (mRequestedScoreCache.shouldRequestScore(wifiKey)) {
                    unscoredNetworks.add(new NetworkKey(wifiKey));
                }
            } catch (IllegalArgumentException e) {
                mLocalLog.log("Invalid SSID=" + scanResult.SSID + " BSSID=" + scanResult.BSSID
                        + " for network score. Skip.");
            }
        }

        // Kick the score manager if there are any unscored network.
        if (!unscoredNetworks.isEmpty()) {
            NetworkKey[] unscoredNetworkKeys =
                    unscoredNetworks.toArray(new NetworkKey[unscoredNetworks.size()]);
            mNetworkScoreManager.requestScores(unscoredNetworkKeys);
        }
    }

    private void clearNotRecommendedFlag() {
        List<WifiConfiguration> savedNetworks = mWifiConfigManager.getSavedNetworks();
        for (int i = 0; i < savedNetworks.size(); i++) {
            mWifiConfigManager.updateNetworkNotRecommended(
                    savedNetworks.get(i).networkId, false /* notRecommended*/);
        }
    }

    @Override
    public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
            WifiConfiguration currentNetwork, String currentBssid, boolean connected,
            boolean untrustedNetworkAllowed,
            List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks) {
        if (!mNetworkRecommendationsEnabled) {
            mLocalLog.log("Skipping evaluateNetworks; Network recommendations disabled.");
            return null;
        }
        Set<WifiConfiguration> availableConfiguredNetworks = new ArraySet<>();
        List<ScanResult> scanResults = new ArrayList<>();
        for (int i = 0; i < scanDetails.size(); i++) {
            ScanDetail scanDetail = scanDetails.get(i);
            ScanResult scanResult = scanDetail.getScanResult();
            if (scanResult == null) continue;
            if (mWifiConfigManager.wasEphemeralNetworkDeleted(
                    ScanResultUtil.createQuotedSSID(scanResult.SSID))) {
                continue;
            }

            final WifiConfiguration configuredNetwork =
                    mWifiConfigManager.getSavedNetworkForScanDetailAndCache(scanDetail);

            scanResult.untrusted = configuredNetwork == null || configuredNetwork.ephemeral;

            if (!untrustedNetworkAllowed && scanResult.untrusted) {
                continue;
            }

            if (configuredNetwork != null) {
                if (!configuredNetwork.getNetworkSelectionStatus().isNetworkEnabled()) {
                    continue;
                }
                availableConfiguredNetworks.add(configuredNetwork);
            }
            scanResults.add(scanResult);
            // Track potential connectable networks for the watchdog.
            if (connectableNetworks != null) {
                connectableNetworks.add(Pair.create(scanDetail, configuredNetwork));
            }
        }

        if (scanResults.isEmpty()) {
            return null;
        }

        ScanResult[] scanResultArray = scanResults.toArray(new ScanResult[scanResults.size()]);
        WifiConfiguration[] availableConfigsArray = availableConfiguredNetworks
                .toArray(new WifiConfiguration[availableConfiguredNetworks.size()]);
        int lastSelectedNetworkId = mWifiConfigManager.getLastSelectedNetwork();
        long lastSelectedNetworkTimestamp = mWifiConfigManager.getLastSelectedTimeStamp();
        RecommendationRequest request = new RecommendationRequest.Builder()
                .setScanResults(scanResultArray)
                .setConnectedWifiConfig(currentNetwork)
                .setConnectableConfigs(availableConfigsArray)
                .setLastSelectedNetwork(lastSelectedNetworkId, lastSelectedNetworkTimestamp)
                // TODO: pass in currently recommended network
                .build();
        RecommendationResult result = mNetworkScoreManager.requestRecommendation(request);
        if (result == null) {
            // Recommendation provider could not be reached.
            return null;
        }

        if (result.getWifiConfiguration() == null) {
            // Recommendation provider recommended not connecting to any network.
            for (int i = 0; i < availableConfigsArray.length; i++) {
                if (availableConfigsArray[i].getNetworkSelectionStatus().isNetworkEnabled()) {
                    mWifiConfigManager.updateNetworkNotRecommended(
                            availableConfigsArray[i].networkId, true /* notRecommended*/);
                }
            }
            return null;
        }

        WifiConfiguration recommendedConfig = result.getWifiConfiguration();
        ScanDetail matchingScanDetail = findMatchingScanDetail(scanDetails, recommendedConfig);
        if (matchingScanDetail == null) {
            Slog.e(TAG, "Could not match WifiConfiguration to a ScanDetail.");
            return null;
        }
        ScanResult matchingScanResult = matchingScanDetail.getScanResult();

        // Look for a matching saved config. This can be null for ephemeral networks.
        final WifiConfiguration existingConfig =
                mWifiConfigManager.getSavedNetworkForScanDetailAndCache(matchingScanDetail);

        final int networkId;
        if (existingConfig == null) { // attempt to add a new ephemeral network.
            networkId = addEphemeralNetwork(recommendedConfig, matchingScanResult);
            if (networkId == WifiConfiguration.INVALID_NETWORK_ID) {
                return null;
            }
        } else { // Use the existing config
            networkId = existingConfig.networkId;
        }
        mWifiConfigManager.setNetworkCandidateScanResult(networkId,
                matchingScanResult, 0 /* score */);
        return mWifiConfigManager.getConfiguredNetwork(networkId);
    }

    private static ScanDetail findMatchingScanDetail(List<ScanDetail> scanDetails,
            WifiConfiguration wifiConfiguration) {
        String ssid = WifiInfo.removeDoubleQuotes(wifiConfiguration.SSID);
        String bssid = wifiConfiguration.BSSID;
        boolean ignoreBssid = TextUtils.isEmpty(bssid) || "any".equals(bssid);
        for (int i = 0; i < scanDetails.size(); i++) {
            final ScanDetail scanDetail = scanDetails.get(i);
            if (ssid.equals(scanDetail.getSSID())
                    && (ignoreBssid || bssid.equals(scanDetail.getBSSIDString()))) {
                return scanDetail;
            }
        }

        return null;
    }

    private int addEphemeralNetwork(WifiConfiguration wifiConfiguration, ScanResult scanResult) {
        if (wifiConfiguration.allowedKeyManagement.isEmpty()) {
            ScanResultUtil.setAllowedKeyManagementFromScanResult(scanResult,
                    wifiConfiguration);
        }
        wifiConfiguration.ephemeral = true;
        wifiConfiguration.BSSID = null;
        NetworkUpdateResult networkUpdateResult = mWifiConfigManager
                .addOrUpdateNetwork(wifiConfiguration, Process.WIFI_UID);
        if (networkUpdateResult.isSuccess()) {
            return networkUpdateResult.getNetworkId();
        }
        mLocalLog.log("Failed to add ephemeral network for networkId: "
                + WifiNetworkSelector.toScanId(scanResult));
        return WifiConfiguration.INVALID_NETWORK_ID;
    }

    @Override
    public String getName() {
        return TAG;
    }

    /** Cache for scores that have already been requested. */
    static class RequestedScoreCache extends INetworkScoreCache.Stub {
        /** Number entries to be stored in the {@link LruCache} of requested {@link WifiKey}s. */
        private static final int MAX_CACHE_SIZE = 1000;

        private final long mCacheExpiryMillis;
        @GuardedBy("mCache")
        private final LruCache<WifiKey, Object> mCache = new LruCache<>(MAX_CACHE_SIZE);
        @GuardedBy("mCache")
        private long mCacheCreationTime;

        RequestedScoreCache(long cacheExpiryMillis) {
            mCacheExpiryMillis = cacheExpiryMillis;
        }

        /** Returns whether a score should be requested for a given {@code wifiKey}. */
        public boolean shouldRequestScore(WifiKey wifiKey) {
            long nowMillis = SystemClock.elapsedRealtime();
            long oldestUsableCacheTimeMillis = nowMillis - mCacheExpiryMillis;
            synchronized (mCache) {
                if (mCacheCreationTime < oldestUsableCacheTimeMillis) {
                    mCache.evictAll();
                    mCacheCreationTime = nowMillis;
                }
                boolean shouldRequest = mCache.get(wifiKey) == null;
                mCache.put(wifiKey, this); // Update access time for wifiKey.
                return shouldRequest;
            }
        }

        @Override
        public void updateScores(List<ScoredNetwork> networks) throws RemoteException {}

        @Override
        public void clearScores() throws RemoteException {
            synchronized (mCache) {
                mCache.evictAll();
                mCacheCreationTime = 0;
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
            writer.println("RequestedScoreCache:");
            writer.println("mCacheExpiryMillis: " + mCacheExpiryMillis);
            synchronized (mCache) {
                writer.println("mCacheCreationTime: " + mCacheCreationTime);
                writer.println("mCache size: " + mCache.size());
            }
        }
    }
}
