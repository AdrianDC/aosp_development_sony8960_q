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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanEventCallback;
import android.net.wifi.nan.IWifiNanSessionCallback;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.net.wifi.nan.WifiNanEventCallback;
import android.net.wifi.nan.WifiNanSessionCallback;
import android.os.IBinder;
import android.os.Looper;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.SparseArray;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;

/**
 * Unit test harness for WifiNanStateManager.
 */
@SmallTest
public class WifiNanServiceImplTest {
    private WifiNanServiceImpl mDut;

    @Mock
    private Context mContextMock;
    @Mock
    private PackageManager mPackageManagerMock;
    @Mock
    private WifiNanStateManager mNanStateManagerMock;
    @Mock
    private IBinder mBinderMock;
    @Mock
    IWifiNanEventCallback mCallbackMock;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    /**
     * Initializes mocks.
     */
    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContextMock.getApplicationContext()).thenReturn(mContextMock);
        when(mContextMock.getPackageManager()).thenReturn(mPackageManagerMock);
        when(mPackageManagerMock.hasSystemFeature(PackageManager.FEATURE_WIFI_NAN))
                .thenReturn(true);

        installMockNanStateManager();

        mDut = new WifiNanServiceImpl(mContextMock);
    }

    /**
     * Validate start() function: passes a valid looper.
     */
    @Test
    public void testStart() {
        mDut.start();

        verify(mNanStateManagerMock).start(any(Looper.class));
    }

    /**
     * Validate connect() - returns and uses a client ID.
     */
    @Test
    public void testConnect() {
        doConnect();
    }

    /**
     * Validate disconnect() - correct pass-through args.
     *
     * @throws Exception
     */
    @Test
    public void testDisconnect() throws Exception {
        int clientId = doConnect();

        mDut.disconnect(clientId, mBinderMock);

        verify(mNanStateManagerMock).disconnect(clientId);
        validateInternalStateCleanedUp(clientId);
    }

    /**
     * Validate that security exception thrown when attempting operation using
     * an invalid client ID.
     */
    @Test
    public void testFailOnInvalidClientId() {
        thrown.expect(SecurityException.class);

        mDut.disconnect(-1, mBinderMock);
    }

    /**
     * Validate that security exception thrown when attempting operation using
     * an a client ID which was already cleared-up.
     */
    @Test
    public void testFailOnClearedUpClientId() throws Exception {
        thrown.expect(SecurityException.class);

        int clientId = doConnect();

        mDut.disconnect(clientId, mBinderMock);

        verify(mNanStateManagerMock).disconnect(clientId);
        validateInternalStateCleanedUp(clientId);

        mDut.disconnect(clientId, mBinderMock);
    }

    /**
     * Validates that on binder death we get a disconnect().
     */
    @Test
    public void testBinderDeath() throws Exception {
        ArgumentCaptor<IBinder.DeathRecipient> deathRecipient = ArgumentCaptor
                .forClass(IBinder.DeathRecipient.class);

        int clientId = doConnect();

        verify(mBinderMock).linkToDeath(deathRecipient.capture(), eq(0));
        deathRecipient.getValue().binderDied();
        verify(mNanStateManagerMock).disconnect(clientId);
        validateInternalStateCleanedUp(clientId);
    }

    /**
     * Validates that sequential connect() calls return increasing client IDs.
     */
    @Test
    public void testClientIdIncrementing() {
        int loopCount = 100;

        int prevId = 0;
        for (int i = 0; i < loopCount; ++i) {
            int id = mDut.connect(mBinderMock, mCallbackMock,
                    WifiNanEventCallback.FLAG_LISTEN_IDENTITY_CHANGED);
            if (i != 0) {
                assertTrue("Client ID incrementing", id > prevId);
            }
            prevId = id;
        }
    }

    /**
     * Validate requestConfig() - correct pass-through args.
     */
    @Test
    public void testRequestConfig() {
        int clientId = doConnect();
        ConfigRequest configRequest = new ConfigRequest.Builder().build();

        mDut.requestConfig(clientId, configRequest);

        verify(mNanStateManagerMock).requestConfig(clientId, configRequest);
    }

    /**
     * Validate stopSession() - correct pass-through args.
     */
    @Test
    public void testStopSession() {
        int sessionId = 1024;
        int clientId = doConnect();

        mDut.stopSession(clientId, sessionId);

        verify(mNanStateManagerMock).stopSession(clientId, sessionId);
    }

    /**
     * Validate destroySession() - correct pass-through args.
     */
    @Test
    public void testDestroySession() {
        int sessionId = 1024;
        int clientId = doConnect();

        mDut.destroySession(clientId, sessionId);

        verify(mNanStateManagerMock).destroySession(clientId, sessionId);
    }

    /**
     * Validate createSession() - correct pass-through args.
     */
    @Test
    public void testCreateSession() {
        IWifiNanSessionCallback mockCallback = mock(IWifiNanSessionCallback.class);
        int events = WifiNanSessionCallback.FLAG_LISTEN_MATCH;
        int clientId = doConnect();

        ArgumentCaptor<Integer> sessionId = ArgumentCaptor.forClass(Integer.class);
        int returnedSessionId = mDut.createSession(clientId, mockCallback, events);

        verify(mNanStateManagerMock).createSession(eq(clientId), sessionId.capture(),
                eq(mockCallback), eq(events));
        assertEquals(returnedSessionId, (int) sessionId.getValue());
    }

    /**
     * Validate publish() - correct pass-through args.
     */
    @Test
    public void testPublish() {
        int sessionId = 1024;
        PublishConfig publishConfig = new PublishConfig.Builder().build();
        int clientId = doConnect();

        mDut.publish(clientId, sessionId, publishConfig);

        verify(mNanStateManagerMock).publish(clientId, sessionId, publishConfig);
    }

    /**
     * Validate subscribe() - correct pass-through args.
     */
    @Test
    public void testSubscribe() {
        int sessionId = 2678;
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();
        int clientId = doConnect();

        mDut.subscribe(clientId, sessionId, subscribeConfig);

        verify(mNanStateManagerMock).subscribe(clientId, sessionId, subscribeConfig);
    }

    /**
     * Validate sendMessage() - correct pass-through args.
     */
    @Test
    public void testSendMessage() {
        int sessionId = 2394;
        int peerId = 2032;
        byte[] message = new byte[23];
        int messageId = 2043;
        int clientId = doConnect();

        mDut.sendMessage(clientId, sessionId, peerId, message, message.length, messageId);

        verify(mNanStateManagerMock).sendMessage(clientId, sessionId, peerId, message,
                message.length, messageId);
    }

    /*
     * Tests of internal state of WifiNanServiceImpl: very limited (not usually
     * a good idea). However, these test that the internal state is cleaned-up
     * appropriately. Alternatively would cause issues with memory leaks or
     * information leak between sessions.
     */

    private void validateInternalStateCleanedUp(int clientId) throws Exception {
        Integer uidEntry = getInternalStateUid(clientId);
        assertEquals(null, uidEntry);

        IBinder.DeathRecipient dr = getInternalStateDeathRecipient(clientId);
        assertEquals(null, dr);
    }

    /*
     * Utilities
     */

    private int doConnect() {
        int events = WifiNanEventCallback.FLAG_LISTEN_IDENTITY_CHANGED;

        int returnedClientId = mDut.connect(mBinderMock, mCallbackMock, events);

        ArgumentCaptor<Integer> clientId = ArgumentCaptor.forClass(Integer.class);
        verify(mNanStateManagerMock).connect(clientId.capture(), eq(mCallbackMock), eq(events));
        assertEquals(returnedClientId, (int) clientId.getValue());

        return returnedClientId;
    }

    private void installMockNanStateManager()
            throws Exception {
        Field field = WifiNanStateManager.class.getDeclaredField("sNanStateManagerSingleton");
        field.setAccessible(true);
        field.set(null, mNanStateManagerMock);
    }

    private Integer getInternalStateUid(int clientId) throws Exception {
        Field field = WifiNanServiceImpl.class.getDeclaredField("mUidByClientId");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        SparseArray<Integer> uidByClientId = (SparseArray<Integer>) field.get(mDut);

        return uidByClientId.get(clientId);
    }

    private IBinder.DeathRecipient getInternalStateDeathRecipient(int clientId) throws Exception {
        Field field = WifiNanServiceImpl.class.getDeclaredField("mDeathRecipientsByClientId");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        SparseArray<IBinder.DeathRecipient> deathRecipientsByClientId =
                            (SparseArray<IBinder.DeathRecipient>) field.get(mDut);

        return deathRecipientsByClientId.get(clientId);
    }
}
