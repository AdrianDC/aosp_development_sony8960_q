/*
 * Copyright 2017 The Android Open Source Project
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.test.TestLooper;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

/**
 * Unit tests for {@link WakeupController}.
 */
public class WakeupControllerTest {

    @Mock private Context mContext;
    @Mock private WakeupLock mWakeupLock;
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private WifiConfigStore mWifiConfigStore;
    @Mock private FrameworkFacade mFrameworkFacade;

    private TestLooper mLooper;
    private WakeupController mWakeupController;

    /** Initialize objects before each test run. */
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
    }

    private WakeupController newWakeupController() {
        return new WakeupController(mContext, mLooper.getLooper(), mWakeupLock, mWifiConfigManager,
                mWifiConfigStore, mFrameworkFacade);
    }

    /**
     * Verify WakeupController is enabled when the settings toggle is true.
     */
    @Test
    public void verifyEnabledWhenToggledOn() {
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_WAKEUP_ENABLED, 0)).thenReturn(1);
        mWakeupController = newWakeupController();

        assertTrue(mWakeupController.isEnabled());
    }

    /**
     * Verify WakeupController is disabled when the settings toggle is false.
     */
    @Test
    public void verifyDisabledWhenToggledOff() {
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_WAKEUP_ENABLED, 0)).thenReturn(0);
        mWakeupController = newWakeupController();

        assertFalse(mWakeupController.isEnabled());
    }

    /**
     * Verify WakeupController registers its store data with the WifiConfigStore on construction.
     */
    @Test
    public void registersWakeupConfigStoreData() {
        mWakeupController = newWakeupController();
        verify(mWifiConfigStore).registerStoreData(any(WakeupConfigStoreData.class));
    }

    /**
     * Verify that dump calls also dump the state of the WakeupLock.
     */
    @Test
    public void dumpIncludesWakeupLock() {
        mWakeupController = newWakeupController();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        mWakeupController.dump(null, writer, null);

        verify(mWakeupLock).dump(null, writer, null);
    }
}
