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

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * A lock to determine whether Auto Wifi can re-enable Wifi.
 *
 * <p>Wakeuplock manages a list of networks to determine whether the device's location has changed.
 */
public class WakeupLock {

    @VisibleForTesting
    static final int CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT = 3;

    private Map<ScanResultMatchInfo, Integer> mLockedNetworks = new ArrayMap<>();

    // TODO(easchwar) read initial value of mLockedNetworks from file
    public WakeupLock() {
    }

    /**
     * Initializes the WakeupLock with the given {@link ScanResultMatchInfo} list.
     *
     * @param scanResultList list of ScanResultMatchInfos to start the lock with
     */
    public void initialize(Collection<ScanResultMatchInfo> scanResultList) {
        mLockedNetworks.clear();
        for (ScanResultMatchInfo scanResultMatchInfo : scanResultList) {
            mLockedNetworks.put(scanResultMatchInfo, CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT);
        }
    }

    /**
     * Updates the lock with the given {@link ScanResultMatchInfo} list.
     *
     * <p>If a network in the lock is not present in the list, reduce the number of scans
     * required to evict by one. Remove any entries in the list with 0 scans required to evict.
     *
     * @param scanResultList list of present ScanResultMatchInfos to update the lock with
     */
    public void update(Collection<ScanResultMatchInfo> scanResultList) {
        Iterator<Map.Entry<ScanResultMatchInfo, Integer>> it =
                mLockedNetworks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ScanResultMatchInfo, Integer> entry = it.next();

            // if present in scan list, reset to max
            if (scanResultList.contains(entry.getKey())) {
                entry.setValue(CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT);
                continue;
            }

            // decrement and remove if necessary
            entry.setValue(entry.getValue() - 1);
            if (entry.getValue() <= 0) {
                it.remove();
            }
        }
        // TODO(easchwar) write the updated list to file
    }

    /**
     * Returns whether the internal network set is empty.
     */
    public boolean isEmpty() {
        return mLockedNetworks.isEmpty();
    }
}
