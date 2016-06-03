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

import android.net.IpConfiguration;
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.net.IpConfigStore;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.CharArrayReader;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import static android.net.IpConfiguration.IpAssignment;
import static android.net.IpConfiguration.ProxySettings;

/**
 * Class used to backup/restore data using the SettingsBackupAgent.
 * There are 2 symmetric API's exposed here:
 * 1. retrieveBackupDataFromConfigurations: Retrieve the configuration data to be backed up.
 * 2. retrieveConfigurationsFromBackupData: Restore the configuration using the provided data.
 * The byte stream to be backed up is XML encoded and versioned to migrate the data easily across
 * revisions.
 */
public class WifiBackupRestore {
    private static final String TAG = "WifiBackupRestore";

    /**
     * Current backup data version. This will be incremented for any additions.
     */
    private static final int CURRENT_BACKUP_DATA_VERSION = 1;

    /** This list of older versions will be used to restore data from older backups. */
    /**
     * First version of the backup data format.
     */
    private static final int INITIAL_BACKUP_DATA_VERSION = 1;

    /**
     * List of XML tags in the backed up data
     */
    private static final String XML_TAG_DOCUMENT_HEADER = "WifiBackupData";
    private static final String XML_TAG_VERSION = "Version";
    private static final String XML_TAG_SECTION_HEADER_CONFIGURATION_LIST = "ConfigurationList";
    private static final String XML_TAG_SECTION_HEADER_CONFIGURATION = "Configuration";
    private static final String XML_TAG_CONFIGURATION_SSID = "SSID";
    private static final String XML_TAG_CONFIGURATION_BSSID = "BSSID";
    private static final String XML_TAG_CONFIGURATION_CONFIG_KEY = "ConfigKey";
    private static final String XML_TAG_CONFIGURATION_PRE_SHARED_KEY = "PreSharedKey";
    private static final String XML_TAG_CONFIGURATION_WEP_KEYS = "WEPKeys";
    private static final String XML_TAG_CONFIGURATION_WEP_TX_KEY_INDEX = "WEPTxKeyIndex";
    private static final String XML_TAG_CONFIGURATION_HIDDEN_SSID = "HiddenSSID";
    private static final String XML_TAG_CONFIGURATION_ALLOWED_KEY_MGMT = "AllowedKeyMgmt";
    private static final String XML_TAG_CONFIGURATION_ALLOWED_PROTOCOLS = "AllowedProtocols";
    private static final String XML_TAG_CONFIGURATION_ALLOWED_AUTH_ALGOS = "AllowedAuthAlgos";
    private static final String XML_TAG_CONFIGURATION_SHARED = "Shared";
    private static final String XML_TAG_CONFIGURATION_CREATOR_UID = "CreatorUid";
    private static final String XML_TAG_SECTION_HEADER_IP_CONFIGURATION = "IpConfiguration";
    private static final String XML_TAG_IP_CONFIGURATION_IP_ASSIGNMENT = "IpAssignment";
    private static final String XML_TAG_IP_CONFIGURATION_LINK_ADDRESS = "LinkAddress";
    private static final String XML_TAG_IP_CONFIGURATION_LINK_PREFIX_LENGTH = "LinkPrefixLength";
    private static final String XML_TAG_IP_CONFIGURATION_GATEWAY_ADDRESS = "GatewayAddress";
    private static final String XML_TAG_IP_CONFIGURATION_DNS_SERVER_ADDRESSES = "DNSServers";
    private static final String XML_TAG_IP_CONFIGURATION_PROXY_SETTINGS = "ProxySettings";
    private static final String XML_TAG_IP_CONFIGURATION_PROXY_HOST = "ProxyHost";
    private static final String XML_TAG_IP_CONFIGURATION_PROXY_PORT = "ProxyPort";
    private static final String XML_TAG_IP_CONFIGURATION_PROXY_PAC_FILE = "ProxyPac";
    private static final String XML_TAG_IP_CONFIGURATION_PROXY_EXCLUSION_LIST = "ProxyExclusionList";

    /**
     * Regex to mask out passwords in backup data dump.
     */
    private static final String PASSWORD_MASK_SEARCH_PATTERN =
            "(<.*" + XML_TAG_CONFIGURATION_PRE_SHARED_KEY + ".*>)(.*)(<.*>)";
    private static final String PASSWORD_MASK_REPLACE_PATTERN = "$1*$3";

    /**
     * Verbose logging flag.
     */
    private boolean mVerboseLoggingEnabled = false;

    /**
     * Store the dump of the backup/restore data for debugging. This is only stored when verbose
     * logging is enabled in developer options.
     */
    private byte[] mDebugLastBackupDataRetrieved;
    private byte[] mDebugLastBackupDataRestored;
    private byte[] mDebugLastSupplicantBackupDataRestored;

