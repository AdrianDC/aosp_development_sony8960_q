/*
 * Copyright (C) 2017 The Android Open Source Project
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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaNetwork;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatus;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatusCode;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.text.TextUtils;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for SupplicantStaNetworkHal
 */
public class SupplicantStaNetworkHalTest {
    private static final String TAG = "SupplicantStaNetworkHalTest";

    private SupplicantStaNetworkHal mSupplicantNetwork;
    private SupplicantStatus mStatusSuccess;
    private SupplicantStatus mStatusFailure;
    @Mock private ISupplicantStaNetwork mISupplicantStaNetworkMock;
    @Mock private HandlerThread mHandlerThread;
    private TestLooper mTestLooper;
    private SupplicantNetworkVariables mSupplicantVariables;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestLooper = new TestLooper();
        mStatusSuccess = createSupplicantStatus(SupplicantStatusCode.SUCCESS);
        mStatusFailure = createSupplicantStatus(SupplicantStatusCode.FAILURE_UNKNOWN);
        when(mHandlerThread.getLooper()).thenReturn(mTestLooper.getLooper());
        mSupplicantVariables = new SupplicantNetworkVariables();
        setupISupplicantNetworkMock();

        mSupplicantNetwork =
                new SupplicantStaNetworkHal(mISupplicantStaNetworkMock, mHandlerThread);
    }

    /**
     * Tests the saving of WifiConfiguration to wpa_supplicant.
     */
    @Test
    public void testOpenNetworkWifiConfigurationSaveLoad() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createOpenHiddenNetwork();
        config.updateIdentifier = "45";
        testWifiConfigurationSaveLoad(config);
    }

    /**
     * Tests the saving/loading of WifiConfiguration to wpa_supplicant.
     */
    @Test
    public void testPskNetworkWifiConfigurationSaveLoad() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createPskNetwork();
        config.requirePMF = true;
        testWifiConfigurationSaveLoad(config);
    }

    /**
     * Tests the saving/loading of WifiConfiguration to wpa_supplicant.
     */
    @Test
    public void testWepNetworkWifiConfigurationSaveLoad() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createWepHiddenNetwork();
        config.BSSID = "34:45:19:09:45";
        testWifiConfigurationSaveLoad(config);
    }

    /**
     * Tests the saving of WifiConfiguration to wpa_supplicant.
     */
    @Test
    public void testEapPeapGtcNetworkWifiConfigurationSaveLoad() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork();
        config.enterpriseConfig =
                WifiConfigurationTestUtil.createPEAPWifiEnterpriseConfigWithGTCPhase2();
        testWifiConfigurationSaveLoad(config);
    }

    /**
     * Tests the saving of WifiConfiguration to wpa_supplicant.
     */
    @Test
    public void testEapTlsNoneNetworkWifiConfigurationSaveLoad() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork();
        config.enterpriseConfig =
                WifiConfigurationTestUtil.createTLSWifiEnterpriseConfigWithNonePhase2();
        testWifiConfigurationSaveLoad(config);
    }

    /**
     * Tests the saving of WifiConfiguration to wpa_supplicant.
     */
    @Test
    public void testEapTlsNoneClientCertNetworkWifiConfigurationSaveLoad() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork();
        config.enterpriseConfig =
                WifiConfigurationTestUtil.createTLSWifiEnterpriseConfigWithNonePhase2();
        config.enterpriseConfig.setClientCertificateAlias("test_alias");
        testWifiConfigurationSaveLoad(config);
    }

    /**
     * Tests the failure to load ssid aborts the loading of network variables.
     */
    @Test
    public void testSsidLoadFailure() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createWepHiddenNetwork();
        assertTrue(mSupplicantNetwork.saveWifiConfiguration(config));

        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getSsidCallback cb) throws RemoteException {
                cb.onValues(mStatusFailure, mSupplicantVariables.ssid);
            }
        }).when(mISupplicantStaNetworkMock)
                .getSsid(any(ISupplicantStaNetwork.getSsidCallback.class));

        WifiConfiguration loadConfig = new WifiConfiguration();
        Map<String, String> networkExtras = new HashMap<>();
        assertFalse(mSupplicantNetwork.loadWifiConfiguration(loadConfig, networkExtras));
    }

    /**
     * Tests the failure to save ssid.
     */
    @Test
    public void testSsidSaveFailure() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createWepHiddenNetwork();

        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(ArrayList<Byte> ssid) throws RemoteException {
                return mStatusFailure;
            }
        }).when(mISupplicantStaNetworkMock).setSsid(any(ArrayList.class));

        assertFalse(mSupplicantNetwork.saveWifiConfiguration(config));
    }

    /**
     * Tests the failure to save invalid key mgmt (unknown bit set in the
     * {@link WifiConfiguration#allowedKeyManagement} being saved).
     */
    @Test
    public void testInvalidKeyMgmtSaveFailure() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createWepHiddenNetwork();
        config.allowedKeyManagement.set(10);
        try {
            assertFalse(mSupplicantNetwork.saveWifiConfiguration(config));
        } catch (IllegalArgumentException e) {
            return;
        }
        assertTrue(false);
    }

    /**
     * Tests the failure to load invalid key mgmt (unkown bit set in the supplicant network key_mgmt
     * variable read).
     */
    @Test
    public void testInvalidKeyMgmtLoadFailure() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createWepHiddenNetwork();
        assertTrue(mSupplicantNetwork.saveWifiConfiguration(config));

        // Modify the supplicant variable directly.
        mSupplicantVariables.keyMgmtMask = 0xFFFFF;
        WifiConfiguration loadConfig = new WifiConfiguration();
        Map<String, String> networkExtras = new HashMap<>();
        try {
            assertFalse(mSupplicantNetwork.loadWifiConfiguration(loadConfig, networkExtras));
        } catch (IllegalArgumentException e) {
            return;
        }
        assertTrue(false);
    }

    /**
     * Tests the failure to save invalid bssid (less than 6 bytes in the
     * {@link WifiConfiguration#BSSID} being saved).
     */
    @Test
    public void testInvalidBssidSaveFailure() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createWepHiddenNetwork();
        config.getNetworkSelectionStatus().setNetworkSelectionBSSID("45:34:23:12");
        try {
            assertFalse(mSupplicantNetwork.saveWifiConfiguration(config));
        } catch (IllegalArgumentException e) {
            return;
        }
        assertTrue(false);
    }

    /**
     * Tests the failure to load invalid bssid (less than 6 bytes in the supplicant bssid variable
     * read).
     */
    @Test
    public void testInvalidBssidLoadFailure() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createWepHiddenNetwork();
        assertTrue(mSupplicantNetwork.saveWifiConfiguration(config));

        // Modify the supplicant variable directly.
        mSupplicantVariables.bssid = new byte[3];
        WifiConfiguration loadConfig = new WifiConfiguration();
        Map<String, String> networkExtras = new HashMap<>();
        try {
            assertFalse(mSupplicantNetwork.loadWifiConfiguration(loadConfig, networkExtras));
        } catch (IllegalArgumentException e) {
            return;
        }
        assertTrue(false);
    }

    /**
     * Tests the loading of invalid ssid from wpa_supplicant.
     */
    @Test
    public void testInvalidSsidLoadFailure() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createWepHiddenNetwork();
        assertTrue(mSupplicantNetwork.saveWifiConfiguration(config));

        // Modify the supplicant variable directly.
        mSupplicantVariables.ssid = null;

        WifiConfiguration loadConfig = new WifiConfiguration();
        Map<String, String> networkExtras = new HashMap<>();
        assertFalse(mSupplicantNetwork.loadWifiConfiguration(loadConfig, networkExtras));
    }

    /**
     * Tests the loading of invalid eap method from wpa_supplicant.
     * Invalid eap method is assumed to be a non enterprise network. So, the loading should
     * succeed as a non-enterprise network.
     */
    @Test
    public void testInvalidEapMethodLoadFailure() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork();
        config.enterpriseConfig =
                WifiConfigurationTestUtil.createPEAPWifiEnterpriseConfigWithGTCPhase2();
        assertTrue(mSupplicantNetwork.saveWifiConfiguration(config));

        // Modify the supplicant variable directly.
        mSupplicantVariables.eapMethod = -1;

        WifiConfiguration loadConfig = new WifiConfiguration();
        Map<String, String> networkExtras = new HashMap<>();
        assertTrue(mSupplicantNetwork.loadWifiConfiguration(loadConfig, networkExtras));
    }

    /**
     * Tests the loading of invalid eap phase2 method from wpa_supplicant.
     */
    @Test
    public void testInvalidEapPhase2MethodLoadFailure() throws Exception {
        WifiConfiguration config = WifiConfigurationTestUtil.createEapNetwork();
        config.enterpriseConfig =
                WifiConfigurationTestUtil.createPEAPWifiEnterpriseConfigWithGTCPhase2();
        assertTrue(mSupplicantNetwork.saveWifiConfiguration(config));

        // Modify the supplicant variable directly.
        mSupplicantVariables.eapPhase2Method = -1;

        WifiConfiguration loadConfig = new WifiConfiguration();
        Map<String, String> networkExtras = new HashMap<>();
        assertFalse(mSupplicantNetwork.loadWifiConfiguration(loadConfig, networkExtras));
    }

    private void testWifiConfigurationSaveLoad(WifiConfiguration config) {
        assertTrue(mSupplicantNetwork.saveWifiConfiguration(config));
        WifiConfiguration loadConfig = new WifiConfiguration();
        Map<String, String> networkExtras = new HashMap<>();
        assertTrue(mSupplicantNetwork.loadWifiConfiguration(loadConfig, networkExtras));
        WifiConfigurationTestUtil.assertConfigurationEqualForSupplicant(config, loadConfig);
        assertEquals(config.configKey(),
                networkExtras.get(SupplicantStaNetworkHal.ID_STRING_KEY_CONFIG_KEY));
        assertEquals(
                config.creatorUid,
                Integer.parseInt(networkExtras.get(
                        SupplicantStaNetworkHal.ID_STRING_KEY_CREATOR_UID)));
        // There is no getter for this one, so check the supplicant variable.
        if (!TextUtils.isEmpty(config.updateIdentifier)) {
            assertEquals(Integer.parseInt(config.updateIdentifier),
                    mSupplicantVariables.updateIdentifier);
        }
        // There is no getter for this one, so check the supplicant variable.
        String oppKeyCaching =
                config.enterpriseConfig.getFieldValue(WifiEnterpriseConfig.OPP_KEY_CACHING);
        if (!TextUtils.isEmpty(oppKeyCaching)) {
            assertEquals(
                    Integer.parseInt(oppKeyCaching) == 1 ? true : false,
                    mSupplicantVariables.eapProactiveKeyCaching);
        }
    }

    /**
     * Sets up the HIDL interface mock with all the setters/getter values.
     * Note: This only sets up the mock to return success on all methods.
     */
    private void setupISupplicantNetworkMock() throws Exception {
        /** SSID */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(ArrayList<Byte> ssid) throws RemoteException {
                mSupplicantVariables.ssid = ssid;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setSsid(any(ArrayList.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getSsidCallback cb) throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.ssid);
            }
        }).when(mISupplicantStaNetworkMock)
                .getSsid(any(ISupplicantStaNetwork.getSsidCallback.class));

        /** BSSID */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(byte[] bssid) throws RemoteException {
                mSupplicantVariables.bssid = bssid;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setBssid(any(byte[].class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getBssidCallback cb) throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.bssid);
            }
        }).when(mISupplicantStaNetworkMock)
                .getBssid(any(ISupplicantStaNetwork.getBssidCallback.class));

        /** Scan SSID (Is Hidden Network?) */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(boolean enable) throws RemoteException {
                mSupplicantVariables.scanSsid = enable;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setScanSsid(any(boolean.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getScanSsidCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.scanSsid);
            }
        }).when(mISupplicantStaNetworkMock)
                .getScanSsid(any(ISupplicantStaNetwork.getScanSsidCallback.class));

        /** Require PMF*/
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(boolean enable) throws RemoteException {
                mSupplicantVariables.requirePmf = enable;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setRequirePmf(any(boolean.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getRequirePmfCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.requirePmf);
            }
        }).when(mISupplicantStaNetworkMock)
                .getRequirePmf(any(ISupplicantStaNetwork.getRequirePmfCallback.class));

        /** PSK pass phrase*/
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(String pskPassphrase) throws RemoteException {
                mSupplicantVariables.pskPassphrase = pskPassphrase;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setPskPassphrase(any(String.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getPskPassphraseCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.pskPassphrase);
            }
        }).when(mISupplicantStaNetworkMock)
                .getPskPassphrase(any(ISupplicantStaNetwork.getPskPassphraseCallback.class));

        /** WEP keys **/
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(int keyIdx, ArrayList<Byte> key) throws RemoteException {
                mSupplicantVariables.wepKey[keyIdx] = key;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setWepKey(any(int.class), any(ArrayList.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(int keyIdx, ISupplicantStaNetwork.getWepKeyCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.wepKey[keyIdx]);
            }
        }).when(mISupplicantStaNetworkMock)
                .getWepKey(any(int.class), any(ISupplicantStaNetwork.getWepKeyCallback.class));

        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(int keyIdx) throws RemoteException {
                mSupplicantVariables.wepTxKeyIdx = keyIdx;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setWepTxKeyIdx(any(int.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getWepTxKeyIdxCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.wepTxKeyIdx);
            }
        }).when(mISupplicantStaNetworkMock)
                .getWepTxKeyIdx(any(ISupplicantStaNetwork.getWepTxKeyIdxCallback.class));

        /** allowedKeyManagement */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(int mask) throws RemoteException {
                mSupplicantVariables.keyMgmtMask = mask;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setKeyMgmt(any(int.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getKeyMgmtCallback cb) throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.keyMgmtMask);
            }
        }).when(mISupplicantStaNetworkMock)
                .getKeyMgmt(any(ISupplicantStaNetwork.getKeyMgmtCallback.class));

        /** allowedProtocols */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(int mask) throws RemoteException {
                mSupplicantVariables.protoMask = mask;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setProto(any(int.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getProtoCallback cb) throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.protoMask);
            }
        }).when(mISupplicantStaNetworkMock)
                .getProto(any(ISupplicantStaNetwork.getProtoCallback.class));

        /** allowedAuthAlgorithms */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(int mask) throws RemoteException {
                mSupplicantVariables.authAlgMask = mask;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setAuthAlg(any(int.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getAuthAlgCallback cb) throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.authAlgMask);
            }
        }).when(mISupplicantStaNetworkMock)
                .getAuthAlg(any(ISupplicantStaNetwork.getAuthAlgCallback.class));

        /** allowedGroupCiphers */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(int mask) throws RemoteException {
                mSupplicantVariables.groupCipherMask = mask;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setGroupCipher(any(int.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getGroupCipherCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.groupCipherMask);
            }
        }).when(mISupplicantStaNetworkMock)
                .getGroupCipher(any(ISupplicantStaNetwork.getGroupCipherCallback.class));

        /** allowedPairwiseCiphers */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(int mask) throws RemoteException {
                mSupplicantVariables.pairwiseCipherMask = mask;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setPairwiseCipher(any(int.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getPairwiseCipherCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.pairwiseCipherMask);
            }
        }).when(mISupplicantStaNetworkMock)
                .getPairwiseCipher(any(ISupplicantStaNetwork.getPairwiseCipherCallback.class));

        /** metadata: idstr */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(String idStr) throws RemoteException {
                mSupplicantVariables.idStr = idStr;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setIdStr(any(String.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getIdStrCallback cb) throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.idStr);
            }
        }).when(mISupplicantStaNetworkMock)
                .getIdStr(any(ISupplicantStaNetwork.getIdStrCallback.class));

        /** UpdateIdentifier */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(int identifier) throws RemoteException {
                mSupplicantVariables.updateIdentifier = identifier;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setUpdateIdentifier(any(int.class));

        /** EAP method */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(int method) throws RemoteException {
                mSupplicantVariables.eapMethod = method;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapMethod(any(int.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapMethodCallback cb)
                    throws RemoteException {
                // When not set, return failure.
                if (mSupplicantVariables.eapMethod == -1) {
                    cb.onValues(mStatusFailure, mSupplicantVariables.eapMethod);
                } else {
                    cb.onValues(mStatusSuccess, mSupplicantVariables.eapMethod);
                }
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapMethod(any(ISupplicantStaNetwork.getEapMethodCallback.class));

        /** EAP Phase 2 method */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(int method) throws RemoteException {
                mSupplicantVariables.eapPhase2Method = method;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapPhase2Method(any(int.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapPhase2MethodCallback cb)
                    throws RemoteException {
                // When not set, return failure.
                if (mSupplicantVariables.eapPhase2Method == -1) {
                    cb.onValues(mStatusFailure, mSupplicantVariables.eapPhase2Method);
                } else {
                    cb.onValues(mStatusSuccess, mSupplicantVariables.eapPhase2Method);
                }
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapPhase2Method(any(ISupplicantStaNetwork.getEapPhase2MethodCallback.class));

        /** EAP Identity */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(ArrayList<Byte> identity) throws RemoteException {
                mSupplicantVariables.eapIdentity = identity;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapIdentity(any(ArrayList.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapIdentityCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.eapIdentity);
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapIdentity(any(ISupplicantStaNetwork.getEapIdentityCallback.class));

        /** EAP Anonymous Identity */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(ArrayList<Byte> identity) throws RemoteException {
                mSupplicantVariables.eapAnonymousIdentity = identity;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapAnonymousIdentity(any(ArrayList.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapAnonymousIdentityCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.eapAnonymousIdentity);
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapAnonymousIdentity(
                        any(ISupplicantStaNetwork.getEapAnonymousIdentityCallback.class));

        /** EAP Password */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(ArrayList<Byte> password) throws RemoteException {
                mSupplicantVariables.eapPassword = password;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapPassword(any(ArrayList.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapPasswordCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.eapPassword);
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapPassword(any(ISupplicantStaNetwork.getEapPasswordCallback.class));

        /** EAP Client Cert */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(String cert) throws RemoteException {
                mSupplicantVariables.eapClientCert = cert;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapClientCert(any(String.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapClientCertCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.eapClientCert);
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapClientCert(any(ISupplicantStaNetwork.getEapClientCertCallback.class));

        /** EAP CA Cert */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(String cert) throws RemoteException {
                mSupplicantVariables.eapCACert = cert;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapCACert(any(String.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapCACertCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.eapCACert);
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapCACert(any(ISupplicantStaNetwork.getEapCACertCallback.class));

        /** EAP Subject Match */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(String match) throws RemoteException {
                mSupplicantVariables.eapSubjectMatch = match;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapSubjectMatch(any(String.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapSubjectMatchCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.eapSubjectMatch);
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapSubjectMatch(any(ISupplicantStaNetwork.getEapSubjectMatchCallback.class));

        /** EAP Engine */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(boolean enable) throws RemoteException {
                mSupplicantVariables.eapEngine = enable;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapEngine(any(boolean.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapEngineCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.eapEngine);
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapEngine(any(ISupplicantStaNetwork.getEapEngineCallback.class));

        /** EAP Engine ID */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(String id) throws RemoteException {
                mSupplicantVariables.eapEngineID = id;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapEngineID(any(String.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapEngineIDCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.eapEngineID);
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapEngineID(any(ISupplicantStaNetwork.getEapEngineIDCallback.class));

        /** EAP Private Key */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(String key) throws RemoteException {
                mSupplicantVariables.eapPrivateKey = key;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapPrivateKey(any(String.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapPrivateKeyCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.eapPrivateKey);
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapPrivateKey(any(ISupplicantStaNetwork.getEapPrivateKeyCallback.class));

        /** EAP Alt Subject Match */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(String match) throws RemoteException {
                mSupplicantVariables.eapAltSubjectMatch = match;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapAltSubjectMatch(any(String.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapAltSubjectMatchCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.eapAltSubjectMatch);
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapAltSubjectMatch(
                        any(ISupplicantStaNetwork.getEapAltSubjectMatchCallback.class));

        /** EAP Domain Suffix Match */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(String match) throws RemoteException {
                mSupplicantVariables.eapDomainSuffixMatch = match;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapDomainSuffixMatch(any(String.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapDomainSuffixMatchCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.eapDomainSuffixMatch);
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapDomainSuffixMatch(
                        any(ISupplicantStaNetwork.getEapDomainSuffixMatchCallback.class));

        /** EAP CA Path*/
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(String path) throws RemoteException {
                mSupplicantVariables.eapCAPath = path;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setEapCAPath(any(String.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(ISupplicantStaNetwork.getEapCAPathCallback cb)
                    throws RemoteException {
                cb.onValues(mStatusSuccess, mSupplicantVariables.eapCAPath);
            }
        }).when(mISupplicantStaNetworkMock)
                .getEapCAPath(any(ISupplicantStaNetwork.getEapCAPathCallback.class));

        /** EAP Proactive Key Caching */
        doAnswer(new AnswerWithArguments() {
            public SupplicantStatus answer(boolean enable) throws RemoteException {
                mSupplicantVariables.eapProactiveKeyCaching = enable;
                return mStatusSuccess;
            }
        }).when(mISupplicantStaNetworkMock).setProactiveKeyCaching(any(boolean.class));
    }

    private SupplicantStatus createSupplicantStatus(int code) {
        SupplicantStatus status = new SupplicantStatus();
        status.code = code;
        return status;
    }

    // Private class to to store/inspect values set via the HIDL mock.
    private class SupplicantNetworkVariables {
        public ArrayList<Byte> ssid;
        public byte[/* 6 */] bssid;
        public int keyMgmtMask;
        public int protoMask;
        public int authAlgMask;
        public int groupCipherMask;
        public int pairwiseCipherMask;
        public boolean scanSsid;
        public boolean requirePmf;
        public String idStr;
        public int updateIdentifier;
        public String pskPassphrase;
        public ArrayList<Byte>[] wepKey = new ArrayList[4];
        public int wepTxKeyIdx;
        public int eapMethod = -1;
        public int eapPhase2Method = -1;
        public ArrayList<Byte> eapIdentity;
        public ArrayList<Byte> eapAnonymousIdentity;
        public ArrayList<Byte> eapPassword;
        public String eapCACert;
        public String eapCAPath;
        public String eapClientCert;
        public String eapPrivateKey;
        public String eapSubjectMatch;
        public String eapAltSubjectMatch;
        public boolean eapEngine;
        public String eapEngineID;
        public String eapDomainSuffixMatch;
        public boolean eapProactiveKeyCaching;
    }
}
