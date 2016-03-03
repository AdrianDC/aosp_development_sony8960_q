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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
    private WifiNative.RingBufferStatus mFakeRbs;

    /**
     * Returns the data that we would dump in a bug report, for our ring buffer.
     * @return a 2-D byte array, where the first dimension is the record number, and the second
     * dimension is the byte index within that record.
     */
    private final byte[][] getLoggerRingBufferData() throws Exception {
        return mWifiLogger.getBugReports().get(0).ringBuffers.get(FAKE_RING_BUFFER_NAME);
    }

    /**
     * Initializes common state (e.g. mocks) needed by test cases.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mFakeRbs = new WifiNative.RingBufferStatus();
        mFakeRbs.name = FAKE_RING_BUFFER_NAME;

        WifiNative.RingBufferStatus[] ringBufferStatuses = new WifiNative.RingBufferStatus[] {
                mFakeRbs
        };

        when(mWifiNative.getRingBufferStatus()).thenReturn(ringBufferStatuses);
        when(mWifiNative.readKernelLog()).thenReturn("");

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

    /**
     * Verifies that we capture ring-buffer data.
     */
    @Test
    public void canCaptureAndStoreRingBufferData() throws Exception {
        final boolean verbosityToggle = false;
        mWifiLogger.startLogging(verbosityToggle);

        final byte[] data = new byte[WifiLogger.MAX_RING_BUFFER_SIZE_BYTES];
        mWifiLogger.onRingBufferData(mFakeRbs, data);
        mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_NONE);

        byte[][] ringBufferData = getLoggerRingBufferData();
        assertEquals(1, ringBufferData.length);
        assertArrayEquals(data, ringBufferData[0]);
    }

    /**
     * Verifies that we discard extraneous ring-buffer data.
     */
    @Test
    public void loggerDiscardsExtraneousData() throws Exception {
        final boolean verbosityToggle = false;
        mWifiLogger.startLogging(verbosityToggle);

        final byte[] data1 = new byte[WifiLogger.MAX_RING_BUFFER_SIZE_BYTES];
        final byte[] data2 = {1, 2, 3};
        mWifiLogger.onRingBufferData(mFakeRbs, data1);
        mWifiLogger.onRingBufferData(mFakeRbs, data2);
        mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_NONE);

        byte[][] ringBufferData = getLoggerRingBufferData();
        assertEquals(1, ringBufferData.length);
        assertArrayEquals(data2, ringBufferData[0]);
    }
}
