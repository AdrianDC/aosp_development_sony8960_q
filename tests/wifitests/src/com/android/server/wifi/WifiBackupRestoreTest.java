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

import android.net.wifi.WifiConfiguration;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
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
    private static final String TEST_FQDN = null;
    private static final String TEST_PROVIDER_FRIENDLY_NAME = null;

    private final WifiBackupRestore mWifiBackupRestore = new WifiBackupRestore();

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
        assertConfigurationsEqual(configurations, retrievedConfigurations);
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
        assertConfigurationsEqual(configurations, retrievedConfigurations);
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
        assertConfigurationsEqual(configurations, retrievedConfigurations);
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
        assertConfigurationsEqual(configurations, retrievedConfigurations);
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
        assertConfigurationsEqual(expectedConfigurations, retrievedConfigurations);
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
    }

    private WifiConfiguration createOpenNetwork(int id) {
        String ssid = TEST_SSID + id;
        return WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                false, false, TEST_FQDN, TEST_PROVIDER_FRIENDLY_NAME,
                WifiConfigurationTestUtil.SECURITY_NONE);
    }

    private WifiConfiguration createPskNetwork(int id) {
        String ssid = TEST_SSID + id;
        WifiConfiguration configuration =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                        false, false, TEST_FQDN, TEST_PROVIDER_FRIENDLY_NAME,
                        WifiConfigurationTestUtil.SECURITY_PSK);
        configuration.preSharedKey = TEST_PSK;
        return configuration;
    }

    private WifiConfiguration createWepNetwork(int id) {
        String ssid = TEST_SSID + id;
        WifiConfiguration configuration =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                        false, false, TEST_FQDN, TEST_PROVIDER_FRIENDLY_NAME,
                        WifiConfigurationTestUtil.SECURITY_WEP);
        configuration.wepKeys = TEST_WEP_KEYS;
        configuration.wepTxKeyIndex = TEST_WEP_TX_KEY_INDEX;
        return configuration;
    }

    private WifiConfiguration createEapNetwork(int id) {
        String ssid = TEST_SSID + id;
        WifiConfiguration configuration =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                        false, false, TEST_FQDN, TEST_PROVIDER_FRIENDLY_NAME,
                        WifiConfigurationTestUtil.SECURITY_EAP);
        return configuration;
    }

    /**
     * Asserts that the 2 lists of configurations are equal
     */
    private void assertConfigurationsEqual(
            List<WifiConfiguration> expected, List<WifiConfiguration> actual) {
        assertEquals(expected.size(), actual.size());
        for (WifiConfiguration expectedConfiguration : expected) {
            String expectedConfigKey = expectedConfiguration.configKey();
            boolean didCompare = false;
            for (WifiConfiguration actualConfiguration : actual) {
                String actualConfigKey = actualConfiguration.configKey();
                if (actualConfigKey.equals(expectedConfigKey)) {
                    assertConfigurationEqual(expectedConfiguration, actualConfiguration);
                    didCompare = true;
                }
            }
            assertTrue(didCompare);
        }
    }

    /**
     * Asserts that the 2 configurations are equal
     */
    private void assertConfigurationEqual(
            WifiConfiguration expected, WifiConfiguration actual) {
        assertEquals(expected.SSID, actual.SSID);
        assertEquals(expected.BSSID, actual.BSSID);
        assertEquals(expected.preSharedKey, actual.preSharedKey);
        assertEquals(expected.wepKeys, actual.wepKeys);
        assertEquals(expected.wepTxKeyIndex, actual.wepTxKeyIndex);
        assertEquals(expected.hiddenSSID, actual.hiddenSSID);
        assertEquals(expected.allowedKeyManagement, actual.allowedKeyManagement);
        assertEquals(expected.allowedProtocols, actual.allowedProtocols);
        assertEquals(expected.allowedAuthAlgorithms, actual.allowedAuthAlgorithms);
        assertEquals(expected.shared, actual.shared);
        assertEquals(expected.creatorUid, actual.creatorUid);
    }
}
