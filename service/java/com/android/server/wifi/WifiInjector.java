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
import android.net.ConnectivityManager;
import android.net.wifi.IApInterface;
import android.net.wifi.IWificond;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.KeyStore;
import android.telephony.TelephonyManager;

import com.android.internal.R;
import com.android.server.am.BatteryStatsService;
import com.android.server.net.DelayedDiskWrite;
import com.android.server.net.IpConfigStore;

import java.util.ArrayList;

/**
 *  WiFi dependency injector. To be used for accessing various WiFi class instances and as a
 *  handle for mock injection.
 *
 *  Some WiFi class instances currently depend on having a Looper from a HandlerThread that has
 *  been started. To accommodate this, we have a two-phased approach to initialize and retrieve
 *  an instance of the WifiInjector.
 */
public class WifiInjector {
    private static final String BOOT_DEFAULT_WIFI_COUNTRY_CODE = "ro.boot.wificountrycode";
    private static final String WIFICOND_SERVICE_NAME = "wificond";

    static WifiInjector sWifiInjector = null;

    private final Context mContext;
    private final FrameworkFacade mFrameworkFacade = new FrameworkFacade();
    private final HandlerThread mWifiServiceHandlerThread;
    private final HandlerThread mWifiStateMachineHandlerThread;
    private final WifiTrafficPoller mTrafficPoller;
    private final WifiCountryCode mCountryCode;
    private final BackupManagerProxy mBackupManagerProxy = new BackupManagerProxy();
    private final WifiNative mWifiNative;
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
    private final WifiMulticastLockManager mWifiMulticastLockManager;
    private final WifiConfigStore mWifiConfigStore;
    private final WifiKeyStore mWifiKeyStore;
    private final WifiNetworkHistory mWifiNetworkHistory;
    private final WifiSupplicantControl mWifiSupplicantControl;
    private final IpConfigStore mIpConfigStore;
    private final WifiConfigStoreLegacy mWifiConfigStoreLegacy;
    private final WifiConfigManager mWifiConfigManager;

    private final boolean mUseRealLogger;

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
        mUseRealLogger = mContext.getResources().getBoolean(
                R.bool.config_wifi_enable_wifi_firmware_debugging);

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
        mWifiNative = WifiNative.getWlanNativeInterface();

        // WifiConfigManager/Store objects and their dependencies.
        // New config store
        mWifiKeyStore = new WifiKeyStore(mKeyStore);
        mWifiConfigStore = new WifiConfigStore(
                mContext, mWifiStateMachineHandlerThread.getLooper(), mClock,
                WifiConfigStore.createSharedFile(),
                WifiConfigStore.createUserFile(UserHandle.USER_SYSTEM));
        // Legacy config store
        DelayedDiskWrite writer = new DelayedDiskWrite();
        mWifiNetworkHistory = new WifiNetworkHistory(mContext, mWifiNative.getLocalLog(), writer);
        mWifiSupplicantControl = new WifiSupplicantControl(
                TelephonyManager.from(mContext), mWifiNative, mWifiNative.getLocalLog());
        mIpConfigStore = new IpConfigStore(writer);
        mWifiConfigStoreLegacy = new WifiConfigStoreLegacy(
                mWifiNetworkHistory, mWifiSupplicantControl, mIpConfigStore);
        // Config Manager
        mWifiConfigManager = new WifiConfigManager(mContext, mFrameworkFacade, mClock,
                UserManager.get(mContext), TelephonyManager.from(mContext),
                mWifiKeyStore, mWifiConfigStore, mWifiConfigStoreLegacy);

        mWifiStateMachine = new WifiStateMachine(mContext, mFrameworkFacade,
                mWifiStateMachineHandlerThread.getLooper(), UserManager.get(mContext),
                this, mBackupManagerProxy, mCountryCode, mWifiNative);
        mSettingsStore = new WifiSettingsStore(mContext);
        mCertManager = new WifiCertManager(mContext);
        mNotificationController = new WifiNotificationController(mContext,
                mWifiServiceHandlerThread.getLooper(), mWifiStateMachine,
                mFrameworkFacade, null);
        mLockManager = new WifiLockManager(mContext, BatteryStatsService.getService());
        mWifiController = new WifiController(mContext, mWifiStateMachine, mSettingsStore,
                mLockManager, mWifiServiceHandlerThread.getLooper(), mFrameworkFacade);
        mWifiLastResortWatchdog = new WifiLastResortWatchdog(mWifiController, mWifiMetrics);
        mWifiMulticastLockManager = new WifiMulticastLockManager(mWifiStateMachine,
                BatteryStatsService.getService());
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

    public WifiMulticastLockManager getWifiMulticastLockManager() {
        return mWifiMulticastLockManager;
    }

    public WifiSupplicantControl getWifiSupplicantControl() {
        return mWifiSupplicantControl;
    }

    public WifiConfigManager getWifiConfigManager() {
        return mWifiConfigManager;
    }

    public TelephonyManager makeTelephonyManager() {
        // may not be available when WiFi starts
        return (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    public IWificond makeWificond() {
        // We depend on being able to refresh our binder in WifiStateMachine, so don't cache it.
        IBinder binder = ServiceManager.getService(WIFICOND_SERVICE_NAME);
        return IWificond.Stub.asInterface(binder);
    }

    /**
     * Create a SoftApManager.
     * @param wifiNative reference to WifiNative
     * @param nmService reference to NetworkManagementService
     * @param cm reference to ConnectivityManager
     * @param countryCode Country code
     * @param allowed2GChannels list of allowed 2G channels
     * @param listener listener for SoftApManager
     * @param apInterface network interface to start hostapd against
     * @return an instance of SoftApManager
     */
    public SoftApManager makeSoftApManager(
            WifiNative wifiNative,
            INetworkManagementService nmService, ConnectivityManager cm,
            String countryCode, ArrayList<Integer> allowed2GChannels,
            SoftApManager.Listener listener, IApInterface apInterface) {
        return new SoftApManager(
                mWifiServiceHandlerThread.getLooper(),
                wifiNative, countryCode,
                allowed2GChannels, listener, apInterface);
    }

    /**
     * Create a WifiLog instance.
     * @param tag module name to include in all log messages
     */
    public WifiLog makeLog(String tag) {
        return new LogcatLog(tag);
    }

    public BaseWifiDiagnostics makeWifiDiagnostics(WifiNative wifiNative) {
        if (mUseRealLogger) {
            return new WifiDiagnostics(
                    mContext, this, mWifiStateMachine, wifiNative, mBuildProperties);
        } else {
            return new BaseWifiDiagnostics();
        }
    }
}
