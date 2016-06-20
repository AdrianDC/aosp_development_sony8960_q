/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.content.Context;
import android.net.NetworkKey;
import android.net.NetworkScoreManager;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import android.util.Pair;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * This class looks at all the connectivity scan results then
 * selects a network for the phone to connect or roam to.
 */
public class WifiQualifiedNetworkSelector {
    private WifiConfigManager mWifiConfigManager;
    private WifiInfo mWifiInfo;
    private NetworkScoreManager mScoreManager;
    private WifiNetworkScoreCache mNetworkScoreCache;
    private Clock mClock;
    private static final String TAG = "WifiQualifiedNetworkSelector:";
    // Always enable debugging logs for now since QNS is still a new feature.
    private static final boolean FORCE_DEBUG = true;
    private boolean mDbg = FORCE_DEBUG;
    private WifiConfiguration mCurrentConnectedNetwork = null;
    private String mCurrentBssid = null;

    // Buffer of filtered scan results (Scan results considered by network selection) & associated
    // WifiConfiguration (if any).
    private volatile List<Pair<ScanDetail, WifiConfiguration>> mFilteredScanDetails = null;

    // Minimum time gap between last successful Qualified Network Selection and a new selection
    // attempt.
    private static final int MINIMUM_QUALIFIED_NETWORK_SELECTION_INTERVAL_MS = 10 * 1000;

    // A 2.4GHz network with RSSI value above this threshold is considered qualified. No new
    // selection attempt necessary.
    public static final int QUALIFIED_RSSI_24G_BAND = -73;
    // A 5GHz network with RSSI value above this threshold is considered qualified. No new
    // selection attempt necessary.
    public static final int QUALIFIED_RSSI_5G_BAND = -70;
    // A RSSI vaule larger than this threshold is considered saturated and switching to a
    // higher RSSI value network won't benefit the connection much.
    public static final int RSSI_SATURATION_2G_BAND = -60;
    public static final int RSSI_SATURATION_5G_BAND = -57;
    // Any RSSI value below this is considered unacceptable, and the network will be filtered out.
    public static final int MINIMUM_2G_ACCEPT_RSSI = -85;
    public static final int MINIMUM_5G_ACCEPT_RSSI = -82;

    // Constants for BSSID scoring formula.
    public static final int RSSI_SCORE_SLOPE = 4;
    public static final int RSSI_SCORE_OFFSET = 85;
    public static final int BAND_AWARD_5GHz = 40;
    public static final int SAME_NETWORK_AWARD = 16;
    public static final int SAME_BSSID_AWARD = 24;
    public static final int LAST_SELECTION_AWARD = 480;
    public static final int PASSPOINT_SECURITY_AWARD = 40;
    public static final int SECURITY_AWARD = 80;

    // BSSID blacklist parameters.
    public static final int BSSID_BLACKLIST_THRESHOLD = 3;
    public static final int BSSID_BLACKLIST_EXPIRE_TIME_MS = 5 * 60 * 1000;

    private final int mNoIntnetPenalty;
    private static final int INVALID_TIME_STAMP = -1;
    private long mLastQualifiedNetworkSelectionTimeStamp = INVALID_TIME_STAMP;

    private final LocalLog mLocalLog = new LocalLog(512);
    private int mRssiScoreSlope = RSSI_SCORE_SLOPE;
    private int mRssiScoreOffset = RSSI_SCORE_OFFSET;
    private int mSameBssidAward = SAME_BSSID_AWARD;
    private int mLastSelectionAward = LAST_SELECTION_AWARD;
    private int mPasspointSecurityAward = PASSPOINT_SECURITY_AWARD;
    private int mSecurityAward = SECURITY_AWARD;
    private int mUserPreferedBand = WifiManager.WIFI_FREQUENCY_BAND_AUTO;
    private Map<String, BssidBlacklistStatus> mBssidBlacklist =
            new HashMap<String, BssidBlacklistStatus>();

    /**
     * Class that saves the blacklist status of a given BSSID.
     */
    private static class BssidBlacklistStatus {
        // Number of times this BSSID has been requested to be blacklisted.
        // Association rejection triggers such a request.
        int mCounter;
        boolean mIsBlacklisted;
        long mBlacklistedTimeStamp = INVALID_TIME_STAMP;
    }

    private void localLog(String log) {
        if (mDbg) {
            mLocalLog.log(log);
        }
    }

    private void localLoge(String log) {
        mLocalLog.log(log);
    }

    @VisibleForTesting
    void setWifiNetworkScoreCache(WifiNetworkScoreCache cache) {
        mNetworkScoreCache = cache;
    }

    /**
     * @return current target connected network
     */
    public WifiConfiguration getConnetionTargetNetwork() {
        return mCurrentConnectedNetwork;
    }

    /**
     * @return the list of ScanDetails scored as potential candidates by the last run of
     * selectQualifiedNetwork, this will be empty if QNS determined no selection was needed on last
     * run. This includes scan details of sufficient signal strength, and had an associated
     * WifiConfiguration.
     */
    public List<Pair<ScanDetail, WifiConfiguration>> getFilteredScanDetails() {
        return mFilteredScanDetails;
    }

