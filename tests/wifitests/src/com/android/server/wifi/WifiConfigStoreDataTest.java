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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.net.IpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Unit tests for {@link com.android.server.wifi.WifiConfigStoreData}.
 */
@SmallTest
public class WifiConfigStoreDataTest {

    private static final int TEST_NETWORK_ID = -1;
    private static final int TEST_UID = 1;
    private static final String TEST_SSID = "WifiConfigStoreDataSSID_";
    private static final String TEST_PSK = "WifiConfigStoreDataPsk";
    private static final String[] TEST_WEP_KEYS =
            {"WifiConfigStoreDataWep1", "WifiConfigStoreDataWep2",
                    "WifiConfigStoreDataWep3", "WifiConfigStoreDataWep3"};
    private static final int TEST_WEP_TX_KEY_INDEX = 1;
    private static final String TEST_FQDN = "WifiConfigStoreDataFQDN";
    private static final String TEST_PROVIDER_FRIENDLY_NAME = "WifiConfigStoreDataFriendlyName";
    private static final String TEST_STATIC_IP_LINK_ADDRESS = "192.168.48.2";
    private static final int TEST_STATIC_IP_LINK_PREFIX_LENGTH = 8;
    private static final String TEST_STATIC_IP_GATEWAY_ADDRESS = "192.168.48.1";
    private static final String[] TEST_STATIC_IP_DNS_SERVER_ADDRESSES =
            new String[]{"192.168.48.1", "192.168.48.10"};
    private static final String TEST_STATIC_PROXY_HOST = "192.168.48.1";
    private static final int TEST_STATIC_PROXY_PORT = 8000;
    private static final String TEST_STATIC_PROXY_EXCLUSION_LIST = "";
    private static final String TEST_PAC_PROXY_LOCATION = "http://";
    private static final String TEST_CONNECT_CHOICE = "XmlUtilConnectChoice";
    private static final long TEST_CONNECT_CHOICE_TIMESTAMP = 0x4566;

