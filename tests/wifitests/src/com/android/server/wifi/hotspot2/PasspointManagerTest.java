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

package com.android.server.wifi.hotspot2;

import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_BSSID;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_DATA;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_FILE;
import static android.net.wifi.WifiManager.PASSPOINT_ICON_RECEIVED_ACTION;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.WifiInjector;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.PasspointManager}.
 */
@SmallTest
public class PasspointManagerTest {
    private static final long BSSID = 0x112233445566L;
    private static final String ICON_FILENAME = "test";

    @Mock Context mContext;
    @Mock WifiInjector mWifiInjector;
    @Mock PasspointEventHandler.Callbacks mCallbacks;
    PasspointManager mManager;

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mManager = new PasspointManager(mContext, mWifiInjector);
        ArgumentCaptor<PasspointEventHandler.Callbacks> callbacks =
                ArgumentCaptor.forClass(PasspointEventHandler.Callbacks.class);
        verify(mWifiInjector).makePasspointEventHandler(callbacks.capture());
        mCallbacks = callbacks.getValue();
    }

    /**
     * Verify PASSPOINT_ICON_RECEIVED_ACTION broadcast intent.
     * @param bssid BSSID of the AP
     * @param fileName Name of the icon file
     * @param data icon data byte array
     */
    private void verifyIconIntent(long bssid, String fileName, byte[] data) {
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);
        verify(mContext).sendBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        assertEquals(PASSPOINT_ICON_RECEIVED_ACTION, intent.getValue().getAction());
        assertTrue(intent.getValue().getExtras().containsKey(EXTRA_PASSPOINT_ICON_BSSID));
        assertEquals(bssid, intent.getValue().getExtras().getLong(EXTRA_PASSPOINT_ICON_BSSID));
        assertTrue(intent.getValue().getExtras().containsKey(EXTRA_PASSPOINT_ICON_FILE));
        assertEquals(fileName, intent.getValue().getExtras().getString(EXTRA_PASSPOINT_ICON_FILE));
        if (data != null) {
            assertTrue(intent.getValue().getExtras().containsKey(EXTRA_PASSPOINT_ICON_DATA));
            assertEquals(data,
                         intent.getValue().getExtras().getByteArray(EXTRA_PASSPOINT_ICON_DATA));
        }
    }

    /**
     * Validate the broadcast intent when icon file retrieval succeeded.
     */
    @Test
    public void iconResponseSuccess() {
        byte[] iconData = new byte[] {0x00, 0x11};
        mCallbacks.onIconResponse(BSSID, ICON_FILENAME, iconData);
        verifyIconIntent(BSSID, ICON_FILENAME, iconData);
    }

    /**
     * Validate the broadcast intent when icon file retrieval failed.
     */
    @Test
    public void iconResponseFailure() {
        mCallbacks.onIconResponse(BSSID, ICON_FILENAME, null);
        verifyIconIntent(BSSID, ICON_FILENAME, null);
    }
}
