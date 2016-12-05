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

package com.android.server.wifi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.net.wifi.WifiScanner;
import android.os.test.TestLooper;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Unit tests for {@link com.android.server.wifi.WifiWakeupController}.
 */
public class WifiWakeupControllerTest {
    public static final String TAG = "WifiScanningServiceTest";

    @Mock private Context mContext;
    @Mock private WifiStateMachine mWifiStateMachine;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private NotificationManager mNotificationManager;
    @Mock private WifiScanner mWifiScanner;
    @Mock private ContentResolver mContentResolver;
    private WifiWakeupController mWifiWakeupController;


    /** Initialize objects before each test run. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_WAKEUP_ENABLED, 0)).thenReturn(1);
        TestLooper testLooper = new TestLooper();
        mWifiWakeupController = new WifiWakeupController(
                mContext, testLooper.getLooper(), mFrameworkFacade);
    }

    /** Test WifiWakeupEnabledSettingObserver enables feature correctly. */
    @Test
    public void testEnableWifiWakeup() {
        assertTrue(mWifiWakeupController.mWifiWakeupEnabled);

        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_WAKEUP_ENABLED, 0)).thenReturn(0);
        mWifiWakeupController.mContentObserver.onChange(true);
        assertFalse(mWifiWakeupController.mWifiWakeupEnabled);
    }

    /** Test dump() does not crash. */
    @Test
    public void testDump() {
        StringWriter stringWriter = new StringWriter();
        mWifiWakeupController.dump(
                new FileDescriptor(), new PrintWriter(stringWriter), new String[0]);
    }

}
