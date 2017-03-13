/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.net.apf.ApfCapabilities;
import android.net.wifi.IApInterface;
import android.net.wifi.IClientInterface;
import android.net.wifi.RttManager;
import android.net.wifi.RttManager.ResponderConfig;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiLinkLayerStats;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiWakeReasonAndCounts;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pGroupList;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.Immutable;
import com.android.internal.util.HexDump;
import com.android.server.connectivity.KeepalivePacketData;
import com.android.server.wifi.util.FrameParser;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;


/**
 * Native calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 *
 * {@hide}
 */
public class WifiNative {
    private final String mTAG;
    private final String mInterfaceName;
    private final SupplicantStaIfaceHal mSupplicantStaIfaceHal;
    private final SupplicantP2pIfaceHal mSupplicantP2pIfaceHal;
    private final WifiVendorHal mWifiVendorHal;
    private final WificondControl mWificondControl;

    public WifiNative(String interfaceName, WifiVendorHal vendorHal,
                      SupplicantStaIfaceHal staIfaceHal, SupplicantP2pIfaceHal p2pIfaceHal,
                      WificondControl condControl) {
        mTAG = "WifiNative-" + interfaceName;
        mInterfaceName = interfaceName;
        mWifiVendorHal = vendorHal;
        mSupplicantStaIfaceHal = staIfaceHal;
        mSupplicantP2pIfaceHal = p2pIfaceHal;
        mWificondControl = condControl;
    }

    public String getInterfaceName() {
        return mInterfaceName;
    }

    /**
     * Enable verbose logging for all sub modules.
     */
    public void enableVerboseLogging(int verbose) {
        mWificondControl.enableVerboseLogging(verbose > 0 ? true : false);
        mSupplicantStaIfaceHal.enableVerboseLogging(verbose > 0);
        mWifiVendorHal.enableVerboseLogging(verbose > 0);
    }

   /********************************************************
    * Native Initialization/Deinitialization
    ********************************************************/

   /**
    * Setup wifi native for Client mode operations.
    *
    * 1. Starts the Wifi HAL and configures it in client/STA mode.
    * 2. Setup Wificond to operate in client mode and retrieve the handle to use for client
    * operations.
    *
    * @return An IClientInterface as wificond client interface binder handler.
    * Returns null on failure.
    */
    public IClientInterface setupForClientMode() {
        if (!startHal(true)) {
            // TODO(b/34859006): Handle failures.
            Log.e(mTAG, "Failed to start HAL for client mode");
        }
        return mWificondControl.setupDriverForClientMode();
    }

    /**
     * Setup wifi native for AP mode operations.
     *
     * 1. Starts the Wifi HAL and configures it in AP mode.
     * 2. Setup Wificond to operate in AP mode and retrieve the handle to use for ap operations.
     *
     * @return An IApInterface as wificond Ap interface binder handler.
     * Returns null on failure.
     */
    public IApInterface setupForSoftApMode() {
        if (!startHal(false)) {
            // TODO(b/34859006): Handle failures.
            Log.e(mTAG, "Failed to start HAL for AP mode");
        }
        return mWificondControl.setupDriverForSoftApMode();
    }

    /**
     * Teardown all mode configurations in wifi native.
     *
     * 1. Tears down all the interfaces from Wificond.
     * 2. Stops the Wifi HAL.
     *
     * @return Returns true on success.
     */
    public boolean tearDown() {
        if (!mWificondControl.tearDownInterfaces()) {
            // TODO(b/34859006): Handle failures.
            Log.e(mTAG, "Failed to teardown interfaces from Wificond");
            return false;
        }
        stopHal();
        return true;
    }

    /********************************************************
     * Wificond operations
     ********************************************************/
    /**
     * Result of a signal poll.
     */
    public static class SignalPollResult {
        // RSSI value in dBM.
        public int currentRssi;
        //Transmission bit rate in Mbps.
        public int txBitrate;
        // Association frequency in MHz.
        public int associationFrequency;
    }

    /**
     * WiFi interface transimission counters.
     */
    public static class TxPacketCounters {
        // Number of successfully transmitted packets.
        public int txSucceeded;
        // Number of tramsmission failures.
        public int txFailed;
    }

    /**
    * Disable wpa_supplicant via wificond.
    * @return Returns true on success.
    */
    public boolean disableSupplicant() {
        return mWificondControl.disableSupplicant();
    }

    /**
    * Enable wpa_supplicant via wificond.
    * @return Returns true on success.
    */
    public boolean enableSupplicant() {
        return mWificondControl.enableSupplicant();
    }

    /**
    * Request signal polling to wificond.
    * Returns an SignalPollResult object.
    * Returns null on failure.
    */
    public SignalPollResult signalPoll() {
        return mWificondControl.signalPoll();
    }

    /**
     * Fetch TX packet counters on current connection from wificond.
    * Returns an TxPacketCounters object.
    * Returns null on failure.
    */
    public TxPacketCounters getTxPacketCounters() {
        return mWificondControl.getTxPacketCounters();
    }

    /**
     * Start a scan using wificond for the given parameters.
     * @param freqs list of frequencies to scan for, if null scan all supported channels.
     * @param hiddenNetworkSSIDs List of hidden networks to be scanned for.
     * @return Returns true on success.
     */
    public boolean scan(Set<Integer> freqs, Set<String> hiddenNetworkSSIDs) {
        return mWificondControl.scan(freqs, hiddenNetworkSSIDs);
    }

    /**
     * Fetch the latest scan result from kernel via wificond.
     * @return Returns an ArrayList of ScanDetail.
     * Returns an empty ArrayList on failure.
     */
    public ArrayList<ScanDetail> getScanResults() {
        return mWificondControl.getScanResults();
    }

    /**
     * Start PNO scan.
     * @param pnoSettings Pno scan configuration.
     * @return true on success.
     */
    public boolean startPnoScan(PnoSettings pnoSettings) {
        return mWificondControl.startPnoScan(pnoSettings);
    }

    /**
     * Stop PNO scan.
     * @return true on success.
     */
    public boolean stopPnoScan() {
        return mWificondControl.stopPnoScan();
    }

    /********************************************************
     * Supplicant operations
     ********************************************************/

    /**
     * This method is called repeatedly until the connection to wpa_supplicant is established.
     *
     * @return true if connection is established, false otherwise.
     * TODO: Add unit tests for these once we remove the legacy code.
     */
    public boolean connectToStaSupplicant() {
        // Start initialization if not already started.
        if (!mSupplicantStaIfaceHal.isInitializationStarted()
                && !mSupplicantStaIfaceHal.initialize()) {
            return false;
        }
        // Check if the initialization is complete.
        return mSupplicantStaIfaceHal.isInitializationComplete();
    }

    /**
     * This method is called repeatedly until the connection to wpa_supplicant is established.
     *
     * @return true if connection is established, false otherwise.
     * TODO: Add unit tests for these once we remove the legacy code.
     */
    public boolean connectToP2pSupplicant() {
        // Start initialization if not already started.
        if (!mSupplicantP2pIfaceHal.isInitializationStarted()
                && !mSupplicantP2pIfaceHal.initialize()) {
            return false;
        }
        // Check if the initialization is complete.
        return mSupplicantP2pIfaceHal.isInitializationComplete();
    }

    /**
     * Close supplicant connection.
     */
    public void closeSupplicantConnection() {
        // Nothing to do for HIDL.
    }

    /**
     * Set supplicant log level
     *
     * @param turnOnVerbose Whether to turn on verbose logging or not.
     */
    public void setSupplicantLogLevel(boolean turnOnVerbose) {
        int logLevel = turnOnVerbose
                ? SupplicantStaIfaceHal.LOG_LEVEL_DEBUG
                : SupplicantStaIfaceHal.LOG_LEVEL_INFO;
        mSupplicantStaIfaceHal.setLogLevel(logLevel);
    }

    /**
     * Trigger a reconnection if the iface is disconnected.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean reconnect() {
        return mSupplicantStaIfaceHal.reconnect();
    }

    /**
     * Trigger a reassociation even if the iface is currently connected.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean reassociate() {
        return mSupplicantStaIfaceHal.reassociate();
    }

    /**
     * Trigger a disconnection from the currently connected network.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean disconnect() {
        return mSupplicantStaIfaceHal.disconnect();
    }

    /**
     * Makes a callback to HIDL to getMacAddress from supplicant
     *
     * @return string containing the MAC address, or null on a failed call
     */
    public String getMacAddress() {
        return mSupplicantStaIfaceHal.getMacAddress();
    }

