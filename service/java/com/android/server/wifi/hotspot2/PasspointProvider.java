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

import android.net.wifi.EAPConstants;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.Credential.SimCredential;
import android.net.wifi.hotspot2.pps.Credential.UserCredential;
import android.security.Credentials;
import android.util.Base64;
import android.util.Log;

import com.android.server.wifi.IMSIParameter;
import com.android.server.wifi.SIMAccessor;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType;
import com.android.server.wifi.hotspot2.anqp.DomainNameElement;
import com.android.server.wifi.hotspot2.anqp.NAIRealmElement;
import com.android.server.wifi.hotspot2.anqp.RoamingConsortiumElement;
import com.android.server.wifi.hotspot2.anqp.ThreeGPPNetworkElement;
import com.android.server.wifi.hotspot2.anqp.eap.AuthParam;
import com.android.server.wifi.hotspot2.anqp.eap.NonEAPInnerAuth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Abstraction for Passpoint service provider.  This class contains the both static
 * Passpoint configuration data and the runtime data (e.g. blacklisted SSIDs, statistics).
 */
public class PasspointProvider {
    private static final String TAG = "PasspointProvider";

    /**
     * Used as part of alias string for certificates and keys.  The alias string is in the format
     * of: [KEY_TYPE]_HS2_[ProviderID]
     * For example: "CACERT_HS2_0", "USRCERT_HS2_0", "USRPKEY_HS2_0"
     */
    private static final String ALIAS_HS_TYPE = "HS2_";

    private final PasspointConfiguration mConfig;
    private final WifiKeyStore mKeyStore;

    // Aliases for the private keys and certificates installed in the keystore.
    private String mCaCertificateAlias;
    private String mClientPrivateKeyAlias;
    private String mClientCertificateAlias;

    /**
     * The suffix of the alias using for storing certificates and keys.  Each alias is prefix
     * with the key or certificate type.  In key/certificate installation, the full alias is
     * used.  However, the setCaCertificateAlias and setClientCertificateAlias function
     * in WifiEnterpriseConfig, the alias that it is referring to is actually the suffix, since
     * WifiEnterpriseConfig will append the appropriate prefix to that alias based on the type.
     */
    private final String mKeyStoreAliasSuffix;

    private final IMSIParameter mImsiParameter;
    private final List<String> mMatchingSIMImsiList;

    private final int mEAPMethodID;
    private final AuthParam mAuthParam;

