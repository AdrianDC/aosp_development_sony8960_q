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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.RttManager;
import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanEventCallback;
import android.net.wifi.nan.IWifiNanSessionCallback;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.os.IBinder;
import android.os.Looper;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.SparseArray;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;

/**
 * Unit test harness for WifiNanStateManager.
 */
@SmallTest
public class WifiNanServiceImplTest {
    private WifiNanServiceImplSpy mDut;
    private int mDefaultUid = 1500;

    @Mock
    private Context mContextMock;
    @Mock
    private PackageManager mPackageManagerMock;
    @Mock
    private WifiNanStateManager mNanStateManagerMock;
    @Mock
    private IBinder mBinderMock;
    @Mock
    private IWifiNanEventCallback mCallbackMock;
    @Mock
    private IWifiNanSessionCallback mSessionCallbackMock;

    /**
     * Using instead of spy to avoid native crash failures - possibly due to
     * spy's copying of state.
     */
    private class WifiNanServiceImplSpy extends WifiNanServiceImpl {
        public int fakeUid;

        WifiNanServiceImplSpy(Context context) {
            super(context);
        }

        /**
         * Return the fake UID instead of the real one: pseudo-spy
         * implementation.
         */
        @Override
        public int getMockableCallingUid() {
            return fakeUid;
        }
    }

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