    private static final Set<String> TEST_BSSID_BLACKLIST = new HashSet<String>() {
        {
            add("05:58:45:56:55:55");
            add("05:48:25:56:35:55");
        }
    };
    private static final Set<String> TEST_DELETED_EPHEMERAL_LIST = new HashSet<String>() {
        {
            add("\"" + TEST_SSID + "1\"");
            add("\"" + TEST_SSID + "2\"");
        }
    };
    private static final String SINGLE_OPEN_NETWORK_LIST_XML_STRING_FORMAT =
            "<NetworkList>\n"
                    + "<Network>\n"
                    + "<WifiConfiguration>\n"
                    + "<string name=\"ConfigKey\">%s</string>\n"
                    + "<string name=\"SSID\">%s</string>\n"
                    + "<null name=\"BSSID\" />\n"
                    + "<null name=\"PreSharedKey\" />\n"
                    + "<null name=\"WEPKeys\" />\n"
                    + "<int name=\"WEPTxKeyIndex\" value=\"0\" />\n"
                    + "<boolean name=\"HiddenSSID\" value=\"false\" />\n"
                    + "<byte-array name=\"AllowedKeyMgmt\" num=\"1\">01</byte-array>\n"
                    + "<byte-array name=\"AllowedProtocols\" num=\"0\"></byte-array>\n"
                    + "<byte-array name=\"AllowedAuthAlgos\" num=\"0\"></byte-array>\n"
                    + "<boolean name=\"Shared\" value=\"%s\" />\n"
                    + "<null name=\"FQDN\" />\n"
                    + "<null name=\"ProviderFriendlyName\" />\n"
                    + "<null name=\"LinkedNetworksList\" />\n"
                    + "<null name=\"DefaultGwMacAddress\" />\n"
                    + "<boolean name=\"ValidatedInternetAccess\" value=\"false\" />\n"
                    + "<boolean name=\"NoInternetAccessExpected\" value=\"false\" />\n"
                    + "<int name=\"UserApproved\" value=\"0\" />\n"
                    + "<boolean name=\"MeteredHint\" value=\"false\" />\n"
                    + "<boolean name=\"UseExternalScores\" value=\"false\" />\n"
                    + "<int name=\"NumAssociation\" value=\"0\" />\n"
                    + "<int name=\"CreatorUid\" value=\"%d\" />\n"
                    + "<null name=\"CreatorName\" />\n"
                    + "<null name=\"CreationTime\" />\n"
                    + "<int name=\"LastUpdateUid\" value=\"-1\" />\n"
                    + "<null name=\"LastUpdateName\" />\n"
                    + "<int name=\"LastConnectUid\" value=\"0\" />\n"
                    + "</WifiConfiguration>\n"
                    + "<NetworkStatus>\n"
                    + "<int name=\"SelectionStatus\" value=\"0\" />\n"
                    + "<int name=\"DisableReason\" value=\"0\" />\n"
                    + "<null name=\"ConnectChoice\" />\n"
                    + "<long name=\"ConnectChoiceTimeStamp\" value=\"-1\" />\n"
                    + "<boolean name=\"HasEverConnected\" value=\"false\" />\n"
                    + "</NetworkStatus>\n"
                    + "<IpConfiguration>\n"
                    + "<string name=\"IpAssignment\">DHCP</string>\n"
                    + "<string name=\"ProxySettings\">NONE</string>\n"
                    + "</IpConfiguration>\n"
                    + "</Network>\n"
                    + "</NetworkList>\n";
    private static final String SINGLE_OPEN_NETWORK_SHARED_DATA_XML_STRING_FORMAT =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                    + "<WifiConfigStoreData>\n"
                    + "<int name=\"Version\" value=\"1\" />\n"
                    + SINGLE_OPEN_NETWORK_LIST_XML_STRING_FORMAT
                    + "<LastNetworkId>\n"
                    + "<int name=\"Id\" value=\"0\" />\n"
                    + "</LastNetworkId>\n"
                    + "</WifiConfigStoreData>\n";
    private static final String SINGLE_OPEN_NETWORK_USER_DATA_XML_STRING_FORMAT =
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                    + "<WifiConfigStoreData>\n"
                    + "<int name=\"Version\" value=\"1\" />\n"
                    + SINGLE_OPEN_NETWORK_LIST_XML_STRING_FORMAT
                    + "<BSSIDBlacklist>\n"
                    + "<set name=\"BSSIDList\" />\n"
                    + "</BSSIDBlacklist>\n"
                    + "<DeletedEphemeralSSIDList>\n"
                    + "<set name=\"SSIDList\" />\n"
                    + "</DeletedEphemeralSSIDList>\n"
                    + "</WifiConfigStoreData>\n";


    /**
     * Verify that multiple shared networks with different credential types and IpConfiguration
     * types are serialized and deserialized correctly.
     */
    @Test
    public void testMultipleNetworkAllShared()
            throws XmlPullParserException, IOException {
        List<WifiConfiguration> configurations = createNetworks(true);
        serializeDeserializeConfigStoreData(configurations);
    }

    /**
     * Verify that multiple user networks with different credential types and IpConfiguration
     * types are serialized and deserialized correctly.
     */
    @Test
    public void testMultipleNetworksAllUser()
            throws XmlPullParserException, IOException {
        List<WifiConfiguration> configurations = createNetworks(false);
        serializeDeserializeConfigStoreData(configurations);
    }

    /**
     * Verify that multiple networks with different credential types and IpConfiguration
     * types are serialized and deserialized correctly when both user & shared networks are present.
     */
    @Test
    public void testMultipleNetworksSharedAndUserNetworks()
            throws XmlPullParserException, IOException {
        List<WifiConfiguration> configurations = createNetworks();
        // Let's split the list of networks into 2 and make all the networks in the first list
        // shared and the second list all user networks.
        int listSize = configurations.size();
        List<WifiConfiguration> sharedConfigurations = configurations.subList(0, listSize / 2);
        List<WifiConfiguration> userConfigurations = configurations.subList(listSize / 2, listSize);
        for (WifiConfiguration config : sharedConfigurations) {
            config.shared = true;
        }
        for (WifiConfiguration config : userConfigurations) {
            config.shared = false;
        }
        serializeDeserializeConfigStoreData(configurations);
    }

