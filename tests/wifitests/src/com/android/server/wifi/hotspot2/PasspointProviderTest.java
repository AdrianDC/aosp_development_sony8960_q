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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSP;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.FakeKeys;
import com.android.server.wifi.WifiKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.PasspointProvider}.
 */
@SmallTest
public class PasspointProviderTest {
    private static final long PROVIDER_ID = 12L;
    private static final String CA_CERTIFICATE_ALIAS = "CACERT_HS2_12";
    private static final String CLIENT_CERTIFICATE_ALIAS = "USRCERT_HS2_12";
    private static final String CLIENT_PRIVATE_KEY_ALIAS = "USRPKEY_HS2_12";

    @Mock WifiKeyStore mKeyStore;
    PasspointProvider mProvider;

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        initMocks(this);
    }

    /**
     * Helper function for creating a provider instance for testing.
     *
     * @param config The configuration associated with the provider
     * @return {@link com.android.server.wifi.hotspot2.PasspointProvider}
     */
    private PasspointProvider createProvider(PasspointConfiguration config) {
        return new PasspointProvider(config, mKeyStore, PROVIDER_ID);
    }

    /**
     * Verify that the configuration associated with the provider is the same or not the same
     * as the expected configuration.
     *
     * @param expectedConfig The expected configuration
     * @param equals Flag indicating equality or inequality check
     */
    private void verifyInstalledConfig(PasspointConfiguration expectedConfig, boolean equals) {
        PasspointConfiguration actualConfig = mProvider.getConfig();
        if (equals) {
            assertTrue(actualConfig.equals(expectedConfig));
        } else {
            assertFalse(actualConfig.equals(expectedConfig));
        }
    }

    /**
     * Verify that modification to the configuration used for creating PasspointProvider
     * will not change the configuration stored inside the PasspointProvider.
     *
     * @throws Exception
     */
    @Test
    public void verifyModifyOriginalConfig() throws Exception {
        // Create a dummy PasspointConfiguration.
        PasspointConfiguration config = new PasspointConfiguration();
        config.homeSp = new HomeSP();
        config.homeSp.fqdn = "test1";
        mProvider = createProvider(config);
        verifyInstalledConfig(config, true);

        // Modify the original configuration, the configuration maintained by the provider
        // should be unchanged.
        config.homeSp.fqdn = "test2";
        verifyInstalledConfig(config, false);
    }

    /**
     * Verify that modification to the configuration retrieved from the PasspointProvider
     * will not change the configuration stored inside the PasspointProvider.
     *
     * @throws Exception
     */
    @Test
    public void verifyModifyRetrievedConfig() throws Exception {
        // Create a dummy PasspointConfiguration.
        PasspointConfiguration config = new PasspointConfiguration();
        config.homeSp = new HomeSP();
        config.homeSp.fqdn = "test1";
        mProvider = createProvider(config);
        verifyInstalledConfig(config, true);

        // Modify the retrieved configuration, verify the configuration maintained by the
        // provider should be unchanged.
        PasspointConfiguration retrievedConfig = mProvider.getConfig();
        retrievedConfig.homeSp.fqdn = "test2";
        verifyInstalledConfig(retrievedConfig, false);
    }

    /**
     * Verify a successful installation of certificates and key.
     *
     * @throws Exception
     */
    @Test
    public void installCertsAndKeysSuccess() throws Exception {
        // Create a dummy configuration with certificate credential.
        PasspointConfiguration config = new PasspointConfiguration();
        config.credential = new Credential();
        config.credential.certCredential = new Credential.CertificateCredential();
        config.credential.certCredential.certSha256FingerPrint =
                MessageDigest.getInstance("SHA-256").digest(FakeKeys.CLIENT_CERT.getEncoded());
        config.credential.caCertificate = FakeKeys.CA_CERT0;
        config.credential.clientPrivateKey = FakeKeys.RSA_KEY1;
        config.credential.clientCertificateChain = new X509Certificate[] {FakeKeys.CLIENT_CERT};
        mProvider = createProvider(config);

        // Install client certificate and key to the keystore successfully.
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_ALIAS, FakeKeys.CA_CERT0))
                .thenReturn(true);
        when(mKeyStore.putKeyInKeyStore(CLIENT_PRIVATE_KEY_ALIAS, FakeKeys.RSA_KEY1))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(CLIENT_CERTIFICATE_ALIAS, FakeKeys.CLIENT_CERT))
                .thenReturn(true);
        assertTrue(mProvider.installCertsAndKeys());

        // Verify client certificate and key in the configuration gets cleared and aliases
        // are set correctly.
        PasspointConfiguration curConfig = mProvider.getConfig();
        assertTrue(curConfig.credential.caCertificate == null);
        assertTrue(curConfig.credential.clientPrivateKey == null);
        assertTrue(curConfig.credential.clientCertificateChain == null);
        assertTrue(mProvider.getCaCertificateAlias().equals(CA_CERTIFICATE_ALIAS));
        assertTrue(mProvider.getClientPrivateKeyAlias().equals(CLIENT_PRIVATE_KEY_ALIAS));
        assertTrue(mProvider.getClientCertificateAlias().equals(CLIENT_CERTIFICATE_ALIAS));
    }

    /**
     * Verify a failure installation of certificates and key.
     *
     * @throws Exception
     */
    @Test
    public void installCertsAndKeysFailure() throws Exception {
        // Create a dummy configuration with certificate credential.
        PasspointConfiguration config = new PasspointConfiguration();
        config.credential = new Credential();
        config.credential.certCredential = new Credential.CertificateCredential();
        config.credential.certCredential.certSha256FingerPrint =
                MessageDigest.getInstance("SHA-256").digest(FakeKeys.CLIENT_CERT.getEncoded());
        config.credential.caCertificate = FakeKeys.CA_CERT0;
        config.credential.clientPrivateKey = FakeKeys.RSA_KEY1;
        config.credential.clientCertificateChain = new X509Certificate[] {FakeKeys.CLIENT_CERT};
        mProvider = createProvider(config);

        // Failed to install client certificate to the keystore.
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_ALIAS, FakeKeys.CA_CERT0))
                .thenReturn(true);
        when(mKeyStore.putKeyInKeyStore(CLIENT_PRIVATE_KEY_ALIAS, FakeKeys.RSA_KEY1))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(CLIENT_CERTIFICATE_ALIAS, FakeKeys.CLIENT_CERT))
                .thenReturn(false);
        assertFalse(mProvider.installCertsAndKeys());

        // Verify certificates and key in the configuration are not cleared and aliases
        // are not set.
        PasspointConfiguration curConfig = mProvider.getConfig();
        assertTrue(curConfig.credential.caCertificate != null);
        assertTrue(curConfig.credential.clientCertificateChain != null);
        assertTrue(curConfig.credential.clientPrivateKey != null);
        assertTrue(mProvider.getCaCertificateAlias() == null);
        assertTrue(mProvider.getClientPrivateKeyAlias() == null);
        assertTrue(mProvider.getClientCertificateAlias() == null);
    }

    /**
     * Verify a successful uninstallation of certificates and key.
     */
    @Test
    public void uninstallCertsAndKeys() throws Exception {
        // Create a dummy configuration with certificate credential.
        PasspointConfiguration config = new PasspointConfiguration();
        config.credential = new Credential();
        config.credential.certCredential = new Credential.CertificateCredential();
        config.credential.certCredential.certSha256FingerPrint =
                MessageDigest.getInstance("SHA-256").digest(FakeKeys.CLIENT_CERT.getEncoded());
        config.credential.caCertificate = FakeKeys.CA_CERT0;
        config.credential.clientPrivateKey = FakeKeys.RSA_KEY1;
        config.credential.clientCertificateChain = new X509Certificate[] {FakeKeys.CLIENT_CERT};
        mProvider = createProvider(config);

        // Install client certificate and key to the keystore successfully.
        when(mKeyStore.putCertInKeyStore(CA_CERTIFICATE_ALIAS, FakeKeys.CA_CERT0))
                .thenReturn(true);
        when(mKeyStore.putKeyInKeyStore(CLIENT_PRIVATE_KEY_ALIAS, FakeKeys.RSA_KEY1))
                .thenReturn(true);
        when(mKeyStore.putCertInKeyStore(CLIENT_CERTIFICATE_ALIAS, FakeKeys.CLIENT_CERT))
                .thenReturn(true);
        assertTrue(mProvider.installCertsAndKeys());
        assertTrue(mProvider.getCaCertificateAlias().equals(CA_CERTIFICATE_ALIAS));
        assertTrue(mProvider.getClientPrivateKeyAlias().equals(CLIENT_PRIVATE_KEY_ALIAS));
        assertTrue(mProvider.getClientCertificateAlias().equals(CLIENT_CERTIFICATE_ALIAS));

        // Uninstall certificates and key from the keystore.
        mProvider.uninstallCertsAndKeys();
        verify(mKeyStore).removeEntryFromKeyStore(CA_CERTIFICATE_ALIAS);
        verify(mKeyStore).removeEntryFromKeyStore(CLIENT_CERTIFICATE_ALIAS);
        verify(mKeyStore).removeEntryFromKeyStore(CLIENT_PRIVATE_KEY_ALIAS);
        assertTrue(mProvider.getCaCertificateAlias() == null);
        assertTrue(mProvider.getClientPrivateKeyAlias() == null);
        assertTrue(mProvider.getClientCertificateAlias() == null);
    }
}
