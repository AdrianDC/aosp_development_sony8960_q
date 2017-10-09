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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.TestUtil;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.PasspointProvisioner}.
 */
@SmallTest
public class PasspointProvisionerTest {
    private static final String TAG = "PasspointProvisionerTest";

    private static final int TEST_UID = 1500;

    OsuProvider mOsuProvider;
    PasspointProvisioner mPasspointProvisioner;

    @Mock Context mContext;
    @Mock WifiManager mWifiManager;
    @Mock IProvisioningCallback mCallback;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        doReturn(mWifiManager).when(mContext)
                .getSystemService(eq(Context.WIFI_SERVICE));
        mPasspointProvisioner = new PasspointProvisioner(mContext);
        mOsuProvider = PasspointProvisioningTestUtil.generateOsuProvider(true);
    }

    /**
     * Verifies that PasspointProvisioner registers for the two intents required
     * to get Wifi state and connection events using the context provided
     */
    @Test
    public void verifyBroadcastIntentRegistration() {
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        ArgumentCaptor<IntentFilter> intentFilterCaptor =
                ArgumentCaptor.forClass(IntentFilter.class);
        mPasspointProvisioner.init();
        verify(mContext).registerReceiver(
                broadcastReceiverCaptor.capture(), intentFilterCaptor.capture());
        BroadcastReceiver broadcastReceiver = broadcastReceiverCaptor.getValue();
        IntentFilter intentFilter = intentFilterCaptor.getValue();
        assertEquals(intentFilter.countActions(), 2);
        TestUtil.sendWifiStateChanged(broadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendNetworkStateChanged(broadcastReceiver, mContext,
                NetworkInfo.DetailedState.CONNECTED);
        // TODO: Verify that PasspointProvisioner correctly receives these intents
    }

    /**
     * Verifies that startSubscriptionProvisioning() API returns true when valid
     * parameters are passed in
     */
    @Test
    public void verifyStartProvisioning() {
        assertTrue(mPasspointProvisioner.startSubscriptionProvisioning(
                TEST_UID, mOsuProvider, mCallback));
    }
}
