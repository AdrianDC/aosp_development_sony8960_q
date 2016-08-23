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

package com.android.server.wifi.nan;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyShort;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.test.TestAlarmManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
import android.net.NetworkRequest;
import android.net.wifi.RttManager;
import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanEventCallback;
import android.net.wifi.nan.IWifiNanManager;
import android.net.wifi.nan.IWifiNanSessionCallback;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.net.wifi.nan.WifiNanEventCallback;
import android.net.wifi.nan.WifiNanManager;
import android.net.wifi.nan.WifiNanPublishSession;
import android.net.wifi.nan.WifiNanSessionCallback;
import android.net.wifi.nan.WifiNanSubscribeSession;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.Messenger;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Pair;

import com.android.internal.util.AsyncChannel;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * Unit test harness for WifiNanDataPathStateManager class.
 */
@SmallTest
public class WifiNanDataPathStateManagerTest {
    private static final String sNanInterfacePrefix = "nan";

    private TestLooper mMockLooper;
    private WifiNanStateManager mDut;
    @Mock private WifiNanNative mMockNative;
    @Mock private Context mMockContext;
    @Mock private IWifiNanManager mMockNanService;
    @Mock private WifiNanPublishSession mMockPublishSession;
    @Mock private WifiNanSubscribeSession mMockSubscribeSession;
    @Mock private RttManager.RttListener mMockRttListener;
    @Mock private ConnectivityManager mMockCm;
    @Mock private INetworkManagementService mMockNwMgt;
    @Mock private WifiNanDataPathStateManager.NetworkInterfaceWrapper mMockNetworkInterface;
    @Mock private IWifiNanEventCallback mMockCallback;
    @Mock IWifiNanSessionCallback mMockSessionCallback;
    TestAlarmManager mAlarmManager;

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    /**
     * Initialize mocks.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mAlarmManager = new TestAlarmManager();
        when(mMockContext.getSystemService(Context.ALARM_SERVICE))
                .thenReturn(mAlarmManager.getAlarmManager());

        when(mMockContext.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(mMockCm);

        mMockLooper = new TestLooper();

        mDut = installNewNanStateManager();
        mDut.start(mMockContext, mMockLooper.getLooper());
        mDut.startLate();

        when(mMockNative.getCapabilities(anyShort())).thenReturn(true);
        when(mMockNative.enableAndConfigure(anyShort(), any(ConfigRequest.class), anyBoolean()))
                .thenReturn(true);
        when(mMockNative.disable(anyShort())).thenReturn(true);
        when(mMockNative.publish(anyShort(), anyInt(), any(PublishConfig.class))).thenReturn(true);
        when(mMockNative.subscribe(anyShort(), anyInt(), any(SubscribeConfig.class)))
                .thenReturn(true);
        when(mMockNative.sendMessage(anyShort(), anyInt(), anyInt(), any(byte[].class),
                any(byte[].class), anyInt())).thenReturn(true);
        when(mMockNative.stopPublish(anyShort(), anyInt())).thenReturn(true);
        when(mMockNative.stopSubscribe(anyShort(), anyInt())).thenReturn(true);
        when(mMockNative.createNanNetworkInterface(anyShort(), anyString())).thenReturn(true);
        when(mMockNative.deleteNanNetworkInterface(anyShort(), anyString())).thenReturn(true);
        when(mMockNative.initiateDataPath(anyShort(), anyInt(), anyInt(), anyInt(),
                any(byte[].class), anyString(), any(byte[].class))).thenReturn(true);
        when(mMockNative.respondToDataPathRequest(anyShort(), anyBoolean(), anyInt(), anyString(),
                any(byte[].class))).thenReturn(true);
        when(mMockNative.endDataPath(anyShort(), anyInt())).thenReturn(true);

        when(mMockNetworkInterface.configureAgentProperties(
                any(WifiNanDataPathStateManager.NanNetworkRequestInformation.class), anyString(),
                anyInt(), any(NetworkInfo.class), any(NetworkCapabilities.class),
                any(LinkProperties.class))).thenReturn(true);

        installMockWifiNanNative(mMockNative);
        installDataPathStateManagerMocks();
    }

    /**
     * Validates that creating and deleting all interfaces works based on capabilities.
     */
    @Test
    public void testCreateDeleteAllInterfaces() throws Exception {
        final int numNdis = 3;
        final int failCreateInterfaceIndex = 1;

        WifiNanNative.Capabilities capabilities = new WifiNanNative.Capabilities();
        capabilities.maxNdiInterfaces = numNdis;

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<String> interfaceName = ArgumentCaptor.forClass(String.class);
        InOrder inOrder = inOrder(mMockNative);

        // (1) get capabilities
        mDut.getCapabilities();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), capabilities);
        mMockLooper.dispatchAll();

        // (2) create all interfaces
        mDut.createAllDataPathInterfaces();
        mMockLooper.dispatchAll();
        for (int i = 0; i < numNdis; ++i) {
            inOrder.verify(mMockNative).createNanNetworkInterface(transactionId.capture(),
                    interfaceName.capture());
            collector.checkThat("interface created -- " + i, sNanInterfacePrefix + i,
                    equalTo(interfaceName.getValue()));
            mDut.onCreateDataPathInterfaceResponse(transactionId.getValue(), true, 0);
            mMockLooper.dispatchAll();
        }

        // (3) delete all interfaces [one unsuccessfully] - note that will not necessarily be
        // done sequentially
        boolean[] done = new boolean[numNdis];
        Arrays.fill(done, false);
        mDut.deleteAllDataPathInterfaces();
        mMockLooper.dispatchAll();
        for (int i = 0; i < numNdis; ++i) {
            inOrder.verify(mMockNative).deleteNanNetworkInterface(transactionId.capture(),
                    interfaceName.capture());
            int interfaceIndex = Integer.valueOf(
                    interfaceName.getValue().substring(sNanInterfacePrefix.length()));
            done[interfaceIndex] = true;
            if (interfaceIndex == failCreateInterfaceIndex) {
                mDut.onDeleteDataPathInterfaceResponse(transactionId.getValue(), false, 0);
            } else {
                mDut.onDeleteDataPathInterfaceResponse(transactionId.getValue(), true, 0);
            }
            mMockLooper.dispatchAll();
        }
        for (int i = 0; i < numNdis; ++i) {
            collector.checkThat("interface deleted -- " + i, done[i], equalTo(true));
        }

        // (4) create all interfaces (should get a delete for the one which couldn't delete earlier)
        mDut.createAllDataPathInterfaces();
        mMockLooper.dispatchAll();
        for (int i = 0; i < numNdis; ++i) {
            if (i == failCreateInterfaceIndex) {
                inOrder.verify(mMockNative).deleteNanNetworkInterface(transactionId.capture(),
                        interfaceName.capture());
                collector.checkThat("interface delete pre-create -- " + i, sNanInterfacePrefix + i,
                        equalTo(interfaceName.getValue()));
                mDut.onDeleteDataPathInterfaceResponse(transactionId.getValue(), true, 0);
                mMockLooper.dispatchAll();
            }
            inOrder.verify(mMockNative).createNanNetworkInterface(transactionId.capture(),
                    interfaceName.capture());
            collector.checkThat("interface created -- " + i, sNanInterfacePrefix + i,
                    equalTo(interfaceName.getValue()));
            mDut.onCreateDataPathInterfaceResponse(transactionId.getValue(), true, 0);
            mMockLooper.dispatchAll();
        }

        verifyNoMoreInteractions(mMockNative);
    }

    /*
     * Initiator tests
     */

    /**
     * Validate the success flow of the Initiator: using session network specifier with a non-null
     * token.
     */
    @Test
    public void testDataPathInitiatorMacTokenSuccess() throws Exception {
        testDataPathInitiatorUtility(false, true, true, true);
    }

    /**
     * Validate the fail flow of the Initiator: using session network specifier with a null
     * token.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testDataPathInitiatorMacNoTokenFail() throws Exception {
        testDataPathInitiatorUtility(false, true, false, true);
    }

    /**
     * Validate the fail flow of the Initiator: using session network specifier with a 0
     * peer ID.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testDataPathInitiatorNoMacFail() throws Exception {
        testDataPathInitiatorUtility(false, false, true, true);
    }

    /**
     * Validate the success flow of the Initiator: using a direct network specifier with a non-null
     * peer mac and non-null token.
     */
    @Test
    public void testDataPathInitiatorDirectMacTokenSuccess() throws Exception {
        testDataPathInitiatorUtility(true, true, true, true);
    }

    /**
     * Validate the fail flow of the Initiator: using a direct network specifier with a non-null
     * peer mac and null token.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testDataPathInitiatorDirectMacNoTokenFail() throws Exception {
        testDataPathInitiatorUtility(true, true, false, true);
    }

    /**
     * Validate the fail flow of the Initiator: using a direct network specifier with a null peer
     * mac and non-null token.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testDataPathInitiatorDirectNoMacTokenFail() throws Exception {
        testDataPathInitiatorUtility(true, false, true, true);
    }

    /**
     * Validate the fail flow of the Initiator: using a direct network specifier with a null peer
     * mac and null token.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testDataPathInitiatorDirectNoMacNoTokenFail() throws Exception {
        testDataPathInitiatorUtility(true, false, false, true);
    }

    /**
     * Validate the fail flow of the Initiator: use a session network specifier with a non-null
     * token, but don't get a confirmation.
     */
    @Test
    public void testDataPathInitiatorNoConfirmationTimeoutFail() throws Exception {
        testDataPathInitiatorUtility(false, true, true, false);
    }

    /*
     * Responder tests
     */

    /**
     * Validate the success flow of the Responder: using session network specifier with a non-null
     * token.
     */
    @Test
    public void testDataPathResonderMacTokenSuccess() throws Exception {
        testDataPathResponderUtility(false, true, true, true);
    }

    /**
     * Validate the success flow of the Responder: using session network specifier with a null
     * token.
     */
    @Test
    public void testDataPathResonderMacNoTokenSuccess() throws Exception {
        testDataPathResponderUtility(false, true, false, true);
    }

    /**
     * Validate the success flow of the Responder: using session network specifier with a
     * token and no peer ID (i.e. 0).
     */
    @Test
    public void testDataPathResonderMacTokenNoPeerIdSuccess() throws Exception {
        testDataPathResponderUtility(false, false, true, true);
    }

    /**
     * Validate the success flow of the Responder: using session network specifier with a null
     * token and no peer ID (i.e. 0).
     */
    @Test
    public void testDataPathResonderMacTokenNoPeerIdNoTokenSuccess() throws Exception {
        testDataPathResponderUtility(false, false, false, true);
    }

    /**
     * Validate the success flow of the Responder: using a direct network specifier with a non-null
     * peer mac and non-null token.
     */
    @Test
    public void testDataPathResonderDirectMacTokenSuccess() throws Exception {
        testDataPathResponderUtility(true, true, true, true);
    }

    /**
     * Validate the success flow of the Responder: using a direct network specifier with a non-null
     * peer mac and null token.
     */
    @Test
    public void testDataPathResonderDirectMacNoTokenSuccess() throws Exception {
        testDataPathResponderUtility(true, true, false, true);
    }

    /**
     * Validate the success flow of the Responder: using a direct network specifier with a null peer
     * mac and non-null token.
     */
    @Test
    public void testDataPathResonderDirectNoMacTokenSuccess() throws Exception {
        testDataPathResponderUtility(true, false, true, true);
    }

    /**
     * Validate the success flow of the Responder: using a direct network specifier with a null peer
     * mac and null token.
     */
    @Test
    public void testDataPathResonderDirectNoMacNoTokenSuccess() throws Exception {
        testDataPathResponderUtility(true, false, false, true);
    }

    /**
     * Validate the fail flow of the Responder: use a session network specifier with a non-null
     * token, but don't get a confirmation.
     */
    @Test
    public void testDataPathResponderNoConfirmationTimeoutFail() throws Exception {
        testDataPathInitiatorUtility(false, true, true, false);
    }

    /*
     * Utilities
     */

    private void testDataPathInitiatorUtility(boolean useDirect, boolean provideMac,
            boolean provideToken, boolean getConfirmation) throws Exception {
        final int clientId = 123;
        final int pubSubId = 11234;
        final int peerId = 1341234;
        final int ndpId = 2;
        final String token = "some token";
        final String peerToken = "let's go!";
        final byte[] peerDiscoveryMac = HexEncoding.decode("000102030405".toCharArray(), false);
        final byte[] peerDataPathMac = HexEncoding.decode("0A0B0C0D0E0F".toCharArray(), false);

        ArgumentCaptor<Messenger> messengerCaptor = ArgumentCaptor.forClass(Messenger.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mMockNative, mMockCm, mMockCallback, mMockSessionCallback);

        // (0) initialize
        Pair<Integer, Messenger> res = initDataPathEndPoint(clientId, pubSubId, peerId,
                peerDiscoveryMac, inOrder);

        // (1) request network
        NetworkRequest nr;
        if (useDirect) {
            nr = getDirectNetworkRequest(clientId, WifiNanManager.WIFI_NAN_DATA_PATH_ROLE_INITIATOR,
                    provideMac ? peerDiscoveryMac : null, provideToken ? token : null);
        } else {
            nr = getSessionNetworkRequest(clientId, res.first, provideMac ? peerId : 0,
                    WifiNanManager.WIFI_NAN_DATA_PATH_ROLE_INITIATOR, provideToken ? token : null);
        }

        Message reqNetworkMsg = Message.obtain();
        reqNetworkMsg.what = NetworkFactory.CMD_REQUEST_NETWORK;
        reqNetworkMsg.obj = nr;
        reqNetworkMsg.arg1 = 0;
        res.second.send(reqNetworkMsg);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).initiateDataPath(transactionId.capture(),
                eq(useDirect ? 0 : peerId), eq(WifiNanNative.CHANNEL_REQUEST_TYPE_REQUESTED),
                eq(2437), eq(peerDiscoveryMac), eq("nan0"), eq(token.getBytes()));
        mDut.onInitiateDataPathResponseSuccess(transactionId.getValue(), ndpId);
        mMockLooper.dispatchAll();

        // (2) get confirmation OR timeout
        if (getConfirmation) {
            mDut.onDataPathConfirmNotification(ndpId, peerDataPathMac, true, 0,
                    peerToken.getBytes());
            mMockLooper.dispatchAll();
            inOrder.verify(mMockCm).registerNetworkAgent(messengerCaptor.capture(),
                    any(NetworkInfo.class), any(LinkProperties.class),
                    any(NetworkCapabilities.class),
                    anyInt(), any(NetworkMisc.class));
        } else {
            assertTrue(
                    mAlarmManager.dispatch(WifiNanStateManager.HAL_DATA_PATH_CONFIRM_TIMEOUT_TAG));
            mMockLooper.dispatchAll();
            inOrder.verify(mMockNative).endDataPath(transactionId.capture(), eq(ndpId));
            mDut.onEndDataPathResponse(transactionId.getValue(), true, 0);
            mMockLooper.dispatchAll();
        }

        // (3) end data-path (unless didn't get confirmation)
        if (getConfirmation) {
            Message endNetworkReqMsg = Message.obtain();
            endNetworkReqMsg.what = NetworkFactory.CMD_CANCEL_REQUEST;
            endNetworkReqMsg.obj = nr;
            res.second.send(endNetworkReqMsg);

            Message endNetworkUsageMsg = Message.obtain();
            endNetworkUsageMsg.what = AsyncChannel.CMD_CHANNEL_DISCONNECTED;
            messengerCaptor.getValue().send(endNetworkUsageMsg);

            mMockLooper.dispatchAll();
            inOrder.verify(mMockNative).endDataPath(transactionId.capture(), eq(ndpId));
            mDut.onEndDataPathResponse(transactionId.getValue(), true, 0);
            mMockLooper.dispatchAll();
        }

        verifyNoMoreInteractions(mMockNative, mMockCm);
    }

    private void testDataPathResponderUtility(boolean useDirect, boolean provideMac,
            boolean provideToken, boolean getConfirmation) throws Exception {
        final int clientId = 123;
        final int pubSubId = 11234;
        final int peerId = 1341234;
        final int ndpId = 2;
        final String token = "some token";
        final String peerToken = "let's go!";
        final byte[] peerDiscoveryMac = HexEncoding.decode("000102030405".toCharArray(), false);
        final byte[] peerDataPathMac = HexEncoding.decode("0A0B0C0D0E0F".toCharArray(), false);

        ArgumentCaptor<Messenger> messengerCaptor = ArgumentCaptor.forClass(Messenger.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mMockNative, mMockCm, mMockCallback, mMockSessionCallback);

        // (0) initialize
        Pair<Integer, Messenger> res = initDataPathEndPoint(clientId, pubSubId, peerId,
                peerDiscoveryMac, inOrder);

        // (1) request network
        NetworkRequest nr;
        if (useDirect) {
            nr = getDirectNetworkRequest(clientId, WifiNanManager.WIFI_NAN_DATA_PATH_ROLE_RESPONDER,
                    provideMac ? peerDiscoveryMac : null, provideToken ? token : null);
        } else {
            nr = getSessionNetworkRequest(clientId, res.first, provideMac ? peerId : 0,
                    WifiNanManager.WIFI_NAN_DATA_PATH_ROLE_RESPONDER, provideToken ? token : null);
        }

        Message reqNetworkMsg = Message.obtain();
        reqNetworkMsg.what = NetworkFactory.CMD_REQUEST_NETWORK;
        reqNetworkMsg.obj = nr;
        reqNetworkMsg.arg1 = 0;
        res.second.send(reqNetworkMsg);
        mMockLooper.dispatchAll();

        // (2) get request & respond
        mDut.onDataPathRequestNotification(pubSubId, peerDiscoveryMac, ndpId, token.getBytes());
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).respondToDataPathRequest(transactionId.capture(), eq(true),
                eq(ndpId), eq("nan0"), eq(new byte[0]));
        mDut.onRespondToDataPathSetupRequestResponse(transactionId.getValue(), true, 0);
        mMockLooper.dispatchAll();

        // (3) get confirmation OR timeout
        if (getConfirmation) {
            mDut.onDataPathConfirmNotification(ndpId, peerDataPathMac, true, 0,
                    peerToken.getBytes());
            mMockLooper.dispatchAll();
            inOrder.verify(mMockCm).registerNetworkAgent(messengerCaptor.capture(),
                    any(NetworkInfo.class), any(LinkProperties.class),
                    any(NetworkCapabilities.class),
                    anyInt(), any(NetworkMisc.class));
        } else {
            assertTrue(
                    mAlarmManager.dispatch(WifiNanStateManager.HAL_DATA_PATH_CONFIRM_TIMEOUT_TAG));
            mMockLooper.dispatchAll();
            inOrder.verify(mMockNative).endDataPath(transactionId.capture(), eq(ndpId));
            mDut.onEndDataPathResponse(transactionId.getValue(), true, 0);
            mMockLooper.dispatchAll();
        }

        // (4) end data-path (unless didn't get confirmation)
        if (getConfirmation) {
            Message endNetworkMsg = Message.obtain();
            endNetworkMsg.what = NetworkFactory.CMD_CANCEL_REQUEST;
            endNetworkMsg.obj = nr;
            res.second.send(endNetworkMsg);

            Message endNetworkUsageMsg = Message.obtain();
            endNetworkUsageMsg.what = AsyncChannel.CMD_CHANNEL_DISCONNECTED;
            messengerCaptor.getValue().send(endNetworkUsageMsg);

            mMockLooper.dispatchAll();
            inOrder.verify(mMockNative).endDataPath(transactionId.capture(), eq(ndpId));
            mDut.onEndDataPathResponse(transactionId.getValue(), true, 0);
            mMockLooper.dispatchAll();
        }

        verifyNoMoreInteractions(mMockNative, mMockCm);
    }


    private static WifiNanStateManager installNewNanStateManager()
            throws Exception {
        Constructor<WifiNanStateManager> ctr = WifiNanStateManager.class.getDeclaredConstructor();
        ctr.setAccessible(true);
        WifiNanStateManager nanStateManager = ctr.newInstance();

        Field field = WifiNanStateManager.class.getDeclaredField("sNanStateManagerSingleton");
        field.setAccessible(true);
        field.set(null, nanStateManager);

        return WifiNanStateManager.getInstance();
    }

    private static void installMockWifiNanNative(WifiNanNative obj) throws Exception {
        Field field = WifiNanNative.class.getDeclaredField("sWifiNanNativeSingleton");
        field.setAccessible(true);
        field.set(null, obj);
    }

    private void installDataPathStateManagerMocks() throws Exception {
        Field field = WifiNanStateManager.class.getDeclaredField("mDataPathMgr");
        field.setAccessible(true);
        Object mDataPathMgr = field.get(mDut);

        field = WifiNanDataPathStateManager.class.getDeclaredField("mNwService");
        field.setAccessible(true);
        field.set(mDataPathMgr, mMockNwMgt);

        field = WifiNanDataPathStateManager.class.getDeclaredField("mNiWrapper");
        field.setAccessible(true);
        field.set(mDataPathMgr, mMockNetworkInterface);
    }

    private NetworkRequest getSessionNetworkRequest(int clientId, int sessionId, int peerId,
            int role, String token) throws Exception {
        final WifiNanManager mgr = new WifiNanManager(mMockContext, mMockNanService);
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();

        ArgumentCaptor<IWifiNanEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiNanEventCallback.class);
        ArgumentCaptor<IWifiNanSessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiNanSessionCallback.class);
        ArgumentCaptor<WifiNanPublishSession> publishSession = ArgumentCaptor
                .forClass(WifiNanPublishSession.class);

        WifiNanEventCallback mockCallback = mock(WifiNanEventCallback.class);
        WifiNanSessionCallback mockSessionCallback = mock(WifiNanSessionCallback.class);

        when(mMockNanService.connect(any(IBinder.class), anyString(),
                any(IWifiNanEventCallback.class), any(ConfigRequest.class))).thenReturn(clientId);

        mgr.connect(mMockLooper.getLooper(), configRequest, mockCallback);
        verify(mMockNanService).connect(any(IBinder.class), anyString(),
                clientProxyCallback.capture(), eq(configRequest));
        clientProxyCallback.getValue().onConnectSuccess();
        mMockLooper.dispatchAll();
        mgr.publish(publishConfig, mockSessionCallback);
        verify(mMockNanService).publish(eq(clientId), eq(publishConfig),
                sessionProxyCallback.capture());
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        mMockLooper.dispatchAll();
        verify(mockSessionCallback).onPublishStarted(publishSession.capture());

        String ns = publishSession.getValue().createNetworkSpecifier(role, peerId,
                (token == null) ? null : token.getBytes());

        NetworkCapabilities nc = new NetworkCapabilities();
        nc.clearAll();
        nc.addTransportType(NetworkCapabilities.TRANSPORT_WIFI_NAN);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).addCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        nc.setNetworkSpecifier(ns);
        nc.setLinkUpstreamBandwidthKbps(1);
        nc.setLinkDownstreamBandwidthKbps(1);
        nc.setSignalStrength(1);

        return new NetworkRequest(nc, 0, 0, NetworkRequest.Type.NONE);
    }

    private NetworkRequest getDirectNetworkRequest(int clientId, int role, byte[] peer,
            String token) throws Exception {
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final WifiNanManager mgr = new WifiNanManager(mMockContext, mMockNanService);

        ArgumentCaptor<IWifiNanEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiNanEventCallback.class);

        WifiNanEventCallback mockCallback = mock(WifiNanEventCallback.class);

        when(mMockNanService.connect(any(IBinder.class), anyString(),
                any(IWifiNanEventCallback.class), any(ConfigRequest.class))).thenReturn(clientId);

        mgr.connect(mMockLooper.getLooper(), configRequest, mockCallback);
        verify(mMockNanService).connect(any(IBinder.class), anyString(),
                clientProxyCallback.capture(), eq(configRequest));
        clientProxyCallback.getValue().onConnectSuccess();
        mMockLooper.dispatchAll();

        String ns = mgr.createNetworkSpecifier(role, peer,
                (token == null) ? null : token.getBytes());
        NetworkCapabilities nc = new NetworkCapabilities();
        nc.clearAll();
        nc.addTransportType(NetworkCapabilities.TRANSPORT_WIFI_NAN);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).addCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        nc.setNetworkSpecifier(ns);
        nc.setLinkUpstreamBandwidthKbps(1);
        nc.setLinkDownstreamBandwidthKbps(1);
        nc.setSignalStrength(1);

        return new NetworkRequest(nc, 0, 0, NetworkRequest.Type.REQUEST);
    }

    private Pair<Integer, Messenger> initDataPathEndPoint(int clientId, int pubSubId, int peerId,
            byte[] peerDiscoveryMac, InOrder inOrder) throws Exception {
        final int uid = 1000;
        final int pid = 2000;
        final String callingPackage = "com.android.somePackage";
        final String someMsg = "some arbitrary message from peer";
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();

        WifiNanNative.Capabilities capabilities = new WifiNanNative.Capabilities();
        capabilities.maxNdiInterfaces = 1;

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Messenger> messengerCaptor = ArgumentCaptor.forClass(Messenger.class);
        ArgumentCaptor<String> strCaptor = ArgumentCaptor.forClass(String.class);

        // (0) start/registrations
        inOrder.verify(mMockCm).registerNetworkFactory(messengerCaptor.capture(),
                strCaptor.capture());
        collector.checkThat("factory name", "WIFI_NAN_FACTORY", equalTo(strCaptor.getValue()));

        // (1) get capabilities
        mDut.getCapabilities();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), capabilities);
        mMockLooper.dispatchAll();

        // (2) enable usage (creates interfaces)
        mDut.enableUsage();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).deInitNan();
        inOrder.verify(mMockNative).createNanNetworkInterface(transactionId.capture(),
                strCaptor.capture());
        collector.checkThat("interface created -- 0", sNanInterfacePrefix + 0,
                equalTo(strCaptor.getValue()));
        mDut.onCreateDataPathInterfaceResponse(transactionId.getValue(), true, 0);
        mMockLooper.dispatchAll();

        // (3) create client & session & rx message
        mDut.connect(clientId, uid, pid, callingPackage, mMockCallback, configRequest);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                eq(configRequest), eq(true));
        mDut.onConfigSuccessResponse(transactionId.getValue());
        mMockLooper.dispatchAll();
        inOrder.verify(mMockCallback).onConnectSuccess();
        mDut.publish(clientId, publishConfig, mMockSessionCallback);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        mDut.onSessionConfigSuccessResponse(transactionId.getValue(), true, pubSubId);
        mMockLooper.dispatchAll();
        inOrder.verify(mMockSessionCallback).onSessionStarted(sessionId.capture());
        mDut.onMessageReceivedNotification(pubSubId, peerId, peerDiscoveryMac, someMsg.getBytes());
        mMockLooper.dispatchAll();
        inOrder.verify(mMockSessionCallback).onMessageReceived(peerId, someMsg.getBytes());

        return new Pair<>(sessionId.getValue(), messengerCaptor.getValue());
    }
}
