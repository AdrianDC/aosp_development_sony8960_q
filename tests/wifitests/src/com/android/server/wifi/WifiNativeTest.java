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
 * limitations under the License
 */

package com.android.server.wifi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link com.android.server.wifi.WifiNative}.
 */
@SmallTest
public class WifiNativeTest {
    private static final int NETWORK_ID = 0;
    private static final String NETWORK_EXTRAS_VARIABLE = "test";
    private static final Map<String, String> NETWORK_EXTRAS_VALUES = new HashMap<>();
    static {
        NETWORK_EXTRAS_VALUES.put("key1", "value1");
        NETWORK_EXTRAS_VALUES.put("key2", "value2");
    }
    private static final String NETWORK_EXTRAS_SERIALIZED =
            "\"%7B%22key2%22%3A%22value2%22%2C%22key1%22%3A%22value1%22%7D\"";

    private WifiNative mWifiNative;

    @Before
    public void setUp() throws Exception {
        final Constructor<WifiNative> wifiNativeConstructor =
                WifiNative.class.getDeclaredConstructor(String.class, Boolean.TYPE);
        wifiNativeConstructor.setAccessible(true);
        mWifiNative = spy(wifiNativeConstructor.newInstance("test", true));
    }

    /**
     * Verifies that setNetworkExtra() correctly writes a serialized and URL-encoded JSON object.
     */
    @Test
    public void testSetNetworkExtra() {
        when(mWifiNative.setNetworkVariable(anyInt(), anyString(), anyString())).thenReturn(true);
        assertTrue(mWifiNative.setNetworkExtra(NETWORK_ID, NETWORK_EXTRAS_VARIABLE,
                NETWORK_EXTRAS_VALUES));
        verify(mWifiNative).setNetworkVariable(NETWORK_ID, NETWORK_EXTRAS_VARIABLE,
                NETWORK_EXTRAS_SERIALIZED);
    }

    /**
     * Verifies that getNetworkExtra() correctly reads a serialized and URL-encoded JSON object.
     */
    @Test
    public void testGetNetworkExtra() {
        when(mWifiNative.getNetworkVariable(NETWORK_ID, NETWORK_EXTRAS_VARIABLE))
                .thenReturn(NETWORK_EXTRAS_SERIALIZED);
        final Map<String, String> actualValues =
                mWifiNative.getNetworkExtra(NETWORK_ID, NETWORK_EXTRAS_VARIABLE);
        assertEquals(NETWORK_EXTRAS_VALUES, actualValues);
    }
}
