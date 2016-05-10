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
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.MockLooper;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit test harness for WifiNanManager class.
 */
@SmallTest
public class WifiNanManagerTest {
    private WifiNanManager mDut;
    private MockLooper mMockLooper;

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    @Mock
    public WifiNanEventCallback mockCallback;

    @Mock
    public WifiNanSessionCallback mockSessionCallback;

    @Mock
    public IWifiNanManager mockNanService;

    @Mock
    public WifiNanPublishSession mockPublishSession;

    @Mock
    public WifiNanSubscribeSession mockSubscribeSession;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mDut = new WifiNanManager(mockNanService);
        mMockLooper = new MockLooper();
    }

    /*
     * Straight pass-through tests
     */

    /**
     * Validate pass-through of enableUsage() API.
     */
    @Test
    public void testEnableUsage() throws Exception {
        mDut.enableUsage();

        verify(mockNanService).enableUsage();
    }

    /**
     * Validate pass-through of disableUsage() API.
     */
    @Test
    public void testDisableUsage() throws Exception {
        mDut.disableUsage();

        verify(mockNanService).disableUsage();
    }

    /**
     * Validate pass-through of isUsageEnabled() API.
     */
    @Test
    public void testIsUsageEnable() throws Exception {
        mDut.isUsageEnabled();

        verify(mockNanService).isUsageEnabled();
    }

    /*
     * WifiNanEventCallbackProxy Tests
     */

    /**
     * Validate the successful connect flow: (1) try subscribing (2) connect +
     * success (3) publish, (4) disconnect (5) try publishing (6) connect again
     */
    @Test
    public void testConnectFlow() throws Exception {
        final int clientId = 4565;

        when(mockNanService.connect(any(IBinder.class), any(IWifiNanEventCallback.class),
                any(ConfigRequest.class))).thenReturn(clientId);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockNanService);
        ArgumentCaptor<IWifiNanEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiNanEventCallback.class);
        ArgumentCaptor<IBinder> binder = ArgumentCaptor.forClass(IBinder.class);

        // (1) try subscribing on an unconnected manager: fails silently
        mDut.subscribe(new SubscribeConfig.Builder().build(), mockSessionCallback);

        // (2) connect + success
        mDut.connect(mMockLooper.getLooper(), mockCallback);
        inOrder.verify(mockNanService).connect(binder.capture(),
                clientProxyCallback.capture(), (ConfigRequest) isNull());
        clientProxyCallback.getValue().onConnectSuccess();
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (3) publish - should succeed
        PublishConfig publishConfig = new PublishConfig.Builder().build();
        mDut.publish(publishConfig, mockSessionCallback);
        inOrder.verify(mockNanService).publish(eq(clientId), eq(publishConfig),
                any(IWifiNanSessionCallback.class));

        // (4) disconnect
        mDut.disconnect();
        inOrder.verify(mockNanService).disconnect(eq(clientId), eq(binder.getValue()));

        // (5) try publishing again - fails silently
        mDut.publish(new PublishConfig.Builder().build(), mockSessionCallback);

        // (6) connect
        mDut.connect(mMockLooper.getLooper(), mockCallback);
        inOrder.verify(mockNanService).connect(binder.capture(), any(IWifiNanEventCallback.class),
                (ConfigRequest) isNull());

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockNanService);
    }

    /**
     * Validate the failed connect flow: (1) connect + failure, (2) try
     * publishing (3) connect + success (4) subscribe
     */
    @Test
    public void testConnectFailure() throws Exception {
        final int clientId = 4565;
        final int reason = WifiNanEventCallback.REASON_OTHER;

        when(mockNanService.connect(any(IBinder.class), any(IWifiNanEventCallback.class),
                any(ConfigRequest.class))).thenReturn(clientId);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockNanService);
        ArgumentCaptor<IWifiNanEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiNanEventCallback.class);

        // (1) connect + failure
        mDut.connect(mMockLooper.getLooper(), mockCallback);
        inOrder.verify(mockNanService).connect(any(IBinder.class), clientProxyCallback.capture(),
                (ConfigRequest) isNull());
        clientProxyCallback.getValue().onConnectFail(reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectFail(reason);

        // (2) try publishing - silent failure (since already know that no
        // connection)
        mDut.publish(new PublishConfig.Builder().build(), mockSessionCallback);

        // (3) connect + success
        mDut.connect(mMockLooper.getLooper(), mockCallback);
        inOrder.verify(mockNanService).connect(any(IBinder.class), clientProxyCallback.capture(),
                (ConfigRequest) isNull());
        clientProxyCallback.getValue().onConnectSuccess();
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (4) subscribe: should succeed
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();
        mDut.subscribe(subscribeConfig, mockSessionCallback);
        inOrder.verify(mockNanService).subscribe(eq(clientId), eq(subscribeConfig),
                any(IWifiNanSessionCallback.class));

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockNanService);
    }

    /**
     * Validate that cannot call connect on an existing connection: (1) connect
     * + success, (2) try connect again
     */
    @Test
    public void testInvalidConnectSequence() throws Exception {
        final int clientId = 4565;

        when(mockNanService.connect(any(IBinder.class), any(IWifiNanEventCallback.class),
                any(ConfigRequest.class))).thenReturn(clientId);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockNanService);
        ArgumentCaptor<IWifiNanEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiNanEventCallback.class);

        // (1) connect + success
        mDut.connect(mMockLooper.getLooper(), mockCallback);
        inOrder.verify(mockNanService).connect(any(IBinder.class), clientProxyCallback.capture(),
                (ConfigRequest) isNull());
        clientProxyCallback.getValue().onConnectSuccess();
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) connect - forward to service (though will fail silently)
        mDut.connect(mMockLooper.getLooper(), mockCallback);
        inOrder.verify(mockNanService).connect(any(IBinder.class), clientProxyCallback.capture(),
                (ConfigRequest) isNull());

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockNanService);
    }

    /*
     * WifiNanSessionCallbackProxy Tests
     */

    /**
     * Validate the publish flow: (0) connect + success, (1) publish, (2)
     * success creates session, (3) pass through everything, (4) update publish
     * through session, (5) terminate locally, (6) try another command -
     * ignored.
     */
    @Test
    public void testPublishFlow() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();
        final int peerId = 873;
        final String string1 = "hey from here...";
        final String string2 = "some other arbitrary string...";
        final int messageId = 2123;
        final int reason = WifiNanSessionCallback.REASON_OTHER;

        when(mockNanService.connect(any(IBinder.class), any(IWifiNanEventCallback.class),
                eq(configRequest))).thenReturn(clientId);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockNanService,
                mockPublishSession);
        ArgumentCaptor<IWifiNanEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiNanEventCallback.class);
        ArgumentCaptor<IWifiNanSessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiNanSessionCallback.class);
        ArgumentCaptor<WifiNanPublishSession> publishSession = ArgumentCaptor
                .forClass(WifiNanPublishSession.class);

        // (0) connect + success
        mDut.connect(mMockLooper.getLooper(), mockCallback, configRequest);
        inOrder.verify(mockNanService).connect(any(IBinder.class),
                clientProxyCallback.capture(), eq(configRequest));
        clientProxyCallback.getValue().onConnectSuccess();
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (1) publish
        mDut.publish(publishConfig, mockSessionCallback);
        inOrder.verify(mockNanService).publish(eq(clientId), eq(publishConfig),
                sessionProxyCallback.capture());

        // (2) publish session created
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onPublishStarted(publishSession.capture());

        // (3) ...
        publishSession.getValue().sendMessage(peerId, string1.getBytes(), string1.length(),
                messageId);
        sessionProxyCallback.getValue().onMatch(peerId, string1.getBytes(),
                string1.length(), string2.getBytes(), string2.length());
        sessionProxyCallback.getValue().onMessageReceived(peerId, string1.getBytes(),
                string1.length());
        sessionProxyCallback.getValue().onMessageSendFail(messageId, reason);
        sessionProxyCallback.getValue().onMessageSendSuccess(messageId);
        mMockLooper.dispatchAll();

        inOrder.verify(mockNanService).sendMessage(eq(clientId), eq(sessionId), eq(peerId),
                eq(string1.getBytes()), eq(string1.length()), eq(messageId));
        inOrder.verify(mockSessionCallback).onMatch(eq(peerId), eq(string1.getBytes()),
                eq(string1.length()), eq(string2.getBytes()), eq(string2.length()));
        inOrder.verify(mockSessionCallback).onMessageReceived(eq(peerId), eq(string1.getBytes()),
                eq(string1.length()));
        inOrder.verify(mockSessionCallback).onMessageSendFail(eq(messageId), eq(reason));
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(eq(messageId));

        // (4) update publish
        publishSession.getValue().updatePublish(publishConfig);
        sessionProxyCallback.getValue().onSessionConfigFail(reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockNanService).updatePublish(eq(clientId), eq(sessionId),
                eq(publishConfig));
        inOrder.verify(mockSessionCallback).onSessionConfigFail(eq(reason));

        // (5) terminate
        publishSession.getValue().terminate();
        mMockLooper.dispatchAll();
        inOrder.verify(mockNanService).terminateSession(clientId, sessionId);

        // (6) try an update (nothing)
        publishSession.getValue().updatePublish(publishConfig);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockNanService,
                mockPublishSession);
    }

    /**
     * Validate race condition of session terminate and session action: (1)
     * connect, (2) publish success + terminate, (3) update.
     */
    @Test
    public void testPublishRemoteTerminate() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final PublishConfig publishConfig = new PublishConfig.Builder().build();
        final int reason = WifiNanSessionCallback.TERMINATE_REASON_DONE;

        when(mockNanService.connect(any(IBinder.class), any(IWifiNanEventCallback.class),
                eq(configRequest))).thenReturn(clientId);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockNanService,
                mockPublishSession);
        ArgumentCaptor<IWifiNanEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiNanEventCallback.class);
        ArgumentCaptor<IWifiNanSessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiNanSessionCallback.class);
        ArgumentCaptor<WifiNanPublishSession> publishSession = ArgumentCaptor
                .forClass(WifiNanPublishSession.class);

        // (1) connect successfully
        mDut.connect(mMockLooper.getLooper(), mockCallback, configRequest);
        inOrder.verify(mockNanService).connect(any(IBinder.class), clientProxyCallback.capture(),
                eq(configRequest));
        clientProxyCallback.getValue().onConnectSuccess();
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) publish: successfully - then terminated
        mDut.publish(publishConfig, mockSessionCallback);
        inOrder.verify(mockNanService).publish(eq(clientId), eq(publishConfig),
                sessionProxyCallback.capture());
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        sessionProxyCallback.getValue().onSessionTerminated(reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onPublishStarted(publishSession.capture());
        inOrder.verify(mockSessionCallback).onSessionTerminated(reason);

        // (3) failure when trying to update: NOP
        publishSession.getValue().updatePublish(publishConfig);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockNanService,
                mockPublishSession);
    }

    /**
     * Validate the subscribe flow: (0) connect + success, (1) subscribe, (2)
     * success creates session, (3) pass through everything, (4) update
     * subscribe through session, (5) terminate locally, (6) try another command
     * - ignored.
     */
    @Test
    public void testSubscribeFlow() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();
        final int peerId = 873;
        final String string1 = "hey from here...";
        final String string2 = "some other arbitrary string...";
        final int messageId = 2123;
        final int reason = WifiNanSessionCallback.REASON_OTHER;

        when(mockNanService.connect(any(IBinder.class), any(IWifiNanEventCallback.class),
                eq(configRequest))).thenReturn(clientId);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockNanService,
                mockSubscribeSession);
        ArgumentCaptor<IWifiNanEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiNanEventCallback.class);
        ArgumentCaptor<IWifiNanSessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiNanSessionCallback.class);
        ArgumentCaptor<WifiNanSubscribeSession> subscribeSession = ArgumentCaptor
                .forClass(WifiNanSubscribeSession.class);

        // (0) connect + success
        mDut.connect(mMockLooper.getLooper(), mockCallback, configRequest);
        inOrder.verify(mockNanService).connect(any(IBinder.class), clientProxyCallback.capture(),
                eq(configRequest));
        clientProxyCallback.getValue().onConnectSuccess();
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (1) subscribe
        mDut.subscribe(subscribeConfig, mockSessionCallback);
        inOrder.verify(mockNanService).subscribe(eq(clientId), eq(subscribeConfig),
                sessionProxyCallback.capture());

        // (2) subscribe session created
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSubscribeStarted(subscribeSession.capture());

        // (3) ...
        subscribeSession.getValue().sendMessage(peerId, string1.getBytes(), string1.length(),
                messageId);
        sessionProxyCallback.getValue().onMatch(peerId, string1.getBytes(), string1.length(),
                string2.getBytes(), string2.length());
        sessionProxyCallback.getValue().onMessageReceived(peerId, string1.getBytes(),
                string1.length());
        sessionProxyCallback.getValue().onMessageSendFail(messageId, reason);
        sessionProxyCallback.getValue().onMessageSendSuccess(messageId);
        mMockLooper.dispatchAll();

        inOrder.verify(mockNanService).sendMessage(eq(clientId), eq(sessionId), eq(peerId),
                eq(string1.getBytes()), eq(string1.length()), eq(messageId));
        inOrder.verify(mockSessionCallback).onMatch(eq(peerId), eq(string1.getBytes()),
                eq(string1.length()), eq(string2.getBytes()), eq(string2.length()));
        inOrder.verify(mockSessionCallback).onMessageReceived(eq(peerId), eq(string1.getBytes()),
                eq(string1.length()));
        inOrder.verify(mockSessionCallback).onMessageSendFail(eq(messageId), eq(reason));
        inOrder.verify(mockSessionCallback).onMessageSendSuccess(eq(messageId));

        // (4) update subscribe
        subscribeSession.getValue().updateSubscribe(subscribeConfig);
        sessionProxyCallback.getValue().onSessionConfigFail(reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockNanService).updateSubscribe(eq(clientId), eq(sessionId),
                eq(subscribeConfig));
        inOrder.verify(mockSessionCallback).onSessionConfigFail(eq(reason));

        // (5) terminate
        subscribeSession.getValue().terminate();
        mMockLooper.dispatchAll();
        inOrder.verify(mockNanService).terminateSession(clientId, sessionId);

        // (6) try an update (nothing)
        subscribeSession.getValue().updateSubscribe(subscribeConfig);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockNanService,
                mockSubscribeSession);
    }

    /**
     * Validate race condition of session terminate and session action: (1)
     * connect, (2) subscribe success + terminate, (3) update.
     */
    @Test
    public void testSubscribeRemoteTerminate() throws Exception {
        final int clientId = 4565;
        final int sessionId = 123;
        final ConfigRequest configRequest = new ConfigRequest.Builder().build();
        final SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();
        final int reason = WifiNanSessionCallback.TERMINATE_REASON_DONE;

        when(mockNanService.connect(any(IBinder.class), any(IWifiNanEventCallback.class),
                eq(configRequest))).thenReturn(clientId);

        InOrder inOrder = inOrder(mockCallback, mockSessionCallback, mockNanService,
                mockSubscribeSession);
        ArgumentCaptor<IWifiNanEventCallback> clientProxyCallback = ArgumentCaptor
                .forClass(IWifiNanEventCallback.class);
        ArgumentCaptor<IWifiNanSessionCallback> sessionProxyCallback = ArgumentCaptor
                .forClass(IWifiNanSessionCallback.class);
        ArgumentCaptor<WifiNanSubscribeSession> subscribeSession = ArgumentCaptor
                .forClass(WifiNanSubscribeSession.class);

        // (1) connect successfully
        mDut.connect(mMockLooper.getLooper(), mockCallback, configRequest);
        inOrder.verify(mockNanService).connect(any(IBinder.class), clientProxyCallback.capture(),
                eq(configRequest));
        clientProxyCallback.getValue().onConnectSuccess();
        mMockLooper.dispatchAll();
        inOrder.verify(mockCallback).onConnectSuccess();

        // (2) subscribe: successfully - then terminated
        mDut.subscribe(subscribeConfig, mockSessionCallback);
        inOrder.verify(mockNanService).subscribe(eq(clientId), eq(subscribeConfig),
                sessionProxyCallback.capture());
        sessionProxyCallback.getValue().onSessionStarted(sessionId);
        sessionProxyCallback.getValue().onSessionTerminated(reason);
        mMockLooper.dispatchAll();
        inOrder.verify(mockSessionCallback).onSubscribeStarted(subscribeSession.capture());
        inOrder.verify(mockSessionCallback).onSessionTerminated(reason);

        // (3) failure when trying to update: NOP
        subscribeSession.getValue().updateSubscribe(subscribeConfig);

        verifyNoMoreInteractions(mockCallback, mockSessionCallback, mockNanService,
                mockSubscribeSession);
    }

    /*
     * ConfigRequest Tests
     */

    @Test
    public void testConfigRequestBuilderDefaults() {
        ConfigRequest configRequest = new ConfigRequest.Builder().build();

        collector.checkThat("mClusterHigh", ConfigRequest.CLUSTER_ID_MAX,
                equalTo(configRequest.mClusterHigh));
        collector.checkThat("mClusterLow", ConfigRequest.CLUSTER_ID_MIN,
                equalTo(configRequest.mClusterLow));
        collector.checkThat("mMasterPreference", 0,
                equalTo(configRequest.mMasterPreference));
        collector.checkThat("mSupport5gBand", false, equalTo(configRequest.mSupport5gBand));
        collector.checkThat("mEnableIdentityChangeCallback", false,
                equalTo(configRequest.mEnableIdentityChangeCallback));
    }

    @Test
    public void testConfigRequestBuilder() {
        final int clusterHigh = 100;
        final int clusterLow = 5;
        final int masterPreference = 55;
        final boolean supportBand5g = true;
        final boolean enableIdentityChangeCallback = true;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterHigh(clusterHigh)
                .setClusterLow(clusterLow).setMasterPreference(masterPreference)
                .setSupport5gBand(supportBand5g)
                .setEnableIdentityChangeCallback(enableIdentityChangeCallback).build();

        collector.checkThat("mClusterHigh", clusterHigh, equalTo(configRequest.mClusterHigh));
        collector.checkThat("mClusterLow", clusterLow, equalTo(configRequest.mClusterLow));
        collector.checkThat("mMasterPreference", masterPreference,
                equalTo(configRequest.mMasterPreference));
        collector.checkThat("mSupport5gBand", supportBand5g, equalTo(configRequest.mSupport5gBand));
        collector.checkThat("mEnableIdentityChangeCallback", enableIdentityChangeCallback,
                equalTo(configRequest.mEnableIdentityChangeCallback));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderMasterPrefNegative() {
        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setMasterPreference(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderMasterPrefReserved1() {
        new ConfigRequest.Builder().setMasterPreference(1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderMasterPrefReserved255() {
        new ConfigRequest.Builder().setMasterPreference(255);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderMasterPrefTooLarge() {
        new ConfigRequest.Builder().setMasterPreference(256);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterLowNegative() {
        new ConfigRequest.Builder().setClusterLow(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterHighNegative() {
        new ConfigRequest.Builder().setClusterHigh(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterLowAboveMax() {
        new ConfigRequest.Builder().setClusterLow(ConfigRequest.CLUSTER_ID_MAX + 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterHighAboveMax() {
        new ConfigRequest.Builder().setClusterHigh(ConfigRequest.CLUSTER_ID_MAX + 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequestBuilderClusterLowLargerThanHigh() {
        new ConfigRequest.Builder().setClusterLow(100).setClusterHigh(5).build();
    }

    @Test
    public void testConfigRequestParcel() {
        final int clusterHigh = 189;
        final int clusterLow = 25;
        final int masterPreference = 177;
        final boolean supportBand5g = true;
        final boolean enableIdentityChangeCallback = true;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterHigh(clusterHigh)
                .setClusterLow(clusterLow).setMasterPreference(masterPreference)
                .setSupport5gBand(supportBand5g)
                .setEnableIdentityChangeCallback(enableIdentityChangeCallback).build();

        Parcel parcelW = Parcel.obtain();
        configRequest.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        ConfigRequest rereadConfigRequest = ConfigRequest.CREATOR.createFromParcel(parcelR);

        assertEquals(configRequest, rereadConfigRequest);
    }

    /*
     * SubscribeConfig Tests
     */

    @Test
    public void testSubscribeConfigBuilderDefaults() {
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();

        collector.checkThat("mServiceName", subscribeConfig.mServiceName, equalTo(null));
        collector.checkThat("mServiceSpecificInfoLength",
                subscribeConfig.mServiceSpecificInfoLength, equalTo(0));
        collector.checkThat("mTxFilterLength", subscribeConfig.mTxFilterLength, equalTo(0));
        collector.checkThat("mRxFilterLength", subscribeConfig.mRxFilterLength, equalTo(0));
        collector.checkThat("mSubscribeType", subscribeConfig.mSubscribeType,
                equalTo(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE));
        collector.checkThat("mSubscribeCount", subscribeConfig.mSubscribeCount, equalTo(0));
        collector.checkThat("mTtlSec", subscribeConfig.mTtlSec, equalTo(0));
        collector.checkThat("mMatchStyle", subscribeConfig.mMatchStyle,
                equalTo(SubscribeConfig.MATCH_STYLE_ALL));
        collector.checkThat("mEnableTerminateNotification",
                subscribeConfig.mEnableTerminateNotification, equalTo(true));
    }

    @Test
    public void testSubscribeConfigBuilder() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] txFilter = {
                0, 1, 16, 1, 22 };
        final byte[] rxFilter = {
                1, 127, 0, 1, -5, 1, 22 };
        final int subscribeType = SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE;
        final int subscribeCount = 10;
        final int subscribeTtl = 15;
        final int matchStyle = SubscribeConfig.MATCH_STYLE_FIRST_ONLY;
        final boolean enableTerminateNotification = false;

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo).setTxFilter(txFilter, txFilter.length)
                .setRxFilter(rxFilter, rxFilter.length).setSubscribeType(subscribeType)
                .setSubscribeCount(subscribeCount).setTtlSec(subscribeTtl).setMatchStyle(matchStyle)
                .setEnableTerminateNotification(enableTerminateNotification).build();

        collector.checkThat("mServiceName", serviceName, equalTo(subscribeConfig.mServiceName));
        collector.checkThat("mServiceSpecificInfo",
                utilAreArraysEqual(serviceSpecificInfo.getBytes(), serviceSpecificInfo.length(),
                        subscribeConfig.mServiceSpecificInfo,
                        subscribeConfig.mServiceSpecificInfoLength),
                equalTo(true));
        collector.checkThat("mTxFilter", utilAreArraysEqual(txFilter, txFilter.length,
                subscribeConfig.mTxFilter, subscribeConfig.mTxFilterLength), equalTo(true));
        collector.checkThat("mRxFilter", utilAreArraysEqual(rxFilter, rxFilter.length,
                subscribeConfig.mRxFilter, subscribeConfig.mRxFilterLength), equalTo(true));
        collector.checkThat("mSubscribeType", subscribeType,
                equalTo(subscribeConfig.mSubscribeType));
        collector.checkThat("mSubscribeCount", subscribeCount,
                equalTo(subscribeConfig.mSubscribeCount));
        collector.checkThat("mTtlSec", subscribeTtl, equalTo(subscribeConfig.mTtlSec));
        collector.checkThat("mMatchStyle", matchStyle, equalTo(subscribeConfig.mMatchStyle));
        collector.checkThat("mEnableTerminateNotification", enableTerminateNotification,
                equalTo(subscribeConfig.mEnableTerminateNotification));
    }

    @Test
    public void testSubscribeConfigParcel() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] txFilter = {
                0, 1, 16, 1, 22 };
        final byte[] rxFilter = {
                1, 127, 0, 1, -5, 1, 22 };
        final int subscribeType = SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE;
        final int subscribeCount = 10;
        final int subscribeTtl = 15;
        final int matchStyle = SubscribeConfig.MATCH_STYLE_FIRST_ONLY;
        final boolean enableTerminateNotification = true;

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo).setTxFilter(txFilter, txFilter.length)
                .setTxFilter(rxFilter, rxFilter.length).setSubscribeType(subscribeType)
                .setSubscribeCount(subscribeCount).setTtlSec(subscribeTtl).setMatchStyle(matchStyle)
                .setEnableTerminateNotification(enableTerminateNotification).build();

        Parcel parcelW = Parcel.obtain();
        subscribeConfig.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        SubscribeConfig rereadSubscribeConfig = SubscribeConfig.CREATOR.createFromParcel(parcelR);

        assertEquals(subscribeConfig, rereadSubscribeConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeConfigBuilderBadSubscribeType() {
        new SubscribeConfig.Builder().setSubscribeType(10);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeConfigBuilderNegativeCount() {
        new SubscribeConfig.Builder().setSubscribeCount(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeConfigBuilderNegativeTtl() {
        new SubscribeConfig.Builder().setTtlSec(-100);
    }

    /**
     * Validate that a bad match style configuration throws an exception.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testSubscribeConfigBuilderBadMatchStyle() {
        new SubscribeConfig.Builder().setMatchStyle(10);
    }

    /*
     * PublishConfig Tests
     */

    @Test
    public void testPublishConfigBuilderDefaults() {
        PublishConfig publishConfig = new PublishConfig.Builder().build();

        collector.checkThat("mServiceName", publishConfig.mServiceName, equalTo(null));
        collector.checkThat("mServiceSpecificInfoLength", publishConfig.mServiceSpecificInfoLength,
                equalTo(0));
        collector.checkThat("mTxFilterLength", publishConfig.mTxFilterLength, equalTo(0));
        collector.checkThat("mRxFilterLength", publishConfig.mRxFilterLength, equalTo(0));
        collector.checkThat("mPublishType", publishConfig.mPublishType,
                equalTo(PublishConfig.PUBLISH_TYPE_UNSOLICITED));
        collector.checkThat("mPublishCount", publishConfig.mPublishCount, equalTo(0));
        collector.checkThat("mTtlSec", publishConfig.mTtlSec, equalTo(0));
        collector.checkThat("mEnableTerminateNotification",
                publishConfig.mEnableTerminateNotification, equalTo(true));
    }

    @Test
    public void testPublishConfigBuilder() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] txFilter = {
                0, 1, 16, 1, 22 };
        final byte[] rxFilter = {
                1, 127, 0, 1, -5, 1, 22 };
        final int publishType = PublishConfig.PUBLISH_TYPE_SOLICITED;
        final int publishCount = 10;
        final int publishTtl = 15;
        final boolean enableTerminateNotification = false;

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo).setTxFilter(txFilter, txFilter.length)
                .setRxFilter(rxFilter, rxFilter.length).setPublishType(publishType)
                .setPublishCount(publishCount).setTtlSec(publishTtl)
                .setEnableTerminateNotification(enableTerminateNotification).build();

        collector.checkThat("mServiceName", serviceName, equalTo(publishConfig.mServiceName));
        collector.checkThat("mServiceSpecificInfo",
                utilAreArraysEqual(serviceSpecificInfo.getBytes(), serviceSpecificInfo.length(),
                        publishConfig.mServiceSpecificInfo,
                        publishConfig.mServiceSpecificInfoLength),
                equalTo(true));
        collector.checkThat("mTxFilter", utilAreArraysEqual(txFilter, txFilter.length,
                publishConfig.mTxFilter, publishConfig.mTxFilterLength), equalTo(true));
        collector.checkThat("mRxFilter", utilAreArraysEqual(rxFilter, rxFilter.length,
                publishConfig.mRxFilter, publishConfig.mRxFilterLength), equalTo(true));
        collector.checkThat("mPublishType", publishType, equalTo(publishConfig.mPublishType));
        collector.checkThat("mPublishCount", publishCount, equalTo(publishConfig.mPublishCount));
        collector.checkThat("mTtlSec", publishTtl, equalTo(publishConfig.mTtlSec));
        collector.checkThat("mEnableTerminateNotification", enableTerminateNotification,
                equalTo(publishConfig.mEnableTerminateNotification));
    }

    @Test
    public void testPublishConfigParcel() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] txFilter = {
                0, 1, 16, 1, 22 };
        final byte[] rxFilter = {
                1, 127, 0, 1, -5, 1, 22 };
        final int publishType = PublishConfig.PUBLISH_TYPE_SOLICITED;
        final int publishCount = 10;
        final int publishTtl = 15;
        final boolean enableTerminateNotification = false;

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo).setTxFilter(txFilter, txFilter.length)
                .setTxFilter(rxFilter, rxFilter.length).setPublishType(publishType)
                .setPublishCount(publishCount).setTtlSec(publishTtl)
                .setEnableTerminateNotification(enableTerminateNotification).build();

        Parcel parcelW = Parcel.obtain();
        publishConfig.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        PublishConfig rereadPublishConfig = PublishConfig.CREATOR.createFromParcel(parcelR);

        assertEquals(publishConfig, rereadPublishConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPublishConfigBuilderBadPublishType() {
        new PublishConfig.Builder().setPublishType(5);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPublishConfigBuilderNegativeCount() {
        new PublishConfig.Builder().setPublishCount(-4);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPublishConfigBuilderNegativeTtl() {
        new PublishConfig.Builder().setTtlSec(-10);
    }

    /*
     * Utilities
     */

    private static boolean utilAreArraysEqual(byte[] x, int xLength, byte[] y, int yLength) {
        if (xLength != yLength) {
            return false;
        }

        if (x != null && y != null) {
            for (int i = 0; i < xLength; ++i) {
                if (x[i] != y[i]) {
                    return false;
                }
            }
        } else if (xLength != 0) {
            return false; // invalid != invalid
        }

        return true;
    }
}