    /**
     * Verify that multiple shared networks with different credential types and IpConfiguration
     * types are serialized and deserialized correctly when the shared data bytes are null in
     * |parseRawData| method.
     */
    @Test
    public void testMultipleNetworksSharedDataNullInParseRawData()
            throws XmlPullParserException, IOException {
        List<WifiConfiguration> configurations = createNetworks(false);
        serializeDeserializeConfigStoreData(configurations, true, false);
    }

    /**
     * Verify that multiple shared networks with different credential types and IpConfiguration
     * types are serialized and deserialized correctly when the user data bytes are null in
     * |parseRawData| method.
     */
    @Test
    public void testMultipleNetworksUserDataNullInParseRawData()
            throws XmlPullParserException, IOException {
        List<WifiConfiguration> configurations = createNetworks(true);
        serializeDeserializeConfigStoreData(configurations, false, true);
    }

    /**
     * Verify that the manually populated xml string for is deserialized/serialized correctly.
     * This generates a store data corresponding to the XML string and verifies that the string
     * is indeed parsed correctly to the store data.
     */
    @Test
    public void testManualConfigStoreDataParse() {
        WifiConfiguration sharedNetwork = createOpenNetwork(0);
        sharedNetwork.shared = true;
        sharedNetwork.setIpConfiguration(createDHCPIpConfigurationWithNoProxy());
        WifiConfiguration userNetwork = createOpenNetwork(1);
        userNetwork.setIpConfiguration(createDHCPIpConfigurationWithNoProxy());
        userNetwork.shared = false;

        // Create the store data for comparison.
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(sharedNetwork);
        networks.add(userNetwork);
        WifiConfigStoreData storeData =
                new WifiConfigStoreData(networks, new HashSet<String>(), new HashSet<String>(), 0);

        String sharedStoreXmlString =
                String.format(SINGLE_OPEN_NETWORK_SHARED_DATA_XML_STRING_FORMAT,
                        sharedNetwork.configKey().replaceAll("\"", "&quot;"),
                        sharedNetwork.SSID.replaceAll("\"", "&quot;"),
                        sharedNetwork.shared, sharedNetwork.creatorUid);
        String userStoreXmlString =
                String.format(SINGLE_OPEN_NETWORK_USER_DATA_XML_STRING_FORMAT,
                        userNetwork.configKey().replaceAll("\"", "&quot;"),
                        userNetwork.SSID.replaceAll("\"", "&quot;"),
                        userNetwork.shared, userNetwork.creatorUid);
        byte[] rawSharedData = sharedStoreXmlString.getBytes();
        byte[] rawUserData = userStoreXmlString.getBytes();
        WifiConfigStoreData retrievedStoreData = null;
        try {
            retrievedStoreData = WifiConfigStoreData.parseRawData(rawSharedData, rawUserData);
        } catch (Exception e) {
            // Assert if an exception was raised.
            fail("Error in parsing the xml data: " + e
                    + ". Shared data: " + sharedStoreXmlString
                    + ", User data: " + userStoreXmlString);
        }
        // Compare the retrieved config store data with the original.
        assertConfigStoreDataEqual(storeData, retrievedStoreData);

        // Now convert the store data to XML bytes and compare the output with the expected string.
        byte[] retrievedSharedStoreXmlBytes = null;
        byte[] retrievedUserStoreXmlBytes = null;
        try {
            retrievedSharedStoreXmlBytes = retrievedStoreData.createSharedRawData();
            retrievedUserStoreXmlBytes = retrievedStoreData.createUserRawData();
        } catch (Exception e) {
            // Assert if an exception was raised.
            fail("Error in writing the xml data: " + e);
        }
        String retrievedSharedStoreXmlString =
                new String(retrievedSharedStoreXmlBytes, StandardCharsets.UTF_8);
        String retrievedUserStoreXmlString =
                new String(retrievedUserStoreXmlBytes, StandardCharsets.UTF_8);
        assertEquals("Retrieved: " + retrievedSharedStoreXmlString
                + ", Expected: " + sharedStoreXmlString,
                sharedStoreXmlString, retrievedSharedStoreXmlString);
        assertEquals("Retrieved: " + retrievedUserStoreXmlString
                + ", Expected: " + userStoreXmlString,
                userStoreXmlString, retrievedUserStoreXmlString);
    }

