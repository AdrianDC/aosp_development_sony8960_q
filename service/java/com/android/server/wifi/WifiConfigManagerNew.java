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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.IpConfiguration;
import android.net.ProxyInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
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
     * Flags to be passed in for |canModifyNetwork| to decide if the change is minor and can
     * bypass the lockdown checks.
     */
    private static final boolean ALLOW_LOCKDOWN_CHECK_BYPASS = true;
    private static final boolean DISALLOW_LOCKDOWN_CHECK_BYPASS = false;

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
     * Construct the string to be put in the |creationTime| & |updateTime| elements of
     * WifiConfiguration from the provided wall clock millis.
     *
     * @param wallClockMillis Time in milliseconds to be converted to string.
     */
    @VisibleForTesting
    public static String createDebugTimeStampString(long wallClockMillis) {
        StringBuilder sb = new StringBuilder();
        sb.append("time=");
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(wallClockMillis);
        sb.append(String.format("%tm-%td %tH:%tM:%tS.%tL", c, c, c, c, c, c));
        return sb.toString();
    }

    /**
     * Enable/disable verbose logging in WifiConfigManager & its helper classes.
     */
    public void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            mVerboseLoggingEnabled = true;
        } else {
            mVerboseLoggingEnabled = false;
        }
        mWifiConfigStore.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiKeyStore.enableVerboseLogging(mVerboseLoggingEnabled);
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
     * Method to send out the configured networks change broadcast when a single network
     * configuration is changed.
     *
     * @param network WifiConfiguration corresponding to the network that was changed.
     * @param reason  The reason for the change, should be one of WifiManager.CHANGE_REASON_ADDED,
     *                WifiManager.CHANGE_REASON_REMOVED, or WifiManager.CHANGE_REASON_CHANGE.
     */
    private void sendConfiguredNetworkChangedBroadcast(
            WifiConfiguration network, int reason) {
        Intent intent = new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, false);
        // Create a new WifiConfiguration with passwords masked before we send it out.
        WifiConfiguration broadcastNetwork = new WifiConfiguration(network);
        maskPasswordsInWifiConfiguration(broadcastNetwork);
        intent.putExtra(WifiManager.EXTRA_WIFI_CONFIGURATION, broadcastNetwork);
        intent.putExtra(WifiManager.EXTRA_CHANGE_REASON, reason);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Method to send out the configured networks change broadcast when multiple network
     * configurations are changed.
     */
    private void sendConfiguredNetworksChangedBroadcast() {
        Intent intent = new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, true);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Checks if the app has the permission to override Wi-Fi network configuration or not.
     *
     * @param uid uid of the app.
     * @return true if the app does have the permission, false otherwise.
     */
    private boolean checkConfigOverridePermission(int uid) {
        try {
            int permission =
                    mFacade.checkUidPermission(
                            android.Manifest.permission.OVERRIDE_WIFI_CONFIG, uid);
            return (permission == PackageManager.PERMISSION_GRANTED);
        } catch (RemoteException e) {
            Log.e(TAG, "Error checking for permission " + e);
            return false;
        }
    }

    /**
     * Checks if |uid| has permission to modify the provided configuration.
     *
     * @param config         WifiConfiguration object corresponding to the network to be modified.
     * @param uid            UID of the app requesting the modification.
     * @param ignoreLockdown Ignore the configuration lockdown checks for debug data updates.
     */
    private boolean canModifyNetwork(WifiConfiguration config, int uid, boolean ignoreLockdown) {
        final DevicePolicyManagerInternal dpmi = LocalServices.getService(
                DevicePolicyManagerInternal.class);

        final boolean isUidDeviceOwner = dpmi != null && dpmi.isActiveAdminWithPolicy(uid,
                DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

        // If |uid| corresponds to the device owner, allow all modifications.
        if (isUidDeviceOwner) {
            return true;
        }

        final boolean isCreator = (config.creatorUid == uid);

        // Check if the |uid| is either the creator of the network or holds the
        // |OVERRIDE_CONFIG_WIFI| permission if the caller asks us to bypass the lockdown checks.
        if (ignoreLockdown) {
            return isCreator || checkConfigOverridePermission(uid);
        }

        // Check if device has DPM capability. If it has and |dpmi| is still null, then we
        // treat this case with suspicion and bail out.
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)
                && dpmi == null) {
            Log.w(TAG, "Error retrieving DPMI service.");
            return false;
        }

        // WiFi config lockdown related logic. At this point we know uid is NOT a Device Owner.

        final boolean isConfigEligibleForLockdown = dpmi != null && dpmi.isActiveAdminWithPolicy(
                config.creatorUid, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        if (!isConfigEligibleForLockdown) {
            return isCreator || checkConfigOverridePermission(uid);
        }

        final ContentResolver resolver = mContext.getContentResolver();
        final boolean isLockdownFeatureEnabled = Settings.Global.getInt(resolver,
                Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN, 0) != 0;
        return !isLockdownFeatureEnabled && checkConfigOverridePermission(uid);
    }

    /**
     * Copy over public elements from an external WifiConfiguration object to the internal
     * configuration object if element has been set in the provided external WifiConfiguration.
     * The only exception is the hidden |IpConfiguration| parameters, these need to be copied over
     * for every update.
     *
     * This method updates all elements that are common to both network addition & update.
     * The following fields of {@link WifiConfiguration} are not copied from external configs:
     *  > networkId - These are allocated by Wi-Fi stack internally for any new configurations.
     *  > status - The status needs to be explicitly updated using
     *             {@link WifiManager#enableNetwork(int, boolean)} or
     *             {@link WifiManager#disableNetwork(int)}.
     *
     * @param externalConfig WifiConfiguration object provided from the external API.
     * @param internalConfig WifiConfiguration object in our internal map.
     */
    private void mergeWithInternalWifiConfiguration(
            WifiConfiguration externalConfig, WifiConfiguration internalConfig) {
        if (!TextUtils.isEmpty(externalConfig.SSID)) {
            internalConfig.SSID = externalConfig.SSID;
        }
        if (!TextUtils.isEmpty(externalConfig.BSSID)) {
            internalConfig.BSSID = externalConfig.BSSID;
        }
        internalConfig.hiddenSSID = externalConfig.hiddenSSID;
        if (!TextUtils.isEmpty(externalConfig.preSharedKey)) {
            internalConfig.preSharedKey = externalConfig.preSharedKey;
        }
        internalConfig.wepTxKeyIndex = externalConfig.wepTxKeyIndex;
        // Modify only wep keys are present in the provided configuration. This is a little tricky
        // because there is no easy way to tell if the app is actually trying to null out the
        // existing keys or not.
        if (externalConfig.wepKeys != null) {
            for (int i = 0; i < internalConfig.wepKeys.length; i++) {
                if (externalConfig.wepKeys[i] != null) {
                    internalConfig.wepKeys[i] = externalConfig.wepKeys[i];
                }
            }
        }
        if (!TextUtils.isEmpty(externalConfig.FQDN)) {
            internalConfig.FQDN = externalConfig.FQDN;
        }
        if (!TextUtils.isEmpty(externalConfig.providerFriendlyName)) {
            internalConfig.providerFriendlyName = externalConfig.providerFriendlyName;
        }
        if (externalConfig.roamingConsortiumIds != null) {
            internalConfig.roamingConsortiumIds = externalConfig.roamingConsortiumIds;
        }

        // Copy over the auth parameters if set.
        if (!externalConfig.allowedAuthAlgorithms.isEmpty()) {
            internalConfig.allowedAuthAlgorithms = externalConfig.allowedAuthAlgorithms;
        }
        if (!externalConfig.allowedProtocols.isEmpty()) {
            internalConfig.allowedProtocols = externalConfig.allowedProtocols;
        }
        if (!externalConfig.allowedKeyManagement.isEmpty()) {
            internalConfig.allowedKeyManagement = externalConfig.allowedKeyManagement;
        }
        if (!externalConfig.allowedPairwiseCiphers.isEmpty()) {
            internalConfig.allowedPairwiseCiphers = externalConfig.allowedPairwiseCiphers;
        }
        if (!externalConfig.allowedGroupCiphers.isEmpty()) {
            internalConfig.allowedGroupCiphers = externalConfig.allowedGroupCiphers;
        }

        // Copy over the IpConfiguration parameters if set.
        if (externalConfig.getIpAssignment() != IpConfiguration.IpAssignment.UNASSIGNED) {
            internalConfig.setIpAssignment(externalConfig.getIpAssignment());
            internalConfig.setStaticIpConfiguration(externalConfig.getStaticIpConfiguration());
        }
        if (externalConfig.getProxySettings() != IpConfiguration.ProxySettings.UNASSIGNED) {
            internalConfig.setProxySettings(externalConfig.getProxySettings());
            internalConfig.setHttpProxy(externalConfig.getHttpProxy());
        }

        // TODO(b/29641570): Merge enterprise config params. We may need to just check for non-null
        // values even above so that apps can reset fields during an update if needed.
        internalConfig.enterpriseConfig = new WifiEnterpriseConfig(externalConfig.enterpriseConfig);
    }

    /**
     * Set all the exposed defaults in the newly created WifiConfiguration object.
     * These fields have a default value advertised in our public documentation. The only exception
     * is the hidden |IpConfiguration| parameters, these have a default value even though they're
     * hidden.
     *
     * @param configuration provided WifiConfiguration object.
     */
    private void setDefaultsInWifiConfiguration(WifiConfiguration configuration) {
        configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
        configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);

        configuration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
        configuration.allowedProtocols.set(WifiConfiguration.Protocol.WPA);

        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);

        configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);

        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);

        configuration.setIpAssignment(IpConfiguration.IpAssignment.DHCP);
        configuration.setProxySettings(IpConfiguration.ProxySettings.NONE);
    }

    /**
     * Create a new WifiConfiguration object by copying over parameters from the provided
     * external configuration and set defaults for the appropriate parameters.
     *
     * @param config provided external WifiConfiguration object.
     * @return New WifiConfiguration object with parameters merged from the provided external
     * configuration.
     */
    private WifiConfiguration createNewInternalWifiConfigurationFromExternal(
            WifiConfiguration config, int uid) {
        WifiConfiguration newConfig = new WifiConfiguration();

        // First allocate a new network ID for the configuration.
        newConfig.networkId = mLastNetworkId++;

        // First set defaults in the new configuration created.
        setDefaultsInWifiConfiguration(newConfig);

        // Copy over all the public elements from the provided configuration.
        mergeWithInternalWifiConfiguration(config, newConfig);

        // Copy over the hidden configuration parameters. These are the only parameters used by
        // system apps to indicate some property about the network being added.
        // These are only copied over for network additions and ignored for network updates.
        newConfig.requirePMF = config.requirePMF;
        newConfig.noInternetAccessExpected = config.noInternetAccessExpected;
        newConfig.ephemeral = config.ephemeral;
        newConfig.meteredHint = config.meteredHint;
        newConfig.useExternalScores = config.useExternalScores;
        newConfig.shared = config.shared;

        // Add debug information for network addition.
        newConfig.creatorUid = newConfig.lastUpdateUid = uid;
        newConfig.creatorName = newConfig.lastUpdateName =
                mContext.getPackageManager().getNameForUid(uid);
        newConfig.creationTime = newConfig.updateTime =
                createDebugTimeStampString(mClock.getWallClockMillis());

        return newConfig;
    }

    /**
     * Merges the provided external WifiConfiguration object with the existing internal
     * WifiConfiguration object.
     *
     * @param config provided external WifiConfiguration object.
     * @return Existing WifiConfiguration object with parameters merged from the provided
     * configuration.
     */
    private WifiConfiguration updateExistingInternalWifiConfigurationFromExternal(
            WifiConfiguration config, int uid) {
        WifiConfiguration existingConfig = getInternalConfiguredNetwork(config);

        // Copy over all the public elements from the provided configuration.
        mergeWithInternalWifiConfiguration(config, existingConfig);

        // Add debug information for network update.
        existingConfig.lastUpdateUid = uid;
        existingConfig.lastUpdateName = mContext.getPackageManager().getNameForUid(uid);
        existingConfig.updateTime = createDebugTimeStampString(mClock.getWallClockMillis());

        return existingConfig;
    }

    /**
     * Compare existing and new IpConfiguration and return if IP assignment has changed or not.
     *
     * @param existingConfig Existing config corresponding to the network already stored in our
     *                       database.
     * @param newConfig      New updated config object in our database.
     * @return true if IP assignment have changed, false otherwise.
     */
    private boolean hasIpChanged(WifiConfiguration existingConfig, WifiConfiguration newConfig) {
        switch (newConfig.getIpAssignment()) {
            case STATIC:
                if (existingConfig.getIpAssignment() != newConfig.getIpAssignment()) {
                    return true;
                } else {
                    return !Objects.equals(
                            existingConfig.getStaticIpConfiguration(),
                            newConfig.getStaticIpConfiguration());
                }
            case DHCP:
                return (existingConfig.getIpAssignment() != newConfig.getIpAssignment());
            default:
                return false;
        }
    }

    /**
     * Compare existing and new IpConfiguration and return if Proxy settings has changed or not.
     *
     * @param existingConfig Existing config corresponding to the network already stored in our
     *                       database.
     * @param newConfig      New updated config object in our database.
     * @return true if proxy settings have changed, false otherwise.
     */
    private boolean hasProxyChanged(WifiConfiguration existingConfig, WifiConfiguration newConfig) {
        switch (newConfig.getProxySettings()) {
            case STATIC:
            case PAC:
                ProxyInfo newHttpProxy = newConfig.getHttpProxy();
                ProxyInfo currentHttpProxy = existingConfig.getHttpProxy();
                if (newHttpProxy != null) {
                    return !newHttpProxy.equals(currentHttpProxy);
                } else {
                    return (currentHttpProxy != null);
                }
            case NONE:
                return (existingConfig.getProxySettings() != newConfig.getProxySettings());
            default:
                return false;
        }
    }

    /**
     * Add a network or update a network configuration to our database.
     * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
     * network configuration. Otherwise, the networkId should refer to an existing configuration.
     *
     * @param config provided WifiConfiguration object.
     * @param uid    UID of the app requesting the network addition/deletion.
     * @return NetworkUpdateResult object representing status of the update.
     */
    private NetworkUpdateResult addOrUpdateNetworkInternal(WifiConfiguration config, int uid) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Adding/Updating network " + config.getPrintableSsid());
        }
        boolean newNetwork;
        WifiConfiguration existingInternalConfig;
        WifiConfiguration newInternalConfig;

        if (!isNetworkConfiguredInternally(config)) {
            newNetwork = true;
            existingInternalConfig = null;
            newInternalConfig = createNewInternalWifiConfigurationFromExternal(config, uid);
        } else {
            newNetwork = false;
            existingInternalConfig =
                    new WifiConfiguration(getInternalConfiguredNetwork(config));
            // Check for the app's permission before we let it update this network.
            if (!canModifyNetwork(existingInternalConfig, uid, DISALLOW_LOCKDOWN_CHECK_BYPASS)) {
                Log.e(TAG, "UID " + uid + " does not have permission to update configuration "
                        + config.configKey());
                return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
            }
            newInternalConfig = updateExistingInternalWifiConfigurationFromExternal(config, uid);
        }

        // Update the keys for enterprise networks.
        if (config.enterpriseConfig != null
                && config.enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE) {
            if (!(mWifiKeyStore.updateNetworkKeys(config, existingInternalConfig))) {
                return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
            }
        }

        // Add it our internal map.
        mConfiguredNetworks.put(newInternalConfig);

        // Stage the backup of the SettingsProvider package which backs this up.
        mBackupManagerProxy.notifyDataChanged();

        // This is needed to inform IpManager about any IP configuration changes.
        boolean hasIpChanged =
                newNetwork || hasIpChanged(existingInternalConfig, newInternalConfig);
        boolean hasProxyChanged =
                newNetwork || hasProxyChanged(existingInternalConfig, newInternalConfig);
        NetworkUpdateResult result = new NetworkUpdateResult(hasIpChanged, hasProxyChanged);
        result.setIsNewNetwork(newNetwork);
        result.setNetworkId(newInternalConfig.networkId);

        localLog("addOrUpdateNetworkInternal: added/updated config."
                + " netId=" + newInternalConfig.networkId
                + " configKey=" + newInternalConfig.configKey()
                + " uid=" + Integer.toString(newInternalConfig.creatorUid)
                + " name=" + newInternalConfig.creatorName);
        return result;
    }

    /**
     * Add a network or update a network configuration to our database.
     * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
     * network configuration. Otherwise, the networkId should refer to an existing configuration.
     *
     * @param config provided WifiConfiguration object.
     * @param uid    UID of the app requesting the network addition/deletion.
     * @return NetworkUpdateResult object representing status of the update.
     */
    public NetworkUpdateResult addOrUpdateNetwork(WifiConfiguration config, int uid) {
        if (config == null) {
            Log.e(TAG, "Cannot add/update network with null config");
            return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
        }
        NetworkUpdateResult result = addOrUpdateNetworkInternal(config, uid);
        if (!result.isSuccess()) {
            Log.e(TAG, "Failed to add/update network " + config.getPrintableSsid());
            return result;
        }
        WifiConfiguration newConfig = getInternalConfiguredNetwork(result.getNetworkId());
        sendConfiguredNetworkChangedBroadcast(
                newConfig,
                result.isNewNetwork()
                        ? WifiManager.CHANGE_REASON_ADDED
                        : WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        // External modification, persist it immediately.
        saveToStore(true);
        return result;
    }

    /**
     * Removes the specified network configuration from our database.
     *
     * @param config provided WifiConfiguration object.
     * @return true if successful, false otherwise.
     */
    private boolean removeNetworkInternal(WifiConfiguration config) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing network " + config.getPrintableSsid());
        }
        // Remove any associated enterprise keys.
        if (config.enterpriseConfig != null
                && config.enterpriseConfig.getEapMethod() != WifiEnterpriseConfig.Eap.NONE) {
            mWifiKeyStore.removeKeys(config.enterpriseConfig);
        }

        mConfiguredNetworks.remove(config.networkId);
        mScanDetailCaches.remove(config.networkId);
        // Stage the backup of the SettingsProvider package which backs this up.
        mBackupManagerProxy.notifyDataChanged();

        localLog("removeNetworkInternal: removed config."
                + " netId=" + config.networkId
                + " configKey=" + config.configKey());
        return true;
    }

    /**
     * Removes the specified network configuration from our database.
     *
     * @param networkId network ID of the provided network.
     * @return true if successful, false otherwise.
     */
    public boolean removeNetwork(int networkId) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            Log.e(TAG, "Cannot find network with networkId " + networkId);
            return false;
        }
        if (!removeNetworkInternal(config)) {
            Log.e(TAG, "Failed to remove network " + config.getPrintableSsid());
            return false;
        }
        sendConfiguredNetworkChangedBroadcast(config, WifiManager.CHANGE_REASON_REMOVED);
        // External modification, persist it immediately.
        saveToStore(true);
        return true;
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
            Log.wtf(TAG, "Reading from new store failed " + e + ". All saved networks are lost!");
            return;
        }
        long readTime = mClock.getElapsedSinceBootMillis() - readStartTime;
        Log.d(TAG, "Loading from store completed in " + readTime + " ms.");

        // Clear out all the existing in-memory lists and load the lists from what was retrieved
        // from the config store.
        mConfiguredNetworks.clear();
        mDeletedEphemeralSSIDs.clear();
        for (WifiConfiguration configuration : storeData.configurations) {
            configuration.networkId = mLastNetworkId++;
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Adding network from store " + configuration.configKey());
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
            Log.wtf(TAG, "Writing to store failed " + e + ". Saved networks maybe lost!");
            return false;
        }
        long writeTime = mClock.getElapsedSinceBootMillis() - writeStartTime;
        Log.d(TAG, "Writing to store completed in " + writeTime + " ms.");
        return true;
    }

    /**
     * Updates the last connected UID for the provided configuration if the UID has the permission
     * to do it.
     *
     * @param networkId network ID corresponding to the network.
     * @param uid       uid of the app requesting the connection.
     */
    public boolean updateLastConnectUid(int networkId, int uid) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (!canModifyNetwork(config, uid, DISALLOW_LOCKDOWN_CHECK_BYPASS)) {
            if (config.lastConnectUid != uid) {
                config.lastConnectUid = uid;
            }
            return true;
        }
        return false;
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