    /**
     * Retrieve an XML byte stream representing the data that needs to be backed up from the
     * provided configurations.
     *
     * @param configurations list of currently saved networks that needs to be backed up.
     * @return Raw byte stream of XML that needs to be backed up.
     */
    public byte[] retrieveBackupDataFromConfigurations(List<WifiConfiguration> configurations) {
        if (configurations == null) {
            Log.e(TAG, "Invalid configuration list received");
            return null;
        }

        try {
            final XmlSerializer out = new FastXmlSerializer();
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            out.setOutput(outputStream, StandardCharsets.UTF_8.name());

            // Start writing the XML stream.
            XmlUtil.writeDocumentStart(out, XML_TAG_DOCUMENT_HEADER);

            XmlUtil.writeNextValue(out, XML_TAG_VERSION, CURRENT_BACKUP_DATA_VERSION);

            writeWifiConfigurationsToXml(out, configurations);

            XmlUtil.writeDocumentEnd(out, XML_TAG_DOCUMENT_HEADER);

            byte[] data = outputStream.toByteArray();

            if (mVerboseLoggingEnabled) {
                mDebugLastBackupDataRetrieved = data;
            }

            return data;
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Error retrieving the backup data: " + e);
        } catch (IOException e) {
            Log.e(TAG, "Error retrieving the backup data: " + e);
        }
        return null;
    }

