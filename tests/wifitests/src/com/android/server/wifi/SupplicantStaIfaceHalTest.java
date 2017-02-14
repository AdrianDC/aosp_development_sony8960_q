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
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.test.MockAnswerUtil;
import android.content.Context;
import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import android.hardware.wifi.supplicant.V1_0.ISupplicantIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaNetwork;
import android.hardware.wifi.supplicant.V1_0.IfaceType;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatus;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatusCode;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.net.IpConfiguration;
import android.net.wifi.WifiConfiguration;
import android.os.IHwBinder;
import android.os.RemoteException;
import android.util.SparseArray;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for SupplicantStaIfaceHal
 */
public class SupplicantStaIfaceHalTest {
    private static final String TAG = "SupplicantStaIfaceHalTest";
    private static final Map<Integer, String> NETWORK_ID_TO_SSID = new HashMap<Integer, String>() {{
            put(1, "ssid1");
            put(2, "ssid2");
            put(3, "ssid3");
        }};
    private static final int EXISTING_SUPPLICANT_NETWORK_ID = 2;
    private static final int ROAM_NETWORK_ID = 4;
    private static final String ROAM_BSSID = "fa:45:23:23:12:12";
    @Mock IServiceManager mServiceManagerMock;
    @Mock ISupplicant mISupplicantMock;
    @Mock ISupplicantIface mISupplicantIfaceMock;
    @Mock ISupplicantStaIface mISupplicantStaIfaceMock;
    @Mock Context mContext;
    @Mock WifiMonitor mWifiMonitor;
    @Mock SupplicantStaNetworkHal mSupplicantStaNetworkMock;
    SupplicantStatus mStatusSuccess;
    SupplicantStatus mStatusFailure;
    ISupplicant.IfaceInfo mStaIface;
    ISupplicant.IfaceInfo mP2pIface;
    ArrayList<ISupplicant.IfaceInfo> mIfaceInfoList;
    private SupplicantStaIfaceHal mDut;
    private ArgumentCaptor<IHwBinder.DeathRecipient> mDeathRecipientCaptor =
            ArgumentCaptor.forClass(IHwBinder.DeathRecipient.class);
    private ArgumentCaptor<IServiceNotification.Stub> mServiceNotificationCaptor =
            ArgumentCaptor.forClass(IServiceNotification.Stub.class);
    private InOrder mInOrder;

    private class SupplicantStaIfaceHalSpy extends SupplicantStaIfaceHal {
        SupplicantStaIfaceHalSpy(Context context, WifiMonitor monitor) {
            super(context, monitor);
        }

        @Override
        protected IServiceManager getServiceManagerMockable() throws RemoteException {
            return mServiceManagerMock;
        }

        @Override
        protected ISupplicant getSupplicantMockable() throws RemoteException {
            return mISupplicantMock;
        }

        @Override
        protected ISupplicantStaIface getStaIfaceMockable(ISupplicantIface iface) {
            return mISupplicantStaIfaceMock;
        }

        @Override
        protected SupplicantStaNetworkHal getStaNetworkMockable(
                ISupplicantStaNetwork iSupplicantStaNetwork) {
            return mSupplicantStaNetworkMock;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mStatusSuccess = createSupplicantStatus(SupplicantStatusCode.SUCCESS);
        mStatusFailure = createSupplicantStatus(SupplicantStatusCode.FAILURE_UNKNOWN);
        mStaIface = createIfaceInfo(IfaceType.STA, "wlan0");
        mP2pIface = createIfaceInfo(IfaceType.P2P, "p2p0");

        mIfaceInfoList = new ArrayList<>();
        mIfaceInfoList.add(mStaIface);
        mIfaceInfoList.add(mP2pIface);

        when(mServiceManagerMock.linkToDeath(any(IHwBinder.DeathRecipient.class),
                anyLong())).thenReturn(true);
        when(mServiceManagerMock.registerForNotifications(anyString(), anyString(),
                any(IServiceNotification.Stub.class))).thenReturn(true);
        mDut = new SupplicantStaIfaceHalSpy(mContext, mWifiMonitor);
    }

    /**
     * Sunny day scenario for SupplicantStaIfaceHal initialization
     * Asserts successful initialization
     */
    @Test
    public void testInitialize_success() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
    }

    /**
     * Tests the initialization flow, with a RemoteException occurring when 'getInterface' is called
     * Ensures initialization fails.
     */
    @Test
    public void testInitialize_remoteExceptionFailure() throws Exception {
        executeAndValidateInitializationSequence(true, false, false);
    }