    /**
     * Set the user preferred band.
     *
     * @param band preferred band user selected
     */
    public void setUserPreferredBand(int band) {
        mUserPreferedBand = band;
    }

    WifiQualifiedNetworkSelector(WifiConfigManager configureStore, Context context,
            WifiInfo wifiInfo, Clock clock) {
        mWifiConfigManager = configureStore;
        mWifiInfo = wifiInfo;
        mClock = clock;
        mScoreManager =
                (NetworkScoreManager) context.getSystemService(Context.NETWORK_SCORE_SERVICE);
        if (mScoreManager != null) {
            mNetworkScoreCache = new WifiNetworkScoreCache(context);
            mScoreManager.registerNetworkScoreCache(NetworkKey.TYPE_WIFI, mNetworkScoreCache);
        } else {
            localLoge("Couldn't get NETWORK_SCORE_SERVICE.");
            mNetworkScoreCache = null;
        }

        mRssiScoreSlope = context.getResources().getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_SLOPE);
        mRssiScoreOffset = context.getResources().getInteger(
                R.integer.config_wifi_framework_RSSI_SCORE_OFFSET);
        mSameBssidAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_SAME_BSSID_AWARD);
        mLastSelectionAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_LAST_SELECTION_AWARD);
        mPasspointSecurityAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_PASSPOINT_SECURITY_AWARD);
        mSecurityAward = context.getResources().getInteger(
                R.integer.config_wifi_framework_SECURITY_AWARD);
        mNoIntnetPenalty = (mWifiConfigManager.mThresholdSaturatedRssi24.get() + mRssiScoreOffset)
                * mRssiScoreSlope + mWifiConfigManager.mBandAward5Ghz.get()
                + mWifiConfigManager.mCurrentNetworkBoost.get() + mSameBssidAward + mSecurityAward;
    }

    void enableVerboseLogging(int verbose) {
        mDbg = verbose > 0 || FORCE_DEBUG;
    }

    private String getNetworkString(WifiConfiguration network) {
        if (network == null) {
            return null;
        }

        return (network.SSID + ":" + network.networkId);

    }

    /**
     * Check if the current connected network is already qualified so that network
     * selection from the new scan results is not necessary.
     *
     * @param currentNetwork -- current connected network
     */
    private boolean isCurrentNetworkQualified(WifiConfiguration currentNetwork) {
        if (currentNetwork == null) {
            localLog("No current connected network");
            return false;
        } else {
            localLog("Current connected network: " + currentNetwork.SSID
                    + " , ID: " + currentNetwork.networkId);
        }

        // Ephemeral networks are not qualified.
        if (currentNetwork.ephemeral) {
            localLog("Current network is an ephemeral one");
            return false;
        }

        // Open networks are not qualified.
        if (mWifiConfigManager.isOpenNetwork(currentNetwork)) {
            localLog("Current network is a open one");
            return false;
        }

        // Does the current network band match the user preference?
        //
        // Note, here the check for 2.4GHz band is different from the one for 5GHz band
        // such that 5GHz band is always favored.
        // When the current network is 2.4GHz, it is considered as not qualified as long
        // as the band preference set by user is not 2.4GHz only. This gives QNS an
        // opportunity to recommend a 5GHz network if one is available.
        // When the current network is 5GHz, it's considered as not qualified only if
        // the band preference set by user is 2.4GHz only.
        if ((mWifiInfo.is24GHz() && (mUserPreferedBand != WifiManager.WIFI_FREQUENCY_BAND_2GHZ))
                || (mWifiInfo.is5GHz()
                     && (mUserPreferedBand == WifiManager.WIFI_FREQUENCY_BAND_2GHZ))) {
            localLog("Current network band does not match user preference: "
                    + "current network band=" + (mWifiInfo.is24GHz() ? "2.4GHz" : "5GHz")
                    + ", however user preferred band=" + mUserPreferedBand);
            return false;
        }

        // Is the current network's singnal strength qualified?
        int currentRssi = mWifiInfo.getRssi();
        if ((mWifiInfo.is24GHz()
                        && currentRssi < mWifiConfigManager.mThresholdQualifiedRssi24.get())
                || (mWifiInfo.is5GHz()
                        && currentRssi < mWifiConfigManager.mThresholdQualifiedRssi5.get())) {
            localLog("Current network band=" + (mWifiInfo.is24GHz() ? "2.4GHz" : "5GHz")
                    + ", RSSI[" + currentRssi + "]-acceptable but not qualified");
            return false;
        }

        return true;
    }

    /**
     * Check whether QualifiedNetworkSelection is needed.
     *
     * @param isLinkDebouncing true -- Link layer is under debouncing
     *                         false -- Link layer is not under debouncing
     * @param isConnected true -- device is connected to an AP currently
     *                    false -- device is not connected to an AP currently
     * @param isDisconnected true -- WifiStateMachine is at disconnected state
     *                       false -- WifiStateMachine is not at disconnected state
     * @param isSupplicantTransientState true -- supplicant is in a transient state now
     *                                   false -- supplicant is not in a transient state now
     */
    private boolean needQualifiedNetworkSelection(boolean isLinkDebouncing, boolean isConnected,
            boolean isDisconnected, boolean isSupplicantTransientState) {
        // No Qualified Network Selection during the L2 link debouncing procedure.
        if (isLinkDebouncing) {
            localLog("No QNS during L2 debouncing");
            return false;
        }

        if (isConnected) {
            // Already connected. Looking for a better candidate.

            // Is network switching allowed in connected state?
            if (!mWifiConfigManager.getEnableAutoJoinWhenAssociated()) {
                localLog("Switching networks in connected state is not allowed");
                return false;
            }

            // Do not select again if last selection is within
            // MINIMUM_QUALIFIED_NETWORK_SELECTION_INTERVAL_MS.
            if (mLastQualifiedNetworkSelectionTimeStamp != INVALID_TIME_STAMP) {
                long gap = mClock.getElapsedSinceBootMillis()
                            - mLastQualifiedNetworkSelectionTimeStamp;
                if (gap < MINIMUM_QUALIFIED_NETWORK_SELECTION_INTERVAL_MS) {
                    localLog("Too short from last successful Qualified Network Selection. Gap is:"
                            + gap + " ms!");
                    return false;
                }
            }

            WifiConfiguration currentNetwork =
                    mWifiConfigManager.getWifiConfiguration(mWifiInfo.getNetworkId());
            if (currentNetwork == null) {
                // WifiStateMachine in connected state but WifiInfo is not. It means there is a race
                // condition. Defer QNS until WifiStateMachine enters the disconnected state.
                //
                // TODO(b/28249371): Root cause this race condition.
                return false;
            }

            // Already connected to a qualified network?
            if (!isCurrentNetworkQualified(mCurrentConnectedNetwork)) {
                localLog("Current connected network is not qualified");
                return true;
            } else {
                return false;
            }
        } else if (isDisconnected) {
            mCurrentConnectedNetwork = null;
            mCurrentBssid = null;
            // Defer Qualified Network Selection if wpa_supplicant is in the transient state.
            if (isSupplicantTransientState) {
                return false;
            }
        } else {
            // Do not allow new network selection if WifiStateMachine is in a state
            // other than connected or disconnected.
            localLog("WifiStateMachine is not on connected or disconnected state");
            return false;
        }

        return true;
    }

    int calculateBssidScore(ScanResult scanResult, WifiConfiguration network,
            WifiConfiguration currentNetwork, boolean sameBssid, boolean sameSelect,
            StringBuffer sbuf) {

        int score = 0;
        // Calculate the RSSI score.
        int rssi = scanResult.level <= mWifiConfigManager.mThresholdSaturatedRssi24.get()
                ? scanResult.level : mWifiConfigManager.mThresholdSaturatedRssi24.get();
        score += (rssi + mRssiScoreOffset) * mRssiScoreSlope;
        sbuf.append("RSSI score: ").append(score);

        // 5GHz band bonus.
        if (scanResult.is5GHz()) {
            score += mWifiConfigManager.mBandAward5Ghz.get();
            sbuf.append(" 5GHz bonus: ").append(mWifiConfigManager.mBandAward5Ghz.get());
        }

        // Last user selection award.
        if (sameSelect) {
            long timeDifference = mClock.getElapsedSinceBootMillis()
                    - mWifiConfigManager.getLastSelectedTimeStamp();

            if (timeDifference > 0) {
                int bonus = mLastSelectionAward - (int) (timeDifference / 1000 / 60);
                score += bonus > 0 ? bonus : 0;
                sbuf.append(" User selected it last time ").append(timeDifference / 1000 / 60)
                        .append(" minutes ago, bonus: ").append(bonus);
            }
        }

        // Same network award.
        if (network == currentNetwork || network.isLinked(currentNetwork)) {
            score += mWifiConfigManager.mCurrentNetworkBoost.get();
            sbuf.append(" Same network as the current one, bonus: ")
                    .append(mWifiConfigManager.mCurrentNetworkBoost.get());
        }

        // Same BSSID award.
        if (sameBssid) {
            score += mSameBssidAward;
            sbuf.append(" Same BSSID as the current one, bonus: ").append(mSameBssidAward);
        }

        // Security award.
        if (network.isPasspoint()) {
            score += mPasspointSecurityAward;
            sbuf.append(" Passpoint bonus: ").append(mPasspointSecurityAward);
        } else if (!mWifiConfigManager.isOpenNetwork(network)) {
            score += mSecurityAward;
            sbuf.append(" Secure network bonus: ").append(mSecurityAward);
        }

        // No internet penalty.
        if (network.numNoInternetAccessReports > 0 && !network.validatedInternetAccess) {
            score -= mNoIntnetPenalty;
            sbuf.append(" No internet penalty: -").append(mNoIntnetPenalty);
        }

        sbuf.append("    -- ScanResult: ").append(scanResult).append(" for network: ")
                .append(network.networkId).append(" score: ").append(score).append(" --\n");

        return score;
    }

    /**
     * Update all the saved networks' selection status
     */
    private void updateSavedNetworkSelectionStatus() {
        List<WifiConfiguration> savedNetworks = mWifiConfigManager.getSavedNetworks();
        if (savedNetworks.size() == 0) {
            localLog("no saved network");
            return;
        }

        StringBuffer sbuf = new StringBuffer("Saved Network List: \n");
        for (WifiConfiguration network : savedNetworks) {
            WifiConfiguration config = mWifiConfigManager.getWifiConfiguration(network.networkId);
            WifiConfiguration.NetworkSelectionStatus status =
                    config.getNetworkSelectionStatus();

            // If a configuration is temporarily disabled, re-enable it before trying
            // to connect to it.
            if (status.isNetworkTemporaryDisabled()) {
                mWifiConfigManager.tryEnableQualifiedNetwork(network.networkId);
            }

            // Clear the cached candidate, score and seen.
            status.setCandidate(null);
            status.setCandidateScore(Integer.MIN_VALUE);
            status.setSeenInLastQualifiedNetworkSelection(false);

            sbuf.append(" ").append(getNetworkString(network)).append(" ")
                    .append(" User Preferred BSSID: ").append(network.BSSID)
                    .append(" FQDN: ").append(network.FQDN).append(" ")
                    .append(status.getNetworkStatusString()).append(" Disable account: ");
            for (int index = WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE;
                    index < WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_DISABLED_MAX;
                    index++) {
                sbuf.append(status.getDisableReasonCounter(index)).append(" ");
            }
            sbuf.append("Connect Choice: ").append(status.getConnectChoice())
                .append(" set time: ").append(status.getConnectChoiceTimestamp())
                .append("\n");
        }
        localLog(sbuf.toString());
    }

    /**
     * This API is called when user explicitly selects a network. Currently, it is used in following
     * cases:
     * (1) User explicitly chooses to connect to a saved network.
     * (2) User saves a network after adding a new network.
     * (3) User saves a network after modifying a saved network.
     * Following actions will be triggered:
     * 1. If this network is disabled, we need re-enable it again.
     * 2. This network is favored over all the other networks visible in latest network
     *    selection procedure.
     *
     * @param netId  ID for the network chosen by the user
     * @param persist  whether user has the authority to overwrite current connect choice
     * @return true -- There is change made to connection choice of any saved network.
     *         false -- There is no change made to connection choice of any saved network.
     */
    public boolean userSelectNetwork(int netId, boolean persist) {
        localLog("userSelectNetwork: network ID=" + netId + " persist=" + persist);

        WifiConfiguration selected = mWifiConfigManager.getWifiConfiguration(netId);
        if (selected == null || selected.SSID == null) {
            localLoge("userSelectNetwork: Invalid configuration with nid=" + netId);
            return false;
        }

        // Enable the network if it is disabled.
        if (!selected.getNetworkSelectionStatus().isNetworkEnabled()) {
            mWifiConfigManager.updateNetworkSelectionStatus(netId,
                    WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLE);
        }

        if (!persist) {
            localLog("User has no privilege to overwrite the current priority");
            return false;
        }

        boolean change = false;
        String key = selected.configKey();
        // This is only used for setting the connect choice timestamp for debugging purposes.
        long currentTime = mClock.getWallClockMillis();
        List<WifiConfiguration> savedNetworks = mWifiConfigManager.getSavedNetworks();

        for (WifiConfiguration network : savedNetworks) {
            WifiConfiguration config = mWifiConfigManager.getWifiConfiguration(network.networkId);
            WifiConfiguration.NetworkSelectionStatus status = config.getNetworkSelectionStatus();
            if (config.networkId == selected.networkId) {
                if (status.getConnectChoice() != null) {
                    localLog("Remove user selection preference of " + status.getConnectChoice()
                            + " Set Time: " + status.getConnectChoiceTimestamp() + " from "
                            + config.SSID + " : " + config.networkId);
                    status.setConnectChoice(null);
                    status.setConnectChoiceTimestamp(WifiConfiguration.NetworkSelectionStatus
                            .INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP);
                    change = true;
                }
                continue;
            }

            if (status.getSeenInLastQualifiedNetworkSelection()
                    && (status.getConnectChoice() == null
                    || !status.getConnectChoice().equals(key))) {
                localLog("Add key: " + key + " Set Time: " + currentTime + " to "
                        + getNetworkString(config));
                status.setConnectChoice(key);
                status.setConnectChoiceTimestamp(currentTime);
                change = true;
            }
        }

        // Persist changes.
        if (change) {
            mWifiConfigManager.writeKnownNetworkHistory();
            return true;
        }

        return false;
    }

    /**
     * Enable/disable a BSSID for Quality Network Selection
     * When an association rejection event is obtained, Quality Network Selector will disable this
     * BSSID but supplicant still can try to connect to this bssid. If supplicant connect to it
     * successfully later, this bssid can be re-enabled.
     *
     * @param bssid the bssid to be enabled / disabled
     * @param enable -- true enable a bssid if it has been disabled
     *               -- false disable a bssid
     */
    public boolean enableBssidForQualityNetworkSelection(String bssid, boolean enable) {
        if (enable) {
            return (mBssidBlacklist.remove(bssid) != null);
        } else {
            if (bssid != null) {
                BssidBlacklistStatus status = mBssidBlacklist.get(bssid);
                if (status == null) {
                    // First time for this BSSID
                    BssidBlacklistStatus newStatus = new BssidBlacklistStatus();
                    newStatus.mCounter++;
                    mBssidBlacklist.put(bssid, newStatus);
                } else if (!status.mIsBlacklisted) {
                    status.mCounter++;
                    if (status.mCounter >= BSSID_BLACKLIST_THRESHOLD) {
                        status.mIsBlacklisted = true;
                        status.mBlacklistedTimeStamp = mClock.getElapsedSinceBootMillis();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Update the buffered BSSID blacklist
     *
     * Go through the whole buffered BSSIDs blacklist and check when the BSSIDs is blocked. If they
     * have been blacklisted for BSSID_BLACKLIST_EXPIRE_TIME_MS, re-enable them.
     */
    private void updateBssidBlacklist() {
        Iterator<BssidBlacklistStatus> iter = mBssidBlacklist.values().iterator();
        while (iter.hasNext()) {
            BssidBlacklistStatus status = iter.next();
            if (status != null && status.mIsBlacklisted) {
                if (mClock.getElapsedSinceBootMillis() - status.mBlacklistedTimeStamp
                            >= BSSID_BLACKLIST_EXPIRE_TIME_MS) {
                    iter.remove();
                }
            }
        }
    }

    /**
     * Check whether a bssid is disabled
     * @param bssid -- the bssid to check
     */
    public boolean isBssidDisabled(String bssid) {
        BssidBlacklistStatus status = mBssidBlacklist.get(bssid);
        return status == null ? false : status.mIsBlacklisted;
    }

    /**
     * Select the best network candidate from the new scan results for WifiConnectivityManager
     * to connect/roam to.
     *
     * @param forceSelectNetwork true -- start a qualified network selection anyway, no matter
     *                                   the current network is already qualified or not.
     *                           false -- if current network is already qualified, stay connected
     *                                    to it.
     * @param isUntrustedConnectionsAllowed connection to untrusted networks is allowed or not
     * @param isLinkDebouncing Link layer is under debouncing or not
     * @param isConnected WifiStateMachine is in the Connected state or not
     * @param isDisconnected WifiStateMachine is in the Disconnected state or not
     * @param isSupplicantTransient wpa_supplicant is in a transient state or not
     * @param scanDetails new connectivity scan results
     * @return Best network candidate identified. Null if no candidate available or we should
     *         stay connected to the current network.
     */
    public WifiConfiguration selectQualifiedNetwork(boolean forceSelectNetwork,
            boolean isUntrustedConnectionsAllowed, boolean isLinkDebouncing,
            boolean isConnected, boolean isDisconnected, boolean isSupplicantTransient,
            List<ScanDetail>  scanDetails) {
        localLog("==========start qualified Network Selection==========");

        List<Pair<ScanDetail, WifiConfiguration>> filteredScanDetails = new ArrayList<>();

        if (scanDetails.size() == 0) {
            localLog("Empty connectivity scan result");
            mFilteredScanDetails = filteredScanDetails;
            return null;
        }

        if (mCurrentConnectedNetwork == null) {
            mCurrentConnectedNetwork =
                    mWifiConfigManager.getWifiConfiguration(mWifiInfo.getNetworkId());
        }

        if (mCurrentBssid == null) {
            mCurrentBssid = mWifiInfo.getBSSID();
        }

        if (!forceSelectNetwork && !needQualifiedNetworkSelection(isLinkDebouncing, isConnected,
                isDisconnected, isSupplicantTransient)) {
            localLog("Stay connected to the current qualified network");
            mFilteredScanDetails = filteredScanDetails;
            return null;
        }

        int currentHighestScore = Integer.MIN_VALUE;
        ScanResult scanResultCandidate = null;
        WifiConfiguration networkCandidate = null;
        final ExternalScoreEvaluator externalScoreEvaluator =
                new ExternalScoreEvaluator(mLocalLog, mDbg);
        String lastUserSelectedNetWorkKey = mWifiConfigManager.getLastSelectedConfiguration();
        WifiConfiguration lastUserSelectedNetwork =
                mWifiConfigManager.getWifiConfiguration(lastUserSelectedNetWorkKey);
        if (lastUserSelectedNetwork != null) {
            localLog("Last selection is " + lastUserSelectedNetwork.SSID + " Time to now: "
                    + ((mClock.getElapsedSinceBootMillis()
                    - mWifiConfigManager.getLastSelectedTimeStamp()) / 1000 / 60 + " minutes"));
        }

        updateSavedNetworkSelectionStatus();
        updateBssidBlacklist();

        StringBuffer lowSignalScan = new StringBuffer();
        StringBuffer notSavedScan = new StringBuffer();
        StringBuffer noValidSsid = new StringBuffer();
        StringBuffer unwantedBand = new StringBuffer();
        StringBuffer scoreHistory =  new StringBuffer();
        ArrayList<NetworkKey> unscoredNetworks = new ArrayList<NetworkKey>();

        // Iterate over all scan results to find the best candidate.
        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();
            // Skip bad scan result.
            if (scanResult.SSID == null || TextUtils.isEmpty(scanResult.SSID)) {
                if (mDbg) {
                    noValidSsid.append(scanResult.BSSID).append(" / ");
                }
                continue;
            }

            final String scanId = toScanId(scanResult);
            // Skip blacklisted BSSID.
            if (mWifiConfigManager.isBssidBlacklisted(scanResult.BSSID)
                    || isBssidDisabled(scanResult.BSSID)) {
                Log.i(TAG, scanId + " is in the blacklist.");
                continue;
            }

            // Skip network with too weak signals.
            if ((scanResult.is24GHz() && scanResult.level
                    < mWifiConfigManager.mThresholdMinimumRssi24.get())
                    || (scanResult.is5GHz() && scanResult.level
                    < mWifiConfigManager.mThresholdMinimumRssi5.get())) {
                if (mDbg) {
                    lowSignalScan.append(scanId).append("(")
                        .append(scanResult.is24GHz() ? "2.4GHz" : "5GHz")
                        .append(")").append(scanResult.level).append(" / ");
                }
                continue;
            }

            // Skip network not matching band preference set by user.
            // WifiConnectivityManager schedules scan according to the user band prefrence. This is
            // a check for the ScanResults generated from the old settings.
            if ((scanResult.is24GHz()
                    && (mUserPreferedBand == WifiManager.WIFI_FREQUENCY_BAND_5GHZ))
                    || (scanResult.is5GHz()
                        && (mUserPreferedBand == WifiManager.WIFI_FREQUENCY_BAND_2GHZ))) {
                if (mDbg) {
                    unwantedBand.append(scanId).append("(")
                        .append(scanResult.is24GHz() ? "2.4GHz" : "5GHz")
                        .append(")").append(" / ");
                }
                continue;
            }

            // Is there a score for this network? If not, request a score.
            if (mNetworkScoreCache != null && !mNetworkScoreCache.isScoredNetwork(scanResult)) {
                WifiKey wifiKey;

                try {
                    wifiKey = new WifiKey("\"" + scanResult.SSID + "\"", scanResult.BSSID);
                    NetworkKey ntwkKey = new NetworkKey(wifiKey);
                    // Add to the unscoredNetworks list so we can request score later
                    unscoredNetworks.add(ntwkKey);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid SSID=" + scanResult.SSID + " BSSID=" + scanResult.BSSID
                            + " for network score. Skip.");
                }
            }

            // Is this scan result from an ephemeral network?
            boolean potentiallyEphemeral = false;
            // Stores WifiConfiguration of potential connection candidates for scan result filtering
            WifiConfiguration potentialEphemeralCandidate = null;
            List<WifiConfiguration> associatedWifiConfigurations =
                    mWifiConfigManager.updateSavedNetworkWithNewScanDetail(scanDetail,
                            isSupplicantTransient || isConnected || isLinkDebouncing);
            if (associatedWifiConfigurations == null) {
                potentiallyEphemeral =  true;
                if (mDbg) {
                    notSavedScan.append(scanId).append(" / ");
                }
            } else if (associatedWifiConfigurations.size() == 1) {
                // If there is more than one associated network, it must be a passpoint network.
                WifiConfiguration network = associatedWifiConfigurations.get(0);
                if (network.ephemeral) {
                    potentialEphemeralCandidate = network;
                    potentiallyEphemeral =  true;
                }
            }

            // Evaluate the potentially ephemeral network as a possible candidate if untrusted
            // connections are allowed and we have an external score for the scan result.
            if (potentiallyEphemeral) {
                if (isUntrustedConnectionsAllowed) {
                    Integer netScore = getNetworkScore(scanResult, false);
                    if (netScore != null
                            && !mWifiConfigManager.wasEphemeralNetworkDeleted(scanResult.SSID)) {
                        externalScoreEvaluator.evalUntrustedCandidate(netScore, scanResult);
                        // scanDetail is for available ephemeral network
                        filteredScanDetails.add(Pair.create(scanDetail,
                                potentialEphemeralCandidate));
                    }
                }
                continue;
            }

            // Calculate the score of each ScanResult whose associated network is not ephemeral.
            // One ScanResult can associated with more than one network, hence we calculate all
            // the scores and use the highest one as the ScanResult's score
            int highestScore = Integer.MIN_VALUE;
            int score;
            WifiConfiguration configurationCandidateForThisScan = null;
            WifiConfiguration potentialCandidate = null;
            for (WifiConfiguration network : associatedWifiConfigurations) {
                WifiConfiguration.NetworkSelectionStatus status =
                        network.getNetworkSelectionStatus();
                status.setSeenInLastQualifiedNetworkSelection(true);
                if (potentialCandidate == null) {
                    potentialCandidate = network;
                }
                if (!status.isNetworkEnabled()) {
                    continue;
                } else if (network.BSSID != null && !network.BSSID.equals("any")
                        && !network.BSSID.equals(scanResult.BSSID)) {
                    // App has specified the only BSSID to connect for this
                    // configuration. So only the matching ScanResult can be a candidate.
                    localLog("Network " + getNetworkString(network) + " has specified BSSID "
                            + network.BSSID + ". Skip " + scanResult.BSSID);
                    continue;
                }

                // If the network is marked to use external scores then attempt to fetch the score.
                // These networks will not be considered alongside the other saved networks.
                if (network.useExternalScores) {
                    Integer netScore = getNetworkScore(scanResult, false);
                    externalScoreEvaluator.evalSavedCandidate(netScore, network, scanResult);
                    continue;
                }

                score = calculateBssidScore(scanResult, network, mCurrentConnectedNetwork,
                        (mCurrentBssid == null ? false : mCurrentBssid.equals(scanResult.BSSID)),
                        (lastUserSelectedNetwork == null ? false : lastUserSelectedNetwork.networkId
                         == network.networkId), scoreHistory);
                if (score > highestScore) {
                    highestScore = score;
                    configurationCandidateForThisScan = network;
                    potentialCandidate = network;
                }
                // Update the cached candidate.
                if (score > status.getCandidateScore()) {
                    status.setCandidate(scanResult);
                    status.setCandidateScore(score);
                }
            }
            // Create potential filteredScanDetail entry.
            filteredScanDetails.add(Pair.create(scanDetail, potentialCandidate));

            if (highestScore > currentHighestScore || (highestScore == currentHighestScore
                    && scanResultCandidate != null
                    && scanResult.level > scanResultCandidate.level)) {
                currentHighestScore = highestScore;
                scanResultCandidate = scanResult;
                networkCandidate = configurationCandidateForThisScan;
            }
        }

        mFilteredScanDetails = filteredScanDetails;

        // Kick the score manager if there is any unscored network.
        if (mScoreManager != null && unscoredNetworks.size() != 0) {
            NetworkKey[] unscoredNetworkKeys =
                    unscoredNetworks.toArray(new NetworkKey[unscoredNetworks.size()]);
            mScoreManager.requestScores(unscoredNetworkKeys);
        }

        if (mDbg) {
            if (lowSignalScan.length() != 0) {
                localLog(lowSignalScan + " skipped due to low signal");
            }
            if (notSavedScan.length() != 0) {
                localLog(notSavedScan + " skipped due to not saved");
            }
            if (noValidSsid.length() != 0) {
                localLog(noValidSsid + " skipped due to invalid SSID");
            }
            if (unwantedBand.length() != 0) {
                localLog(unwantedBand + " skipped due to user band preference");
            }
            localLog(scoreHistory.toString());
        }

        // Traverse the whole user preference to choose the one user likes the most.
        if (scanResultCandidate != null) {
            WifiConfiguration tempConfig = networkCandidate;

            while (tempConfig.getNetworkSelectionStatus().getConnectChoice() != null) {
                String key = tempConfig.getNetworkSelectionStatus().getConnectChoice();
                tempConfig = mWifiConfigManager.getWifiConfiguration(key);

                if (tempConfig != null) {
                    WifiConfiguration.NetworkSelectionStatus tempStatus =
                            tempConfig.getNetworkSelectionStatus();
                    if (tempStatus.getCandidate() != null && tempStatus.isNetworkEnabled()) {
                        scanResultCandidate = tempStatus.getCandidate();
                        networkCandidate = tempConfig;
                    }
                } else {
                    // We should not come here in theory.
                    localLoge("Connect choice: " + key + " has no corresponding saved config");
                    break;
                }
            }
            localLog("After user choice adjustment, the final candidate is:"
                    + getNetworkString(networkCandidate) + " : " + scanResultCandidate.BSSID);
        }

        // At this point none of the saved networks were good candidates so we fall back to
        // externally scored networks if any are available.
        if (scanResultCandidate == null) {
            localLog("Checking the externalScoreEvaluator for candidates...");
            networkCandidate = getExternalScoreCandidate(externalScoreEvaluator);
            if (networkCandidate != null) {
                scanResultCandidate = networkCandidate.getNetworkSelectionStatus().getCandidate();
            }
        }

        if (scanResultCandidate == null) {
            localLog("Can not find any suitable candidates");
            return null;
        }

        String currentAssociationId = mCurrentConnectedNetwork == null ? "Disconnected" :
                getNetworkString(mCurrentConnectedNetwork);
        String targetAssociationId = getNetworkString(networkCandidate);
        // In passpoint, saved configuration is initialized with a fake SSID. Now update it with
        // the real SSID from the scan result.
        if (networkCandidate.isPasspoint()) {
            networkCandidate.SSID = "\"" + scanResultCandidate.SSID + "\"";
        }

        mCurrentBssid = scanResultCandidate.BSSID;
        mCurrentConnectedNetwork = networkCandidate;
        mLastQualifiedNetworkSelectionTimeStamp = mClock.getElapsedSinceBootMillis();
        return networkCandidate;
    }

    /**
     * Returns the best candidate network according to the given ExternalScoreEvaluator.
     */
    @Nullable
    WifiConfiguration getExternalScoreCandidate(ExternalScoreEvaluator scoreEvaluator) {
        WifiConfiguration networkCandidate = null;
        switch (scoreEvaluator.getBestCandidateType()) {
            case ExternalScoreEvaluator.BestCandidateType.UNTRUSTED_NETWORK:
                ScanResult untrustedScanResultCandidate =
                        scoreEvaluator.getScanResultCandidate();
                WifiConfiguration unTrustedNetworkCandidate =
                        mWifiConfigManager.wifiConfigurationFromScanResult(
                                untrustedScanResultCandidate);

                // Mark this config as ephemeral so it isn't persisted.
                unTrustedNetworkCandidate.ephemeral = true;
                if (mNetworkScoreCache != null) {
                    unTrustedNetworkCandidate.meteredHint =
                            mNetworkScoreCache.getMeteredHint(untrustedScanResultCandidate);
                }
                mWifiConfigManager.saveNetwork(unTrustedNetworkCandidate,
                        WifiConfiguration.UNKNOWN_UID);

                localLog(String.format("new ephemeral candidate %s network ID:%d, "
                                + "meteredHint=%b",
                        toScanId(untrustedScanResultCandidate), unTrustedNetworkCandidate.networkId,
                        unTrustedNetworkCandidate.meteredHint));

                unTrustedNetworkCandidate.getNetworkSelectionStatus()
                        .setCandidate(untrustedScanResultCandidate);
                networkCandidate = unTrustedNetworkCandidate;
                break;

            case ExternalScoreEvaluator.BestCandidateType.SAVED_NETWORK:
                ScanResult scanResultCandidate = scoreEvaluator.getScanResultCandidate();
                networkCandidate = scoreEvaluator.getSavedConfig();
                networkCandidate.getNetworkSelectionStatus().setCandidate(scanResultCandidate);
                localLog(String.format("new scored candidate %s network ID:%d",
                        toScanId(scanResultCandidate), networkCandidate.networkId));
                break;

            case ExternalScoreEvaluator.BestCandidateType.NONE:
                localLog("ExternalScoreEvaluator did not see any good candidates.");
                break;

            default:
                localLoge("Unhandled ExternalScoreEvaluator case. No candidate selected.");
                break;
        }
        return networkCandidate;
    }

    /**
     * Returns the available external network score or NULL if no score is available.
     *
     * @param scanResult The scan result of the network to score.
     * @param isActiveNetwork Whether or not the network is currently connected.
     * @return A valid external score if one is available or NULL.
     */
    @Nullable
    Integer getNetworkScore(ScanResult scanResult, boolean isActiveNetwork) {
        if (mNetworkScoreCache != null && mNetworkScoreCache.isScoredNetwork(scanResult)) {
            int networkScore = mNetworkScoreCache.getNetworkScore(scanResult, isActiveNetwork);
            localLog(toScanId(scanResult) + " has score: " + networkScore);
            return networkScore;
        }
        return null;
    }

    /**
     * Formats the given ScanResult as a scan ID for logging.
     */
    private static String toScanId(@Nullable ScanResult scanResult) {
        return scanResult == null ? "NULL"
                                  : String.format("%s:%s", scanResult.SSID, scanResult.BSSID);
    }

    // Dump the logs.
    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiQualifiedNetworkSelector");
        pw.println("WifiQualifiedNetworkSelector - Log Begin ----");
        mLocalLog.dump(fd, pw, args);
        pw.println("WifiQualifiedNetworkSelector - Log End ----");
    }

    /**
     * Used to track and evaluate networks that are assigned external scores.
     */
    static class ExternalScoreEvaluator {
        @Retention(RetentionPolicy.SOURCE)
        @interface BestCandidateType {
            int NONE = 0;
            int SAVED_NETWORK = 1;
            int UNTRUSTED_NETWORK = 2;
        }
        // Always set to the best known candidate.
        private @BestCandidateType int mBestCandidateType = BestCandidateType.NONE;
        private int mHighScore = WifiNetworkScoreCache.INVALID_NETWORK_SCORE;
        private WifiConfiguration mSavedConfig;
        private ScanResult mScanResultCandidate;
        private final LocalLog mLocalLog;
        private final boolean mDbg;

        ExternalScoreEvaluator(LocalLog localLog, boolean dbg) {
            mLocalLog = localLog;
            mDbg = dbg;
        }

        // Determines whether or not the given scan result is the best one its seen so far.
        void evalUntrustedCandidate(@Nullable Integer score, ScanResult scanResult) {
            if (score != null && score > mHighScore) {
                mHighScore = score;
                mScanResultCandidate = scanResult;
                mBestCandidateType = BestCandidateType.UNTRUSTED_NETWORK;
                localLog(toScanId(scanResult) + " become the new untrusted candidate");
            }
        }

        // Determines whether or not the given saved network is the best one its seen so far.
        void evalSavedCandidate(@Nullable Integer score, WifiConfiguration config,
                ScanResult scanResult) {
            // Always take the highest score. If there's a tie and an untrusted network is currently
            // the best then pick the saved network.
            if (score != null
                    && (score > mHighScore
                        || (mBestCandidateType == BestCandidateType.UNTRUSTED_NETWORK
                            && score == mHighScore))) {
                mHighScore = score;
                mSavedConfig = config;
                mScanResultCandidate = scanResult;
                mBestCandidateType = BestCandidateType.SAVED_NETWORK;
                localLog(toScanId(scanResult) + " become the new externally scored saved network "
                        + "candidate");
            }
        }

        int getBestCandidateType() {
            return mBestCandidateType;
        }

        int getHighScore() {
            return mHighScore;
        }

        public ScanResult getScanResultCandidate() {
            return mScanResultCandidate;
        }

        WifiConfiguration getSavedConfig() {
            return mSavedConfig;
        }

        private void localLog(String log) {
            if (mDbg) {
                mLocalLog.log(log);
            }
        }
    }
}
