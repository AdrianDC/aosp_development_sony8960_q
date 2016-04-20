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

package com.android.server.wifi.util;

import static org.junit.Assert.assertEquals;

import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.WifiLoggerHal;

import org.junit.Test;

/**
 * Unit tests for {@link com.android.server.wifi.util.FrameParser}.
 */
@SmallTest
public class FrameParserTest {

    private static final byte[] TEST_EAPOL_1_OF_4_FRAME_BYTES = new byte[] {
            (byte) 0x7C, (byte) 0x7D, (byte) 0x3D, (byte) 0x51, (byte) 0x10, (byte) 0xDC,
            (byte) 0x08, (byte) 0x96, (byte) 0xD7, (byte) 0x8B, (byte) 0xE3, (byte) 0xFB,
            (byte) 0x88, (byte) 0x8E, (byte) 0x02, (byte) 0x03, (byte) 0x00, (byte) 0x5F,
            (byte) 0x02, (byte) 0x00, (byte) 0x8A, (byte) 0x00, (byte) 0x10, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x01, (byte) 0xF2, (byte) 0x0D, (byte) 0xFA, (byte) 0x35, (byte) 0x89,
            (byte) 0x9C, (byte) 0xA8, (byte) 0x8C, (byte) 0x14, (byte) 0xD9, (byte) 0x3F,
            (byte) 0xC9, (byte) 0x62, (byte) 0x11, (byte) 0x39, (byte) 0xC3, (byte) 0x34,
            (byte) 0xE1, (byte) 0x00, (byte) 0x09, (byte) 0xE3, (byte) 0x9C, (byte) 0x0C,
            (byte) 0x32, (byte) 0xFE, (byte) 0x7F, (byte) 0x79, (byte) 0x29, (byte) 0x3E,
            (byte) 0x6C, (byte) 0xF2, (byte) 0x57, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00};
    private static final byte TEST_EAPOL_1_OF_4_FRAME_TYPE =
            WifiLoggerHal.FRAME_TYPE_ETHERNET_II;
    private static final String TEST_EAPOL_1_OF_4_FRAME_PROTOCOL_STRING = "EAPOL";
    private static final String TEST_EAPOL_1_OF_4_FRAME_TYPE_STRING = "Pairwise Key message 1/4";

    private static final byte[] TEST_EAPOL_2_OF_4_FRAME_BYTES = new byte[] {
            (byte) 0x08, (byte) 0x96, (byte) 0xD7, (byte) 0x8B, (byte) 0xE3, (byte) 0xFB,
            (byte) 0x7C, (byte) 0x7D, (byte) 0x3D, (byte) 0x51, (byte) 0x10, (byte) 0xDC,
            (byte) 0x88, (byte) 0x8E, (byte) 0x01, (byte) 0x03, (byte) 0x00, (byte) 0x75,
            (byte) 0x02, (byte) 0x01, (byte) 0x0A, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x01, (byte) 0x42, (byte) 0xAE, (byte) 0x33, (byte) 0x61, (byte) 0xF8,
            (byte) 0x38, (byte) 0x28, (byte) 0x81, (byte) 0x70, (byte) 0xD8, (byte) 0xA5,
            (byte) 0xDD, (byte) 0x90, (byte) 0xB1, (byte) 0x8E, (byte) 0xEB, (byte) 0x58,
            (byte) 0xF1, (byte) 0x0A, (byte) 0xE4, (byte) 0xA1, (byte) 0x93, (byte) 0x34,
            (byte) 0xE1, (byte) 0x4F, (byte) 0x90, (byte) 0x85, (byte) 0xA0, (byte) 0x21,
            (byte) 0x95, (byte) 0xC8, (byte) 0xD8, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x53,
            (byte) 0x12, (byte) 0xE4, (byte) 0x97, (byte) 0xBC, (byte) 0xFF, (byte) 0x15,
            (byte) 0x45, (byte) 0xCF, (byte) 0x4C, (byte) 0xC0, (byte) 0xB8, (byte) 0xBA,
            (byte) 0x30, (byte) 0x5F, (byte) 0x6C, (byte) 0x00, (byte) 0x16, (byte) 0x30,
            (byte) 0x14, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x0F, (byte) 0xAC,
            (byte) 0x04, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x0F, (byte) 0xAC,
            (byte) 0x04, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x0F, (byte) 0xAC,
            (byte) 0x02, (byte) 0x80, (byte) 0x00};
    private static final byte TEST_EAPOL_2_OF_4_FRAME_TYPE =
            WifiLoggerHal.FRAME_TYPE_ETHERNET_II;
    private static final String TEST_EAPOL_2_OF_4_FRAME_PROTOCOL_STRING = "EAPOL";
    private static final String TEST_EAPOL_2_OF_4_FRAME_TYPE_STRING = "Pairwise Key message 2/4";

