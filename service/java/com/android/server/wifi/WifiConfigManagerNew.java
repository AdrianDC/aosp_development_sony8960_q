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
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.IpConfiguration;
import android.net.ProxyInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class provides the APIs to manage configured Wi-Fi networks.
 * It deals with the following:
 * - Maintaining a list of configured networks for quick access.
 * - Persisting the configurations to store when required.
 * - Supporting WifiManager Public API calls:
 *   > addOrUpdateNetwork()
 *   > removeNetwork()
 *   > enableNetwork()
 *   > disableNetwork()
 * - Handle user switching on multi-user devices.
 *
 * All network configurations retrieved from this class are copies of the original configuration
 * stored in the internal database. So, any updates to the retrieved configuration object are
 * meaningless and will not be reflected in the original database.
 * This is done on purpose to ensure that only WifiConfigManager can modify configurations stored
 * in the internal database. Any configuration updates should be triggered with appropriate helper
 * methods of this class using the configuration's unique networkId.
 *
 * NOTE: These API's are not thread safe and should only be used from WifiStateMachine thread.
 */
public class WifiConfigManagerNew {
    /**
     * String used to mask passwords to public interface.
     */
    @VisibleForTesting
    public static final String PASSWORD_MASK = "*";

    /**
     * Log tag for this class.
     */
    private static final String TAG = "WifiConfigManagerNew";
    /**
     * Network Selection disable reason thresholds. These numbers are used to debounce network
     * failures before we disable them.
     * These are indexed using the disable reason constants defined in
     * {@link android.net.wifi.WifiConfiguration.NetworkSelectionStatus}.
     */
    private static final int[] NETWORK_SELECTION_DISABLE_THRESHOLD = {
            -1, //  threshold for NETWORK_SELECTION_ENABLE
            1,  //  threshold for DISABLED_BAD_LINK
            5,  //  threshold for DISABLED_ASSOCIATION_REJECTION
            5,  //  threshold for DISABLED_AUTHENTICATION_FAILURE
            5,  //  threshold for DISABLED_DHCP_FAILURE
            5,  //  threshold for DISABLED_DNS_FAILURE
            1,  //  threshold for DISABLED_WPS_START
            6,  //  threshold for DISABLED_TLS_VERSION_MISMATCH
            1,  //  threshold for DISABLED_AUTHENTICATION_NO_CREDENTIALS
            1,  //  threshold for DISABLED_NO_INTERNET
            1,  //  threshold for DISABLED_BY_WIFI_MANAGER
            1   //  threshold for DISABLED_BY_USER_SWITCH
    };
    /**
     * Network Selection disable timeout for each kind of error. After the timeout minutes,enable
     * the network again.
     * These are indexed using the disable reason constants defined in
     * {@link android.net.wifi.WifiConfiguration.NetworkSelectionStatus}.
     */
    private static final int[] NETWORK_SELECTION_DISABLE_TIMEOUT = {
            Integer.MAX_VALUE,  // threshold for NETWORK_SELECTION_ENABLE
            15,                 // threshold for DISABLED_BAD_LINK
            5,                  // threshold for DISABLED_ASSOCIATION_REJECTION
            5,                  // threshold for DISABLED_AUTHENTICATION_FAILURE
            5,                  // threshold for DISABLED_DHCP_FAILURE
            5,                  // threshold for DISABLED_DNS_FAILURE
            0,                  // threshold for DISABLED_WPS_START
            Integer.MAX_VALUE,  // threshold for DISABLED_TLS_VERSION
            Integer.MAX_VALUE,  // threshold for DISABLED_AUTHENTICATION_NO_CREDENTIALS
            Integer.MAX_VALUE,  // threshold for DISABLED_NO_INTERNET
            Integer.MAX_VALUE,  // threshold for DISABLED_BY_WIFI_MANAGER
            Integer.MAX_VALUE   // threshold for DISABLED_BY_USER_SWITCH
    };
    /**
     * List of external dependencies for WifiConfigManager.
     */
    private final Context mContext;
    private final FrameworkFacade mFacade;
    private final Clock mClock;
    private final UserManager mUserManager;
    private final BackupManagerProxy mBackupManagerProxy;
    private final WifiConfigStoreNew mWifiConfigStore;
    private final WifiKeyStore mWifiKeyStore;
    /**
     * Local log used for debugging any WifiConfigManager issues.
     */
    private final LocalLog mLocalLog =
            new LocalLog(ActivityManager.isLowRamDeviceStatic() ? 128 : 256);
    /**
     * Map of configured networks with network id as the key.
     */
    private final ConfigurationMap mConfiguredNetworks;
    /**
     * Stores a map of NetworkId to ScanDetailCache.
     */
    private final ConcurrentHashMap<Integer, ScanDetailCache> mScanDetailCaches;
    /**
     * Framework keeps a list of ephemeral SSIDs that where deleted by user,
     * so as, framework knows not to autoconnect again those SSIDs based on scorer input.
     * The list is never cleared up.
     * The SSIDs are encoded in a String as per definition of WifiConfiguration.SSID field.
     */
    private final Set<String> mDeletedEphemeralSSIDs;

