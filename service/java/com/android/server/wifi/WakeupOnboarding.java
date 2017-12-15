/*
 * Copyright 2018 The Android Open Source Project
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

import static com.android.server.wifi.WakeupNotificationFactory.ACTION_DISMISS_NOTIFICATION;
import static com.android.server.wifi.WakeupNotificationFactory.ACTION_OPEN_WIFI_PREFERENCES;
import static com.android.server.wifi.WakeupNotificationFactory.ACTION_TURN_OFF_WIFI_WAKE;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;

/**
 * Manages the WiFi Wake onboarding notification.
 *
 * <p>If a user disables wifi with Wifi Wake enabled, this notification is shown to explain that
 * wifi may turn back on automatically. Wifi will not automatically turn back on until after the
 * user interacts with the onboarding notification in some way (e.g. dismiss, tap).
 */
public class WakeupOnboarding {

    private static final String TAG = "WakeupOnboarding";

    private final Context mContext;
    private final WakeupNotificationFactory mWakeupNotificationFactory;
    private NotificationManager mNotificationManager;
    private final Handler mHandler;
    private final WifiConfigManager mWifiConfigManager;
    private final IntentFilter mIntentFilter;
    private final FrameworkFacade mFrameworkFacade;

    private boolean mIsOnboarded;
    private boolean mIsNotificationShowing;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case ACTION_TURN_OFF_WIFI_WAKE:
                    mFrameworkFacade.setIntegerSetting(mContext,
                            Settings.Global.WIFI_WAKEUP_ENABLED, 0);
                    dismissNotification(true /* shouldOnboard */);
                    break;
                case ACTION_OPEN_WIFI_PREFERENCES:
                    // Close notification drawer before opening preferences.
                    mContext.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
                    mContext.startActivity(new Intent(Settings.ACTION_WIFI_IP_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    dismissNotification(true /* shouldOnboard */);
                    break;
                case ACTION_DISMISS_NOTIFICATION:
                    dismissNotification(true /* shouldOnboard */);
                    break;
                default:
                    Log.e(TAG, "Unknown action " + intent.getAction());
            }
        }
    };

    public WakeupOnboarding(
            Context context,
            WifiConfigManager wifiConfigManager,
            Looper looper,
            FrameworkFacade frameworkFacade,
            WakeupNotificationFactory wakeupNotificationFactory) {
        mContext = context;
        mWifiConfigManager = wifiConfigManager;
        mHandler = new Handler(looper);
        mFrameworkFacade = frameworkFacade;
        mWakeupNotificationFactory = wakeupNotificationFactory;

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(ACTION_TURN_OFF_WIFI_WAKE);
        mIntentFilter.addAction(ACTION_DISMISS_NOTIFICATION);
        mIntentFilter.addAction(ACTION_OPEN_WIFI_PREFERENCES);
    }

    /** Returns whether the user is onboarded. */
    public boolean isOnboarded() {
        return mIsOnboarded;
    }

    /** Shows the onboarding notification if applicable. */
    public void maybeShowNotification() {
        if (isOnboarded() || mIsNotificationShowing) {
            return;
        }

        Log.d(TAG, "Showing onboarding notification.");

        mContext.registerReceiver(mBroadcastReceiver, mIntentFilter,
                null /* broadcastPermission */, mHandler);
        getNotificationManager().notify(SystemMessage.NOTE_WIFI_WAKE_ONBOARD,
                mWakeupNotificationFactory.createOnboardingNotification());
        mIsNotificationShowing = true;
    }

    /** Handles onboarding cleanup on stop. */
    public void onStop() {
        dismissNotification(false /* shouldOnboard */);
    }

    private void dismissNotification(boolean shouldOnboard) {
        if (!mIsNotificationShowing) {
            return;
        }

        if (shouldOnboard) {
            setOnboarded();
        }

        mContext.unregisterReceiver(mBroadcastReceiver);
        getNotificationManager().cancel(SystemMessage.NOTE_WIFI_WAKE_ONBOARD);
        mIsNotificationShowing = false;
    }

    private void setOnboarded() {
        Log.d(TAG, "Setting user as onboarded.");
        mIsOnboarded = true;
        mWifiConfigManager.saveToStore(false /* forceWrite */);
    }

    private NotificationManager getNotificationManager() {
        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager)
                    mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        }
        return mNotificationManager;
    }

    /** Returns the {@link WakeupConfigStoreData.DataSource} for the {@link WifiConfigStore}. */
    public WakeupConfigStoreData.DataSource<Boolean> getDataSource() {
        return new OnboardingDataSource();
    }

    private class OnboardingDataSource implements WakeupConfigStoreData.DataSource<Boolean> {

        @Override
        public Boolean getData() {
            return mIsOnboarded;
        }

        @Override
        public void setData(Boolean data) {
            mIsOnboarded = data;
        }
    }
}
