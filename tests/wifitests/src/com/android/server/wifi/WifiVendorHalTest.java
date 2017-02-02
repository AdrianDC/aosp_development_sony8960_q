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

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.hardware.wifi.V1_0.IWifiApIface;
import android.hardware.wifi.V1_0.IWifiChip;
import android.hardware.wifi.V1_0.IWifiIface;
import android.hardware.wifi.V1_0.IWifiRttController;
import android.hardware.wifi.V1_0.IWifiStaIface;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.os.HandlerThread;
import android.os.Looper;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


/**
 * Unit tests for {@link com.android.server.wifi.WifiVendorHal}.
 */
public class WifiVendorHalTest {

    WifiVendorHal mWifiVendorHal;
    @Mock private HalDeviceManager mHalDeviceManager;
    @Mock private HandlerThread mWifiStateMachineHandlerThread;
    @Mock private WifiVendorHal.HalDeviceManagerStatusListener mHalDeviceManagerStatusCallbacks;
    @Mock private IWifiApIface mIWifiApIface;
    @Mock private IWifiChip mIWifiChip;
    @Mock private IWifiStaIface mIWifiStaIface;
    @Mock private IWifiRttController mIWifiRttController;

    /**
     * Sets up for unit test
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Setup the HalDeviceManager mock's start/stop behaviour. This can be overridden in
        // individual tests, if needed.
        doAnswer(new AnswerWithArguments() {
            public boolean answer() {
                when(mHalDeviceManager.isReady()).thenReturn(true);
                when(mHalDeviceManager.isStarted()).thenReturn(true);
                mHalDeviceManagerStatusCallbacks.onStatusChanged();
                return true;
            }
        }).when(mHalDeviceManager).start();

        doAnswer(new AnswerWithArguments() {
            public void answer() {
                when(mHalDeviceManager.isReady()).thenReturn(true);
                when(mHalDeviceManager.isStarted()).thenReturn(false);
                mHalDeviceManagerStatusCallbacks.onStatusChanged();
            }
        }).when(mHalDeviceManager).stop();
        when(mHalDeviceManager.createStaIface(eq(null), eq(null)))
                .thenReturn(mIWifiStaIface);
        when(mHalDeviceManager.createApIface(eq(null), eq(null)))
                .thenReturn(mIWifiApIface);
        when(mHalDeviceManager.getChip(any(IWifiIface.class)))
                .thenReturn(mIWifiChip);
        when(mHalDeviceManager.createRttController(any(IWifiIface.class)))
                .thenReturn(mIWifiRttController);

        // Create the vendor HAL object under test.
        mWifiVendorHal = new WifiVendorHal(mHalDeviceManager, mWifiStateMachineHandlerThread);

        // Initialize the vendor HAL to capture the registered callback.
        mWifiVendorHal.initialize();
        ArgumentCaptor<WifiVendorHal.HalDeviceManagerStatusListener> callbackCaptor =
                ArgumentCaptor.forClass(WifiVendorHal.HalDeviceManagerStatusListener.class);
        verify(mHalDeviceManager).registerStatusListener(
                callbackCaptor.capture(), any(Looper.class));
        mHalDeviceManagerStatusCallbacks = callbackCaptor.getValue();
    }

    /**
     * Test that parsing a typical colon-delimited MAC adddress works
     */
    @Test
    public void testTypicalHexParse() throws Exception {
        byte[] sixBytes = new byte[6];
        mWifiVendorHal.parseUnquotedMacStrToByteArray("61:52:43:34:25:16", sixBytes);
        Assert.assertArrayEquals(new byte[]{0x61, 0x52, 0x43, 0x34, 0x25, 0x16}, sixBytes);
    }

