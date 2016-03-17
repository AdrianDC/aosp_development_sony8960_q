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
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyShort;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanEventCallback;
import android.net.wifi.nan.IWifiNanSessionCallback;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.net.wifi.nan.WifiNanEventCallback;
import android.net.wifi.nan.WifiNanSessionCallback;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.SparseArray;

import com.android.server.wifi.MockLooper;

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

/**
 * Unit test harness for WifiNanStateManager.
 */
@SmallTest
public class WifiNanStateManagerTest {
    private MockLooper mMockLooper;
    private WifiNanStateManager mDut;
    @Mock private WifiNanNative mMockNative;

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    private int mAllNanEventFlags = WifiNanEventCallback.FLAG_LISTEN_CONFIG_COMPLETED
            | WifiNanEventCallback.FLAG_LISTEN_CONFIG_FAILED
            | WifiNanEventCallback.FLAG_LISTEN_IDENTITY_CHANGED
            | WifiNanEventCallback.FLAG_LISTEN_NAN_DOWN;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mMockLooper = new MockLooper();

        mDut = installNewNanStateManagerAndResetState();
        mDut.start(mMockLooper.getLooper());

        installMockWifiNanNative(mMockNative);
    }

    @Test
    public void testNanEventsDelivered() throws Exception {
        final int clientId = 1005;
        final int clusterLow1 = 5;
        final int clusterHigh1 = 100;
        final int masterPref1 = 111;
        final int clusterLow2 = 7;
        final int clusterHigh2 = 155;
        final int masterPref2 = 0;
        final int reason = WifiNanSessionCallback.FAIL_REASON_NO_RESOURCES;
        final byte[] someMac = HexEncoding.decode("000102030405".toCharArray(), false);

        ConfigRequest configRequest1 = new ConfigRequest.Builder().setClusterLow(clusterLow1)
                .setClusterHigh(clusterHigh1).setMasterPreference(masterPref1).build();

        ConfigRequest configRequest2 = new ConfigRequest.Builder().setClusterLow(clusterLow2)
                .setClusterHigh(clusterHigh2).setMasterPreference(masterPref2).build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mMockNative);

        mDut.connect(clientId, mockCallback, mAllNanEventFlags);
        mDut.requestConfig(clientId, configRequest1);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest1));
        short transactionId1 = transactionId.getValue();

        mDut.requestConfig(clientId, configRequest2);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest2));
        short transactionId2 = transactionId.getValue();

        mDut.onClusterChange(WifiNanClientState.CLUSTER_CHANGE_EVENT_STARTED, someMac);
        mDut.onConfigCompleted(transactionId1);
        mDut.onConfigFailed(transactionId2, reason);
        mDut.onInterfaceAddressChange(someMac);
        mDut.onNanDown(reason);
        mMockLooper.dispatchAll();

        inOrder.verify(mockCallback).onIdentityChanged();
        inOrder.verify(mockCallback).onConfigCompleted(configRequest1);
        inOrder.verify(mockCallback).onConfigFailed(configRequest2, reason);
        inOrder.verify(mockCallback).onIdentityChanged();
        inOrder.verify(mockCallback).onNanDown(reason);
        verifyNoMoreInteractions(mockCallback);

        validateInternalTransactionInfoCleanedUp(transactionId1);
        validateInternalTransactionInfoCleanedUp(transactionId2);
    }

    @Test
    public void testNanEventsNotDelivered() throws Exception {
        final int clientId = 1005;
        final int clusterLow1 = 5;
        final int clusterHigh1 = 100;
        final int masterPref1 = 111;
        final int clusterLow2 = 7;
        final int clusterHigh2 = 155;
        final int masterPref2 = 0;
        final int reason = WifiNanSessionCallback.FAIL_REASON_NO_RESOURCES;
        final byte[] someMac = HexEncoding.decode("000102030405".toCharArray(), false);

        ConfigRequest configRequest1 = new ConfigRequest.Builder().setClusterLow(clusterLow1)
                .setClusterHigh(clusterHigh1).setMasterPreference(masterPref1).build();

        ConfigRequest configRequest2 = new ConfigRequest.Builder().setClusterLow(clusterLow2)
                .setClusterHigh(clusterHigh2).setMasterPreference(masterPref2).build();

        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mMockNative);

        mDut.connect(clientId, mockCallback, 0);
        mDut.requestConfig(clientId, configRequest1);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest1));
        short transactionId1 = transactionId.getValue();

        mDut.requestConfig(clientId, configRequest2);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest2));
        short transactionId2 = transactionId.getValue();

        mDut.onClusterChange(WifiNanClientState.CLUSTER_CHANGE_EVENT_JOINED, someMac);
        mDut.onConfigCompleted(transactionId1);
        mDut.onConfigFailed(transactionId2, reason);
        mDut.onInterfaceAddressChange(someMac);
        mDut.onNanDown(reason);
        mMockLooper.dispatchAll();

        verifyZeroInteractions(mockCallback);

        validateInternalTransactionInfoCleanedUp(transactionId1);
        validateInternalTransactionInfoCleanedUp(transactionId2);
    }

    @Test
    public void testPublish() throws Exception {
        final int clientId = 1005;
        final int sessionId = 20;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int publishCount = 7;
        final int reasonFail = WifiNanSessionCallback.FAIL_REASON_NO_RESOURCES;
        final int reasonTerminate = WifiNanSessionCallback.TERMINATE_REASON_DONE;
        final int publishId1 = 15;
        final int publishId2 = 22;

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi).setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                .setPublishCount(publishCount).build();

        IWifiNanSessionCallback mockCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mMockNative);

        mDut.connect(clientId, null, 0);
        mDut.createSession(clientId, sessionId, mockCallback);

        // publish - fail
        mDut.publish(clientId, sessionId, publishConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        mDut.onPublishFail(transactionId.getValue(), reasonFail);
        mMockLooper.dispatchAll();

        inOrder.verify(mockCallback).onSessionConfigFail(reasonFail);
        validateInternalTransactionInfoCleanedUp(transactionId.getValue());

        // publish - success/terminate
        mDut.publish(clientId, sessionId, publishConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        mDut.onPublishSuccess(transactionId.getValue(), publishId1);
        mMockLooper.dispatchAll();

        mDut.onPublishTerminated(publishId1, reasonTerminate);
        mMockLooper.dispatchAll();

        inOrder.verify(mockCallback).onSessionTerminated(reasonTerminate);
        validateInternalTransactionInfoCleanedUp(transactionId.getValue());

        // re-publish
        mDut.publish(clientId, sessionId, publishConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        mDut.onPublishSuccess(transactionId.getValue(), publishId2);
        mDut.publish(clientId, sessionId, publishConfig);
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(publishId2),
                eq(publishConfig));
        verifyNoMoreInteractions(mockCallback, mMockNative);
    }

    @Test
    public void testSubscribe() throws Exception {
        final int clientId = 1005;
        final int sessionId = 20;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeCount = 7;
        final int reasonFail = WifiNanSessionCallback.FAIL_REASON_NO_RESOURCES;
        final int reasonTerminate = WifiNanSessionCallback.TERMINATE_REASON_DONE;
        final int subscribeId1 = 15;
        final int subscribeId2 = 10;

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi)
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                .setSubscribeCount(subscribeCount).build();

        IWifiNanSessionCallback mockCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mMockNative);

        mDut.connect(clientId, null, 0);
        mDut.createSession(clientId, sessionId, mockCallback);

        // subscribe - fail
        mDut.subscribe(clientId, sessionId, subscribeConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        mDut.onSubscribeFail(transactionId.getValue(), reasonFail);
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mockCallback).onSessionConfigFail(reasonFail);

        // subscribe - success/terminate
        mDut.subscribe(clientId, sessionId, subscribeConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        mDut.onSubscribeSuccess(transactionId.getValue(), subscribeId1);
        mMockLooper.dispatchAll();

        mDut.onSubscribeTerminated(subscribeId1, reasonTerminate);
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mockCallback).onSessionTerminated(reasonTerminate);

        // re-subscribe
        mDut.subscribe(clientId, sessionId, subscribeConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        mDut.onSubscribeSuccess(transactionId.getValue(), subscribeId2);
        mDut.subscribe(clientId, sessionId, subscribeConfig);
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(subscribeId2),
                eq(subscribeConfig));
        verifyNoMoreInteractions(mockCallback, mMockNative);
    }

    @Test
    public void testMatchAndMessages() throws Exception {
        final int clientId = 1005;
        final int sessionId = 20;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeCount = 7;
        final int reasonFail = WifiNanSessionCallback.FAIL_REASON_NO_RESOURCES;
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final String peerMsg = "some message from peer";
        final int messageId = 6948;

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi)
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                .setSubscribeCount(subscribeCount).build();

        IWifiNanSessionCallback mockCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mMockNative);

        mDut.connect(clientId, null, 0);
        mDut.createSession(clientId, sessionId, mockCallback);
        mDut.subscribe(clientId, sessionId, subscribeConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        mDut.onSubscribeSuccess(transactionId.getValue(), subscribeId);
        mDut.onMatch(subscribeId, requestorId, peerMac, peerSsi.getBytes(), peerSsi.length(),
                peerMatchFilter.getBytes(), peerMatchFilter.length());
        mDut.onMessageReceived(subscribeId, requestorId, peerMac, peerMsg.getBytes(),
                peerMsg.length());
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mockCallback).onMatch(requestorId, peerSsi.getBytes(), peerSsi.length(),
                peerMatchFilter.getBytes(), peerMatchFilter.length());
        inOrder.verify(mockCallback).onMessageReceived(requestorId, peerMsg.getBytes(),
                peerMsg.length());

        mDut.sendMessage(clientId, sessionId, requestorId, ssi.getBytes(), ssi.length(), messageId);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()));

        mDut.onMessageSendFail(transactionId.getValue(), reasonFail);
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mockCallback).onMessageSendFail(messageId, reasonFail);

        mDut.sendMessage(clientId, sessionId, requestorId, ssi.getBytes(), ssi.length(), messageId);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(subscribeId),
                eq(requestorId), eq(peerMac), eq(ssi.getBytes()), eq(ssi.length()));

        mDut.onMessageSendSuccess(transactionId.getValue());
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mockCallback).onMessageSendSuccess(messageId);

        verifyNoMoreInteractions(mockCallback, mMockNative);
    }

    /**
     * Summary: in a single publish session interact with multiple peers
     * (different MAC addresses).
     */
    @Test
    public void testMultipleMessageSources() throws Exception {
        final int clientId = 300;
        final int clusterLow = 7;
        final int clusterHigh = 7;
        final int masterPref = 0;
        final int sessionId = 26;
        final String serviceName = "some-service-name";
        final int publishId = 88;
        final int peerId1 = 568;
        final int peerId2 = 873;
        final byte[] peerMac1 = HexEncoding.decode("000102030405".toCharArray(), false);
        final byte[] peerMac2 = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String msgFromPeer1 = "hey from 000102...";
        final String msgFromPeer2 = "hey from 0607...";
        final String msgToPeer1 = "hey there 000102...";
        final String msgToPeer2 = "hey there 0506...";
        final int msgToPeerId1 = 546;
        final int msgToPeerId2 = 9654;
        final int reason = WifiNanSessionCallback.FAIL_REASON_OTHER;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.connect(clientId, mockCallback, mAllNanEventFlags);
        mDut.requestConfig(clientId, configRequest);
        mDut.createSession(clientId, sessionId, mockSessionCallback);
        mDut.publish(clientId, sessionId, publishConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest));
        short transactionIdConfig = transactionId.getValue();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        short transactionIdPublish = transactionId.getValue();

        mDut.onConfigCompleted(transactionIdConfig);
        mDut.onPublishSuccess(transactionIdPublish, publishId);
        mDut.onMessageReceived(publishId, peerId1, peerMac1, msgFromPeer1.getBytes(),
                msgFromPeer1.length());
        mDut.onMessageReceived(publishId, peerId2, peerMac2, msgFromPeer2.getBytes(),
                msgFromPeer2.length());
        mDut.sendMessage(clientId, sessionId, peerId2, msgToPeer2.getBytes(), msgToPeer2.length(),
                msgToPeerId2);
        mDut.sendMessage(clientId, sessionId, peerId1, msgToPeer1.getBytes(), msgToPeer1.length(),
                msgToPeerId1);
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionIdConfig);
        validateInternalTransactionInfoCleanedUp(transactionIdPublish);
        inOrder.verify(mockCallback).onConfigCompleted(configRequest);
        inOrder.verify(mockSessionCallback).onMessageReceived(peerId1, msgFromPeer1.getBytes(),
                msgFromPeer1.length());
        inOrder.verify(mockSessionCallback).onMessageReceived(peerId2, msgFromPeer2.getBytes(),
                msgFromPeer2.length());
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(publishId), eq(peerId2),
                eq(peerMac2), eq(msgToPeer2.getBytes()), eq(msgToPeer2.length()));
        short transactionIdMsg2 = transactionId.getValue();
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(publishId), eq(peerId1),
                eq(peerMac1), eq(msgToPeer1.getBytes()), eq(msgToPeer1.length()));
        short transactionIdMsg1 = transactionId.getValue();

        mDut.onMessageSendFail(transactionIdMsg1, reason);
        mDut.onMessageSendSuccess(transactionIdMsg2);
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionIdMsg1);
        validateInternalTransactionInfoCleanedUp(transactionIdMsg2);
        inOrder.verify(mockSessionCallback).onMessageSendFail(msgToPeerId1, reason);
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(msgToPeerId2);
        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    /**
     * Summary: interact with a peer which changed its identity (MAC address)
     * but which keeps its requestor instance ID. Should be transparent.
     */
    @Test
    public void testMessageWhilePeerChangesIdentity() throws Exception {
        final int clientId = 300;
        final int clusterLow = 7;
        final int clusterHigh = 7;
        final int masterPref = 0;
        final int sessionId = 26;
        final String serviceName = "some-service-name";
        final int publishId = 88;
        final int peerId = 568;
        final byte[] peerMacOrig = HexEncoding.decode("000102030405".toCharArray(), false);
        final byte[] peerMacLater = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String msgFromPeer1 = "hey from 000102...";
        final String msgFromPeer2 = "hey from 0607...";
        final String msgToPeer1 = "hey there 000102...";
        final String msgToPeer2 = "hey there 0506...";
        final int msgToPeerId1 = 546;
        final int msgToPeerId2 = 9654;
        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.connect(clientId, mockCallback, mAllNanEventFlags);
        mDut.requestConfig(clientId, configRequest);
        mDut.createSession(clientId, sessionId, mockSessionCallback);
        mDut.publish(clientId, sessionId, publishConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest));
        short transactionIdConfig = transactionId.getValue();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        short transactionIdPublish = transactionId.getValue();

        mDut.onConfigCompleted(transactionIdConfig);
        mDut.onPublishSuccess(transactionIdPublish, publishId);
        mDut.onMessageReceived(publishId, peerId, peerMacOrig, msgFromPeer1.getBytes(),
                msgFromPeer1.length());
        mDut.sendMessage(clientId, sessionId, peerId, msgToPeer1.getBytes(), msgToPeer1.length(),
                msgToPeerId1);
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionIdConfig);
        validateInternalTransactionInfoCleanedUp(transactionIdPublish);
        inOrder.verify(mockCallback).onConfigCompleted(configRequest);
        inOrder.verify(mockSessionCallback).onMessageReceived(peerId, msgFromPeer1.getBytes(),
                msgFromPeer1.length());
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(publishId), eq(peerId),
                eq(peerMacOrig), eq(msgToPeer1.getBytes()), eq(msgToPeer1.length()));
        short transactionIdMsg = transactionId.getValue();

        mDut.onMessageSendSuccess(transactionIdMsg);
        mDut.onMessageReceived(publishId, peerId, peerMacLater, msgFromPeer2.getBytes(),
                msgFromPeer2.length());
        mDut.sendMessage(clientId, sessionId, peerId, msgToPeer2.getBytes(), msgToPeer2.length(),
                msgToPeerId2);
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionIdMsg);
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(msgToPeerId1);
        inOrder.verify(mockSessionCallback).onMessageReceived(peerId, msgFromPeer2.getBytes(),
                msgFromPeer2.length());
        inOrder.verify(mMockNative).sendMessage(transactionId.capture(), eq(publishId), eq(peerId),
                eq(peerMacLater), eq(msgToPeer2.getBytes()), eq(msgToPeer2.length()));
        transactionIdMsg = transactionId.getValue();

        mDut.onMessageSendSuccess(transactionIdMsg);
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionIdMsg);
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(msgToPeerId2);

        verifyNoMoreInteractions(mMockNative, mockCallback, mockSessionCallback);
    }

    @Test
    public void testSendMessageToInvalidPeerId() throws Exception {
        final int clientId = 1005;
        final int sessionId = 20;
        final String ssi = "some much longer and more arbitrary data";
        final int subscribeId = 15;
        final int requestorId = 22;
        final byte[] peerMac = HexEncoding.decode("060708090A0B".toCharArray(), false);
        final String peerSsi = "some peer ssi data";
        final String peerMatchFilter = "filter binary array represented as string";
        final int messageId = 6948;

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        IWifiNanSessionCallback mockCallback = mock(IWifiNanSessionCallback.class);
        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        InOrder inOrder = inOrder(mockCallback, mMockNative);

        mDut.connect(clientId, null, 0);
        mDut.createSession(clientId, sessionId, mockCallback);
        mDut.subscribe(clientId, sessionId, subscribeConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        mDut.onSubscribeSuccess(transactionId.getValue(), subscribeId);
        mDut.onMatch(subscribeId, requestorId, peerMac, peerSsi.getBytes(), peerSsi.length(),
                peerMatchFilter.getBytes(), peerMatchFilter.length());
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mockCallback).onMatch(requestorId, peerSsi.getBytes(), peerSsi.length(),
                peerMatchFilter.getBytes(), peerMatchFilter.length());

        mDut.sendMessage(clientId, sessionId, requestorId + 5, ssi.getBytes(), ssi.length(),
                messageId);
        mMockLooper.dispatchAll();

        inOrder.verify(mockCallback).onMessageSendFail(messageId,
                WifiNanSessionCallback.FAIL_REASON_NO_MATCH_SESSION);
        validateInternalTransactionInfoCleanedUp(transactionId.getValue());

        verifyNoMoreInteractions(mockCallback, mMockNative);
    }

    @Test
    public void testConfigs() throws Exception {
        final int clientId1 = 9999;
        final int clusterLow1 = 5;
        final int clusterHigh1 = 100;
        final int masterPref1 = 111;
        final int clientId2 = 1001;
        final boolean support5g2 = true;
        final int clusterLow2 = 7;
        final int clusterHigh2 = 155;
        final int masterPref2 = 0;
        final int clientId3 = 55;

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<ConfigRequest> crCapture = ArgumentCaptor.forClass(ConfigRequest.class);

        ConfigRequest configRequest1 = new ConfigRequest.Builder().setClusterLow(clusterLow1)
                .setClusterHigh(clusterHigh1).setMasterPreference(masterPref1).build();

        ConfigRequest configRequest2 = new ConfigRequest.Builder().setSupport5gBand(support5g2)
                .setClusterLow(clusterLow2).setClusterHigh(clusterHigh2)
                .setMasterPreference(masterPref2).build();

        ConfigRequest configRequest3 = new ConfigRequest.Builder().build();

        IWifiNanEventCallback mockCallback1 = mock(IWifiNanEventCallback.class);
        IWifiNanEventCallback mockCallback2 = mock(IWifiNanEventCallback.class);
        IWifiNanEventCallback mockCallback3 = mock(IWifiNanEventCallback.class);

        InOrder inOrder = inOrder(mMockNative, mockCallback1, mockCallback2, mockCallback3);

        mDut.connect(clientId1, mockCallback1, WifiNanEventCallback.FLAG_LISTEN_CONFIG_COMPLETED);
        mDut.requestConfig(clientId1, configRequest1);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture());
        collector.checkThat("merge: stage 0", configRequest1, equalTo(crCapture.getValue()));

        mDut.onConfigCompleted(transactionId.getValue());
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mockCallback1).onConfigCompleted(configRequest1);

        mDut.connect(clientId2, mockCallback2, WifiNanEventCallback.FLAG_LISTEN_CONFIG_COMPLETED);
        mDut.requestConfig(clientId2, configRequest2);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture());
        collector.checkThat("merge: stage 1: support 5g", crCapture.getValue().mSupport5gBand,
                equalTo(true));
        collector.checkThat("merge: stage 1: master pref", crCapture.getValue().mMasterPreference,
                equalTo(Math.max(masterPref1, masterPref2)));
        collector.checkThat("merge: stage 1: cluster low", crCapture.getValue().mClusterLow,
                equalTo(Math.min(clusterLow1, clusterLow2)));
        collector.checkThat("merge: stage 1: cluster high", crCapture.getValue().mClusterHigh,
                equalTo(Math.max(clusterHigh1, clusterHigh2)));

        mDut.onConfigCompleted(transactionId.getValue());
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mockCallback1).onConfigCompleted(crCapture.getValue());

        mDut.connect(clientId3, mockCallback3, WifiNanEventCallback.FLAG_LISTEN_CONFIG_COMPLETED);
        mDut.requestConfig(clientId3, configRequest3);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture());
        collector.checkThat("merge: stage 2: support 5g", crCapture.getValue().mSupport5gBand,
                equalTo(true));
        collector.checkThat("merge: stage 2: master pref", crCapture.getValue().mMasterPreference,
                equalTo(Math.max(masterPref1, masterPref2)));
        collector.checkThat("merge: stage 2: cluster low", crCapture.getValue().mClusterLow,
                equalTo(Math.min(clusterLow1, clusterLow2)));
        collector.checkThat("merge: stage 2: cluster high", crCapture.getValue().mClusterHigh,
                equalTo(Math.max(clusterHigh1, clusterHigh2)));

        mDut.onConfigCompleted(transactionId.getValue());
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mockCallback1).onConfigCompleted(crCapture.getValue());

        mDut.disconnect(clientId2);
        mMockLooper.dispatchAll();

        validateInternalClientInfoCleanedUp(clientId2);
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture());
        collector.checkThat("merge: stage 3", configRequest1, equalTo(crCapture.getValue()));

        mDut.onConfigCompleted(transactionId.getValue());
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mockCallback1).onConfigCompleted(crCapture.getValue());

        mDut.disconnect(clientId1);
        mMockLooper.dispatchAll();

        validateInternalClientInfoCleanedUp(clientId2);
        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(),
                crCapture.capture());
        collector.checkThat("merge: stage 4", configRequest3, equalTo(crCapture.getValue()));

        mDut.onConfigCompleted(transactionId.getValue());
        mMockLooper.dispatchAll();

        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
        inOrder.verify(mockCallback3).onConfigCompleted(crCapture.getValue());

        mDut.disconnect(clientId3);
        mMockLooper.dispatchAll();

        validateInternalClientInfoCleanedUp(clientId2);
        inOrder.verify(mMockNative).disable(anyShort());

        verifyNoMoreInteractions(mMockNative);
    }

    /**
     * Summary: disconnect a client while there are pending transactions.
     * Validate that no callbacks are called and that internal state is
     * cleaned-up.
     */
    @Test
    public void testDisconnectWithPendingTransactions() throws Exception {
        final int clientId = 125;
        final int clusterLow = 5;
        final int clusterHigh = 100;
        final int masterPref = 111;
        final int sessionId = 20;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int publishCount = 7;
        final int reason = WifiNanSessionCallback.TERMINATE_REASON_DONE;
        final int publishId = 22;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi).setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                .setPublishCount(publishCount).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.connect(clientId, mockCallback, mAllNanEventFlags);
        mDut.createSession(clientId, sessionId, mockSessionCallback);
        mDut.requestConfig(clientId, configRequest);
        mDut.publish(clientId, sessionId, publishConfig);
        mDut.disconnect(clientId);
        mDut.publish(clientId, sessionId, publishConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest));
        short transactionIdConfig = transactionId.getValue();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        short transactionIdPublish = transactionId.getValue();

        validateInternalClientInfoCleanedUp(clientId);
        validateInternalTransactionInfoCleanedUp(transactionIdPublish);

        mDut.onConfigCompleted(transactionIdConfig);
        mDut.onPublishSuccess(transactionIdPublish, publishId);
        mMockLooper.dispatchAll();

        mDut.onPublishTerminated(publishId, reason);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).disable(anyShort());
        verifyZeroInteractions(mockCallback, mockSessionCallback);
    }

    /**
     * Summary: destroy a session while there are pending transactions. Validate
     * that no callbacks are called and that internal state is cleaned-up.
     */
    @Test
    public void testDestroySessionWithPendingTransactions() throws Exception {
        final int clientId = 128;
        final int clusterLow = 15;
        final int clusterHigh = 192;
        final int masterPref = 234;
        final int publishSessionId = 19;
        final int subscribeSessionId = 24;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int publishCount = 15;
        final int subscribeCount = 22;
        final int reason = WifiNanSessionCallback.TERMINATE_REASON_DONE;
        final int publishId = 23;
        final int subscribeId = 55;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi).setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                .setPublishCount(publishCount).build();

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi)
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                .setSubscribeCount(subscribeCount).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockPublishSessionCallback = mock(IWifiNanSessionCallback.class);
        IWifiNanSessionCallback mockSubscribeSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockPublishSessionCallback,
                mockSubscribeSessionCallback);

        mDut.connect(clientId, mockCallback, mAllNanEventFlags);
        mDut.requestConfig(clientId, configRequest);
        mDut.createSession(clientId, publishSessionId, mockPublishSessionCallback);
        mDut.publish(clientId, publishSessionId, publishConfig);
        mDut.createSession(clientId, subscribeSessionId, mockSubscribeSessionCallback);
        mDut.subscribe(clientId, subscribeSessionId, subscribeConfig);
        mDut.destroySession(clientId, publishSessionId);
        mDut.publish(clientId, publishSessionId, publishConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest));
        short transactionIdConfig = transactionId.getValue();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        short transactionIdPublish = transactionId.getValue();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        short transactionIdSubscribe = transactionId.getValue();

        validateInternalTransactionInfoCleanedUp(transactionIdPublish);

        mDut.onConfigCompleted(transactionIdConfig);
        mDut.onPublishSuccess(transactionIdPublish, publishId);
        mDut.onSubscribeSuccess(transactionIdSubscribe, subscribeId);
        mDut.onPublishTerminated(publishId, reason);
        mDut.destroySession(clientId, subscribeSessionId);
        mDut.onSubscribeTerminated(subscribeId, reason);
        mMockLooper.dispatchAll();

        inOrder.verify(mockCallback).onConfigCompleted(configRequest);
        inOrder.verify(mMockNative).stopSubscribe(anyShort(), eq(subscribeId));
        verifyZeroInteractions(mockPublishSessionCallback);
        verifyNoMoreInteractions(mockSubscribeSessionCallback);
        validateInternalTransactionInfoCleanedUp(transactionIdConfig);
        validateInternalTransactionInfoCleanedUp(transactionIdSubscribe);
    }

    /**
     * Summary: stop a session while there are pending transactions. Validate
     * that active publish/subscribe is cancelled - callbacks are routed until
     * the Terminate callback (kind of bogus since HAL wouldn't trigger callback
     * past termination).
     */
    @Test
    public void testStopSessionWithPendingTransactions() throws Exception {
        final int clientId = 128;
        final int clusterLow = 15;
        final int clusterHigh = 192;
        final int masterPref = 234;
        final int publishSessionId = 19;
        final int subscribeSessionId = 24;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int publishCount = 15;
        final int subscribeCount = 22;
        final int reason = WifiNanSessionCallback.TERMINATE_REASON_DONE;
        final int publishId = 23;
        final int subscribeId = 55;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi).setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                .setPublishCount(publishCount).build();

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi)
                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
                .setSubscribeCount(subscribeCount).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockPublishSessionCallback = mock(IWifiNanSessionCallback.class);
        IWifiNanSessionCallback mockSubscribeSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockPublishSessionCallback,
                mockSubscribeSessionCallback);

        mDut.connect(clientId, mockCallback, mAllNanEventFlags);
        mDut.requestConfig(clientId, configRequest);
        mDut.createSession(clientId, publishSessionId, mockPublishSessionCallback);
        mDut.publish(clientId, publishSessionId, publishConfig);
        mDut.createSession(clientId, subscribeSessionId, mockSubscribeSessionCallback);
        mDut.subscribe(clientId, subscribeSessionId, subscribeConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest));
        short transactionIdConfig = transactionId.getValue();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        short transactionIdPublish = transactionId.getValue();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));
        short transactionIdSubscribe = transactionId.getValue();

        mDut.onConfigCompleted(transactionIdConfig);
        mDut.onPublishSuccess(transactionIdPublish, publishId);
        mDut.onSubscribeSuccess(transactionIdSubscribe, subscribeId);
        mDut.stopSession(clientId, publishSessionId);
        mDut.stopSession(clientId, subscribeSessionId);
        mDut.onPublishTerminated(publishId, reason);
        mDut.onSubscribeTerminated(subscribeId, reason);
        mMockLooper.dispatchAll();

        inOrder.verify(mockCallback).onConfigCompleted(configRequest);
        inOrder.verify(mMockNative).stopPublish(anyShort(), eq(publishId));
        inOrder.verify(mMockNative).stopSubscribe(anyShort(), eq(subscribeId));
        inOrder.verify(mockPublishSessionCallback).onSessionTerminated(reason);
        inOrder.verify(mockSubscribeSessionCallback).onSessionTerminated(reason);
        verifyNoMoreInteractions(mockPublishSessionCallback);
        verifyNoMoreInteractions(mockSubscribeSessionCallback);
        validateInternalTransactionInfoCleanedUp(transactionIdConfig);
        validateInternalTransactionInfoCleanedUp(transactionIdPublish);
        validateInternalTransactionInfoCleanedUp(transactionIdSubscribe);
    }

    /**
     * Validate that an unknown transaction (i.e. a callback from HAL with an
     * unknown type) is simply ignored - but also cleans up its state.
     */
    @Test
    public void testUnknownTransactionType() throws Exception {
        final int clientId = 129;
        final int clusterLow = 15;
        final int clusterHigh = 192;
        final int masterPref = 234;
        final int publishSessionId = 19;
        final String serviceName = "some-service-name";
        final String ssi = "some much longer and more arbitrary data";
        final int publishCount = 15;
        final int status = WifiNanSessionCallback.FAIL_REASON_OTHER;
        final int responseType = 9999;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setMasterPreference(masterPref).build();

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(ssi).setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                .setPublishCount(publishCount).build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockPublishSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockPublishSessionCallback);

        mDut.connect(clientId, mockCallback, mAllNanEventFlags);
        mDut.requestConfig(clientId, configRequest);
        mDut.createSession(clientId, publishSessionId, mockPublishSessionCallback);
        mDut.publish(clientId, publishSessionId, publishConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest));
        short transactionIdConfig = transactionId.getValue();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));
        short transactionIdPublish = transactionId.getValue();

        mDut.onUnknownTransaction(responseType, transactionIdConfig, status);
        mDut.onUnknownTransaction(responseType, transactionIdPublish, status);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockCallback);
        verifyNoMoreInteractions(mockPublishSessionCallback);
        validateInternalTransactionInfoCleanedUp(transactionIdConfig);
        validateInternalTransactionInfoCleanedUp(transactionIdPublish);
    }

    /**
     * Validate that a NoOp transaction (i.e. a callback from HAL which doesn't
     * require any action except clearing up state) actually cleans up its state
     * (and does nothing else).
     */
    @Test
    public void testNoOpTransaction() throws Exception {
        final int clientId = 1294;
        final int sessionId = 19;

        PublishConfig publishConfig = new PublishConfig.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.connect(clientId, mockCallback, mAllNanEventFlags);
        mDut.createSession(clientId, sessionId, mockSessionCallback);
        mDut.publish(clientId, sessionId, publishConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        mDut.onNoOpTransaction(transactionId.getValue());
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockCallback);
        verifyNoMoreInteractions(mockSessionCallback);
        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
    }

    /**
     * Validate that getting callbacks from HAL with unknown (expired)
     * transaction ID or invalid publish/subscribe ID session doesn't have any
     * impact.
     */
    @Test
    public void testInvalidCallbackIdParameters() throws Exception {
        final int clientId = 132;

        ConfigRequest configRequest = new ConfigRequest.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback);

        mDut.connect(clientId, mockCallback, mAllNanEventFlags);
        mDut.requestConfig(clientId, configRequest);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).enableAndConfigure(transactionId.capture(), eq(configRequest));
        short transactionIdConfig = transactionId.getValue();

        mDut.onConfigCompleted(transactionIdConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mockCallback).onConfigCompleted(configRequest);
        validateInternalTransactionInfoCleanedUp(transactionIdConfig);

        mDut.onCapabilitiesUpdate(transactionIdConfig, null);
        mDut.onConfigCompleted(transactionIdConfig);
        mDut.onConfigFailed(transactionIdConfig, -1);
        mDut.onPublishSuccess(transactionIdConfig, -1);
        mDut.onPublishFail(transactionIdConfig, -1);
        mDut.onMessageSendSuccess(transactionIdConfig);
        mDut.onMessageSendFail(transactionIdConfig, -1);
        mDut.onSubscribeSuccess(transactionIdConfig, -1);
        mDut.onSubscribeFail(transactionIdConfig, -1);
        mDut.onUnknownTransaction(-10, transactionIdConfig, -1);
        mDut.onMatch(-1, -1, null, null, 0, null, 0);
        mDut.onPublishTerminated(-1, -1);
        mDut.onSubscribeTerminated(-1, -1);
        mDut.onMessageReceived(-1, -1, null, null, 0);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockCallback);
    }

    /**
     * Validate that trying to (re)subscribe on a publish session fails.
     */
    @Test
    public void testSubscribeOnPublishSessionType() throws Exception {
        final int clientId = 188;
        final int sessionId = 19;
        final int status = WifiNanSessionCallback.FAIL_REASON_NO_RESOURCES;

        PublishConfig publishConfig = new PublishConfig.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.connect(clientId, mockCallback, mAllNanEventFlags);
        mDut.createSession(clientId, sessionId, mockSessionCallback);
        mDut.publish(clientId, sessionId, publishConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).publish(transactionId.capture(), eq(0), eq(publishConfig));

        mDut.onPublishFail(transactionId.getValue(), status);
        mDut.subscribe(clientId, sessionId, subscribeConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mockSessionCallback).onSessionConfigFail(status);
        inOrder.verify(mockSessionCallback)
                .onSessionConfigFail(WifiNanSessionCallback.FAIL_REASON_OTHER);
        verifyNoMoreInteractions(mockCallback);
        verifyNoMoreInteractions(mockSessionCallback);
        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
    }

    /**
     * Validate that trying to (re)subscribe on a publish session or (re)publish
     * on a subscribe session fails.
     */
    @Test
    public void testPublishOnSubscribeSessionType() throws Exception {
        final int clientId = 188;
        final int sessionId = 19;
        final int status = WifiNanSessionCallback.FAIL_REASON_NO_RESOURCES;

        PublishConfig publishConfig = new PublishConfig.Builder().build();
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        IWifiNanEventCallback mockCallback = mock(IWifiNanEventCallback.class);
        IWifiNanSessionCallback mockSessionCallback = mock(IWifiNanSessionCallback.class);
        InOrder inOrder = inOrder(mMockNative, mockCallback, mockSessionCallback);

        mDut.connect(clientId, mockCallback, mAllNanEventFlags);
        mDut.createSession(clientId, sessionId, mockSessionCallback);
        mDut.subscribe(clientId, sessionId, subscribeConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mMockNative).subscribe(transactionId.capture(), eq(0), eq(subscribeConfig));

        mDut.onSubscribeFail(transactionId.getValue(), status);
        mDut.publish(clientId, sessionId, publishConfig);
        mMockLooper.dispatchAll();

        inOrder.verify(mockSessionCallback).onSessionConfigFail(status);
        inOrder.verify(mockSessionCallback)
                .onSessionConfigFail(WifiNanSessionCallback.FAIL_REASON_OTHER);
        verifyNoMoreInteractions(mockCallback);
        verifyNoMoreInteractions(mockSessionCallback);
        validateInternalTransactionInfoCleanedUp(transactionId.getValue());
    }

    @Test
    public void testTransactionIdIncrement() {
        int loopCount = 100;

        short prevId = 0;
        for (int i = 0; i < loopCount; ++i) {
            short id = mDut.createNextTransactionId();
            if (i != 0) {
                assertTrue("Transaction ID incrementing", id > prevId);
            }
            prevId = id;
        }
    }

    /*
     * Tests of internal state of WifiNanStateManager: very limited (not usually
     * a good idea). However, these test that the internal state is cleaned-up
     * appropriately. Alternatively would cause issues with memory leaks or
     * information leak between sessions.
     */

    /**
     * Utility routine used to validate that the internal state is cleaned-up
     * after the specific transaction ID. To be used in every test which
     * involves a transaction.
     *
     * @param transactionId The transaction ID whose state should be erased.
     */
    public void validateInternalTransactionInfoCleanedUp(short transactionId) throws Exception {
        Object info = getInternalPendingTransactionInfo(mDut, transactionId);
        collector.checkThat("Transaction record not cleared up for transactionId=" + transactionId,
                info, nullValue());
    }

    /**
     * Utility routine used to validate that the internal state is cleaned-up
     * after a client is disconnected. To be used in every test which terminates
     * a client.
     *
     * @param clientId The ID of the client which should be deleted.
     */
    public void validateInternalClientInfoCleanedUp(int clientId) throws Exception {
        WifiNanClientState client = getInternalClientState(mDut, clientId);
        collector.checkThat("Client record not cleared up for clientId=" + clientId, client,
                nullValue());
    }

    /*
     * Utilities
     */

    private static WifiNanStateManager installNewNanStateManagerAndResetState() throws Exception {
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

    private static Object getInternalPendingTransactionInfo(WifiNanStateManager dut,
            short transactionId) throws Exception {
        Field field = WifiNanStateManager.class.getDeclaredField("mPendingResponses");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        SparseArray<Object> pendingResponses = (SparseArray<Object>) field.get(dut);

        return pendingResponses.get(transactionId);
    }

    private static WifiNanClientState getInternalClientState(WifiNanStateManager dut, int clientId)
            throws Exception {
        Field field = WifiNanStateManager.class.getDeclaredField("mClients");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        SparseArray<WifiNanClientState> clients = (SparseArray<WifiNanClientState>) field.get(dut);

        return clients.get(clientId);
    }
}

