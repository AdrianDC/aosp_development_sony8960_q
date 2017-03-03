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

package com.android.server.wifi.hotspot2;

import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_BSSID;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_DATA;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_FILE;
import static android.net.wifi.WifiManager.PASSPOINT_ICON_RECEIVED_ACTION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.EAPConstants;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Base64;
import android.util.Pair;

import com.android.server.wifi.Clock;
import com.android.server.wifi.FakeKeys;
import com.android.server.wifi.IMSIParameter;
import com.android.server.wifi.SIMAccessor;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiConfigStore;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType;
import com.android.server.wifi.hotspot2.anqp.DomainNameElement;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.PasspointManager}.
 */
@SmallTest
public class PasspointManagerTest {
    private static final long BSSID = 0x112233445566L;
    private static final String ICON_FILENAME = "test";
    private static final String TEST_FQDN = "test1.test.com";
    private static final String TEST_FRIENDLY_NAME = "friendly name";
    private static final String TEST_REALM = "realm.test.com";
    private static final String TEST_IMSI = "1234*";
    private static final IMSIParameter TEST_IMSI_PARAM = IMSIParameter.build(TEST_IMSI);

    private static final String TEST_SSID = "TestSSID";
    private static final long TEST_BSSID = 0x1234L;
    private static final long TEST_HESSID = 0x5678L;
    private static final int TEST_ANQP_DOMAIN_ID = 1;