    /**
     * Start filtering out Multicast V4 packets
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     *
     * Multicast filtering rules work as follows:
     *
     * The driver can filter multicast (v4 and/or v6) and broadcast packets when in
     * a power optimized mode (typically when screen goes off).
     *
     * In order to prevent the driver from filtering the multicast/broadcast packets, we have to
     * add a DRIVER RXFILTER-ADD rule followed by DRIVER RXFILTER-START to make the rule effective
     *
     * DRIVER RXFILTER-ADD Num
     *   where Num = 0 - Unicast, 1 - Broadcast, 2 - Mutil4 or 3 - Multi6
     *
     * and DRIVER RXFILTER-START
     * In order to stop the usage of these rules, we do
     *
     * DRIVER RXFILTER-STOP
     * DRIVER RXFILTER-REMOVE Num
     *   where Num is as described for RXFILTER-ADD
     *
     * The  SETSUSPENDOPT driver command overrides the filtering rules
     */
    public boolean startFilteringMulticastV4Packets() {
        return mSupplicantStaIfaceHal.stopRxFilter()
                && mSupplicantStaIfaceHal.removeRxFilter(
                SupplicantStaIfaceHal.RX_FILTER_TYPE_V4_MULTICAST)
                && mSupplicantStaIfaceHal.startRxFilter();
    }

    /**
     * Stop filtering out Multicast V4 packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean stopFilteringMulticastV4Packets() {
        return mSupplicantStaIfaceHal.stopRxFilter()
                && mSupplicantStaIfaceHal.addRxFilter(
                SupplicantStaIfaceHal.RX_FILTER_TYPE_V4_MULTICAST)
                && mSupplicantStaIfaceHal.startRxFilter();
    }

    /**
     * Start filtering out Multicast V6 packets
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean startFilteringMulticastV6Packets() {
        return mSupplicantStaIfaceHal.stopRxFilter()
                && mSupplicantStaIfaceHal.removeRxFilter(
                SupplicantStaIfaceHal.RX_FILTER_TYPE_V6_MULTICAST)
                && mSupplicantStaIfaceHal.startRxFilter();
    }

    /**
     * Stop filtering out Multicast V6 packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean stopFilteringMulticastV6Packets() {
        return mSupplicantStaIfaceHal.stopRxFilter()
                && mSupplicantStaIfaceHal.addRxFilter(
                SupplicantStaIfaceHal.RX_FILTER_TYPE_V6_MULTICAST)
                && mSupplicantStaIfaceHal.startRxFilter();
    }

    public static final int BLUETOOTH_COEXISTENCE_MODE_ENABLED  =
            SupplicantStaIfaceHal.BT_COEX_MODE_ENABLED;
    public static final int BLUETOOTH_COEXISTENCE_MODE_DISABLED =
            SupplicantStaIfaceHal.BT_COEX_MODE_DISABLED;
    public static final int BLUETOOTH_COEXISTENCE_MODE_SENSE    =
            SupplicantStaIfaceHal.BT_COEX_MODE_SENSE;
    /**
      * Sets the bluetooth coexistence mode.
      *
      * @param mode One of {@link #BLUETOOTH_COEXISTENCE_MODE_DISABLED},
      *            {@link #BLUETOOTH_COEXISTENCE_MODE_ENABLED}, or
      *            {@link #BLUETOOTH_COEXISTENCE_MODE_SENSE}.
      * @return Whether the mode was successfully set.
      */
    public boolean setBluetoothCoexistenceMode(int mode) {
        return mSupplicantStaIfaceHal.setBtCoexistenceMode((byte) mode);
    }

    /**
     * Enable or disable Bluetooth coexistence scan mode. When this mode is on,
     * some of the low-level scan parameters used by the driver are changed to
     * reduce interference with A2DP streaming.
     *
     * @param setCoexScanMode whether to enable or disable this mode
     * @return {@code true} if the command succeeded, {@code false} otherwise.
     */
    public boolean setBluetoothCoexistenceScanMode(boolean setCoexScanMode) {
        return mSupplicantStaIfaceHal.setBtCoexistenceScanModeEnabled(setCoexScanMode);
    }

    /**
     * Enable or disable suspend mode optimizations.
     *
     * @param enabled true to enable, false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setSuspendOptimizations(boolean enabled) {
        return mSupplicantStaIfaceHal.setSuspendModeEnabled(enabled);
    }

    /**
     * Set country code.
     *
     * @param countryCode 2 byte ASCII string. For ex: US, CA.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setCountryCode(String countryCode) {
        return mSupplicantStaIfaceHal.setCountryCode(countryCode);
    }

    /**
     * Initiate TDLS discover and setup or teardown with the specified peer.
     *
     * @param macAddr MAC Address of the peer.
     * @param enable true to start discovery and setup, false to teardown.
     */
    public void startTdls(String macAddr, boolean enable) {
        if (enable) {
            mSupplicantStaIfaceHal.initiateTdlsDiscover(macAddr);
            mSupplicantStaIfaceHal.initiateTdlsSetup(macAddr);
        } else {
            mSupplicantStaIfaceHal.initiateTdlsTeardown(macAddr);
        }
    }

    /**
     * Start WPS pin display operation with the specified peer.
     *
     * @param bssid BSSID of the peer.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsPbc(String bssid) {
        return mSupplicantStaIfaceHal.startWpsPbc(bssid);
    }

    /**
     * Start WPS pin keypad operation with the specified pin.
     *
     * @param pin Pin to be used.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsPinKeypad(String pin) {
        return mSupplicantStaIfaceHal.startWpsPinKeypad(pin);
    }

    /**
     * Start WPS pin display operation with the specified peer.
     *
     * @param bssid BSSID of the peer.
     * @return new pin generated on success, null otherwise.
     */
    public String startWpsPinDisplay(String bssid) {
        return mSupplicantStaIfaceHal.startWpsPinDisplay(bssid);
    }

    /**
     * Sets whether to use external sim for SIM/USIM processing.
     *
     * @param external true to enable, false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setExternalSim(boolean external) {
        return mSupplicantStaIfaceHal.setExternalSim(external);
    }

    /**
     * Sim auth response types.
     */
    public static final String SIM_AUTH_RESP_TYPE_GSM_AUTH = "GSM-AUTH";
    public static final String SIM_AUTH_RESP_TYPE_UMTS_AUTH = "UMTS-AUTH";
    public static final String SIM_AUTH_RESP_TYPE_UMTS_AUTS = "UMTS-AUTS";

    /**
     * Send the sim auth response for the currently configured network.
     *
     * @param type |GSM-AUTH|, |UMTS-AUTH| or |UMTS-AUTS|.
     * @param response Response params.
     * @return true if succeeds, false otherwise.
     */
    public boolean simAuthResponse(int id, String type, String response) {
        if (SIM_AUTH_RESP_TYPE_GSM_AUTH.equals(type)) {
            return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimGsmAuthResponse(response);
        } else if (SIM_AUTH_RESP_TYPE_UMTS_AUTH.equals(type)) {
            return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimUmtsAuthResponse(response);
        } else if (SIM_AUTH_RESP_TYPE_UMTS_AUTS.equals(type)) {
            return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimUmtsAutsResponse(response);
        } else {
            return false;
        }
    }

    /**
     * Send the eap sim gsm auth failure for the currently configured network.
     *
     * @return true if succeeds, false otherwise.
     */
    public boolean simAuthFailedResponse(int id) {
        return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimGsmAuthFailure();
    }

    /**
     * Send the eap sim umts auth failure for the currently configured network.
     *
     * @return true if succeeds, false otherwise.
     */
    public boolean umtsAuthFailedResponse(int id) {
        return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimUmtsAuthFailure();
    }

    /**
     * Send the eap identity response for the currently configured network.
     *
     * @param response String to send.
     * @return true if succeeds, false otherwise.
     */
    public boolean simIdentityResponse(int id, String response) {
        return mSupplicantStaIfaceHal.sendCurrentNetworkEapIdentityResponse(response);
    }

