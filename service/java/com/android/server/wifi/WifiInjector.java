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

import android.content.Context;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.os.UserManager;
import android.provider.Settings;
import android.security.KeyStore;

import com.android.internal.R;
import com.android.server.am.BatteryStatsService;

/**
 *  WiFi dependency injecto. To be used for accessing various wifi class instances and as a
 *  handle for mock injection.
 *
 *  Some wifi class instances currently depend on having a Looper from a HandlerThread that has
 *  been started. To accomodate this, we have a two-phased approach to initialize and retrieve
 *  an instance of the WifiInjector.
 */
public class WifiInjector {
    private static final String BOOT_DEFAULT_WIFI_COUNTRY_CODE = "ro.boot.wificountrycode";

    static WifiInjector sWifiInjector = null;

    private final Context mContext;
    private final FrameworkFacade mFrameworkFacade = new FrameworkFacade();
    private final HandlerThread mWifiServiceHandlerThread;
    private final HandlerThread mWifiStateMachineHandlerThread;
    private final WifiTrafficPoller mTrafficPoller;
    private final WifiCountryCode mCountryCode;
    private final BackupManagerProxy mBackupManagerProxy = new BackupManagerProxy();
    private final WifiStateMachine mWifiStateMachine;
    private final WifiSettingsStore mSettingsStore;
    private final WifiCertManager mCertManager;
    private final WifiNotificationController mNotificationController;
    private final WifiLockManager mLockManager;
    private final WifiController mWifiController;
    private final Clock mClock = new Clock();
    private final WifiMetrics mWifiMetrics = new WifiMetrics(mClock);
    private final WifiLastResortWatchdog mWifiLastResortWatchdog;
    private final PropertyService mPropertyService = new SystemPropertyService();
    private final BuildProperties mBuildProperties = new SystemBuildProperties();
    private final KeyStore mKeyStore = KeyStore.getInstance();
    private final WifiBackupRestore mWifiBackupRestore = new WifiBackupRestore();

    public WifiInjector(Context context) {
        if (context == null) {
            throw new IllegalStateException(
                    "WifiInjector should not be initialized with a null Context.");
        }

        if (sWifiInjector != null) {
            throw new IllegalStateException(
                    "WifiInjector was already created, use getInstance instead.");
        }

        sWifiInjector = this;

        mContext = context;
        // Now create and start handler threads
        mWifiServiceHandlerThread = new HandlerThread("WifiService");
        mWifiServiceHandlerThread.start();
        mWifiStateMachineHandlerThread = new HandlerThread("WifiStateMachine");
        mWifiStateMachineHandlerThread.start();

        // Now get instances of all the objects that depend on the HandlerThreads
        mTrafficPoller =  new WifiTrafficPoller(mContext, mWifiServiceHandlerThread.getLooper(),
                WifiNative.getWlanNativeInterface().getInterfaceName());
        mCountryCode = new WifiCountryCode(WifiNative.getWlanNativeInterface(),
                SystemProperties.get(BOOT_DEFAULT_WIFI_COUNTRY_CODE),
                mFrameworkFacade.getStringSetting(mContext, Settings.Global.WIFI_COUNTRY_CODE),
                mContext.getResources()
                        .getBoolean(R.bool.config_wifi_revert_country_code_on_cellular_loss));
        mWifiStateMachine = new WifiStateMachine(mContext, mFrameworkFacade,
                mWifiStateMachineHandlerThread.getLooper(), UserManager.get(mContext),
                this, mBackupManagerProxy, mCountryCode);
        mSettingsStore = new WifiSettingsStore(mContext);
        mCertManager = new WifiCertManager(mContext);
        mNotificationController = new WifiNotificationController(mContext,
                mWifiServiceHandlerThread.getLooper(), mWifiStateMachine,
                mFrameworkFacade, null);
        mLockManager = new WifiLockManager(mContext, BatteryStatsService.getService());
        mWifiController = new WifiController(mContext, mWifiStateMachine, mSettingsStore,
                mLockManager, mWifiServiceHandlerThread.getLooper(), mFrameworkFacade);
        mWifiLastResortWatchdog = new WifiLastResortWatchdog(mWifiController, mWifiMetrics);
    }

    /**
     *  Obtain an instance of the WifiInjector class.
     *
     *  This is the generic method to get an instance of the class. The first instance should be
     *  retrieved using the getInstanceWithContext method.
     */
    public static WifiInjector getInstance() {
        if (sWifiInjector == null) {
            throw new IllegalStateException(
                    "Attempted to retrieve a WifiInjector instance before constructor was called.");
        }
        return sWifiInjector;
    }

    public WifiMetrics getWifiMetrics() {
        return mWifiMetrics;
    }

    public BackupManagerProxy getBackupManagerProxy() {
        return mBackupManagerProxy;
    }

    public FrameworkFacade getFrameworkFacade() {
        return mFrameworkFacade;
    }

    public HandlerThread getWifiServiceHandlerThread() {
        return mWifiServiceHandlerThread;
    }

    public HandlerThread getWifiStateMachineHandlerThread() {
        return mWifiStateMachineHandlerThread;
    }

    public WifiTrafficPoller getWifiTrafficPoller() {
        return mTrafficPoller;
    }

    public WifiCountryCode getWifiCountryCode() {
        return mCountryCode;
    }

    public WifiStateMachine getWifiStateMachine() {
        return mWifiStateMachine;
    }

    public WifiSettingsStore getWifiSettingsStore() {
        return mSettingsStore;
    }

    public WifiCertManager getWifiCertManager() {
        return mCertManager;
    }

    public WifiNotificationController getWifiNotificationController() {
        return mNotificationController;
    }

    public WifiLockManager getWifiLockManager() {
        return mLockManager;
    }

    public WifiController getWifiController() {
        return mWifiController;
    }

    public WifiLastResortWatchdog getWifiLastResortWatchdog() {
        return mWifiLastResortWatchdog;
    }

    public Clock getClock() {
        return mClock;
    }

    public PropertyService getPropertyService() {
        return mPropertyService;
    }

    public BuildProperties getBuildProperties() {
        return mBuildProperties;
    }

    public KeyStore getKeyStore() {
        return mKeyStore;
    }

    public WifiBackupRestore getWifiBackupRestore() {
        return mWifiBackupRestore;
    }
}
