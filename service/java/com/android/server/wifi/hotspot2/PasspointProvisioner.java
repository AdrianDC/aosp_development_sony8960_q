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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.util.Log;

/**
 * Provides methods to carry out provisioning flow
 */
public class PasspointProvisioner {
    private static final String TAG = "PasspointProvisioner";

    private final Context mContext;
    private WifiManager mWifiManager;

    private int mCallingUid;

    PasspointProvisioner(Context context) {
        mContext = context;
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
    }

    /**
     * Initializes and registers for broadcast intents to get notifications about changes
     * in Wifi or Network state.
     */
    public void init() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                            NetworkInfo networkInfo =
                                    intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                            Log.v(TAG, "Network state change %" + networkInfo.toString());
                        } else if (intent.getAction().equals(
                                WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                            int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                    WifiManager.WIFI_STATE_UNKNOWN);
                            Log.v(TAG, "Wifi state is " + state);
                        }
                    }
                }, intentFilter);
    }

    /**
     * Start provisioning flow with a given provider.
     * @param callingUid calling uid.
     * @param provider {@link OsuProvider} to provision with.
     * @param callback {@link IProvisioningCallback} to provide provisioning status.
     * @return boolean value, true if provisioning was started, false otherwise.
     *
     * Implements HS2.0 provisioning flow with a given HS2.0 provider.
     */
    public boolean startSubscriptionProvisioning(int callingUid, OsuProvider provider,
            IProvisioningCallback callback) {
        if (!mWifiManager.isWifiEnabled()) {
            Log.e(TAG, "Cannot provision when Wifi is not enabled");
            return false;
        }
        mCallingUid = callingUid;
        Log.v(TAG, "Provisioning started with " + provider.toString());
        return true;
    }
}