    /**
     * Start WPS pin registrar operation with the specified peer and pin.
     *
     * @param bssid BSSID of the peer.
     * @param pin Pin to be used.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsRegistrar(String bssid, String pin) {
        return mSupplicantStaIfaceHal.startWpsRegistrar(bssid, pin);
    }

    /**
     * Cancels any ongoing WPS requests.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean cancelWps() {
        return mSupplicantStaIfaceHal.cancelWps();
    }

    /**
     * Set WPS device name.
     *
     * @param name String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setDeviceName(String name) {
        return mSupplicantStaIfaceHal.setWpsDeviceName(name);
    }

    /**
     * Set WPS device type.
     *
     * @param type Type specified as a string. Used format: <categ>-<OUI>-<subcateg>
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setDeviceType(String type) {
        return mSupplicantStaIfaceHal.setWpsDeviceType(type);
    }

    /**
     * Set WPS config methods
     *
     * @param cfg List of config methods.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setConfigMethods(String cfg) {
        return mSupplicantStaIfaceHal.setWpsConfigMethods(cfg);
    }

    /**
     * Set WPS manufacturer.
     *
     * @param value String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setManufacturer(String value) {
        return mSupplicantStaIfaceHal.setWpsManufacturer(value);
    }

    /**
     * Set WPS model name.
     *
     * @param value String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setModelName(String value) {
        return mSupplicantStaIfaceHal.setWpsModelName(value);
    }

    /**
     * Set WPS model number.
     *
     * @param value String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setModelNumber(String value) {
        return mSupplicantStaIfaceHal.setWpsModelNumber(value);
    }

    /**
     * Set WPS serial number.
     *
     * @param value String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setSerialNumber(String value) {
        return mSupplicantStaIfaceHal.setWpsSerialNumber(value);
    }

    /**
     * Enable or disable power save mode.
     *
     * @param enabled true to enable, false to disable.
     */
    public void setPowerSave(boolean enabled) {
        mSupplicantStaIfaceHal.setPowerSave(enabled);
    }

    /**
     * Set concurrency priority between P2P & STA operations.
     *
     * @param isStaHigherPriority Set to true to prefer STA over P2P during concurrency operations,
     *                            false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setConcurrencyPriority(boolean isStaHigherPriority) {
        return mSupplicantStaIfaceHal.setConcurrencyPriority(isStaHigherPriority);
    }

    /**
     * Migrate all the configured networks from wpa_supplicant.
     *
     * @param configs       Map of configuration key to configuration objects corresponding to all
     *                      the networks.
     * @param networkExtras Map of extra configuration parameters stored in wpa_supplicant.conf
     * @return Max priority of all the configs.
     */
    public boolean migrateNetworksFromSupplicant(Map<String, WifiConfiguration> configs,
                                                 SparseArray<Map<String, String>> networkExtras) {
        return mSupplicantStaIfaceHal.loadNetworks(configs, networkExtras);
    }

    /**
     * Add the provided network configuration to wpa_supplicant and initiate connection to it.
     * This method does the following:
     * 1. Triggers disconnect command to wpa_supplicant (if |shouldDisconnect| is true).
     * 2. Remove any existing network in wpa_supplicant.
     * 3. Add a new network to wpa_supplicant.
     * 4. Save the provided configuration to wpa_supplicant.
     * 5. Select the new network in wpa_supplicant.
     * 6. Triggers reconnect command to wpa_supplicant.
     *
     * @param configuration WifiConfiguration parameters for the provided network.
     * @param shouldDisconnect whether to trigger a disconnection or not.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean connectToNetwork(WifiConfiguration configuration, boolean shouldDisconnect) {
        return mSupplicantStaIfaceHal.connectToNetwork(configuration, shouldDisconnect);
    }

    /**
     * Initiates roaming to the already configured network in wpa_supplicant. If the network
     * configuration provided does not match the already configured network, then this triggers
     * a new connection attempt (instead of roam).
     * 1. First check if we're attempting to connect to the same network as we currently have
     * configured.
     * 2. Set the new bssid for the network in wpa_supplicant.
     * 3. Triggers reassociate command to wpa_supplicant.
     *
     * @param configuration WifiConfiguration parameters for the provided network.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean roamToNetwork(WifiConfiguration configuration) {
        return mSupplicantStaIfaceHal.roamToNetwork(configuration);
    }

    /**
     * Get the framework network ID corresponding to the provided supplicant network ID for the
     * network configured in wpa_supplicant.
     *
     * @param supplicantNetworkId network ID in wpa_supplicant for the network.
     * @return Corresponding framework network ID if found, -1 if network not found.
     */
    public int getFrameworkNetworkId(int supplicantNetworkId) {
        return supplicantNetworkId;
    }

    /**
     * Remove all the networks.
     *
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean removeAllNetworks() {
        return mSupplicantStaIfaceHal.removeAllNetworks();
    }

    /**
     * Set the BSSID for the currently configured network in wpa_supplicant.
     *
     * @return true if successful, false otherwise.
     */
    public boolean setConfiguredNetworkBSSID(String bssid) {
        return mSupplicantStaIfaceHal.setCurrentNetworkBssid(bssid);
    }

    /**
     * Initiate ANQP query.
     *
     * @param bssid BSSID of the AP to be queried
     * @param anqpIds Set of anqp IDs.
     * @param hs20Subtypes Set of HS20 subtypes.
     * @return true on success, false otherwise.
     */
    public boolean requestAnqp(String bssid, Set<Integer> anqpIds, Set<Integer> hs20Subtypes) {
        if (bssid == null || ((anqpIds == null || anqpIds.isEmpty())
                && (hs20Subtypes == null || hs20Subtypes.isEmpty()))) {
            Log.e(mTAG, "Invalid arguments for ANQP request.");
            return false;
        }
        ArrayList<Short> anqpIdList = new ArrayList<>();
        for (Integer anqpId : anqpIds) {
            anqpIdList.add(anqpId.shortValue());
        }
        ArrayList<Integer> hs20SubtypeList = new ArrayList<>();
        hs20SubtypeList.addAll(hs20Subtypes);
        return mSupplicantStaIfaceHal.initiateAnqpQuery(bssid, anqpIdList, hs20SubtypeList);
    }

    /**
     * Request a passpoint icon file |filename| from the specified AP |bssid|.
     * @param bssid BSSID of the AP
     * @param fileName name of the icon file
     * @return true if request is sent successfully, false otherwise
     */
    public boolean requestIcon(String  bssid, String fileName) {
        if (bssid == null || fileName == null) {
            Log.e(mTAG, "Invalid arguments for Icon request.");
            return false;
        }
        return mSupplicantStaIfaceHal.initiateHs20IconQuery(bssid, fileName);
    }

    /**
     * Get the currently configured network's WPS NFC token.
     *
     * @return Hex string corresponding to the WPS NFC token.
     */
    public String getNfcWpsConfigurationToken(int netId) {
        return mSupplicantStaIfaceHal.getCurrentNetworkWpsNfcConfigurationToken();
    }

    /**
     * Populate list of available networks or update existing list.
     *
     * @return true, if list has been modified.
     */
    public boolean p2pListNetworks(WifiP2pGroupList groups) {
        return mSupplicantP2pIfaceHal.loadGroups(groups);
    }

    /**
     * Initiate WPS Push Button setup.
     * The PBC operation requires that a button is also pressed at the
     * AP/Registrar at about the same time (2 minute window).
     *
     * @param iface Group interface name to use.
     * @param bssid BSSID of the AP. Use zero'ed bssid to indicate wildcard.
     * @return true, if operation was successful.
     */
    public boolean startWpsPbc(String iface, String bssid) {
        return mSupplicantP2pIfaceHal.startWpsPbc(iface, bssid);
    }

    /**
     * Initiate WPS Pin Keypad setup.
     *
     * @param iface Group interface name to use.
     * @param pin 8 digit pin to be used.
     * @return true, if operation was successful.
     */
    public boolean startWpsPinKeypad(String iface, String pin) {
        return mSupplicantP2pIfaceHal.startWpsPinKeypad(iface, pin);
    }

    /**
     * Initiate WPS Pin Display setup.
     *
     * @param iface Group interface name to use.
     * @param bssid BSSID of the AP. Use zero'ed bssid to indicate wildcard.
     * @return generated pin if operation was successful, null otherwise.
     */
    public String startWpsPinDisplay(String iface, String bssid) {
        return mSupplicantP2pIfaceHal.startWpsPinDisplay(iface, bssid);
    }

    /**
     * Remove network with provided id.
     *
     * @param netId Id of the network to lookup.
     * @return true, if operation was successful.
     */
    public boolean removeP2pNetwork(int netId) {
        return mSupplicantP2pIfaceHal.removeNetwork(netId);
    }

