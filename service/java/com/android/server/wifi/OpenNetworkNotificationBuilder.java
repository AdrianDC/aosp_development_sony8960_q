/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.server.wifi.OpenNetworkNotifier.ACTION_USER_DISMISSED_NOTIFICATION;
import static com.android.server.wifi.OpenNetworkNotifier.ACTION_USER_TAPPED_CONTENT;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;

import com.android.internal.R;
import com.android.internal.notification.SystemNotificationChannels;

/**
 * Helper to create notifications for {@link OpenNetworkNotifier}.
 */
public class OpenNetworkNotificationBuilder {

    private Context mContext;
    private Resources mResources;
    private FrameworkFacade mFrameworkFacade;

    public OpenNetworkNotificationBuilder(
            Context context,
            FrameworkFacade framework) {
        mContext = context;
        mResources = context.getResources();
        mFrameworkFacade = framework;
    }

    /**
     * Creates the open network available notification that alerts users there are open networks
     * nearby.
     */
    public Notification createOpenNetworkAvailableNotification(int numNetworks) {

        CharSequence title = mResources.getQuantityText(
                com.android.internal.R.plurals.wifi_available, numNetworks);
        CharSequence content = mResources.getQuantityText(
                com.android.internal.R.plurals.wifi_available_detailed, numNetworks);

        PendingIntent contentIntent =
                mFrameworkFacade.getBroadcast(
                        mContext,
                        0,
                        new Intent(ACTION_USER_TAPPED_CONTENT),
                        PendingIntent.FLAG_UPDATE_CURRENT);
        return createNotificationBuilder(title, content)
                .setContentIntent(contentIntent)
                .build();
    }

    private Notification.Builder createNotificationBuilder(
            CharSequence title, CharSequence content) {
        PendingIntent deleteIntent =
                mFrameworkFacade.getBroadcast(
                        mContext,
                        0,
                        new Intent(ACTION_USER_DISMISSED_NOTIFICATION),
                        PendingIntent.FLAG_UPDATE_CURRENT);
        return mFrameworkFacade.makeNotificationBuilder(mContext,
                SystemNotificationChannels.NETWORK_AVAILABLE)
                .setSmallIcon(R.drawable.stat_notify_wifi_in_range)
                .setAutoCancel(true)
                .setTicker(title)
                .setContentTitle(title)
                .setContentText(content)
                .setDeleteIntent(deleteIntent)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setColor(mResources.getColor(R.color.system_notification_accent_color,
                        mContext.getTheme()));
    }
}
