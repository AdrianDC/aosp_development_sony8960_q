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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.ScanResult;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.test.TestLooper;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link WifiNotificationController}.
 */
public class WifiNotificationControllerTest {

    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private NotificationManager mNotificationManager;
    @Mock private UserManager mUserManager;
    private WifiNotificationController mNotificationController;


    /** Initialize objects before each test run. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .thenReturn(mNotificationManager);
        when(mFrameworkFacade.getIntegerSetting(mContext,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 1)).thenReturn(1);
        when(mContext.getSystemService(Context.USER_SERVICE))
                .thenReturn(mUserManager);
        when(mContext.getResources()).thenReturn(mResources);

        TestLooper mock_looper = new TestLooper();
        mNotificationController = new WifiNotificationController(
                mContext, mock_looper.getLooper(), mFrameworkFacade,
                mock(Notification.Builder.class));
        mNotificationController.handleScreenStateChanged(true);
    }

    private List<ScanDetail> createOpenScanResults() {
        List<ScanDetail> scanResults = new ArrayList<>();
        ScanResult scanResult = new ScanResult();
        scanResult.capabilities = "[ESS]";
        scanResults.add(new ScanDetail(scanResult, null /* networkDetail */));
        return scanResults;
    }

    /**
     * When scan results with open networks are handled, a notification is posted.
     */
    @Test
    public void handleScanResults_hasOpenNetworks_notificationDisplayed() {
        mNotificationController.handleScanResults(createOpenScanResults());

        verify(mNotificationManager).notifyAsUser(any(), anyInt(), any(), any());
    }

    /**
     * When scan results with no open networks are handled, a notification is not posted.
     */
    @Test
    public void handleScanResults_emptyList_notificationNotDisplayed() {
        mNotificationController.handleScanResults(new ArrayList<>());

        verify(mNotificationManager, never()).notifyAsUser(any(), anyInt(), any(), any());
    }

    /**
     * When a notification is showing and scan results with no open networks are handled, the
     * notification is cleared.
     */
    @Test
    public void handleScanResults_notificationShown_emptyList_notificationCleared() {
        mNotificationController.handleScanResults(createOpenScanResults());

        verify(mNotificationManager).notifyAsUser(any(), anyInt(), any(), any());

        mNotificationController.handleScanResults(new ArrayList<>());

        verify(mNotificationManager).cancelAsUser(any(), anyInt(), any());
    }
    /**
     * When a notification is showing, screen is off, and scan results with no open networks are
     * handled, the notification is cleared.
     */
    @Test
    public void handleScanResults_notificationShown_screenOff_emptyList_notificationCleared() {
        mNotificationController.handleScanResults(createOpenScanResults());

        verify(mNotificationManager).notifyAsUser(any(), anyInt(), any(), any());

        mNotificationController.handleScreenStateChanged(false);
        mNotificationController.handleScanResults(new ArrayList<>());

        verify(mNotificationManager).cancelAsUser(any(), anyInt(), any());
    }

    /**
     * If notification is showing, do not post another notification.
     */
    @Test
    public void handleScanResults_notificationShowing_doesNotRepostNotification() {
        mNotificationController.handleScanResults(createOpenScanResults());
        mNotificationController.handleScanResults(createOpenScanResults());

        verify(mNotificationManager).notifyAsUser(any(), anyInt(), any(), any());
    }

    /**
     * When {@link WifiNotificationController#clearPendingNotification(boolean)} is called and a
     * notification is shown, clear the notification.
     */
    @Test
    public void clearPendingNotification_clearsNotificationIfOneIsShowing() {
        mNotificationController.handleScanResults(createOpenScanResults());

        verify(mNotificationManager).notifyAsUser(any(), anyInt(), any(), any());

        mNotificationController.clearPendingNotification(true);

        verify(mNotificationManager).cancelAsUser(any(), anyInt(), any());
    }

    /**
     * When {@link WifiNotificationController#clearPendingNotification(boolean)} is called and a
     * notification was not previously shown, do not clear the notification.
     */
    @Test
    public void clearPendingNotification_doesNotClearNotificationIfNoneShowing() {
        mNotificationController.clearPendingNotification(true);

        verify(mNotificationManager, never()).cancelAsUser(any(), anyInt(), any());
    }

    /**
     * When screen is off and notification is not displayed, notification is not posted on handling
     * new scan results with open networks.
     */
    @Test
    public void screenOff_handleScanResults_notificationNotDisplayed() {
        mNotificationController.handleScreenStateChanged(false);
        mNotificationController.handleScanResults(createOpenScanResults());

        verify(mNotificationManager, never()).notifyAsUser(any(), anyInt(), any(), any());
    }

    /** Verifies that {@link UserManager#DISALLOW_CONFIG_WIFI} disables the feature. */
    @Test
    public void userHasDisallowConfigWifiRestriction_notificationNotDisplayed() {
        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, UserHandle.CURRENT))
                .thenReturn(true);

        mNotificationController.handleScanResults(createOpenScanResults());

        verify(mNotificationManager, never()).notifyAsUser(any(), anyInt(), any(), any());
    }

    /** Verifies that {@link UserManager#DISALLOW_CONFIG_WIFI} clears the showing notification. */
    @Test
    public void userHasDisallowConfigWifiRestriction_showingNotificationIsCleared() {
        mNotificationController.handleScanResults(createOpenScanResults());

        verify(mNotificationManager).notifyAsUser(any(), anyInt(), any(), any());

        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, UserHandle.CURRENT))
                .thenReturn(true);

        mNotificationController.handleScanResults(createOpenScanResults());

        verify(mNotificationManager).cancelAsUser(any(), anyInt(), any());
    }
}