    /**
     * Set WPS device name.
     *
     * @param name String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setP2pDeviceName(String name) {
        return mSupplicantP2pIfaceHal.setWpsDeviceName(name);
    }

    /**
     * Set WPS device type.
     *
     * @param type Type specified as a string. Used format: <categ>-<OUI>-<subcateg>
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setP2pDeviceType(String type) {
        return mSupplicantP2pIfaceHal.setWpsDeviceType(type);
    }

    /**
     * Set the postfix to be used for P2P SSID's.
     *
     * @param postfix String to be appended to SSID.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean setP2pSsidPostfix(String postfix) {
        return mSupplicantP2pIfaceHal.setSsidPostfix(postfix);
    }

    /**
     * Set the Maximum idle time in seconds for P2P groups.
     * This value controls how long a P2P group is maintained after there
     * is no other members in the group. As a group owner, this means no
     * associated stations in the group. As a P2P client, this means no
     * group owner seen in scan results.
     *
     * @param iface Group interface name to use.
     * @param time Timeout value in seconds.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean setP2pGroupIdle(String iface, int time) {
        return mSupplicantP2pIfaceHal.setGroupIdle(iface, time);
    }

    /**
     * Turn on/off power save mode for the interface.
     *
     * @param iface Group interface name to use.
     * @param enabled Indicate if power save is to be turned on/off.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean setP2pPowerSave(String iface, boolean enabled) {
        return mSupplicantP2pIfaceHal.setPowerSave(iface, enabled);
    }

    /**
     * Enable/Disable Wifi Display.
     *
     * @param enable true to enable, false to disable.
     * @return true, if operation was successful.
     */
    public boolean setWfdEnable(boolean enable) {
        return mSupplicantP2pIfaceHal.enableWfd(enable);
    }

    /**
     * Set Wifi Display device info.
     *
     * @param hex WFD device info as described in section 5.1.2 of WFD technical
     *        specification v1.0.0.
     * @return true, if operation was successful.
     */
    public boolean setWfdDeviceInfo(String hex) {
        return mSupplicantP2pIfaceHal.setWfdDeviceInfo(hex);
    }

    /**
     * Initiate a P2P service discovery indefinitely.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pFind() {
        return p2pFind(0);
    }

    /**
     * Initiate a P2P service discovery with a (optional) timeout.
     *
     * @param timeout Max time to be spent is peforming discovery.
     *        Set to 0 to indefinely continue discovery untill and explicit
     *        |stopFind| is sent.
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pFind(int timeout) {
        return mSupplicantP2pIfaceHal.find(timeout);
    }

    /**
     * Stop an ongoing P2P service discovery.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pStopFind() {
        return mSupplicantP2pIfaceHal.stopFind();
    }

    /**
     * Configure Extended Listen Timing.
     *
     * If enabled, listen state must be entered every |intervalInMillis| for at
     * least |periodInMillis|. Both values have acceptable range of 1-65535
     * (with interval obviously having to be larger than or equal to duration).
     * If the P2P module is not idle at the time the Extended Listen Timing
     * timeout occurs, the Listen State operation must be skipped.
     *
     * @param enable Enables or disables listening.
     * @param period Period in milliseconds.
     * @param interval Interval in milliseconds.
     *
     * @return true, if operation was successful.
     */
    public boolean p2pExtListen(boolean enable, int period, int interval) {
        return mSupplicantP2pIfaceHal.configureExtListen(enable, period, interval);
    }

    /**
     * Set P2P Listen channel.
     *
     * When specifying a social channel on the 2.4 GHz band (1/6/11) there is no
     * need to specify the operating class since it defaults to 81. When
     * specifying a social channel on the 60 GHz band (2), specify the 60 GHz
     * operating class (180).
     *
     * @param lc Wifi channel. eg, 1, 6, 11.
     * @param oc Operating Class indicates the channel set of the AP
     *        indicated by this BSSID
     *
     * @return true, if operation was successful.
     */
    public boolean p2pSetChannel(int lc, int oc) {
        return mSupplicantP2pIfaceHal.setListenChannel(lc, oc);
    }

    /**
     * Flush P2P peer table and state.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pFlush() {
        return mSupplicantP2pIfaceHal.flush();
    }

    /**
     * Start P2P group formation with a discovered P2P peer. This includes
     * optional group owner negotiation, group interface setup, provisioning,
     * and establishing data connection.
     *
     * @param config Configuration to use to connect to remote device.
     * @param joinExistingGroup Indicates that this is a command to join an
     *        existing group as a client. It skips the group owner negotiation
     *        part. This must send a Provision Discovery Request message to the
     *        target group owner before associating for WPS provisioning.
     *
     * @return String containing generated pin, if selected provision method
     *        uses PIN.
     */
    public String p2pConnect(WifiP2pConfig config, boolean joinExistingGroup) {
        return mSupplicantP2pIfaceHal.connect(config, joinExistingGroup);
    }

    /**
     * Cancel an ongoing P2P group formation and joining-a-group related
     * operation. This operation unauthorizes the specific peer device (if any
     * had been authorized to start group formation), stops P2P find (if in
     * progress), stops pending operations for join-a-group, and removes the
     * P2P group interface (if one was used) that is in the WPS provisioning
     * step. If the WPS provisioning step has been completed, the group is not
     * terminated.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pCancelConnect() {
        return mSupplicantP2pIfaceHal.cancelConnect();
    }

    /**
     * Send P2P provision discovery request to the specified peer. The
     * parameters for this command are the P2P device address of the peer and the
     * desired configuration method.
     *
     * @param config Config class describing peer setup.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pProvisionDiscovery(WifiP2pConfig config) {
        return mSupplicantP2pIfaceHal.provisionDiscovery(config);
    }

    /**
     * Set up a P2P group owner manually.
     * This is a helper method that invokes groupAdd(networkId, isPersistent) internally.
     *
     * @param persistent Used to request a persistent group to be formed.
     *
     * @return true, if operation was successful.
     */
    public boolean p2pGroupAdd(boolean persistent) {
        return mSupplicantP2pIfaceHal.groupAdd(persistent);
    }

    /**
     * Set up a P2P group owner manually (i.e., without group owner
     * negotiation with a specific peer). This is also known as autonomous
     * group owner.
     *
     * @param netId Used to specify the restart of a persistent group.
     *
     * @return true, if operation was successful.
     */
    public boolean p2pGroupAdd(int netId) {
        return mSupplicantP2pIfaceHal.groupAdd(netId, true);
    }

    /**
     * Terminate a P2P group. If a new virtual network interface was used for
     * the group, it must also be removed. The network interface name of the
     * group interface is used as a parameter for this command.
     *
     * @param iface Group interface name to use.
     * @return true, if operation was successful.
     */
    public boolean p2pGroupRemove(String iface) {
        return mSupplicantP2pIfaceHal.groupRemove(iface);
    }

    /**
     * Reject connection attempt from a peer (specified with a device
     * address). This is a mechanism to reject a pending group owner negotiation
     * with a peer and request to automatically block any further connection or
     * discovery of the peer.
     *
     * @param deviceAddress MAC address of the device to reject.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pReject(String deviceAddress) {
        return mSupplicantP2pIfaceHal.reject(deviceAddress);
    }

    /**
     * Invite a device to a persistent group.
     * If the peer device is the group owner of the persistent group, the peer
     * parameter is not needed. Otherwise it is used to specify which
     * device to invite. |goDeviceAddress| parameter may be used to override
     * the group owner device address for Invitation Request should it not be
     * known for some reason (this should not be needed in most cases).
     *
     * @param group Group object to use.
     * @param deviceAddress MAC address of the device to invite.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pInvite(WifiP2pGroup group, String deviceAddress) {
        return mSupplicantP2pIfaceHal.invite(group, deviceAddress);
    }

    /**
     * Reinvoke a device from a persistent group.
     *
     * @param netId Used to specify the persistent group.
     * @param deviceAddress MAC address of the device to reinvoke.
     *
     * @return true, if operation was successful.
     */
    public boolean p2pReinvoke(int netId, String deviceAddress) {
        return mSupplicantP2pIfaceHal.reinvoke(netId, deviceAddress);
    }

    /**
     * Gets the operational SSID of the device.
     *
     * @param deviceAddress MAC address of the peer.
     *
     * @return SSID of the device.
     */
    public String p2pGetSsid(String deviceAddress) {
        return mSupplicantP2pIfaceHal.getSsid(deviceAddress);
    }

    /**
     * Gets the MAC address of the device.
     *
     * @return MAC address of the device.
     */
    public String p2pGetDeviceAddress() {
        return mSupplicantP2pIfaceHal.getDeviceAddress();
    }

    /**
     * Gets the capability of the group which the device is a
     * member of.
     *
     * @param deviceAddress MAC address of the peer.
     *
     * @return combination of |GroupCapabilityMask| values.
     */
    public int getGroupCapability(String deviceAddress) {
        return mSupplicantP2pIfaceHal.getGroupCapability(deviceAddress);
    }

