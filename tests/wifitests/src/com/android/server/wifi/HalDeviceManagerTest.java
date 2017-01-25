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

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.hardware.wifi.V1_0.IWifi;
import android.hardware.wifi.V1_0.IWifiEventCallback;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.IHwBinder;
import android.os.test.TestLooper;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Unit test harness for HalDeviceManagerTest.
 */
public class HalDeviceManagerTest {
    private HalDeviceManager mDut;
    @Mock IServiceManager mServiceManagerMock;
    @Mock IWifi mWifiMock;
    @Mock HalDeviceManager.ManagerStatusCallback mManagerStatusCallbackMock;
    TestLooper mTestLooper;
    private ArgumentCaptor<IHwBinder.DeathRecipient> mDeathRecipientCaptor =
            ArgumentCaptor.forClass(IHwBinder.DeathRecipient.class);
    private ArgumentCaptor<IServiceNotification.Stub> mServiceNotificationCaptor =
            ArgumentCaptor.forClass(IServiceNotification.Stub.class);
    private ArgumentCaptor<IWifiEventCallback> mWifiEventCallbackCaptor = ArgumentCaptor.forClass(
            IWifiEventCallback.class);
    private InOrder mInOrder;
    @Rule public ErrorCollector collector = new ErrorCollector();
    private WifiStatus mStatusOk;
    private WifiStatus mStatusFail;

    private class HalDeviceManagerSpy extends HalDeviceManager {
        @Override
        protected IWifi getWifiServiceMockable() {
            return mWifiMock;
        }

        @Override
        protected IServiceManager getServiceManagerMockable() {
            return mServiceManagerMock;
        }
    }

    @Before
    public void before() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestLooper = new TestLooper();

        // initialize dummy status objects
        mStatusOk = new WifiStatus();
        mStatusOk.code = WifiStatusCode.SUCCESS;
        mStatusFail = new WifiStatus();
        mStatusFail.code = WifiStatusCode.ERROR_UNKNOWN;

        when(mServiceManagerMock.linkToDeath(any(IHwBinder.DeathRecipient.class),
                anyLong())).thenReturn(true);
        when(mServiceManagerMock.registerForNotifications(anyString(), anyString(),
                any(IServiceNotification.Stub.class))).thenReturn(true);
        when(mWifiMock.linkToDeath(any(IHwBinder.DeathRecipient.class), anyLong())).thenReturn(
                true);
        when(mWifiMock.registerEventCallback(any(IWifiEventCallback.class))).thenReturn(mStatusOk);
        when(mWifiMock.start()).thenReturn(mStatusOk);
        when(mWifiMock.stop()).thenReturn(mStatusOk);

        mInOrder = inOrder(mServiceManagerMock, mWifiMock, mManagerStatusCallbackMock);