    private static final byte[] TEST_PROBE_RESPONSE_FRAME_BYTES = new byte[] {
            (byte) 0x50, (byte) 0x08, (byte) 0x3a, (byte) 0x01, (byte) 0x54, (byte) 0x27,
            (byte) 0x1e, (byte) 0xf2, (byte) 0xcd, (byte) 0x0f, (byte) 0x10, (byte) 0x6f,
            (byte) 0x3f, (byte) 0xf6, (byte) 0x89, (byte) 0x0e, (byte) 0x10, (byte) 0x6f,
            (byte) 0x3f, (byte) 0xf6, (byte) 0x89, (byte) 0x0e, (byte) 0x60, (byte) 0xc4,
            (byte) 0x4a, (byte) 0xaa, (byte) 0x2a, (byte) 0x0d, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x64, (byte) 0x00, (byte) 0x11, (byte) 0x04,
            (byte) 0x00, (byte) 0x16, (byte) 0x77, (byte) 0x7a, (byte) 0x72, (byte) 0x5f,
            (byte) 0x68, (byte) 0x70, (byte) 0x5f, (byte) 0x67, (byte) 0x34, (byte) 0x35,
            (byte) 0x30, (byte) 0x68, (byte) 0x5f, (byte) 0x67, (byte) 0x5f, (byte) 0x63,
            (byte) 0x68, (byte) 0x35, (byte) 0x5f, (byte) 0x77, (byte) 0x70, (byte) 0x61,
            (byte) 0x01, (byte) 0x08, (byte) 0x82, (byte) 0x84, (byte) 0x8b, (byte) 0x96,
            (byte) 0x0c, (byte) 0x12, (byte) 0x18, (byte) 0x24, (byte) 0x03, (byte) 0x01,
            (byte) 0x05, (byte) 0x2a, (byte) 0x01, (byte) 0x04, (byte) 0x32, (byte) 0x04,
            (byte) 0x30, (byte) 0x48, (byte) 0x60, (byte) 0x6c, (byte) 0x30, (byte) 0x18,
            (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x0f, (byte) 0xac, (byte) 0x02,
            (byte) 0x02, (byte) 0x00, (byte) 0x00, (byte) 0x0f, (byte) 0xac, (byte) 0x04,
            (byte) 0x00, (byte) 0x0f, (byte) 0xac, (byte) 0x02, (byte) 0x01, (byte) 0x00,
            (byte) 0x00, (byte) 0x0f, (byte) 0xac, (byte) 0x02, (byte) 0x0c, (byte) 0x00,
            (byte) 0xdd, (byte) 0x18, (byte) 0x00, (byte) 0x50, (byte) 0xf2, (byte) 0x02,
            (byte) 0x01, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0xa4,
            (byte) 0x00, (byte) 0x00, (byte) 0x27, (byte) 0xa4, (byte) 0x00, (byte) 0x00,
            (byte) 0x42, (byte) 0x43, (byte) 0x5e, (byte) 0x00, (byte) 0x62, (byte) 0x32,
            (byte) 0x2f, (byte) 0x00, (byte) 0xdd, (byte) 0x70, (byte) 0x00, (byte) 0x50,
            (byte) 0xf2, (byte) 0x04, (byte) 0x10, (byte) 0x4a, (byte) 0x00, (byte) 0x01,
            (byte) 0x10, (byte) 0x10, (byte) 0x44, (byte) 0x00, (byte) 0x01, (byte) 0x02,
            (byte) 0x10, (byte) 0x3b, (byte) 0x00, (byte) 0x01, (byte) 0x03, (byte) 0x10,
            (byte) 0x47, (byte) 0x00, (byte) 0x10, (byte) 0xf3, (byte) 0x1b, (byte) 0x31,
            (byte) 0x7f, (byte) 0x8c, (byte) 0x25, (byte) 0x56, (byte) 0x46, (byte) 0xb5,
            (byte) 0xc9, (byte) 0x5f, (byte) 0x2f, (byte) 0x0b, (byte) 0xcf, (byte) 0x6a,
            (byte) 0x14, (byte) 0x10, (byte) 0x21, (byte) 0x00, (byte) 0x06, (byte) 0x44,
            (byte) 0x44, (byte) 0x2d, (byte) 0x57, (byte) 0x52, (byte) 0x54, (byte) 0x10,
            (byte) 0x23, (byte) 0x00, (byte) 0x0c, (byte) 0x57, (byte) 0x5a, (byte) 0x52,
            (byte) 0x2d, (byte) 0x48, (byte) 0x50, (byte) 0x2d, (byte) 0x47, (byte) 0x34,
            (byte) 0x35, (byte) 0x30, (byte) 0x48, (byte) 0x10, (byte) 0x24, (byte) 0x00,
            (byte) 0x01, (byte) 0x30, (byte) 0x10, (byte) 0x42, (byte) 0x00, (byte) 0x05,
            (byte) 0x31, (byte) 0x32, (byte) 0x33, (byte) 0x34, (byte) 0x35, (byte) 0x10,
            (byte) 0x54, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x06, (byte) 0x00,
            (byte) 0x50, (byte) 0xf2, (byte) 0x04, (byte) 0x00, (byte) 0x01, (byte) 0x10,
            (byte) 0x11, (byte) 0x00, (byte) 0x06, (byte) 0x44, (byte) 0x44, (byte) 0x2d,
            (byte) 0x57, (byte) 0x52, (byte) 0x54, (byte) 0x10, (byte) 0x08, (byte) 0x00,
            (byte) 0x02, (byte) 0x01, (byte) 0x04, (byte) 0x10, (byte) 0x3c, (byte) 0x00,
            (byte) 0x01, (byte) 0x01, (byte) 0x8a, (byte) 0x0e, (byte) 0x06, (byte) 0xcf};
    private static final byte TEST_PROBE_RESPONSE_FRAME_TYPE = WifiLoggerHal.FRAME_TYPE_80211_MGMT;
    private static final String TEST_PROBE_RESPONSE_FRAME_PROTOCOL_STRING = "802.11 Mgmt";
    private static final String TEST_PROBE_RESPONSE_FRAME_TYPE_STRING = "Probe Response";

    /**
     * Test that a probe response frame is parsed correctly.
     */
    @Test
    public void parseProbeResponseFrame() {
        FrameParser parser = new FrameParser(
                TEST_PROBE_RESPONSE_FRAME_TYPE, TEST_PROBE_RESPONSE_FRAME_BYTES);
        assertEquals(TEST_PROBE_RESPONSE_FRAME_PROTOCOL_STRING, parser.mMostSpecificProtocolString);
        assertEquals(TEST_PROBE_RESPONSE_FRAME_TYPE_STRING, parser.mTypeString);
    }

    /**
     * Test that pairwise EAPOL 1/4 and 2/4 frames are parsed correctly.
     */
    @Test
    public void parseEapolFrames() {
        FrameParser parser1 = new FrameParser(
                TEST_EAPOL_1_OF_4_FRAME_TYPE, TEST_EAPOL_1_OF_4_FRAME_BYTES);
        assertEquals(TEST_EAPOL_1_OF_4_FRAME_PROTOCOL_STRING, parser1.mMostSpecificProtocolString);
        assertEquals(TEST_EAPOL_1_OF_4_FRAME_TYPE_STRING, parser1.mTypeString);

        FrameParser parser2 = new FrameParser(
                TEST_EAPOL_2_OF_4_FRAME_TYPE, TEST_EAPOL_2_OF_4_FRAME_BYTES);
        assertEquals(TEST_EAPOL_2_OF_4_FRAME_PROTOCOL_STRING, parser2.mMostSpecificProtocolString);
        assertEquals(TEST_EAPOL_2_OF_4_FRAME_TYPE_STRING, parser2.mTypeString);
    }
}