    /**
     * Verify that XML with corrupted version provided to WifiConfigStoreData is ignored correctly.
     */
    @Test
    public void testCorruptVersionConfigStoreData() {
        String storeDataAsString =
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                        + "<WifiConfigStoreData>\n"
                        + "<int name=\"Version\" value=\"200\" />\n"
                        + "</WifiConfigStoreData>\n";
        byte[] rawData = storeDataAsString.getBytes();
        try {
            WifiConfigStoreData storeData = WifiConfigStoreData.parseRawData(rawData, rawData);
        } catch (Exception e) {
            return;
        }
        // Assert if there was no exception was raised.
        fail();
    }

    /**
     * Verify that XML with no network list provided to WifiConfigStoreData is ignored correctly.
     */
    @Test
    public void testCorruptNetworkListConfigStoreData() {
        String storeDataAsString =
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                        + "<WifiConfigStoreData>\n"
                        + "<int name=\"Version\" value=\"1\" />\n"
                        + "</WifiConfigStoreData>\n";
        byte[] rawData = storeDataAsString.getBytes();
        try {
            WifiConfigStoreData storeData = WifiConfigStoreData.parseRawData(rawData, rawData);
        } catch (Exception e) {
            return;
        }
        // Assert if there was no exception was raised.
        fail();
    }

    /**
     * Verify that any corrupted data provided to WifiConfigStoreData is ignored correctly.
     */
    @Test
    public void testRandomCorruptConfigStoreData() {
        Random random = new Random();
        byte[] rawData = new byte[100];
        random.nextBytes(rawData);
        try {
            WifiConfigStoreData storeData = WifiConfigStoreData.parseRawData(rawData, rawData);
        } catch (Exception e) {
            return;
        }
        // Assert if there was no exception was raised.
        fail();
    }

