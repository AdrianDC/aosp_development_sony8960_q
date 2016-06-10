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

package com.android.server.wifi.util;

import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.util.Log;
import android.util.Pair;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.BitSet;
import java.util.HashMap;

/**
 * Utils for manipulating XML data. This is essentially a wrapper over XmlUtils provided by core.
 * The utility provides methods to write/parse section headers and write/parse values.
 * This utility is designed for formatting the XML into the following format:
 * <Document Header>
 *  <Section 1 Header>
 *   <Value 1>
 *   <Value 2>
 *   ...
 *   <Sub Section 1 Header>
 *    <Value 1>
 *    <Value 2>
 *    ...
 *   </Sub Section 1 Header>
 *  </Section 1 Header>
 * </Document Header>
 */
public class XmlUtil {
    private static final String TAG = "WifiXmlUtil";

    /**
     * Ensure that the XML stream is at a start tag or the end of document.
     *
     * @throws XmlPullParserException if parsing errors occur.
     */
    private static void gotoStartTag(XmlPullParser in)
            throws XmlPullParserException, IOException {
        int type = in.getEventType();
        while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) {
            type = in.next();
        }
    }

    /**
     * Ensure that the XML stream is at an end tag or the end of document.
     *
     * @throws XmlPullParserException if parsing errors occur.
     */
    private static void gotoEndTag(XmlPullParser in)
            throws XmlPullParserException, IOException {
        int type = in.getEventType();
        while (type != XmlPullParser.END_TAG && type != XmlPullParser.END_DOCUMENT) {
            type = in.next();
        }
    }

    /**
     * Start processing the XML stream at the document header.
     *
     * @param in         XmlPullParser instance pointing to the XML stream.
     * @param headerName expected name for the start tag.
     * @throws XmlPullParserException if parsing errors occur.
     */
    public static void gotoDocumentStart(XmlPullParser in, String headerName)
            throws XmlPullParserException, IOException {
        XmlUtils.beginDocument(in, headerName);
    }

    /**
     * Move the XML stream to the next section header. The provided outerDepth is used to find
     * sub sections within that depth.
     *
     * @param in         XmlPullParser instance pointing to the XML stream.
     * @param headerName expected name for the start tag.
     * @param outerDepth Find section within this depth.
     * @return {@code true} if a start tag with the provided name is found, {@code false} otherwise
     * @throws XmlPullParserException if parsing errors occur.
     */
    public static boolean gotoNextSection(XmlPullParser in, String headerName, int outerDepth)
            throws XmlPullParserException, IOException {
        while (XmlUtils.nextElementWithin(in, outerDepth)) {
            if (in.getName().equals(headerName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the stream is at the end of a section of values. This moves the stream to next tag
     * and checks if it finds an end tag at the specified depth.
     *
     * @param in           XmlPullParser instance pointing to the XML stream.
     * @param sectionDepth depth of the start tag of this section. Used to match the end tag.
     * @return {@code true} if a end tag at the provided depth is found, {@code false} otherwise
     * @throws XmlPullParserException if parsing errors occur.
     */
    public static boolean isNextSectionEnd(XmlPullParser in, int sectionDepth)
            throws XmlPullParserException, IOException {
        return (in.nextTag() == XmlPullParser.END_TAG && in.getDepth() == sectionDepth);
    }

    /**
     * Read the current value in the XML stream using core XmlUtils and stores the retrieved
     * value name in the string provided. This method reads the value contained in current start
     * tag.
     * Note: Because there could be genuine null values being read from the XML, this method raises
     * an exception to indicate errors.
     *
     * @param in        XmlPullParser instance pointing to the XML stream.
     * @param valueName An array of one string, used to return the name attribute
     *                  of the value's tag.
     * @return value retrieved from the XML stream.
     * @throws XmlPullParserException if parsing errors occur.
     */
    public static Object readCurrentValue(XmlPullParser in, String[] valueName)
            throws XmlPullParserException, IOException {
        Object value = XmlUtils.readValueXml(in, valueName);
        // XmlUtils.readValue does not always move the stream to the end of the tag. So, move
        // it to the end tag before returning from here.
        gotoEndTag(in);
        return value;
    }

    /**
     * Read the next value in the XML stream using core XmlUtils and ensure that it matches the
     * provided name. This method moves the stream to the next start tag and reads the value
     * contained in it.
     * Note: Because there could be genuine null values being read from the XML, this method raises
     * an exception to indicate errors.
     *
     * @param in XmlPullParser instance pointing to the XML stream.
     * @return value retrieved from the XML stream.
     * @throws XmlPullParserException if the value read does not match |expectedName|,
     *                                or if parsing errors occur.
     */
    public static Object readNextValueWithName(XmlPullParser in, String expectedName)
            throws XmlPullParserException, IOException {
        String[] valueName = new String[1];
        XmlUtils.nextElement(in);
        Object value = readCurrentValue(in, valueName);
        if (valueName[0].equals(expectedName)) {
            return value;
        }
        throw new XmlPullParserException(
                "Value not found. Expected: " + expectedName + ", but got: " + valueName[0]);
    }

    /**
     * Write the XML document start with the provided document header name.
     *
     * @param out        XmlSerializer instance pointing to the XML stream.
     * @param headerName name for the start tag.
     */
    public static void writeDocumentStart(XmlSerializer out, String headerName)
            throws IOException {
        out.startDocument(null, true);
        out.startTag(null, headerName);
    }

    /**
     * Write the XML document end with the provided document header name.
     *
     * @param out        XmlSerializer instance pointing to the XML stream.
     * @param headerName name for the end tag.
     */
    public static void writeDocumentEnd(XmlSerializer out, String headerName)
            throws IOException {
        out.endTag(null, headerName);
        out.endDocument();
    }

    /**
     * Write a section start header tag with the provided section name.
     *
     * @param out        XmlSerializer instance pointing to the XML stream.
     * @param headerName name for the start tag.
     */
    public static void writeNextSectionStart(XmlSerializer out, String headerName)
            throws IOException {
        out.startTag(null, headerName);
    }

    /**
     * Write a section end header tag with the provided section name.
     *
     * @param out        XmlSerializer instance pointing to the XML stream.
     * @param headerName name for the end tag.
     */
    public static void writeNextSectionEnd(XmlSerializer out, String headerName)
            throws IOException {
        out.endTag(null, headerName);
    }

    /**
     * Write the value with the provided name in the XML stream using core XmlUtils.
     *
     * @param out   XmlSerializer instance pointing to the XML stream.
     * @param name  name of the value.
     * @param value value to be written.
     */
    public static void writeNextValue(XmlSerializer out, String name, Object value)
            throws XmlPullParserException, IOException {
        XmlUtils.writeValueXml(value, name, out);
    }

    /**
     * Utility class to serialize and deseriaize {@link WifiConfiguration} object to XML &
     * vice versa.
     * This is used by both {@link com.android.server.wifi.WifiConfigStore} &
     * {@link com.android.server.wifi.WifiBackupRestore} modules.
     * The |writeConfigurationToXml| has 2 versions, one for backup and one for config store.
     * There is only 1 version of |parseXmlToConfiguration| for both backup & config store.
     * The parse method is written so that any element added/deleted in future revisions can
     * be easily handled.
     */
    public static class WifiConfigurationXmlUtil {
        /**
         * List of XML tags corresponding to WifiConfiguration object elements.
         */
        public static final String XML_TAG_SSID = "SSID";
        public static final String XML_TAG_BSSID = "BSSID";
        public static final String XML_TAG_CONFIG_KEY = "ConfigKey";
        public static final String XML_TAG_PRE_SHARED_KEY = "PreSharedKey";
        public static final String XML_TAG_WEP_KEYS = "WEPKeys";
        public static final String XML_TAG_WEP_TX_KEY_INDEX = "WEPTxKeyIndex";
        public static final String XML_TAG_HIDDEN_SSID = "HiddenSSID";
        public static final String XML_TAG_ALLOWED_KEY_MGMT = "AllowedKeyMgmt";
        public static final String XML_TAG_ALLOWED_PROTOCOLS = "AllowedProtocols";
        public static final String XML_TAG_ALLOWED_AUTH_ALGOS = "AllowedAuthAlgos";
        public static final String XML_TAG_SHARED = "Shared";
        public static final String XML_TAG_FQDN = "FQDN";
        public static final String XML_TAG_PROVIDER_FRIENDLY_NAME = "ProviderFriendlyName";
        public static final String XML_TAG_LINKED_NETWORKS_LIST = "LinkedNetworksList";
        public static final String XML_TAG_DEFAULT_GW_MAC_ADDRESS = "DefaultGwMacAddress";
        public static final String XML_TAG_VALIDATED_INTERNET_ACCESS = "ValidatedInternetAccess";
        public static final String XML_TAG_NO_INTERNET_ACCESS_EXPECTED = "NoInternetAccessExpected";
        public static final String XML_TAG_USER_APPROVED = "UserApproved";
        public static final String XML_TAG_METERED_HINT = "MeteredHint";
        public static final String XML_TAG_USE_EXTERNAL_SCORES = "UseExternalScores";
        public static final String XML_TAG_NUM_ASSOCIATION = "NumAssociation";
        public static final String XML_TAG_CREATOR_UID = "CreatorUid";
        public static final String XML_TAG_CREATOR_NAME = "CreatorName";
        public static final String XML_TAG_CREATION_TIME = "CreationTime";
        public static final String XML_TAG_LAST_UPDATE_UID = "LastUpdateUid";
        public static final String XML_TAG_LAST_UPDATE_NAME = "LastUpdateName";
        public static final String XML_TAG_LAST_CONNECT_UID = "LastConnectUid";

        /**
         * Write WepKeys to the XML stream.
         * WepKeys array is intialized in WifiConfiguration constructor, but all of the elements
         * are set to null. User may chose to set any one of the key elements in WifiConfiguration.
         * XmlUtils serialization doesn't handle this array of nulls well .
         * So, write empty strings if some of the keys are not initialized and null if all of
         * the elements are empty.
         */
        private static void writeWepKeysToXml(XmlSerializer out, String[] wepKeys)
                throws XmlPullParserException, IOException {
            String[] wepKeysToWrite = new String[wepKeys.length];
            boolean hasWepKey = false;
            for (int i = 0; i < wepKeys.length; i++) {
                if (wepKeys[i] == null) {
                    wepKeysToWrite[i] = new String();
                } else {
                    wepKeysToWrite[i] = wepKeys[i];
                    hasWepKey = true;
                }
            }
            if (hasWepKey) {
                XmlUtil.writeNextValue(out, XML_TAG_WEP_KEYS, wepKeysToWrite);
            } else {
                XmlUtil.writeNextValue(out, XML_TAG_WEP_KEYS, null);
            }
        }

        /**
         * Write the Configuration data elements that are common for backup & config store to the
         * XML stream.
         *
         * @param out           XmlSerializer instance pointing to the XML stream.
         * @param configuration WifiConfiguration object to be serialized.
         */
        public static void writeCommonWifiConfigurationElementsToXml(XmlSerializer out,
                WifiConfiguration configuration)
                throws XmlPullParserException, IOException {
            XmlUtil.writeNextValue(out, XML_TAG_CONFIG_KEY, configuration.configKey());
            XmlUtil.writeNextValue(out, XML_TAG_SSID, configuration.SSID);
            XmlUtil.writeNextValue(out, XML_TAG_BSSID, configuration.BSSID);
            XmlUtil.writeNextValue(out, XML_TAG_PRE_SHARED_KEY, configuration.preSharedKey);
            writeWepKeysToXml(out, configuration.wepKeys);
            XmlUtil.writeNextValue(out, XML_TAG_WEP_TX_KEY_INDEX, configuration.wepTxKeyIndex);
            XmlUtil.writeNextValue(out, XML_TAG_HIDDEN_SSID, configuration.hiddenSSID);
            XmlUtil.writeNextValue(
                    out, XML_TAG_ALLOWED_KEY_MGMT,
                    configuration.allowedKeyManagement.toByteArray());
            XmlUtil.writeNextValue(
                    out, XML_TAG_ALLOWED_PROTOCOLS,
                    configuration.allowedProtocols.toByteArray());
            XmlUtil.writeNextValue(
                    out, XML_TAG_ALLOWED_AUTH_ALGOS,
                    configuration.allowedAuthAlgorithms.toByteArray());
            XmlUtil.writeNextValue(out, XML_TAG_SHARED, configuration.shared);
        }

        /**
         * Write the Configuration data elements for backup from the provided Configuration to the
         * XML stream.
         * Note: This is a subset of the elements serialized for config store.
         *
         * @param out           XmlSerializer instance pointing to the XML stream.
         * @param configuration WifiConfiguration object to be serialized.
         */
        public static void writeWifiConfigurationToXmlForBackup(XmlSerializer out,
                WifiConfiguration configuration)
                throws XmlPullParserException, IOException {
            writeCommonWifiConfigurationElementsToXml(out, configuration);
        }

        /**
         * Write the Configuration data elements for config store from the provided Configuration
         * to the XML stream.
         */
        public static void writeWifiConfigurationToXmlForConfigStore(XmlSerializer out,
                WifiConfiguration configuration)
                throws XmlPullParserException, IOException {
            writeCommonWifiConfigurationElementsToXml(out, configuration);
            // TODO: Will need to add more elements which needs to be persisted.
            XmlUtil.writeNextValue(out, XML_TAG_FQDN, configuration.FQDN);
            XmlUtil.writeNextValue(
                    out, XML_TAG_PROVIDER_FRIENDLY_NAME, configuration.providerFriendlyName);
            XmlUtil.writeNextValue(
                    out, XML_TAG_LINKED_NETWORKS_LIST, configuration.linkedConfigurations);
            XmlUtil.writeNextValue(
                    out, XML_TAG_DEFAULT_GW_MAC_ADDRESS, configuration.defaultGwMacAddress);
            XmlUtil.writeNextValue(
                    out, XML_TAG_VALIDATED_INTERNET_ACCESS, configuration.validatedInternetAccess);
            XmlUtil.writeNextValue(
                    out, XML_TAG_NO_INTERNET_ACCESS_EXPECTED,
                    configuration.noInternetAccessExpected);
            XmlUtil.writeNextValue(out, XML_TAG_USER_APPROVED, configuration.userApproved);
            XmlUtil.writeNextValue(out, XML_TAG_METERED_HINT, configuration.meteredHint);
            XmlUtil.writeNextValue(
                    out, XML_TAG_USE_EXTERNAL_SCORES, configuration.useExternalScores);
            XmlUtil.writeNextValue(out, XML_TAG_NUM_ASSOCIATION, configuration.numAssociation);
            XmlUtil.writeNextValue(out, XML_TAG_CREATOR_UID, configuration.creatorUid);
            XmlUtil.writeNextValue(out, XML_TAG_CREATOR_NAME, configuration.creatorName);
            XmlUtil.writeNextValue(out, XML_TAG_CREATION_TIME, configuration.creationTime);
            XmlUtil.writeNextValue(out, XML_TAG_LAST_UPDATE_UID, configuration.lastUpdateUid);
            XmlUtil.writeNextValue(out, XML_TAG_LAST_UPDATE_NAME, configuration.lastUpdateName);
            XmlUtil.writeNextValue(out, XML_TAG_LAST_CONNECT_UID, configuration.lastConnectUid);
        }

        /**
         * Populate wepKeys array elements only if they were non-empty in the backup data.
         *
         * @throws XmlPullParserException if parsing errors occur.
         */
        private static void populateWepKeysFromXmlValue(Object value, String[] wepKeys)
                throws XmlPullParserException, IOException {
            String[] wepKeysInData = (String[]) value;
            if (wepKeysInData == null) {
                return;
            }
            if (wepKeysInData.length != wepKeys.length) {
                throw new XmlPullParserException(
                        "Invalid Wep Keys length: " + wepKeysInData.length);
            }
            for (int i = 0; i < wepKeys.length; i++) {
                if (wepKeysInData[i].isEmpty()) {
                    wepKeys[i] = null;
                } else {
                    wepKeys[i] = wepKeysInData[i];
                }
            }
        }

        /**
         * Parses the configuration data elements from the provided XML stream to a
         * WifiConfiguration object.
         * Note: This is used for parsing both backup data and config store data. Looping through
         * the tags make it easy to add or remove elements in the future versions if needed.
         *
         * @param in            XmlPullParser instance pointing to the XML stream.
         * @param outerTagDepth depth of the outer tag in the XML document.
         * @return Pair<Config key, WifiConfiguration object> if parsing is successful,
         * null otherwise.
         */
        public static Pair<String, WifiConfiguration> parseWifiConfigurationFromXml(
                XmlPullParser in, int outerTagDepth)
                throws XmlPullParserException, IOException {
            WifiConfiguration configuration = new WifiConfiguration();
            String configKeyInData = null;

            // Loop through and parse out all the elements from the stream within this section.
            while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
                String[] valueName = new String[1];
                Object value = XmlUtil.readCurrentValue(in, valueName);
                if (valueName[0] == null) {
                    Log.e(TAG, "Missing value name");
                    return null;
                }
                switch (valueName[0]) {
                    case XML_TAG_CONFIG_KEY:
                        configKeyInData = (String) value;
                        break;
                    case XML_TAG_SSID:
                        configuration.SSID = (String) value;
                        break;
                    case XML_TAG_BSSID:
                        configuration.BSSID = (String) value;
                        break;
                    case XML_TAG_PRE_SHARED_KEY:
                        configuration.preSharedKey = (String) value;
                        break;
                    case XML_TAG_WEP_KEYS:
                        populateWepKeysFromXmlValue(value, configuration.wepKeys);
                        break;
                    case XML_TAG_WEP_TX_KEY_INDEX:
                        configuration.wepTxKeyIndex = (int) value;
                        break;
                    case XML_TAG_HIDDEN_SSID:
                        configuration.hiddenSSID = (boolean) value;
                        break;
                    case XML_TAG_ALLOWED_KEY_MGMT:
                        byte[] allowedKeyMgmt = (byte[]) value;
                        configuration.allowedKeyManagement = BitSet.valueOf(allowedKeyMgmt);
                        break;
                    case XML_TAG_ALLOWED_PROTOCOLS:
                        byte[] allowedProtocols = (byte[]) value;
                        configuration.allowedProtocols = BitSet.valueOf(allowedProtocols);
                        break;
                    case XML_TAG_ALLOWED_AUTH_ALGOS:
                        byte[] allowedAuthAlgorithms = (byte[]) value;
                        configuration.allowedAuthAlgorithms = BitSet.valueOf(allowedAuthAlgorithms);
                        break;
                    case XML_TAG_SHARED:
                        configuration.shared = (boolean) value;
                        break;
                    case XML_TAG_FQDN:
                        configuration.FQDN = (String) value;
                        break;
                    case XML_TAG_PROVIDER_FRIENDLY_NAME:
                        configuration.providerFriendlyName = (String) value;
                        break;
                    case XML_TAG_LINKED_NETWORKS_LIST:
                        configuration.linkedConfigurations = (HashMap<String, Integer>) value;
                        break;
                    case XML_TAG_DEFAULT_GW_MAC_ADDRESS:
                        configuration.defaultGwMacAddress = (String) value;
                        break;
                    case XML_TAG_VALIDATED_INTERNET_ACCESS:
                        configuration.validatedInternetAccess = (boolean) value;
                        break;
                    case XML_TAG_NO_INTERNET_ACCESS_EXPECTED:
                        configuration.noInternetAccessExpected = (boolean) value;
                        break;
                    case XML_TAG_USER_APPROVED:
                        configuration.userApproved = (int) value;
                        break;
                    case XML_TAG_METERED_HINT:
                        configuration.meteredHint = (boolean) value;
                        break;
                    case XML_TAG_USE_EXTERNAL_SCORES:
                        configuration.useExternalScores = (boolean) value;
                        break;
                    case XML_TAG_NUM_ASSOCIATION:
                        configuration.numAssociation = (int) value;
                        break;
                    case XML_TAG_CREATOR_UID:
                        configuration.creatorUid = (int) value;
                        break;
                    case XML_TAG_CREATOR_NAME:
                        configuration.creatorName = (String) value;
                        break;
                    case XML_TAG_CREATION_TIME:
                        configuration.creationTime = (String) value;
                        break;
                    case XML_TAG_LAST_UPDATE_UID:
                        configuration.lastUpdateUid = (int) value;
                        break;
                    case XML_TAG_LAST_UPDATE_NAME:
                        configuration.lastUpdateName = (String) value;
                        break;
                    case XML_TAG_LAST_CONNECT_UID:
                        configuration.lastConnectUid = (int) value;
                        break;
                    default:
                        Log.e(TAG, "Unknown value name found: " + valueName[0]);
                        return null;
                }
            }
            return Pair.create(configKeyInData, configuration);
        }
    }

    /**
     * Utility class to serialize and deseriaize {@link IpConfiguration} object to XML & vice versa.
     * This is used by both {@link com.android.server.wifi.WifiConfigStore} &
     * {@link com.android.server.wifi.WifiBackupRestore} modules.
     */
    public static class IpConfigurationXmlUtil {

        /**
         * List of XML tags corresponding to IpConfiguration object elements.
         */
        public static final String XML_TAG_IP_ASSIGNMENT = "IpAssignment";
        public static final String XML_TAG_LINK_ADDRESS = "LinkAddress";
        public static final String XML_TAG_LINK_PREFIX_LENGTH = "LinkPrefixLength";
        public static final String XML_TAG_GATEWAY_ADDRESS = "GatewayAddress";
        public static final String XML_TAG_DNS_SERVER_ADDRESSES = "DNSServers";
        public static final String XML_TAG_PROXY_SETTINGS = "ProxySettings";
        public static final String XML_TAG_PROXY_HOST = "ProxyHost";
        public static final String XML_TAG_PROXY_PORT = "ProxyPort";
        public static final String XML_TAG_PROXY_PAC_FILE = "ProxyPac";
        public static final String XML_TAG_PROXY_EXCLUSION_LIST = "ProxyExclusionList";

        /**
         * Write the static IP configuration data elements to XML stream.
         */
        private static void writeStaticIpConfigurationToXml(XmlSerializer out,
                StaticIpConfiguration staticIpConfiguration)
                throws XmlPullParserException, IOException {
            if (staticIpConfiguration.ipAddress != null) {
                XmlUtil.writeNextValue(
                        out, XML_TAG_LINK_ADDRESS,
                        staticIpConfiguration.ipAddress.getAddress().getHostAddress());
                XmlUtil.writeNextValue(
                        out, XML_TAG_LINK_PREFIX_LENGTH,
                        staticIpConfiguration.ipAddress.getPrefixLength());
            } else {
                XmlUtil.writeNextValue(
                        out, XML_TAG_LINK_ADDRESS, null);
                XmlUtil.writeNextValue(
                        out, XML_TAG_LINK_PREFIX_LENGTH, null);
            }
            if (staticIpConfiguration.gateway != null) {
                XmlUtil.writeNextValue(
                        out, XML_TAG_GATEWAY_ADDRESS,
                        staticIpConfiguration.gateway.getHostAddress());
            } else {
                XmlUtil.writeNextValue(
                        out, XML_TAG_GATEWAY_ADDRESS, null);

            }
            if (staticIpConfiguration.dnsServers != null) {
                // Create a string array of DNS server addresses
                String[] dnsServers = new String[staticIpConfiguration.dnsServers.size()];
                int dnsServerIdx = 0;
                for (InetAddress inetAddr : staticIpConfiguration.dnsServers) {
                    dnsServers[dnsServerIdx++] = inetAddr.getHostAddress();
                }
                XmlUtil.writeNextValue(
                        out, XML_TAG_DNS_SERVER_ADDRESSES, dnsServers);
            } else {
                XmlUtil.writeNextValue(
                        out, XML_TAG_DNS_SERVER_ADDRESSES, null);
            }
        }

        /**
         * Write the IP configuration data elements from the provided Configuration to the XML
         * stream.
         */
        public static void writeIpConfigurationToXml(XmlSerializer out,
                IpConfiguration ipConfiguration)
                throws XmlPullParserException, IOException {
            // Write IP assignment settings
            XmlUtil.writeNextValue(out, XML_TAG_IP_ASSIGNMENT,
                    ipConfiguration.ipAssignment.toString());
            switch (ipConfiguration.ipAssignment) {
                case STATIC:
                    writeStaticIpConfigurationToXml(
                            out, ipConfiguration.getStaticIpConfiguration());
                    break;
                default:
                    break;
            }

            // Write proxy settings
            XmlUtil.writeNextValue(
                    out, XML_TAG_PROXY_SETTINGS,
                    ipConfiguration.proxySettings.toString());
            switch (ipConfiguration.proxySettings) {
                case STATIC:
                    XmlUtil.writeNextValue(
                            out, XML_TAG_PROXY_HOST,
                            ipConfiguration.httpProxy.getHost());
                    XmlUtil.writeNextValue(
                            out, XML_TAG_PROXY_PORT,
                            ipConfiguration.httpProxy.getPort());
                    XmlUtil.writeNextValue(
                            out, XML_TAG_PROXY_EXCLUSION_LIST,
                            ipConfiguration.httpProxy.getExclusionListAsString());
                    break;
                case PAC:
                    XmlUtil.writeNextValue(
                            out, XML_TAG_PROXY_PAC_FILE,
                            ipConfiguration.httpProxy.getPacFileUrl().toString());
                    break;
                default:
                    break;
            }
        }

        /**
         * Parse out the static IP configuration from the XML stream.
         */
        private static StaticIpConfiguration parseStaticIpConfigurationFromXml(XmlPullParser in)
                throws XmlPullParserException, IOException {
            StaticIpConfiguration staticIpConfiguration = new StaticIpConfiguration();

            String linkAddressString =
                    (String) XmlUtil.readNextValueWithName(in, XML_TAG_LINK_ADDRESS);
            Integer linkPrefixLength =
                    (Integer) XmlUtil.readNextValueWithName(in, XML_TAG_LINK_PREFIX_LENGTH);
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
                    (String) XmlUtil.readNextValueWithName(in, XML_TAG_GATEWAY_ADDRESS);
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
                    (String[]) XmlUtil.readNextValueWithName(in, XML_TAG_DNS_SERVER_ADDRESSES);
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
         * Parses the IP configuration data elements from the provided XML stream to an
         * IpConfiguration object.
         *
         * @param in            XmlPullParser instance pointing to the XML stream.
         * @param outerTagDepth depth of the outer tag in the XML document.
         * @return IpConfiguration object if parsing is successful, null otherwise.
         */
        public static IpConfiguration parseIpConfigurationFromXml(XmlPullParser in,
                int outerTagDepth)
                throws XmlPullParserException, IOException {
            IpConfiguration ipConfiguration = new IpConfiguration();

            // Parse out the IP assignment info first.
            String ipAssignmentString =
                    (String) XmlUtil.readNextValueWithName(in, XML_TAG_IP_ASSIGNMENT);
            IpAssignment ipAssignment = IpAssignment.valueOf(ipAssignmentString);
            ipConfiguration.setIpAssignment(ipAssignment);
            switch (ipAssignment) {
                case STATIC:
                    ipConfiguration.setStaticIpConfiguration(parseStaticIpConfigurationFromXml(in));
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
                    (String) XmlUtil.readNextValueWithName(in, XML_TAG_PROXY_SETTINGS);
            ProxySettings proxySettings = ProxySettings.valueOf(proxySettingsString);
            ipConfiguration.setProxySettings(proxySettings);
            switch (proxySettings) {
                case STATIC:
                    String proxyHost =
                            (String) XmlUtil.readNextValueWithName(in, XML_TAG_PROXY_HOST);
                    int proxyPort =
                            (int) XmlUtil.readNextValueWithName(in, XML_TAG_PROXY_PORT);
                    String proxyExclusionList =
                            (String) XmlUtil.readNextValueWithName(
                                    in, XML_TAG_PROXY_EXCLUSION_LIST);
                    ipConfiguration.setHttpProxy(
                            new ProxyInfo(proxyHost, proxyPort, proxyExclusionList));
                    break;
                case PAC:
                    String proxyPacFile =
                            (String) XmlUtil.readNextValueWithName(in, XML_TAG_PROXY_PAC_FILE);
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
    }

    /**
     * Utility class to serialize and deseriaize {@link NetworkSelectionStatus} object to XML &
     * vice versa. This is used by {@link com.android.server.wifi.WifiConfigStore} module.
     */
    public static class NetworkSelectionStatusXmlUtil {

        /**
         * List of XML tags corresponding to NetworkSelectionStatus object elements.
         */
        public static final String XML_TAG_SELECTION_STATUS = "SelectionStatus";
        public static final String XML_TAG_DISABLE_REASON = "DisableReason";
        public static final String XML_TAG_CONNECT_CHOICE = "ConnectChoice";
        public static final String XML_TAG_CONNECT_CHOICE_TIMESTAMP = "ConnectChoiceTimeStamp";
        public static final String XML_TAG_HAS_EVER_CONNECTED = "HasEverConnected";

        /**
         * Write the NetworkSelectionStatus data elements from the provided status to the XML
         * stream.
         */
        public static void writeNetworkSelectionStatusToXml(XmlSerializer out,
                NetworkSelectionStatus status)
                throws XmlPullParserException, IOException {
            // Don't persist blacklists across reboots. So, if the status is temporarily disabled,
            // store the status as enabled. This will ensure that when the device reboots, it is
            // still considered for network selection.
            int selectionStatus = status.getNetworkSelectionStatus();
            if (selectionStatus == NetworkSelectionStatus.NETWORK_SELECTION_TEMPORARY_DISABLED) {
                selectionStatus = NetworkSelectionStatus.NETWORK_SELECTION_ENABLED;
            }
            XmlUtil.writeNextValue(out, XML_TAG_SELECTION_STATUS, selectionStatus);
            XmlUtil.writeNextValue(
                    out, XML_TAG_DISABLE_REASON, status.getNetworkSelectionDisableReason());
            XmlUtil.writeNextValue(out, XML_TAG_CONNECT_CHOICE, status.getConnectChoice());
            XmlUtil.writeNextValue(
                    out, XML_TAG_CONNECT_CHOICE_TIMESTAMP, status.getConnectChoiceTimestamp());
            XmlUtil.writeNextValue(out, XML_TAG_HAS_EVER_CONNECTED, status.getHasEverConnected());
        }

        /**
         * Parses the NetworkSelectionStatus data elements from the provided XML stream to a
         * NetworkSelectionStatus object.
         *
         * @param in            XmlPullParser instance pointing to the XML stream.
         * @param outerTagDepth depth of the outer tag in the XML document.
         * @return NetworkSelectionStatus object if parsing is successful, null otherwise.
         */
        public static NetworkSelectionStatus parseNetworkSelectionStatusFromXml(XmlPullParser in,
                int outerTagDepth)
                throws XmlPullParserException, IOException {
            NetworkSelectionStatus status = new NetworkSelectionStatus();

            // Loop through and parse out all the elements from the stream within this section.
            while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
                String[] valueName = new String[1];
                Object value = XmlUtil.readCurrentValue(in, valueName);
                if (valueName[0] == null) {
                    Log.e(TAG, "Missing value name");
                    return null;
                }
                switch (valueName[0]) {
                    case XML_TAG_SELECTION_STATUS:
                        status.setNetworkSelectionStatus((int) value);
                        break;
                    case XML_TAG_DISABLE_REASON:
                        status.setNetworkSelectionDisableReason((int) value);
                        break;
                    case XML_TAG_CONNECT_CHOICE:
                        status.setConnectChoice((String) value);
                        break;
                    case XML_TAG_CONNECT_CHOICE_TIMESTAMP:
                        status.setConnectChoiceTimestamp((long) value);
                        break;
                    case XML_TAG_HAS_EVER_CONNECTED:
                        status.setHasEverConnected((boolean) value);
                        break;
                    default:
                        Log.e(TAG, "Unknown value name found: " + valueName[0]);
                        return null;
                }
            }
            return status;
        }
    }
}

