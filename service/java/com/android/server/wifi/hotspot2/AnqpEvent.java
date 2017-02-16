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

package com.android.server.wifi.hotspot2;

import android.util.Log;

import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.ANQPParser;
import com.android.server.wifi.hotspot2.anqp.Constants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * This class carries the response of an ANQP request.
 */
public class AnqpEvent {
    private static final String TAG = "AnqpEvent";
    private static final Map<String, Constants.ANQPElementType> sWpsNames = new HashMap<>();

    static {
        sWpsNames.put("anqp_venue_name", Constants.ANQPElementType.ANQPVenueName);
        sWpsNames.put("anqp_roaming_consortium", Constants.ANQPElementType.ANQPRoamingConsortium);
        sWpsNames.put("anqp_ip_addr_type_availability",
                Constants.ANQPElementType.ANQPIPAddrAvailability);
        sWpsNames.put("anqp_nai_realm", Constants.ANQPElementType.ANQPNAIRealm);
        sWpsNames.put("anqp_3gpp", Constants.ANQPElementType.ANQP3GPPNetwork);
        sWpsNames.put("anqp_domain_name", Constants.ANQPElementType.ANQPDomName);
        sWpsNames.put("hs20_operator_friendly_name", Constants.ANQPElementType.HSFriendlyName);
        sWpsNames.put("hs20_wan_metrics", Constants.ANQPElementType.HSWANMetrics);
        sWpsNames.put("hs20_connection_capability", Constants.ANQPElementType.HSConnCapability);
        sWpsNames.put("hs20_osu_providers_list", Constants.ANQPElementType.HSOSUProviders);
    }

    /**
     * Bssid of the access point.
     */
    private final long mBssid;
    /**
     * Map of ANQP element type to the data retrieved from the access point.
     */
    private final Map<Constants.ANQPElementType, ANQPElement> mElements;

    public AnqpEvent(long bssid, Map<Constants.ANQPElementType, ANQPElement> elements) {
        mBssid = bssid;
        mElements = elements;
    }

    /**
     * Creates an ANQP result instance using the passed in scan result data.
     *
     * @param bssid   BSSID of the access point.
     * @param bssData Scan results fetched from wpa_supplicant after ANQP query.
     */
    public static AnqpEvent buildAnqpEvent(long bssid, String bssData) {
        Map<Constants.ANQPElementType, ANQPElement> elements = null;
        try {
            elements = parseWPSData(bssData);
            Log.d(TAG,
                    String.format("Successful ANQP response for %012x: %s",
                            bssid, elements));
        } catch (IOException | BufferUnderflowException e) {
            Log.e(TAG, "Failed to parse ANQP: " + e.toString() + ": " + bssData);
            return null;
        }
        return new AnqpEvent(bssid, elements);
    }

    private static Map<Constants.ANQPElementType, ANQPElement> parseWPSData(String bssInfo)
            throws IOException {
        Map<Constants.ANQPElementType, ANQPElement> elements = new HashMap<>();
        if (bssInfo == null) {
            return elements;
        }
        BufferedReader lineReader = new BufferedReader(new StringReader(bssInfo));
        String line;
        while ((line = lineReader.readLine()) != null) {
            ANQPElement element = buildElement(line);
            if (element != null) {
                elements.put(element.getID(), element);
            }
        }
        return elements;
    }

    private static ANQPElement buildElement(String text) throws ProtocolException {
        int separator = text.indexOf('=');
        if (separator < 0 || separator + 1 == text.length()) {
            return null;
        }

        String elementName = text.substring(0, separator);
        Constants.ANQPElementType elementType = sWpsNames.get(elementName);
        if (elementType == null) {
            return null;
        }

        byte[] payload;
        try {
            payload = Utils.hexToBytes(text.substring(separator + 1));
        } catch (NumberFormatException nfe) {
            Log.e(Utils.hs2LogTag(PasspointEventHandler.class),
                    "Failed to parse hex string");
            return null;
        }
        // Wrap the payload inside a ByteBuffer.
        ByteBuffer buffer = ByteBuffer.wrap(payload);

        return Constants.getANQPElementID(elementType) != null
                ? ANQPParser.parseElement(elementType, buffer)
                : ANQPParser.parseHS20Element(elementType, buffer);
    }

    /**
     * Get the bssid of the access point from which this ANQP result was created.
     */
    public long getBssid() {
        return mBssid;
    }

    /**
     * Get the map of ANQP elements retrieved from the access point.
     */
    public Map<Constants.ANQPElementType, ANQPElement> getElements() {
        return mElements;
    }

}