    @Mock Context mContext;
    @Mock WifiNative mWifiNative;
    @Mock WifiKeyStore mWifiKeyStore;
    @Mock Clock mClock;
    @Mock SIMAccessor mSimAccessor;
    @Mock PasspointObjectFactory mObjectFactory;
    @Mock PasspointEventHandler.Callbacks mCallbacks;
    @Mock AnqpCache mAnqpCache;
    @Mock ANQPRequestManager mAnqpRequestManager;
    @Mock WifiConfigManager mWifiConfigManager;
    @Mock WifiConfigStore mWifiConfigStore;
    @Mock PasspointConfigStoreData.DataSource mDataSource;
    PasspointManager mManager;

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        initMocks(this);
        when(mObjectFactory.makeAnqpCache(mClock)).thenReturn(mAnqpCache);
        when(mObjectFactory.makeANQPRequestManager(any(PasspointEventHandler.class), eq(mClock)))
                .thenReturn(mAnqpRequestManager);
        mManager = new PasspointManager(mContext, mWifiNative, mWifiKeyStore, mClock,
                mSimAccessor, mObjectFactory, mWifiConfigManager, mWifiConfigStore);
        ArgumentCaptor<PasspointEventHandler.Callbacks> callbacks =
                ArgumentCaptor.forClass(PasspointEventHandler.Callbacks.class);
        verify(mObjectFactory).makePasspointEventHandler(any(WifiNative.class),
                                                         callbacks.capture());
        ArgumentCaptor<PasspointConfigStoreData.DataSource> dataSource =
                ArgumentCaptor.forClass(PasspointConfigStoreData.DataSource.class);
        verify(mObjectFactory).makePasspointConfigStoreData(
                any(WifiKeyStore.class), any(SIMAccessor.class), dataSource.capture());
        mCallbacks = callbacks.getValue();
        mDataSource = dataSource.getValue();
    }

    /**
     * Verify PASSPOINT_ICON_RECEIVED_ACTION broadcast intent.
     * @param bssid BSSID of the AP
     * @param fileName Name of the icon file
     * @param data icon data byte array
     */
    private void verifyIconIntent(long bssid, String fileName, byte[] data) {
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        assertEquals(PASSPOINT_ICON_RECEIVED_ACTION, intent.getValue().getAction());
        assertTrue(intent.getValue().getExtras().containsKey(EXTRA_PASSPOINT_ICON_BSSID));
        assertEquals(bssid, intent.getValue().getExtras().getLong(EXTRA_PASSPOINT_ICON_BSSID));
        assertTrue(intent.getValue().getExtras().containsKey(EXTRA_PASSPOINT_ICON_FILE));
        assertEquals(fileName, intent.getValue().getExtras().getString(EXTRA_PASSPOINT_ICON_FILE));
        if (data != null) {
            assertTrue(intent.getValue().getExtras().containsKey(EXTRA_PASSPOINT_ICON_DATA));
            assertEquals(data,
                         intent.getValue().getExtras().getByteArray(EXTRA_PASSPOINT_ICON_DATA));
        }
    }

    /**
     * Verify that the given Passpoint configuration matches the one that's added to
     * the PasspointManager.
     *
     * @param expectedConfig The expected installed Passpoint configuration
     */
    private void verifyInstalledConfig(PasspointConfiguration expectedConfig) {
        List<PasspointConfiguration> installedConfigs = mManager.getProviderConfigs();
        assertEquals(1, installedConfigs.size());
        assertEquals(expectedConfig, installedConfigs.get(0));
    }

    /**
     * Create a mock PasspointProvider with default expectations.
     *
     * @param config The configuration associated with the provider
     * @return {@link com.android.server.wifi.hotspot2.PasspointProvider}
     */
    private PasspointProvider createMockProvider(PasspointConfiguration config) {
        PasspointProvider provider = mock(PasspointProvider.class);
        when(provider.installCertsAndKeys()).thenReturn(true);
        when(provider.getConfig()).thenReturn(config);
        return provider;
    }

    /**
     * Helper function for creating a test configuration with user credential.
     *
     * @return {@link PasspointConfiguration}
     */
    private PasspointConfiguration createTestConfigWithUserCredential() {
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(TEST_FQDN);
        homeSp.setFriendlyName(TEST_FRIENDLY_NAME);
        config.setHomeSp(homeSp);
        Credential credential = new Credential();
        credential.setRealm(TEST_REALM);
        credential.setCaCertificate(FakeKeys.CA_CERT0);
        Credential.UserCredential userCredential = new Credential.UserCredential();
        userCredential.setUsername("username");
        userCredential.setPassword("password");
        userCredential.setEapType(EAPConstants.EAP_TTLS);
        userCredential.setNonEapInnerMethod(Credential.UserCredential.AUTH_METHOD_MSCHAP);
        credential.setUserCredential(userCredential);
        config.setCredential(credential);
        return config;
    }

    /**
     * Helper function for creating a test configuration with SIM credential.
     *
     * @return {@link PasspointConfiguration}
     */
    private PasspointConfiguration createTestConfigWithSimCredential() {
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(TEST_FQDN);
        homeSp.setFriendlyName(TEST_FRIENDLY_NAME);
        config.setHomeSp(homeSp);
        Credential credential = new Credential();
        credential.setRealm(TEST_REALM);
        Credential.SimCredential simCredential = new Credential.SimCredential();
        simCredential.setImsi(TEST_IMSI);
        simCredential.setEapType(EAPConstants.EAP_SIM);
        credential.setSimCredential(simCredential);
        config.setCredential(credential);
        return config;
    }

    /**
     * Helper function for adding a test provider to the manager.  Return the mock
     * provider that's added to the manager.
     *
     * @return {@link PasspointProvider}
     */
    private PasspointProvider addTestProvider() {
        PasspointConfiguration config = createTestConfigWithUserCredential();
        PasspointProvider provider = createMockProvider(config);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mSimAccessor), anyLong())).thenReturn(provider);
        assertTrue(mManager.addOrUpdateProvider(config));

        return provider;
    }

    /**
     * Helper function for creating a mock ScanDetail.
     *
     * @return {@link ScanDetail}
     */
    private ScanDetail createMockScanDetail() {
        NetworkDetail networkDetail = mock(NetworkDetail.class);
        when(networkDetail.getSSID()).thenReturn(TEST_SSID);
        when(networkDetail.getBSSID()).thenReturn(TEST_BSSID);
        when(networkDetail.getHESSID()).thenReturn(TEST_HESSID);
        when(networkDetail.getAnqpDomainID()).thenReturn(TEST_ANQP_DOMAIN_ID);

        ScanDetail scanDetail = mock(ScanDetail.class);
        when(scanDetail.getNetworkDetail()).thenReturn(networkDetail);
        return scanDetail;
    }

    /**
     * Verify that the ANQP elements will be added to the ANQP cache on receiving a successful
     * response.
     *
     * @throws Exception
     */
    @Test
    public void anqpResponseSuccess() throws Exception {
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPDomName,
                new DomainNameElement(Arrays.asList(new String[] {"test.com"})));

        ScanDetail scanDetail = createMockScanDetail();
        ANQPNetworkKey anqpKey = ANQPNetworkKey.buildKey(TEST_SSID, TEST_BSSID, TEST_HESSID,
                TEST_ANQP_DOMAIN_ID);
        when(mAnqpRequestManager.onRequestCompleted(TEST_BSSID, true)).thenReturn(scanDetail);
        mCallbacks.onANQPResponse(TEST_BSSID, anqpElementMap);
        verify(mAnqpCache).addEntry(anqpKey, anqpElementMap);
        verify(scanDetail).propagateANQPInfo(anqpElementMap);
    }

    /**
     * Verify that no ANQP elements will be added to the ANQP cache on receiving a successful
     * response for a request that's not sent by us.
     *
     * @throws Exception
     */
    @Test
    public void anqpResponseSuccessWithUnknownRequest() throws Exception {
        Map<ANQPElementType, ANQPElement> anqpElementMap = new HashMap<>();
        anqpElementMap.put(ANQPElementType.ANQPDomName,
                new DomainNameElement(Arrays.asList(new String[] {"test.com"})));

        when(mAnqpRequestManager.onRequestCompleted(TEST_BSSID, true)).thenReturn(null);
        mCallbacks.onANQPResponse(TEST_BSSID, anqpElementMap);
        verify(mAnqpCache, never()).addEntry(any(ANQPNetworkKey.class), anyMap());
    }

    /**
     * Verify that no ANQP elements will be added to the ANQP cache on receiving a failure response.
     *
     * @throws Exception
     */
    @Test
    public void anqpResponseFailure() throws Exception {
        ANQPNetworkKey anqpKey = ANQPNetworkKey.buildKey(TEST_SSID, TEST_BSSID, TEST_HESSID,
                TEST_ANQP_DOMAIN_ID);

        ScanDetail scanDetail = createMockScanDetail();
        when(mAnqpRequestManager.onRequestCompleted(TEST_BSSID, false)).thenReturn(scanDetail);
        mCallbacks.onANQPResponse(TEST_BSSID, null);
        verify(mAnqpCache, never()).addEntry(any(ANQPNetworkKey.class), anyMap());

    }

    /**
     * Validate the broadcast intent when icon file retrieval succeeded.
     *
     * @throws Exception
     */
    @Test
    public void iconResponseSuccess() throws Exception {
        byte[] iconData = new byte[] {0x00, 0x11};
        mCallbacks.onIconResponse(BSSID, ICON_FILENAME, iconData);
        verifyIconIntent(BSSID, ICON_FILENAME, iconData);
    }

    /**
     * Validate the broadcast intent when icon file retrieval failed.
     *
     * @throws Exception
     */
    @Test
    public void iconResponseFailure() throws Exception {
        mCallbacks.onIconResponse(BSSID, ICON_FILENAME, null);
        verifyIconIntent(BSSID, ICON_FILENAME, null);
    }

    /**
     * Verify that adding a provider with a null configuration will fail.
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithNullConfig() throws Exception {
        assertFalse(mManager.addOrUpdateProvider(null));
    }

    /**
     * Verify that adding a provider with a empty configuration will fail.
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithEmptyConfig() throws Exception {
        assertFalse(mManager.addOrUpdateProvider(new PasspointConfiguration()));
    }

    /**
     * Verify taht adding a provider with an invalid credential will fail (using EAP-TLS
     * for user credential).
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithInvalidCredential() throws Exception {
        PasspointConfiguration config = createTestConfigWithUserCredential();
        // EAP-TLS not allowed for user credential.
        config.getCredential().getUserCredential().setEapType(EAPConstants.EAP_TLS);
        assertFalse(mManager.addOrUpdateProvider(config));
    }

    /**
     * Verify that adding a provider with a valid configuration and user credential will succeed.
     *
     * @throws Exception
     */
    @Test
    public void addRemoveProviderWithValidUserCredential() throws Exception {
        PasspointConfiguration config = createTestConfigWithUserCredential();
        PasspointProvider provider = createMockProvider(config);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mSimAccessor), anyLong())).thenReturn(provider);
        assertTrue(mManager.addOrUpdateProvider(config));
        verifyInstalledConfig(config);
        verify(mWifiConfigManager).saveToStore(true);
        reset(mWifiConfigManager);

        // Verify content in the data source.
        List<PasspointProvider> providers = mDataSource.getProviders();
        assertEquals(1, providers.size());
        assertEquals(config, providers.get(0).getConfig());
        // Provider index start with 0, should be 1 after adding a provider.
        assertEquals(1, mDataSource.getProviderIndex());

        // Remove the provider.
        assertTrue(mManager.removeProvider(TEST_FQDN));
        verify(provider).uninstallCertsAndKeys();
        verify(mWifiConfigManager).saveToStore(true);
        assertTrue(mManager.getProviderConfigs().isEmpty());

        // Verify content in the data source.
        assertTrue(mDataSource.getProviders().isEmpty());
        // Removing a provider should not change the provider index.
        assertEquals(1, mDataSource.getProviderIndex());
    }

    /**
     * Verify that adding a provider with a valid configuration and SIM credential will succeed.
     *
     * @throws Exception
     */
    @Test
    public void addRemoveProviderWithValidSimCredential() throws Exception {
        PasspointConfiguration config = createTestConfigWithSimCredential();
        PasspointProvider provider = createMockProvider(config);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mSimAccessor), anyLong())).thenReturn(provider);
        assertTrue(mManager.addOrUpdateProvider(config));
        verifyInstalledConfig(config);
        verify(mWifiConfigManager).saveToStore(true);
        reset(mWifiConfigManager);

        // Verify content in the data source.
        List<PasspointProvider> providers = mDataSource.getProviders();
        assertEquals(1, providers.size());
        assertEquals(config, providers.get(0).getConfig());
        // Provider index start with 0, should be 1 after adding a provider.
        assertEquals(1, mDataSource.getProviderIndex());

        // Remove the provider.
        assertTrue(mManager.removeProvider(TEST_FQDN));
        verify(provider).uninstallCertsAndKeys();
        verify(mWifiConfigManager).saveToStore(true);
        assertTrue(mManager.getProviderConfigs().isEmpty());

        // Verify content in the data source.
        assertTrue(mDataSource.getProviders().isEmpty());
        // Removing a provider should not change the provider index.
        assertEquals(1, mDataSource.getProviderIndex());
    }

    /**
     * Verify that adding a provider with the same base domain as the existing provider will
     * succeed, and verify that the existing provider is replaced by the new provider with
     * the new configuration.
     *
     * @throws Exception
     */
    @Test
    public void addProviderWithExistingConfig() throws Exception {
        // Add a provider with the original configuration.
        PasspointConfiguration origConfig = createTestConfigWithSimCredential();
        PasspointProvider origProvider = createMockProvider(origConfig);
        when(mObjectFactory.makePasspointProvider(eq(origConfig), eq(mWifiKeyStore),
                eq(mSimAccessor), anyLong())).thenReturn(origProvider);
        assertTrue(mManager.addOrUpdateProvider(origConfig));
        verifyInstalledConfig(origConfig);
        verify(mWifiConfigManager).saveToStore(true);
        reset(mWifiConfigManager);

        // Verify data source content.
        List<PasspointProvider> origProviders = mDataSource.getProviders();
        assertEquals(1, origProviders.size());
        assertEquals(origConfig, origProviders.get(0).getConfig());
        assertEquals(1, mDataSource.getProviderIndex());

        // Add another provider with the same base domain as the existing provider.
        // This should replace the existing provider with the new configuration.
        PasspointConfiguration newConfig = createTestConfigWithUserCredential();
        PasspointProvider newProvider = createMockProvider(newConfig);
        when(mObjectFactory.makePasspointProvider(eq(newConfig), eq(mWifiKeyStore),
                eq(mSimAccessor), anyLong())).thenReturn(newProvider);
        assertTrue(mManager.addOrUpdateProvider(newConfig));
        verifyInstalledConfig(newConfig);
        verify(mWifiConfigManager).saveToStore(true);

        // Verify data source content.
        List<PasspointProvider> newProviders = mDataSource.getProviders();
        assertEquals(1, newProviders.size());
        assertEquals(newConfig, newProviders.get(0).getConfig());
        assertEquals(2, mDataSource.getProviderIndex());
    }

    /**
     * Verify that adding a provider will fail when failing to install certificates and
     * key to the keystore.
     *
     * @throws Exception
     */
    @Test
    public void addProviderOnKeyInstallationFailiure() throws Exception {
        PasspointConfiguration config = createTestConfigWithUserCredential();
        PasspointProvider provider = mock(PasspointProvider.class);
        when(provider.installCertsAndKeys()).thenReturn(false);
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mSimAccessor), anyLong())).thenReturn(provider);
        assertFalse(mManager.addOrUpdateProvider(config));
    }

    /**
     * Verify that removing a non-existing provider will fail.
     *
     * @throws Exception
     */
    @Test
    public void removeNonExistingProvider() throws Exception {
        assertFalse(mManager.removeProvider(TEST_FQDN));
    }

    /**
     * Verify that an empty list will be returned when no providers are installed.
     *
     * @throws Exception
     */
    @Test
    public void matchProviderWithNoProvidersInstalled() throws Exception {
        List<Pair<PasspointProvider, PasspointMatch>> result =
                mManager.matchProvider(createMockScanDetail());
        assertTrue(result.isEmpty());
    }

    /**
     * Verify that an empty list will be returned when ANQP entry doesn't exist in the cache.
     *
     * @throws Exception
     */
    @Test
    public void matchProviderWithAnqpCacheMissed() throws Exception {
        addTestProvider();

        ANQPNetworkKey anqpKey = ANQPNetworkKey.buildKey(TEST_SSID, TEST_BSSID, TEST_HESSID,
                TEST_ANQP_DOMAIN_ID);
        when(mAnqpCache.getEntry(anqpKey)).thenReturn(null);
        List<Pair<PasspointProvider, PasspointMatch>> result =
                mManager.matchProvider(createMockScanDetail());
        // Verify that a request for ANQP elements is initiated.
        verify(mAnqpRequestManager).requestANQPElements(eq(TEST_BSSID), any(ScanDetail.class),
                anyBoolean(), anyBoolean());
        assertTrue(result.isEmpty());
    }

    /**
     * Verify that the returned list will contained an expected provider when a HomeProvider
     * is matched.
     *
     * @throws Exception
     */
    @Test
    public void matchProviderAsHomeProvider() throws Exception {
        PasspointProvider provider = addTestProvider();
        ANQPData entry = new ANQPData(mClock, null);
        ANQPNetworkKey anqpKey = ANQPNetworkKey.buildKey(TEST_SSID, TEST_BSSID, TEST_HESSID,
                TEST_ANQP_DOMAIN_ID);

        when(mAnqpCache.getEntry(anqpKey)).thenReturn(entry);
        when(provider.match(anyMap())).thenReturn(PasspointMatch.HomeProvider);
        List<Pair<PasspointProvider, PasspointMatch>> result =
                mManager.matchProvider(createMockScanDetail());
        assertEquals(1, result.size());
        assertEquals(PasspointMatch.HomeProvider, result.get(0).second);
    }

    /**
     * Verify that the returned list will contained an expected provider when a RoamingProvider
     * is matched.
     *
     * @throws Exception
     */
    @Test
    public void matchProviderAsRoamingProvider() throws Exception {
        PasspointProvider provider = addTestProvider();
        ANQPData entry = new ANQPData(mClock, null);
        ANQPNetworkKey anqpKey = ANQPNetworkKey.buildKey(TEST_SSID, TEST_BSSID, TEST_HESSID,
                TEST_ANQP_DOMAIN_ID);

        when(mAnqpCache.getEntry(anqpKey)).thenReturn(entry);
        when(provider.match(anyMap())).thenReturn(PasspointMatch.RoamingProvider);
        List<Pair<PasspointProvider, PasspointMatch>> result =
                mManager.matchProvider(createMockScanDetail());
        assertEquals(1, result.size());
        assertEquals(PasspointMatch.RoamingProvider, result.get(0).second);
        assertEquals(TEST_FQDN, provider.getConfig().getHomeSp().getFqdn());
    }

    /**
     * Verify that an empty list will be returned when there is no matching provider.
     *
     * @throws Exception
     */
    @Test
    public void matchProviderWithNoMatch() throws Exception {
        PasspointProvider provider = addTestProvider();
        ANQPData entry = new ANQPData(mClock, null);
        ANQPNetworkKey anqpKey = ANQPNetworkKey.buildKey(TEST_SSID, TEST_BSSID, TEST_HESSID,
                TEST_ANQP_DOMAIN_ID);

        when(mAnqpCache.getEntry(anqpKey)).thenReturn(entry);
        when(provider.match(anyMap())).thenReturn(PasspointMatch.None);
        List<Pair<PasspointProvider, PasspointMatch>> result =
                mManager.matchProvider(createMockScanDetail());
        assertEquals(0, result.size());
    }

    /**
     * Verify the expectations for sweepCache.
     *
     * @throws Exception
     */
    @Test
    public void sweepCache() throws Exception {
        mManager.sweepCache();
        verify(mAnqpCache).sweep();
    }

    /**
     * Verify that the provider list maintained by the PasspointManager after the list is updated
     * in the data source.
     *
     * @throws Exception
     */
    @Test
    public void verifyProvidersAfterDataSourceUpdate() throws Exception {
        // Update the provider list in the data source.
        PasspointConfiguration config = createTestConfigWithUserCredential();
        PasspointProvider provider = createMockProvider(config);
        List<PasspointProvider> providers = new ArrayList<>();
        providers.add(provider);
        mDataSource.setProviders(providers);

        // Verify the providers maintained by PasspointManager.
        assertEquals(1, mManager.getProviderConfigs().size());
        assertEquals(config, mManager.getProviderConfigs().get(0));
    }

    /**
     * Verify that the provider index used by PasspointManager is updated after it is updated in
     * the data source.
     *
     * @throws Exception
     */
    @Test
    public void verifyProviderIndexAfterDataSourceUpdate() throws Exception {
        long providerIndex = 9;
        mDataSource.setProviderIndex(providerIndex);
        assertEquals(providerIndex, mDataSource.getProviderIndex());

        // Add a provider.
        PasspointConfiguration config = createTestConfigWithUserCredential();
        PasspointProvider provider = createMockProvider(config);
        // Verify the provider ID used to create the new provider.
        when(mObjectFactory.makePasspointProvider(eq(config), eq(mWifiKeyStore),
                eq(mSimAccessor), eq(providerIndex))).thenReturn(provider);
        assertTrue(mManager.addOrUpdateProvider(config));
        verifyInstalledConfig(config);
        verify(mWifiConfigManager).saveToStore(true);
        reset(mWifiConfigManager);
    }

    /**
     * Verify that a PasspointProvider with expected PasspointConfiguration will be installed when
     * adding a legacy Passpoint configuration containing a valid user credential.
     *
     * @throws Exception
     */
    @Test
    public void addLegacyPasspointConfigWithUserCredential() throws Exception {
        // Test data.
        String fqdn = "test.com";
        String friendlyName = "Friendly Name";
        long[] rcOIs = new long[] {0x1234L, 0x2345L};
        String realm = "realm.com";
        String username = "username";
        String password = "password";
        byte[] base64EncodedPw =
                Base64.encode(password.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT);
        String encodedPasswordStr = new String(base64EncodedPw, StandardCharsets.UTF_8);
        String caCertificateAlias = "CaCert";

        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = fqdn;
        wifiConfig.providerFriendlyName = friendlyName;
        wifiConfig.roamingConsortiumIds = rcOIs;
        wifiConfig.enterpriseConfig.setIdentity(username);
        wifiConfig.enterpriseConfig.setPassword(password);
        wifiConfig.enterpriseConfig.setRealm(realm);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
        wifiConfig.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.PAP);
        wifiConfig.enterpriseConfig.setCaCertificateAlias(caCertificateAlias);

        // Setup expected {@link PasspointConfiguration}
        PasspointConfiguration passpointConfig = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(fqdn);
        homeSp.setFriendlyName(friendlyName);
        homeSp.setRoamingConsortiumOis(rcOIs);
        passpointConfig.setHomeSp(homeSp);
        Credential credential = new Credential();
        Credential.UserCredential userCredential = new Credential.UserCredential();
        userCredential.setUsername(username);
        userCredential.setPassword(encodedPasswordStr);
        userCredential.setEapType(EAPConstants.EAP_TTLS);
        userCredential.setNonEapInnerMethod("PAP");
        credential.setUserCredential(userCredential);
        credential.setRealm(realm);
        passpointConfig.setCredential(credential);

        assertTrue(PasspointManager.addLegacyPasspointConfig(wifiConfig));
        verifyInstalledConfig(passpointConfig);
    }

    /**
     * Verify that adding a legacy Passpoint configuration containing user credential will
     * fail when client certificate is not provided.
     *
     * @throws Exception
     */
    @Test
    public void addLegacyPasspointConfigWithUserCredentialWithoutCaCert() throws Exception {
        // Test data.
        String fqdn = "test.com";
        String friendlyName = "Friendly Name";
        long[] rcOIs = new long[] {0x1234L, 0x2345L};
        String realm = "realm.com";
        String username = "username";
        String password = "password";
        byte[] base64EncodedPw =
                Base64.encode(password.getBytes(StandardCharsets.UTF_8), Base64.DEFAULT);
        String encodedPasswordStr = new String(base64EncodedPw, StandardCharsets.UTF_8);

        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = fqdn;
        wifiConfig.providerFriendlyName = friendlyName;
        wifiConfig.roamingConsortiumIds = rcOIs;
        wifiConfig.enterpriseConfig.setIdentity(username);
        wifiConfig.enterpriseConfig.setPassword(password);
        wifiConfig.enterpriseConfig.setRealm(realm);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
        wifiConfig.enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.PAP);

        assertFalse(PasspointManager.addLegacyPasspointConfig(wifiConfig));
    }

    /**
     * Verify that a PasspointProvider with expected PasspointConfiguration will be installed when
     * adding a legacy Passpoint configuration containing a valid SIM credential.
     *
     * @throws Exception
     */
    @Test
    public void addLegacyPasspointConfigWithSimCredential() throws Exception {
        // Test data.
        String fqdn = "test.com";
        String friendlyName = "Friendly Name";
        long[] rcOIs = new long[] {0x1234L, 0x2345L};
        String realm = "realm.com";
        String imsi = "1234";

        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = fqdn;
        wifiConfig.providerFriendlyName = friendlyName;
        wifiConfig.roamingConsortiumIds = rcOIs;
        wifiConfig.enterpriseConfig.setRealm(realm);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.SIM);
        wifiConfig.enterpriseConfig.setPlmn(imsi);

        // Setup expected {@link PasspointConfiguration}
        PasspointConfiguration passpointConfig = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(fqdn);
        homeSp.setFriendlyName(friendlyName);
        homeSp.setRoamingConsortiumOis(rcOIs);
        passpointConfig.setHomeSp(homeSp);
        Credential credential = new Credential();
        Credential.SimCredential simCredential = new Credential.SimCredential();
        simCredential.setEapType(EAPConstants.EAP_SIM);
        simCredential.setImsi(imsi);
        credential.setSimCredential(simCredential);
        credential.setRealm(realm);
        passpointConfig.setCredential(credential);

        assertTrue(PasspointManager.addLegacyPasspointConfig(wifiConfig));
        verifyInstalledConfig(passpointConfig);
    }

    /**
     * Verify that a PasspointProvider with expected PasspointConfiguration will be installed when
     * adding a legacy Passpoint configuration containing a valid certificate credential.
     *
     * @throws Exception
     */
    @Test
    public void addLegacyPasspointConfigWithCertCredential() throws Exception {
        // Test data.
        String fqdn = "test.com";
        String friendlyName = "Friendly Name";
        long[] rcOIs = new long[] {0x1234L, 0x2345L};
        String realm = "realm.com";
        String caCertificateAlias = "CaCert";
        String clientCertificateAlias = "ClientCert";

        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = fqdn;
        wifiConfig.providerFriendlyName = friendlyName;
        wifiConfig.roamingConsortiumIds = rcOIs;
        wifiConfig.enterpriseConfig.setRealm(realm);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        wifiConfig.enterpriseConfig.setCaCertificateAlias(caCertificateAlias);
        wifiConfig.enterpriseConfig.setClientCertificateAlias(clientCertificateAlias);

        // Setup expected {@link PasspointConfiguration}
        PasspointConfiguration passpointConfig = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(fqdn);
        homeSp.setFriendlyName(friendlyName);
        homeSp.setRoamingConsortiumOis(rcOIs);
        passpointConfig.setHomeSp(homeSp);
        Credential credential = new Credential();
        Credential.CertificateCredential certCredential = new Credential.CertificateCredential();
        certCredential.setCertType(Credential.CertificateCredential.CERT_TYPE_X509V3);
        credential.setCertCredential(certCredential);
        credential.setRealm(realm);
        passpointConfig.setCredential(credential);

        assertTrue(PasspointManager.addLegacyPasspointConfig(wifiConfig));
        verifyInstalledConfig(passpointConfig);
    }

    /**
     * Verify that adding a legacy Passpoint configuration containing certificate credential will
     * fail when CA certificate is not provided.
     *
     * @throws Exception
     */
    @Test
    public void addLegacyPasspointConfigWithCertCredentialWithoutCaCert() throws Exception {
        // Test data.
        String fqdn = "test.com";
        String friendlyName = "Friendly Name";
        long[] rcOIs = new long[] {0x1234L, 0x2345L};
        String realm = "realm.com";
        String clientCertificateAlias = "ClientCert";

        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = fqdn;
        wifiConfig.providerFriendlyName = friendlyName;
        wifiConfig.roamingConsortiumIds = rcOIs;
        wifiConfig.enterpriseConfig.setRealm(realm);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        wifiConfig.enterpriseConfig.setClientCertificateAlias(clientCertificateAlias);

        assertFalse(PasspointManager.addLegacyPasspointConfig(wifiConfig));
    }

    /**
     * Verify that adding a legacy Passpoint configuration containing certificate credential will
     * fail when client certificate is not provided.
     *
     * @throws Exception
     */
    @Test
    public void addLegacyPasspointConfigWithCertCredentialWithoutClientCert() throws Exception {
        // Test data.
        String fqdn = "test.com";
        String friendlyName = "Friendly Name";
        long[] rcOIs = new long[] {0x1234L, 0x2345L};
        String realm = "realm.com";
        String caCertificateAlias = "CaCert";

        // Setup WifiConfiguration for legacy Passpoint configuraiton.
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = fqdn;
        wifiConfig.providerFriendlyName = friendlyName;
        wifiConfig.roamingConsortiumIds = rcOIs;
        wifiConfig.enterpriseConfig.setRealm(realm);
        wifiConfig.enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        wifiConfig.enterpriseConfig.setCaCertificateAlias(caCertificateAlias);

        assertFalse(PasspointManager.addLegacyPasspointConfig(wifiConfig));
    }
}
