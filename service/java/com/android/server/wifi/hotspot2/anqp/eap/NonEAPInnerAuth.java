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

package com.android.server.wifi.hotspot2.anqp.eap;

import com.android.internal.annotations.VisibleForTesting;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * The Non-EAP Inner Authentication Type authentication parameter, IEEE802.11-2012, table 8-188.
 *
 * Format:
 * | Type |
 *    1
 */
public class NonEAPInnerAuth extends AuthParam {
    public static final int AUTH_TYPE_PAP = 1;
    public static final int AUTH_TYPE_CHAP = 2;
    public static final int AUTH_TYPE_MSCHAP = 3;
    public static final int AUTH_TYPE_MSCHAPV2 = 4;

    @VisibleForTesting
    public static final int EXPECTED_LENGTH_VALUE = 1;

    private final int mAuthType;

    public NonEAPInnerAuth(int authType) {
        super(AuthParam.PARAM_TYPE_NON_EAP_INNER_AUTH_TYPE);
        mAuthType = authType;
    }

    /**
     * Parse a NonEAPInnerAuth from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @param length The length of the data
     * @return {@link NonEAPInnerAuth}
     * @throws BufferUnderflowException
     */
    public static NonEAPInnerAuth parse(ByteBuffer payload, int length) throws ProtocolException {
        if (length != EXPECTED_LENGTH_VALUE) {
            throw new ProtocolException("Invalid length: " + length);
        }
        int authType = payload.get() & 0xFF;
        return new NonEAPInnerAuth(authType);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        }
        if (!(thatObject instanceof NonEAPInnerAuth)) {
            return false;
        }
        NonEAPInnerAuth that = (NonEAPInnerAuth) thatObject;
        return mAuthType == that.mAuthType;
    }

    @Override
    public int hashCode() {
        return mAuthType;
    }

    @Override
    public String toString() {
        return "NonEAPInnerAuth{mAuthType=" + mAuthType + "}";
    }
}