    /**
     * Tests the successful starting of HAL in STA mode using
     * {@link WifiVendorHal#startVendorHal(boolean)}.
     */
    @Test
    public void testStartHalSuccessInStaMode() {
        assertTrue(mWifiVendorHal.startVendorHal(true));
        assertTrue(mWifiVendorHal.isHalStarted());

        verify(mHalDeviceManager).start();
        verify(mHalDeviceManager).createStaIface(eq(null), eq(null));
        verify(mHalDeviceManager).getChip(eq(mIWifiStaIface));
        verify(mHalDeviceManager).createRttController(eq(mIWifiStaIface));
        verify(mHalDeviceManager).isReady();
        verify(mHalDeviceManager).isStarted();

        verify(mHalDeviceManager, never()).createApIface(eq(null), eq(null));
    }

    /**
     * Tests the successful starting of HAL in AP mode using
     * {@link WifiVendorHal#startVendorHal(boolean)}.
     */
    @Test
    public void testStartHalSuccessInApMode() {
        assertTrue(mWifiVendorHal.startVendorHal(false));
        assertTrue(mWifiVendorHal.isHalStarted());

        verify(mHalDeviceManager).start();
        verify(mHalDeviceManager).createApIface(eq(null), eq(null));
        verify(mHalDeviceManager).getChip(eq(mIWifiApIface));
        verify(mHalDeviceManager).isReady();
        verify(mHalDeviceManager).isStarted();

        verify(mHalDeviceManager, never()).createStaIface(eq(null), eq(null));
        verify(mHalDeviceManager, never()).createRttController(any(IWifiIface.class));
    }

    /**
     * Tests the failure to start HAL in STA mode using
     * {@link WifiVendorHal#startVendorHal(boolean)}.
     */
    @Test
    public void testStartHalFailureInStaMode() {
        // No callbacks are invoked in this case since the start itself failed. So, override
        // default AnswerWithArguments that we setup.
        doAnswer(new AnswerWithArguments() {
            public boolean answer() {
                return false;
            }
        }).when(mHalDeviceManager).start();
        assertFalse(mWifiVendorHal.startVendorHal(true));
        assertFalse(mWifiVendorHal.isHalStarted());

        verify(mHalDeviceManager).start();

        verify(mHalDeviceManager, never()).createStaIface(eq(null), eq(null));
        verify(mHalDeviceManager, never()).createApIface(eq(null), eq(null));
        verify(mHalDeviceManager, never()).getChip(any(IWifiIface.class));
        verify(mHalDeviceManager, never()).createRttController(any(IWifiIface.class));
    }

    /**
     * Tests the failure to start HAL in STA mode using
     * {@link WifiVendorHal#startVendorHal(boolean)}.
     */
    @Test
    public void testStartHalFailureInIfaceCreationInStaMode() {
        when(mHalDeviceManager.createStaIface(eq(null), eq(null))).thenReturn(null);
        assertFalse(mWifiVendorHal.startVendorHal(true));
        assertFalse(mWifiVendorHal.isHalStarted());

        verify(mHalDeviceManager).start();
        verify(mHalDeviceManager).createStaIface(eq(null), eq(null));
        verify(mHalDeviceManager).stop();

        verify(mHalDeviceManager, never()).createApIface(eq(null), eq(null));
        verify(mHalDeviceManager, never()).getChip(any(IWifiIface.class));
        verify(mHalDeviceManager, never()).createRttController(any(IWifiIface.class));
    }

    /**
     * Tests the failure to start HAL in STA mode using
     * {@link WifiVendorHal#startVendorHal(boolean)}.
     */
    @Test
    public void testStartHalFailureInRttControllerCreationInStaMode() {
        when(mHalDeviceManager.createRttController(any(IWifiIface.class))).thenReturn(null);
        assertFalse(mWifiVendorHal.startVendorHal(true));
        assertFalse(mWifiVendorHal.isHalStarted());

        verify(mHalDeviceManager).start();
        verify(mHalDeviceManager).createStaIface(eq(null), eq(null));
        verify(mHalDeviceManager).createRttController(eq(mIWifiStaIface));
        verify(mHalDeviceManager).stop();

        verify(mHalDeviceManager, never()).createApIface(eq(null), eq(null));
        verify(mHalDeviceManager, never()).getChip(any(IWifiIface.class));
    }

