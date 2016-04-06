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
 * limitations under the License
 */

package com.android.server.wifi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link com.android.server.wifi.WifiNative}.
 */
@SmallTest
public class WifiNativeTest {
    private static final int NETWORK_ID = 0;
    private static final String NETWORK_EXTRAS_VARIABLE = "test";
    private static final Map<String, String> NETWORK_EXTRAS_VALUES = new HashMap<>();
    static {
        NETWORK_EXTRAS_VALUES.put("key1", "value1");
        NETWORK_EXTRAS_VALUES.put("key2", "value2");
    }
    private static final String NETWORK_EXTRAS_SERIALIZED =
            "\"%7B%22key2%22%3A%22value2%22%2C%22key1%22%3A%22value1%22%7D\"";

    private WifiNative mWifiNative;

    @Before
    public void setUp() throws Exception {
        final Constructor<WifiNative> wifiNativeConstructor =
                WifiNative.class.getDeclaredConstructor(String.class, Boolean.TYPE);
        wifiNativeConstructor.setAccessible(true);
        mWifiNative = spy(wifiNativeConstructor.newInstance("test", true));
    }

    /**
     * Verifies that setNetworkExtra() correctly writes a serialized and URL-encoded JSON object.
     */
    @Test
    public void testSetNetworkExtra() {
        when(mWifiNative.setNetworkVariable(anyInt(), anyString(), anyString())).thenReturn(true);
        assertTrue(mWifiNative.setNetworkExtra(NETWORK_ID, NETWORK_EXTRAS_VARIABLE,
                NETWORK_EXTRAS_VALUES));
        verify(mWifiNative).setNetworkVariable(NETWORK_ID, NETWORK_EXTRAS_VARIABLE,
                NETWORK_EXTRAS_SERIALIZED);
    }

    /**
     * Verifies that getNetworkExtra() correctly reads a serialized and URL-encoded JSON object.
     */
    @Test
    public void testGetNetworkExtra() {
        when(mWifiNative.getNetworkVariable(NETWORK_ID, NETWORK_EXTRAS_VARIABLE))
                .thenReturn(NETWORK_EXTRAS_SERIALIZED);
        final Map<String, String> actualValues =
                mWifiNative.getNetworkExtra(NETWORK_ID, NETWORK_EXTRAS_VARIABLE);
        assertEquals(NETWORK_EXTRAS_VALUES, actualValues);
    }

    /**
     * Verifies that TxFateReport's constructor sets all of the TxFateReport fields.
     */
    @Test
    public void testTxFateReportCtorSetsFields() {
        long driverTimestampUSec = 12345;
        byte[] frameBytes = new byte[] {'a', 'b', 0, 'c'};
        WifiNative.TxFateReport fateReport = new WifiNative.TxFateReport(
                WifiLoggerHal.TX_PKT_FATE_SENT,  // non-zero value
                driverTimestampUSec,
                WifiLoggerHal.FRAME_TYPE_ETHERNET_II,  // non-zero value
                frameBytes
        );
        assertEquals(WifiLoggerHal.TX_PKT_FATE_SENT, fateReport.mFate);
        assertEquals(driverTimestampUSec, fateReport.mDriverTimestampUSec);
        assertEquals(WifiLoggerHal.FRAME_TYPE_ETHERNET_II, fateReport.mFrameType);
        assertArrayEquals(frameBytes, fateReport.mFrameBytes);
    }

    /**
     * Verifies that RxFateReport's constructor sets all of the RxFateReport fields.
     */
    @Test
    public void testRxFateReportCtorSetsFields() {
        long driverTimestampUSec = 12345;
        byte[] frameBytes = new byte[] {'a', 'b', 0, 'c'};
        WifiNative.RxFateReport fateReport = new WifiNative.RxFateReport(
                WifiLoggerHal.RX_PKT_FATE_FW_DROP_INVALID,  // non-zero value
                driverTimestampUSec,
                WifiLoggerHal.FRAME_TYPE_ETHERNET_II,  // non-zero value
                frameBytes
        );
        assertEquals(WifiLoggerHal.RX_PKT_FATE_FW_DROP_INVALID, fateReport.mFate);
        assertEquals(driverTimestampUSec, fateReport.mDriverTimestampUSec);
        assertEquals(WifiLoggerHal.FRAME_TYPE_ETHERNET_II, fateReport.mFrameType);
        assertArrayEquals(frameBytes, fateReport.mFrameBytes);
    }

