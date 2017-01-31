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
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiNetworkScoreCache;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.LocalLog;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ScanResultUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link WifiNetworkSelector.NetworkEvaluator} implementation that uses
 * {@link NetworkScoreManager#requestRecommendation(RecommendationRequest)}.
 */
public class RecommendedNetworkEvaluator implements WifiNetworkSelector.NetworkEvaluator {
    private static final String TAG = "RecNetEvaluator";
    private final NetworkScoreManager mNetworkScoreManager;
    private final WifiNetworkScoreCache mNetworkScoreCache;
    private final WifiConfigManager mWifiConfigManager;
    private final LocalLog mLocalLog;
    private final ExternalScoreEvaluator mExternalScoreEvaluator;
    @VisibleForTesting final ContentObserver mContentObserver;
    private boolean mNetworkRecommendationsEnabled;

    RecommendedNetworkEvaluator(final Context context, ContentResolver contentResolver,
            Looper looper, final FrameworkFacade frameworkFacade,
            WifiNetworkScoreCache networkScoreCache,
            NetworkScoreManager networkScoreManager, WifiConfigManager wifiConfigManager,
            LocalLog localLog, ExternalScoreEvaluator externalScoreEvaluator) {
        mNetworkScoreCache = networkScoreCache;
        mNetworkScoreManager = networkScoreManager;
        mWifiConfigManager = wifiConfigManager;
        mLocalLog = localLog;
        mExternalScoreEvaluator = externalScoreEvaluator; // TODO(b/33694202): Remove
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
        mLocalLog.log("RecommendedNetworkEvaluator constructed. mNetworkRecommendationsEnabled: "
                + mNetworkRecommendationsEnabled);
    }

    @Override
    public void update(List<ScanDetail> scanDetails) {
        if (mNetworkRecommendationsEnabled) {
            updateNetworkScoreCache(scanDetails);
        } else {
            mExternalScoreEvaluator.update(scanDetails);
        }
    }

    private void updateNetworkScoreCache(List<ScanDetail> scanDetails) {
        ArrayList<NetworkKey> unscoredNetworks = new ArrayList<NetworkKey>();

        for (int i = 0; i < scanDetails.size(); i++) {
            ScanResult scanResult = scanDetails.get(i).getScanResult();

            // Is there a score for this network? If not, request a score.
            if (!mNetworkScoreCache.isScoredNetwork(scanResult)) {
                try {
                    WifiKey wifiKey = new WifiKey("\"" + scanResult.SSID + "\"", scanResult.BSSID);
                    unscoredNetworks.add(new NetworkKey(wifiKey));
                } catch (IllegalArgumentException e) {
                    mLocalLog.log("Invalid SSID=" + scanResult.SSID + " BSSID=" + scanResult.BSSID
                            + " for network score. Skip.");
                }
            }
        }

        // Kick the score manager if there are any unscored network.
        if (!unscoredNetworks.isEmpty()) {
            NetworkKey[] unscoredNetworkKeys =
                    unscoredNetworks.toArray(new NetworkKey[unscoredNetworks.size()]);
            mNetworkScoreManager.requestScores(unscoredNetworkKeys);
        }
    }

    @Override
    public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
            WifiConfiguration currentNetwork, String currentBssid, boolean connected,
            boolean untrustedNetworkAllowed,
            List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks) {
        if (!mNetworkRecommendationsEnabled) {
            return mExternalScoreEvaluator.evaluateNetworks(scanDetails, currentNetwork,
                    currentBssid, connected, untrustedNetworkAllowed, connectableNetworks);
        }
        List<WifiConfiguration> availableConfiguredNetworks = new ArrayList<>();
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
        if (result == null || result.getWifiConfiguration() == null) {
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

    private ScanDetail findMatchingScanDetail(List<ScanDetail> scanDetails,
            WifiConfiguration wifiConfiguration) {
        String ssid = WifiInfo.removeDoubleQuotes(wifiConfiguration.SSID);
        String bssid = wifiConfiguration.BSSID;
        for (int i = 0; i < scanDetails.size(); i++) {
            final ScanDetail scanDetail = scanDetails.get(i);
            if (ssid.equals(scanDetail.getSSID()) && bssid.equals(scanDetail.getBSSIDString())) {
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
        if (mNetworkRecommendationsEnabled) {
            return TAG;
        }
        return TAG + "-" + mExternalScoreEvaluator.getName();
    }
}
