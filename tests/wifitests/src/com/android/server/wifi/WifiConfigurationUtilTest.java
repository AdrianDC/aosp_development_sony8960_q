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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.UserInfo;
import android.net.wifi.FakeKeys;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;

import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.WifiConfigurationUtil}.
 */
@SmallTest
public class WifiConfigurationUtilTest {
    static final int CURRENT_USER_ID = 0;
    static final int CURRENT_USER_MANAGED_PROFILE_USER_ID = 10;
    static final int OTHER_USER_ID = 11;
    static final List<UserInfo> PROFILES = Arrays.asList(
            new UserInfo(CURRENT_USER_ID, "owner", 0),
            new UserInfo(CURRENT_USER_MANAGED_PROFILE_USER_ID, "managed profile", 0));

    /**
     * Test for {@link WifiConfigurationUtil.isVisibleToAnyProfile}.
     */
    @Test
    public void isVisibleToAnyProfile() {
        // Shared network configuration created by another user.
        final WifiConfiguration configuration = new WifiConfiguration();
        configuration.creatorUid = UserHandle.getUid(OTHER_USER_ID, 0);
        assertTrue(WifiConfigurationUtil.isVisibleToAnyProfile(configuration, PROFILES));

        // Private network configuration created by another user.
        configuration.shared = false;
        assertFalse(WifiConfigurationUtil.isVisibleToAnyProfile(configuration, PROFILES));

        // Private network configuration created by the current user.
        configuration.creatorUid = UserHandle.getUid(CURRENT_USER_ID, 0);
        assertTrue(WifiConfigurationUtil.isVisibleToAnyProfile(configuration, PROFILES));

        // Private network configuration created by the current user's managed profile.
        configuration.creatorUid = UserHandle.getUid(CURRENT_USER_MANAGED_PROFILE_USER_ID, 0);
        assertTrue(WifiConfigurationUtil.isVisibleToAnyProfile(configuration, PROFILES));
    }

    /**
     * Verify that new WifiEnterpriseConfig is detected.
     */
    @Test
    public void testEnterpriseConfigAdded() {
        EnterpriseConfig eapConfig = new EnterpriseConfig(WifiEnterpriseConfig.Eap.TTLS)
                .setPhase2(WifiEnterpriseConfig.Phase2.MSCHAPV2)
                .setIdentity("username", "password")
                .setCaCerts(new X509Certificate[]{FakeKeys.CA_CERT0});

        assertTrue(WifiConfigurationUtil.hasEnterpriseConfigChanged(
                null, eapConfig.enterpriseConfig));
    }

    /**
     * Verify WifiEnterpriseConfig eap change is detected.
     */
    @Test
    public void testEnterpriseConfigEapChangeDetected() {
        EnterpriseConfig eapConfig = new EnterpriseConfig(WifiEnterpriseConfig.Eap.TTLS);
        EnterpriseConfig peapConfig = new EnterpriseConfig(WifiEnterpriseConfig.Eap.PEAP);

        assertTrue(WifiConfigurationUtil.hasEnterpriseConfigChanged(eapConfig.enterpriseConfig,
                peapConfig.enterpriseConfig));
    }

    /**
     * Verify WifiEnterpriseConfig phase2 method change is detected.
     */
    @Test
    public void testEnterpriseConfigPhase2ChangeDetected() {
        EnterpriseConfig eapConfig =
                new EnterpriseConfig(WifiEnterpriseConfig.Eap.TTLS)
                        .setPhase2(WifiEnterpriseConfig.Phase2.MSCHAPV2);
        EnterpriseConfig papConfig =
                new EnterpriseConfig(WifiEnterpriseConfig.Eap.TTLS)
                        .setPhase2(WifiEnterpriseConfig.Phase2.PAP);

        assertTrue(WifiConfigurationUtil.hasEnterpriseConfigChanged(eapConfig.enterpriseConfig,
                papConfig.enterpriseConfig));
    }

    /**
     * Verify WifiEnterpriseConfig added Certificate is detected.
     */
    @Test
    public void testCaCertificateAddedDetected() {
        EnterpriseConfig eapConfigNoCerts = new EnterpriseConfig(WifiEnterpriseConfig.Eap.TTLS)
                .setPhase2(WifiEnterpriseConfig.Phase2.MSCHAPV2)
                .setIdentity("username", "password");

        EnterpriseConfig eapConfig1Cert = new EnterpriseConfig(WifiEnterpriseConfig.Eap.TTLS)
                .setPhase2(WifiEnterpriseConfig.Phase2.MSCHAPV2)
                .setIdentity("username", "password")
                .setCaCerts(new X509Certificate[]{FakeKeys.CA_CERT0});

        assertTrue(WifiConfigurationUtil.hasEnterpriseConfigChanged(
                eapConfigNoCerts.enterpriseConfig, eapConfig1Cert.enterpriseConfig));
    }

