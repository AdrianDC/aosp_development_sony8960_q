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

import static org.junit.Assert.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import android.net.Network;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * Unit tests for {@link com.android.server.wifi.hotspot2.OsuServerConnection}.
 */
@SmallTest
public class OsuServerConnectionTest {
    private static final String TAG = "OsuServerConnectionTest";

    private static final String TEST_URL = "http://www.example.com/";
    private static final String TLS_VERSION = "TLSv1";
    private static final String TLS_VERSION_INVALID = "xx";
    private static final int ENABLE_VERBOSE_LOGGING = 1;
    private static final int TEST_SESSION_ID = 1;

    private OsuServerConnection mOsuServerConnection;
    private URL mServerUrl;

    @Mock PasspointProvisioner.OsuServerCallbacks mOsuServerCallbacks;
    @Mock Network mNetwork;
    @Mock HttpsURLConnection mUrlConnection;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mOsuServerConnection = new OsuServerConnection();
        mOsuServerConnection.enableVerboseLogging(ENABLE_VERBOSE_LOGGING);
        when(mOsuServerCallbacks.getSessionId()).thenReturn(TEST_SESSION_ID);
        when(mNetwork.openConnection(any(URL.class))).thenReturn(mUrlConnection);
        try {
            mServerUrl = new URL(TEST_URL);
        } catch (MalformedURLException e) {
            // Test code, will not happen
        }
    }

    /**
     * Verifies initialization and connecting to the OSU server
     */
    @Test
    public void verifyInitAndConnect() {
        mOsuServerConnection.init(TLS_VERSION);
        mOsuServerConnection.setEventCallback(mOsuServerCallbacks);
        assertTrue(mOsuServerConnection.connect(mServerUrl, mNetwork));
    }

    /**
     * Verifies initialization of the HTTPS connection with invalid TLS algorithm
     */
    @Test
    public void verifyInitFailure() {
        mOsuServerConnection.init(TLS_VERSION_INVALID);
        mOsuServerConnection.setEventCallback(mOsuServerCallbacks);
        assertFalse(mOsuServerConnection.canValidateServer());
    }

    /**
     * Verifies initialization and opening URL connection failed on the network
     */
    @Test
    public void verifyInitAndNetworkOpenURLConnectionFailed() throws IOException {
        mOsuServerConnection.init(TLS_VERSION);
        mOsuServerConnection.setEventCallback(mOsuServerCallbacks);
        doThrow(new IOException()).when(mNetwork).openConnection(any(URL.class));
        assertFalse(mOsuServerConnection.connect(mServerUrl, mNetwork));
    }

    /**
     * Verifies initialization and connection failure to OSU server
     */
    @Test
    public void verifyInitAndServerConnectFailure() throws IOException {
        mOsuServerConnection.init(TLS_VERSION);
        mOsuServerConnection.setEventCallback(mOsuServerCallbacks);
        doThrow(new IOException()).when(mUrlConnection).connect();
        assertFalse(mOsuServerConnection.connect(mServerUrl, mNetwork));
    }
}
