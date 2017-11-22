/*
 * Copyright 2017 The Android Open Source Project
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
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;

/**
 * WakeupController is responsible managing Auto Wifi.
 *
 * <p>It determines if and when to re-enable wifi after it has been turned off by the user.
 */
public class WakeupController {

    // TODO(b/69624403) propagate this to Settings
    private static final boolean USE_PLATFORM_WIFI_WAKE = false;

    private final Context mContext;
    private final Handler mHandler;
    private final FrameworkFacade mFrameworkFacade;
    private final ContentObserver mContentObserver;
    private final WakeupLock mWakeupLock;
    private final WifiConfigManager mWifiConfigManager;

    /** Whether this feature is enabled in Settings. */
    private boolean mWifiWakeupEnabled;

    /** Whether the WakeupController is currently active. */
    private boolean mIsActive = false;

    public WakeupController(
            Context context,
            Looper looper,
            WakeupLock wakeupLock,
            WifiConfigManager wifiConfigManager,
            WifiConfigStore wifiConfigStore,
            FrameworkFacade frameworkFacade) {
        mContext = context;
        mHandler = new Handler(looper);
        mWakeupLock = wakeupLock;
        mWifiConfigManager = wifiConfigManager;
        mFrameworkFacade = frameworkFacade;
        mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mWifiWakeupEnabled = mFrameworkFacade.getIntegerSetting(
                                mContext, Settings.Global.WIFI_WAKEUP_ENABLED, 0) == 1;
            }
        };
        mFrameworkFacade.registerContentObserver(mContext, Settings.Global.getUriFor(
                Settings.Global.WIFI_WAKEUP_ENABLED), true, mContentObserver);
        mContentObserver.onChange(false /* selfChange */);

        // registering the store data here has the effect of reading the persisted value of the
        // data sources after system boot finishes
        WakeupConfigStoreData wakeupConfigStoreData =
                new WakeupConfigStoreData(new IsActiveDataSource(), mWakeupLock.getDataSource());
        wifiConfigStore.registerStoreData(wakeupConfigStoreData);
    }

    private void setActive(boolean isActive) {
        if (mIsActive != isActive) {
            mIsActive = isActive;
            mWifiConfigManager.saveToStore(false /* forceWrite */);
        }
    }

    /**
     * Whether the feature is enabled in settings.
     *
     * <p>Note: This method is only used to determine whether or not to actually enable wifi. All
     * other aspects of the WakeupController lifecycle operate normally irrespective of this.
     */
    @VisibleForTesting
    boolean isEnabled() {
        return mWifiWakeupEnabled;
    }

    private class IsActiveDataSource implements WakeupConfigStoreData.DataSource<Boolean> {

        @Override
        public Boolean getData() {
            return mIsActive;
        }

        @Override
        public void setData(Boolean data) {
            mIsActive = data;
        }
    }
}