    /**
     * Tests the initialization flow, with listInterfaces returning 0 interfaces.
     * Ensures failure
     */
    @Test
    public void testInitialize_zeroInterfacesFailure() throws Exception {
        executeAndValidateInitializationSequence(false, true, false);
    }

    /**
     * Tests the initialization flow, with a null interface being returned by getInterface.
     * Ensures initialization fails.
     */
    @Test
    public void testInitialize_nullInterfaceFailure() throws Exception {
        executeAndValidateInitializationSequence(false, false, true);
    }

    /**
     * Tests the loading of networks using {@link SupplicantStaNetworkHal}.
     * Fills up only the SSID field of configs and uses it as a configKey as well.
     */
    @Test
    public void testLoadNetworks() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(ISupplicantStaIface.listNetworksCallback cb) {
                cb.onValues(mStatusSuccess, new ArrayList<>(NETWORK_ID_TO_SSID.keySet()));
            }
        }).when(mISupplicantStaIfaceMock)
                .listNetworks(any(ISupplicantStaIface.listNetworksCallback.class));
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(final int networkId, ISupplicantStaIface.getNetworkCallback cb) {
                // Reset the |mSupplicantStaNetwork| mock for each network.
                doAnswer(new MockAnswerUtil.AnswerWithArguments() {
                    public boolean answer(
                            WifiConfiguration config, Map<String, String> networkExtra) {
                        config.SSID = NETWORK_ID_TO_SSID.get(networkId);
                        config.networkId = networkId;
                        networkExtra.put(
                                SupplicantStaNetworkHal.ID_STRING_KEY_CONFIG_KEY, config.SSID);
                        return true;
                    }
                }).when(mSupplicantStaNetworkMock)
                        .loadWifiConfiguration(any(WifiConfiguration.class), any(Map.class));
                cb.onValues(mStatusSuccess, mock(ISupplicantStaNetwork.class));
                return;
            }
        }).when(mISupplicantStaIfaceMock)
                .getNetwork(anyInt(), any(ISupplicantStaIface.getNetworkCallback.class));

        Map<String, WifiConfiguration> configs = new HashMap<>();
        SparseArray<Map<String, String>> extras = new SparseArray<>();
        assertTrue(mDut.loadNetworks(configs, extras));

        assertEquals(3, configs.size());
        assertEquals(3, extras.size());
        for (Map.Entry<Integer, String> network : NETWORK_ID_TO_SSID.entrySet()) {
            WifiConfiguration config = configs.get(network.getValue());
            assertTrue(config != null);
            assertEquals(network.getKey(), Integer.valueOf(config.networkId));
            assertEquals(network.getValue(), config.SSID);
            assertEquals(IpConfiguration.IpAssignment.DHCP, config.getIpAssignment());
            assertEquals(IpConfiguration.ProxySettings.NONE, config.getProxySettings());
        }
    }

    /**
     * Tests the loading of networks using {@link SupplicantStaNetworkHal} removes any networks
     * with duplicate config key.
     * Fills up only the SSID field of configs and uses it as a configKey as well.
     */
    @Test
    public void testLoadNetworksRemovesDuplicates() throws Exception {
        // Network ID which will have the same config key as the previous one.
        final int duplicateNetworkId = 2;
        final int toRemoveNetworkId = duplicateNetworkId - 1;
        executeAndValidateInitializationSequence(false, false, false);
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(ISupplicantStaIface.listNetworksCallback cb) {
                cb.onValues(mStatusSuccess, new ArrayList<>(NETWORK_ID_TO_SSID.keySet()));
            }
        }).when(mISupplicantStaIfaceMock)
                .listNetworks(any(ISupplicantStaIface.listNetworksCallback.class));
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public SupplicantStatus answer(int id) {
                return mStatusSuccess;
            }
        }).when(mISupplicantStaIfaceMock).removeNetwork(eq(toRemoveNetworkId));
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(final int networkId, ISupplicantStaIface.getNetworkCallback cb) {
                // Reset the |mSupplicantStaNetwork| mock for each network.
                doAnswer(new MockAnswerUtil.AnswerWithArguments() {
                    public boolean answer(
                            WifiConfiguration config, Map<String, String> networkExtra) {
                        config.SSID = NETWORK_ID_TO_SSID.get(networkId);
                        config.networkId = networkId;
                        // Duplicate network gets the same config key as the to removed one.
                        if (networkId == duplicateNetworkId) {
                            networkExtra.put(
                                    SupplicantStaNetworkHal.ID_STRING_KEY_CONFIG_KEY,
                                    NETWORK_ID_TO_SSID.get(toRemoveNetworkId));
                        } else {
                            networkExtra.put(
                                    SupplicantStaNetworkHal.ID_STRING_KEY_CONFIG_KEY,
                                    NETWORK_ID_TO_SSID.get(networkId));
                        }
                        return true;
                    }
                }).when(mSupplicantStaNetworkMock)
                        .loadWifiConfiguration(any(WifiConfiguration.class), any(Map.class));
                cb.onValues(mStatusSuccess, mock(ISupplicantStaNetwork.class));
                return;
            }
        }).when(mISupplicantStaIfaceMock)
                .getNetwork(anyInt(), any(ISupplicantStaIface.getNetworkCallback.class));

        Map<String, WifiConfiguration> configs = new HashMap<>();
        SparseArray<Map<String, String>> extras = new SparseArray<>();
        assertTrue(mDut.loadNetworks(configs, extras));

        assertEquals(2, configs.size());
        assertEquals(2, extras.size());
        for (Map.Entry<Integer, String> network : NETWORK_ID_TO_SSID.entrySet()) {
            if (network.getKey() == toRemoveNetworkId) {
                continue;
            }
            WifiConfiguration config;
            // Duplicate network gets the same config key as the to removed one. So, use that to
            // lookup the map.
            if (network.getKey() == duplicateNetworkId) {
                config = configs.get(NETWORK_ID_TO_SSID.get(toRemoveNetworkId));
            } else {
                config = configs.get(network.getValue());
            }
            assertTrue(config != null);
            assertEquals(network.getKey(), Integer.valueOf(config.networkId));
            assertEquals(network.getValue(), config.SSID);
            assertEquals(IpConfiguration.IpAssignment.DHCP, config.getIpAssignment());
            assertEquals(IpConfiguration.ProxySettings.NONE, config.getProxySettings());
        }
    }

    /**
     * Tests the failure to load networks because of listNetworks failure.
     */
    @Test
    public void testLoadNetworksFailedDueToListNetworks() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(ISupplicantStaIface.listNetworksCallback cb) {
                cb.onValues(mStatusFailure, null);
            }
        }).when(mISupplicantStaIfaceMock)
                .listNetworks(any(ISupplicantStaIface.listNetworksCallback.class));

        Map<String, WifiConfiguration> configs = new HashMap<>();
        SparseArray<Map<String, String>> extras = new SparseArray<>();
        assertFalse(mDut.loadNetworks(configs, extras));
    }

    /**
     * Tests the failure to load networks because of getNetwork failure.
     */
    @Test
    public void testLoadNetworksFailedDueToGetNetwork() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(ISupplicantStaIface.listNetworksCallback cb) {
                cb.onValues(mStatusSuccess, new ArrayList<>(NETWORK_ID_TO_SSID.keySet()));
            }
        }).when(mISupplicantStaIfaceMock)
                .listNetworks(any(ISupplicantStaIface.listNetworksCallback.class));
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(final int networkId, ISupplicantStaIface.getNetworkCallback cb) {
                cb.onValues(mStatusFailure, mock(ISupplicantStaNetwork.class));
                return;
            }
        }).when(mISupplicantStaIfaceMock)
                .getNetwork(anyInt(), any(ISupplicantStaIface.getNetworkCallback.class));

        Map<String, WifiConfiguration> configs = new HashMap<>();
        SparseArray<Map<String, String>> extras = new SparseArray<>();
        assertFalse(mDut.loadNetworks(configs, extras));
    }

    /**
     * Tests the failure to load networks because of loadWifiConfiguration failure.
     */
    @Test
    public void testLoadNetworksFailedDueToLoadWifiConfiguration() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(ISupplicantStaIface.listNetworksCallback cb) {
                cb.onValues(mStatusSuccess, new ArrayList<>(NETWORK_ID_TO_SSID.keySet()));
            }
        }).when(mISupplicantStaIfaceMock)
                .listNetworks(any(ISupplicantStaIface.listNetworksCallback.class));
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public boolean answer(WifiConfiguration config, Map<String, String> networkExtra) {
                return false;
            }
        }).when(mSupplicantStaNetworkMock)
                .loadWifiConfiguration(any(WifiConfiguration.class), any(Map.class));

        Map<String, WifiConfiguration> configs = new HashMap<>();
        SparseArray<Map<String, String>> extras = new SparseArray<>();
        assertFalse(mDut.loadNetworks(configs, extras));
    }

    /**
     * Tests connection to a specified network without triggering disconnect.
     */
    @Test
    public void testConnectWithNoDisconnectAndEmptyExistingNetworks() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        executeAndValidateConnectSequence(0, false, false);
    }

    /**
     * Tests connection to a specified network without triggering disconnect.
     */
    @Test
    public void testConnectWithNoDisconnectAndSingleExistingNetwork() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        executeAndValidateConnectSequence(0, true, false);
    }

    /**
     * Tests connection to a specified network, with a triggered disconnect.
     */
    @Test
    public void testConnectWithDisconnectAndSingleExistingNetwork() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        executeAndValidateConnectSequence(0, false, true);
    }

    /**
     * Tests connection to a specified network failure due to network add.
     */
    @Test
    public void testConnectFailureDueToNetworkAddFailure() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        setupMocksForConnectSequence(false);
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(ISupplicantStaIface.addNetworkCallback cb) throws RemoteException {
                cb.onValues(mStatusFailure, mock(ISupplicantStaNetwork.class));
                return;
            }
        }).when(mISupplicantStaIfaceMock).addNetwork(
                any(ISupplicantStaIface.addNetworkCallback.class));

        assertFalse(mDut.connectToNetwork(new WifiConfiguration(), false));
    }

    /**
     * Tests connection to a specified network failure due to network save.
     */
    @Test
    public void testConnectFailureDueToNetworkSaveFailure() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        setupMocksForConnectSequence(false);

        when(mSupplicantStaNetworkMock.saveWifiConfiguration(any(WifiConfiguration.class)))
                .thenReturn(false);

        assertFalse(mDut.connectToNetwork(new WifiConfiguration(), false));
    }

    /**
     * Tests connection to a specified network failure due to network select.
     */
    @Test
    public void testConnectFailureDueToNetworkSelectFailure() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        setupMocksForConnectSequence(false);

        when(mSupplicantStaNetworkMock.select()).thenReturn(false);

        assertFalse(mDut.connectToNetwork(new WifiConfiguration(), false));
    }

    /**
     * Tests roaming to the same network as the currently connected one.
     */
    @Test
    public void testRoamToSameNetwork() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        executeAndValidateRoamSequence(true);
    }

    /**
     * Tests roaming to a different network.
     */
    @Test
    public void testRoamToDifferentNetwork() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        executeAndValidateRoamSequence(false);
    }

    /**
     * Tests roaming failure because of unable to set bssid.
     */
    @Test
    public void testRoamFailureDueToBssidSet() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        int connectedNetworkId = 5;
        executeAndValidateConnectSequence(connectedNetworkId, false, false);
        when(mSupplicantStaNetworkMock.setBssid(anyString())).thenReturn(false);

        WifiConfiguration roamingConfig = new WifiConfiguration();
        roamingConfig.networkId = connectedNetworkId;
        roamingConfig.getNetworkSelectionStatus().setNetworkSelectionBSSID("45:34:23:23:ab:ed");
        assertFalse(mDut.roamToNetwork(roamingConfig));
    }

    /**
     * Tests roaming failure because of unable to reassociate.
     */
    @Test
    public void testRoamFailureDueToReassociate() throws Exception {
        executeAndValidateInitializationSequence(false, false, false);
        int connectedNetworkId = 5;
        executeAndValidateConnectSequence(connectedNetworkId, false, false);

        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public SupplicantStatus answer() throws RemoteException {
                return mStatusFailure;
            }
        }).when(mISupplicantStaIfaceMock).reassociate();
        when(mSupplicantStaNetworkMock.setBssid(anyString())).thenReturn(true);

        WifiConfiguration roamingConfig = new WifiConfiguration();
        roamingConfig.networkId = connectedNetworkId;
        roamingConfig.getNetworkSelectionStatus().setNetworkSelectionBSSID("45:34:23:23:ab:ed");
        assertFalse(mDut.roamToNetwork(roamingConfig));
    }

    /**
     * Calls.initialize(), mocking various call back answers and verifying flow, asserting for the
     * expected result. Verifies if ISupplicantStaIface manager is initialized or reset.
     * Each of the arguments will cause a different failure mode when set true.
     */
    private void executeAndValidateInitializationSequence(boolean causeRemoteException,
                                                          boolean getZeroInterfaces,
                                                          boolean getNullInterface)
            throws Exception {
        boolean shouldSucceed = !causeRemoteException && !getZeroInterfaces && !getNullInterface;
        // Setup callback mock answers
        ArrayList<ISupplicant.IfaceInfo> interfaces;
        if (getZeroInterfaces) {
            interfaces = new ArrayList<>();
        } else {
            interfaces = mIfaceInfoList;
        }
        doAnswer(new GetListInterfacesAnswer(interfaces)).when(mISupplicantMock)
                .listInterfaces(any(ISupplicant.listInterfacesCallback.class));
        if (causeRemoteException) {
            doThrow(new RemoteException("Some error!!!"))
                    .when(mISupplicantMock).getInterface(any(ISupplicant.IfaceInfo.class),
                    any(ISupplicant.getInterfaceCallback.class));
        } else {
            doAnswer(new GetGetInterfaceAnswer(getNullInterface))
                    .when(mISupplicantMock).getInterface(any(ISupplicant.IfaceInfo.class),
                    any(ISupplicant.getInterfaceCallback.class));
        }

        mInOrder = inOrder(mServiceManagerMock, mISupplicantMock);
        // Initialize SupplicantStaIfaceHal, should call serviceManager.registerForNotifications
        assertTrue(mDut.initialize());
        // verify: service manager initialization sequence
        mInOrder.verify(mServiceManagerMock).linkToDeath(any(IHwBinder.DeathRecipient.class),
                anyLong());
        mInOrder.verify(mServiceManagerMock).registerForNotifications(
                eq(ISupplicant.kInterfaceName), eq(""), mServiceNotificationCaptor.capture());
        // act: cause the onRegistration(...) callback to execute
        mServiceNotificationCaptor.getValue().onRegistration(ISupplicant.kInterfaceName, "", true);

        assertTrue(mDut.isInitializationComplete() == shouldSucceed);
        // verify: listInterfaces is called
        mInOrder.verify(mISupplicantMock).listInterfaces(
                any(ISupplicant.listInterfacesCallback.class));
        if (!getZeroInterfaces) {
            mInOrder.verify(mISupplicantMock)
                    .getInterface(any(ISupplicant.IfaceInfo.class),
                            any(ISupplicant.getInterfaceCallback.class));
        }
    }

    private SupplicantStatus createSupplicantStatus(int code) {
        SupplicantStatus status = new SupplicantStatus();
        status.code = code;
        return status;
    }

    /**
     * Create an IfaceInfo with given type and name
     */
    private ISupplicant.IfaceInfo createIfaceInfo(int type, String name) {
        ISupplicant.IfaceInfo info = new ISupplicant.IfaceInfo();
        info.type = type;
        info.name = name;
        return info;
    }

    private class GetListInterfacesAnswer extends MockAnswerUtil.AnswerWithArguments {
        private ArrayList<ISupplicant.IfaceInfo> mInterfaceList;

        GetListInterfacesAnswer(ArrayList<ISupplicant.IfaceInfo> ifaces) {
            mInterfaceList = ifaces;
        }

        public void answer(ISupplicant.listInterfacesCallback cb) {
            cb.onValues(mStatusSuccess, mInterfaceList);
        }
    }

    private class GetGetInterfaceAnswer extends MockAnswerUtil.AnswerWithArguments {
        boolean mGetNullInterface;

        GetGetInterfaceAnswer(boolean getNullInterface) {
            mGetNullInterface = getNullInterface;
        }

        public void answer(ISupplicant.IfaceInfo iface, ISupplicant.getInterfaceCallback cb) {
            if (mGetNullInterface) {
                cb.onValues(mStatusSuccess, null);
            } else {
                cb.onValues(mStatusSuccess, mISupplicantIfaceMock);
            }
        }
    }

    /**
     * Setup mocks for connect sequence.
     */
    private void setupMocksForConnectSequence(final boolean haveExistingNetwork) throws Exception {
        final int existingNetworkId = EXISTING_SUPPLICANT_NETWORK_ID;
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public SupplicantStatus answer() throws RemoteException {
                return mStatusSuccess;
            }
        }).when(mISupplicantStaIfaceMock).disconnect();
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(ISupplicantStaIface.listNetworksCallback cb) throws RemoteException {
                if (haveExistingNetwork) {
                    cb.onValues(mStatusSuccess, new ArrayList<>(Arrays.asList(existingNetworkId)));
                } else {
                    cb.onValues(mStatusSuccess, new ArrayList<>());
                }
            }
        }).when(mISupplicantStaIfaceMock)
                .listNetworks(any(ISupplicantStaIface.listNetworksCallback.class));
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public SupplicantStatus answer(int id) throws RemoteException {
                return mStatusSuccess;
            }
        }).when(mISupplicantStaIfaceMock).removeNetwork(eq(existingNetworkId));
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public void answer(ISupplicantStaIface.addNetworkCallback cb) throws RemoteException {
                cb.onValues(mStatusSuccess, mock(ISupplicantStaNetwork.class));
                return;
            }
        }).when(mISupplicantStaIfaceMock).addNetwork(
                any(ISupplicantStaIface.addNetworkCallback.class));
        when(mSupplicantStaNetworkMock.saveWifiConfiguration(any(WifiConfiguration.class)))
                .thenReturn(true);
        when(mSupplicantStaNetworkMock.select()).thenReturn(true);
    }

    /**
     * Helper function to validate the connect sequence.
     */
    private void validateConnectSequence(
            final boolean haveExistingNetwork, boolean shouldDisconnect, int numNetworkAdditions)
            throws Exception {
        if (shouldDisconnect) {
            verify(mISupplicantStaIfaceMock).disconnect();
        }
        if (haveExistingNetwork) {
            verify(mISupplicantStaIfaceMock).removeNetwork(anyInt());
        }
        verify(mISupplicantStaIfaceMock, times(numNetworkAdditions))
                .addNetwork(any(ISupplicantStaIface.addNetworkCallback.class));
        verify(mSupplicantStaNetworkMock, times(numNetworkAdditions))
                .saveWifiConfiguration(any(WifiConfiguration.class));
        verify(mSupplicantStaNetworkMock, times(numNetworkAdditions)).select();
    }

    /**
     * Helper function to execute all the actions to perform connection to the network.
     *
     * @param newFrameworkNetworkId Framework Network Id of the new network to connect.
     * @param haveExistingNetwork Removes the existing network.
     * @param shouldDisconnect Should trigger disconnect before connecting.
     */
    private void executeAndValidateConnectSequence(
            final int newFrameworkNetworkId, final boolean haveExistingNetwork,
            boolean shouldDisconnect) throws Exception {
        setupMocksForConnectSequence(haveExistingNetwork);
        WifiConfiguration config = new WifiConfiguration();
        config.networkId = newFrameworkNetworkId;
        assertTrue(mDut.connectToNetwork(config, shouldDisconnect));
        validateConnectSequence(haveExistingNetwork, shouldDisconnect, 1);
    }

    /**
     * Setup mocks for roam sequence.
     */
    private void setupMocksForRoamSequence(String roamBssid) throws Exception {
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public SupplicantStatus answer() throws RemoteException {
                return mStatusSuccess;
            }
        }).when(mISupplicantStaIfaceMock).reassociate();
        when(mSupplicantStaNetworkMock.setBssid(eq(roamBssid))).thenReturn(true);
    }

    /**
     * Helper function to execute all the actions to perform roaming to the network.
     *
     * @param sameNetwork Roam to the same network or not.
     */
    private void executeAndValidateRoamSequence(boolean sameNetwork) throws Exception {
        int connectedNetworkId = ROAM_NETWORK_ID;
        String roamBssid = ROAM_BSSID;
        int roamNetworkId;
        if (sameNetwork) {
            roamNetworkId = connectedNetworkId;
        } else {
            roamNetworkId = connectedNetworkId + 1;
        }
        executeAndValidateConnectSequence(connectedNetworkId, false, true);
        setupMocksForRoamSequence(roamBssid);

        WifiConfiguration roamingConfig = new WifiConfiguration();
        roamingConfig.networkId = roamNetworkId;
        roamingConfig.getNetworkSelectionStatus().setNetworkSelectionBSSID(roamBssid);
        assertTrue(mDut.roamToNetwork(roamingConfig));

        if (!sameNetwork) {
            validateConnectSequence(false, false, 2);
            verify(mSupplicantStaNetworkMock, never()).setBssid(anyString());
            verify(mISupplicantStaIfaceMock, never()).reassociate();
        } else {
            verify(mSupplicantStaNetworkMock).setBssid(eq(roamBssid));
            verify(mISupplicantStaIfaceMock).reassociate();
        }
    }
}
