/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static org.mockito.Mockito.*;

import android.content.Context;
import android.net.wifi.WifiScanner;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

/**
 * Unit tests for {@link com.android.server.wifi.ScanRequestProxy}.
 */
@SmallTest
public class ScanRequestProxyTest {
    private static final int TEST_UID = 5;

    @Mock private Context mContext;
    @Mock private WifiInjector mWifiInjector;
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private WifiScanner mWifiScanner;

    private ScanRequestProxy mScanRequestProxy;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mWifiInjector.getWifiScanner()).thenReturn(mWifiScanner);
        when(mWifiConfigManager.retrieveHiddenNetworkList()).thenReturn(new ArrayList());
        mScanRequestProxy = new ScanRequestProxy(mContext, mWifiInjector, mWifiConfigManager);
    }

    @After
    public void cleanUp() throws Exception {
        validateMockitoUsage();
    }

    /**
     * Verify scan request will be rejected if we cannot get a handle to wifiscanner.
     */
    @Test
    public void testStartScanFailWithoutScanner() {
        when(mWifiInjector.getWifiScanner()).thenReturn(null);
        assertFalse(mScanRequestProxy.startScan(TEST_UID));
    }

    /**
     * Verify scan request will forwarded to wifiscanner if wifiscanner is present.
     */
    @Test
    public void testStartScanSuccessWithScanner() {
        assertTrue(mScanRequestProxy.startScan(TEST_UID));
    }

    /**
     * Verify that hidden network list is not retrieved when hidden network scanning is disabled.
     */
    @Test
    public void testStartScanWithHiddenNetworkScanningDisabled() {
        mScanRequestProxy.enableScanningForHiddenNetworks(false);
        assertTrue(mScanRequestProxy.startScan(TEST_UID));
        verify(mWifiConfigManager, never()).retrieveHiddenNetworkList();
    }

    /**
     * Verify that hidden network list is retrieved when hidden network scanning is enabled.
     */
    @Test
    public void testStartScanWithHiddenNetworkScanningEnabled() {
        mScanRequestProxy.enableScanningForHiddenNetworks(true);
        assertTrue(mScanRequestProxy.startScan(TEST_UID));
        verify(mWifiConfigManager).retrieveHiddenNetworkList();
    }
}
