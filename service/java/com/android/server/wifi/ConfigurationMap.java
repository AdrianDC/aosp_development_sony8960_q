package com.android.server.wifi;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.server.wifi.util.ScanResultUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ConfigurationMap {
    private final Map<Integer, WifiConfiguration> mPerID = new HashMap<>();

    private final Map<Integer, WifiConfiguration> mPerIDForCurrentUser = new HashMap<>();
    private final Map<String, WifiConfiguration> mPerFQDNForCurrentUser = new HashMap<>();
    private final Map<ScanResultMatchInfo, WifiConfiguration>
            mScanResultMatchInfoMapForCurrentUser = new HashMap<>();

    private final UserManager mUserManager;

    private int mCurrentUserId = UserHandle.USER_SYSTEM;

    ConfigurationMap(UserManager userManager) {
        mUserManager = userManager;
    }

    // RW methods:
    public WifiConfiguration put(WifiConfiguration config) {
        final WifiConfiguration current = mPerID.put(config.networkId, config);
        if (WifiConfigurationUtil.isVisibleToAnyProfile(config,
                mUserManager.getProfiles(mCurrentUserId))) {
            mPerIDForCurrentUser.put(config.networkId, config);
            if (config.FQDN != null && config.FQDN.length() > 0) {
                mPerFQDNForCurrentUser.put(config.FQDN, config);
            }
            mScanResultMatchInfoMapForCurrentUser.put(
                    ScanResultMatchInfo.fromWifiConfiguration(config), config);
        }
        return current;
    }

    public WifiConfiguration remove(int netID) {
        WifiConfiguration config = mPerID.remove(netID);
        if (config == null) {
            return null;
        }

        mPerIDForCurrentUser.remove(netID);
        Iterator<Map.Entry<String, WifiConfiguration>> fqdnEntries =
                mPerFQDNForCurrentUser.entrySet().iterator();
        while (fqdnEntries.hasNext()) {
            if (fqdnEntries.next().getValue().networkId == netID) {
                fqdnEntries.remove();
                break;
            }
        }

        Iterator<Map.Entry<ScanResultMatchInfo, WifiConfiguration>> scanResultMatchInfoEntries =
                mScanResultMatchInfoMapForCurrentUser.entrySet().iterator();
        while (scanResultMatchInfoEntries.hasNext()) {
            if (scanResultMatchInfoEntries.next().getValue().networkId == netID) {
                scanResultMatchInfoEntries.remove();
                break;
            }
        }
        return config;
    }

    public void clear() {
        mPerID.clear();
        mPerIDForCurrentUser.clear();
        mPerFQDNForCurrentUser.clear();
        mScanResultMatchInfoMapForCurrentUser.clear();
    }

    /**
     * Sets the new foreground user ID.
     *
     * @param userId the id of the new foreground user
     */
    public void setNewUser(int userId) {
        mCurrentUserId = userId;
    }

    // RO methods:
    public WifiConfiguration getForAllUsers(int netid) {
        return mPerID.get(netid);
    }

    public WifiConfiguration getForCurrentUser(int netid) {
        return mPerIDForCurrentUser.get(netid);
    }

    public int sizeForAllUsers() {
        return mPerID.size();
    }

    public int sizeForCurrentUser() {
        return mPerIDForCurrentUser.size();
    }

    public WifiConfiguration getByFQDNForCurrentUser(String fqdn) {
        return mPerFQDNForCurrentUser.get(fqdn);
    }

    public WifiConfiguration getByConfigKeyForCurrentUser(String key) {
        if (key == null) {
            return null;
        }
        for (WifiConfiguration config : mPerIDForCurrentUser.values()) {
            if (config.configKey().equals(key)) {
                return config;
            }
        }
        return null;
    }

    /**
     * Retrieves the |WifiConfiguration| object matching the provided |scanResult| from the internal
     * map.
     * Essentially checks if network config and scan result have the same SSID and encryption type.
     */
    public WifiConfiguration getByScanResultForCurrentUser(ScanResult scanResult) {
        return mScanResultMatchInfoMapForCurrentUser.get(
                ScanResultMatchInfo.fromScanResult(scanResult));
    }


    public Collection<WifiConfiguration> getEnabledNetworksForCurrentUser() {
        List<WifiConfiguration> list = new ArrayList<>();
        for (WifiConfiguration config : mPerIDForCurrentUser.values()) {
            if (config.status != WifiConfiguration.Status.DISABLED) {
                list.add(config);
            }
        }
        return list;
    }

    public WifiConfiguration getEphemeralForCurrentUser(String ssid) {
        for (WifiConfiguration config : mPerIDForCurrentUser.values()) {
            if (ssid.equals(config.SSID) && config.ephemeral) {
                return config;
            }
        }
        return null;
    }

    public Collection<WifiConfiguration> valuesForAllUsers() {
        return mPerID.values();
    }

    public Collection<WifiConfiguration> valuesForCurrentUser() {
        return mPerIDForCurrentUser.values();
    }

    /**
     * Class to store the info needed to match a scan result to the provided network configuration.
     */
    private static class ScanResultMatchInfo {
        private static final int NETWORK_TYPE_OPEN = 0;
        private static final int NETWORK_TYPE_WEP = 1;
        private static final int NETWORK_TYPE_PSK = 2;
        private static final int NETWORK_TYPE_EAP = 3;

        /**
         * SSID of the network.
         */
        public String networkSsid;
        /**
         * Security Type of the network.
         */
        public int networkType;

        public static ScanResultMatchInfo fromWifiConfiguration(WifiConfiguration config) {
            ScanResultMatchInfo info = new ScanResultMatchInfo();
            info.networkSsid = config.SSID;
            if (WifiConfigurationUtil.isConfigForPskNetwork(config)) {
                info.networkType = NETWORK_TYPE_PSK;
            } else if (WifiConfigurationUtil.isConfigForEapNetwork(config)) {
                info.networkType = NETWORK_TYPE_EAP;
            } else if (WifiConfigurationUtil.isConfigForWepNetwork(config)) {
                info.networkType = NETWORK_TYPE_WEP;
            } else if (WifiConfigurationUtil.isConfigForOpenNetwork(config)) {
                info.networkType = NETWORK_TYPE_OPEN;
            } else {
                throw new IllegalArgumentException("Invalid WifiConfiguration: " + config);
            }
            return info;
        }

        public static ScanResultMatchInfo fromScanResult(ScanResult scanResult) {
            ScanResultMatchInfo info = new ScanResultMatchInfo();
            // Scan result ssid's are not quoted, hence add quotes.
            // TODO: This matching algo works only if the scan result contains a string SSID.
            // However, according to our public documentation ths {@link WifiConfiguration#SSID} can
            // either have a hex string or quoted ASCII string SSID.
            info.networkSsid = ScanResultUtil.createQuotedSSID(scanResult.SSID);
            if (ScanResultUtil.isScanResultForPskNetwork(scanResult)) {
                info.networkType = NETWORK_TYPE_PSK;
            } else if (ScanResultUtil.isScanResultForEapNetwork(scanResult)) {
                info.networkType = NETWORK_TYPE_EAP;
            } else if (ScanResultUtil.isScanResultForWepNetwork(scanResult)) {
                info.networkType = NETWORK_TYPE_WEP;
            } else if (ScanResultUtil.isScanResultForOpenNetwork(scanResult)) {
                info.networkType = NETWORK_TYPE_OPEN;
            } else {
                throw new IllegalArgumentException("Invalid ScanResult: " + scanResult);
            }
            return info;
        }

        @Override
        public boolean equals(Object otherObj) {
            if (this == otherObj) {
                return true;
            } else if (!(otherObj instanceof ScanResultMatchInfo)) {
                return false;
            }
            ScanResultMatchInfo other = (ScanResultMatchInfo) otherObj;
            return Objects.equals(networkSsid, other.networkSsid)
                    && networkType == other.networkType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(networkSsid, networkType);
        }

        @Override
        public String toString() {
            return "ScanResultMatchInfo: " + networkSsid + ", type: " + networkType;
        }
    }
}