    /**
     * Verbose logging flag. Toggled by developer options.
     */
    private boolean mVerboseLoggingEnabled = false;
    /**
     * Current logged in user ID.
     */
    private int mCurrentUserId = UserHandle.USER_SYSTEM;
    /**
     * This is keeping track of the last network ID assigned. Any new networks will be assigned
     * |mLastNetworkId + 1| as network ID.
     */
    private int mLastNetworkId;

    /**
     * Create new instance of WifiConfigManager.
     */
    WifiConfigManagerNew(
            Context context, FrameworkFacade facade, Clock clock, UserManager userManager,
            WifiKeyStore wifiKeyStore, WifiConfigStoreNew wifiConfigStore) {
        mContext = context;
        mFacade = facade;
        mClock = clock;
        mUserManager = userManager;
        mBackupManagerProxy = new BackupManagerProxy();
        mWifiConfigStore = wifiConfigStore;
        mWifiKeyStore = wifiKeyStore;

        mConfiguredNetworks = new ConfigurationMap(userManager);
        mScanDetailCaches = new ConcurrentHashMap<>(16, 0.75f, 2);
        mDeletedEphemeralSSIDs = new HashSet<String>();
    }

    /**
     * Enable/disable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            mVerboseLoggingEnabled = true;
        } else {
            mVerboseLoggingEnabled = false;
        }
        mWifiConfigStore.enableVerboseLogging(verbose);
    }

    /**
     * Helper method to mask all passwords/keys from the provided WifiConfiguration object. This
     * is needed when the network configurations are being requested via the public WifiManager
     * API's.
     * This currently masks the following elements: psk, wepKeys & enterprise config password.
     */
    private void maskPasswordsInWifiConfiguration(WifiConfiguration configuration) {
        if (!TextUtils.isEmpty(configuration.preSharedKey)) {
            configuration.preSharedKey = PASSWORD_MASK;
        }
        if (configuration.wepKeys != null) {
            for (int i = 0; i < configuration.wepKeys.length; i++) {
                if (!TextUtils.isEmpty(configuration.wepKeys[i])) {
                    configuration.wepKeys[i] = PASSWORD_MASK;
                }
            }
        }
        if (!TextUtils.isEmpty(configuration.enterpriseConfig.getPassword())) {
            configuration.enterpriseConfig.setPassword(PASSWORD_MASK);
        }
    }

    /**
     * Fetch the list of currently configured networks maintained in WifiConfigManager.
     *
     * This retrieves a copy of the internal configurations maintained by WifiConfigManager and
     * should be used for any public interfaces.
     *
     * @param savedOnly     Retrieve only saved networks.
     * @param maskPasswords Mask passwords or not.
     * @return List of WifiConfiguration objects representing the networks.
     */
    private List<WifiConfiguration> getConfiguredNetworks(
            boolean savedOnly, boolean maskPasswords) {
        List<WifiConfiguration> networks = new ArrayList<>();
        for (WifiConfiguration config : getInternalConfiguredNetworks()) {
            if (savedOnly && config.ephemeral) {
                continue;
            }
            WifiConfiguration newConfig = new WifiConfiguration(config);
            if (maskPasswords) {
                maskPasswordsInWifiConfiguration(newConfig);
            }
            networks.add(newConfig);
        }
        return networks;
    }

    /**
     * Retrieves the list of all configured networks with passwords masked.
     *
     * @return List of WifiConfiguration objects representing the networks.
     */
    public List<WifiConfiguration> getConfiguredNetworks() {
        return getConfiguredNetworks(false, true);
    }

    /**
     * Retrieves the list of all configured networks with the passwords.
     *
     * WARNING: Don't use this to pass network configurations to external apps. Should only sent
     * to system apps who have a need for the passwords.
     * TODO: Need to understand the current use case of this API.
     *
     * @return List of WifiConfiguration objects representing the networks.
     */
    public List<WifiConfiguration> getConfiguredNetworksWithPasswords() {
        return getConfiguredNetworks(false, false);
    }

    /**
     * Retrieves the list of all configured networks with the passwords masked.
     *
     * @return List of WifiConfiguration objects representing the networks.
     */
    public List<WifiConfiguration> getSavedNetworks() {
        return getConfiguredNetworks(true, true);
    }