    /**
     * This command can be used to add a upnp/bonjour service.
     *
     * @param servInfo List of service queries.
     *
     * @return true, if operation was successful.
     */
    public boolean p2pServiceAdd(WifiP2pServiceInfo servInfo) {
        return mSupplicantP2pIfaceHal.serviceAdd(servInfo);
    }

    /**
     * This command can be used to remove a upnp/bonjour service.
     *
     * @param servInfo List of service queries.
     *
     * @return true, if operation was successful.
     */
    public boolean p2pServiceDel(WifiP2pServiceInfo servInfo) {
        return mSupplicantP2pIfaceHal.serviceRemove(servInfo);
    }

    /**
     * This command can be used to flush all services from the
     * device.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pServiceFlush() {
        return mSupplicantP2pIfaceHal.serviceFlush();
    }

    /**
     * Schedule a P2P service discovery request. The parameters for this command
     * are the device address of the peer device (or 00:00:00:00:00:00 for
     * wildcard query that is sent to every discovered P2P peer that supports
     * service discovery) and P2P Service Query TLV(s) as hexdump.
     *
     * @param addr MAC address of the device to discover.
     * @param query Hex dump of the query data.
     * @return identifier Identifier for the request. Can be used to cancel the
     *         request.
     */
    public String p2pServDiscReq(String addr, String query) {
        return mSupplicantP2pIfaceHal.requestServiceDiscovery(addr, query);
    }

    /**
     * Cancel a previous service discovery request.
     *
     * @param id Identifier for the request to cancel.
     * @return true, if operation was successful.
     */
    public boolean p2pServDiscCancelReq(String id) {
        return mSupplicantP2pIfaceHal.cancelServiceDiscovery(id);
    }

    /**
     * Send driver command to set Miracast mode.
     *
     * @param mode Mode of Miracast.
     *        0 = disabled
     *        1 = operating as source
     *        2 = operating as sink
     */
    public void setMiracastMode(int mode) {
        mSupplicantP2pIfaceHal.setMiracastMode(mode);
    }

    /**
     * Get NFC handover request message.
     *
     * @return select message if created successfully, null otherwise.
     */
    public String getNfcHandoverRequest() {
        return mSupplicantP2pIfaceHal.getNfcHandoverRequest();
    }

    /**
     * Get NFC handover select message.
     *
     * @return select message if created successfully, null otherwise.
     */
    public String getNfcHandoverSelect() {
        return mSupplicantP2pIfaceHal.getNfcHandoverSelect();
    }

    /**
     * Report NFC handover select message.
     *
     * @return true if reported successfully, false otherwise.
     */
    public boolean initiatorReportNfcHandover(String selectMessage) {
        return mSupplicantP2pIfaceHal.initiatorReportNfcHandover(selectMessage);
    }

    /**
     * Report NFC handover request message.
     *
     * @return true if reported successfully, false otherwise.
     */
    public boolean responderReportNfcHandover(String requestMessage) {
        return mSupplicantP2pIfaceHal.responderReportNfcHandover(requestMessage);
    }

    /**
     * Get P2P client list for the given network ID.
     * @return true on success, false otherwise.
     */
    public String getP2pClientList(int netId) {
        // TODO(b/36042785): Add HIDL method.
        return null;
    }

    /**
     * Set P2P client list for the given network ID.
     * @return true on success, false otherwise.
     */
    public boolean setP2pClientList(int netId, String list) {
        // TODO(b/36042785): Add HIDL method.
        return false;
    }

    /**
     * Save the current configuration to wpa_supplicant.conf.
     */
    public boolean saveConfig() {
        // TODO(b/36042785): Add HIDL method.
        return false;
    }

    /********************************************************
     * Vendor HAL operations
     ********************************************************/

    /**
     * Initializes the vendor HAL. This is just used to initialize the {@link HalDeviceManager}.
     */
    public boolean initializeVendorHal() {
        return mWifiVendorHal.initialize();
    }

    /**
     * Bring up the Vendor HAL and configure for STA mode or AP mode.
     *
     * @param isStaMode true to start HAL in STA mode, false to start in AP mode.
     */
    public boolean startHal(boolean isStaMode) {
        return mWifiVendorHal.startVendorHal(isStaMode);
    }

    /**
     * Stops the HAL
     */
    public void stopHal() {
        mWifiVendorHal.stopVendorHal();
    }

    /**
     * Tests whether the HAL is running or not
     */
    public boolean isHalStarted() {
        return mWifiVendorHal.isHalStarted();
    }

    // TODO: Change variable names to camel style.
    public static class ScanCapabilities {
        public int  max_scan_cache_size;
        public int  max_scan_buckets;
        public int  max_ap_cache_per_scan;
        public int  max_rssi_sample_size;
        public int  max_scan_reporting_threshold;
        public int  max_hotlist_bssids;
        public int  max_significant_wifi_change_aps;
        public int  max_bssid_history_entries;
        public int  max_number_epno_networks;
        public int  max_number_epno_networks_by_ssid;
        public int  max_number_of_white_listed_ssid;
    }

    /**
     * Gets the scan capabilities
     *
     * @param capabilities object to be filled in
     * @return true for success. false for failure
     */
    public boolean getBgScanCapabilities(ScanCapabilities capabilities) {
        return mWifiVendorHal.getBgScanCapabilities(capabilities);
    }

    public static class ChannelSettings {
        public int frequency;
        public int dwell_time_ms;
        public boolean passive;
    }

    public static class BucketSettings {
        public int bucket;
        public int band;
        public int period_ms;
        public int max_period_ms;
        public int step_count;
        public int report_events;
        public int num_channels;
        public ChannelSettings[] channels;
    }

    /**
     * Network parameters for hidden networks to be scanned for.
     */
    public static class HiddenNetwork {
        public String ssid;

        @Override
        public boolean equals(Object otherObj) {
            if (this == otherObj) {
                return true;
            } else if (otherObj == null || getClass() != otherObj.getClass()) {
                return false;
            }
            HiddenNetwork other = (HiddenNetwork) otherObj;
            return Objects.equals(ssid, other.ssid);
        }

        @Override
        public int hashCode() {
            return (ssid == null ? 0 : ssid.hashCode());
        }
    }

    public static class ScanSettings {
        public int base_period_ms;
        public int max_ap_per_scan;
        public int report_threshold_percent;
        public int report_threshold_num_scans;
        public int num_buckets;
        /* Not used for bg scans. Only works for single scans. */
        public HiddenNetwork[] hiddenNetworks;
        public BucketSettings[] buckets;
    }

    /**
     * Network parameters to start PNO scan.
     */
    public static class PnoNetwork {
        public String ssid;
        public byte flags;
        public byte auth_bit_field;

        @Override
        public boolean equals(Object otherObj) {
            if (this == otherObj) {
                return true;
            } else if (otherObj == null || getClass() != otherObj.getClass()) {
                return false;
            }
            PnoNetwork other = (PnoNetwork) otherObj;
            return ((Objects.equals(ssid, other.ssid)) && (flags == other.flags)
                    && (auth_bit_field == other.auth_bit_field));
        }

        @Override
        public int hashCode() {
            int result = (ssid == null ? 0 : ssid.hashCode());
            result ^= ((int) flags * 31) + ((int) auth_bit_field << 8);
            return result;
        }
    }

    /**
     * Parameters to start PNO scan. This holds the list of networks which are going to used for
     * PNO scan.
     */
    public static class PnoSettings {
        public int min5GHzRssi;
        public int min24GHzRssi;
        public int initialScoreMax;
        public int currentConnectionBonus;
        public int sameNetworkBonus;
        public int secureBonus;
        public int band5GHzBonus;
        public int periodInMs;
        public boolean isConnected;
        public PnoNetwork[] networkList;
    }

    public static interface ScanEventHandler {
        /**
         * Called for each AP as it is found with the entire contents of the beacon/probe response.
         * Only called when WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT is specified.
         */
        void onFullScanResult(ScanResult fullScanResult, int bucketsScanned);
        /**
         * Callback on an event during a gscan scan.
         * See WifiNative.WIFI_SCAN_* for possible values.
         */
        void onScanStatus(int event);
        /**
         * Called with the current cached scan results when gscan is paused.
         */
        void onScanPaused(WifiScanner.ScanData[] data);
        /**
         * Called with the current cached scan results when gscan is resumed.
         */
        void onScanRestarted();
    }