        mDut = new HalDeviceManagerSpy();
        executeAndValidateInitializationSequence();
    }

    /**
     * Print out the dump of the device manager after each test. Not used in test validation
     * (internal state) - but can help in debugging failed tests.
     */
    @After
    public void after() throws Exception {
        dumpDut("after: ");
    }

    /**
     * Test basic startup flow:
     * - IServiceManager registrations
     * - IWifi registrations
     * - IWifi startup delayed
     * - Start Wi-Fi -> onStart
     * - Stop Wi-Fi -> onStop
     */
    @Test
    public void testStartStopFlow() throws Exception {
        executeAndValidateStartupSequence();

        // act: stop Wi-Fi
        mDut.stop();
        mTestLooper.dispatchAll();

        // verify: onStop called
        mInOrder.verify(mWifiMock).stop();
        mInOrder.verify(mManagerStatusCallbackMock).onStop();

        verifyNoMoreInteractions(mServiceManagerMock, mWifiMock, mManagerStatusCallbackMock);
    }

    /**
     * Validate that multiple callback registrations are called and that duplicate ones are
     * only called once.
     */
    @Test
    public void testMultipleCallbackRegistrations() throws Exception {
        // register another 2 callbacks - one of them twice
        HalDeviceManager.ManagerStatusCallback callback1 = mock(
                HalDeviceManager.ManagerStatusCallback.class);
        HalDeviceManager.ManagerStatusCallback callback2 = mock(
                HalDeviceManager.ManagerStatusCallback.class);
        mDut.registerStatusCallback(callback2, mTestLooper.getLooper());
        mDut.registerStatusCallback(callback1, mTestLooper.getLooper());
        mDut.registerStatusCallback(callback2, mTestLooper.getLooper());

        // startup
        executeAndValidateStartupSequence();

        // verify
        verify(callback1).onStart();
        verify(callback2).onStart();

        verifyNoMoreInteractions(mServiceManagerMock, mWifiMock, mManagerStatusCallbackMock,
                callback1, callback2);
    }

    /**
     * Validate IWifi death listener and registration flow.
     */
    @Test
    public void testWifiDeathAndRegistration() throws Exception {
        executeAndValidateStartupSequence();

        // act: IWifi service death
        mDeathRecipientCaptor.getValue().serviceDied(0);
        mTestLooper.dispatchAll();

        // verify: getting onStop
        mInOrder.verify(mManagerStatusCallbackMock).onStop();

        // act: service startup
        mServiceNotificationCaptor.getValue().onRegistration(IWifi.kInterfaceName, "", false);

        // verify: initialization of IWifi
        mInOrder.verify(mWifiMock).linkToDeath(mDeathRecipientCaptor.capture(), anyLong());
        mInOrder.verify(mWifiMock).registerEventCallback(mWifiEventCallbackCaptor.capture());

        // act: start
        mDut.start();
        mWifiEventCallbackCaptor.getValue().onStart();
        mTestLooper.dispatchAll();

        // verify: service and callback calls
        mInOrder.verify(mWifiMock).start();
        mInOrder.verify(mManagerStatusCallbackMock).onStart();

        verifyNoMoreInteractions(mServiceManagerMock, mWifiMock, mManagerStatusCallbackMock);
    }

    /**
     * Validate IWifi onFailure causes notification
     */
    @Test
    public void testWifiFail() throws Exception {
        executeAndValidateStartupSequence();

        // act: IWifi failure
        mWifiEventCallbackCaptor.getValue().onFailure(mStatusFail);
        mTestLooper.dispatchAll();

        // verify: getting onStop
        mInOrder.verify(mManagerStatusCallbackMock).onStop();

        // act: start again
        mDut.start();
        mWifiEventCallbackCaptor.getValue().onStart();
        mTestLooper.dispatchAll();

        // verify: service and callback calls
        mInOrder.verify(mWifiMock).start();
        mInOrder.verify(mManagerStatusCallbackMock).onStart();

        verifyNoMoreInteractions(mServiceManagerMock, mWifiMock, mManagerStatusCallbackMock);
    }


    // utilities
    private void dumpDut(String prefix) {
        StringWriter sw = new StringWriter();
        mDut.dump(null, new PrintWriter(sw), null);
        Log.e("HalDeviceManager", prefix + sw.toString());
    }

    private void executeAndValidateInitializationSequence() throws Exception {
        // act:
        mDut.initialize();

        // verify: service manager initialization sequence
        mInOrder.verify(mServiceManagerMock).linkToDeath(any(IHwBinder.DeathRecipient.class),
                anyLong());
        mInOrder.verify(mServiceManagerMock).registerForNotifications(eq(IWifi.kInterfaceName),
                eq(""), mServiceNotificationCaptor.capture());

        // act: get the service started (which happens even when service was already up)
        mServiceNotificationCaptor.getValue().onRegistration(IWifi.kInterfaceName, "", true);

        // verify: wifi initialization sequence
        mInOrder.verify(mWifiMock).linkToDeath(mDeathRecipientCaptor.capture(), anyLong());
        mInOrder.verify(mWifiMock).registerEventCallback(mWifiEventCallbackCaptor.capture());
    }

    private void executeAndValidateStartupSequence() throws Exception {
        // act: register listener & start Wi-Fi
        mDut.registerStatusCallback(mManagerStatusCallbackMock, mTestLooper.getLooper());
        mDut.start();

        // verify
        mInOrder.verify(mWifiMock).start();

        // act: trigger onStart callback of IWifiEventCallback
        mWifiEventCallbackCaptor.getValue().onStart();
        mTestLooper.dispatchAll();

        // verify: onStart called on registered listener
        mInOrder.verify(mManagerStatusCallbackMock).onStart();
    }
}
