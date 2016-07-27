package com.android.server.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Venue Name ANQP Element, IEEE802.11-2012 section 8.4.4.4
 */
public class VenueNameElement extends ANQPElement {
    private final List<I18Name> mNames;

    public VenueNameElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        if (payload.remaining() < 2)
            throw new ProtocolException("Runt Venue Name");

        // Skip the Venue Info field, which we don't use.
        for (int i = 0; i < Constants.VENUE_INFO_LENGTH; ++i) {
            payload.get();
        }

        mNames = new ArrayList<I18Name>();
        while (payload.hasRemaining()) {
            mNames.add(new I18Name(payload));
        }
    }

    public List<I18Name> getNames() {
        return Collections.unmodifiableList(mNames);
    }

    @Override
    public String toString() {
        return "VenueName{ mNames=" + mNames + "}";
    }
}