    /**
     * Handler to notify the occurrence of various events during PNO scan.
     */
    public interface PnoEventHandler {
        /**
         * Callback to notify when one of the shortlisted networks is found during PNO scan.
         * @param results List of Scan results received.
         */
        void onPnoNetworkFound(ScanResult[] results);

        /**
         * Callback to notify when the PNO scan schedule fails.
         */
        void onPnoScanFailed();
    }

    public static final int WIFI_SCAN_RESULTS_AVAILABLE = 0;
    public static final int WIFI_SCAN_THRESHOLD_NUM_SCANS = 1;
    public static final int WIFI_SCAN_THRESHOLD_PERCENT = 2;
    public static final int WIFI_SCAN_FAILED = 3;

    /**
     * Starts a background scan.
     * Any ongoing scan will be stopped first
     *
     * @param settings     to control the scan
     * @param eventHandler to call with the results
     * @return true for success
     */
    public boolean startBgScan(ScanSettings settings, ScanEventHandler eventHandler) {
        return mWifiVendorHal.startBgScan(settings, eventHandler);
    }

    /**
     * Stops any ongoing backgound scan
     */
    public void stopBgScan() {
        mWifiVendorHal.stopBgScan();
    }

    /**
     * Pauses an ongoing backgound scan
     */
    public void pauseBgScan() {
        mWifiVendorHal.pauseBgScan();
    }

    /**
     * Restarts a paused scan
     */
    public void restartBgScan() {
        mWifiVendorHal.restartBgScan();
    }

    /**
     * Gets the latest scan results received.
     */
    public WifiScanner.ScanData[] getBgScanResults() {
        return mWifiVendorHal.getBgScanResults();
    }

    public static interface HotlistEventHandler {
        void onHotlistApFound (ScanResult[] result);
        void onHotlistApLost  (ScanResult[] result);
    }

    public boolean setHotlist(WifiScanner.HotlistSettings settings,
            HotlistEventHandler eventHandler) {
        Log.e(mTAG, "setHotlist not supported");
        return false;
    }

    public void resetHotlist() {
        Log.e(mTAG, "resetHotlist not supported");
    }

    public static interface SignificantWifiChangeEventHandler {
        void onChangesFound(ScanResult[] result);
    }

    public boolean trackSignificantWifiChange(
            WifiScanner.WifiChangeSettings settings, SignificantWifiChangeEventHandler handler) {
        Log.e(mTAG, "trackSignificantWifiChange not supported");
        return false;
    }

    public void untrackSignificantWifiChange() {
        Log.e(mTAG, "untrackSignificantWifiChange not supported");
    }

    public WifiLinkLayerStats getWifiLinkLayerStats(String iface) {
        return mWifiVendorHal.getWifiLinkLayerStats();
    }

    public void setWifiLinkLayerStats(String iface, int enable) {
        // TODO(b//36087365) Remove this. Link layer stats is enabled when the HAL is started.
    }

    /**
     * Get the supported features
     *
     * @return bitmask defined by WifiManager.WIFI_FEATURE_*
     */
    public int getSupportedFeatureSet() {
        return mWifiVendorHal.getSupportedFeatureSet();
    }

    public static interface RttEventHandler {
        void onRttResults(RttManager.RttResult[] result);
    }

    /**
     * Starts a new rtt request
     *
     * @param params RTT request params. Refer to {@link RttManager#RttParams}.
     * @param handler Callback to be invoked to notify any results.
     * @return true if the request was successful, false otherwise.
     */
    public boolean requestRtt(
            RttManager.RttParams[] params, RttEventHandler handler) {
        return mWifiVendorHal.requestRtt(params, handler);
    }

    /**
     * Cancels an outstanding rtt request
     *
     * @param params RTT request params. Refer to {@link RttManager#RttParams}
     * @return true if there was an outstanding request and it was successfully cancelled
     */
    public boolean cancelRtt(RttManager.RttParams[] params) {
        return mWifiVendorHal.cancelRtt(params);
    }

    /**
     * Enable RTT responder role on the device. Returns {@link ResponderConfig} if the responder
     * role is successfully enabled, {@code null} otherwise.
     *
     * @param timeoutSeconds timeout to use for the responder.
     */
    @Nullable
    public ResponderConfig enableRttResponder(int timeoutSeconds) {
        return mWifiVendorHal.enableRttResponder(timeoutSeconds);
    }

    /**
     * Disable RTT responder role. Returns {@code true} if responder role is successfully disabled,
     * {@code false} otherwise.
     */
    public boolean disableRttResponder() {
        return mWifiVendorHal.disableRttResponder();
    }

    /**
     * Set the MAC OUI during scanning.
     * An OUI {Organizationally Unique Identifier} is a 24-bit number that
     * uniquely identifies a vendor or manufacturer.
     *
     * @param oui OUI to set.
     * @return true for success
     */
    public boolean setScanningMacOui(byte[] oui) {
        return mWifiVendorHal.setScanningMacOui(oui);
    }

    /**
     * Query the list of valid frequencies for the provided band.
     * The result depends on the on the country code that has been set.
     *
     * @param band as specified by one of the WifiScanner.WIFI_BAND_* constants.
     * @return frequencies vector of valid frequencies (MHz), or null for error.
     * @throws IllegalArgumentException if band is not recognized.
     */
    public int [] getChannelsForBand(int band) {
        return mWifiVendorHal.getChannelsForBand(band);
    }

    /**
     * Indicates whether getChannelsForBand is supported.
     *
     * @return true if it is.
     */
    public boolean isGetChannelsForBandSupported() {
        return mWifiVendorHal.isGetChannelsForBandSupported();
    }

    /**
     * Set DFS - actually, this is always on.
     *
     * @param dfsOn
     * @return success indication
     */
    public boolean setDfsFlag(boolean dfsOn) {
        return mWifiVendorHal.setDfsFlag(dfsOn);
    }

    /**
     * RTT (Round Trip Time) measurement capabilities of the device.
     */
    public RttManager.RttCapabilities getRttCapabilities() {
        return mWifiVendorHal.getRttCapabilities();
    }

    /**
     * Get the APF (Android Packet Filter) capabilities of the device
     */
    public ApfCapabilities getApfCapabilities() {
        return mWifiVendorHal.getApfCapabilities();
    }

    /**
     * Installs an APF program on this iface, replacing any existing program.
     *
     * @param filter is the android packet filter program
     * @return true for success
     */
    public boolean installPacketFilter(byte[] filter) {
        return mWifiVendorHal.installPacketFilter(filter);
    }

    /**
     * Set country code for this AP iface.
     *
     * @param countryCode - two-letter country code (as ISO 3166)
     * @return true for success
     */
    public boolean setCountryCodeHal(String countryCode) {
        return mWifiVendorHal.setCountryCodeHal(countryCode);
    }

    //---------------------------------------------------------------------------------
    /* Wifi Logger commands/events */
    public static interface WifiLoggerEventHandler {
        void onRingBufferData(RingBufferStatus status, byte[] buffer);
        void onWifiAlert(int errorCode, byte[] buffer);
    }

    /**
     * Registers the logger callback and enables alerts.
     * Ring buffer data collection is only triggered when |startLoggingRingBuffer| is invoked.
     *
     * @param handler Callback to be invoked.
     * @return true on success, false otherwise.
     */
    public boolean setLoggingEventHandler(WifiLoggerEventHandler handler) {
        return mWifiVendorHal.setLoggingEventHandler(handler);
    }

    /**
     * Control debug data collection
     *
     * @param verboseLevel 0 to 3, inclusive. 0 stops logging.
     * @param flags        Ignored.
     * @param maxInterval  Maximum interval between reports; ignore if 0.
     * @param minDataSize  Minimum data size in buffer for report; ignore if 0.
     * @param ringName     Name of the ring for which data collection is to start.
     * @return true for success, false otherwise.
     */
    public boolean startLoggingRingBuffer(int verboseLevel, int flags, int maxInterval,
            int minDataSize, String ringName){
        return mWifiVendorHal.startLoggingRingBuffer(
                verboseLevel, flags, maxInterval, minDataSize, ringName);
    }

    /**
     * Logger features exposed.
     * This is a no-op now, will always return -1.
     *
     * @return true on success, false otherwise.
     */
    public int getSupportedLoggerFeatureSet() {
        return mWifiVendorHal.getSupportedLoggerFeatureSet();
    }

    /**
     * Stops all logging and resets the logger callback.
     * This stops both the alerts and ring buffer data collection.
     * @return true on success, false otherwise.
     */
    public boolean resetLogHandler() {
        return mWifiVendorHal.resetLogHandler();
    }

