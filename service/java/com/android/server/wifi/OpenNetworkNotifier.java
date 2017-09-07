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
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.wifi.ScanResult;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Set;

/**
 * Takes care of handling the "open wi-fi network available" notification
 *
 * NOTE: These API's are not thread safe and should only be used from WifiStateMachine thread.
 * @hide
 */
public class OpenNetworkNotifier {

    private static final String TAG = "OpenNetworkNotifier";

    static final String ACTION_USER_DISMISSED_NOTIFICATION =
            "com.android.server.wifi.OpenNetworkNotifier.USER_DISMISSED_NOTIFICATION";
    static final String ACTION_USER_TAPPED_CONTENT =
            "com.android.server.wifi.OpenNetworkNotifier.USER_TAPPED_CONTENT";

    /** Identifier of the {@link SsidSetStoreData}. */
    private static final String STORE_DATA_IDENTIFIER = "OpenNetworkNotifierBlacklist";
    /**
     * The {@link Clock#getWallClockMillis()} must be at least this value for us
     * to show the notification again.
     */
    private long mNotificationRepeatTime;
    /**
     * When a notification is shown, we wait this amount before possibly showing it again.
     */
    private final long mNotificationRepeatDelay;
    /** Default repeat delay in seconds. */
    @VisibleForTesting
    static final int DEFAULT_REPEAT_DELAY_SEC = 900;

    /** Whether the user has set the setting to show the 'available networks' notification. */
    private boolean mSettingEnabled;
    /** Whether the notification is being shown. */
    private boolean mNotificationShown;
    /** Whether the screen is on or not. */
    private boolean mScreenOn;

    /** List of SSIDs blacklisted from recommendation. */
    private final Set<String> mBlacklistedSsids;

    private final Context mContext;
    private final Handler mHandler;
    private final FrameworkFacade mFrameworkFacade;
    private final Clock mClock;
    private final WifiConfigManager mConfigManager;
    private final OpenNetworkRecommender mOpenNetworkRecommender;
    private final OpenNetworkNotificationBuilder mOpenNetworkNotificationBuilder;

    private ScanResult mRecommendedNetwork;

    OpenNetworkNotifier(
            Context context,
            Looper looper,
            FrameworkFacade framework,
            Clock clock,
            WifiConfigManager wifiConfigManager,
            WifiConfigStore wifiConfigStore,
            OpenNetworkRecommender openNetworkRecommender) {
        mContext = context;
        mHandler = new Handler(looper);
        mFrameworkFacade = framework;
        mClock = clock;
        mConfigManager = wifiConfigManager;
        mOpenNetworkRecommender = openNetworkRecommender;
        mOpenNetworkNotificationBuilder = new OpenNetworkNotificationBuilder(context, framework);
        mScreenOn = false;

        mBlacklistedSsids = new ArraySet<>();
        wifiConfigStore.registerStoreData(new SsidSetStoreData(
                STORE_DATA_IDENTIFIER, new OpenNetworkNotifierStoreData()));

        // Setting is in seconds
        mNotificationRepeatDelay = mFrameworkFacade.getIntegerSetting(context,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY,
                DEFAULT_REPEAT_DELAY_SEC) * 1000L;
        NotificationEnabledSettingObserver settingObserver = new NotificationEnabledSettingObserver(
                mHandler);
        settingObserver.register();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USER_DISMISSED_NOTIFICATION);
        filter.addAction(ACTION_USER_TAPPED_CONTENT);
        mContext.registerReceiver(
                mBroadcastReceiver, filter, null /* broadcastPermission */, mHandler);
    }

    private final BroadcastReceiver mBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (ACTION_USER_TAPPED_CONTENT.equals(intent.getAction())) {
                        handleUserClickedContentAction();
                    } else if (ACTION_USER_DISMISSED_NOTIFICATION.equals(intent.getAction())) {
                        handleUserDismissedAction();
                    }
                }
            };

    /**
     * Clears the pending notification. This is called by {@link WifiConnectivityManager} on stop.
     *
     * @param resetRepeatDelay resets the time delay for repeated notification if true.
     */
    public void clearPendingNotification(boolean resetRepeatDelay) {
        if (resetRepeatDelay) {
            mNotificationRepeatTime = 0;
        }

        if (mNotificationShown) {
            getNotificationManager().cancel(SystemMessage.NOTE_NETWORK_AVAILABLE);
            mRecommendedNetwork = null;
            mNotificationShown = false;
        }
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

        mRecommendedNetwork = mOpenNetworkRecommender.recommendNetwork(
                availableNetworks, new ArraySet<>(mBlacklistedSsids));

        postNotification(availableNetworks.size());
    }

    /** Handles screen state changes. */
    public void handleScreenStateChanged(boolean screenOn) {
        mScreenOn = screenOn;
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private void postNotification(int numNetworks) {
        // Not enough time has passed to show the notification again
        if (mClock.getWallClockMillis() < mNotificationRepeatTime) {
            return;
        }

        getNotificationManager().notify(
                SystemMessage.NOTE_NETWORK_AVAILABLE,
                mOpenNetworkNotificationBuilder.createOpenNetworkAvailableNotification(
                        numNetworks));
        mNotificationShown = true;
        mNotificationRepeatTime = mClock.getWallClockMillis() + mNotificationRepeatDelay;
    }

    /** Opens Wi-Fi picker. */
    private void handleUserClickedContentAction() {
        mNotificationShown = false;
        mContext.startActivity(
                new Intent(Settings.ACTION_WIFI_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void handleUserDismissedAction() {
        if (mRecommendedNetwork != null) {
            // blacklist dismissed network
            mBlacklistedSsids.add(mRecommendedNetwork.SSID);
            mConfigManager.saveToStore(false /* forceWrite */);
            Log.d(TAG, "Network is added to the open network notification blacklist: "
                    + mRecommendedNetwork.SSID);
        }
        mNotificationShown = false;
    }

    /** Dump ONA controller state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("OpenNetworkNotifier: ");
        pw.println("mSettingEnabled " + mSettingEnabled);
        pw.println("currentTime: " + mClock.getWallClockMillis());
        pw.println("mNotificationRepeatTime: " + mNotificationRepeatTime);
        pw.println("mNotificationShown: " + mNotificationShown);
        pw.println("mBlacklistedSsids: " + mBlacklistedSsids.toString());
    }

    private class OpenNetworkNotifierStoreData implements SsidSetStoreData.DataSource {
        @Override
        public Set<String> getSsids() {
            return new ArraySet<>(mBlacklistedSsids);
        }

        @Override
        public void setSsids(Set<String> ssidList) {
            mBlacklistedSsids.addAll(ssidList);
        }
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