    /**
     * Helper method to retrieve all the internal WifiConfiguration objects corresponding to all
     * the networks in our database.
     */
    private Collection<WifiConfiguration> getInternalConfiguredNetworks() {
        return mConfiguredNetworks.valuesForCurrentUser();
    }

    /**
     * Helper method to retrieve the internal WifiConfiguration object corresponding to the
     * provided configuration in our database.
     * This first attempts to find the network using the provided network ID in configuration,
     * else it attempts to find a matching configuration using the configKey.
     */
    private WifiConfiguration getInternalConfiguredNetwork(WifiConfiguration config) {
        WifiConfiguration internalConfig = mConfiguredNetworks.getForCurrentUser(config.networkId);
        if (internalConfig != null) {
            return internalConfig;
        }
        return mConfiguredNetworks.getByConfigKeyForCurrentUser(config.configKey());
    }

    /**
     * Helper method to retrieve the internal WifiConfiguration object corresponding to the
     * provided network ID in our database.
     */
    private WifiConfiguration getInternalConfiguredNetwork(int networkId) {
        return mConfiguredNetworks.getForCurrentUser(networkId);
    }

    /**
     * Helper method to check if the network is already configured internally or not.
     */
    private boolean isNetworkConfiguredInternally(WifiConfiguration config) {
        return getInternalConfiguredNetwork(config) != null;
    }

    /**
     * Helper method to check if the network is already configured internally or not.
     */
    private boolean isNetworkConfiguredInternally(int networkId) {
        return getInternalConfiguredNetwork(networkId) != null;
    }

    /**
     * Read the config store and load the in-memory lists from the store data retrieved.
     * This reads all the network configurations from:
     * 1. Shared WifiConfigStore.xml
     * 2. User WifiConfigStore.xml
     * 3. PerProviderSubscription.conf
     */
    private void loadFromStore() {
        WifiConfigStoreData storeData;

        long readStartTime = mClock.getElapsedSinceBootMillis();
        try {
            storeData = mWifiConfigStore.read();
        } catch (Exception e) {
            // TODO: Handle this exception properly?
            Log.wtf(TAG, "Reading from new store failed: " + e + ". All saved networks are lost!");
            return;
        }
        long readTime = mClock.getElapsedSinceBootMillis() - readStartTime;
        Log.d(TAG, "loadFromStore: Read time: " + readTime + " ms");

        // Clear out all the existing in-memory lists and load the lists from what was retrieved
        // from the config store.
        mConfiguredNetworks.clear();
        mDeletedEphemeralSSIDs.clear();
        for (WifiConfiguration configuration : storeData.configurations) {
            configuration.networkId = mLastNetworkId++;
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Adding network from store: " + configuration.configKey());
            }
            mConfiguredNetworks.put(configuration);
        }
        for (String ssid : storeData.deletedEphemeralSSIDs) {
            mDeletedEphemeralSSIDs.add(ssid);
        }
        if (mConfiguredNetworks.sizeForAllUsers() == 0) {
            Log.w(TAG, "No stored networks found.");
        }
    }

    /**
     * Save the current snapshot of the in-memory lists to the config store.
     *
     * @param forceWrite Whether the write needs to be forced or not.
     * @return Whether the write was successful or not, this is applicable only for force writes.
     */
    private boolean saveToStore(boolean forceWrite) {
        ArrayList<WifiConfiguration> configurations = new ArrayList<>();
        // Don't persist ephemeral networks to store.
        for (WifiConfiguration config : mConfiguredNetworks.valuesForCurrentUser()) {
            if (!config.ephemeral) {
                configurations.add(config);
            }
        }
        WifiConfigStoreData storeData =
                new WifiConfigStoreData(configurations, mDeletedEphemeralSSIDs);

        long writeStartTime = mClock.getElapsedSinceBootMillis();
        try {
            mWifiConfigStore.write(forceWrite, storeData);
        } catch (Exception e) {
            // TODO: Handle this exception properly?
            Log.wtf(TAG, "Writing to store failed: " + e + ". Saved networks maybe lost!");
            return false;
        }
        long writeTime = mClock.getElapsedSinceBootMillis() - writeStartTime;
        Log.d(TAG, "saveToStore: Write time: " + writeTime + " ms");
        return true;
    }

    /**
     * Helper method for logging into local log buffer.
     */
    private void localLog(String s) {
        if (mLocalLog != null) {
            mLocalLog.log(s);
        }
    }

    /**
     * Dump the local log buffer.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiConfigManager");
        pw.println("WifiConfigManager - Log Begin ----");
        mLocalLog.dump(fd, pw, args);
        pw.println("WifiConfigManager - Log End ----");
    }
}
