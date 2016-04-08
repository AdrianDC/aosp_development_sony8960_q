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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.server.wifi.MockAnswerUtil.AnswerWithArguments;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;

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

        final byte[] data = new byte[WifiLogger.RING_BUFFER_BYTE_LIMIT_SMALL];
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

        final byte[] data1 = new byte[WifiLogger.RING_BUFFER_BYTE_LIMIT_SMALL];
        final byte[] data2 = {1, 2, 3};
        mWifiLogger.onRingBufferData(mFakeRbs, data1);
        mWifiLogger.onRingBufferData(mFakeRbs, data2);
        mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_NONE);

        byte[][] ringBufferData = getLoggerRingBufferData();
        assertEquals(1, ringBufferData.length);
        assertArrayEquals(data2, ringBufferData[0]);
    }

    /**
     * Verifies that, when verbose mode is not enabled, startLogging() does not
     * startPktFateMonitoring().
     */
    @Test
    public void startLoggingIgnoresPacketFateWithoutVerboseMode() {
        final boolean verbosityToggle = false;
        mWifiLogger.startLogging(verbosityToggle);
        verify(mWifiNative, never()).startPktFateMonitoring();
    }

    /**
     * Verifies that, when verbose mode is enabled, startLogging() calls
     * startPktFateMonitoring().
     */
    @Test
    public void startLoggingStartsPacketFateInVerboseMode() {
        final boolean verbosityToggle = true;
        mWifiLogger.startLogging(verbosityToggle);
        verify(mWifiNative).startPktFateMonitoring();
    }

    /**
     * Verifies that, when verbose mode is not enabled, reportConnectionFailure() does not
     * fetch packet fates.
     */
    @Test
    public void reportConnectionFailureIsIgnoredWithoutVerboseMode() {
        final boolean verbosityToggle = false;
        mWifiLogger.startLogging(verbosityToggle);
        mWifiLogger.reportConnectionFailure();
        verify(mWifiNative, never()).getTxPktFates(anyObject());
        verify(mWifiNative, never()).getRxPktFates(anyObject());
    }

    /**
     * Verifies that, when verbose mode is enabled, reportConnectionFailure() fetches packet fates.
     */
    @Test
    public void reportConnectionFailureFetchesFatesInVerboseMode() {
        final boolean verbosityToggle = true;
        mWifiLogger.startLogging(verbosityToggle);
        mWifiLogger.reportConnectionFailure();
        verify(mWifiNative).getTxPktFates(anyObject());
        verify(mWifiNative).getRxPktFates(anyObject());
    }

    /**
     * Verifies that we try to fetch TX fates, even if fetching RX fates failed.
     */
    @Test
    public void loggerFetchesTxFatesEvenIfFetchingRxFatesFails() {
        final boolean verbosityToggle = true;
        when(mWifiNative.getRxPktFates(anyObject())).thenReturn(false);
        mWifiLogger.startLogging(verbosityToggle);
        mWifiLogger.reportConnectionFailure();
        verify(mWifiNative).getTxPktFates(anyObject());
        verify(mWifiNative).getRxPktFates(anyObject());
    }

    /**
     * Verifies that we try to fetch RX fates, even if fetching TX fates failed.
     */
    @Test
    public void loggerFetchesRxFatesEvenIfFetchingTxFatesFails() {
        final boolean verbosityToggle = true;
        when(mWifiNative.getTxPktFates(anyObject())).thenReturn(false);
        mWifiLogger.startLogging(verbosityToggle);
        mWifiLogger.reportConnectionFailure();
        verify(mWifiNative).getTxPktFates(anyObject());
        verify(mWifiNative).getRxPktFates(anyObject());
    }

    /**
     * Verifies that dump() does not synchronously fetch fates in release builds. (In debug builds,
     * having dump() do an additional fetch makes it possible to test the feature with a fully
     * working network.)
     */
    @Test
    public void dumpDoesNotFetchFatesInReleaseBuild() {
        final boolean verbosityToggle = true;
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiLogger.startLogging(verbosityToggle);
        mWifiLogger.dump(new FileDescriptor(), pw, new String[]{"bogus", "args"});
        verify(mWifiNative, never()).getTxPktFates(anyObject());
        verify(mWifiNative, never()).getRxPktFates(anyObject());
    }

    /**
     * Verifies that dump() doesn't crash, or generate garbage, in the case where we haven't fetched
     * any fates.
     */
    @Test
    public void dumpSucceedsWhenNoFatesHaveNotBeenFetched() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiLogger.dump(new FileDescriptor(), pw, new String[]{"bogus", "args"});

        String fateDumpString = sw.toString();
        assertTrue(fateDumpString.contains("Last failed"));
        // Verify dump terminator is present
        assertTrue(fateDumpString.contains(
                "--------------------------------------------------------------------"));
    }

    /**
     * Verifies that dump() doesn't crash, or generate garbage, in the case where the fates that
     * the HAL-provided fates are empty.
     */
    @Test
    public void dumpSucceedsWhenFatesHaveBeenFetchedButAreEmpty() {
        final boolean verbosityToggle = true;
        mWifiLogger.startLogging(verbosityToggle);
        mWifiLogger.reportConnectionFailure();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiLogger.dump(new FileDescriptor(), pw, new String[]{"bogus", "args"});
        verify(mWifiNative).getTxPktFates(anyObject());
        verify(mWifiNative).getRxPktFates(anyObject());

        String fateDumpString = sw.toString();
        assertTrue(fateDumpString.contains("Last failed"));
        // Verify dump terminator is present
        assertTrue(fateDumpString.contains(
                "--------------------------------------------------------------------"));
    }

    /**
     * Verifies that dump() shows both TX, and RX, fates.
     */
    @Test
    public void dumpShowsTxAndRxFates() {
        final boolean verbosityToggle = true;
        mWifiLogger.startLogging(verbosityToggle);
        when(mWifiNative.getTxPktFates(anyObject())).then(new AnswerWithArguments() {
            public boolean answer(WifiNative.TxFateReport[] fates) {
                fates[0] = new WifiNative.TxFateReport(
                        WifiLoggerHal.TX_PKT_FATE_ACKED, 0, WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                        new byte[0]
                );
                return true;
            }
        });
        when(mWifiNative.getRxPktFates(anyObject())).then(new AnswerWithArguments() {
            public boolean answer(WifiNative.RxFateReport[] fates) {
                fates[0] = new WifiNative.RxFateReport(
                        WifiLoggerHal.RX_PKT_FATE_SUCCESS, 1, WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                        new byte[0]
                );
                return true;
            }
        });
        mWifiLogger.reportConnectionFailure();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiLogger.dump(new FileDescriptor(), pw, new String[]{"bogus", "args"});

        String fateDumpString = sw.toString();
        assertTrue(fateDumpString.contains("Frame direction: TX"));
        assertTrue(fateDumpString.contains("Frame direction: RX"));
    }

    /**
     * Verifies that dump() outputs frames in timestamp order, even if the HAL provided the
     * data out-of-order.
     */
    @Test
    public void dumpIsSortedByTimestamp() {
        final boolean verbosityToggle = true;
        mWifiLogger.startLogging(verbosityToggle);
        when(mWifiNative.getTxPktFates(anyObject())).then(new AnswerWithArguments() {
            public boolean answer(WifiNative.TxFateReport[] fates) {
                fates[0] = new WifiNative.TxFateReport(
                        WifiLoggerHal.TX_PKT_FATE_ACKED, 2, WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                        new byte[0]
                );
                fates[1] = new WifiNative.TxFateReport(
                        WifiLoggerHal.TX_PKT_FATE_ACKED, 0, WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                        new byte[0]
                );
                return true;
            }
        });
        when(mWifiNative.getRxPktFates(anyObject())).then(new AnswerWithArguments() {
            public boolean answer(WifiNative.RxFateReport[] fates) {
                fates[0] = new WifiNative.RxFateReport(
                        WifiLoggerHal.RX_PKT_FATE_SUCCESS, 3, WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                        new byte[0]
                );
                fates[1] = new WifiNative.RxFateReport(
                        WifiLoggerHal.RX_PKT_FATE_SUCCESS, 1, WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                        new byte[0]
                );
                return true;
            }
        });
        mWifiLogger.reportConnectionFailure();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiLogger.dump(new FileDescriptor(), pw, new String[]{"bogus", "args"});

        String fateDumpString = sw.toString();
        assertTrue(fateDumpString.contains(
                "Frame number: 1\nFrame direction: TX\nFrame timestamp: 0\n"));
        assertTrue(fateDumpString.contains(
                "Frame number: 2\nFrame direction: RX\nFrame timestamp: 1\n"));
        assertTrue(fateDumpString.contains(
                "Frame number: 3\nFrame direction: TX\nFrame timestamp: 2\n"));
        assertTrue(fateDumpString.contains(
                "Frame number: 4\nFrame direction: RX\nFrame timestamp: 3\n"));
    }

    /** Verifies that the default size of our ring buffers is small. */
    @Test
    public void ringBufferSizeIsSmallByDefault() throws Exception {
        final boolean verbosityToggle = false;
        mWifiLogger.startLogging(verbosityToggle);
        mWifiLogger.onRingBufferData(
                mFakeRbs, new byte[WifiLogger.RING_BUFFER_BYTE_LIMIT_SMALL + 1]);
        mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_NONE);
        assertEquals(0, getLoggerRingBufferData().length);
    }

    /** Verifies that we use large ring buffers when initially started in verbose mode. */
    @Test
    public void ringBufferSizeIsLargeInVerboseMode() throws Exception {
        final boolean verbosityToggle = true;
        mWifiLogger.startLogging(verbosityToggle);
        mWifiLogger.onRingBufferData(mFakeRbs, new byte[WifiLogger.RING_BUFFER_BYTE_LIMIT_LARGE]);
        mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_NONE);
        assertEquals(1, getLoggerRingBufferData().length);
    }

    /** Verifies that we use large ring buffers when switched from normal to verbose mode. */
    @Test
    public void startLoggingGrowsRingBuffersIfNeeded() throws Exception {
        mWifiLogger.startLogging(false  /* verbose disabled */);
        mWifiLogger.startLogging(true  /* verbose enabled */);
        mWifiLogger.onRingBufferData(mFakeRbs, new byte[WifiLogger.RING_BUFFER_BYTE_LIMIT_LARGE]);
        mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_NONE);
        assertEquals(1, getLoggerRingBufferData().length);
    }

    /** Verifies that we use small ring buffers when switched from verbose to normal mode. */
    @Test
    public void startLoggingShrinksRingBuffersIfNeeded() throws Exception {
        mWifiLogger.startLogging(true  /* verbose enabled */);
        mWifiLogger.onRingBufferData(
                mFakeRbs, new byte[WifiLogger.RING_BUFFER_BYTE_LIMIT_SMALL + 1]);

        // Existing data is nuked (too large).
        mWifiLogger.startLogging(false  /* verbose disabled */);
        mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_NONE);
        assertEquals(0, getLoggerRingBufferData().length);

        // New data must obey limit as well.
        mWifiLogger.onRingBufferData(
                mFakeRbs, new byte[WifiLogger.RING_BUFFER_BYTE_LIMIT_SMALL + 1]);
        mWifiLogger.captureBugReportData(WifiLogger.REPORT_REASON_NONE);
        assertEquals(0, getLoggerRingBufferData().length);
    }
}
