/*
 * Copyright 2018 The Android Open Source Project
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

import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link ScanOnlyModeManager}.
 */
@SmallTest
public class ScanOnlyModeManagerTest {
    private static final String TAG = "ScanOnlyModeManagerTest";

    TestLooper mLooper;

    ScanOnlyModeManager mScanOnlyModeManager;

    @Before
    public void setUp() {
        mLooper = new TestLooper();
        mScanOnlyModeManager = createScanOnlyModeManager();
    }

    private ScanOnlyModeManager createScanOnlyModeManager() {
        return new ScanOnlyModeManager(mLooper.getLooper());
    }

    /**
     * This is a basic test that will be enhanced as functionality is added to the class.
     */
    @Test
    public void scanModeStartDoesNotCrash() {
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();
    }

    /**
     * Calling ScanOnlyModeManager.start twice does not crash or restart scan mode.
     */
    @Test
    public void scanOnlyModeStartCalledTwice() {
        mScanOnlyModeManager.start();
        mScanOnlyModeManager.start();
        mLooper.dispatchAll();
    }

    /**
     * This is a basic test that will be enhanced as funtionatliy is added to the class.
     */
    @Test
    public void scanModeStopDoesNotCrash() {
        mScanOnlyModeManager.start();
        mScanOnlyModeManager.stop();
        mLooper.dispatchAll();
    }

    /**
     * Calling stop when ScanMode is not started should not crash.
     */
    @Test
    public void scanModeStopWhenNotStartedDoesNotCrash() {
        mScanOnlyModeManager.stop();
        mLooper.dispatchAll();
    }
}
