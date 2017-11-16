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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for {@link WakeupLock}.
 */
public class WakeupLockTest {

    private static final String SSID_1 = "ssid1";
    private static final String SSID_2 = "ssid2";

    private ScanResultMatchInfo mNetwork1;
    private ScanResultMatchInfo mNetwork2;
    private WakeupLock mWakeupLock;

    /**
     * Initialize objects before each test run.
     */
    @Before
    public void setUp() {
        mNetwork1 = new ScanResultMatchInfo();
        mNetwork1.networkSsid = SSID_1;
        mNetwork1.networkType = ScanResultMatchInfo.NETWORK_TYPE_OPEN;

        mNetwork2 = new ScanResultMatchInfo();
        mNetwork2.networkSsid = SSID_2;
        mNetwork2.networkType = ScanResultMatchInfo.NETWORK_TYPE_EAP;

        mWakeupLock = new WakeupLock();
    }

    /**
     * Updates the lock enough times to evict any networks not passed in.
     *
     * <p>It calls update {@link WakeupLock#CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT} times with
     * the given network list. It asserts that the lock isn't empty prior to each call to update.
     */
    private void updateEnoughTimesToEvictWithAsserts(Collection<ScanResultMatchInfo> networks) {
        for (int i = 0; i < WakeupLock.CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT; i++) {
            assertFalse("Lock empty after " + i + " scans", mWakeupLock.isEmpty());
            mWakeupLock.update(networks);
        }
    }

    /**
     * Verify that the WakeupLock is not empty immediately after being initialized with networks.
     */
    @Test
    public void verifyNotEmptyWhenInitializedWithNetworkList() {
        mWakeupLock.initialize(Arrays.asList(mNetwork1, mNetwork2));
        assertFalse(mWakeupLock.isEmpty());
    }

    /**
     * Verify that the WakeupLock is empty when initialized with an empty list.
     */
    @Test
    public void isEmptyWhenInitializedWithEmptyList() {
        mWakeupLock.initialize(Collections.emptyList());
        assertTrue(mWakeupLock.isEmpty());
    }

    /**
     * Verify that initializing the WakeupLock clears out previous entries.
     */
    @Test
    public void initializingLockClearsPreviousNetworks() {
        mWakeupLock.initialize(Collections.singletonList(mNetwork1));
        assertFalse(mWakeupLock.isEmpty());

        mWakeupLock.initialize(Collections.emptyList());
        assertTrue(mWakeupLock.isEmpty());
    }

    /**
     * Updating the lock should evict scan results that haven't been seen in
     * {@link WakeupLock#CONSECUTIVE_MISSED_SCANS_REQUIRED_TO_EVICT} scans.
     */
    @Test
    public void updateShouldRemoveNetworksAfterConsecutiveMissedScans() {
        mWakeupLock.initialize(Collections.singletonList(mNetwork1));

        updateEnoughTimesToEvictWithAsserts(Collections.singletonList(mNetwork2));

        assertTrue(mWakeupLock.isEmpty());
    }

    /**
     * Ensure that missed scans must be consecutive in order to evict networks from lock.
     */
    @Test
    public void updateWithLockedNetworkShouldResetRequiredNumberOfScans() {
        List<ScanResultMatchInfo> lockedNetworks = Collections.singletonList(mNetwork1);
        List<ScanResultMatchInfo> updateNetworks = Collections.singletonList(mNetwork2);

        mWakeupLock.initialize(lockedNetworks);

        // one update without network
        mWakeupLock.update(updateNetworks);
        // one update with network
        mWakeupLock.update(lockedNetworks);

        updateEnoughTimesToEvictWithAsserts(updateNetworks);

        assertTrue(mWakeupLock.isEmpty());
    }

    /**
     * Once a network is removed from the lock, it should not be reset even if it's seen again.
     */
    @Test
    public void updateWithLockedNetworkAfterItIsRemovedDoesNotReset() {
        List<ScanResultMatchInfo> lockedNetworks = Collections.singletonList(mNetwork1);
        mWakeupLock.initialize(lockedNetworks);

        updateEnoughTimesToEvictWithAsserts(Collections.emptyList());

        assertTrue(mWakeupLock.isEmpty());
        mWakeupLock.update(lockedNetworks);
        assertTrue(mWakeupLock.isEmpty());
    }

    /**
     * Verify that networks can be incrementally removed from the lock. Their counters should be
     * independent.
     */
    @Test
    public void networksCanBeRemovedIncrementallyFromLock() {
        List<ScanResultMatchInfo> lockedNetworks = Arrays.asList(mNetwork1, mNetwork2);
        mWakeupLock.initialize(lockedNetworks);

        updateEnoughTimesToEvictWithAsserts(Collections.singletonList(mNetwork1));
        assertFalse(mWakeupLock.isEmpty());

        updateEnoughTimesToEvictWithAsserts(Collections.singletonList(mNetwork2));
        assertTrue(mWakeupLock.isEmpty());
    }
}