    private WifiConfiguration createOpenNetwork(int id) {
        String ssid = "\"" + TEST_SSID + id + "\"";
        return WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                true, true, null, null,
                WifiConfigurationTestUtil.SECURITY_NONE);
    }

    private WifiConfiguration createPskNetwork(int id) {
        String ssid = "\"" + TEST_SSID + id + "\"";
        WifiConfiguration configuration =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                        true, true, null, null,
                        WifiConfigurationTestUtil.SECURITY_PSK);
        configuration.preSharedKey = TEST_PSK;
        return configuration;
    }

    private WifiConfiguration createWepNetwork(int id) {
        String ssid = "\"" + TEST_SSID + id + "\"";
        WifiConfiguration configuration =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                        true, true, null, null,
                        WifiConfigurationTestUtil.SECURITY_WEP);
        configuration.wepKeys = TEST_WEP_KEYS;
        configuration.wepTxKeyIndex = TEST_WEP_TX_KEY_INDEX;
        return configuration;
    }

    private WifiConfiguration createEapNetwork(int id) {
        String ssid = "\"" + TEST_SSID + id + "\"";
        WifiConfiguration configuration =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                        true, true, TEST_FQDN, TEST_PROVIDER_FRIENDLY_NAME,
                        WifiConfigurationTestUtil.SECURITY_EAP);
        return configuration;
    }

    private IpConfiguration createStaticIpConfigurationWithPacProxy() {
        return WifiConfigurationTestUtil.generateIpConfig(
                WifiConfigurationTestUtil.STATIC_IP_ASSIGNMENT,
                WifiConfigurationTestUtil.PAC_PROXY_SETTING,
                TEST_STATIC_IP_LINK_ADDRESS, TEST_STATIC_IP_LINK_PREFIX_LENGTH,
                TEST_STATIC_IP_GATEWAY_ADDRESS, TEST_STATIC_IP_DNS_SERVER_ADDRESSES,
                TEST_STATIC_PROXY_HOST, TEST_STATIC_PROXY_PORT, TEST_STATIC_PROXY_EXCLUSION_LIST,
                TEST_PAC_PROXY_LOCATION);
    }

    private IpConfiguration createStaticIpConfigurationWithStaticProxy() {
        return WifiConfigurationTestUtil.generateIpConfig(
                WifiConfigurationTestUtil.STATIC_IP_ASSIGNMENT,
                WifiConfigurationTestUtil.STATIC_PROXY_SETTING,
                TEST_STATIC_IP_LINK_ADDRESS, TEST_STATIC_IP_LINK_PREFIX_LENGTH,
                TEST_STATIC_IP_GATEWAY_ADDRESS, TEST_STATIC_IP_DNS_SERVER_ADDRESSES,
                TEST_STATIC_PROXY_HOST, TEST_STATIC_PROXY_PORT, TEST_STATIC_PROXY_EXCLUSION_LIST,
                TEST_PAC_PROXY_LOCATION);
    }

    private IpConfiguration createPartialStaticIpConfigurationWithPacProxy() {
        return WifiConfigurationTestUtil.generateIpConfig(
                WifiConfigurationTestUtil.STATIC_IP_ASSIGNMENT,
                WifiConfigurationTestUtil.PAC_PROXY_SETTING,
                TEST_STATIC_IP_LINK_ADDRESS, TEST_STATIC_IP_LINK_PREFIX_LENGTH,
                null, null,
                TEST_STATIC_PROXY_HOST, TEST_STATIC_PROXY_PORT, TEST_STATIC_PROXY_EXCLUSION_LIST,
                TEST_PAC_PROXY_LOCATION);
    }

    private IpConfiguration createDHCPIpConfigurationWithPacProxy() {
        return WifiConfigurationTestUtil.generateIpConfig(
                WifiConfigurationTestUtil.DHCP_IP_ASSIGNMENT,
                WifiConfigurationTestUtil.PAC_PROXY_SETTING,
                TEST_STATIC_IP_LINK_ADDRESS, TEST_STATIC_IP_LINK_PREFIX_LENGTH,
                TEST_STATIC_IP_GATEWAY_ADDRESS, TEST_STATIC_IP_DNS_SERVER_ADDRESSES,
                TEST_STATIC_PROXY_HOST, TEST_STATIC_PROXY_PORT, TEST_STATIC_PROXY_EXCLUSION_LIST,
                TEST_PAC_PROXY_LOCATION);
    }

    private IpConfiguration createDHCPIpConfigurationWithNoProxy() {
        return WifiConfigurationTestUtil.generateIpConfig(
                WifiConfigurationTestUtil.DHCP_IP_ASSIGNMENT,
                WifiConfigurationTestUtil.NONE_PROXY_SETTING,
                TEST_STATIC_IP_LINK_ADDRESS, TEST_STATIC_IP_LINK_PREFIX_LENGTH,
                TEST_STATIC_IP_GATEWAY_ADDRESS, TEST_STATIC_IP_DNS_SERVER_ADDRESSES,
                TEST_STATIC_PROXY_HOST, TEST_STATIC_PROXY_PORT, TEST_STATIC_PROXY_EXCLUSION_LIST,
                TEST_PAC_PROXY_LOCATION);
    }

    /**
     * Helper method to add 4 networks with different credential types, IpConfiguration
     * types for all tests in the class.
     *
     * @return
     */
    private List<WifiConfiguration> createNetworks() {
        List<WifiConfiguration> configurations = new ArrayList<>();

        WifiConfiguration wepNetwork = createWepNetwork(0);
        wepNetwork.setIpConfiguration(createDHCPIpConfigurationWithPacProxy());
        wepNetwork.getNetworkSelectionStatus().setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_ENABLED);
        configurations.add(wepNetwork);

        WifiConfiguration pskNetwork = createPskNetwork(1);
        pskNetwork.setIpConfiguration(createStaticIpConfigurationWithPacProxy());
        pskNetwork.getNetworkSelectionStatus().setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_TEMPORARY_DISABLED);
        pskNetwork.getNetworkSelectionStatus().setNetworkSelectionDisableReason(
                NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION);
        configurations.add(pskNetwork);

        WifiConfiguration openNetwork = createOpenNetwork(2);
        openNetwork.setIpConfiguration(createStaticIpConfigurationWithStaticProxy());
        openNetwork.getNetworkSelectionStatus().setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED);
        openNetwork.getNetworkSelectionStatus().setNetworkSelectionDisableReason(
                NetworkSelectionStatus.DISABLED_BY_WIFI_MANAGER);
        configurations.add(openNetwork);

        WifiConfiguration eapNetwork = createEapNetwork(3);
        eapNetwork.setIpConfiguration(createPartialStaticIpConfigurationWithPacProxy());
        eapNetwork.getNetworkSelectionStatus().setConnectChoice(TEST_CONNECT_CHOICE);
        eapNetwork.getNetworkSelectionStatus().setConnectChoiceTimestamp(
                TEST_CONNECT_CHOICE_TIMESTAMP);
        eapNetwork.getNetworkSelectionStatus().setHasEverConnected(true);
        configurations.add(eapNetwork);

        return configurations;
    }

    private List<WifiConfiguration> createNetworks(boolean shared) {
        List<WifiConfiguration> configurations = createNetworks();
        for (WifiConfiguration config : configurations) {
            config.shared = shared;
        }
        return configurations;
    }

    /**
     * Helper method to serialize/deserialize store data.
     */
    private void serializeDeserializeConfigStoreData(List<WifiConfiguration> configurations)
            throws XmlPullParserException, IOException {
        serializeDeserializeConfigStoreData(configurations, false, false);
    }

    /**
     * Helper method to ensure the the provided config store data is serialized/deserialized
     * correctly.
     * This method serialize the provided config store data instance to raw bytes in XML format
     * and then deserialzes the raw bytes back to a config store data instance. It then
     * compares that the original config store data matches with the deserialzed instance.
     *
     * @param configurations list of configurations to be added in the store data instance.
     * @param setSharedDataNull whether to set the shared data to null to simulate the non-existence
     *                          of the shared store file.
     * @param setUserDataNull whether to set the user data to null to simulate the non-existence
     *                        of the user store file.
     */
    private void serializeDeserializeConfigStoreData(
            List<WifiConfiguration> configurations, boolean setSharedDataNull,
            boolean setUserDataNull)
            throws XmlPullParserException, IOException {
        // Will not work if both the flags are set because then we need to ignore the configuration
        // list as well.
        assertFalse(setSharedDataNull & setUserDataNull);

        Set<String> bssidBlackList;
        Set<String> deletedEphemeralList;
        int lastNetworkID;
        if (setSharedDataNull) {
            lastNetworkID = WifiConfigStoreData.NETWORK_ID_START;
        } else {
            lastNetworkID = TEST_NETWORK_ID;
        }
        if (setUserDataNull) {
            bssidBlackList = new HashSet<>();
            deletedEphemeralList = new HashSet<>();
        } else {
            bssidBlackList = TEST_BSSID_BLACKLIST;
            deletedEphemeralList = TEST_DELETED_EPHEMERAL_LIST;
        }

        // Serialize the data.
        WifiConfigStoreData storeData =
                new WifiConfigStoreData(
                        configurations, bssidBlackList, deletedEphemeralList, lastNetworkID);

        byte[] sharedDataBytes = null;
        byte[] userDataBytes = null;
        if (!setSharedDataNull) {
            sharedDataBytes = storeData.createSharedRawData();
        }
        if (!setUserDataNull) {
            userDataBytes = storeData.createUserRawData();
        }

        // Deserialize the data.
        WifiConfigStoreData retrievedStoreData =
                WifiConfigStoreData.parseRawData(sharedDataBytes, userDataBytes);
        assertConfigStoreDataEqual(storeData, retrievedStoreData);
    }

    /**
     * Asserts that the 2 config store data are equal.
     */
    public static void assertConfigStoreDataEqual(
            WifiConfigStoreData expected, WifiConfigStoreData actual) {
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigStore(
                expected.configurations, actual.configurations);
        assertEquals(expected.blackListBSSIDs, actual.blackListBSSIDs);
        assertEquals(expected.deletedEphemeralSSIDs, actual.deletedEphemeralSSIDs);
        assertEquals(expected.lastNetworkId, actual.lastNetworkId);
    }
}
