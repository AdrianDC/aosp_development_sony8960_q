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

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


/**
 * Unit tests for {@link com.android.server.wifi.WifiVendorHal}.
 */
public class WifiVendorHalTest {

    @Mock WifiVendorHal mWifiVendorHal;

    /**
     * Sets up for unit test
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Cleans up after test
     */
    @After
    public void tearDown() throws Exception {
        mWifiVendorHal = null;
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

}