    /**
     * Tests the failure to start HAL in STA mode using
     * {@link WifiVendorHal#startVendorHal(boolean)}.
     */
    @Test
    public void testStartHalFailureInChipGetInStaMode() {
        when(mHalDeviceManager.getChip(any(IWifiIface.class))).thenReturn(null);
        assertFalse(mWifiVendorHal.startVendorHal(true));
        assertFalse(mWifiVendorHal.isHalStarted());

        verify(mHalDeviceManager).start();
        verify(mHalDeviceManager).createStaIface(eq(null), eq(null));
        verify(mHalDeviceManager).createRttController(eq(mIWifiStaIface));
        verify(mHalDeviceManager).getChip(any(IWifiIface.class));
        verify(mHalDeviceManager).stop();

        verify(mHalDeviceManager, never()).createApIface(eq(null), eq(null));
    }

    /**
     * Tests the failure to start HAL in STA mode using
     * {@link WifiVendorHal#startVendorHal(boolean)}.
     */
    @Test
    public void testStartHalFailureInApMode() {
        when(mHalDeviceManager.createApIface(eq(null), eq(null))).thenReturn(null);
        assertFalse(mWifiVendorHal.startVendorHal(false));
        assertFalse(mWifiVendorHal.isHalStarted());

        verify(mHalDeviceManager).start();
        verify(mHalDeviceManager).createApIface(eq(null), eq(null));
        verify(mHalDeviceManager).stop();

        verify(mHalDeviceManager, never()).createStaIface(eq(null), eq(null));
        verify(mHalDeviceManager, never()).getChip(any(IWifiIface.class));
        verify(mHalDeviceManager, never()).createRttController(any(IWifiIface.class));
    }

    /**
     * Tests the stopping of HAL in STA mode using
     * {@link WifiVendorHal#stopVendorHal()}.
     */
    @Test
    public void testStopHalInStaMode() {
        assertTrue(mWifiVendorHal.startVendorHal(true));
        assertTrue(mWifiVendorHal.isHalStarted());

        mWifiVendorHal.stopVendorHal();
        assertFalse(mWifiVendorHal.isHalStarted());

        verify(mHalDeviceManager).start();
        verify(mHalDeviceManager).stop();
        verify(mHalDeviceManager).createStaIface(eq(null), eq(null));
        verify(mHalDeviceManager).getChip(eq(mIWifiStaIface));
        verify(mHalDeviceManager).createRttController(eq(mIWifiStaIface));
        verify(mHalDeviceManager, times(2)).isReady();
        verify(mHalDeviceManager, times(2)).isStarted();

        verify(mHalDeviceManager, never()).createApIface(eq(null), eq(null));
    }

    /**
     * Tests the stopping of HAL in AP mode using
     * {@link WifiVendorHal#stopVendorHal()}.
     */
    @Test
    public void testStopHalInApMode() {
        assertTrue(mWifiVendorHal.startVendorHal(false));
        assertTrue(mWifiVendorHal.isHalStarted());

        mWifiVendorHal.stopVendorHal();
        assertFalse(mWifiVendorHal.isHalStarted());

        verify(mHalDeviceManager).start();
        verify(mHalDeviceManager).stop();
        verify(mHalDeviceManager).createApIface(eq(null), eq(null));
        verify(mHalDeviceManager).getChip(eq(mIWifiApIface));
        verify(mHalDeviceManager, times(2)).isReady();
        verify(mHalDeviceManager, times(2)).isStarted();

        verify(mHalDeviceManager, never()).createStaIface(eq(null), eq(null));
        verify(mHalDeviceManager, never()).createRttController(any(IWifiIface.class));
    }
}
