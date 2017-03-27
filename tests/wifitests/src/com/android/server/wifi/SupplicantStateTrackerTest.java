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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.Handler;
import android.os.Message;
import android.os.test.TestLooper;

import com.android.internal.app.IBatteryStats;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
/**
 * Unit tests for {@link android.net.wifi.SupplicantStateTracker}.
 */
public class SupplicantStateTrackerTest {

    private static final String TAG = "SupplicantStateTrackerTest";
    private static final String   sSSID = "\"GoogleGuest\"";
    private static final WifiSsid sWifiSsid = WifiSsid.createFromAsciiEncoded(sSSID);
    private static final String   sBSSID = "01:02:03:04:05:06";

    private @Mock WifiConfigManager mWcm;
    private @Mock Context mContext;
    private Handler mHandler;
    private SupplicantStateTracker mSupplicantStateTracker;
    private TestLooper mLooper;
    private FrameworkFacade mFacade;
    private BroadcastReceiver mWifiBroadcastReceiver;

    private FrameworkFacade getFrameworkFacade() {
        FrameworkFacade facade = mock(FrameworkFacade.class);
        IBatteryStats batteryStatsService = mock(IBatteryStats.class);
        when(facade.getBatteryService()).thenReturn(batteryStatsService);
        return facade;
    }

    private Message getSupplicantStateChangeMessage(int networkId, WifiSsid wifiSsid,
            String bssid, SupplicantState newSupplicantState) {
        return Message.obtain(null, WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(networkId, wifiSsid, bssid, newSupplicantState));

    }

    @Before
    public void setUp() {
        mLooper = new TestLooper();
        mHandler = new Handler(mLooper.getLooper());
        MockitoAnnotations.initMocks(this);
        mFacade = getFrameworkFacade();
        mSupplicantStateTracker = new SupplicantStateTracker(mContext, mWcm, mFacade, mHandler);
    }

    /**
     * This test verifies that the SupplicantStateTracker sends a broadcast intent upon receiving
     * a message when supplicant state changes
     */
    @Test
    public void testSupplicantStateChangeIntent() {
        mWifiBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                assertTrue(action.equals(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION));
                SupplicantState recvdState =
                        (SupplicantState) intent.getExtra(WifiManager.EXTRA_NEW_STATE, -1);
                assertEquals(SupplicantState.SCANNING, recvdState);
            }
        };
        IntentFilter mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        mContext.registerReceiver(mWifiBroadcastReceiver, mIntentFilter);
        mSupplicantStateTracker.sendMessage(getSupplicantStateChangeMessage(0, sWifiSsid,
                sBSSID, SupplicantState.SCANNING));
    }
}
