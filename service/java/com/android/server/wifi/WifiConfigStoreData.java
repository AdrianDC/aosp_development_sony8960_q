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

import android.net.wifi.WifiConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Class to encapsulate all the data to be stored across all the stores. This is a snapshot
 * of all the settings passed from {@link WifiConfigManager} to persistent store.
 * Instances of this class is passed around for writing/parsing data to/from the stores.
 */
public class WifiConfigStoreData {
    /**
     * list of saved networks visible to the current user to be stored (includes shared & private).
     */
    public List<WifiConfiguration> configurations;
    /**
     * list of blacklist bssids to be stored.
     */
    public Set<String> blackListBSSIDs;
    /**
     * list of deleted ephemeral ssids to be stored.
     */
    public Set<String> deletedEphemeralSSIDs;
    /**
     * last network ID assigned.
     */
    public int lastNetworkId;

    /**
     * Create a new instance of store data to be written to the store files.
     *
     * @param configurations        list of saved networks to be stored.
     *                              See {@link WifiConfigManager#mConfiguredNetworks}.
     * @param blacklistBSSIDs       list of blacklist bssids to be stored.
     *                              See {@link WifiConfigStore#mBssidBlacklist}
     *                              (TODO: Move this member to WifiConfigManager as well).
     * @param deletedEphemeralSSIDs list of deleted ephemeral ssids to be stored.
     *                              See {@link WifiConfigManager#mDeletedEphemeralSSIDs}
     * @param lastNetworkId         last network ID assigned.
     */
    public WifiConfigStoreData(List<WifiConfiguration> configurations, Set<String> blacklistBSSIDs,
            Set<String> deletedEphemeralSSIDs, int lastNetworkId) {
        this.configurations = configurations;
        this.blackListBSSIDs = blacklistBSSIDs;
        this.deletedEphemeralSSIDs = deletedEphemeralSSIDs;
        this.lastNetworkId = lastNetworkId;
    }

    /**
     * Create a new instance of the store data parsed from the store file data.
     *
     * @param sharedDataBytes raw data retrieved from the shared store file.
     * @param userDataBytes   raw data retrieved from the user store file.
     * @return new instance of store data.
     */
    public static WifiConfigStoreData parseRawData(byte[] sharedDataBytes, byte[] userDataBytes) {
        SharedData sharedData = SharedData.parseRawData(sharedDataBytes);
        UserData userData = UserData.parseRawData(userDataBytes);

        return getStoreData(sharedData, userData);
    }

    /**
     * Create a WifiConfigStoreData instance from the retrieved UserData & SharedData instance.
     */
    private static WifiConfigStoreData getStoreData(SharedData sharedData, UserData userData) {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.addAll(sharedData.configurations);
        configurations.addAll(userData.configurations);
        return new WifiConfigStoreData(
                configurations, userData.blackListBSSIDs,
                userData.deletedEphemeralSSIDs, sharedData.lastNetworkId);

    }

    /**
     * Create raw byte array to be stored in the share store file.
     * This method serializes the data to a byte array in XML format.
     *
     * @return pair of byte array with the serialized output. The first element is the shared
     * raw data and the second element is the user raw data.
     */
    public byte[] createSharedRawData() {
        SharedData sharedData = getSharedData();
        return sharedData.createRawData();
    }

    /**
     * Create raw byte array to be stored in the user store file.
     * This method serializes the data to a byte array in XML format.
     *
     * @return byte array with the serialized output.
     */
    public byte[] createUserRawData() {
        UserData userData = getUserData();
        return userData.createRawData();
    }

    /**
     * Retrieve the shared data to be stored in the shared config store file.
     *
     * @return SharedData instance.
     */
    private SharedData getSharedData() {
        List<WifiConfiguration> sharedConfigurations = new ArrayList<>();
        for (WifiConfiguration configuration : configurations) {
            if (configuration.shared) {
                sharedConfigurations.add(configuration);
            }
        }
        return new SharedData(sharedConfigurations, lastNetworkId);
    }

    /**
     * Retrieve the user specific data to be stored in the user config store file.
     *
     * @return UserData instance.
     */
    private UserData getUserData() {
        List<WifiConfiguration> userConfigurations = new ArrayList<>();
        for (WifiConfiguration configuration : configurations) {
            if (!configuration.shared) {
                userConfigurations.add(configuration);
            }
        }
        return new UserData(userConfigurations, blackListBSSIDs, deletedEphemeralSSIDs);
    }

    /**
     * Class to encapsulate all the data to be stored in the shared store.
     * Instances of this class is passed around for writing/parsing data to/from the XML store
     * file.
     */
    public static class SharedData {
        public List<WifiConfiguration> configurations;
        public int lastNetworkId;

        /**
         * Create a new instance of shared store data to be written to the store files.
         *
         * @param configurations list of shared saved networks to be stored.
         * @param lastNetworkId  last network ID assigned.
         */
        public SharedData(List<WifiConfiguration> configurations, int lastNetworkId) {
            this.configurations = configurations;
            this.lastNetworkId = lastNetworkId;
        }

        /**
         * Create a new instance of the shared store data parsed from the store file.
         * This method deserializes the provided byte array in XML format to a new SharedData
         * instance.
         *
         * @param sharedDataBytes raw data retrieved from the shared store file.
         * @return new instance of store data.
         */
        public static SharedData parseRawData(byte[] sharedDataBytes) {
            return new SharedData(null, 0);
        }

        /**
         * Create raw byte array to be stored in the store file.
         * This method serializes the data to a byte array in XML format.
         *
         * @return byte array with the serialized output.
         */
        public static byte[] createRawData() {
            return null;
        }
    }

    /**
     * Class to encapsulate all the data to be stored in the user specific store.
     * Instances of this class is passed around for writing/parsing data to/from the XML store
     * file.
     */
    public static class UserData {
        public List<WifiConfiguration> configurations;
        public Set<String> blackListBSSIDs;
        public Set<String> deletedEphemeralSSIDs;

        /**
         * Create a new instance of user specific store data to be written to the store files.
         *
         * @param configurations        list of user specific saved networks to be stored.
         * @param blacklistBSSIDs       list of blacklist bssids to be stored.
         * @param deletedEphemeralSSIDs list of deleted ephemeral ssids to be stored.
         */
        public UserData(List<WifiConfiguration> configurations, Set<String> blacklistBSSIDs,
                Set<String> deletedEphemeralSSIDs) {
            this.configurations = configurations;
            this.blackListBSSIDs = blacklistBSSIDs;
            this.deletedEphemeralSSIDs = deletedEphemeralSSIDs;
        }

        /**
         * Create a new instance of the user store data parsed from the store file.
         * This method deserializes the provided byte array in XML format to a new UserData
         * instance.
         *
         * @param userDataBytes raw data retrieved from the user store file.
         * @return new instance of store data.
         */
        public static UserData parseRawData(byte[] userDataBytes) {
            return new UserData(null, null, null);
        }

        /**
         * Create raw byte array to be stored in the store file.
         * This method serializes the data to a byte array in XML format.
         *
         * @return byte array with the serialized output.
         */
        public static byte[] createRawData() {
            return null;
        }
    }
}


