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

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.IpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.os.UserManager;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.WifiConfigManagerNew}.
 */
@SmallTest
public class WifiConfigManagerNewTest {

    private static final String TEST_BSSID = "0a:08:5c:67:89:00";
    private static final long TEST_WALLCLOCK_CREATION_TIME_MILLIS = 9845637;
    private static final long TEST_WALLCLOCK_UPDATE_TIME_MILLIS = 75455637;
    private static final int TEST_CREATOR_UID = 5;
    private static final int TEST_UPDATE_UID = 4;
    private static final String TEST_CREATOR_NAME = "com.wificonfigmanagerNew.creator";
    private static final String TEST_UPDATE_NAME = "com.wificonfigmanagerNew.update";

    @Mock private Context mContext;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private Clock mClock;
    @Mock private UserManager mUserManager;
    @Mock private WifiKeyStore mWifiKeyStore;
    @Mock private WifiConfigStoreNew mWifiConfigStore;
    @Mock private PackageManager mPackageManager;

    private WifiConfigManagerNew mWifiConfigManager;

    /**
     * Setup the mocks and an instance of WifiConfigManagerNew before each test.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Set up the package name stuff & permission override.
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        doAnswer(new AnswerWithArguments() {
            public String answer(int uid) throws Exception {
                if (uid == TEST_CREATOR_UID) {
                    return TEST_CREATOR_NAME;
                } else if (uid == TEST_UPDATE_UID) {
                    return TEST_UPDATE_NAME;
                }
                fail("Unexpected UID: " + uid);
                return "";
            }
        }).when(mPackageManager).getNameForUid(anyInt());

        // Both the UID's in the test have the configuration override permission granted by
        // default. This maybe modified for particular tests if needed.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                if (uid == TEST_CREATOR_UID || uid == TEST_UPDATE_UID) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        mWifiConfigManager =
                new WifiConfigManagerNew(
                        mContext, mFrameworkFacade, mClock, mUserManager, mWifiKeyStore,
                        mWifiConfigStore);
        mWifiConfigManager.enableVerboseLogging(1);
    }

    /**
     * Verifies the addition of a single network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)}
     */
    @Test
    public void testAddSingleOpenNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        NetworkUpdateResult result = addNetworkToWifiConfigManager(openNetwork);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
    }

    /**
     * Verifies the modification of a single network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)}
     */
    @Test
    public void testUpdateSingleOpenNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        NetworkUpdateResult result = addNetworkToWifiConfigManager(openNetwork);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());

        // Now set the obtained network id in the configuration and change BSSID.
        openNetwork.networkId = result.getNetworkId();
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);

        // Update the same configuration and compare.
        result = updateNetworkToWifiConfigManager(openNetwork);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertFalse(result.isNewNetwork());

        // Now verify that the modification has been effective.
        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
    }

    /**
     * Verifies the addition of a single ephemeral network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} and verifies that
     * the {@link WifiConfigManagerNew#getSavedNetworks()} does not return this network.
     */
    @Test
    public void testAddSingleEphemeralNetwork() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        openNetwork.ephemeral = true;
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        NetworkUpdateResult result = addNetworkToWifiConfigManager(openNetwork);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        // Ensure that this is not returned in the saved network list.
        assertTrue(mWifiConfigManager.getSavedNetworks().isEmpty());
    }

    /**
     * Verifies that the modification of a single open network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} with a UID which
     * has no permission to modify the network fails.
     */
    @Test
    public void testUpdateSingleOpenNetworkFailedDueToPermissionDenied() throws Exception {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        NetworkUpdateResult result = addNetworkToWifiConfigManager(openNetwork);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());

        // Now set the obtained network id in the configuration and change BSSID.
        openNetwork.networkId = result.getNetworkId();
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);

        // Deny permission for |UPDATE_UID|.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                if (uid == TEST_CREATOR_UID) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        // Update the same configuration and ensure that the operation failed.
        result = updateNetworkToWifiConfigManager(openNetwork);
        assertTrue(result.getNetworkId() == WifiConfiguration.INVALID_NETWORK_ID);
    }

    /**
     * Verifies that the modification of a single open network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} with the creator UID
     * should always succeed.
     */
    @Test
    public void testUpdateSingleOpenNetworkSuccessWithCreatorUID() throws Exception {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        NetworkUpdateResult result = addNetworkToWifiConfigManager(openNetwork);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());

        // Now set the obtained network id in the configuration and change BSSID.
        openNetwork.networkId = result.getNetworkId();
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);

        // Deny permission for all UIDs.
        doAnswer(new AnswerWithArguments() {
            public int answer(String permName, int uid) throws Exception {
                return PackageManager.PERMISSION_DENIED;
            }
        }).when(mFrameworkFacade).checkUidPermission(anyString(), anyInt());

        // Update the same configuration using the creator UID.
        result = mWifiConfigManager.addOrUpdateNetwork(openNetwork, TEST_CREATOR_UID);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);

        // Now verify that the modification has been effective.
        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
    }

    /**
     * Verifies the addition of a single PSK network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} and verifies that
     * {@link WifiConfigManagerNew#getSavedNetworks()} masks the password.
     */
    @Test
    public void testAddSinglePskNetwork() {
        WifiConfiguration pskNetwork = WifiConfigurationTestUtil.createPskNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(pskNetwork);

        NetworkUpdateResult result = addNetworkToWifiConfigManager(pskNetwork);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        List<WifiConfiguration> retrievedSavedNetworks = mWifiConfigManager.getSavedNetworks();
        assertEquals(retrievedSavedNetworks.size(), 1);
        assertEquals(retrievedSavedNetworks.get(0).configKey(), pskNetwork.configKey());
        assertEquals(
                WifiConfigManagerNew.PASSWORD_MASK, retrievedSavedNetworks.get(0).preSharedKey);
    }

    /**
     * Verifies the addition of a single WEP network using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)} and verifies that
     * {@link WifiConfigManagerNew#getSavedNetworks()} masks the password.
     */
    @Test
    public void testAddSingleWepNetwork() {
        WifiConfiguration wepNetwork = WifiConfigurationTestUtil.createWepNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(wepNetwork);

        NetworkUpdateResult result = addNetworkToWifiConfigManager(wepNetwork);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());

        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);

        List<WifiConfiguration> retrievedSavedNetworks = mWifiConfigManager.getSavedNetworks();
        assertEquals(retrievedSavedNetworks.size(), 1);
        assertEquals(retrievedSavedNetworks.get(0).configKey(), wepNetwork.configKey());
        assertEquals(
                WifiConfigManagerNew.PASSWORD_MASK, retrievedSavedNetworks.get(0).wepKeys[0]);
        assertEquals(
                WifiConfigManagerNew.PASSWORD_MASK, retrievedSavedNetworks.get(0).wepKeys[1]);
        assertEquals(
                WifiConfigManagerNew.PASSWORD_MASK, retrievedSavedNetworks.get(0).wepKeys[2]);
        assertEquals(
                WifiConfigManagerNew.PASSWORD_MASK, retrievedSavedNetworks.get(0).wepKeys[3]);
    }

    /**
     * Verifies the modification of an IpConfiguration using
     * {@link WifiConfigManagerNew#addOrUpdateNetwork(WifiConfiguration, int)}
     */
    @Test
    public void testUpdateIpConfiguration() {
        WifiConfiguration openNetwork = WifiConfigurationTestUtil.createOpenNetwork();
        List<WifiConfiguration> networks = new ArrayList<>();
        networks.add(openNetwork);

        NetworkUpdateResult result = addNetworkToWifiConfigManager(openNetwork);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertTrue(result.isNewNetwork());
        assertTrue(result.hasIpChanged());
        assertTrue(result.hasProxyChanged());

        // Now set the obtained network id in the configuration and change BSSID.
        openNetwork.networkId = result.getNetworkId();
        assertAndSetNetworkBSSID(openNetwork, TEST_BSSID);

        // Update the same configuration and ensure that the IP configuration change flags
        // are not set.
        result = updateNetworkToWifiConfigManager(openNetwork);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertFalse(result.isNewNetwork());
        assertFalse(result.hasIpChanged());
        assertFalse(result.hasProxyChanged());

        // Change the IpConfiguration now and ensure that the IP configuration flags are set now.
        assertAndSetNetworkIpConfiguration(
                openNetwork,
                WifiConfigurationTestUtil.createStaticIpConfigurationWithStaticProxy());
        result = updateNetworkToWifiConfigManager(openNetwork);
        assertTrue(result.getNetworkId() != WifiConfiguration.INVALID_NETWORK_ID);
        assertFalse(result.isNewNetwork());
        assertTrue(result.hasIpChanged());
        assertTrue(result.hasProxyChanged());

        // Now verify that all the modifications have been effective.
        List<WifiConfiguration> retrievedNetworks =
                mWifiConfigManager.getConfiguredNetworksWithPasswords();
        WifiConfigurationTestUtil.assertConfigurationsEqualForConfigManagerAddOrUpdate(
                networks, retrievedNetworks);
    }

    /**
     * This method sets defaults in the provided WifiConfiguration object if not set
     * so that it can be used for comparison with the configuration retrieved from
     * WifiConfigManager.
     */
    private void setDefaults(WifiConfiguration configuration) {
        if (configuration.allowedAuthAlgorithms.isEmpty()) {
            configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
        }
        if (configuration.allowedProtocols.isEmpty()) {
            configuration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            configuration.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
        }
        if (configuration.allowedKeyManagement.isEmpty()) {
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        }
        if (configuration.allowedPairwiseCiphers.isEmpty()) {
            configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
        }
        if (configuration.allowedGroupCiphers.isEmpty()) {
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
        }
        if (configuration.getIpAssignment() == IpConfiguration.IpAssignment.UNASSIGNED) {
            configuration.setIpAssignment(IpConfiguration.IpAssignment.DHCP);
        }
        if (configuration.getProxySettings() == IpConfiguration.ProxySettings.UNASSIGNED) {
            configuration.setProxySettings(IpConfiguration.ProxySettings.NONE);
        }
    }

    /**
     * Modifies the provided configuration with creator uid, package name
     * and time.
     */
    private void setCreationDebugParams(WifiConfiguration configuration) {
        configuration.creatorUid = configuration.lastUpdateUid = TEST_CREATOR_UID;
        configuration.creatorName = configuration.lastUpdateName = TEST_CREATOR_NAME;
        configuration.creationTime = configuration.updateTime =
                WifiConfigManagerNew.createDebugTimeStampString(
                        TEST_WALLCLOCK_CREATION_TIME_MILLIS);
    }

    /**
     * Modifies the provided configuration with update uid, package name
     * and time.
     */
    private void setUpdateDebugParams(WifiConfiguration configuration) {
        configuration.lastUpdateUid = TEST_UPDATE_UID;
        configuration.lastUpdateName = TEST_UPDATE_NAME;
        configuration.updateTime =
                WifiConfigManagerNew.createDebugTimeStampString(TEST_WALLCLOCK_UPDATE_TIME_MILLIS);
    }

    /**
     * Modifies the provided WifiConfiguration with the specified bssid value. Also, asserts that
     * the existing |BSSID| field is not the same value as the one being set
     */
    private void assertAndSetNetworkBSSID(WifiConfiguration configuration, String bssid) {
        // Equivalent to assertNotEquals.
        if (configuration.BSSID != null) {
            assertFalse(configuration.BSSID.equals(bssid));
        } else {
            assertNotNull(bssid);
        }
        configuration.BSSID = bssid;
    }

    /**
     * Modifies the provided WifiConfiguration with the specified |IpConfiguration| object. Also,
     * asserts that the existing |IpConfiguration| field is not the same value as the one being set
     */
    private void assertAndSetNetworkIpConfiguration(
            WifiConfiguration configuration, IpConfiguration ipConfiguration) {
        // Equivalent to assertNotEquals.
        if (configuration.getIpConfiguration() != null) {
            assertFalse(configuration.getIpConfiguration().equals(ipConfiguration));
        } else {
            assertNotNull(configuration.getIpConfiguration());
        }
        configuration.setIpConfiguration(ipConfiguration);
    }

    /**
     * Adds the provided configuration to WifiConfigManager and modifies the provided configuration
     * with creator/update uid, package name and time. This also sets defaults for fields not
     * populated.
     * These fields are populated internally by WifiConfigManager and hence we need
     * to modify the configuration before we compare the added network with the retrieved network.
     */
    private NetworkUpdateResult addNetworkToWifiConfigManager(WifiConfiguration configuration) {
        when(mClock.getWallClockMillis()).thenReturn(TEST_WALLCLOCK_CREATION_TIME_MILLIS);
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(configuration, TEST_CREATOR_UID);
        setDefaults(configuration);
        setCreationDebugParams(configuration);
        return result;
    }

    /**
     * Updates the provided configuration to WifiConfigManager and modifies the provided
     * configuration with update uid, package name and time.
     * These fields are populated internally by WifiConfigManager and hence we need
     * to modify the configuration before we compare the added network with the retrieved network.
     */
    private NetworkUpdateResult updateNetworkToWifiConfigManager(WifiConfiguration configuration) {
        when(mClock.getWallClockMillis()).thenReturn(TEST_WALLCLOCK_UPDATE_TIME_MILLIS);
        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(configuration, TEST_UPDATE_UID);
        setUpdateDebugParams(configuration);
        return result;
    }
}
