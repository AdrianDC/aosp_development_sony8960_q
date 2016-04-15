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

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.util.Log;
import android.util.Pair;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * <TBD> Intended Purpose/Behavior of the class upon completion:
 * Essentially this class automates a user toggling 'Airplane Mode' when WiFi "won't work".
 * IF each available saved network has failed connecting more times than the FAILURE_THRESHOLD
 * THEN Watchdog will restart Supplicant, wifi driver and return WifiStateMachine to InitialState.
 * </TBD>
 */
public class WifiLastResortWatchdog {
    private static final String TAG = "WifiLastResortWatchdog";
    private static final boolean VDBG = false;
    /**
     * Cached WifiConfigurations of available networks seen within MAX_BSSID_AGE scan results
     * Key:BSSID, Value:Counters of failure types
     */
    private Map<String, AvailableNetworkFailureCount> mRecentAvailableNetworks = new HashMap<>();

    // Maximum number of scan results received since we last saw a BSSID.
    // If it is not seen before this limit is reached, the network is culled
    public static final int MAX_BSSID_AGE = 10;

    /**
     * Refreshes recentAvailableNetworks with the latest available networks
     * Adds new networks, removes old ones that have timed out. Should be called after Wifi
     * framework decides what networks it is potentially connecting to.
     * @param availableNetworkFailureCounts ScanDetail & Config list of potential connection
     * candidates
     */
    public void updateAvailableNetworks(
            List<Pair<ScanDetail, WifiConfiguration>> availableNetworkFailureCounts) {
        // Add new networks to mRecentAvailableNetworks
        if (availableNetworkFailureCounts != null) {
            for (Pair<ScanDetail, WifiConfiguration> pair : availableNetworkFailureCounts) {
                ScanResult scanResult = pair.first.getScanResult();
                if (scanResult == null) continue;
                String key = scanResult.BSSID;

                // Cache the scanResult & WifiConfig
                AvailableNetworkFailureCount availableNetworkFailureCount =
                        mRecentAvailableNetworks.get(key);
                if (availableNetworkFailureCount != null) {
                    // We've already cached this, refresh timeout count & config
                    availableNetworkFailureCount.config = pair.second;
                } else {
                    // New network is available
                    availableNetworkFailureCount = new AvailableNetworkFailureCount(pair.second);
                    availableNetworkFailureCount.Ssid = pair.first.getSSID();
                }
                // If we saw a network, set its Age to -1 here, next incrementation will set it to 0
                availableNetworkFailureCount.age = -1;
                mRecentAvailableNetworks.put(key, availableNetworkFailureCount);
            }
        }

        // Iterate through available networks updating timeout counts & removing networks.
        Iterator<Map.Entry<String, AvailableNetworkFailureCount>> it =
                mRecentAvailableNetworks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, AvailableNetworkFailureCount> entry = it.next();
            if (entry.getValue().age < MAX_BSSID_AGE - 1) {
                entry.getValue().age++;
            } else {
                it.remove();
            }
        }
        if (VDBG) Log.v(TAG, toString());
    }

    /**
     * Gets the buffer of recently available networks
     */
    Map<String, AvailableNetworkFailureCount> getRecentAvailableNetworks() {
        return mRecentAvailableNetworks;
    }

    /**
     * Prints all networks & counts within mRecentAvailableNetworks to string
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("WifiLastResortWatchdog: " + mRecentAvailableNetworks.size() + " networks...");
        for (Map.Entry<String, AvailableNetworkFailureCount> entry
                : mRecentAvailableNetworks.entrySet()) {
            sb.append("\n " + entry.getKey() + ": " + entry.getValue());
        }
        return sb.toString();
    }

    static class AvailableNetworkFailureCount {
        /**
         * WifiConfiguration associated with this network. Can be null for Ephemeral networks
         */
        public WifiConfiguration config;
        /**
        * SSID of the network (from ScanDetail)
        */
        public String Ssid = "";
        /**
         * Number of times network has failed for this reason
         */
        public int associationRejection = 0;
        /**
         * Number of times network has failed for this reason
         */
        public int authenticationRejection = 0;
        /**
         * Number of times network has failed for this reason
         */
        public int dhcpFailure = 0;
        /**
         * Number of scanResults since this network was last seen
         */
        public int age = 0;

        AvailableNetworkFailureCount(WifiConfiguration config) {
            config = config;
        }

        void resetCounts() {
            associationRejection = 0;
            authenticationRejection = 0;
            dhcpFailure = 0;
        }

        public String toString() {
            return  Ssid + ", HasEverConnected: " + ((config != null)
                    ? config.getNetworkSelectionStatus().getHasEverConnected() : false)
                    + ", Failures: {"
                    + "Assoc: " + associationRejection
                    + ", Auth: " + authenticationRejection
                    + ", Dhcp: " + dhcpFailure
                    + "}"
                    + ", Age: " + age;
        }
    }
}
