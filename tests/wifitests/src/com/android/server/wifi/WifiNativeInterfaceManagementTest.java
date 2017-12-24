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
 * limitations under the License
 */

package com.android.server.wifi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import android.app.test.MockAnswerUtil;
import android.net.InterfaceConfiguration;
import android.net.wifi.IApInterface;
import android.net.wifi.IClientInterface;
import android.os.INetworkManagementService;
import android.os.RemoteException;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.net.BaseNetworkObserver;
import com.android.server.wifi.HalDeviceManager.InterfaceDestroyedListener;
import com.android.server.wifi.WifiNative.SupplicantDeathEventHandler;
import com.android.server.wifi.WifiNative.VendorHalDeathEventHandler;
import com.android.server.wifi.WifiNative.WificondDeathEventHandler;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for the interface management operations in
 * {@link com.android.server.wifi.WifiNative}.
 */
@SmallTest
public class WifiNativeInterfaceManagementTest {
    private static final String IFACE_NAME_0 = "mockWlan0";
    private static final String IFACE_NAME_1 = "mockWlan1";

    @Mock private WifiVendorHal mWifiVendorHal;
    @Mock private WificondControl mWificondControl;
    @Mock private SupplicantStaIfaceHal mSupplicantStaIfaceHal;
    @Mock private INetworkManagementService mNwManagementService;

    @Mock private WifiNative.StatusListener mStatusListener;
    @Mock private WifiNative.InterfaceCallback mIfaceCallback0;
    @Mock private WifiNative.InterfaceCallback mIfaceCallback1;

    private ArgumentCaptor<VendorHalDeathEventHandler> mWifiVendorHalDeathHandlerCaptor =
            ArgumentCaptor.forClass(VendorHalDeathEventHandler.class);
    private ArgumentCaptor<WificondDeathEventHandler> mWificondDeathHandlerCaptor =
            ArgumentCaptor.forClass(WificondDeathEventHandler.class);
    private ArgumentCaptor<SupplicantDeathEventHandler> mSupplicantDeathHandlerCaptor =
            ArgumentCaptor.forClass(SupplicantDeathEventHandler.class);
    private ArgumentCaptor<BaseNetworkObserver> mNetworkObserverCaptor0 =
            ArgumentCaptor.forClass(BaseNetworkObserver.class);
    private ArgumentCaptor<BaseNetworkObserver> mNetworkObserverCaptor1 =
            ArgumentCaptor.forClass(BaseNetworkObserver.class);
    private ArgumentCaptor<InterfaceDestroyedListener> mIfaceDestroyedListenerCaptor0 =
            ArgumentCaptor.forClass(InterfaceDestroyedListener.class);
    private ArgumentCaptor<InterfaceDestroyedListener> mIfaceDestroyedListenerCaptor1 =
            ArgumentCaptor.forClass(InterfaceDestroyedListener.class);

    private WifiNative mWifiNative;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        // Setup mocks for the positive single interface cases, individual tests can modify the
        // mocks for negative or multi-interface tests.
        when(mWifiVendorHal.initialize(mWifiVendorHalDeathHandlerCaptor.capture()))
            .thenReturn(true);
        when(mWifiVendorHal.startVendorHal()).thenReturn(true);
        when(mWifiVendorHal.createStaIface(any())).thenReturn(IFACE_NAME_0);
        when(mWifiVendorHal.createApIface(any())).thenReturn(IFACE_NAME_0);
        when(mWifiVendorHal.removeStaIface(any())).thenReturn(true);
        when(mWifiVendorHal.removeApIface(any())).thenReturn(true);

        when(mWificondControl.registerDeathHandler(mWificondDeathHandlerCaptor.capture()))
            .thenReturn(true);
        when(mWificondControl.enableSupplicant()).thenReturn(true);
        when(mWificondControl.disableSupplicant()).thenReturn(true);
        when(mWificondControl.setupInterfaceForClientMode(any()))
            .thenReturn(mock(IClientInterface.class));
        when(mWificondControl.setupInterfaceForSoftApMode(any()))
            .thenReturn(mock(IApInterface.class));
        when(mWificondControl.tearDownClientInterface(any())).thenReturn(true);
        when(mWificondControl.tearDownSoftApInterface(any())).thenReturn(true);
        when(mWificondControl.tearDownInterfaces()).thenReturn(true);

