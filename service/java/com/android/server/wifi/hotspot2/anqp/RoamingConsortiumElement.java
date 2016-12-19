package com.android.server.wifi.hotspot2.anqp;

import com.android.server.wifi.hotspot2.Utils;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.android.server.wifi.hotspot2.anqp.Constants.BYTE_MASK;

import com.android.server.wifi.ByteBufferReader;

/**
 * The Roaming Consortium ANQP Element, IEEE802.11-2012 section 8.4.4.7
 */
public class RoamingConsortiumElement extends ANQPElement {

    private final List<Long> mOis;

    public RoamingConsortiumElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        mOis = new ArrayList<Long>();

        while (payload.hasRemaining()) {
            int length = payload.get() & BYTE_MASK;
            if (length > payload.remaining()) {
                throw new ProtocolException("Bad OI length: " + length);
            }
            mOis.add(ByteBufferReader.readInteger(payload, ByteOrder.BIG_ENDIAN, length));
        }
    }

    public List<Long> getOIs() {
        return Collections.unmodifiableList(mOis);
    }

    @Override
    public String toString() {
        return "RoamingConsortium{mOis=[" + Utils.roamingConsortiumsToString(mOis) + "]}";
    }
}
