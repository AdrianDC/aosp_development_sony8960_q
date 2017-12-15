/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.test.TestLooper;
import android.provider.Settings;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link com.android.server.wifi.WakeupOnboarding} */
public class WakeupOnboardingTest {

    @Mock private Context mContext;
    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private WakeupNotificationFactory mWakeupNotificationFactory;
    @Mock private NotificationManager mNotificationManager;

    private TestLooper mLooper;
    private WakeupOnboarding mWakeupOnboarding;

    // convenience method for resetting onboarded status
    private void setOnboardedStatus(boolean isOnboarded) {
        mWakeupOnboarding.getDataSource().setData(isOnboarded);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .thenReturn(mNotificationManager);

        mLooper = new TestLooper();
        mWakeupOnboarding = new WakeupOnboarding(mContext, mWifiConfigManager, mLooper.getLooper(),
                mFrameworkFacade, mWakeupNotificationFactory);
    }

    /**
     * Verify that the notification shows if the user isn't onboarded.
     */
    @Test
    public void showsNotificationIfNotOnboarded() {
        setOnboardedStatus(false);
        mWakeupOnboarding.maybeShowNotification();

        verify(mNotificationManager).notify(eq(SystemMessage.NOTE_WIFI_WAKE_ONBOARD), any());
    }

    /**
     * Verify that the notification does not show if the user is onboarded.
     */
    @Test
    public void doesNotShowNotificationIfAlreadyOnboarded() {
        setOnboardedStatus(true);
        mWakeupOnboarding.maybeShowNotification();

        verify(mNotificationManager, never())
                .notify(eq(SystemMessage.NOTE_WIFI_WAKE_ONBOARD), any());
    }

    /**
     * Verify that the notification does not relaunch if it's already showing.
     */
    @Test
    public void doesNotShowNotificationIfAlreadyShowing() {
        setOnboardedStatus(false);
        mWakeupOnboarding.maybeShowNotification();
        mWakeupOnboarding.maybeShowNotification();

        InOrder inOrder = Mockito.inOrder(mNotificationManager);
        inOrder.verify(mNotificationManager)
                .notify(eq(SystemMessage.NOTE_WIFI_WAKE_ONBOARD), any());
        inOrder.verifyNoMoreInteractions();
    }

    /**
     * Verify that the user is onboarded when the notification is dismissed.
     */
    @Test
    public void dismissNotificationAction_setsOnboarded() {
        setOnboardedStatus(false);
        assertFalse(mWakeupOnboarding.isOnboarded());

        mWakeupOnboarding.maybeShowNotification();
        ArgumentCaptor<BroadcastReceiver> captor = ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext).registerReceiver(captor.capture(), any(IntentFilter.class), any(),
                any(Handler.class));
        BroadcastReceiver broadcastReceiver = captor.getValue();

        broadcastReceiver.onReceive(mContext, new Intent(ACTION_DISMISS_NOTIFICATION));

        verify(mNotificationManager).cancel(SystemMessage.NOTE_WIFI_WAKE_ONBOARD);
        verify(mWifiConfigManager).saveToStore(false);
        assertTrue(mWakeupOnboarding.isOnboarded());
    }

    /**
     * Verify that the user is onboarded and Wifi Wake is turned off when the user selects the
     * ACTION_TURN_OFF_WIFI_WAKE action.
     */
    @Test
    public void turnOffWifiWakeAction_setsOnboardedAndTurnsOffWifiWake() {
        setOnboardedStatus(false);
        assertFalse(mWakeupOnboarding.isOnboarded());

        mWakeupOnboarding.maybeShowNotification();
        ArgumentCaptor<BroadcastReceiver> captor = ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext).registerReceiver(captor.capture(), any(IntentFilter.class), any(),
                any(Handler.class));
        BroadcastReceiver broadcastReceiver = captor.getValue();

        broadcastReceiver.onReceive(mContext, new Intent(ACTION_TURN_OFF_WIFI_WAKE));

        verify(mFrameworkFacade).setIntegerSetting(mContext,
                Settings.Global.WIFI_WAKEUP_ENABLED, 0);

        verify(mNotificationManager).cancel(SystemMessage.NOTE_WIFI_WAKE_ONBOARD);
        verify(mWifiConfigManager).saveToStore(false);
        assertTrue(mWakeupOnboarding.isOnboarded());
    }

    /**
     * Verify that the user is onboarded and sent to WifiSettings when the user selects the
     * ACTION_OPEN_WIFI_SETTINGS action.
     */
    @Test
    public void openWifiSettingsAction_setsOnboardedAndOpensWifiSettings() {
        setOnboardedStatus(false);
        assertFalse(mWakeupOnboarding.isOnboarded());

        mWakeupOnboarding.maybeShowNotification();
        ArgumentCaptor<BroadcastReceiver> captor = ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext).registerReceiver(captor.capture(), any(IntentFilter.class), any(),
                any(Handler.class));
        BroadcastReceiver broadcastReceiver = captor.getValue();

        broadcastReceiver.onReceive(mContext, new Intent(ACTION_OPEN_WIFI_PREFERENCES));

        verify(mContext).startActivity(any());

        verify(mNotificationManager).cancel(SystemMessage.NOTE_WIFI_WAKE_ONBOARD);
        verify(mWifiConfigManager).saveToStore(false);
        assertTrue(mWakeupOnboarding.isOnboarded());
    }

    /**
     * Verify that onStop() doesn't onboard the user.
     */
    @Test
    public void onStopDismissesNotificationWithoutOnboarding() {
        setOnboardedStatus(false);
        assertFalse(mWakeupOnboarding.isOnboarded());

        mWakeupOnboarding.maybeShowNotification();
        mWakeupOnboarding.onStop();

        verify(mNotificationManager).cancel(SystemMessage.NOTE_WIFI_WAKE_ONBOARD);
        assertFalse(mWakeupOnboarding.isOnboarded());
    }
}
