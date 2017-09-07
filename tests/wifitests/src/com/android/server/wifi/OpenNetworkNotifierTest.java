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

import static com.android.server.wifi.OpenNetworkNotifier.DEFAULT_REPEAT_DELAY_SEC;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.wifi.ScanResult;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.util.ArraySet;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Unit tests for {@link OpenNetworkNotifier}.
 */
public class OpenNetworkNotifierTest {

    private static final String TEST_SSID_1 = "Test SSID 1";
    private static final int MIN_RSSI_LEVEL = -127;

    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private Clock mClock;
    @Mock private WifiConfigStore mWifiConfigStore;
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Notification.Builder mNotificationBuilder;
    @Mock private NotificationManager mNotificationManager;
    @Mock private WifiStateMachine mWifiStateMachine;
    @Mock private OpenNetworkRecommender mOpenNetworkRecommender;
    @Mock private UserManager mUserManager;
    private OpenNetworkNotifier mNotificationController;
    private BroadcastReceiver mBroadcastReceiver;
    private ScanResult mDummyNetwork;
    private List<ScanDetail> mOpenNetworks;
    private Set<String> mBlacklistedSsids;


    /** Initialize objects before each test run. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .thenReturn(mNotificationManager);
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 1)).thenReturn(1);
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY, DEFAULT_REPEAT_DELAY_SEC))
                .thenReturn(DEFAULT_REPEAT_DELAY_SEC);
        when(mFrameworkFacade.makeNotificationBuilder(any(), anyString()))
                .thenReturn(mNotificationBuilder);
        when(mContext.getSystemService(Context.USER_SERVICE))
                .thenReturn(mUserManager);
        when(mContext.getResources()).thenReturn(mResources);
        mDummyNetwork = new ScanResult();
        mDummyNetwork.SSID = TEST_SSID_1;
        mDummyNetwork.capabilities = "[ESS]";
        mDummyNetwork.level = MIN_RSSI_LEVEL;
        when(mOpenNetworkRecommender.recommendNetwork(any(), any())).thenReturn(mDummyNetwork);
        mOpenNetworks = new ArrayList<>();
        mOpenNetworks.add(new ScanDetail(mDummyNetwork, null /* networkDetail */));
        mBlacklistedSsids = new ArraySet<>();

        TestLooper mock_looper = new TestLooper();
        mNotificationController = new OpenNetworkNotifier(
                mContext, mock_looper.getLooper(), mFrameworkFacade, mClock, mWifiConfigManager,
                mWifiConfigStore, mWifiStateMachine, mOpenNetworkRecommender);
        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext).registerReceiver(broadcastReceiverCaptor.capture(), any(), any(), any());
        mBroadcastReceiver = broadcastReceiverCaptor.getValue();
        mNotificationController.handleScreenStateChanged(true);
    }

    /**
     * When scan results with open networks are handled, a notification is posted.
     */
    @Test
    public void handleScanResults_hasOpenNetworks_notificationDisplayed() {
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager).notify(anyInt(), any());
    }

    /**
     * When scan results with no open networks are handled, a notification is not posted.
     */
    @Test
    public void handleScanResults_emptyList_notificationNotDisplayed() {
        mNotificationController.handleScanResults(new ArrayList<>());

        verify(mOpenNetworkRecommender, never()).recommendNetwork(any(), any());
        verify(mNotificationManager, never()).notify(anyInt(), any());
    }

    /**
     * When a notification is showing and scan results with no open networks are handled, the
     * notification is cleared.
     */
    @Test
    public void handleScanResults_notificationShown_emptyList_notificationCleared() {
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager).notify(anyInt(), any());

        mNotificationController.handleScanResults(new ArrayList<>());

        verify(mNotificationManager).cancel(anyInt());
    }
    /**
     * When a notification is showing, screen is off, and scan results with no open networks are
     * handled, the notification is cleared.
     */
    @Test
    public void handleScanResults_notificationShown_screenOff_emptyList_notificationCleared() {
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager).notify(anyInt(), any());

        mNotificationController.handleScreenStateChanged(false);
        mNotificationController.handleScanResults(new ArrayList<>());

        verify(mNotificationManager).cancel(anyInt());
    }

    /**
     * If notification is showing, do not post another notification.
     */
    @Test
    public void handleScanResults_notificationShowing_doesNotRepostNotification() {
        mNotificationController.handleScanResults(mOpenNetworks);
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager).notify(anyInt(), any());
    }

    /**
     * When {@link OpenNetworkNotifier#clearPendingNotification(boolean)} is called and a
     * notification is shown, clear the notification.
     */
    @Test
    public void clearPendingNotification_clearsNotificationIfOneIsShowing() {
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager).notify(anyInt(), any());

        mNotificationController.clearPendingNotification(true);

        verify(mNotificationManager).cancel(anyInt());
    }

    /**
     * When {@link OpenNetworkNotifier#clearPendingNotification(boolean)} is called and a
     * notification was not previously shown, do not clear the notification.
     */
    @Test
    public void clearPendingNotification_doesNotClearNotificationIfNoneShowing() {
        mNotificationController.clearPendingNotification(true);

        verify(mNotificationManager, never()).cancel(anyInt());
    }

    /**
     * When screen is off and notification is not displayed, notification is not posted on handling
     * new scan results with open networks.
     */
    @Test
    public void screenOff_handleScanResults_notificationNotDisplayed() {
        mNotificationController.handleScreenStateChanged(false);
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender, never()).recommendNetwork(any(), any());
        verify(mNotificationManager, never()).notify(anyInt(), any());
    }

    /**
     * When a notification is posted and cleared without reseting delay, the next scan with open
     * networks should not post another notification.
     */
    @Test
    public void postNotification_clearNotificationWithoutDelayReset_shouldNotPostNotification() {
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager).notify(anyInt(), any());

        mNotificationController.clearPendingNotification(false);

        mNotificationController.handleScanResults(mOpenNetworks);

        // Recommendation made twice but no new notification posted.
        verify(mOpenNetworkRecommender, times(2)).recommendNetwork(
                mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager).notify(anyInt(), any());
        verify(mNotificationManager).cancel(anyInt());
    }

    /**
     * When a notification is posted and cleared without reseting delay, the next scan with open
     * networks should post a notification.
     */
    @Test
    public void postNotification_clearNotificationWithDelayReset_shouldPostNotification() {
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager).notify(anyInt(), any());

        mNotificationController.clearPendingNotification(true);

        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender, times(2)).recommendNetwork(
                mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager, times(2)).notify(anyInt(), any());
    }

    /**
     * When a notification is tapped, open Wi-Fi settings.
     */
    @Test
    public void notificationTap_opensWifiSettings() {
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager).notify(anyInt(), any());

        mBroadcastReceiver.onReceive(
                mContext, new Intent(OpenNetworkNotifier.ACTION_USER_TAPPED_CONTENT));

        verify(mContext).startActivity(any());
    }

    /**
     * When user dismissed notification and there is a recommended network, network ssid should be
     * blacklisted.
     */
    @Test
    public void userDismissedNotification_shouldBlacklistNetwork() {
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager).notify(anyInt(), any());

        mBroadcastReceiver.onReceive(
                mContext, new Intent(OpenNetworkNotifier.ACTION_USER_DISMISSED_NOTIFICATION));

        verify(mWifiConfigManager).saveToStore(false /* forceWrite */);

        mNotificationController.handleScanResults(mOpenNetworks);

        Set<String> expectedBlacklist = new ArraySet<>();
        expectedBlacklist.add(mDummyNetwork.SSID);
        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, expectedBlacklist);
    }

    /**
     * When a notification is posted and cleared without reseting delay, after the delay has passed
     * the next scan with open networks should post a notification.
     */
    @Test
    public void delaySet_delayPassed_shouldPostNotification() {
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager).notify(anyInt(), any());

        mNotificationController.clearPendingNotification(false);

        // twice the delay time passed
        when(mClock.getWallClockMillis()).thenReturn(DEFAULT_REPEAT_DELAY_SEC * 1000L * 2);

        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender, times(2)).recommendNetwork(
                mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager, times(2)).notify(anyInt(), any());
    }

    /** Verifies that {@link UserManager#DISALLOW_CONFIG_WIFI} disables the feature. */
    @Test
    public void userHasDisallowConfigWifiRestriction_notificationNotDisplayed() {
        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, UserHandle.CURRENT))
                .thenReturn(true);

        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender, never()).recommendNetwork(any(), any());
        verify(mNotificationManager, never()).notify(anyInt(), any());
    }

    /** Verifies that {@link UserManager#DISALLOW_CONFIG_WIFI} clears the showing notification. */
    @Test
    public void userHasDisallowConfigWifiRestriction_showingNotificationIsCleared() {
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, mBlacklistedSsids);
        verify(mNotificationManager).notify(anyInt(), any());

        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, UserHandle.CURRENT))
                .thenReturn(true);

        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mNotificationManager).cancel(anyInt());
    }

    /**
     * {@link OpenNetworkNotifier#ACTION_CONNECT_TO_NETWORK} does not connect to any network if
     * there is no current recommendation.
     */
    @Test
    public void actionConnectToNetwork_currentRecommendationIsNull_doesNothing() {
        mBroadcastReceiver.onReceive(mContext,
                new Intent(OpenNetworkNotifier.ACTION_CONNECT_TO_NETWORK));

        verify(mWifiStateMachine, never()).sendMessage(any(Message.class));
    }

    /**
     * {@link OpenNetworkNotifier#ACTION_CONNECT_TO_NETWORK} connects to the currently recommended
     * network if it exists.
     */
    @Test
    public void actionConnectToNetwork_currentRecommendationExists_connectsToNetwork() {
        mNotificationController.handleScanResults(mOpenNetworks);

        verify(mOpenNetworkRecommender).recommendNetwork(mOpenNetworks, mBlacklistedSsids);

        mBroadcastReceiver.onReceive(mContext,
                new Intent(OpenNetworkNotifier.ACTION_CONNECT_TO_NETWORK));

        verify(mWifiStateMachine).sendMessage(any(Message.class));
    }
}
