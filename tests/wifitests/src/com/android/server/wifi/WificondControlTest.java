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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.wifi.IApInterface;
import android.net.wifi.IClientInterface;
import android.net.wifi.IWificond;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link com.android.server.wifi.WificondControl}.
 */
@SmallTest
public class WificondControlTest {
    private WifiInjector mWifiInjector;
    private WificondControl mWificondControl;

    @Before
    public void setUp() throws Exception {
        mWifiInjector = mock(WifiInjector.class);
        mWificondControl = new WificondControl(mWifiInjector);
    }

    /**
     * Verifies that setupDriverForClientMode() calls Wificond.
     */
    @Test
    public void testSetupDriverForClientMode() throws Exception {
        IWificond wificond = mock(IWificond.class);
        IClientInterface clientInterface = mock(IClientInterface.class);

        when(mWifiInjector.makeWificond()).thenReturn(wificond);
        when(wificond.createClientInterface()).thenReturn(clientInterface);

        IClientInterface returnedClientInterface = mWificondControl.setupDriverForClientMode();
        assertEquals(clientInterface, returnedClientInterface);
        verify(wificond).createClientInterface();
    }

    /**
     * Verifies that setupDriverForClientMode() returns null when wificond is not started.
     */
    @Test
    public void testSetupDriverForClientModeErrorWhenWificondIsNotStarted() throws Exception {
        when(mWifiInjector.makeWificond()).thenReturn(null);

        IClientInterface returnedClientInterface = mWificondControl.setupDriverForClientMode();
        assertEquals(null, returnedClientInterface);
    }

    /**
     * Verifies that setupDriverForClientMode() returns null when wificond failed to setup client
     * interface.
     */
    @Test
    public void testSetupDriverForClientModeErrorWhenWificondFailedToSetupInterface()
            throws Exception {
        IWificond wificond = mock(IWificond.class);

        when(mWifiInjector.makeWificond()).thenReturn(wificond);
        when(wificond.createClientInterface()).thenReturn(null);

        IClientInterface returnedClientInterface = mWificondControl.setupDriverForClientMode();
        assertEquals(null, returnedClientInterface);
    }

    /**
     * Verifies that setupDriverForSoftApMode() calls wificond.
     */
    @Test
    public void testSetupDriverForSoftApMode() throws Exception {
        IWificond wificond = mock(IWificond.class);
        IApInterface apInterface = mock(IApInterface.class);

        when(mWifiInjector.makeWificond()).thenReturn(wificond);
        when(wificond.createApInterface()).thenReturn(apInterface);

        IApInterface returnedApInterface = mWificondControl.setupDriverForSoftApMode();
        assertEquals(apInterface, returnedApInterface);
        verify(wificond).createApInterface();
    }

    /**
     * Verifies that setupDriverForSoftAp() returns null when wificond is not started.
     */
    @Test
    public void testSetupDriverForSoftApModeErrorWhenWificondIsNotStarted() throws Exception {
        when(mWifiInjector.makeWificond()).thenReturn(null);

        IApInterface returnedApInterface = mWificondControl.setupDriverForSoftApMode();

        assertEquals(null, returnedApInterface);
    }

    /**
     * Verifies that setupDriverForSoftApMode() returns null when wificond failed to setup
     * AP interface.
     */
    @Test
    public void testSetupDriverForSoftApModeErrorWhenWificondFailedToSetupInterface()
            throws Exception {
        IWificond wificond = mock(IWificond.class);

        when(mWifiInjector.makeWificond()).thenReturn(wificond);
        when(wificond.createApInterface()).thenReturn(null);

        IApInterface returnedApInterface = mWificondControl.setupDriverForSoftApMode();
        assertEquals(null, returnedApInterface);
    }

    /**
     * Verifies that enableSupplicant() calls wificond.
     */
    @Test
    public void testEnableSupplicant() throws Exception {
        IWificond wificond = mock(IWificond.class);
        IClientInterface clientInterface = mock(IClientInterface.class);

        when(mWifiInjector.makeWificond()).thenReturn(wificond);
        when(wificond.createClientInterface()).thenReturn(clientInterface);
        when(clientInterface.enableSupplicant()).thenReturn(true);

        mWificondControl.setupDriverForClientMode();
        assertTrue(mWificondControl.enableSupplicant());
        verify(clientInterface).enableSupplicant();
    }

    /**
     * Verifies that enableSupplicant() returns false when there is no configured
     * client interface.
     */
    @Test
    public void testEnableSupplicantErrorWhenNoClientInterfaceConfigured() throws Exception {
        IWificond wificond = mock(IWificond.class);
        IClientInterface clientInterface = mock(IClientInterface.class);

        when(mWifiInjector.makeWificond()).thenReturn(wificond);
        when(wificond.createClientInterface()).thenReturn(clientInterface);

        // Configure client interface.
        IClientInterface returnedClientInterface = mWificondControl.setupDriverForClientMode();
        assertEquals(clientInterface, returnedClientInterface);

        // Tear down interfaces.
        assertTrue(mWificondControl.tearDownInterfaces());

        // Enabling supplicant should fail.
        assertFalse(mWificondControl.enableSupplicant());
    }

    /**
     * Verifies that disableSupplicant() calls wificond.
     */
    @Test
    public void testDisableSupplicant() throws Exception {
        IWificond wificond = mock(IWificond.class);
        IClientInterface clientInterface = mock(IClientInterface.class);

        when(mWifiInjector.makeWificond()).thenReturn(wificond);
        when(wificond.createClientInterface()).thenReturn(clientInterface);
        when(clientInterface.disableSupplicant()).thenReturn(true);

        mWificondControl.setupDriverForClientMode();
        assertTrue(mWificondControl.disableSupplicant());
        verify(clientInterface).disableSupplicant();
    }

    /**
     * Verifies that disableSupplicant() returns false when there is no configured
     * client interface.
     */
    @Test
    public void testDisableSupplicantErrorWhenNoClientInterfaceConfigured() throws Exception {
        IWificond wificond = mock(IWificond.class);
        IClientInterface clientInterface = mock(IClientInterface.class);

        when(mWifiInjector.makeWificond()).thenReturn(wificond);
        when(wificond.createClientInterface()).thenReturn(clientInterface);

        // Configure client interface.
        IClientInterface returnedClientInterface = mWificondControl.setupDriverForClientMode();
        assertEquals(clientInterface, returnedClientInterface);

        // Tear down interfaces.
        assertTrue(mWificondControl.tearDownInterfaces());

        // Disabling supplicant should fail.
        assertFalse(mWificondControl.disableSupplicant());
    }

    /**
     * Verifies that tearDownInterfaces() calls wificond.
     */
    @Test
    public void testTearDownInterfaces() throws Exception {
        IWificond wificond = mock(IWificond.class);

        when(mWifiInjector.makeWificond()).thenReturn(wificond);

        assertTrue(mWificondControl.tearDownInterfaces());
        verify(wificond).tearDownInterfaces();
    }

    /**
     * Verifies that tearDownInterfaces() returns false when wificond is not started.
     */
    @Test
    public void testTearDownInterfacesErrorWhenWificondIsNotStarterd() throws Exception {
        when(mWifiInjector.makeWificond()).thenReturn(null);

        assertFalse(mWificondControl.tearDownInterfaces());
    }

}
