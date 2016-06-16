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
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.net.IpConfigStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Unit tests for {@link com.android.server.wifi.WifiBackupRestore}.
 */
@SmallTest
public class WifiBackupRestoreTest {

    private static final int TEST_NETWORK_ID = -1;
    private static final int TEST_UID = 1;
    private static final String TEST_SSID = "WifiBackupRestoreSSID_";
    private static final String TEST_PSK = "WifiBackupRestorePsk";
    private static final String[] TEST_WEP_KEYS =
            {"WifiBackupRestoreWep1", "WifiBackupRestoreWep2",
                    "WifiBackupRestoreWep3", "WifiBackupRestoreWep3"};
    private static final int TEST_WEP_TX_KEY_INDEX = 1;
    private static final String TEST_FQDN = "WifiBackupRestoreFQDN";
    private static final String TEST_PROVIDER_FRIENDLY_NAME = "WifiBackupRestoreFriendlyName";
    private static final String TEST_STATIC_IP_LINK_ADDRESS = "192.168.48.2";
    private static final int TEST_STATIC_IP_LINK_PREFIX_LENGTH = 8;
    private static final String TEST_STATIC_IP_GATEWAY_ADDRESS = "192.168.48.1";
    private static final String[] TEST_STATIC_IP_DNS_SERVER_ADDRESSES =
            new String[]{"192.168.48.1", "192.168.48.10"};
    private static final String TEST_STATIC_PROXY_HOST = "192.168.48.1";
    private static final int TEST_STATIC_PROXY_PORT = 8000;
    private static final String TEST_STATIC_PROXY_EXCLUSION_LIST = "";
    private static final String TEST_PAC_PROXY_LOCATION = "http://";

    private final WifiBackupRestore mWifiBackupRestore = new WifiBackupRestore();
    private boolean mCheckDump = true;

    @Before
    public void setUp() throws Exception {
        // Enable verbose logging before tests to check the backup data dumps.
        mWifiBackupRestore.enableVerboseLogging(1);
    }

    @After
    public void cleanUp() throws Exception {
        if (mCheckDump) {
            StringWriter stringWriter = new StringWriter();
            mWifiBackupRestore.dump(
                    new FileDescriptor(), new PrintWriter(stringWriter), new String[0]);
            String dumpString = stringWriter.toString();
            // Ensure that the SSID was dumped out.
            assertTrue("Dump: " + dumpString, dumpString.contains(TEST_SSID));
            // Ensure that the password wasn't dumped out.
            assertFalse("Dump: " + dumpString, dumpString.contains(TEST_PSK));
            assertFalse("Dump: " + dumpString, dumpString.contains(TEST_WEP_KEYS[0]));
            assertFalse("Dump: " + dumpString, dumpString.contains(TEST_WEP_KEYS[1]));
            assertFalse("Dump: " + dumpString, dumpString.contains(TEST_WEP_KEYS[2]));
            assertFalse("Dump: " + dumpString, dumpString.contains(TEST_WEP_KEYS[3]));
        }
    }

