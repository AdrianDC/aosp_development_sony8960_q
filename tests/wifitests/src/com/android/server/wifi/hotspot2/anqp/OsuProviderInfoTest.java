/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wifi.hotspot2.anqp;

import static org.junit.Assert.assertEquals;

import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.anqp.OsuProviderInfo}.
 */
@SmallTest
public class OsuProviderInfoTest {
    /**
     * Verify that BufferUnderflowException will be thrown when parsing an empty buffer.
     * @throws Exception
     */
    @Test(expected = BufferUnderflowException.class)
    public void parseEmptyBuffer() throws Exception {
        OsuProviderInfo.parse(ByteBuffer.allocate(0));
    }

    /**
     * Verify that BufferUnderflowException will be thrown when parsing a truncated buffer
     * (missing a byte at the end).
     *
     * @throws Exception
     */
    @Test(expected = BufferUnderflowException.class)
    public void parseTruncatedBuffer() throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(
                OsuProviderInfoTestUtil.TEST_OSU_PROVIDER_INFO_RAW_BYTES);
        buffer.limit(buffer.remaining() - 1);
        OsuProviderInfo.parse(buffer);
    }

    /**
     * Verify that ProtocolException will be thrown when parsing a buffer containing an
     * invalid length value.
     *
     * @throws Exception
     */
    @Test(expected = ProtocolException.class)
    public void parseBufferWithInvalidLength() throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(
                OsuProviderInfoTestUtil.TEST_OSU_PROVIDER_INFO_RAW_BYTES_WITH_INVALID_LENGTH);
        OsuProviderInfo.parse(buffer);
    }

    /**
     * Verify that an expected {@link OsuProviderInfo} will be returned when parsing a buffer
     * containing pre-defined test data.
     *
     * @throws Exception
     */
    @Test
    public void parseBufferWithTestData() throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(
                OsuProviderInfoTestUtil.TEST_OSU_PROVIDER_INFO_RAW_BYTES);
        assertEquals(OsuProviderInfoTestUtil.TEST_OSU_PROVIDER_INFO,
                OsuProviderInfo.parse(buffer));
    }
}
