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

package com.android.server.wifi.hotspot2;

import android.net.wifi.WifiConfiguration;
import android.os.Process;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Pair;

import com.android.server.wifi.NetworkUpdateResult;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiNetworkSelector;
import com.android.server.wifi.util.ScanResultUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is the WifiNetworkSelector.NetworkEvaluator implementation for
 * Passpoint networks.
 */
public class PasspointNetworkEvaluator implements WifiNetworkSelector.NetworkEvaluator {
    private static final String NAME = "PasspointNetworkEvaluator";

    private final PasspointManager mPasspointManager;
    private final WifiConfigManager mWifiConfigManager;
    private final LocalLog mLocalLog;

    public PasspointNetworkEvaluator(PasspointManager passpointManager,
            WifiConfigManager wifiConfigManager, LocalLog localLog) {
        mPasspointManager = passpointManager;
        mWifiConfigManager = wifiConfigManager;
        mLocalLog = localLog;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void update(List<ScanDetail> scanDetails) {}

    @Override
    public WifiConfiguration evaluateNetworks(List<ScanDetail> scanDetails,
                    WifiConfiguration currentNetwork, String currentBssid,
                    boolean connected, boolean untrustedNetworkAllowed,
                    List<Pair<ScanDetail, WifiConfiguration>> connectableNetworks) {
        // Go through each ScanDetail and find the best provider for each ScanDetail.
        List<Pair<ScanDetail, Pair<PasspointProvider, PasspointMatch>>> providerList =
                new ArrayList<>();
        for (ScanDetail scanDetail : scanDetails) {
            // Skip non-Passpoint APs.
            if (!scanDetail.getNetworkDetail().isInterworking()) {
                continue;
            }

            List<Pair<PasspointProvider, PasspointMatch>> matchedProviders =
                    mPasspointManager.matchProvider(scanDetail);

            // Find the best provider for this ScanDetail.
            Pair<PasspointProvider, PasspointMatch> bestProvider =
                    findBestProvider(matchedProviders);
            if (bestProvider != null) {
                providerList.add(Pair.create(scanDetail, bestProvider));
            }
        }

        // Done if no matching provider is found.
        if (providerList.isEmpty()) {
            return null;
        }

        // Find the best Passpoint network among all matches.
        Pair<PasspointProvider, ScanDetail> bestNetwork = findBestNetwork(providerList,
                currentNetwork == null ? null : currentNetwork.SSID);

        // Return the configuration for the current connected network if it is the best network.
        if (currentNetwork != null && TextUtils.equals(currentNetwork.SSID,
                ScanResultUtil.createQuotedSSID(bestNetwork.second.getSSID()))) {
            connectableNetworks.add(Pair.create(bestNetwork.second, currentNetwork));
            return currentNetwork;
        }

        WifiConfiguration config =
                createWifiConfigForProvider(bestNetwork.first, bestNetwork.second);
        connectableNetworks.add(Pair.create(bestNetwork.second, config));
        return config;
    }

    /**
     * Create and return a WifiConfiguration for the given ScanDetail and PasspointProvider.
     * The newly created WifiConfiguration will also be added to WifiConfigManager.
     *
     * @param provider The provider to create WifiConfiguration from
     * @param scanDetail The ScanDetail to create WifiConfiguration from
     * @return {@link WifiConfiguration}
     */
    private WifiConfiguration createWifiConfigForProvider(PasspointProvider provider,
            ScanDetail scanDetail) {
        WifiConfiguration config = provider.getWifiConfig();
        config.SSID = ScanResultUtil.createQuotedSSID(scanDetail.getSSID());

        // Add the newly created WifiConfiguration to WifiConfigManager.
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(config, Process.WIFI_UID);
        if (!result.isSuccess()) {
            localLog("Failed to add passpoint network");
            return null;
        }
        mWifiConfigManager.setNetworkCandidateScanResult(result.getNetworkId(),
                scanDetail.getScanResult(), 0);
        mWifiConfigManager.updateScanDetailForNetwork(result.getNetworkId(), scanDetail);
        return mWifiConfigManager.getConfiguredNetwork(result.getNetworkId());
    }

    /**
     * Given a list of provider associated with a ScanDetail, determine and return the best
     * provider from the list.
     *
     * Currently the only criteria is to prefer home provider over roaming provider.  Additional
     * criteria will be added when Hotspot 2.0 Release 2 support is added.
     *
     * A null will be returned if no match is found (providerList is empty).
     *
     * @param providerList The list of matched providers
     * @return Pair of {@link PasspointProvider} with its matching status
     */
    private Pair<PasspointProvider, PasspointMatch> findBestProvider(
            List<Pair<PasspointProvider, PasspointMatch>> providerList) {
        Pair<PasspointProvider, PasspointMatch> bestMatch = null;
        for (Pair<PasspointProvider, PasspointMatch> providerMatch : providerList) {
            if (providerMatch.second == PasspointMatch.HomeProvider) {
                // Home provider found, done.
                bestMatch = providerMatch;
                break;
            } else if (bestMatch == null) {
                bestMatch = providerMatch;
            }
        }
        return bestMatch;
    }

    /**
     * Given a list of Passpoint networks (with both provider and scan info), find and return
     * the one with highest score.  The score is calculated using
     * {@link PasspointNetworkScore#calculateScore}.
     *
     * @param networkList List of Passpoint networks
     * @param currentNetworkSsid The SSID of the currently connected network, null if not connected
     * @return {@link PasspointProvider} and {@link ScanDetail} associated with the network
     */
    private Pair<PasspointProvider, ScanDetail> findBestNetwork(
            List<Pair<ScanDetail, Pair<PasspointProvider, PasspointMatch>>> networkList,
            String currentNetworkSsid) {
        ScanDetail bestScanDetail = null;
        PasspointProvider bestProvider = null;
        int bestScore = Integer.MIN_VALUE;
        for (Pair<ScanDetail, Pair<PasspointProvider, PasspointMatch>> candidate : networkList) {
            ScanDetail scanDetail = candidate.first;
            PasspointProvider provider = candidate.second.first;
            PasspointMatch match = candidate.second.second;

            boolean isActiveNetwork = TextUtils.equals(currentNetworkSsid,
                    ScanResultUtil.createQuotedSSID(scanDetail.getSSID()));
            int score = PasspointNetworkScore.calculateScore(match == PasspointMatch.HomeProvider,
                    scanDetail, isActiveNetwork);

            if (score > bestScore) {
                bestScanDetail = scanDetail;
                bestProvider = provider;
                bestScore = score;
            }
        }
        return Pair.create(bestProvider, bestScanDetail);
    }

    private void localLog(String log) {
        if (mLocalLog != null) {
            mLocalLog.log(log);
        }
    }
}
