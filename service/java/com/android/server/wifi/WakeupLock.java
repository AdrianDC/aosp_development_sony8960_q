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

import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A lock to determine whether Auto Wifi can re-enable Wifi.
 *
 * <p>Wakeuplock manages a list of networks to determine whether the device's location has changed.
 */
public class WakeupLock {

    private static final String TAG = WakeupLock.class.getSimpleName();

    @VisibleForTesting
    static final int CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT = 3;


    private final WifiConfigManager mWifiConfigManager;
    private final Map<ScanResultMatchInfo, Integer> mLockedNetworks = new ArrayMap<>();
    private boolean mVerboseLoggingEnabled;

    public WakeupLock(WifiConfigManager wifiConfigManager) {
        mWifiConfigManager = wifiConfigManager;
    }

    /**
     * Initializes the WakeupLock with the given {@link ScanResultMatchInfo} list.
     *
     * <p>This saves the wakeup lock to the store.
     *
     * @param scanResultList list of ScanResultMatchInfos to start the lock with
     */
    public void initialize(Collection<ScanResultMatchInfo> scanResultList) {
        mLockedNetworks.clear();
        for (ScanResultMatchInfo scanResultMatchInfo : scanResultList) {
            mLockedNetworks.put(scanResultMatchInfo, CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT);
        }

        Log.d(TAG, "Lock initialized. Number of networks: " + mLockedNetworks.size());

        mWifiConfigManager.saveToStore(false /* forceWrite */);
    }

    /**
     * Updates the lock with the given {@link ScanResultMatchInfo} list.
     *
     * <p>If a network in the lock is not present in the list, reduce the number of scans
     * required to evict by one. Remove any entries in the list with 0 scans required to evict. If
     * any entries in the lock are removed, the store is updated.
     *
     * @param scanResultList list of present ScanResultMatchInfos to update the lock with
     */
    public void update(Collection<ScanResultMatchInfo> scanResultList) {
        boolean hasChanged = false;
        Iterator<Map.Entry<ScanResultMatchInfo, Integer>> it =
                mLockedNetworks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ScanResultMatchInfo, Integer> entry = it.next();

            // if present in scan list, reset to max
            if (scanResultList.contains(entry.getKey())) {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "Found network in lock: " + entry.getKey().networkSsid);
                }
                entry.setValue(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT);
                continue;
            }

            // decrement and remove if necessary
            entry.setValue(entry.getValue() - 1);
            if (entry.getValue() <= 0) {
                Log.d(TAG, "Removed network from lock: " + entry.getKey().networkSsid);
                it.remove();
                hasChanged = true;
            }
        }

        if (hasChanged) {
            mWifiConfigManager.saveToStore(false /* forceWrite */);
        }
    }

    /**
     * Returns whether the internal network set is empty.
     */
    public boolean isEmpty() {
        return mLockedNetworks.isEmpty();
    }

    /** Returns the data source for the WakeupLock config store data. */
    public WakeupConfigStoreData.DataSource<Set<ScanResultMatchInfo>> getDataSource() {
        return new WakeupLockDataSource();
    }

    /** Dumps wakeup lock contents. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WakeupLock: ");
        pw.println("Locked networks: " + mLockedNetworks.size());
        for (Map.Entry<ScanResultMatchInfo, Integer> entry : mLockedNetworks.entrySet()) {
            pw.println(entry.getKey() + ", scans to evict: " + entry.getValue());
        }
    }

    /** Set whether verbose logging is enabled. */
    public void enableVerboseLogging(boolean enabled) {
        mVerboseLoggingEnabled = enabled;
    }

    private class WakeupLockDataSource
            implements WakeupConfigStoreData.DataSource<Set<ScanResultMatchInfo>> {

        @Override
        public Set<ScanResultMatchInfo> getData() {
            return mLockedNetworks.keySet();
        }

        @Override
        public void setData(Set<ScanResultMatchInfo> data) {
            mLockedNetworks.clear();
            for (ScanResultMatchInfo network : data) {
                mLockedNetworks.put(network, CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT);
            }

        }
    }
}
