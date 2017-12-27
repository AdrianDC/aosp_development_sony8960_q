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
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

import android.hardware.wifi.hostapd.V1_0.HostapdStatus;
import android.hardware.wifi.hostapd.V1_0.HostapdStatusCode;
import android.hardware.wifi.hostapd.V1_0.IHostapd;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.IHwBinder;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for HostapdHal
 */
public class HostapdHalTest {
    private @Mock IServiceManager mServiceManagerMock;
    private @Mock IHostapd mIHostapdMock;
    private @Mock WifiNative.HostapdDeathEventHandler mHostapdHalDeathHandler;
    HostapdStatus mStatusSuccess;
    HostapdStatus mStatusFailure;
    private HostapdHal mHostapdHal;
    private ArgumentCaptor<IHwBinder.DeathRecipient> mServiceManagerDeathCaptor =
            ArgumentCaptor.forClass(IHwBinder.DeathRecipient.class);
    private ArgumentCaptor<IHwBinder.DeathRecipient> mHostapdDeathCaptor =
            ArgumentCaptor.forClass(IHwBinder.DeathRecipient.class);
    private ArgumentCaptor<IServiceNotification.Stub> mServiceNotificationCaptor =
            ArgumentCaptor.forClass(IServiceNotification.Stub.class);
    private InOrder mInOrder;

    private class HostapdHalSpy extends HostapdHal {
        HostapdHalSpy() {
            super();
        }

        @Override
        protected IServiceManager getServiceManagerMockable() throws RemoteException {
            return mServiceManagerMock;
        }

        @Override
        protected IHostapd getHostapdMockable() throws RemoteException {
            return mIHostapdMock;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mStatusSuccess = createHostapdStatus(HostapdStatusCode.SUCCESS);
        mStatusFailure = createHostapdStatus(HostapdStatusCode.FAILURE_UNKNOWN);

        when(mServiceManagerMock.linkToDeath(any(IHwBinder.DeathRecipient.class),
                anyLong())).thenReturn(true);
        when(mServiceManagerMock.registerForNotifications(anyString(), anyString(),
                any(IServiceNotification.Stub.class))).thenReturn(true);
        when(mIHostapdMock.linkToDeath(any(IHwBinder.DeathRecipient.class),
                anyLong())).thenReturn(true);
        when(mIHostapdMock.linkToDeath(any(IHwBinder.DeathRecipient.class),
                anyLong())).thenReturn(true);
        mHostapdHal = new HostapdHalSpy();
    }

    /**
     * Sunny day scenario for HostapdHal initialization
     * Asserts successful initialization
     */
    @Test
    public void testInitialize_success() throws Exception {
        executeAndValidateInitializationSequence(false, false);
    }

    /**
     * Failure scenario for HostapdHal initialization
     */
    @Test
    public void testInitialize_registerException() throws Exception {
        executeAndValidateInitializationSequence(true, false);
    }

    /**
     * Failure scenario for HostapdHal initialization
     */
    @Test
    public void testInitialize_registerFailure() throws Exception {
        executeAndValidateInitializationSequence(false, true);
    }

    /**
     * Verifies the hostapd death handling.
     */
    @Test
    public void testDeathHandling() throws Exception {
        executeAndValidateInitializationSequence();

        mHostapdHal.registerDeathHandler(mHostapdHalDeathHandler);
        mHostapdDeathCaptor.getValue().serviceDied(0);
        verify(mHostapdHalDeathHandler).onDeath();
    }

    private void executeAndValidateInitializationSequence() throws Exception {
        executeAndValidateInitializationSequence(false, false);
    }

    /**
     * Calls.initialize(), mocking various callback answers and verifying flow, asserting for the
     * expected result. Verifies if IHostapd manager is initialized or reset.
     */
    private void executeAndValidateInitializationSequence(
            boolean causeRegisterRemoteException, boolean causeRegisterFailure) throws Exception {
        boolean shouldSucceed = !causeRegisterRemoteException && !causeRegisterFailure;
        mInOrder = inOrder(mServiceManagerMock, mIHostapdMock);
        if (causeRegisterFailure) {
            when(mServiceManagerMock.registerForNotifications(anyString(), anyString(),
                    any(IServiceNotification.Stub.class))).thenReturn(false);
        } else if (causeRegisterRemoteException) {
            doThrow(new RemoteException()).when(mServiceManagerMock)
                    .registerForNotifications(
                            anyString(), anyString(), any(IServiceNotification.Stub.class));
        }
        // Initialize HostapdHal, should call serviceManager.registerForNotifications
        assertEquals(shouldSucceed, mHostapdHal.initialize());
        // verify: service manager initialization sequence
        mInOrder.verify(mServiceManagerMock).linkToDeath(mServiceManagerDeathCaptor.capture(),
                anyLong());
        mInOrder.verify(mServiceManagerMock).registerForNotifications(
                eq(IHostapd.kInterfaceName), eq(""), mServiceNotificationCaptor.capture());
        if (shouldSucceed) {
            // act: cause the onRegistration(...) callback to execute
            mServiceNotificationCaptor.getValue().onRegistration(IHostapd.kInterfaceName, "", true);
            assertTrue(mHostapdHal.isInitializationComplete());
            mInOrder.verify(mIHostapdMock).linkToDeath(mHostapdDeathCaptor.capture(), anyLong());
        } else {
            assertFalse(mHostapdHal.isInitializationComplete());
            mInOrder.verify(mIHostapdMock, never()).linkToDeath(
                    mHostapdDeathCaptor.capture(), anyLong());
        }
    }

    private HostapdStatus createHostapdStatus(int code) {
        HostapdStatus status = new HostapdStatus();
        status.code = code;
        return status;
    }
}

