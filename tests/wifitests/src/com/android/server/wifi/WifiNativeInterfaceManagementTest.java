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
import static org.junit.Assert.assertNotNull;
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
    @Mock private HostapdHal mHostapdHal;
    @Mock private INetworkManagementService mNwManagementService;
    @Mock private PropertyService mPropertyService;

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
    private InOrder mInOrder;

    private WifiNative mWifiNative;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        // Setup mocks for the positive single interface cases, individual tests can modify the
        // mocks for negative or multi-interface tests.
        when(mWifiVendorHal.initialize(mWifiVendorHalDeathHandlerCaptor.capture()))
            .thenReturn(true);
        when(mWifiVendorHal.isVendorHalSupported()).thenReturn(true);
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

        mInOrder = inOrder(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal, mHostapdHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);

        mWifiNative = new WifiNative(
                IFACE_NAME_0, mWifiVendorHal, mSupplicantStaIfaceHal, mHostapdHal,
                mWificondControl, mNwManagementService, mPropertyService);
        mWifiNative.initialize();
        mWifiNative.registerStatusListener(mStatusListener);

        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).initialize(any());
        mInOrder.verify(mWificondControl).registerDeathHandler(any());
    }

    /**
     * Verifies the setup of a single client interface.
     */
    @Test
    public void testSetupClientInterface() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        assertEquals(IFACE_NAME_0, mWifiNative.getClientInterfaceName());
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
        assertNull(mWifiNative.getClientInterfaceName());
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
        assertEquals(IFACE_NAME_0, mWifiNative.getClientInterfaceName());
        executeAndValidateTeardownClientInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                mIfaceDestroyedListenerCaptor0.getValue(), mNetworkObserverCaptor0.getValue());
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup & teardown of a single softAp interface.
     */
    @Test
    public void testSetupAndTeardownSoftApInterface() throws Exception {
        executeAndValidateSetupSoftApInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        assertNull(mWifiNative.getClientInterfaceName());
        executeAndValidateTeardownSoftApInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                mIfaceDestroyedListenerCaptor0.getValue(), mNetworkObserverCaptor0.getValue());
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup & teardown of a client & softAp interface.
     *
     * Sequence tested:
     * a) Setup client interface.
     * b) Setup softAp interface.
     * c) Teardown client interface.
     * d) Teardown softAp interface.
     */
    @Test
    public void testSetupAndTeardownClientAndSoftApInterface_Seq1() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        executeAndValidateSetupSoftApInterface(
                true, false, IFACE_NAME_1, mIfaceCallback1, mIfaceDestroyedListenerCaptor1,
                mNetworkObserverCaptor1);
        assertEquals(IFACE_NAME_0, mWifiNative.getClientInterfaceName());
        executeAndValidateTeardownClientInterface(false, true, IFACE_NAME_0, mIfaceCallback0,
                mIfaceDestroyedListenerCaptor0.getValue(), mNetworkObserverCaptor0.getValue());
        executeAndValidateTeardownSoftApInterface(false, false, IFACE_NAME_1, mIfaceCallback1,
                mIfaceDestroyedListenerCaptor1.getValue(), mNetworkObserverCaptor1.getValue());
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup & teardown of a client & softAp interface.
     *
     * Sequence tested:
     * a) Setup client interface.
     * b) Setup softAp interface.
     * c) Teardown softAp interface.
     * d) Teardown client interface.
     */
    @Test
    public void testSetupAndTeardownClientAndSoftApInterface_Seq2() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        executeAndValidateSetupSoftApInterface(
                true, false, IFACE_NAME_1, mIfaceCallback1, mIfaceDestroyedListenerCaptor1,
                mNetworkObserverCaptor1);
        assertEquals(IFACE_NAME_0, mWifiNative.getClientInterfaceName());
        executeAndValidateTeardownSoftApInterface(true, false, IFACE_NAME_1, mIfaceCallback1,
                mIfaceDestroyedListenerCaptor1.getValue(), mNetworkObserverCaptor1.getValue());
        executeAndValidateTeardownClientInterface(false, false, IFACE_NAME_0, mIfaceCallback0,
                mIfaceDestroyedListenerCaptor0.getValue(), mNetworkObserverCaptor0.getValue());
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup & teardown of a client & softAp interface.
     *
     * Sequence tested:
     * a) Setup softAp interface.
     * b) Setup client interface.
     * c) Teardown softAp interface.
     * d) Teardown client interface.
     */
    @Test
    public void testSetupAndTeardownClientAndSoftApInterface_Seq3() throws Exception {
        executeAndValidateSetupSoftApInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        executeAndValidateSetupClientInterface(
                false, true, IFACE_NAME_1, mIfaceCallback1, mIfaceDestroyedListenerCaptor1,
                mNetworkObserverCaptor1);
        assertEquals(IFACE_NAME_1, mWifiNative.getClientInterfaceName());
        executeAndValidateTeardownSoftApInterface(true, false, IFACE_NAME_0, mIfaceCallback0,
                mIfaceDestroyedListenerCaptor0.getValue(), mNetworkObserverCaptor0.getValue());
        executeAndValidateTeardownClientInterface(false, false, IFACE_NAME_1, mIfaceCallback1,
                mIfaceDestroyedListenerCaptor1.getValue(), mNetworkObserverCaptor1.getValue());
        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup & teardown of a client & softAp interface.
     *
     * Sequence tested:
     * a) Setup softAp interface.
     * b) Setup client interface.
     * c) Teardown client interface.
     * d) Teardown softAp interface.
     */
    @Test
    public void testSetupAndTeardownClientAndSoftApInterface_Seq4() throws Exception {
        executeAndValidateSetupSoftApInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        executeAndValidateSetupClientInterface(
                false, true, IFACE_NAME_1, mIfaceCallback1, mIfaceDestroyedListenerCaptor1,
                mNetworkObserverCaptor1);
        assertEquals(IFACE_NAME_1, mWifiNative.getClientInterfaceName());
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

        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).createApIface(mIfaceDestroyedListenerCaptor1.capture());
        // Creation of AP interface should trigger the STA interface destroy
        validateOnDestroyedClientInterface(
                false, true, IFACE_NAME_0, mIfaceCallback0, mNetworkObserverCaptor0.getValue());
        // Now continue with rest of AP interface setup.
        mInOrder.verify(mWificondControl).setupInterfaceForSoftApMode(IFACE_NAME_0);
        mInOrder.verify(mNwManagementService).registerObserver(mNetworkObserverCaptor1.capture());

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

        mInOrder.verify(mWificondControl).enableSupplicant();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
        mInOrder.verify(mSupplicantStaIfaceHal).initialize();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
        mInOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).createStaIface(mIfaceDestroyedListenerCaptor1.capture());
        // Creation of STA interface should trigger the AP interface destroy.
        validateOnDestroyedSoftApInterface(
                true, false, IFACE_NAME_0, mIfaceCallback0, mNetworkObserverCaptor0.getValue());
        // Now continue with rest of STA interface setup.
        mInOrder.verify(mWificondControl).setupInterfaceForClientMode(IFACE_NAME_0);
        mInOrder.verify(mSupplicantStaIfaceHal).setupIface(IFACE_NAME_0);
        mInOrder.verify(mNwManagementService).registerObserver(mNetworkObserverCaptor1.capture());
        mInOrder.verify(mNwManagementService).clearInterfaceAddresses(IFACE_NAME_0);
        mInOrder.verify(mNwManagementService).setInterfaceIpv6PrivacyExtensions(IFACE_NAME_0, true);
        mInOrder.verify(mNwManagementService).disableIpv6(IFACE_NAME_0);

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

        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).startVendorHal();

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

        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).startVendorHal();
        mInOrder.verify(mWificondControl).enableSupplicant();

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

        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).startVendorHal();
        mInOrder.verify(mWificondControl).enableSupplicant();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
        mInOrder.verify(mSupplicantStaIfaceHal).initialize();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
        mInOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).createStaIface(any());

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

        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).startVendorHal();
        mInOrder.verify(mWificondControl).enableSupplicant();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
        mInOrder.verify(mSupplicantStaIfaceHal).initialize();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
        mInOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).createStaIface(mIfaceDestroyedListenerCaptor0.capture());
        mInOrder.verify(mWificondControl).setupInterfaceForClientMode(any());
        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).removeStaIface(any());

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

        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).startVendorHal();
        mInOrder.verify(mWificondControl).enableSupplicant();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
        mInOrder.verify(mSupplicantStaIfaceHal).initialize();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
        mInOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).createStaIface(mIfaceDestroyedListenerCaptor0.capture());
        mInOrder.verify(mWificondControl).setupInterfaceForClientMode(any());
        mInOrder.verify(mSupplicantStaIfaceHal).setupIface(any());
        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).removeStaIface(any());

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

        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).startVendorHal();
        mInOrder.verify(mWificondControl).enableSupplicant();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
        mInOrder.verify(mSupplicantStaIfaceHal).initialize();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
        mInOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).createStaIface(mIfaceDestroyedListenerCaptor0.capture());
        mInOrder.verify(mWificondControl).setupInterfaceForClientMode(any());
        mInOrder.verify(mSupplicantStaIfaceHal).setupIface(any());
        mInOrder.verify(mNwManagementService).registerObserver(mNetworkObserverCaptor0.capture());
        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).removeStaIface(any());

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

    /**
     * Verifies that the interface name is null when there are no interfaces setup.
     */
    @Test
    public void testGetClientInterfaceNameWithNoInterfacesSetup() throws Exception {
        assertNull(mWifiNative.getClientInterfaceName());
    }

    /**
     * Verifies that the interface name is null when there are no client interfaces setup.
     */
    @Test
    public void testGetClientInterfaceNameWithNoClientInterfaceSetup() throws Exception {
        executeAndValidateSetupSoftApInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        assertNull(mWifiNative.getClientInterfaceName());
    }

    /**
     * Verifies that the interface name is not null when there is one client interface setup.
     */
    @Test
    public void testGetClientInterfaceNameWithOneClientInterfaceSetup() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        assertEquals(IFACE_NAME_0, mWifiNative.getClientInterfaceName());
    }

    /**
     * Verifies that the interface name is not null when there are more than one client interfaces
     * setup.
     */
    @Test
    public void testGetClientInterfaceNameWithMoreThanOneClientInterfaceSetup() throws Exception {
        executeAndValidateSetupClientInterface(
                false, false, IFACE_NAME_0, mIfaceCallback0, mIfaceDestroyedListenerCaptor0,
                mNetworkObserverCaptor0);
        executeAndValidateSetupClientInterface(
                true, false, IFACE_NAME_1, mIfaceCallback1, mIfaceDestroyedListenerCaptor1,
                mNetworkObserverCaptor1);
        String interfaceName = mWifiNative.getClientInterfaceName();
        assertNotNull(interfaceName);
        assertTrue(interfaceName.equals(IFACE_NAME_0) || interfaceName.equals(IFACE_NAME_1));
    }

    /*
     * Verifies the setup of a client interface and then a SoftAp interface which would
     * destroy the Client interface. This is what would happen on older devices which do not
     * support the vendor HAL.
     */
    @Test
    public void testSetupClientAndSoftApInterfaceCausesClientInterfaceTeardownWithNoVendorHal()
            throws Exception {
        when(mWifiVendorHal.isVendorHalSupported()).thenReturn(false);
        when(mPropertyService.getString(any(), any())).thenReturn(IFACE_NAME_0);

        // First setup a STA interface and verify.
        assertEquals(IFACE_NAME_0, mWifiNative.setupInterfaceForClientMode(mIfaceCallback0));

        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWificondControl).enableSupplicant();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
        mInOrder.verify(mSupplicantStaIfaceHal).initialize();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
        mInOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWificondControl).setupInterfaceForClientMode(IFACE_NAME_0);
        mInOrder.verify(mSupplicantStaIfaceHal).setupIface(IFACE_NAME_0);
        mInOrder.verify(mNwManagementService).registerObserver(mNetworkObserverCaptor0.capture());
        mInOrder.verify(mNwManagementService).clearInterfaceAddresses(IFACE_NAME_0);
        mInOrder.verify(mNwManagementService).setInterfaceIpv6PrivacyExtensions(IFACE_NAME_0, true);
        mInOrder.verify(mNwManagementService).disableIpv6(IFACE_NAME_0);

        // Now setup an AP interface.
        assertEquals(IFACE_NAME_0, mWifiNative.setupInterfaceForSoftApMode(mIfaceCallback1));

        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        // Creation of AP interface should trigger the STA interface destroy
        mInOrder.verify(mNwManagementService).unregisterObserver(
                mNetworkObserverCaptor0.getValue());
        mInOrder.verify(mSupplicantStaIfaceHal).teardownIface(IFACE_NAME_0);
        mInOrder.verify(mWificondControl).tearDownClientInterface(IFACE_NAME_0);
        mInOrder.verify(mSupplicantStaIfaceHal).deregisterDeathHandler();
        mInOrder.verify(mWificondControl).disableSupplicant();
        mInOrder.verify(mIfaceCallback0).onDestroyed(IFACE_NAME_0);
        // Now continue with rest of AP interface setup.
        mInOrder.verify(mWificondControl).setupInterfaceForSoftApMode(IFACE_NAME_0);
        mInOrder.verify(mNwManagementService).registerObserver(mNetworkObserverCaptor1.capture());

        verifyNoMoreInteractions(mWifiVendorHal, mWificondControl, mSupplicantStaIfaceHal,
                mNwManagementService, mIfaceCallback0, mIfaceCallback1);
    }

    /**
     * Verifies the setup of a client interface and then a SoftAp interface which would
     * destroy the Client interface. This is what would happen on older devices which do not
     * support the vendor HAL.
     */
    @Test
    public void testSetupSoftApAndClientInterfaceCausesSoftApInterfaceTeardownWithNoVendorHal()
            throws Exception {
        when(mWifiVendorHal.isVendorHalSupported()).thenReturn(false);
        when(mPropertyService.getString(any(), any())).thenReturn(IFACE_NAME_0);

        // First setup an AP interface and verify.
        assertEquals(IFACE_NAME_0, mWifiNative.setupInterfaceForSoftApMode(mIfaceCallback0));

        mInOrder.verify(mWifiVendorHal, times(2)).isVendorHalSupported();
        mInOrder.verify(mWificondControl).setupInterfaceForSoftApMode(IFACE_NAME_0);
        mInOrder.verify(mNwManagementService).registerObserver(mNetworkObserverCaptor0.capture());

        // Now setup a STA interface.
        assertEquals(IFACE_NAME_0, mWifiNative.setupInterfaceForClientMode(mIfaceCallback1));

        mInOrder.verify(mWificondControl).enableSupplicant();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
        mInOrder.verify(mSupplicantStaIfaceHal).initialize();
        mInOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
        mInOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        // Creation of STA interface should trigger the AP interface destroy.
        mInOrder.verify(mNwManagementService).unregisterObserver(
                mNetworkObserverCaptor0.getValue());
        mInOrder.verify(mWificondControl).stopHostapd(IFACE_NAME_0);
        mInOrder.verify(mWificondControl).tearDownSoftApInterface(IFACE_NAME_0);
        mInOrder.verify(mIfaceCallback0).onDestroyed(IFACE_NAME_0);
        // Now continue with rest of STA interface setup.
        mInOrder.verify(mWificondControl).setupInterfaceForClientMode(IFACE_NAME_0);
        mInOrder.verify(mSupplicantStaIfaceHal).setupIface(IFACE_NAME_0);
        mInOrder.verify(mNwManagementService).registerObserver(mNetworkObserverCaptor1.capture());
        mInOrder.verify(mNwManagementService).clearInterfaceAddresses(IFACE_NAME_0);
        mInOrder.verify(mNwManagementService).setInterfaceIpv6PrivacyExtensions(IFACE_NAME_0, true);
        mInOrder.verify(mNwManagementService).disableIpv6(IFACE_NAME_0);

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
        if (!existingStaIface && !existingApIface) {
            mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
            mInOrder.verify(mWifiVendorHal).startVendorHal();
        }
        if (!existingStaIface) {
            mInOrder.verify(mWificondControl).enableSupplicant();
            mInOrder.verify(mSupplicantStaIfaceHal).isInitializationStarted();
            mInOrder.verify(mSupplicantStaIfaceHal).initialize();
            mInOrder.verify(mSupplicantStaIfaceHal).isInitializationComplete();
            mInOrder.verify(mSupplicantStaIfaceHal).registerDeathHandler(any());
        }
        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).createStaIface(destroyedListenerCaptor.capture());
        mInOrder.verify(mWificondControl).setupInterfaceForClientMode(ifaceName);
        mInOrder.verify(mSupplicantStaIfaceHal).setupIface(ifaceName);
        mInOrder.verify(mNwManagementService).registerObserver(networkObserverCaptor.capture());
        mInOrder.verify(mNwManagementService).clearInterfaceAddresses(ifaceName);
        mInOrder.verify(mNwManagementService).setInterfaceIpv6PrivacyExtensions(ifaceName, true);
        mInOrder.verify(mNwManagementService).disableIpv6(ifaceName);
    }

    private void executeAndValidateTeardownClientInterface(
            boolean anyOtherStaIface, boolean anyOtherApIface,
            String ifaceName, @Mock WifiNative.InterfaceCallback callback,
            InterfaceDestroyedListener destroyedListener,
            BaseNetworkObserver networkObserver) throws Exception {
        mWifiNative.teardownInterface(ifaceName);

        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).removeStaIface(ifaceName);

        // Now trigger the HalDeviceManager destroy callback to initiate the rest of the teardown.
        destroyedListener.onDestroyed(ifaceName);

        validateOnDestroyedClientInterface(
                anyOtherStaIface, anyOtherApIface, ifaceName, callback, networkObserver);
    }

    private void validateOnDestroyedClientInterface(
            boolean anyOtherStaIface, boolean anyOtherApIface,
            String ifaceName, @Mock WifiNative.InterfaceCallback callback,
            BaseNetworkObserver networkObserver) throws Exception {
        mInOrder.verify(mNwManagementService).unregisterObserver(networkObserver);
        mInOrder.verify(mSupplicantStaIfaceHal).teardownIface(ifaceName);
        mInOrder.verify(mWificondControl).tearDownClientInterface(ifaceName);

        if (!anyOtherStaIface) {
            mInOrder.verify(mSupplicantStaIfaceHal).deregisterDeathHandler();
            mInOrder.verify(mWificondControl).disableSupplicant();
        }
        if (!anyOtherStaIface && !anyOtherApIface) {
            mInOrder.verify(mWificondControl).tearDownInterfaces();
            mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
            mInOrder.verify(mWifiVendorHal).stopVendorHal();
        }
        mInOrder.verify(callback).onDestroyed(ifaceName);
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
        if (!existingStaIface && !existingApIface) {
            mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
            mInOrder.verify(mWifiVendorHal).startVendorHal();
        }
        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).createApIface(destroyedListenerCaptor.capture());
        mInOrder.verify(mWificondControl).setupInterfaceForSoftApMode(ifaceName);
        mInOrder.verify(mNwManagementService).registerObserver(networkObserverCaptor.capture());
    }

    private void executeAndValidateTeardownSoftApInterface(
            boolean anyOtherStaIface, boolean anyOtherApIface,
            String ifaceName, @Mock WifiNative.InterfaceCallback callback,
            InterfaceDestroyedListener destroyedListener,
            BaseNetworkObserver networkObserver) throws Exception {
        mWifiNative.teardownInterface(ifaceName);

        mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
        mInOrder.verify(mWifiVendorHal).removeApIface(ifaceName);

        // Now trigger the HalDeviceManager destroy callback to initiate the rest of the teardown.
        destroyedListener.onDestroyed(ifaceName);

        validateOnDestroyedSoftApInterface(
                anyOtherStaIface, anyOtherApIface, ifaceName, callback, networkObserver);
    }

    private void validateOnDestroyedSoftApInterface(
            boolean anyOtherStaIface, boolean anyOtherApIface,
            String ifaceName, @Mock WifiNative.InterfaceCallback callback,
            BaseNetworkObserver networkObserver) throws Exception {
        mInOrder.verify(mNwManagementService).unregisterObserver(networkObserver);
        mInOrder.verify(mHostapdHal).removeAccessPoint(ifaceName);
        mInOrder.verify(mWificondControl).stopHostapd(ifaceName);
        mInOrder.verify(mWificondControl).tearDownSoftApInterface(ifaceName);

        if (!anyOtherStaIface && !anyOtherApIface) {
            mInOrder.verify(mWificondControl).tearDownInterfaces();
            mInOrder.verify(mWifiVendorHal).isVendorHalSupported();
            mInOrder.verify(mWifiVendorHal).stopVendorHal();
        }
        mInOrder.verify(callback).onDestroyed(ifaceName);
    }
}