        when(mSupplicantStaIfaceHal.registerDeathHandler(mSupplicantDeathHandlerCaptor.capture()))
            .thenReturn(true);
        when(mSupplicantStaIfaceHal.deregisterDeathHandler()).thenReturn(true);
        when(mSupplicantStaIfaceHal.initialize()).thenReturn(true);
        when(mSupplicantStaIfaceHal.isInitializationStarted()).thenReturn(false);
        when(mSupplicantStaIfaceHal.isInitializationComplete()).thenReturn(true);
        when(mSupplicantStaIfaceHal.setupIface(any())).thenReturn(true);
        when(mSupplicantStaIfaceHal.teardownIface(any())).thenReturn(true);

        mWifiNative = new WifiNative(
                IFACE_NAME_0, mWifiVendorHal, mSupplicantStaIfaceHal, mWificondControl,
                mNwManagementService);
        mWifiNative.initialize();
        mWifiNative.registerStatusListener(mStatusListener);

        verify(mWifiVendorHal).initialize(any());
        verify(mWificondControl).registerDeathHandler(any());
    }

    /**
     * Verifies the setup of a single client interface.
     */
    @Test
    public void testSetupClientInterface() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup of a single softAp interface.
     */
    @Test
    public void testSetupSoftApInterface() throws Exception {
        executeAndValidateSetupSoftApInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup & teardown of a single client interface.
     */
    @Test
    public void testSetupAndTeardownClientInterface() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        executeAndValidateTeardownClientInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                mIfaceDestroyedListenerCaptor0.getValue(), mNetworkObserverCaptor0.getValue());
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup & teardown of a single client interface.
     */
    @Test
    public void testSetupAndTeardownSoftApInterface() throws Exception {
        executeAndValidateSetupSoftApInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        executeAndValidateTeardownSoftApInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                mIfaceDestroyedListenerCaptor0.getValue(), mNetworkObserverCaptor0.getValue());
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup & teardown of a client & softAp interface.
     */
    @Test
    public void testSetupAndTeardownClientAndSoftApInterface_Seq1() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        executeAndValidateSetupSoftApInterface(
                true, false, IFACE_NAME_1, mIfaceCallback1, mIfaceDestroyedListenerCaptor1,
                mNetworkObserverCaptor1);
        executeAndValidateTeardownClientInterface(false, true, IFACE_NAME_0, mIfaceCallback0,
                mIfaceDestroyedListenerCaptor0.getValue(), mNetworkObserverCaptor0.getValue());
        executeAndValidateTeardownSoftApInterface(false, false, IFACE_NAME_1, mIfaceCallback1,
                mIfaceDestroyedListenerCaptor1.getValue(), mNetworkObserverCaptor1.getValue());
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup & teardown of a client & softAp interface.
     */
    @Test
    public void testSetupAndTeardownClientAndSoftApInterface_Seq2() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        executeAndValidateSetupSoftApInterface(
                true, false, IFACE_NAME_1, mIfaceCallback1, mIfaceDestroyedListenerCaptor1,
                mNetworkObserverCaptor1);
        executeAndValidateTeardownSoftApInterface(true, false, IFACE_NAME_1, mIfaceCallback1,
                mIfaceDestroyedListenerCaptor1.getValue(), mNetworkObserverCaptor1.getValue());
        executeAndValidateTeardownClientInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                mIfaceDestroyedListenerCaptor0.getValue(), mNetworkObserverCaptor0.getValue());
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup & teardown of a client & softAp interface.
     */
    @Test
    public void testSetupAndTeardownClientAndSoftApInterface_Seq3() throws Exception {
        executeAndValidateSetupSoftApInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        executeAndValidateSetupClientInterface(
                false, true, IFACE_NAME_1, mIfaceCallback1, mIfaceDestroyedListenerCaptor1,
                mNetworkObserverCaptor1);
        executeAndValidateTeardownSoftApInterface(true, false, IFACE_NAME_0, mIfaceCallback0,
                mIfaceDestroyedListenerCaptor0.getValue(), mNetworkObserverCaptor0.getValue());
        executeAndValidateTeardownClientInterface(false, false, IFACE_NAME_1, mIfaceCallback1,
                mIfaceDestroyedListenerCaptor1.getValue(), mNetworkObserverCaptor1.getValue());
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup & teardown of a client & softAp interface.
     */
    @Test
    public void testSetupAndTeardownClientAndSoftApInterface_Seq4() throws Exception {
        executeAndValidateSetupSoftApInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        executeAndValidateSetupClientInterface(
                false, true, IFACE_NAME_1, mIfaceCallback1, mIfaceDestroyedListenerCaptor1,
                mNetworkObserverCaptor1);
        executeAndValidateTeardownClientInterface(false, true, IFACE_NAME_1, mIfaceCallback1,
                mIfaceDestroyedListenerCaptor1.getValue(), mNetworkObserverCaptor1.getValue());
        executeAndValidateTeardownSoftApInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                mIfaceDestroyedListenerCaptor0.getValue(), mNetworkObserverCaptor0.getValue());
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup of a client interface and then a SoftAp interface which would
     * destroy the Client interface. This is what would happen on older devices which do not
     * support concurrent interfaces.
     */
    @Test
    public void testSetupClientAndSoftApInterfaceCausesClientInterfaceTeardown() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);

        // Trigger the STA interface teardown when AP interface is created.
        // The iface name will remain the same.
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public String answer(InterfaceDestroyedListener destroyedListener) {
                mIfaceDestroyedListenerCaptor0.getValue().onDestroyed(IFACE_NAME_0);
                return IFACE_NAME_0;
            }
        }).when(mWifiVendorHal).createApIface(any());

        assertEquals(IFACE_NAME_0, mWifiNative.setupInterfaceForSoftApMode(mIfaceCallback1));

        validateOnDestroyedClientInterface(
                false, true, IFACE_NAME_0, mIfaceCallback0, mNetworkObserverCaptor0.getValue());
        validateSetupSoftApInterface(
                true, false, IFACE_NAME_0, mIfaceDestroyedListenerCaptor1,
                mNetworkObserverCaptor1);

        // Execute a teardown of the interface to ensure that the new iface removal works.
        executeAndValidateTeardownSoftApInterface(false, false, IFACE_NAME_0, mIfaceCallback1,
                mIfaceDestroyedListenerCaptor1.getValue(), mNetworkObserverCaptor1.getValue());

        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup of a client interface and then a SoftAp interface which would
     * destroy the Client interface. This is what would happen on older devices which do not
     * support concurrent interfaces.
     */
    @Test
    public void testSetupSoftApAndClientInterfaceCausesSoftApInterfaceTeardown() throws Exception {
        executeAndValidateSetupSoftApInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);

        // Trigger the AP interface teardown when STA interface is created.
        // The iface name will remain the same.
        doAnswer(new MockAnswerUtil.AnswerWithArguments() {
            public String answer(InterfaceDestroyedListener destroyedListener) {
                mIfaceDestroyedListenerCaptor0.getValue().onDestroyed(IFACE_NAME_0);
                return IFACE_NAME_0;
            }
        }).when(mWifiVendorHal).createStaIface(any());

        assertEquals(IFACE_NAME_0, mWifiNative.setupInterfaceForClientMode(mIfaceCallback1));

        validateOnDestroyedSoftApInterface(
                true, false, IFACE_NAME_0, mIfaceCallback0, mNetworkObserverCaptor0.getValue());
        validateSetupClientInterface(
                false, true, IFACE_NAME_0, mIfaceDestroyedListenerCaptor1,
                mNetworkObserverCaptor1);

        // Execute a teardown of the interface to ensure that the new iface removal works.
        executeAndValidateTeardownClientInterface(false, false, IFACE_NAME_0, mIfaceCallback1,
                mIfaceDestroyedListenerCaptor1.getValue(), mNetworkObserverCaptor1.getValue());

        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup of a client interface and trigger an interface down event.
     */
    @Test
    public void testSetupClientInterfaceAndTriggerInterfaceDown() throws Exception {
        executeAndValidateSetupSoftApInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);

        mNetworkObserverCaptor0.getValue().interfaceLinkStateChanged(IFACE_NAME_0, false);
        verify(mIfaceCallback0).onDown(IFACE_NAME_0);

        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup of a client interface and trigger an interface up event.
     */
    @Test
    public void testSetupClientInterfaceAndTriggerInterfaceUp() throws Exception {
        executeAndValidateSetupSoftApInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);

        mNetworkObserverCaptor0.getValue().interfaceLinkStateChanged(IFACE_NAME_0, true);
        verify(mIfaceCallback0).onUp(IFACE_NAME_0);

        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup of a client interface and wificond death handling.
     */
    @Test
    public void testSetupClientInterfaceAndWicondDied() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        // Trigger wificond death
        mWificondDeathHandlerCaptor.getValue().onDeath();

        validateOnDestroyedClientInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                mNetworkObserverCaptor0.getValue());

        verify(mStatusListener).onStatusChanged(false);
        verify(mStatusListener).onStatusChanged(true);
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup of a soft ap interface and vendor HAL death handling.
     */
    @Test
    public void testSetupSoftApInterfaceAndVendorHalDied() throws Exception {
        executeAndValidateSetupSoftApInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        // Trigger vendor HAL death
        mWifiVendorHalDeathHandlerCaptor.getValue().onDeath();

        validateOnDestroyedSoftApInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                mNetworkObserverCaptor0.getValue());

        verify(mStatusListener).onStatusChanged(false);
        verify(mStatusListener).onStatusChanged(true);
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup of a client interface and supplicant HAL death handling.
     */
    @Test
    public void testSetupClientInterfaceAndVendorHalDied() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        // Trigger wificond death
        mSupplicantDeathHandlerCaptor.getValue().onDeath();

        validateOnDestroyedClientInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                mNetworkObserverCaptor0.getValue());

        verify(mStatusListener).onStatusChanged(false);
        verify(mStatusListener).onStatusChanged(true);
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies failure handling in setup of a client interface.
     */
    @Test
    public void testSetupClientInterfaceFailureInStartHal() throws Exception {
        when(mWifiVendorHal.startVendorHal()).thenReturn(false);
        assertNull(mWifiNative.setupInterfaceForClientMode(mIfaceCallback0));

        InOrder inOrder = inOrder(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService);
        inOrder.verify(mWifiVendorHal).startVendorHal();

        // To test if the failure is handled cleanly, invoke teardown and ensure that
        // none of the mocks are used because the iface does not exist in the internal
        // database.
        mWifiNative.teardownInterface(IFACE_NAME_0);

        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies failure handling in setup of a client interface.
     */
    @Test
    public void testSetupClientInterfaceFailureInStartSupplicant() throws Exception {
        when(mWificondControl.enableSupplicant()).thenReturn(false);
        assertNull(mWifiNative.setupInterfaceForClientMode(mIfaceCallback0));

        InOrder inOrder = inOrder(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService);
        inOrder.verify(mWifiVendorHal).startVendorHal();
        inOrder.verify(mWificondControl).enableSupplicant();

        // To test if the failure is handled cleanly, invoke teardown and ensure that
        // none of the mocks are used because the iface does not exist in the internal
        // database.
        mWifiNative.teardownInterface(IFACE_NAME_0);

        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies failure handling in setup of a client interface.
     */
    @Test
    public void testSetupClientInterfaceFailureInHalCreateStaIface() throws Exception {
        when(mWifiVendorHal.createStaIface(any())).thenReturn(null);
        assertNull(mWifiNative.setupInterfaceForClientMode(mIfaceCallback0));

        InOrder inOrder = inOrder(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService);
        inOrder.verify(mWifiVendorHal).startVendorHal();
        inOrder.verify(mWificondControl).enableSupplicant();
        inOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
        inOrder.verify(mSupplicantStaIfaceHal).initialize();
        inOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
        inOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        inOrder.verify(mWifiVendorHal).createStaIface(any());

        // To test if the failure is handled cleanly, invoke teardown and ensure that
        // none of the mocks are used because the iface does not exist in the internal
        // database.
        mWifiNative.teardownInterface(IFACE_NAME_0);

        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies failure handling in setup of a client interface.
     */
    @Test
    public void testSetupClientInterfaceFailureInWificondSetupInterfaceForClientMode()
            throws Exception {
        when(mWificondControl.setupInterfaceForClientMode(any())).thenReturn(null);
        assertNull(mWifiNative.setupInterfaceForClientMode(mIfaceCallback0));

        InOrder inOrder = inOrder(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService);
        inOrder.verify(mWifiVendorHal).startVendorHal();
        inOrder.verify(mWificondControl).enableSupplicant();
        inOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
        inOrder.verify(mSupplicantStaIfaceHal).initialize();
        inOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
        inOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        inOrder.verify(mWifiVendorHal).createStaIface(mIfaceDestroyedListenerCaptor0.capture());
        inOrder.verify(mWificondControl).setupInterfaceForClientMode(any());
        inOrder.verify(mWifiVendorHal).removeStaIface(any());

        // Trigger the HAL interface destroyed callback to verify the whole removal sequence.
        mIfaceDestroyedListenerCaptor0.getValue().onDestroyed(IFACE_NAME_0);
        validateOnDestroyedClientInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                null);

        // To test if the failure is handled cleanly, invoke teardown and ensure that
        // none of the mocks are used because the iface does not exist in the internal
        // database.
        mWifiNative.teardownInterface(IFACE_NAME_0);

        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies failure handling in setup of a client interface.
     */
    @Test
    public void testSetupClientInterfaceFailureInSupplicantSetupIface() throws Exception {
        when(mSupplicantStaIfaceHal.setupIface(any())).thenReturn(false);
        assertNull(mWifiNative.setupInterfaceForClientMode(mIfaceCallback0));

        InOrder inOrder = inOrder(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService);
        inOrder.verify(mWifiVendorHal).startVendorHal();
        inOrder.verify(mWificondControl).enableSupplicant();
        inOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
        inOrder.verify(mSupplicantStaIfaceHal).initialize();
        inOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
        inOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        inOrder.verify(mWifiVendorHal).createStaIface(mIfaceDestroyedListenerCaptor0.capture());
        inOrder.verify(mWificondControl).setupInterfaceForClientMode(any());
        inOrder.verify(mSupplicantStaIfaceHal).setupIface(any());
        inOrder.verify(mWifiVendorHal).removeStaIface(any());

        // Trigger the HAL interface destroyed callback to verify the whole removal sequence.
        mIfaceDestroyedListenerCaptor0.getValue().onDestroyed(IFACE_NAME_0);
        validateOnDestroyedClientInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                null);

        // To test if the failure is handled cleanly, invoke teardown and ensure that
        // none of the mocks are used because the iface does not exist in the internal
        // database.
        mWifiNative.teardownInterface(IFACE_NAME_0);

        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies failure handling in setup of a client interface.
     */
    @Test
    public void testSetupClientInterfaceFailureInNetworkObserverRegister() throws Exception {
        doThrow(new RemoteException()).when(mNwManagementService).registerObserver(any());
        assertNull(mWifiNative.setupInterfaceForClientMode(mIfaceCallback0));

        InOrder inOrder = inOrder(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService);
        inOrder.verify(mWifiVendorHal).startVendorHal();
        inOrder.verify(mWificondControl).enableSupplicant();
        inOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
        inOrder.verify(mSupplicantStaIfaceHal).initialize();
        inOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
        inOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        inOrder.verify(mWifiVendorHal).createStaIface(mIfaceDestroyedListenerCaptor0.capture());
        inOrder.verify(mWificondControl).setupInterfaceForClientMode(any());
        inOrder.verify(mSupplicantStaIfaceHal).setupIface(any());
        inOrder.verify(mNwManagementService).registerObserver(mNetworkObserverCaptor0.capture());
        inOrder.verify(mWifiVendorHal).removeStaIface(any());

        // Trigger the HAL interface destroyed callback to verify the whole removal sequence.
        mIfaceDestroyedListenerCaptor0.getValue().onDestroyed(IFACE_NAME_0);
        validateOnDestroyedClientInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                mNetworkObserverCaptor0.getValue());

        // To test if the failure is handled cleanly, invoke teardown and ensure that
        // none of the mocks are used because the iface does not exist in the internal
        // database.
        mWifiNative.teardownInterface(IFACE_NAME_0);

        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the interface state query API.
     */
    @Test
    public void testIsInterfaceUp() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);

        InterfaceConfiguration config = new InterfaceConfiguration();
        when(mNwManagementService.getInterfaceConfig(IFACE_NAME_0)).thenReturn(config);

        config.setInterfaceUp();
        assertTrue(mWifiNative.isInterfaceUp(IFACE_NAME_0));

        config.setInterfaceDown();
        assertFalse(mWifiNative.isInterfaceUp(IFACE_NAME_0));

        when(mNwManagementService.getInterfaceConfig(IFACE_NAME_0)).thenReturn(null);
        assertFalse(mWifiNative.isInterfaceUp(IFACE_NAME_0));

        verify(mNwManagementService, times(3)).getInterfaceConfig(IFACE_NAME_0);

        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    private void executeAndValidateSetupClientInterface(
            boolean existingStaIface, boolean existingApIface,
            String ifaceName, @Mock WifiNative.InterfaceCallback callback,
            ArgumentCaptor<InterfaceDestroyedListener> destroyedListenerCaptor,
            ArgumentCaptor<BaseNetworkObserver> networkObserverCaptor) throws Exception {
        when(mWifiVendorHal.createStaIface(any())).thenReturn(ifaceName);
        assertEquals(ifaceName, mWifiNative.setupInterfaceForClientMode(callback));

        validateSetupClientInterface(
                existingStaIface, existingApIface, ifaceName, destroyedListenerCaptor,
                networkObserverCaptor);
    }

    private void validateSetupClientInterface(
            boolean existingStaIface, boolean existingApIface,
            String ifaceName, ArgumentCaptor<InterfaceDestroyedListener> destroyedListenerCaptor,
            ArgumentCaptor<BaseNetworkObserver> networkObserverCaptor) throws Exception {
        InOrder inOrder = inOrder(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService);

        if (!existingStaIface && !existingApIface) {
            inOrder.verify(mWifiVendorHal).startVendorHal();
        }
        if (!existingStaIface) {
            inOrder.verify(mWificondControl).enableSupplicant();
            inOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
            inOrder.verify(mSupplicantStaIfaceHal).initialize();
            inOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
            inOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        }
        inOrder.verify(mWifiVendorHal).createStaIface(destroyedListenerCaptor.capture());
        inOrder.verify(mWificondControl).setupInterfaceForClientMode(ifaceName);
        inOrder.verify(mSupplicantStaIfaceHal).setupIface(ifaceName);
        inOrder.verify(mNwManagementService).registerObserver(networkObserverCaptor.capture());
    }

    private void executeAndValidateTeardownClientInterface(
            boolean anyOtherStaIface, boolean anyOtherApIface,
            String ifaceName, @Mock WifiNative.InterfaceCallback callback,
            InterfaceDestroyedListener destroyedListener,
            BaseNetworkObserver networkObserver) throws Exception {
        InOrder inOrder = inOrder(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, callback);

        mWifiNative.teardownInterface(ifaceName);

        inOrder.verify(mWifiVendorHal).removeStaIface(ifaceName);

        // Now trigger the HalDeviceManager destroy callback to initiate the rest of the teardown.
        destroyedListener.onDestroyed(ifaceName);

        validateOnDestroyedClientInterface(
                anyOtherStaIface, anyOtherApIface, ifaceName, callback, networkObserver);
    }

    private void validateOnDestroyedClientInterface(
            boolean anyOtherStaIface, boolean anyOtherApIface,
            String ifaceName, @Mock WifiNative.InterfaceCallback callback,
            BaseNetworkObserver networkObserver) throws Exception {
        InOrder inOrder = inOrder(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, callback);

        inOrder.verify(mNwManagementService).unregisterObserver(networkObserver);
        inOrder.verify(mSupplicantStaIfaceHal).teardownIface(ifaceName);
        inOrder.verify(mWificondControl).tearDownClientInterface(ifaceName);

        if (!anyOtherStaIface) {
            inOrder.verify(mSupplicantStaIfaceHal).deregisterDeathHandler();
            inOrder.verify(mWificondControl).disableSupplicant();
        }
        if (!anyOtherStaIface && !anyOtherApIface) {
            inOrder.verify(mWificondControl).tearDownInterfaces();
            inOrder.verify(mWifiVendorHal).stopVendorHal();
        }
        inOrder.verify(callback).onDestroyed(ifaceName);
    }

    private void executeAndValidateSetupSoftApInterface(
            boolean existingStaIface, boolean existingApIface,
            String ifaceName, @Mock WifiNative.InterfaceCallback callback,
            ArgumentCaptor<InterfaceDestroyedListener> destroyedListenerCaptor,
            ArgumentCaptor<BaseNetworkObserver> networkObserverCaptor) throws Exception {
        when(mWifiVendorHal.createApIface(any())).thenReturn(ifaceName);
        assertEquals(ifaceName, mWifiNative.setupInterfaceForSoftApMode(callback));

        validateSetupSoftApInterface(
                existingStaIface, existingApIface, ifaceName, destroyedListenerCaptor,
                networkObserverCaptor);
    }

    private void validateSetupSoftApInterface(
            boolean existingStaIface, boolean existingApIface,
            String ifaceName, ArgumentCaptor<InterfaceDestroyedListener> destroyedListenerCaptor,
            ArgumentCaptor<BaseNetworkObserver> networkObserverCaptor) throws Exception {
        InOrder inOrder = inOrder(mWifiVendorHal, mWificondControl, mNwManagementService);

        if (!existingStaIface && !existingApIface) {
            inOrder.verify(mWifiVendorHal).startVendorHal();
        }
        inOrder.verify(mWifiVendorHal).createApIface(destroyedListenerCaptor.capture());
        inOrder.verify(mWificondControl).setupInterfaceForSoftApMode(ifaceName);
        inOrder.verify(mNwManagementService).registerObserver(networkObserverCaptor.capture());
    }

    private void executeAndValidateTeardownSoftApInterface(
            boolean anyOtherStaIface, boolean anyOtherApIface,
            String ifaceName, @Mock WifiNative.InterfaceCallback callback,
            InterfaceDestroyedListener destroyedListener,
            BaseNetworkObserver networkObserver) throws Exception {
        InOrder inOrder = inOrder(mWifiVendorHal, mWificondControl, mNwManagementService, callback);

        mWifiNative.teardownInterface(ifaceName);

        inOrder.verify(mWifiVendorHal).removeApIface(ifaceName);

        // Now trigger the HalDeviceManager destroy callback to initiate the rest of the teardown.
        destroyedListener.onDestroyed(ifaceName);

        validateOnDestroyedSoftApInterface(
                anyOtherStaIface, anyOtherApIface, ifaceName, callback, networkObserver);
    }

    private void validateOnDestroyedSoftApInterface(
            boolean anyOtherStaIface, boolean anyOtherApIface,
            String ifaceName, @Mock WifiNative.InterfaceCallback callback,
            BaseNetworkObserver networkObserver) throws Exception {
        InOrder inOrder = inOrder(mWifiVendorHal, mWificondControl, mNwManagementService, callback);

        inOrder.verify(mNwManagementService).unregisterObserver(networkObserver);
        inOrder.verify(mWificondControl).stopSoftAp(ifaceName);
        inOrder.verify(mWificondControl).tearDownSoftApInterface(ifaceName);

        if (!anyOtherStaIface && !anyOtherApIface) {
            inOrder.verify(mWificondControl).tearDownInterfaces();
            inOrder.verify(mWifiVendorHal).stopVendorHal();
        }
        inOrder.verify(callback).onDestroyed(ifaceName);
    }
}
