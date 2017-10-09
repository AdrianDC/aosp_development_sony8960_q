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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

/**
 * Responsible for setup/monitor on Wi-Fi state and connection to the OSU AP.
 */
public class OsuNetworkConnection {
    private static final String TAG = "OsuNetworkConnection";

    private final Context mContext;

    private boolean mVerboseLoggingEnabled = false;
    private WifiManager mWifiManager;
    private Callbacks mCallbacks;
    private boolean mConnected = false;
    private int mNetworkId = -1;
    private boolean mWifiEnabled = false;

    /**
     * Callbacks on Wi-Fi connection state changes.
     */
    public interface Callbacks {
        /**
         * Invoked when network connection is established with IP connectivity.
         *
         * @param network {@link Network} associated with the connected network.
         */
        void onConnected(Network network);

        /**
         * Invoked when the targeted network is disconnected.
         */
        void onDisconnected();

        /**
         * Invoked when a timer tracking connection request is not reset by successfull connection.
         */
        void onTimeOut();

        /**
         * Invoked when Wifi is enabled.
         */
        void onWifiEnabled();

        /**
         * Invoked when Wifi is disabled.
         */
        void onWifiDisabled();
    }

    /**
     * Create an instance of {@link NetworkConnection} for the specified Wi-Fi network.
     * @param context The application context
     */
    public OsuNetworkConnection(Context context) {
        mContext = context;
    }

    /**
     * Called to initialize tracking of wifi state and network events by registering for the
     * corresponding intents.
     */
    public void init(Handler handler) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    handleNetworkStateChanged(
                            intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO),
                            intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO));
                } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                    int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN);
                    handleWifiStateChanged(state);
                }
            }
        };
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mContext.registerReceiver(receiver, filter, null, handler);
        mWifiEnabled = mWifiManager.isWifiEnabled();
    }

    /**
     * Disconnect, if required in the two cases
     * - still connected to the OSU AP
     * - connection to OSU AP was requested and in progress
     */
    public void disconnectIfNeeded() {
        if (mNetworkId < 0) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "No connection to tear down");
            }
            return;
        }
        mWifiManager.removeNetwork(mNetworkId);
        mNetworkId = -1;
        mConnected = false;
        if (mCallbacks != null) {
            mCallbacks.onDisconnected();
        }
    }

    /**
     * Register for network and Wifi state events
     * @param callbacks The callbacks to be invoked on network change events
     */
    public void setEventCallback(Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    /**
     * Connect to a OSU Wi-Fi network specified by the given SSID. The security type of the Wi-Fi
     * network is either open or OSEN (OSU Server-only authenticated layer 2 Encryption Network).
     * When network access identifier is provided, OSEN is used.
     *
     * @param ssid The SSID to connect to
     * @param nai Network access identifier of the network
     *
     * @return boolean true if connection was successfully initiated
     */
    public boolean connect(WifiSsid ssid, String nai) {
        if (mConnected) {
            if (mVerboseLoggingEnabled) {
                // Already connected
                Log.v(TAG, "Connect called twice");
            }
            return true;
        }
        if (!mWifiManager.isWifiEnabled()) {
            Log.w(TAG, "Wifi is not enabled");
            return false;
        }
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + ssid.toString() + "\"";
        if (TextUtils.isEmpty(nai)) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            // TODO(sohanirao): Handle OSEN.
            Log.w(TAG, "OSEN not supported");
            return false;
        }
        mNetworkId = mWifiManager.addNetwork(config);
        if (mNetworkId < 0) {
            Log.e(TAG, "Unable to add network");
            return false;
        }
        if (!mWifiManager.enableNetwork(mNetworkId, true)) {
            Log.e(TAG, "Unable to enable network " + mNetworkId);
            disconnectIfNeeded();
            return false;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Current network ID " + mNetworkId);
        }
        // TODO(sohanirao): setup alarm to time out the connection attempt.
        return true;
    }

    /**
     * Method to update logging level in this class
     * @param verbose more than 0 enables verbose logging
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0 ? true : false;
    }

    /**
     * Handle network state changed events.
     *
     * @param networkInfo {@link NetworkInfo} indicating the current network state
     * @param wifiInfo {@link WifiInfo} associated with the current network when connected
     */
    private void handleNetworkStateChanged(NetworkInfo networkInfo, WifiInfo wifiInfo) {
        if (networkInfo == null) {
            Log.w(TAG, "NetworkInfo not provided for network state changed event");
            return;
        }
        switch (networkInfo.getDetailedState()) {
            case CONNECTED:
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Connected event received");
                }
                if (wifiInfo == null) {
                    Log.w(TAG, "WifiInfo not provided for network state changed event");
                    return;
                }
                handleConnectedEvent(wifiInfo);
                break;
            case DISCONNECTED:
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Disconnected event received");
                }
                disconnectIfNeeded();
                break;
            default:
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Ignore uninterested state: " + networkInfo.getDetailedState());
                }
                break;
        }
    }

    /**
     * Handle network connected event.
     *
     * @param wifiInfo {@link WifiInfo} associated with the current connection
     */
    private void handleConnectedEvent(WifiInfo wifiInfo) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "handleConnectedEvent " + wifiInfo.getNetworkId());
        }
        if (wifiInfo.getNetworkId() != mNetworkId) {
            disconnectIfNeeded();
            return;
        }
        if (!mConnected) {
            mConnected = true;
            if (mCallbacks != null) {
                mCallbacks.onConnected(mWifiManager.getCurrentNetwork());
            }
        }
    }

    /**
     * Handle Wifi state change event
     */
    private void handleWifiStateChanged(int state) {
        if (state == WifiManager.WIFI_STATE_DISABLED && mWifiEnabled) {
            mWifiEnabled = false;
            if (mCallbacks != null) mCallbacks.onWifiDisabled();
        }
        if (state == WifiManager.WIFI_STATE_ENABLED && !mWifiEnabled) {
            mWifiEnabled = true;
            if (mCallbacks != null) mCallbacks.onWifiEnabled();
        }
    }
}