    // Support classes for test{Tx,Rx}FateReportToString.
    private static class FrameTypeMapping {
        byte mTypeNumber;
        String mExpectedText;
        FrameTypeMapping(byte typeNumber, String expectedText) {
            this.mTypeNumber = typeNumber;
            this.mExpectedText = expectedText;
        }
    }
    private static class FateMapping {
        byte mFateNumber;
        String mExpectedText;
        FateMapping(byte fateNumber, String expectedText) {
            this.mFateNumber = fateNumber;
            this.mExpectedText = expectedText;
        }
    }

    /**
     * Verifies that TxFateReport.toString() includes the information we care about.
     */
    @Test
    public void testTxFateReportToString() {
        long driverTimestampUSec = 12345;
        byte[] frameBytes = new byte[] {
                'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 0, 1, 2, 3, 4, 5, 6, 7};
        WifiNative.TxFateReport fateReport = new WifiNative.TxFateReport(
                WifiLoggerHal.TX_PKT_FATE_SENT,
                driverTimestampUSec,
                WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                frameBytes
        );

        String fateString = fateReport.toString();
        assertTrue(fateString.contains("Frame direction: TX"));
        assertTrue(fateString.contains("Frame timestamp: 12345"));
        assertTrue(fateString.contains("Frame fate: sent"));
        assertTrue(fateString.contains("Frame type: data"));
        assertTrue(fateString.contains("Frame length: 16"));
        assertTrue(fateString.contains(
                "61 62 63 64 65 66 67 68 00 01 02 03 04 05 06 07")); // hex dump
        // TODO(quiche): uncomment this, once b/27975149 is fixed.
        // assertTrue(fateString.contains("abcdefgh........"));  // hex dump

        FrameTypeMapping[] frameTypeMappings = new FrameTypeMapping[] {
                new FrameTypeMapping(WifiLoggerHal.FRAME_TYPE_UNKNOWN, "unknown"),
                new FrameTypeMapping(WifiLoggerHal.FRAME_TYPE_ETHERNET_II, "data"),
                new FrameTypeMapping(WifiLoggerHal.FRAME_TYPE_80211_MGMT, "802.11 management"),
                new FrameTypeMapping((byte) 42, "42")
        };
        for (FrameTypeMapping frameTypeMapping : frameTypeMappings) {
            fateReport = new WifiNative.TxFateReport(
                    WifiLoggerHal.TX_PKT_FATE_SENT,
                    driverTimestampUSec,
                    frameTypeMapping.mTypeNumber,
                    frameBytes
            );
            assertTrue(fateReport.toString().contains(
                    "Frame type: " + frameTypeMapping.mExpectedText));
        }

        FateMapping[] fateMappings = new FateMapping[] {
                new FateMapping(WifiLoggerHal.TX_PKT_FATE_ACKED, "acked"),
                new FateMapping(WifiLoggerHal.TX_PKT_FATE_SENT, "sent"),
                new FateMapping(WifiLoggerHal.TX_PKT_FATE_FW_QUEUED, "firmware queued"),
                new FateMapping(WifiLoggerHal.TX_PKT_FATE_FW_DROP_INVALID,
                        "firmware dropped (invalid frame)"),
                new FateMapping(
                        WifiLoggerHal.TX_PKT_FATE_FW_DROP_NOBUFS,  "firmware dropped (no bufs)"),
                new FateMapping(
                        WifiLoggerHal.TX_PKT_FATE_FW_DROP_OTHER, "firmware dropped (other)"),
                new FateMapping(WifiLoggerHal.TX_PKT_FATE_DRV_QUEUED, "driver queued"),
                new FateMapping(WifiLoggerHal.TX_PKT_FATE_DRV_DROP_INVALID,
                        "driver dropped (invalid frame)"),
                new FateMapping(WifiLoggerHal.TX_PKT_FATE_DRV_DROP_NOBUFS,
                        "driver dropped (no bufs)"),
                new FateMapping(WifiLoggerHal.TX_PKT_FATE_DRV_DROP_OTHER, "driver dropped (other)"),
                new FateMapping((byte) 42, "42")
        };
        for (FateMapping fateMapping : fateMappings) {
            fateReport = new WifiNative.TxFateReport(
                    fateMapping.mFateNumber,
                    driverTimestampUSec,
                    WifiLoggerHal.FRAME_TYPE_80211_MGMT,
                    frameBytes
            );
            assertTrue(fateReport.toString().contains("Frame fate: " + fateMapping.mExpectedText));
        }
    }

    /**
     * Verifies that RxFateReport.toString() includes the information we care about.
     */
    @Test
    public void testRxFateReportToString() {
        long driverTimestampUSec = 67890;
        byte[] frameBytes = new byte[] {
                'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 0, 1, 2, 3, 4, 5, 6, 7};
        WifiNative.RxFateReport fateReport = new WifiNative.RxFateReport(
                WifiLoggerHal.RX_PKT_FATE_FW_DROP_INVALID,
                driverTimestampUSec,
                WifiLoggerHal.FRAME_TYPE_ETHERNET_II,
                frameBytes
        );

        String fateString = fateReport.toString();
        assertTrue(fateString.contains("Frame direction: RX"));
        assertTrue(fateString.contains("Frame timestamp: 67890"));
        assertTrue(fateString.contains("Frame fate: firmware dropped (invalid frame)"));
        assertTrue(fateString.contains("Frame type: data"));
        assertTrue(fateString.contains("Frame length: 16"));
        assertTrue(fateString.contains(
                "61 62 63 64 65 66 67 68 00 01 02 03 04 05 06 07")); // hex dump
        // TODO(quiche): uncomment this, once b/27975149 is fixed.
        // assertTrue(fateString.contains("abcdefgh........"));  // hex dump

        // FrameTypeMappings omitted, as they're the same as for TX.

        FateMapping[] fateMappings = new FateMapping[] {
                new FateMapping(WifiLoggerHal.RX_PKT_FATE_SUCCESS, "success"),
                new FateMapping(WifiLoggerHal.RX_PKT_FATE_FW_QUEUED, "firmware queued"),
                new FateMapping(
                        WifiLoggerHal.RX_PKT_FATE_FW_DROP_FILTER, "firmware dropped (filter)"),
                new FateMapping(WifiLoggerHal.RX_PKT_FATE_FW_DROP_INVALID,
                        "firmware dropped (invalid frame)"),
                new FateMapping(
                        WifiLoggerHal.RX_PKT_FATE_FW_DROP_NOBUFS, "firmware dropped (no bufs)"),
                new FateMapping(
                        WifiLoggerHal.RX_PKT_FATE_FW_DROP_OTHER, "firmware dropped (other)"),
                new FateMapping(WifiLoggerHal.RX_PKT_FATE_DRV_QUEUED, "driver queued"),
                new FateMapping(
                        WifiLoggerHal.RX_PKT_FATE_DRV_DROP_FILTER, "driver dropped (filter)"),
                new FateMapping(WifiLoggerHal.RX_PKT_FATE_DRV_DROP_INVALID,
                        "driver dropped (invalid frame)"),
                new FateMapping(
                        WifiLoggerHal.RX_PKT_FATE_DRV_DROP_NOBUFS, "driver dropped (no bufs)"),
                new FateMapping(WifiLoggerHal.RX_PKT_FATE_DRV_DROP_OTHER, "driver dropped (other)"),
                new FateMapping((byte) 42, "42")
        };
        for (FateMapping fateMapping : fateMappings) {
            fateReport = new WifiNative.RxFateReport(
                    fateMapping.mFateNumber,
                    driverTimestampUSec,
                    WifiLoggerHal.FRAME_TYPE_80211_MGMT,
                    frameBytes
            );
            assertTrue(fateReport.toString().contains("Frame fate: " + fateMapping.mExpectedText));
        }
    }


    /**
     * Verifies that startPktFateMonitoring returns false when HAL is not started.
     */
    @Test
    public void testStartPktFateMonitoringReturnsFalseWhenHalIsNotStarted() {
        assertFalse(mWifiNative.isHalStarted());
        assertFalse(mWifiNative.startPktFateMonitoring());
    }

    /**
     * Verifies that getTxPktFates returns error when HAL is not started.
     */
    @Test
    public void testGetTxPktFatesReturnsErrorWhenHalIsNotStarted() {
        WifiNative.TxFateReport[] fateReports = null;
        assertFalse(mWifiNative.isHalStarted());
        assertFalse(mWifiNative.getTxPktFates(fateReports));
    }

    /**
     * Verifies that getRxPktFates returns error when HAL is not started.
     */
    @Test
    public void testGetRxPktFatesReturnsErrorWhenHalIsNotStarted() {
        WifiNative.RxFateReport[] fateReports = null;
        assertFalse(mWifiNative.isHalStarted());
        assertFalse(mWifiNative.getRxPktFates(fateReports));
    }

    // TODO(quiche): Add tests for the success cases (when HAL has been started). Specifically:
    // - testStartPktFateMonitoringCallsHalIfHalIsStarted()
    // - testGetTxPktFatesCallsHalIfHalIsStarted()
    // - testGetRxPktFatesCallsHalIfHalIsStarted()
    //
    // Adding these tests is difficult to do at the moment, because we can't mock out the HAL
    // itself. Also, we can't mock out the native methods, because those methods are private.
    // b/28005116.


}
