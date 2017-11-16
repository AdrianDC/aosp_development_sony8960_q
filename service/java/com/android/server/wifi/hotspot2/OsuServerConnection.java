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

import android.net.Network;
import android.util.Log;

import java.io.IOException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Provides methods to interface with the OSU server
 */
public class OsuServerConnection {
    private static final String TAG = "OsuServerConnection";

    private static final String HTTP = "http";
    private static final String HTTPS = "https";

    private SSLSocketFactory mSocketFactory;
    private URL mUrl;
    private Network mNetwork;
    private WFATrustManager mTrustManager;
    private HttpsURLConnection mUrlConnection = null;
    private PasspointProvisioner.OsuServerCallbacks mOsuServerCallbacks;
    private boolean mSetupComplete = false;
    private boolean mVerboseLoggingEnabled = false;

    /**
     * Sets up callback for event
     * @param callbacks OsuServerCallbacks to be invoked for server related events
     */
    public void setEventCallback(PasspointProvisioner.OsuServerCallbacks callbacks) {
        mOsuServerCallbacks = callbacks;
    }

    /**
     * Initialize socket factory for server connection using HTTPS
     * @param tlsVersion String indicating the TLS version that will be used
     */
    public void init(String tlsVersion) {
        // TODO(sohanirao) : Create and pass in a custom WFA Keystore
        try {
            // TODO(sohanirao) : Use the custom key store instead
            mTrustManager = new WFATrustManager(KeyStore.getInstance("AndroidCAStore"));
            SSLContext tlsContext = SSLContext.getInstance(tlsVersion);
            tlsContext.init(null, new TrustManager[] { mTrustManager }, null);
            mSocketFactory = tlsContext.getSocketFactory();
        } catch (KeyStoreException | NoSuchAlgorithmException | KeyManagementException e) {
            Log.w(TAG, "Initialization failed");
            e.printStackTrace();
            return;
        }
        mSetupComplete = true;
    }

    /**
     * Provides the capability to run OSU server validation
     * @return boolean true if capability available
     */
    public boolean canValidateServer() {
        return mSetupComplete;
    }

    /**
     * Enables verbose logging
     * @param verbose a value greater than zero enables verbose logging
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0 ? true : false;
    }

    /**
     * Connect to the OSU server
     * @param url Osu Server's URL
     * @param network current network connection
     * @return boolean value, true if connection was successful
     *
     * Relies on the caller to ensure that the capability to validate the OSU
     * Server is available.
     */
    public boolean connect(URL url, Network network) {
        mNetwork = network;
        mUrl = url;
        HttpsURLConnection urlConnection = null;
        try {
            urlConnection = (HttpsURLConnection) mNetwork.openConnection(mUrl);
            urlConnection.setSSLSocketFactory(mSocketFactory);
            urlConnection.connect();
        } catch (IOException e) {
            Log.e(TAG, "Unable to establish a URL connection");
            e.printStackTrace();
            return false;
        }
        mUrlConnection = urlConnection;
        return true;
    }

    /**
     * Validate the OSU server
     */
    public boolean validateProvider(String friendlyName) {
        X509Certificate[] certs = mTrustManager.getTrustChain();
        if (certs == null) {
            return false;
        }
        // TODO(sohanirao) : Validate friendly name
        return true;
    }

    /**
     * Clean up
     */
    public void cleanup() {
        mUrlConnection.disconnect();
    }

    private class WFATrustManager implements X509TrustManager {
        private X509Certificate[] mTrustChain;
        KeyStore mKeyStore;

        WFATrustManager(KeyStore ks) {
            mKeyStore = ks;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "checkClientTrusted " + authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "checkServerTrusted " + authType);
            }
            boolean certsValid = false;
            for (X509Certificate cert : chain) {
                // TODO(sohanirao) : Validation of certs
                certsValid = true;
            }
            mTrustChain = chain;
            if (mOsuServerCallbacks != null) {
                mOsuServerCallbacks.onServerValidationStatus(
                        mOsuServerCallbacks.getSessionId(), certsValid);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "getAcceptedIssuers ");
            }
            return null;
        }

        public X509Certificate[] getTrustChain() {
            return mTrustChain;
        }
    }
}