    /**
     * Verify that a single open network configuration is serialized & deserialized correctly.
     */
    @Test
    public void testSingleOpenNetworkBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createOpenNetwork(0));

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single open hidden network configuration is serialized & deserialized
     * correctly.
     */
    @Test
    public void testSingleOpenHiddenNetworkBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createOpenHiddenNetwork(0));

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single PSK network configuration is serialized & deserialized correctly.
     */
    @Test
    public void testSinglePskNetworkBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createPskNetwork(0));

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single PSK hidden network configuration is serialized & deserialized correctly.
     */
    @Test
    public void testSinglePskHiddenNetworkBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createPskHiddenNetwork(0));

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single WEP network configuration is serialized & deserialized correctly.
     */
    @Test
    public void testSingleWepNetworkBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createWepNetwork(0));

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single WEP network configuration with only 1 key is serialized & deserialized
     * correctly.
     */
    @Test
    public void testSingleWepNetworkWithSingleKeyBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createWepNetworkWithSingleKey(0));

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single enterprise network configuration is not serialized.
     */
    @Test
    public void testSingleEnterpriseNetworkNotBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createEapNetwork(0));

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        assertTrue(retrievedConfigurations.isEmpty());
        // No valid data to check in dump.
        mCheckDump = false;
    }

    /**
     * Verify that a single PSK network configuration with static ip/proxy settings is serialized &
     * deserialized correctly.
     */
    @Test
    public void testSinglePskNetworkWithStaticIpAndStaticProxyBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        WifiConfiguration pskNetwork = createPskNetwork(0);
        pskNetwork.setIpConfiguration(createStaticIpConfigurationWithStaticProxy());
        configurations.add(pskNetwork);

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single PSK network configuration with static ip & PAC proxy settings is
     * serialized & deserialized correctly.
     */
    @Test
    public void testSinglePskNetworkWithStaticIpAndPACProxyBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        WifiConfiguration pskNetwork = createPskNetwork(0);
        pskNetwork.setIpConfiguration(createStaticIpConfigurationWithPacProxy());
        configurations.add(pskNetwork);

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single PSK network configuration with DHCP ip & PAC proxy settings is
     * serialized & deserialized correctly.
     */
    @Test
    public void testSinglePskNetworkWithDHCPIpAndPACProxyBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        WifiConfiguration pskNetwork = createPskNetwork(0);
        pskNetwork.setIpConfiguration(createDHCPIpConfigurationWithPacProxy());
        configurations.add(pskNetwork);

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single PSK network configuration with partial static ip settings is serialized
     * & deserialized correctly.
     */
    @Test
    public void testSinglePskNetworkWithPartialStaticIpBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        WifiConfiguration pskNetwork = createPskNetwork(0);
        pskNetwork.setIpConfiguration(createPartialStaticIpConfigurationWithPacProxy());
        configurations.add(pskNetwork);

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that multiple networks of different types are serialized and deserialized correctly.
     */
    @Test
    public void testMultipleNetworksAllBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createWepNetwork(0));
        configurations.add(createWepNetwork(1));
        configurations.add(createPskNetwork(2));
        configurations.add(createOpenNetwork(3));

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that multiple networks of different types except enterprise ones are serialized and
     * deserialized correctly
     */
    @Test
    public void testMultipleNetworksNonEnterpriseBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        List<WifiConfiguration> expectedConfigurations = new ArrayList<>();

        configurations.add(createWepNetwork(0));
        expectedConfigurations.add(createWepNetwork(0));

        configurations.add(createEapNetwork(1));

        configurations.add(createPskNetwork(2));
        expectedConfigurations.add(createPskNetwork(2));

        configurations.add(createOpenNetwork(3));
        expectedConfigurations.add(createOpenNetwork(3));

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                expectedConfigurations, retrievedConfigurations);
    }

    /**
     * Verify that multiple networks with different credential types and IpConfiguration types are
     * serialized and deserialized correctly.
     */
    @Test
    public void testMultipleNetworksWithDifferentIpConfigurationsAllBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();

        WifiConfiguration wepNetwork = createWepNetwork(0);
        wepNetwork.setIpConfiguration(createDHCPIpConfigurationWithPacProxy());
        configurations.add(wepNetwork);

        WifiConfiguration pskNetwork = createPskNetwork(1);
        pskNetwork.setIpConfiguration(createStaticIpConfigurationWithPacProxy());
        configurations.add(pskNetwork);

        WifiConfiguration openNetwork = createOpenNetwork(2);
        openNetwork.setIpConfiguration(createStaticIpConfigurationWithStaticProxy());
        configurations.add(openNetwork);

        byte[] backupData = mWifiBackupRestore.retrieveBackupDataFromConfigurations(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single open network configuration is serialized & deserialized correctly from
     * old backups.
     */
    @Test
    public void testSingleOpenNetworkSupplicantBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createOpenNetwork(0));

        byte[] supplicantData = createWpaSupplicantConfBackupData(configurations);
        byte[] ipConfigData = createIpConfBackupData(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromSupplicantBackupData(
                        supplicantData, ipConfigData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single open hidden network configuration is serialized & deserialized
     * correctly from old backups.
     */
    @Test
    public void testSingleOpenHiddenNetworkSupplicantBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createOpenHiddenNetwork(0));

        byte[] supplicantData = createWpaSupplicantConfBackupData(configurations);
        byte[] ipConfigData = createIpConfBackupData(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromSupplicantBackupData(
                        supplicantData, ipConfigData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single PSK network configuration is serialized & deserialized correctly from
     * old backups.
     */
    @Test
    public void testSinglePskNetworkSupplicantBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createPskNetwork(0));

        byte[] supplicantData = createWpaSupplicantConfBackupData(configurations);
        byte[] ipConfigData = createIpConfBackupData(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromSupplicantBackupData(
                        supplicantData, ipConfigData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single PSK hidden network configuration is serialized & deserialized correctly
     * from old backups.
     */
    @Test
    public void testSinglePskHiddenNetworkSupplicantBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createPskHiddenNetwork(0));

        byte[] supplicantData = createWpaSupplicantConfBackupData(configurations);
        byte[] ipConfigData = createIpConfBackupData(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromSupplicantBackupData(
                        supplicantData, ipConfigData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single WEP network configuration is serialized & deserialized correctly from
     * old backups.
     */
    @Test
    public void testSingleWepNetworkSupplicantBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createWepNetwork(0));

        byte[] supplicantData = createWpaSupplicantConfBackupData(configurations);
        byte[] ipConfigData = createIpConfBackupData(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromSupplicantBackupData(
                        supplicantData, ipConfigData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single WEP network configuration with only 1 key is serialized & deserialized
     * correctly from old backups.
     */
    @Test
    public void testSingleWepNetworkWithSingleKeySupplicantBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createWepNetworkWithSingleKey(0));

        byte[] supplicantData = createWpaSupplicantConfBackupData(configurations);
        byte[] ipConfigData = createIpConfBackupData(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromSupplicantBackupData(
                        supplicantData, ipConfigData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single enterprise network configuration is not serialized from old backups.
     */
    @Test
    public void testSingleEnterpriseNetworkNotSupplicantBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createEapNetwork(0));

        byte[] supplicantData = createWpaSupplicantConfBackupData(configurations);
        byte[] ipConfigData = createIpConfBackupData(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromSupplicantBackupData(
                        supplicantData, ipConfigData);
        assertTrue(retrievedConfigurations.isEmpty());
    }

    /**
     * Verify that multiple networks with different credential types and IpConfiguration types are
     * serialized and deserialized correctly from old backups
     */
    @Test
    public void testMultipleNetworksWithDifferentIpConfigurationsAllSupplicantBackupRestore() {
        List<WifiConfiguration> configurations = new ArrayList<>();

        WifiConfiguration wepNetwork = createWepNetwork(0);
        wepNetwork.setIpConfiguration(createDHCPIpConfigurationWithPacProxy());
        configurations.add(wepNetwork);

        WifiConfiguration pskNetwork = createPskNetwork(1);
        pskNetwork.setIpConfiguration(createStaticIpConfigurationWithPacProxy());
        configurations.add(pskNetwork);

        WifiConfiguration openNetwork = createOpenNetwork(2);
        openNetwork.setIpConfiguration(createStaticIpConfigurationWithStaticProxy());
        configurations.add(openNetwork);

        byte[] supplicantData = createWpaSupplicantConfBackupData(configurations);
        byte[] ipConfigData = createIpConfBackupData(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromSupplicantBackupData(
                        supplicantData, ipConfigData);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that a single open network configuration is serialized & deserialized correctly from
     * old backups with no ipconfig data.
     */
    @Test
    public void testSingleOpenNetworkSupplicantBackupRestoreWithNoIpConfigData() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createOpenNetwork(0));

        byte[] supplicantData = createWpaSupplicantConfBackupData(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromSupplicantBackupData(
                        supplicantData, null);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that multiple networks with different credential types are serialized and
     * deserialized correctly from old backups with no ipconfig data.
     */
    @Test
    public void testMultipleNetworksAllSupplicantBackupRestoreWithNoIpConfigData() {
        List<WifiConfiguration> configurations = new ArrayList<>();

        WifiConfiguration wepNetwork = createWepNetwork(0);
        configurations.add(wepNetwork);

        WifiConfiguration pskNetwork = createPskNetwork(1);
        configurations.add(pskNetwork);

        WifiConfiguration openNetwork = createOpenNetwork(2);
        configurations.add(openNetwork);

        byte[] supplicantData = createWpaSupplicantConfBackupData(configurations);
        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromSupplicantBackupData(
                        supplicantData, null);
        WifiConfigurationTestUtil.assertConfigurationsEqualForBackup(
                configurations, retrievedConfigurations);
    }

    /**
     * Verify that any corrupted data provided by Backup/Restore is ignored correctly.
     */
    @Test
    public void testCorruptBackupRestore() {
        Random random = new Random();
        byte[] backupData = new byte[100];
        random.nextBytes(backupData);

        List<WifiConfiguration> retrievedConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(backupData);
        assertNull(retrievedConfigurations);
        // No valid data to check in dump.
        mCheckDump = false;
    }

    private WifiConfiguration createOpenNetwork(int id) {
        String ssid = "\"" + TEST_SSID + id + "\"";
        return WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                true, true, null, null,
                WifiConfigurationTestUtil.SECURITY_NONE);
    }

    private WifiConfiguration createOpenHiddenNetwork(int id) {
        String ssid = "\"" + TEST_SSID + id + "\"";
        WifiConfiguration config =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                true, true, null, null,
                WifiConfigurationTestUtil.SECURITY_NONE);
        config.hiddenSSID = true;
        return config;
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

    private WifiConfiguration createPskHiddenNetwork(int id) {
        String ssid = "\"" + TEST_SSID + id + "\"";
        WifiConfiguration configuration =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                        true, true, null, null,
                        WifiConfigurationTestUtil.SECURITY_PSK);
        configuration.preSharedKey = TEST_PSK;
        configuration.hiddenSSID = true;
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

    private WifiConfiguration createWepNetworkWithSingleKey(int id) {
        String ssid = "\"" + TEST_SSID + id + "\"";
        WifiConfiguration configuration =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                        true, true, null, null,
                        WifiConfigurationTestUtil.SECURITY_WEP);
        configuration.wepKeys[0] = TEST_WEP_KEYS[0];
        configuration.wepTxKeyIndex = 0;
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

    private StaticIpConfiguration createStaticIpConfiguration() {
        StaticIpConfiguration staticIpConfiguration = new StaticIpConfiguration();
        LinkAddress linkAddress =
                new LinkAddress(NetworkUtils.numericToInetAddress(TEST_STATIC_IP_LINK_ADDRESS),
                        TEST_STATIC_IP_LINK_PREFIX_LENGTH);
        staticIpConfiguration.ipAddress = linkAddress;
        InetAddress gatewayAddress =
                NetworkUtils.numericToInetAddress(TEST_STATIC_IP_GATEWAY_ADDRESS);
        staticIpConfiguration.gateway = gatewayAddress;
        for (String dnsServerAddress : TEST_STATIC_IP_DNS_SERVER_ADDRESSES) {
            staticIpConfiguration.dnsServers.add(
                    NetworkUtils.numericToInetAddress(dnsServerAddress));
        }
        return staticIpConfiguration;
    }

    private StaticIpConfiguration createPartialStaticIpConfiguration() {
        StaticIpConfiguration staticIpConfiguration = new StaticIpConfiguration();
        LinkAddress linkAddress =
                new LinkAddress(NetworkUtils.numericToInetAddress(TEST_STATIC_IP_LINK_ADDRESS),
                        TEST_STATIC_IP_LINK_PREFIX_LENGTH);
        staticIpConfiguration.ipAddress = linkAddress;
        // Only set the link address, don't set the gateway/dns servers.
        return staticIpConfiguration;
    }

    private IpConfiguration createStaticIpConfigurationWithPacProxy() {
        StaticIpConfiguration staticIpConfiguration = createStaticIpConfiguration();
        ProxyInfo proxyInfo = new ProxyInfo(TEST_PAC_PROXY_LOCATION);
        return new IpConfiguration(IpConfiguration.IpAssignment.STATIC,
                IpConfiguration.ProxySettings.PAC, staticIpConfiguration, proxyInfo);
    }

    private IpConfiguration createStaticIpConfigurationWithStaticProxy() {
        StaticIpConfiguration staticIpConfiguration = createStaticIpConfiguration();
        ProxyInfo proxyInfo =
                new ProxyInfo(TEST_STATIC_PROXY_HOST,
                        TEST_STATIC_PROXY_PORT,
                        TEST_STATIC_PROXY_EXCLUSION_LIST);
        return new IpConfiguration(IpConfiguration.IpAssignment.STATIC,
                IpConfiguration.ProxySettings.STATIC, staticIpConfiguration, proxyInfo);
    }

    private IpConfiguration createPartialStaticIpConfigurationWithPacProxy() {
        StaticIpConfiguration staticIpConfiguration = createPartialStaticIpConfiguration();
        ProxyInfo proxyInfo = new ProxyInfo(TEST_PAC_PROXY_LOCATION);
        return new IpConfiguration(IpConfiguration.IpAssignment.STATIC,
                IpConfiguration.ProxySettings.PAC, staticIpConfiguration, proxyInfo);
    }

    private IpConfiguration createDHCPIpConfigurationWithPacProxy() {
        ProxyInfo proxyInfo = new ProxyInfo(TEST_PAC_PROXY_LOCATION);
        return new IpConfiguration(IpConfiguration.IpAssignment.DHCP,
                IpConfiguration.ProxySettings.PAC, null, proxyInfo);
    }

    /**
     * Helper method to write a list of networks in wpa_supplicant.conf format to the output stream.
     */
    private byte[] createWpaSupplicantConfBackupData(List<WifiConfiguration> configurations) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OutputStreamWriter out = new OutputStreamWriter(bos);
        try {
            for (WifiConfiguration configuration : configurations) {
                writeConfigurationToWpaSupplicantConf(out, configuration);
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Helper method to write a network in wpa_supplicant.conf format to the output stream.
     * This was created using a sample wpa_supplicant.conf file. Using the raw key strings here
     * (instead of consts in WifiBackupRestore).
     */
    private void writeConfigurationToWpaSupplicantConf(
            OutputStreamWriter out, WifiConfiguration configuration)
            throws IOException {
        out.write("network={\n");
        out.write("        " + "ssid=" + configuration.SSID + "\n");
        String allowedKeyManagement = "";
        if (configuration.hiddenSSID) {
            out.write("        " + "scan_ssid=1" + "\n");
        }
        if (configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) {
            allowedKeyManagement += "NONE";
        }
        if (configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            allowedKeyManagement += "WPA-PSK ";
        }
        if (configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)) {
            allowedKeyManagement += "WPA-EAP ";
        }
        if (configuration.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) {
            allowedKeyManagement += "IEEE8021X ";
        }
        out.write("        " + "key_mgmt=" + allowedKeyManagement + "\n");
        if (configuration.preSharedKey != null) {
            out.write("        " + "psk=" + configuration.preSharedKey + "\n");
        }
        if (configuration.wepKeys[0] != null) {
            out.write("        " + "wep_key0=" + configuration.wepKeys[0] + "\n");
        }
        if (configuration.wepKeys[1] != null) {
            out.write("        " + "wep_key1=" + configuration.wepKeys[1] + "\n");
        }
        if (configuration.wepKeys[2] != null) {
            out.write("        " + "wep_key2=" + configuration.wepKeys[2] + "\n");
        }
        if (configuration.wepKeys[3] != null) {
            out.write("        " + "wep_key3=" + configuration.wepKeys[3] + "\n");
        }
        if (configuration.wepKeys[0] != null || configuration.wepKeys[1] != null
                || configuration.wepKeys[2] != null || configuration.wepKeys[3] != null) {
            out.write("        " + "wep_tx_keyidx=" + configuration.wepTxKeyIndex + "\n");
        }
        Map<String, String> extras = new HashMap<>();
        extras.put(WifiSupplicantControl.ID_STRING_KEY_CONFIG_KEY, configuration.configKey());
        extras.put(WifiSupplicantControl.ID_STRING_KEY_CREATOR_UID,
                Integer.toString(configuration.creatorUid));
        String idString = WifiNative.createNetworkExtra(extras);
        if (idString != null) {
            idString = "\"" + idString + "\"";
            out.write("        " + "id_str=" + idString + "\n");
        }
        out.write("}\n");
        out.write("\n");
    }

    /**
     * Helper method to write a list of networks in ipconfig.txt format to the output stream.
     */
    private byte[] createIpConfBackupData(List<WifiConfiguration> configurations) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        try {
            // write version first.
            out.writeInt(2);
            for (WifiConfiguration configuration : configurations) {
                IpConfigStore.writeConfig(out, configuration.configKey().hashCode(),
                        configuration.getIpConfiguration());
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
