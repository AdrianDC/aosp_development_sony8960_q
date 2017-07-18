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

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import com.android.internal.notification.SystemNotificationChannels;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Takes care of handling the "open wi-fi network available" notification
 * @hide
 */
public class WifiNotificationController {
    /**
     * The icon to show in the 'available networks' notification. This will also
     * be the ID of the Notification given to the NotificationManager.
     */
    private static final int ICON_NETWORKS_AVAILABLE =
            com.android.internal.R.drawable.stat_notify_wifi_in_range;
    /**
     * When a notification is shown, we wait this amount before possibly showing it again.
     */
    private final long NOTIFICATION_REPEAT_DELAY_MS;

    /** Whether the user has set the setting to show the 'available networks' notification. */
    private boolean mSettingEnabled;

    /**
     * Observes the user setting to keep {@link #mSettingEnabled} in sync.
     */
    private NotificationEnabledSettingObserver mNotificationEnabledSettingObserver;

    /**
     * The {@link System#currentTimeMillis()} must be at least this value for us
     * to show the notification again.
     */
    private long mNotificationRepeatTime;
    /**
     * The Notification object given to the NotificationManager.
     */
    private Notification.Builder mNotificationBuilder;
    /**
     * Whether the notification is being shown, as set by us. That is, if the
     * user cancels the notification, we will not receive the callback so this
     * will still be true. We only guarantee if this is false, then the
     * notification is not showing.
     */
    private boolean mNotificationShown;
    /** Whether the screen is on or not. */
    private boolean mScreenOn;

    private final Context mContext;
    private FrameworkFacade mFrameworkFacade;

    WifiNotificationController(Context context,
                               Looper looper,
                               FrameworkFacade framework,
                               Notification.Builder builder) {
        mContext = context;
        mFrameworkFacade = framework;
        mNotificationBuilder = builder;

        mScreenOn = false;

        // Setting is in seconds
        NOTIFICATION_REPEAT_DELAY_MS = mFrameworkFacade.getIntegerSetting(context,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY, 900) * 1000L;
        mNotificationEnabledSettingObserver = new NotificationEnabledSettingObserver(
                new Handler(looper));
        mNotificationEnabledSettingObserver.register();
    }

    /**
     * Clears the pending notification. This is called by {@link WifiConnectivityManager} on stop.
     *
     * @param resetRepeatDelay resets the time delay for repeated notification if true.
     */
    public void clearPendingNotification(boolean resetRepeatDelay) {
        if (resetRepeatDelay) {
            mNotificationRepeatTime = 0;
        }
        setNotificationVisible(false, 0, false, 0);
    }

    private boolean isControllerEnabled() {
        return mSettingEnabled && !UserManager.get(mContext)
                .hasUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, UserHandle.CURRENT);
    }

    /**
     * If there are open networks, attempt to post an open network notification.
     *
     * @param availableNetworks Available networks from
     * {@link WifiNetworkSelector.NetworkEvaluator#getFilteredScanDetailsForOpenUnsavedNetworks()}.
     */
    public void handleScanResults(@NonNull List<ScanDetail> availableNetworks) {
        if (!isControllerEnabled()) {
            clearPendingNotification(true /* resetRepeatDelay */);
            return;
        }
        if (availableNetworks.isEmpty()) {
            clearPendingNotification(false /* resetRepeatDelay */);
            return;
        }

        // Do not show or update the notification if screen is off. We want to avoid a race that
        // could occur between a user picking a network in settings and a network candidate picked
        // through network selection, which will happen because screen on triggers a new
        // connectivity scan.
        if (mNotificationShown || !mScreenOn) {
            return;
        }

        setNotificationVisible(true, availableNetworks.size(), false, 0);
    }

    /** Handles screen state changes. */
    public void handleScreenStateChanged(boolean screenOn) {
        mScreenOn = screenOn;
    }

    /**
     * Display or don't display a notification that there are open Wi-Fi networks.
     * @param visible {@code true} if notification should be visible, {@code false} otherwise
     * @param numNetworks the number networks seen
     * @param force {@code true} to force notification to be shown/not-shown,
     * even if it is already shown/not-shown.
     * @param delay time in milliseconds after which the notification should be made
     * visible or invisible.
     */
    private void setNotificationVisible(boolean visible, int numNetworks, boolean force,
            int delay) {

        // Since we use auto cancel on the notification, when the
        // mNetworksAvailableNotificationShown is true, the notification may
        // have actually been canceled.  However, when it is false we know
        // for sure that it is not being shown (it will not be shown any other
        // place than here)

        // If it should be hidden and it is already hidden, then noop
        if (!visible && !mNotificationShown && !force) {
            return;
        }

        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);

        Message message;
        if (visible) {

            // Not enough time has passed to show the notification again
            if (System.currentTimeMillis() < mNotificationRepeatTime) {
                return;
            }

            if (mNotificationBuilder == null) {
                // Cache the Notification builder object.
                mNotificationBuilder = new Notification.Builder(mContext,
                        SystemNotificationChannels.NETWORK_AVAILABLE)
                        .setWhen(0)
                        .setSmallIcon(ICON_NETWORKS_AVAILABLE)
                        .setAutoCancel(true)
                        .setContentIntent(TaskStackBuilder.create(mContext)
                                .addNextIntentWithParentStack(
                                        new Intent(WifiManager.ACTION_PICK_WIFI_NETWORK))
                                .getPendingIntent(0, 0, null, UserHandle.CURRENT))
                        .setColor(mContext.getResources().getColor(
                                com.android.internal.R.color.system_notification_accent_color));
            }

            CharSequence title = mContext.getResources().getQuantityText(
                    com.android.internal.R.plurals.wifi_available, numNetworks);
            CharSequence details = mContext.getResources().getQuantityText(
                    com.android.internal.R.plurals.wifi_available_detailed, numNetworks);
            mNotificationBuilder.setTicker(title);
            mNotificationBuilder.setContentTitle(title);
            mNotificationBuilder.setContentText(details);

            mNotificationRepeatTime = System.currentTimeMillis() + NOTIFICATION_REPEAT_DELAY_MS;

            notificationManager.notifyAsUser(null, ICON_NETWORKS_AVAILABLE,
                    mNotificationBuilder.build(), UserHandle.ALL);
        } else {
            notificationManager.cancelAsUser(null, ICON_NETWORKS_AVAILABLE, UserHandle.ALL);
        }

        mNotificationShown = visible;
    }

    /** Dump ONA controller state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WifiNotificationController: ");
        pw.println("mSettingEnabled " + mSettingEnabled);
        pw.println("mNotificationRepeatTime " + mNotificationRepeatTime);
        pw.println("mNotificationShown " + mNotificationShown);
    }

    private class NotificationEnabledSettingObserver extends ContentObserver {
        NotificationEnabledSettingObserver(Handler handler) {
            super(handler);
        }

        public void register() {
            mFrameworkFacade.registerContentObserver(mContext, Settings.Global.getUriFor(
                    Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON), true, this);
            mSettingEnabled = getValue();
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            mSettingEnabled = getValue();
            clearPendingNotification(true /* resetRepeatDelay */);
        }

        private boolean getValue() {
            return mFrameworkFacade.getIntegerSetting(mContext,
                    Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 1) == 1;
        }
    }
}
