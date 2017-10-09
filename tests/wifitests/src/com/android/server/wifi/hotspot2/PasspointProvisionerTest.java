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

package com.android.server.wifi.hotspot2;

import static org.junit.Assert.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.PasspointProvisioner}.
 */
@SmallTest
public class PasspointProvisionerTest {
    private static final String TAG = "PasspointProvisionerTest";

    private static final int TEST_UID = 1500;

    private PasspointProvisioner mPasspointProvisioner;
    private TestLooper mLooper = new TestLooper();

    @Mock Context mContext;
    @Mock WifiManager mWifiManager;
    @Mock IProvisioningCallback mCallback;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        doReturn(mWifiManager).when(mContext)
                .getSystemService(eq(Context.WIFI_SERVICE));
        OsuNetworkConnection osuNetworkConnection = new OsuNetworkConnection(mContext);
        mPasspointProvisioner = new PasspointProvisioner(mContext, osuNetworkConnection);
    }

    /**
     * Verifies that initialization is required before starting subscription
     * provisioning with a provider
     */
    @Test
    public void verifyInitBeforeStartProvisioning() {
        OsuProvider osuProvider = PasspointProvisioningTestUtil.generateOsuProvider(true);
        mPasspointProvisioner.init(mLooper.getLooper());
        assertTrue(mPasspointProvisioner.startSubscriptionProvisioning(
                TEST_UID, osuProvider, mCallback));
    }
}
