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
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.test.TestLooper;

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
    @Mock
    private ISupplicantStaNetwork mISupplicantStaNetworkMock;
    @Mock
    private HandlerThread mHandlerThread;
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
        if (config.updateIdentifier != null) {
            assertEquals(Integer.parseInt(config.updateIdentifier),
                    mSupplicantVariables.updateIdentifier);
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
    }
}