    /**
     * Write the list of configurations to the XML stream.
     */
    private void writeWifiConfigurationsToXml(
            XmlSerializer out, List<WifiConfiguration> configurations)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_CONFIGURATION_LIST);
        for (WifiConfiguration configuration : configurations) {
            // We don't want to backup/restore enterprise/passpoint configurations.
            if (configuration.isEnterprise() || configuration.isPasspoint()) {
                Log.d(TAG, "Skipping enterprise config for backup: " + configuration.configKey());
                continue;
            }
            // Write this configuration data now.
            XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_CONFIGURATION);
            writeWifiConfigurationToXml(out, configuration);
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_CONFIGURATION);
        }
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_CONFIGURATION_LIST);
    }

    /**
     * Write WepKeys to the XML stream.
     * WepKeys array is intialized in WifiConfiguration constructor, but all of the elements
     * are null. XmlUtils serialization doesn't handle this array of nulls well .
     * So, write null if the keys are not initialized.
     */
    private void writeWepKeysToXml(XmlSerializer out, String[] wepKeys)
            throws XmlPullParserException, IOException {
        if (wepKeys[0] != null) {
            XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_WEP_KEYS, wepKeys);
        } else {
            XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_WEP_KEYS, null);
        }
    }

    /**
     * Write the configuration data elements from the provided Configuration to the XML stream.
     * Uses XmlUtils to write the values of each element.
     */
    private void writeWifiConfigurationToXml(XmlSerializer out, WifiConfiguration configuration)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_CONFIG_KEY, configuration.configKey());
        XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_SSID, configuration.SSID);
        XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_BSSID, configuration.BSSID);
        XmlUtil.writeNextValue(
                out, XML_TAG_CONFIGURATION_PRE_SHARED_KEY, configuration.preSharedKey);
        writeWepKeysToXml(out, configuration.wepKeys);
        XmlUtil.writeNextValue(
                out, XML_TAG_CONFIGURATION_WEP_TX_KEY_INDEX, configuration.wepTxKeyIndex);
        XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_HIDDEN_SSID, configuration.hiddenSSID);
        XmlUtil.writeNextValue(
                out, XML_TAG_CONFIGURATION_ALLOWED_KEY_MGMT,
                configuration.allowedKeyManagement.toByteArray());
        XmlUtil.writeNextValue(
                out, XML_TAG_CONFIGURATION_ALLOWED_PROTOCOLS,
                configuration.allowedProtocols.toByteArray());
        XmlUtil.writeNextValue(
                out, XML_TAG_CONFIGURATION_ALLOWED_AUTH_ALGOS,
                configuration.allowedAuthAlgorithms.toByteArray());
        XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_SHARED, configuration.shared);
        XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_CREATOR_UID, configuration.creatorUid);

        if (configuration.getIpConfiguration() != null) {
            XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_IP_CONFIGURATION);
            writeIpConfigurationToXml(out, configuration.getIpConfiguration());
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_IP_CONFIGURATION);
        }
    }

    /**
     * Write the static IP configuration data elements to XML stream
     */
    private void writeStaticIpConfigurationToXml(XmlSerializer out,
            StaticIpConfiguration staticIpConfiguration)
            throws XmlPullParserException, IOException {
        if (staticIpConfiguration.ipAddress != null) {
            XmlUtil.writeNextValue(
                    out, XML_TAG_IP_CONFIGURATION_LINK_ADDRESS,
                    staticIpConfiguration.ipAddress.getAddress().getHostAddress());
            XmlUtil.writeNextValue(
                    out, XML_TAG_IP_CONFIGURATION_LINK_PREFIX_LENGTH,
                    staticIpConfiguration.ipAddress.getPrefixLength());
        } else {
            XmlUtil.writeNextValue(
                    out, XML_TAG_IP_CONFIGURATION_LINK_ADDRESS, null);
            XmlUtil.writeNextValue(
                    out, XML_TAG_IP_CONFIGURATION_LINK_PREFIX_LENGTH, null);
        }
        if (staticIpConfiguration.gateway != null) {
            XmlUtil.writeNextValue(
                    out, XML_TAG_IP_CONFIGURATION_GATEWAY_ADDRESS,
                    staticIpConfiguration.gateway.getHostAddress());
        } else {
            XmlUtil.writeNextValue(
                    out, XML_TAG_IP_CONFIGURATION_GATEWAY_ADDRESS, null);

        }
        if (staticIpConfiguration.dnsServers != null) {
            // Create a string array of DNS server addresses
            String[] dnsServers = new String[staticIpConfiguration.dnsServers.size()];
            int dnsServerIdx = 0;
            for (InetAddress inetAddr : staticIpConfiguration.dnsServers) {
                dnsServers[dnsServerIdx++] = inetAddr.getHostAddress();
            }
            XmlUtil.writeNextValue(
                    out, XML_TAG_IP_CONFIGURATION_DNS_SERVER_ADDRESSES, dnsServers);
        } else {
            XmlUtil.writeNextValue(
                    out, XML_TAG_IP_CONFIGURATION_DNS_SERVER_ADDRESSES, null);
        }
    }

    /**
     * Write the IP configuration data elements from the provided Configuration to the XML stream.
     * Uses XmlUtils to write the values of each element.
     */
    private void writeIpConfigurationToXml(XmlSerializer out, IpConfiguration ipConfiguration)
            throws XmlPullParserException, IOException {

        // Write IP assignment settings
        XmlUtil.writeNextValue(
                out, XML_TAG_IP_CONFIGURATION_IP_ASSIGNMENT,
                ipConfiguration.ipAssignment.toString());
        switch (ipConfiguration.ipAssignment) {
            case STATIC:
                writeStaticIpConfigurationToXml(out, ipConfiguration.getStaticIpConfiguration());
                break;
            default:
                break;
        }

        // Write proxy settings
        XmlUtil.writeNextValue(
                out, XML_TAG_IP_CONFIGURATION_PROXY_SETTINGS,
                ipConfiguration.proxySettings.toString());
        switch (ipConfiguration.proxySettings) {
            case STATIC:
                XmlUtil.writeNextValue(
                        out, XML_TAG_IP_CONFIGURATION_PROXY_HOST,
                        ipConfiguration.httpProxy.getHost());
                XmlUtil.writeNextValue(
                        out, XML_TAG_IP_CONFIGURATION_PROXY_PORT,
                        ipConfiguration.httpProxy.getPort());
                XmlUtil.writeNextValue(
                        out, XML_TAG_IP_CONFIGURATION_PROXY_EXCLUSION_LIST,
                        ipConfiguration.httpProxy.getExclusionListAsString());
                break;
            case PAC:
                XmlUtil.writeNextValue(
                        out, XML_TAG_IP_CONFIGURATION_PROXY_PAC_FILE,
                        ipConfiguration.httpProxy.getPacFileUrl().toString());
                break;
            default:
                break;
        }
    }

    /**
     * Parse out the configurations from the back up data.
     *
     * @param data raw byte stream representing the XML data.
     * @return list of networks retrieved from the backed up data.
     */
    public List<WifiConfiguration> retrieveConfigurationsFromBackupData(byte[] data) {
        if (data == null || data.length == 0) {
            Log.e(TAG, "Invalid backup data received");
            return null;
        }

        try {
            if (mVerboseLoggingEnabled) {
                mDebugLastBackupDataRestored = data;
            }

            final XmlPullParser in = Xml.newPullParser();
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            in.setInput(inputStream, StandardCharsets.UTF_8.name());

            // Start parsing the XML stream.
            XmlUtil.gotoDocumentStart(in, XML_TAG_DOCUMENT_HEADER);
            int rootTagDepth = in.getDepth();

            int version = (int) XmlUtil.readNextValue(in, XML_TAG_VERSION);
            if (version < INITIAL_BACKUP_DATA_VERSION || version > CURRENT_BACKUP_DATA_VERSION) {
                Log.e(TAG, "Invalid version of data: " + version);
                return null;
            }

            return parseWifiConfigurationsFromXml(in, rootTagDepth, version);
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Error parsing the backup data: " + e);
        } catch (IOException e) {
            Log.e(TAG, "Error parsing the backup data: " + e);
        }
        return null;
    }

    /**
     * Parses the list of configurations from the provided XML stream.
     *
     * @param in            XmlPullParser instance pointing to the XML stream.
     * @param outerTagDepth depth of the outer tag in the XML document.
     * @param dataVersion   version number parsed from incoming data.
     * @return List<WifiConfiguration> object if parsing is successful, null otherwise.
     */
    private List<WifiConfiguration> parseWifiConfigurationsFromXml(
            XmlPullParser in, int outerTagDepth, int dataVersion)
            throws XmlPullParserException, IOException {
        // Find the configuration list section.
        if (!XmlUtil.gotoNextSection(
                in, XML_TAG_SECTION_HEADER_CONFIGURATION_LIST, outerTagDepth)) {
            Log.e(TAG, "Error parsing the backup data. Did not find configuration list");
            // Malformed XML input, bail out.
            return null;
        }
        // Find all the configurations within the configuration list section.
        int confListTagDepth = outerTagDepth + 1;
        List<WifiConfiguration> configurations = new ArrayList<>();
        while (XmlUtil.gotoNextSection(
                in, XML_TAG_SECTION_HEADER_CONFIGURATION, confListTagDepth)) {
            WifiConfiguration configuration =
                    parseWifiConfigurationFromXml(in, dataVersion, confListTagDepth);
            if (configuration != null) {
                Log.v(TAG, "Parsed Configuration: " + configuration.configKey());
                configurations.add(configuration);
            }
        }
        return configurations;
    }

    /**
     * Parse WepKeys from the XML stream.
     * Populate wepKeys array only if they were present in the backup data.
     */
    private void parseWepKeysFromXml(XmlPullParser in, String[] wepKeys)
            throws XmlPullParserException, IOException {
        String[] wepKeysInData =
                (String[]) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_WEP_KEYS);
        if (wepKeysInData != null) {
            for (int i = 0; i < wepKeys.length; i++) {
                wepKeys[i] = wepKeysInData[i];
            }
        }
    }

    /**
     * Parses the configuration data elements from the provided XML stream to a Configuration.
     *
     * @param in            XmlPullParser instance pointing to the XML stream.
     * @param outerTagDepth depth of the outer tag in the XML document.
     * @param dataVersion   version number parsed from incoming data.
     * @return WifiConfiguration object if parsing is successful, null otherwise.
     */
    private WifiConfiguration parseWifiConfigurationFromXml(XmlPullParser in, int dataVersion,
            int outerTagDepth)
            throws XmlPullParserException, IOException {

        // Any version migration needs to be handled here in future.
        if (dataVersion == INITIAL_BACKUP_DATA_VERSION) {
            WifiConfiguration configuration = new WifiConfiguration();
            String configKeyInData =
                    (String) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_CONFIG_KEY);
            configuration.SSID =
                    (String) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_SSID);
            configuration.BSSID =
                    (String) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_BSSID);
            configuration.preSharedKey =
                    (String) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_PRE_SHARED_KEY);
            parseWepKeysFromXml(in, configuration.wepKeys);
            configuration.wepTxKeyIndex =
                    (int) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_WEP_TX_KEY_INDEX);
            configuration.hiddenSSID =
                    (boolean) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_HIDDEN_SSID);
            byte[] allowedKeyMgmt =
                    (byte[]) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_ALLOWED_KEY_MGMT);
            configuration.allowedKeyManagement = BitSet.valueOf(allowedKeyMgmt);
            byte[] allowedProtocols =
                    (byte[]) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_ALLOWED_PROTOCOLS);
            configuration.allowedProtocols = BitSet.valueOf(allowedProtocols);
            byte[] allowedAuthAlgorithms =
                    (byte[]) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_ALLOWED_AUTH_ALGOS);
            configuration.allowedAuthAlgorithms = BitSet.valueOf(allowedAuthAlgorithms);
            configuration.shared =
                    (boolean) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_SHARED);
            configuration.creatorUid =
                    (int) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_CREATOR_UID);

            // We should not have all the data to calculate the configKey. Compare it against the
            // configKey stored in the XML data.
            String configKeyCalculated = configuration.configKey();
            if (!configKeyInData.equals(configKeyCalculated)) {
                Log.e(TAG, "Configuration key does not match. Retrieved: " + configKeyInData
                        + ", Calculated: " + configKeyCalculated);
                return null;
            }
            // Now retrieve any IP configuration info if present.
            int confTagDepth = outerTagDepth + 1;
            if (XmlUtil.gotoNextSection(
                    in, XML_TAG_SECTION_HEADER_IP_CONFIGURATION, confTagDepth)) {
                IpConfiguration ipConfiguration = parseIpConfigurationFromXml(in, dataVersion);
                configuration.setIpConfiguration(ipConfiguration);
            }
            return configuration;
        }
        return null;
    }

    /**
     * Parse out the static IP configuration from the XML stream.
     */
    private StaticIpConfiguration parseStaticIpConfigurationFromXml(XmlPullParser in)
            throws XmlPullParserException, IOException {

        StaticIpConfiguration staticIpConfiguration = new StaticIpConfiguration();
        String linkAddressString =
                (String) XmlUtil.readNextValue(in, XML_TAG_IP_CONFIGURATION_LINK_ADDRESS);
        Integer linkPrefixLength =
                (Integer) XmlUtil.readNextValue(in, XML_TAG_IP_CONFIGURATION_LINK_PREFIX_LENGTH);
        if (linkAddressString != null && linkPrefixLength != null) {
            LinkAddress linkAddress = new LinkAddress(
                    NetworkUtils.numericToInetAddress(linkAddressString),
                    linkPrefixLength);
            if (linkAddress.getAddress() instanceof Inet4Address) {
                staticIpConfiguration.ipAddress = linkAddress;
            } else {
                Log.w(TAG, "Non-IPv4 address: " + linkAddress);
            }
        }
        String gatewayAddressString =
                (String) XmlUtil.readNextValue(in, XML_TAG_IP_CONFIGURATION_GATEWAY_ADDRESS);
        if (gatewayAddressString != null) {
            LinkAddress dest = null;
            InetAddress gateway =
                    NetworkUtils.numericToInetAddress(gatewayAddressString);
            RouteInfo route = new RouteInfo(dest, gateway);
            if (route.isIPv4Default()) {
                staticIpConfiguration.gateway = gateway;
            } else {
                Log.w(TAG, "Non-IPv4 default route: " + route);
            }
        }
        String[] dnsServerAddressesString =
                (String[]) XmlUtil.readNextValue(in, XML_TAG_IP_CONFIGURATION_DNS_SERVER_ADDRESSES);
        if (dnsServerAddressesString != null) {
            for (String dnsServerAddressString : dnsServerAddressesString) {
                InetAddress dnsServerAddress =
                        NetworkUtils.numericToInetAddress(dnsServerAddressString);
                staticIpConfiguration.dnsServers.add(dnsServerAddress);
            }
        }
        return staticIpConfiguration;
    }

    /**
     * Parses the IP configuration data elements from the provided XML stream to a IpConfiguration.
     *
     * @param in          XmlPullParser instance pointing to the XML stream.
     * @param dataVersion version number parsed from incoming data.
     * @return IpConfiguration object if parsing is successful, null otherwise.
     */
    private IpConfiguration parseIpConfigurationFromXml(XmlPullParser in, int dataVersion)
            throws XmlPullParserException, IOException {

        // Any version migration needs to be handled here in future.
        if (dataVersion == INITIAL_BACKUP_DATA_VERSION) {
            IpConfiguration ipConfiguration = new IpConfiguration();

            // Parse out the IP assignment info first.
            String ipAssignmentString =
                    (String) XmlUtil.readNextValue(in, XML_TAG_IP_CONFIGURATION_IP_ASSIGNMENT);
            IpAssignment ipAssignment = IpAssignment.valueOf(ipAssignmentString);
            ipConfiguration.setIpAssignment(ipAssignment);
            switch (ipAssignment) {
                case STATIC:
                    StaticIpConfiguration staticIpConfiguration =
                            parseStaticIpConfigurationFromXml(in);
                    ipConfiguration.setStaticIpConfiguration(staticIpConfiguration);
                    break;
                case DHCP:
                case UNASSIGNED:
                    break;
                default:
                    Log.wtf(TAG, "Unknown ip assignment type: " + ipAssignment);
                    return null;
            }

            // Parse out the proxy settings next.
            String proxySettingsString =
                    (String) XmlUtil.readNextValue(in, XML_TAG_IP_CONFIGURATION_PROXY_SETTINGS);
            ProxySettings proxySettings = ProxySettings.valueOf(proxySettingsString);
            ipConfiguration.setProxySettings(proxySettings);
            switch (proxySettings) {
                case STATIC:
                    String proxyHost =
                            (String) XmlUtil.readNextValue(in, XML_TAG_IP_CONFIGURATION_PROXY_HOST);
                    int proxyPort =
                            (int) XmlUtil.readNextValue(in, XML_TAG_IP_CONFIGURATION_PROXY_PORT);
                    String proxyExclusionList =
                            (String) XmlUtil.readNextValue(in,
                                    XML_TAG_IP_CONFIGURATION_PROXY_EXCLUSION_LIST);
                    ipConfiguration.setHttpProxy(
                            new ProxyInfo(proxyHost, proxyPort, proxyExclusionList));
                    break;
                case PAC:
                    String proxyPacFile =
                            (String) XmlUtil.readNextValue(in,
                                    XML_TAG_IP_CONFIGURATION_PROXY_PAC_FILE);
                    ipConfiguration.setHttpProxy(new ProxyInfo(proxyPacFile));
                    break;
                case NONE:
                case UNASSIGNED:
                    break;
                default:
                    Log.wtf(TAG, "Unknown proxy settings type: " + proxySettings);
                    return null;
            }
            return ipConfiguration;
        }
        return null;
    }

    /**
     * Create log dump of the backup data in XML format with the preShared
     * key masked.
     */
    private String createLogFromBackupData(byte[] data) {
        String xmlString;
        try {
            xmlString = new String(data, StandardCharsets.UTF_8.name());
            xmlString = xmlString.replaceAll(
                    PASSWORD_MASK_SEARCH_PATTERN, PASSWORD_MASK_REPLACE_PATTERN);
        } catch (UnsupportedEncodingException e) {
            return "";
        }
        return xmlString;
    }

    /**
     * Restore state from the older supplicant back up data.
     * The old backup data was essentially a backup of wpa_supplicant.conf & ipconfig.txt file.
     *
     * @param supplicantData Raw byte stream of wpa_supplicant.conf
     * @param ipConfigData   Raw byte stream of ipconfig.txt
     * @return list of networks retrieved from the backed up data.
     */
    public List<WifiConfiguration> retrieveConfigurationsFromSupplicantBackupData(
            byte[] supplicantData, byte[] ipConfigData) {
        if (supplicantData == null || ipConfigData == null
                || supplicantData.length == 0 || ipConfigData.length == 0) {
            Log.e(TAG, "Invalid supplicant backup data received");
            return null;
        }

        if (mVerboseLoggingEnabled) {
            mDebugLastSupplicantBackupDataRestored = supplicantData;
        }

        SupplicantBackupMigration.SupplicantNetworks supplicantNetworks =
                new SupplicantBackupMigration.SupplicantNetworks();
        // Incorporate the networks present in the backup data.
        char[] restoredAsChars = new char[supplicantData.length];
        for (int i = 0; i < supplicantData.length; i++) {
            restoredAsChars[i] = (char) supplicantData[i];
        }

        BufferedReader in = new BufferedReader(new CharArrayReader(restoredAsChars));
        supplicantNetworks.readNetworksFromStream(in);

        // Retrieve corresponding WifiConfiguration objects.
        List<WifiConfiguration> configurations = supplicantNetworks.retrieveWifiConfigurations();

        // Now retrieve all the IpConfiguration objects and set in the corresponding
        // WifiConfiguration objects.
        SparseArray<IpConfiguration> networks =
                IpConfigStore.readIpAndProxyConfigurations(new ByteArrayInputStream(ipConfigData));
        if (networks != null) {
            for (int i = 0; i < networks.size(); i++) {
                int id = networks.keyAt(i);
                for (WifiConfiguration configuration : configurations) {
                    // This is a dangerous lookup, but that's how it is currently written.
                    if (configuration.configKey().hashCode() == id) {
                        configuration.setIpConfiguration(networks.valueAt(i));
                    }
                }
            }
        } else {
            Log.e(TAG, "Invalid Ip config data");
        }
        return configurations;
    }

    /**
     * Enable verbose logging.
     *
     * @param verbose verbosity level.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = (verbose > 0);
        if (!mVerboseLoggingEnabled) {
            mDebugLastBackupDataRetrieved = null;
            mDebugLastBackupDataRestored = null;
            mDebugLastSupplicantBackupDataRestored = null;
        }
    }

    /**
     * Dump out the last backup/restore data if verbose logging is enabled.
     *
     * @param fd   unused
     * @param pw   PrintWriter for writing dump to
     * @param args unused
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiBackupRestore");
        if (mDebugLastBackupDataRetrieved != null) {
            pw.println("Last backup data retrieved: " +
                    createLogFromBackupData(mDebugLastBackupDataRetrieved));
        }
        if (mDebugLastBackupDataRestored != null) {
            pw.println("Last backup data restored: " +
                    createLogFromBackupData(mDebugLastBackupDataRestored));
        }
        if (mDebugLastSupplicantBackupDataRestored != null) {
            pw.println("Last old backup data restored: " +
                    SupplicantBackupMigration.createLogFromBackupData(
                            mDebugLastSupplicantBackupDataRestored));
        }
    }

    /**
     * These sub classes contain the logic to parse older backups and restore wifi state from it.
     * Most of the code here has been migrated over from BackupSettingsAgent.
     * This is kind of ugly text parsing, but it is needed to support the migration of this data.
     */
    public static class SupplicantBackupMigration {
        /**
         * List of keys to look out for in wpa_supplicant.conf parsing.
         * These key values are declared in different parts of the wifi codebase today.
         */
        @VisibleForTesting
        public static final String SUPPLICANT_KEY_SSID = WifiConfiguration.ssidVarName;
        public static final String SUPPLICANT_KEY_HIDDEN = WifiConfiguration.hiddenSSIDVarName;
        public static final String SUPPLICANT_KEY_KEY_MGMT = WifiConfiguration.KeyMgmt.varName;
        public static final String SUPPLICANT_KEY_CLIENT_CERT = WifiEnterpriseConfig.CLIENT_CERT_KEY;
        public static final String SUPPLICANT_KEY_CA_CERT = WifiEnterpriseConfig.CA_CERT_KEY;
        public static final String SUPPLICANT_KEY_CA_PATH = WifiEnterpriseConfig.CA_PATH_KEY;
        public static final String SUPPLICANT_KEY_EAP = WifiEnterpriseConfig.EAP_KEY;
        public static final String SUPPLICANT_KEY_PSK = WifiConfiguration.pskVarName;
        public static final String SUPPLICANT_KEY_WEP_KEY0 = WifiConfiguration.wepKeyVarNames[0];
        public static final String SUPPLICANT_KEY_WEP_KEY1 = WifiConfiguration.wepKeyVarNames[1];
        public static final String SUPPLICANT_KEY_WEP_KEY2 = WifiConfiguration.wepKeyVarNames[2];
        public static final String SUPPLICANT_KEY_WEP_KEY3 = WifiConfiguration.wepKeyVarNames[3];
        public static final String SUPPLICANT_KEY_WEP_KEY_IDX = WifiConfiguration.wepTxKeyIdxVarName;
        public static final String SUPPLICANT_KEY_ID_STR = WifiConfigStore.ID_STRING_VAR_NAME;

        /**
         * Regex to mask out passwords in backup data dump.
         */
        private static final String PASSWORD_MASK_SEARCH_PATTERN =
                "(.*" + SUPPLICANT_KEY_PSK + ".*=)(.*)";
        private static final String PASSWORD_MASK_REPLACE_PATTERN = "$1*";

        /**
         * Class for capturing a network definition from the wifi supplicant config file.
         */
        static class SupplicantNetwork {
            String ssid;
            String hidden;
            String key_mgmt;
            String psk;
            String[] wepKeys = new String[4];
            String wepTxKeyIdx;
            String id_str;
            boolean certUsed = false;
            boolean isEap = false;

            /**
             * Read lines from wpa_supplicant.conf stream for this network.
             */
            public static SupplicantNetwork readNetworkFromStream(BufferedReader in) {
                final SupplicantNetwork n = new SupplicantNetwork();
                String line;
                try {
                    while (in.ready()) {
                        line = in.readLine();
                        if (line == null || line.startsWith("}")) {
                            break;
                        }
                        n.parseLine(line);
                    }
                } catch (IOException e) {
                    return null;
                }
                return n;
            }

            /**
             * Parse a line from wpa_supplicant.conf stream for this network.
             */
            void parseLine(String line) {
                // Can't rely on particular whitespace patterns so strip leading/trailing.
                line = line.trim();
                if (line.isEmpty()) return; // only whitespace; drop the line.

                // Now parse the network block within wpa_supplicant.conf and store the important
                // lines for procesing later.
                if (line.startsWith(SUPPLICANT_KEY_SSID)) {
                    ssid = line;
                } else if (line.startsWith(SUPPLICANT_KEY_HIDDEN)) {
                    hidden = line;
                } else if (line.startsWith(SUPPLICANT_KEY_KEY_MGMT)) {
                    key_mgmt = line;
                    if (line.contains("EAP")) {
                        isEap = true;
                    }
                } else if (line.startsWith(SUPPLICANT_KEY_CLIENT_CERT)) {
                    certUsed = true;
                } else if (line.startsWith(SUPPLICANT_KEY_CA_CERT)) {
                    certUsed = true;
                } else if (line.startsWith(SUPPLICANT_KEY_CA_PATH)) {
                    certUsed = true;
                } else if (line.startsWith(SUPPLICANT_KEY_EAP)) {
                    isEap = true;
                } else if (line.startsWith(SUPPLICANT_KEY_PSK)) {
                    psk = line;
                } else if (line.startsWith(SUPPLICANT_KEY_WEP_KEY0)) {
                    wepKeys[0] = line;
                } else if (line.startsWith(SUPPLICANT_KEY_WEP_KEY1)) {
                    wepKeys[1] = line;
                } else if (line.startsWith(SUPPLICANT_KEY_WEP_KEY2)) {
                    wepKeys[2] = line;
                } else if (line.startsWith(SUPPLICANT_KEY_WEP_KEY3)) {
                    wepKeys[3] = line;
                } else if (line.startsWith(SUPPLICANT_KEY_WEP_KEY_IDX)) {
                    wepTxKeyIdx = line;
                } else if (line.startsWith(SUPPLICANT_KEY_ID_STR)) {
                    id_str = line;
                }
            }

            /**
             * Create WifiConfiguration object from the parsed data for this network.
             */
            public WifiConfiguration createWifiConfiguration() {
                if (ssid == null) {
                    // No SSID => malformed network definition
                    return null;
                }
                WifiConfiguration configuration = new WifiConfiguration();
                configuration.SSID = ssid.substring(ssid.indexOf('=') + 1);

                if (hidden != null) {
                    // Can't use Boolean.valueOf() because it works only for true/false.
                    configuration.hiddenSSID =
                            Integer.parseInt(hidden.substring(hidden.indexOf('=') + 1)) != 0;
                }
                if (key_mgmt == null) {
                    // no key_mgmt specified; this is defined as equivalent to "WPA-PSK WPA-EAP"
                    configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                    configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
                } else {
                    // Need to parse the key_mgmt line
                    final String bareKeyMgmt = key_mgmt.substring(key_mgmt.indexOf('=') + 1);
                    String[] typeStrings = bareKeyMgmt.split("\\s+");

                    // Parse out all the key management regimes permitted for this network.
                    // The literal strings here are the standard values permitted in
                    // wpa_supplicant.conf.
                    for (int i = 0; i < typeStrings.length; i++) {
                        final String ktype = typeStrings[i];
                        if (ktype.equals("NONE")) {
                            configuration.allowedKeyManagement.set(
                                    WifiConfiguration.KeyMgmt.NONE);
                        } else if (ktype.equals("WPA-PSK")) {
                            configuration.allowedKeyManagement.set(
                                    WifiConfiguration.KeyMgmt.WPA_PSK);
                        } else if (ktype.equals("WPA-EAP")) {
                            configuration.allowedKeyManagement.set(
                                    WifiConfiguration.KeyMgmt.WPA_EAP);
                        } else if (ktype.equals("IEEE8021X")) {
                            configuration.allowedKeyManagement.set(
                                    WifiConfiguration.KeyMgmt.IEEE8021X);
                        }
                    }
                }
                if (psk != null) {
                    configuration.preSharedKey = psk.substring(psk.indexOf('=') + 1);
                }
                if (wepKeys[0] != null) {
                    configuration.wepKeys[0] = wepKeys[0].substring(wepKeys[0].indexOf('=') + 1);
                }
                if (wepKeys[1] != null) {
                    configuration.wepKeys[1] = wepKeys[1].substring(wepKeys[1].indexOf('=') + 1);
                }
                if (wepKeys[2] != null) {
                    configuration.wepKeys[2] = wepKeys[2].substring(wepKeys[2].indexOf('=') + 1);
                }
                if (wepKeys[3] != null) {
                    configuration.wepKeys[3] = wepKeys[3].substring(wepKeys[3].indexOf('=') + 1);
                }
                if (wepTxKeyIdx != null) {
                    configuration.wepTxKeyIndex =
                            Integer.valueOf(wepTxKeyIdx.substring(wepTxKeyIdx.indexOf('=') + 1));
                }
                if (id_str != null) {
                    String id_string = id_str.substring(id_str.indexOf('=') + 1);
                    Map<String, String> extras = WifiNative.parseNetworkExtra(id_string);
                    configuration.creatorUid =
                            Integer.valueOf(extras.get(WifiConfigStore.ID_STRING_KEY_CREATOR_UID));
                    String configKey = extras.get(WifiConfigStore.ID_STRING_KEY_CONFIG_KEY);
                    if (!configKey.equals(configuration.configKey())) {
                        Log.e(TAG, "Configuration key does not match. Retrieved: " + configKey
                                + ", Calculated: " + configuration.configKey());
                        return null;
                    }
                }
                return configuration;
            }
        }

        /**
         * Ingest multiple wifi config fragments from wpa_supplicant.conf, looking for network={}
         * blocks and eliminating duplicates
         */
        static class SupplicantNetworks {
            final ArrayList<SupplicantNetwork> mNetworks = new ArrayList<>(8);

            /**
             * Parse the wpa_supplicant.conf file stream and add networks.
             */
            public void readNetworksFromStream(BufferedReader in) {
                try {
                    String line;
                    while (in.ready()) {
                        line = in.readLine();
                        if (line != null) {
                            // Parse out 'network=' decls so we can ignore duplicates
                            if (line.startsWith("network")) {
                                SupplicantNetwork net = SupplicantNetwork.readNetworkFromStream(in);
                                // Don't propagate EAP network definitions
                                if (net.isEap) {
                                    Log.d(TAG, "Skipping EAP network " + net.ssid + " / "
                                            + net.key_mgmt);
                                    continue;
                                }
                                mNetworks.add(net);
                            }
                        }
                    }
                } catch (IOException e) {
                    // whatever happened, we're done now
                }
            }

            /**
             * Retrieve a list of WifiConfiguration objects parsed from wpa_supplicant.conf
             *
             * @return
             */
            public List<WifiConfiguration> retrieveWifiConfigurations() {
                ArrayList<WifiConfiguration> wifiConfigurations = new ArrayList<>();
                for (SupplicantNetwork net : mNetworks) {
                    if (net.certUsed) {
                        // Networks that use certificates for authentication can't be restored
                        // because the certificates they need don't get restored (because they
                        // are stored in keystore, and can't be restored)
                        continue;
                    }

                    if (net.isEap) {
                        // Similarly, omit EAP network definitions to avoid propagating
                        // controlled enterprise network definitions.
                        continue;
                    }
                    WifiConfiguration wifiConfiguration = net.createWifiConfiguration();
                    if (wifiConfiguration != null) {
                        Log.v(TAG, "Parsed Configuration: " + wifiConfiguration.configKey());
                        wifiConfigurations.add(wifiConfiguration);
                    }
                }
                return wifiConfigurations;
            }
        }

        /**
         * Create log dump of the backup data in wpa_supplicant.conf format with the preShared
         * key masked.
         */
        public static String createLogFromBackupData(byte[] data) {
            String supplicantConfString;
            try {
                supplicantConfString = new String(data, StandardCharsets.UTF_8.name());
                supplicantConfString = supplicantConfString.replaceAll(
                        PASSWORD_MASK_SEARCH_PATTERN, PASSWORD_MASK_REPLACE_PATTERN);
            } catch (UnsupportedEncodingException e) {
                return "";
            }
            return supplicantConfString;
        }
    }
}