    /**
     * Vendor-provided wifi driver version string
     *
     * @return String returned from the HAL.
     */
    public String getDriverVersion() {
        return mWifiVendorHal.getDriverVersion();
    }

    /**
     * Vendor-provided wifi firmware version string
     *
     * @return String returned from the HAL.
     */
    public String getFirmwareVersion() {
        return mWifiVendorHal.getFirmwareVersion();
    }

    public static class RingBufferStatus{
        String name;
        int flag;
        int ringBufferId;
        int ringBufferByteSize;
        int verboseLevel;
        int writtenBytes;
        int readBytes;
        int writtenRecords;

        // Bit masks for interpreting |flag|
        public static final int HAS_BINARY_ENTRIES = (1 << 0);
        public static final int HAS_ASCII_ENTRIES = (1 << 1);
        public static final int HAS_PER_PACKET_ENTRIES = (1 << 2);

        @Override
        public String toString() {
            return "name: " + name + " flag: " + flag + " ringBufferId: " + ringBufferId +
                    " ringBufferByteSize: " +ringBufferByteSize + " verboseLevel: " +verboseLevel +
                    " writtenBytes: " + writtenBytes + " readBytes: " + readBytes +
                    " writtenRecords: " + writtenRecords;
        }
    }

    /**
     * API to get the status of all ring buffers supported by driver
     */
    public RingBufferStatus[] getRingBufferStatus() {
        return mWifiVendorHal.getRingBufferStatus();
    }

    /**
     * Indicates to driver that all the data has to be uploaded urgently
     *
     * @param ringName Name of the ring buffer requested.
     * @return true on success, false otherwise.
     */
    public boolean getRingBufferData(String ringName) {
        return mWifiVendorHal.getRingBufferData(ringName);
    }

    /**
     * Request vendor debug info from the firmware
     *
     * @return Raw data obtained from the HAL.
     */
    public byte[] getFwMemoryDump() {
        return mWifiVendorHal.getFwMemoryDump();
    }

    /**
     * Request vendor debug info from the driver
     *
     * @return Raw data obtained from the HAL.
     */
    public byte[] getDriverStateDump() {
        return mWifiVendorHal.getDriverStateDump();
    }

    //---------------------------------------------------------------------------------
    /* Packet fate API */

    @Immutable
    abstract static class FateReport {
        final static int USEC_PER_MSEC = 1000;
        // The driver timestamp is a 32-bit counter, in microseconds. This field holds the
        // maximal value of a driver timestamp in milliseconds.
        final static int MAX_DRIVER_TIMESTAMP_MSEC = (int) (0xffffffffL / 1000);
        final static SimpleDateFormat dateFormatter = new SimpleDateFormat("HH:mm:ss.SSS");

        final byte mFate;
        final long mDriverTimestampUSec;
        final byte mFrameType;
        final byte[] mFrameBytes;
        final long mEstimatedWallclockMSec;

        FateReport(byte fate, long driverTimestampUSec, byte frameType, byte[] frameBytes) {
            mFate = fate;
            mDriverTimestampUSec = driverTimestampUSec;
            mEstimatedWallclockMSec =
                    convertDriverTimestampUSecToWallclockMSec(mDriverTimestampUSec);
            mFrameType = frameType;
            mFrameBytes = frameBytes;
        }

        public String toTableRowString() {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            FrameParser parser = new FrameParser(mFrameType, mFrameBytes);
            dateFormatter.setTimeZone(TimeZone.getDefault());
            pw.format("%-15s  %12s  %-9s  %-32s  %-12s  %-23s  %s\n",
                    mDriverTimestampUSec,
                    dateFormatter.format(new Date(mEstimatedWallclockMSec)),
                    directionToString(), fateToString(), parser.mMostSpecificProtocolString,
                    parser.mTypeString, parser.mResultString);
            return sw.toString();
        }

        public String toVerboseStringWithPiiAllowed() {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            FrameParser parser = new FrameParser(mFrameType, mFrameBytes);
            pw.format("Frame direction: %s\n", directionToString());
            pw.format("Frame timestamp: %d\n", mDriverTimestampUSec);
            pw.format("Frame fate: %s\n", fateToString());
            pw.format("Frame type: %s\n", frameTypeToString(mFrameType));
            pw.format("Frame protocol: %s\n", parser.mMostSpecificProtocolString);
            pw.format("Frame protocol type: %s\n", parser.mTypeString);
            pw.format("Frame length: %d\n", mFrameBytes.length);
            pw.append("Frame bytes");
            pw.append(HexDump.dumpHexString(mFrameBytes));  // potentially contains PII
            pw.append("\n");
            return sw.toString();
        }

        /* Returns a header to match the output of toTableRowString(). */
        public static String getTableHeader() {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.format("\n%-15s  %-12s  %-9s  %-32s  %-12s  %-23s  %s\n",
                    "Time usec", "Walltime", "Direction", "Fate", "Protocol", "Type", "Result");
            pw.format("%-15s  %-12s  %-9s  %-32s  %-12s  %-23s  %s\n",
                    "---------", "--------", "---------", "----", "--------", "----", "------");
            return sw.toString();
        }

        protected abstract String directionToString();

        protected abstract String fateToString();

        private static String frameTypeToString(byte frameType) {
            switch (frameType) {
                case WifiLoggerHal.FRAME_TYPE_UNKNOWN:
                    return "unknown";
                case WifiLoggerHal.FRAME_TYPE_ETHERNET_II:
                    return "data";
                case WifiLoggerHal.FRAME_TYPE_80211_MGMT:
                    return "802.11 management";
                default:
                    return Byte.toString(frameType);
            }
        }

        /**
         * Converts a driver timestamp to a wallclock time, based on the current
         * BOOTTIME to wallclock mapping. The driver timestamp is a 32-bit counter of
         * microseconds, with the same base as BOOTTIME.
         */
        private static long convertDriverTimestampUSecToWallclockMSec(long driverTimestampUSec) {
            final long wallclockMillisNow = System.currentTimeMillis();
            final long boottimeMillisNow = SystemClock.elapsedRealtime();
            final long driverTimestampMillis = driverTimestampUSec / USEC_PER_MSEC;

            long boottimeTimestampMillis = boottimeMillisNow % MAX_DRIVER_TIMESTAMP_MSEC;
            if (boottimeTimestampMillis < driverTimestampMillis) {
                // The 32-bit microsecond count has wrapped between the time that the driver
                // recorded the packet, and the call to this function. Adjust the BOOTTIME
                // timestamp, to compensate.
                //
                // Note that overflow is not a concern here, since the result is less than
                // 2 * MAX_DRIVER_TIMESTAMP_MSEC. (Given the modulus operation above,
                // boottimeTimestampMillis must be less than MAX_DRIVER_TIMESTAMP_MSEC.) And, since
                // MAX_DRIVER_TIMESTAMP_MSEC is an int, 2 * MAX_DRIVER_TIMESTAMP_MSEC must fit
                // within a long.
                boottimeTimestampMillis += MAX_DRIVER_TIMESTAMP_MSEC;
            }

            final long millisSincePacketTimestamp = boottimeTimestampMillis - driverTimestampMillis;
            return wallclockMillisNow - millisSincePacketTimestamp;
        }
    }

    /**
     * Represents the fate information for one outbound packet.
     */
    @Immutable
    public static final class TxFateReport extends FateReport {
        TxFateReport(byte fate, long driverTimestampUSec, byte frameType, byte[] frameBytes) {
            super(fate, driverTimestampUSec, frameType, frameBytes);
        }

        @Override
        protected String directionToString() {
            return "TX";
        }

        @Override
        protected String fateToString() {
            switch (mFate) {
                case WifiLoggerHal.TX_PKT_FATE_ACKED:
                    return "acked";
                case WifiLoggerHal.TX_PKT_FATE_SENT:
                    return "sent";
                case WifiLoggerHal.TX_PKT_FATE_FW_QUEUED:
                    return "firmware queued";
                case WifiLoggerHal.TX_PKT_FATE_FW_DROP_INVALID:
                    return "firmware dropped (invalid frame)";
                case WifiLoggerHal.TX_PKT_FATE_FW_DROP_NOBUFS:
                    return "firmware dropped (no bufs)";
                case WifiLoggerHal.TX_PKT_FATE_FW_DROP_OTHER:
                    return "firmware dropped (other)";
                case WifiLoggerHal.TX_PKT_FATE_DRV_QUEUED:
                    return "driver queued";
                case WifiLoggerHal.TX_PKT_FATE_DRV_DROP_INVALID:
                    return "driver dropped (invalid frame)";
                case WifiLoggerHal.TX_PKT_FATE_DRV_DROP_NOBUFS:
                    return "driver dropped (no bufs)";
                case WifiLoggerHal.TX_PKT_FATE_DRV_DROP_OTHER:
                    return "driver dropped (other)";
                default:
                    return Byte.toString(mFate);
            }
        }
    }

