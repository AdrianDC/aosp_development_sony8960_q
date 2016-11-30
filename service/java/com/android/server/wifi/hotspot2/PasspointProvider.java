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

import android.net.wifi.hotspot2.PasspointConfiguration;
import android.security.Credentials;
import android.util.Log;

import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Map;

/**
 * Abstraction for Passpoint service provider.  This class contains the both static
 * Passpoint configuration data and the runtime data (e.g. blacklisted SSIDs, statistics).
 */
public class PasspointProvider {
    private static final String TAG = "PasspointProvider";

    // Prefix for certificates and keys aliases.
    private static final String ALIAS_PREFIX = "HS2_";

    private final PasspointConfiguration mConfig;
    private final WifiKeyStore mKeyStore;

    // Unique identifier for this provider. Used as part of the alias names for identifying
    // certificates and keys installed on the keystore.
    private final long mProviderId;

    // Aliases for the private keys and certificates installed in the keystore.
    private String mCaCertificateAlias;
    private String mClientPrivateKeyAlias;
    private String mClientCertificateAlias;

    public PasspointProvider(PasspointConfiguration config, WifiKeyStore keyStore,
            long providerId) {
        // Maintain a copy of the configuration to avoid it being updated by others.
        mConfig = new PasspointConfiguration(config);
        mKeyStore = keyStore;
        mProviderId = providerId;
    }

    public PasspointConfiguration getConfig() {
        // Return a copy of the configuration to avoid it being updated by others.
        return new PasspointConfiguration(mConfig);
    }

    public String getCaCertificateAlias() {
        return mCaCertificateAlias;
    }

    public String getClientPrivateKeyAlias() {
        return mClientPrivateKeyAlias;
    }

    public String getClientCertificateAlias() {
        return mClientCertificateAlias;
    }

    /**
     * Install certificates and key based on current configuration.
     * Note: the certificates and keys in the configuration will get cleared once
     * they're installed in the keystore.
     *
     * @return true on success
     */
    public boolean installCertsAndKeys() {
        // Install CA certificate.
        if (mConfig.credential.caCertificate != null) {
            String alias = formatAliasName(Credentials.CA_CERTIFICATE, mProviderId);
            if (!mKeyStore.putCertInKeyStore(alias, mConfig.credential.caCertificate)) {
                Log.e(TAG, "Failed to install CA Certificate");
                uninstallCertsAndKeys();
                return false;
            }
            mCaCertificateAlias = alias;
        }

        // Install the client private key.
        if (mConfig.credential.clientPrivateKey != null) {
            String alias = formatAliasName(Credentials.USER_PRIVATE_KEY, mProviderId);
            if (!mKeyStore.putKeyInKeyStore(alias, mConfig.credential.clientPrivateKey)) {
                Log.e(TAG, "Failed to install client private key");
                uninstallCertsAndKeys();
                return false;
            }
            mClientPrivateKeyAlias = alias;
        }

        // Install the client certificate.
        if (mConfig.credential.clientCertificateChain != null) {
            X509Certificate clientCert =
                    getClientCertificate(mConfig.credential.clientCertificateChain,
                                         mConfig.credential.certCredential.certSha256FingerPrint);
            if (clientCert == null) {
                Log.e(TAG, "Failed to locate client certificate");
                uninstallCertsAndKeys();
                return false;
            }
            String alias = formatAliasName(Credentials.USER_CERTIFICATE, mProviderId);
            if (!mKeyStore.putCertInKeyStore(alias, clientCert)) {
                Log.e(TAG, "Failed to install client certificate");
                uninstallCertsAndKeys();
                return false;
            }
            mClientCertificateAlias = alias;
        }

        // Clear the keys and certificates in the configuration.
        mConfig.credential.caCertificate = null;
        mConfig.credential.clientPrivateKey = null;
        mConfig.credential.clientCertificateChain = null;
        return true;
    }

    /**
     * Remove any installed certificates and key.
     */
    public void uninstallCertsAndKeys() {
        if (mCaCertificateAlias != null) {
            if (!mKeyStore.removeEntryFromKeyStore(mCaCertificateAlias)) {
                Log.e(TAG, "Failed to remove entry: " + mCaCertificateAlias);
            }
            mCaCertificateAlias = null;
        }
        if (mClientPrivateKeyAlias != null) {
            if (!mKeyStore.removeEntryFromKeyStore(mClientPrivateKeyAlias)) {
                Log.e(TAG, "Failed to remove entry: " + mClientPrivateKeyAlias);
            }
            mClientPrivateKeyAlias = null;
        }
        if (mClientCertificateAlias != null) {
            if (!mKeyStore.removeEntryFromKeyStore(mClientCertificateAlias)) {
                Log.e(TAG, "Failed to remove entry: " + mClientCertificateAlias);
            }
            mClientCertificateAlias = null;
        }
    }

    /**
     * Return the matching status with the given AP, based on the ANQP elements from the AP.
     * @param anqpElements ANQP elements from the AP
     * @return {@link com.android.server.wifi.hotspot2.PasspointMatch}
     */
    public PasspointMatch match(Map<ANQPElementType, ANQPElement> anqpElements) {
        // TODO(b/33246489): To be implemented.
        return PasspointMatch.None;
    }

    /**
     * Create and return a certificate or key alias name based on the given prefix and uid.
     *
     * @param type The key or certificate type string
     * @param uid The UID of the alias
     * @return String
     */
    private static String formatAliasName(String type, long uid) {
        return type + ALIAS_PREFIX + uid;
    }

    /**
     * Retrieve the client certificate from the certificates chain.  The certificate
     * with the matching SHA256 digest is the client certificate.
     *
     * @param certChain The client certificates chain
     * @param expectedSha256Fingerprint The expected SHA256 digest of the client certificate
     * @return {@link java.security.cert.X509Certificate}
     */
    private static X509Certificate getClientCertificate(X509Certificate[] certChain,
            byte[] expectedSha256Fingerprint) {
        if (certChain == null) {
            return null;
        }
        try {
            MessageDigest digester = MessageDigest.getInstance("SHA-256");
            for (X509Certificate certificate : certChain) {
                digester.reset();
                byte[] fingerprint = digester.digest(certificate.getEncoded());
                if (Arrays.equals(expectedSha256Fingerprint, fingerprint)) {
                    return certificate;
                }
            }
        } catch (CertificateEncodingException | NoSuchAlgorithmException e) {
            return null;
        }

        return null;
    }
}