    /**
     * Verify WifiEnterpriseConfig Certificate change is detected.
     */
    @Test
    public void testDifferentCaCertificateDetected() {
        EnterpriseConfig eapConfig = new EnterpriseConfig(WifiEnterpriseConfig.Eap.TTLS)
                .setPhase2(WifiEnterpriseConfig.Phase2.MSCHAPV2)
                .setIdentity("username", "password")
                .setCaCerts(new X509Certificate[]{FakeKeys.CA_CERT0});

        EnterpriseConfig eapConfigNewCert = new EnterpriseConfig(WifiEnterpriseConfig.Eap.TTLS)
                .setPhase2(WifiEnterpriseConfig.Phase2.MSCHAPV2)
                .setIdentity("username", "password")
                .setCaCerts(new X509Certificate[]{FakeKeys.CA_CERT1});

        assertTrue(WifiConfigurationUtil.hasEnterpriseConfigChanged(eapConfig.enterpriseConfig,
                eapConfigNewCert.enterpriseConfig));
    }

    /**
     * Verify WifiEnterpriseConfig added Certificate changes are detected.
     */
    @Test
    public void testCaCertificateChangesDetected() {
        EnterpriseConfig eapConfig = new EnterpriseConfig(WifiEnterpriseConfig.Eap.TTLS)
                .setPhase2(WifiEnterpriseConfig.Phase2.MSCHAPV2)
                .setIdentity("username", "password")
                .setCaCerts(new X509Certificate[]{FakeKeys.CA_CERT0});

        EnterpriseConfig eapConfigAddedCert = new EnterpriseConfig(WifiEnterpriseConfig.Eap.TTLS)
                .setPhase2(WifiEnterpriseConfig.Phase2.MSCHAPV2)
                .setIdentity("username", "password")
                .setCaCerts(new X509Certificate[]{FakeKeys.CA_CERT0, FakeKeys.CA_CERT1});

        assertTrue(WifiConfigurationUtil.hasEnterpriseConfigChanged(eapConfig.enterpriseConfig,
                eapConfigAddedCert.enterpriseConfig));
    }

    /**
     * Verify that WifiEnterpriseConfig does not detect changes for identical configs.
     */
    @Test
    public void testWifiEnterpriseConfigNoChanges() {
        EnterpriseConfig eapConfig = new EnterpriseConfig(WifiEnterpriseConfig.Eap.TTLS)
                .setPhase2(WifiEnterpriseConfig.Phase2.MSCHAPV2)
                .setIdentity("username", "password")
                .setCaCerts(new X509Certificate[]{FakeKeys.CA_CERT0, FakeKeys.CA_CERT1});

        // Just to be clear that check is not against the same object
        EnterpriseConfig eapConfigSame = new EnterpriseConfig(WifiEnterpriseConfig.Eap.TTLS)
                .setPhase2(WifiEnterpriseConfig.Phase2.MSCHAPV2)
                .setIdentity("username", "password")
                .setCaCerts(new X509Certificate[]{FakeKeys.CA_CERT0, FakeKeys.CA_CERT1});

        assertFalse(WifiConfigurationUtil.hasEnterpriseConfigChanged(eapConfig.enterpriseConfig,
                eapConfigSame.enterpriseConfig));
    }

    private static class EnterpriseConfig {
        public String eap;
        public String phase2;
        public String identity;
        public String password;
        public X509Certificate[] caCerts;
        public WifiEnterpriseConfig enterpriseConfig;

        EnterpriseConfig(int eapMethod) {
            enterpriseConfig = new WifiEnterpriseConfig();
            enterpriseConfig.setEapMethod(eapMethod);
            eap = WifiEnterpriseConfig.Eap.strings[eapMethod];
        }

        public EnterpriseConfig setPhase2(int phase2Method) {
            enterpriseConfig.setPhase2Method(phase2Method);
            phase2 = "auth=" + WifiEnterpriseConfig.Phase2.strings[phase2Method];
            return this;
        }

        public EnterpriseConfig setIdentity(String identity, String password) {
            enterpriseConfig.setIdentity(identity);
            enterpriseConfig.setPassword(password);
            this.identity = identity;
            this.password = password;
            return this;
        }

        public EnterpriseConfig setCaCerts(X509Certificate[] certs) {
            enterpriseConfig.setCaCertificates(certs);
            caCerts = certs;
            return this;
        }
    }
}