        mDut = new WifiNanServiceImplSpy(mContextMock);
        mDut.fakeUid = mDefaultUid;
    }

    /**
     * Validate start() function: passes a valid looper.
     */
    @Test
    public void testStart() {
        mDut.start();

        verify(mNanStateManagerMock).start(eq(mContextMock), any(Looper.class));
    }

    /**
     * Validate enableUsage() function
     */
    @Test
    public void testEnableUsage() {
        mDut.enableUsage();

        verify(mNanStateManagerMock).enableUsage();
    }

    /**
     * Validate disableUsage() function
     */
    @Test
    public void testDisableUsage() throws Exception {
        mDut.enableUsage();
        doConnect();
        mDut.disableUsage();

        verify(mNanStateManagerMock).disableUsage();
    }

    /**
     * Validate isUsageEnabled() function
     */
    @Test
    public void testIsUsageEnabled() {
        mDut.isUsageEnabled();

        verify(mNanStateManagerMock).isUsageEnabled();
    }


    /**
     * Validate connect() - returns and uses a client ID.
     */
    @Test
    public void testConnect() {
        doConnect();
    }

    /**
     * Validate connect() when a non-null config is passed.
     */
    @Test
    public void testConnectWithConfig() {
        ConfigRequest configRequest = new ConfigRequest.Builder().setMasterPreference(55).build();

        int returnedClientId = mDut.connect(mBinderMock, mCallbackMock, configRequest);

        ArgumentCaptor<Integer> clientId = ArgumentCaptor.forClass(Integer.class);
        verify(mNanStateManagerMock).connect(clientId.capture(), anyInt(), eq(mCallbackMock),
                eq(configRequest));
        assertEquals(returnedClientId, (int) clientId.getValue());
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
    @Test(expected = SecurityException.class)
    public void testFailOnInvalidClientId() {
        mDut.disconnect(-1, mBinderMock);
    }

    /**
     * Validate that security exception thrown when attempting operation using a
     * client ID which was already cleared-up.
     */
    @Test(expected = SecurityException.class)
    public void testFailOnClearedUpClientId() throws Exception {
        int clientId = doConnect();

        mDut.disconnect(clientId, mBinderMock);

        verify(mNanStateManagerMock).disconnect(clientId);
        validateInternalStateCleanedUp(clientId);

        mDut.disconnect(clientId, mBinderMock);
    }

    /**
     * Validate that trying to use a client ID from a UID which is different
     * from the one that created it fails - and that the internal state is not
     * modified so that a valid call (from the correct UID) will subsequently
     * succeed.
     */
    @Test
    public void testFailOnAccessClientIdFromWrongUid() throws Exception {
        int clientId = doConnect();

        mDut.fakeUid = mDefaultUid + 1;

        /*
         * Not using thrown.expect(...) since want to test that subsequent
         * access works.
         */
        boolean failsAsExpected = false;
        try {
            mDut.disconnect(clientId, mBinderMock);
        } catch (SecurityException e) {
            failsAsExpected = true;
        }

        mDut.fakeUid = mDefaultUid;

        PublishConfig publishConfig = new PublishConfig.Builder().build();
        mDut.publish(clientId, publishConfig, mSessionCallbackMock);

        verify(mNanStateManagerMock).publish(clientId, publishConfig, mSessionCallbackMock);
        assertTrue("SecurityException for invalid access from wrong UID thrown", failsAsExpected);
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
            int id = mDut.connect(mBinderMock, mCallbackMock, null);
            if (i != 0) {
                assertTrue("Client ID incrementing", id > prevId);
            }
            prevId = id;
        }
    }

    /**
     * Validate terminateSession() - correct pass-through args.
     */
    @Test
    public void testTerminateSession() {
        int sessionId = 1024;
        int clientId = doConnect();

        mDut.terminateSession(clientId, sessionId);

        verify(mNanStateManagerMock).terminateSession(clientId, sessionId);
    }

    /**
     * Validate publish() - correct pass-through args.
     */
    @Test
    public void testPublish() {
        PublishConfig publishConfig = new PublishConfig.Builder().build();
        int clientId = doConnect();
        IWifiNanSessionCallback mockCallback = mock(IWifiNanSessionCallback.class);

        mDut.publish(clientId, publishConfig, mockCallback);

        verify(mNanStateManagerMock).publish(clientId, publishConfig, mockCallback);
    }

    /**
     * Validate updatePublish() - correct pass-through args.
     */
    @Test
    public void testUpdatePublish() {
        int sessionId = 1232;
        PublishConfig publishConfig = new PublishConfig.Builder().build();
        int clientId = doConnect();

        mDut.updatePublish(clientId, sessionId, publishConfig);

        verify(mNanStateManagerMock).updatePublish(clientId, sessionId, publishConfig);
    }

    /**
     * Validate subscribe() - correct pass-through args.
     */
    @Test
    public void testSubscribe() {
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();
        int clientId = doConnect();
        IWifiNanSessionCallback mockCallback = mock(IWifiNanSessionCallback.class);

        mDut.subscribe(clientId, subscribeConfig, mockCallback);

        verify(mNanStateManagerMock).subscribe(clientId, subscribeConfig, mockCallback);
    }

    /**
     * Validate updateSubscribe() - correct pass-through args.
     */
    @Test
    public void testUpdateSubscribe() {
        int sessionId = 1232;
        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().build();
        int clientId = doConnect();

        mDut.updateSubscribe(clientId, sessionId, subscribeConfig);

        verify(mNanStateManagerMock).updateSubscribe(clientId, sessionId, subscribeConfig);
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

        mDut.sendMessage(clientId, sessionId, peerId, message, message.length, messageId, 0);

        verify(mNanStateManagerMock).sendMessage(clientId, sessionId, peerId, message,
                message.length, messageId, 0);
    }

    /**
     * Validate startRanging() - correct pass-through args
     */
    @Test
    public void testStartRanging() {
        int clientId = doConnect();
        int sessionId = 65345;
        RttManager.ParcelableRttParams params =
                new RttManager.ParcelableRttParams(new RttManager.RttParams[1]);

        ArgumentCaptor<RttManager.RttParams[]> paramsCaptor =
                ArgumentCaptor.forClass(RttManager.RttParams[].class);

        int rangingId = mDut.startRanging(clientId, sessionId, params);

        verify(mNanStateManagerMock).startRanging(eq(clientId), eq(sessionId),
                paramsCaptor.capture(), eq(rangingId));

        assertArrayEquals(paramsCaptor.getValue(), params.mParams);
    }

    /**
     * Validates that sequential startRanging() calls return increasing ranging IDs.
     */
    @Test
    public void testRangingIdIncrementing() {
        int loopCount = 100;
        int clientId = doConnect();
        int sessionId = 65345;
        RttManager.ParcelableRttParams params =
                new RttManager.ParcelableRttParams(new RttManager.RttParams[1]);

        int prevRangingId = 0;
        for (int i = 0; i < loopCount; ++i) {
            int rangingId = mDut.startRanging(clientId, sessionId, params);
            if (i != 0) {
                assertTrue("Client ID incrementing", rangingId > prevRangingId);
            }
            prevRangingId = rangingId;
        }
    }

    /**
     * Validates that startRanging() requires a non-empty list
     */
    @Test(expected = IllegalArgumentException.class)
    public void testStartRangingZeroArgs() {
        int clientId = doConnect();
        int sessionId = 65345;
        RttManager.ParcelableRttParams params =
                new RttManager.ParcelableRttParams(new RttManager.RttParams[0]);

        ArgumentCaptor<RttManager.RttParams[]> paramsCaptor =
                ArgumentCaptor.forClass(RttManager.RttParams[].class);

        int rangingId = mDut.startRanging(clientId, sessionId, params);
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
        int returnedClientId = mDut.connect(mBinderMock, mCallbackMock, null);

        ArgumentCaptor<Integer> clientId = ArgumentCaptor.forClass(Integer.class);
        verify(mNanStateManagerMock).connect(clientId.capture(), anyInt(), eq(mCallbackMock),
                eq(new ConfigRequest.Builder().build()));
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
