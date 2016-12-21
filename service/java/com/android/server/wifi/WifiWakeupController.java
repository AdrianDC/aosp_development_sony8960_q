/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Handles enabling Wi-Fi for the Wi-Fi Wakeup feature.
 * @hide
 */
public class WifiWakeupController {

    private Context mContext;
    private final FrameworkFacade mFrameworkFacade;
    private final Handler mHandler;
    @VisibleForTesting
    final ContentObserver mContentObserver;
    @VisibleForTesting
    boolean mWifiWakeupEnabled;

    WifiWakeupController(Context context, Looper looper, FrameworkFacade frameworkFacade) {
        mContext = context;
        mFrameworkFacade = frameworkFacade;

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);

        mHandler = new Handler(looper);

        mContext.registerReceiver(mBroadcastReceiver, filter, null, mHandler);
        mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mWifiWakeupEnabled = getWifiWakeupEnabledSetting();
            }
        };
        ContentResolver cr = mContext.getContentResolver();
        cr.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.WIFI_WAKEUP_ENABLED), true, mContentObserver);
        mWifiWakeupEnabled = getWifiWakeupEnabledSetting();
    }

    private boolean getWifiWakeupEnabledSetting() {
        return mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_WAKEUP_ENABLED, 0) == 1;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                handleWifiStateChanged(intent);
            } else if (intent.getAction().equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                handleScanResultsAvailable(intent);
            }
        }
    };

    private void handleWifiStateChanged(Intent intent) {};
    private void handleScanResultsAvailable(Intent intent) {};

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mWifiWakeupEnabled " + mWifiWakeupEnabled);
    }
}
