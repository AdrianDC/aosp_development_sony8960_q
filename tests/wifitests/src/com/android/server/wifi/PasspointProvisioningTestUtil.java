/*
 * Copyright 2017 The Android Open Source Project
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

import android.graphics.drawable.Icon;
import android.net.Uri;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.OsuProvider;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Helper for creating and populating WifiConfigurations in unit tests.
 */
public class PasspointProvisioningTestUtil {
    /**
     * These are constants used to generate predefined OsuProvider.
     */
    public static final WifiSsid TEST_SSID =
            WifiSsid.createFromByteArray("TEST SSID".getBytes(StandardCharsets.UTF_8));
    public static final String TEST_FRIENDLY_NAME = "Friendly Name";
    public static final String TEST_SERVICE_DESCRIPTION = "Dummy Service";
    public static final Uri TEST_SERVER_URI = Uri.parse("https://test.com");
    public static final Uri INVALID_SERVER_URI = Uri.parse("abcd");
    public static final String TEST_NAI = "test.access.com";
    public static final List<Integer> TEST_METHOD_LIST =
            Arrays.asList(OsuProvider.METHOD_SOAP_XML_SPP);
    public static final Icon TEST_ICON = Icon.createWithData(new byte[10], 0, 10);

    /**
     * Construct a {@link android.net.wifi.hotspot2.OsuProvider}.
     * @param openOsuAP indicates if the OSU AP belongs to an open or OSEN network
     * @return the constructed {@link android.net.wifi.hotspot2.OsuProvider}
     */
    public static OsuProvider generateOsuProvider(boolean openOsuAP) {
        if (openOsuAP) {
            return new OsuProvider(TEST_SSID, TEST_FRIENDLY_NAME, TEST_SERVICE_DESCRIPTION,
                    TEST_SERVER_URI, null, TEST_METHOD_LIST, TEST_ICON);
        } else {
            return new OsuProvider(TEST_SSID, TEST_FRIENDLY_NAME, TEST_SERVICE_DESCRIPTION,
                    TEST_SERVER_URI, TEST_NAI, TEST_METHOD_LIST, TEST_ICON);
        }
    }

    /**
     * Construct a {@link android.net.wifi.hotspot2.OsuProvider} with invalid server URL
     * @return the constructed {@link android.net.wifi.hotspot2.OsuProvider}
     */
    public static OsuProvider generateInvalidServerUrlOsuProvider() {
        return new OsuProvider(TEST_SSID, TEST_FRIENDLY_NAME, TEST_SERVICE_DESCRIPTION,
                INVALID_SERVER_URI, null, TEST_METHOD_LIST, TEST_ICON);
    }
}
