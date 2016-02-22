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

import android.test.suitebuilder.annotation.SmallTest;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.server.wifi.WifiLogger}.
 */
@SmallTest
public class WifiLoggerTest {
    public static final String TAG = "WifiLoggerTest";

    @Mock WifiStateMachine mWsm;
    @Mock WifiNative mWifiNative;
    WifiLogger mWifiLogger;

    private static final String FAKE_RING_BUFFER_NAME = "fake-ring-buffer";

    /**
     * Initializes common state (e.g. mocks) needed by test cases.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        WifiNative.RingBufferStatus fakeRbs = new WifiNative.RingBufferStatus();
        WifiNative.RingBufferStatus[] ringBufferStatuses = new WifiNative.RingBufferStatus[] {
                fakeRbs
        };
        fakeRbs.name = FAKE_RING_BUFFER_NAME;
        when(mWifiNative.getRingBufferStatus()).thenReturn(ringBufferStatuses);

        mWifiLogger = new WifiLogger(mWsm, mWifiNative);
    }

    /**
     * Verifies that startLogging() restarts HAL ringbuffers.
     *
     * Specifically: verifies that startLogging()
     * a) stops any ring buffer logging that might be already running,
     * b) instructs WifiNative to enable ring buffers of the appropriate log level.
     */
    @Test
    public void startLoggingStopsAndRestartsRingBufferLogging() throws Exception {
        final boolean verbosityToggle = false;
        mWifiLogger.startLogging(verbosityToggle);
        verify(mWifiNative).startLoggingRingBuffer(
                eq(WifiLogger.VERBOSE_NO_LOG), anyInt(), anyInt(), anyInt(),
                eq(FAKE_RING_BUFFER_NAME));
        verify(mWifiNative).startLoggingRingBuffer(
                eq(WifiLogger.VERBOSE_NORMAL_LOG), anyInt(), anyInt(), anyInt(),
                eq(FAKE_RING_BUFFER_NAME));
    }
}