    public PasspointProvider(PasspointConfiguration config, WifiKeyStore keyStore,
            SIMAccessor simAccessor, long providerId) {
        // Maintain a copy of the configuration to avoid it being updated by others.
        mConfig = new PasspointConfiguration(config);
        mKeyStore = keyStore;
        mKeyStoreAliasSuffix = ALIAS_HS_TYPE + providerId;

        // Setup EAP method and authentication parameter based on the credential.
        if (mConfig.getCredential().getUserCredential() != null) {
            mEAPMethodID = EAPConstants.EAP_TTLS;
            mAuthParam = new NonEAPInnerAuth(NonEAPInnerAuth.getAuthTypeID(
                    mConfig.getCredential().getUserCredential().getNonEapInnerMethod()));
            mImsiParameter = null;
            mMatchingSIMImsiList = null;
        } else if (mConfig.getCredential().getCertCredential() != null) {
            mEAPMethodID = EAPConstants.EAP_TLS;
            mAuthParam = null;
            mImsiParameter = null;
            mMatchingSIMImsiList = null;
        } else {
            mEAPMethodID = mConfig.getCredential().getSimCredential().getEapType();
            mAuthParam = null;
            mImsiParameter = IMSIParameter.build(
                    mConfig.getCredential().getSimCredential().getImsi());
            mMatchingSIMImsiList = simAccessor.getMatchingImsis(mImsiParameter);
        }
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
        if (mConfig.getCredential().getCaCertificate() != null) {
            String alias = Credentials.CA_CERTIFICATE + mKeyStoreAliasSuffix;
            if (!mKeyStore.putCertInKeyStore(alias, mConfig.getCredential().getCaCertificate())) {
                Log.e(TAG, "Failed to install CA Certificate");
                uninstallCertsAndKeys();
                return false;
            }
            mCaCertificateAlias = alias;
        }

        // Install the client private key.
        if (mConfig.getCredential().getClientPrivateKey() != null) {
            String alias = Credentials.USER_PRIVATE_KEY + mKeyStoreAliasSuffix;
            if (!mKeyStore.putKeyInKeyStore(alias,
                    mConfig.getCredential().getClientPrivateKey())) {
                Log.e(TAG, "Failed to install client private key");
                uninstallCertsAndKeys();
                return false;
            }
            mClientPrivateKeyAlias = alias;
        }

        // Install the client certificate.
        if (mConfig.getCredential().getClientCertificateChain() != null) {
            X509Certificate clientCert = getClientCertificate(
                    mConfig.getCredential().getClientCertificateChain(),
                    mConfig.getCredential().getCertCredential().getCertSha256Fingerprint());
            if (clientCert == null) {
                Log.e(TAG, "Failed to locate client certificate");
                uninstallCertsAndKeys();
                return false;
            }
            String alias = Credentials.USER_CERTIFICATE + mKeyStoreAliasSuffix;
            if (!mKeyStore.putCertInKeyStore(alias, clientCert)) {
                Log.e(TAG, "Failed to install client certificate");
                uninstallCertsAndKeys();
                return false;
            }
            mClientCertificateAlias = alias;
        }

        // Clear the keys and certificates in the configuration.
        mConfig.getCredential().setCaCertificate(null);
        mConfig.getCredential().setClientPrivateKey(null);
        mConfig.getCredential().setClientCertificateChain(null);
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
     *
     * @param anqpElements ANQP elements from the AP
     * @return {@link PasspointMatch}
     */
    public PasspointMatch match(Map<ANQPElementType, ANQPElement> anqpElements) {
        PasspointMatch providerMatch = matchProvider(anqpElements);

        // Perform authentication match against the NAI Realm.
        int authMatch = ANQPMatcher.matchNAIRealm(
                (NAIRealmElement) anqpElements.get(ANQPElementType.ANQPNAIRealm),
                mConfig.getCredential().getRealm(), mEAPMethodID, mAuthParam);

        // Auth mismatch, demote provider match.
        if (authMatch == AuthMatch.NONE) {
            return PasspointMatch.None;
        }

        // No realm match, return provider match as is.
        if ((authMatch & AuthMatch.REALM) == 0) {
            return providerMatch;
        }

        // Realm match, promote provider match to roaming if no other provider match is found.
        return providerMatch == PasspointMatch.None ? PasspointMatch.RoamingProvider
                : providerMatch;
    }

    /**
     * Generate a WifiConfiguration based on the provider's configuration.  The generated
     * WifiConfiguration will include all the necessary credentials for network connection except
     * the SSID, which should be added by the caller when the config is being used for network
     * connection.
     *
     * @return {@link WifiConfiguration}
     */
    public WifiConfiguration getWifiConfig() {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.FQDN = mConfig.getHomeSp().getFqdn();
        if (mConfig.getHomeSp().getRoamingConsortiumOis() != null) {
            wifiConfig.roamingConsortiumIds = Arrays.copyOf(
                    mConfig.getHomeSp().getRoamingConsortiumOis(),
                    mConfig.getHomeSp().getRoamingConsortiumOis().length);
        }
        wifiConfig.providerFriendlyName = mConfig.getHomeSp().getFriendlyName();
        wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
        wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);

        WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
        enterpriseConfig.setRealm(mConfig.getCredential().getRealm());
        if (mConfig.getCredential().getUserCredential() != null) {
            buildEnterpriseConfigForUserCredential(enterpriseConfig,
                    mConfig.getCredential().getUserCredential());
            setAnonymousIdentityToNaiRealm(enterpriseConfig, mConfig.getCredential().getRealm());
        } else if (mConfig.getCredential().getCertCredential() != null) {
            buildEnterpriseConfigForCertCredential(enterpriseConfig);
            setAnonymousIdentityToNaiRealm(enterpriseConfig, mConfig.getCredential().getRealm());
        } else {
            buildEnterpriseConfigForSimCredential(enterpriseConfig,
                    mConfig.getCredential().getSimCredential());
        }
        wifiConfig.enterpriseConfig = enterpriseConfig;
        return wifiConfig;
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

    /**
     * Perform a provider match based on the given ANQP elements.
     *
     * @param anqpElements List of ANQP elements
     * @return {@link PasspointMatch}
     */
    private PasspointMatch matchProvider(Map<ANQPElementType, ANQPElement> anqpElements) {
        // Domain name matching.
        if (ANQPMatcher.matchDomainName(
                (DomainNameElement) anqpElements.get(ANQPElementType.ANQPDomName),
                mConfig.getHomeSp().getFqdn(), mImsiParameter, mMatchingSIMImsiList)) {
            return PasspointMatch.HomeProvider;
        }

        // Roaming Consortium OI matching.
        if (ANQPMatcher.matchRoamingConsortium(
                (RoamingConsortiumElement) anqpElements.get(ANQPElementType.ANQPRoamingConsortium),
                mConfig.getHomeSp().getRoamingConsortiumOis())) {
            return PasspointMatch.RoamingProvider;
        }

        // 3GPP Network matching.
        if (ANQPMatcher.matchThreeGPPNetwork(
                (ThreeGPPNetworkElement) anqpElements.get(ANQPElementType.ANQP3GPPNetwork),
                mImsiParameter, mMatchingSIMImsiList)) {
            return PasspointMatch.RoamingProvider;
        }
        return PasspointMatch.None;
    }

    /**
     * Fill in WifiEnterpriseConfig with information from an user credential.
     *
     * @param config Instance of {@link WifiEnterpriseConfig}
     * @param credential Instance of {@link UserCredential}
     */
    private void buildEnterpriseConfigForUserCredential(WifiEnterpriseConfig config,
            Credential.UserCredential credential) {
        byte[] pwOctets = Base64.decode(credential.getPassword(), Base64.DEFAULT);
        String decodedPassword = new String(pwOctets, StandardCharsets.UTF_8);
        config.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
        config.setIdentity(credential.getUsername());
        config.setPassword(decodedPassword);
        config.setCaCertificateAlias(mKeyStoreAliasSuffix);
        int phase2Method = WifiEnterpriseConfig.Phase2.NONE;
        switch (credential.getNonEapInnerMethod()) {
            case "PAP":
                phase2Method = WifiEnterpriseConfig.Phase2.PAP;
                break;
            case "MS-CHAP":
                phase2Method = WifiEnterpriseConfig.Phase2.MSCHAP;
                break;
            case "MS-CHAP-V2":
                phase2Method = WifiEnterpriseConfig.Phase2.MSCHAPV2;
                break;
            default:
                // Should never happen since this is already validated when the provider is
                // added.
                Log.wtf(TAG, "Unsupported Auth: " + credential.getNonEapInnerMethod());
                break;
        }
        config.setPhase2Method(phase2Method);
    }

    /**
     * Fill in WifiEnterpriseConfig with information from a certificate credential.
     *
     * @param config Instance of {@link WifiEnterpriseConfig}
     */
    private void buildEnterpriseConfigForCertCredential(WifiEnterpriseConfig config) {
        config.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
        config.setClientCertificateAlias(mKeyStoreAliasSuffix);
        config.setCaCertificateAlias(mKeyStoreAliasSuffix);
    }

    /**
     * Fill in WifiEnterpriseConfig with information from a SIM credential.
     *
     * @param config Instance of {@link WifiEnterpriseConfig}
     * @param credential Instance of {@link SimCredential}
     */
    private void buildEnterpriseConfigForSimCredential(WifiEnterpriseConfig config,
            Credential.SimCredential credential) {
        int eapMethod = WifiEnterpriseConfig.Eap.NONE;
        switch(credential.getEapType()) {
            case EAPConstants.EAP_SIM:
                eapMethod = WifiEnterpriseConfig.Eap.SIM;
                break;
            case EAPConstants.EAP_AKA:
                eapMethod = WifiEnterpriseConfig.Eap.AKA;
                break;
            case EAPConstants.EAP_AKA_PRIME:
                eapMethod = WifiEnterpriseConfig.Eap.AKA_PRIME;
                break;
            default:
                // Should never happen since this is already validated when the provider is
                // added.
                Log.wtf(TAG, "Unsupported EAP Method: " + credential.getEapType());
                break;
        }
        config.setEapMethod(eapMethod);
        config.setPlmn(credential.getImsi());
    }

    private static void setAnonymousIdentityToNaiRealm(WifiEnterpriseConfig config, String realm) {
        /**
         * Set WPA supplicant's anonymous identity field to a string containing the NAI realm, so
         * that this value will be sent to the EAP server as part of the EAP-Response/ Identity
         * packet. WPA supplicant will reset this field after using it for the EAP-Response/Identity
         * packet, and revert to using the (real) identity field for subsequent transactions that
         * request an identity (e.g. in EAP-TTLS).
         *
         * This NAI realm value (the portion of the identity after the '@') is used to tell the
         * AAA server which AAA/H to forward packets to. The hardcoded username, "anonymous", is a
         * placeholder that is not used--it is set to this value by convention. See Section 5.1 of
         * RFC3748 for more details.
         *
         * NOTE: we do not set this value for EAP-SIM/AKA/AKA', since the EAP server expects the
         * EAP-Response/Identity packet to contain an actual, IMSI-based identity, in order to
         * identify the device.
         */
        config.setAnonymousIdentity("anonymous@" + realm);
    }
}