    /**
     * Represents the fate information for one inbound packet.
     */
    @Immutable
    public static final class RxFateReport extends FateReport {
        RxFateReport(byte fate, long driverTimestampUSec, byte frameType, byte[] frameBytes) {
            super(fate, driverTimestampUSec, frameType, frameBytes);
        }

        @Override
        protected String directionToString() {
            return "RX";
        }

        @Override
        protected String fateToString() {
            switch (mFate) {
                case WifiLoggerHal.RX_PKT_FATE_SUCCESS:
                    return "success";
                case WifiLoggerHal.RX_PKT_FATE_FW_QUEUED:
                    return "firmware queued";
                case WifiLoggerHal.RX_PKT_FATE_FW_DROP_FILTER:
                    return "firmware dropped (filter)";
                case WifiLoggerHal.RX_PKT_FATE_FW_DROP_INVALID:
                    return "firmware dropped (invalid frame)";
                case WifiLoggerHal.RX_PKT_FATE_FW_DROP_NOBUFS:
                    return "firmware dropped (no bufs)";
                case WifiLoggerHal.RX_PKT_FATE_FW_DROP_OTHER:
                    return "firmware dropped (other)";
                case WifiLoggerHal.RX_PKT_FATE_DRV_QUEUED:
                    return "driver queued";
                case WifiLoggerHal.RX_PKT_FATE_DRV_DROP_FILTER:
                    return "driver dropped (filter)";
                case WifiLoggerHal.RX_PKT_FATE_DRV_DROP_INVALID:
                    return "driver dropped (invalid frame)";
                case WifiLoggerHal.RX_PKT_FATE_DRV_DROP_NOBUFS:
                    return "driver dropped (no bufs)";
                case WifiLoggerHal.RX_PKT_FATE_DRV_DROP_OTHER:
                    return "driver dropped (other)";
                default:
                    return Byte.toString(mFate);
            }
        }
    }

    /**
     * Ask the HAL to enable packet fate monitoring. Fails unless HAL is started.
     *
     * @return true for success, false otherwise.
     */
    public boolean startPktFateMonitoring() {
        return mWifiVendorHal.startPktFateMonitoring();
    }

    /**
     * Fetch the most recent TX packet fates from the HAL. Fails unless HAL is started.
     *
     * @return true for success, false otherwise.
     */
    public boolean getTxPktFates(TxFateReport[] reportBufs) {
        return mWifiVendorHal.getTxPktFates(reportBufs);
    }

    /**
     * Fetch the most recent RX packet fates from the HAL. Fails unless HAL is started.
     */
    public boolean getRxPktFates(RxFateReport[] reportBufs) {
        return mWifiVendorHal.getRxPktFates(reportBufs);
    }

    /**
     * Set the PNO settings & the network list in HAL to start PNO.
     * @param settings PNO settings and network list.
     * @param eventHandler Handler to receive notifications back during PNO scan.
     * @return true if success, false otherwise
     */
    public boolean setPnoList(PnoSettings settings, PnoEventHandler eventHandler) {
        Log.e(mTAG, "setPnoList not supported");
        return false;
    }

    /**
     * Reset the PNO settings in HAL to stop PNO.
     * @return true if success, false otherwise
     */
    public boolean resetPnoList() {
        Log.e(mTAG, "resetPnoList not supported");
        return false;
    }

    /**
     * Start sending the specified keep alive packets periodically.
     *
     * @param slot Integer used to identify each request.
     * @param keepAlivePacket Raw packet contents to send.
     * @param period Period to use for sending these packets.
     * @return 0 for success, -1 for error
     */
    public int startSendingOffloadedPacket(int slot, KeepalivePacketData keepAlivePacket,
                                           int period) {
        String[] macAddrStr = getMacAddress().split(":");
        byte[] srcMac = new byte[6];
        for (int i = 0; i < 6; i++) {
            Integer hexVal = Integer.parseInt(macAddrStr[i], 16);
            srcMac[i] = hexVal.byteValue();
        }
        return mWifiVendorHal.startSendingOffloadedPacket(
                slot, srcMac, keepAlivePacket, period);
    }

    /**
     * Stop sending the specified keep alive packets.
     *
     * @param slot id - same as startSendingOffloadedPacket call.
     * @return 0 for success, -1 for error
     */
    public int stopSendingOffloadedPacket(int slot) {
        return mWifiVendorHal.stopSendingOffloadedPacket(slot);
    }

    public static interface WifiRssiEventHandler {
        void onRssiThresholdBreached(byte curRssi);
    }

    /**
     * Start RSSI monitoring on the currently connected access point.
     *
     * @param maxRssi          Maximum RSSI threshold.
     * @param minRssi          Minimum RSSI threshold.
     * @param rssiEventHandler Called when RSSI goes above maxRssi or below minRssi
     * @return 0 for success, -1 for failure
     */
    public int startRssiMonitoring(byte maxRssi, byte minRssi,
                                   WifiRssiEventHandler rssiEventHandler) {
        return mWifiVendorHal.startRssiMonitoring(maxRssi, minRssi, rssiEventHandler);
    }

    public int stopRssiMonitoring() {
        return mWifiVendorHal.stopRssiMonitoring();
    }

    /**
     * Fetch the host wakeup reasons stats from wlan driver.
     *
     * @return the |WifiWakeReasonAndCounts| object retrieved from the wlan driver.
     */
    public WifiWakeReasonAndCounts getWlanWakeReasonCount() {
        return mWifiVendorHal.getWlanWakeReasonCount();
    }

    /**
     * Enable/Disable Neighbour discovery offload functionality in the firmware.
     *
     * @param enabled true to enable, false to disable.
     * @return true for success, false otherwise.
     */
    public boolean configureNeighborDiscoveryOffload(boolean enabled) {
        return mWifiVendorHal.configureNeighborDiscoveryOffload(enabled);
    }

    // Firmware roaming control.

    /**
     * Class to retrieve firmware roaming capability parameters.
     */
    public static class RoamingCapabilities {
        public int  maxBlacklistSize;
        public int  maxWhitelistSize;
    }

    /**
     * Query the firmware roaming capabilities.
     * @return true for success, false otherwise.
     */
    public boolean getRoamingCapabilities(RoamingCapabilities capabilities) {
        return mWifiVendorHal.getRoamingCapabilities(capabilities);
    }

    /**
     * Macros for controlling firmware roaming.
     */
    public static final int DISABLE_FIRMWARE_ROAMING = 0;
    public static final int ENABLE_FIRMWARE_ROAMING = 1;

    /**
     * Enable/disable firmware roaming.
     *
     * @return error code returned from HAL.
     */
    public int enableFirmwareRoaming(int state) {
        return mWifiVendorHal.enableFirmwareRoaming(state);
    }

    /**
     * Class for specifying the roaming configurations.
     */
    public static class RoamingConfig {
        public ArrayList<String> blacklistBssids;
        public ArrayList<String> whitelistSsids;
    }

    /**
     * Set firmware roaming configurations.
     */
    public boolean configureRoaming(RoamingConfig config) {
        Log.d(mTAG, "configureRoaming ");
        return mWifiVendorHal.configureRoaming(config);
    }

    /**
     * Reset firmware roaming configuration.
     */
    public boolean resetRoamingConfiguration() {
        // Pass in an empty RoamingConfig object which translates to zero size
        // blacklist and whitelist to reset the firmware roaming configuration.
        return mWifiVendorHal.configureRoaming(new RoamingConfig());
    }

    /********************************************************
     * JNI operations
     ********************************************************/
    /* Register native functions */
    static {
        /* Native functions are defined in libwifi-service.so */
        System.loadLibrary("wifi-service");
        registerNatives();
    }

    private static native int registerNatives();
    /* kernel logging support */
    private static native byte[] readKernelLogNative();

    /**
     * Fetches the latest kernel logs.
     */
    public synchronized String readKernelLog() {
        byte[] bytes = readKernelLogNative();
        if (bytes != null) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            try {
                CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
                return decoded.toString();
            } catch (CharacterCodingException cce) {
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
        } else {
            return "*** failed to read kernel log ***";
        }
    }
}
