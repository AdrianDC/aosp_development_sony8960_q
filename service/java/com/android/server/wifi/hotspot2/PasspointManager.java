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

import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_BSSID;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_DATA;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_FILE;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_BSSID;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_DELAY;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_ESS;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_METHOD;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_URL;
import static android.net.wifi.WifiManager.PASSPOINT_ICON_RECEIVED_ACTION;
import static android.net.wifi.WifiManager.PASSPOINT_WNM_FRAME_RECEIVED_ACTION;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.Constants;

import java.util.Map;

/**
 * Responsible for managing passpoint networks.
 */
public class PasspointManager {
    private final Context mContext;
    private final PasspointEventHandler mHandler;

    private class CallbackHandler implements PasspointEventHandler.Callbacks {
        private final Context mContext;
        CallbackHandler(Context context) {
            mContext = context;
        }

        @Override
        public void onANQPResponse(long bssid,
                Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
            // TO BE IMPLEMENTED.
        }

        @Override
        public void onIconResponse(long bssid, String fileName, byte[] data) {
            Intent intent = new Intent(PASSPOINT_ICON_RECEIVED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(EXTRA_PASSPOINT_ICON_BSSID, bssid);
            intent.putExtra(EXTRA_PASSPOINT_ICON_FILE, fileName);
            if (data != null) {
                intent.putExtra(EXTRA_PASSPOINT_ICON_DATA, data);
            }
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        @Override
        public void onWnmFrameReceived(WnmData event) {
            // %012x HS20-SUBSCRIPTION-REMEDIATION "%u %s", osu_method, url
            // %012x HS20-DEAUTH-IMMINENT-NOTICE "%u %u %s", code, reauth_delay, url
            Intent intent = new Intent(PASSPOINT_WNM_FRAME_RECEIVED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

            intent.putExtra(EXTRA_PASSPOINT_WNM_BSSID, event.getBssid());
            intent.putExtra(EXTRA_PASSPOINT_WNM_URL, event.getUrl());

            if (event.isDeauthEvent()) {
                intent.putExtra(EXTRA_PASSPOINT_WNM_ESS, event.isEss());
                intent.putExtra(EXTRA_PASSPOINT_WNM_DELAY, event.getDelay());
            } else {
                intent.putExtra(EXTRA_PASSPOINT_WNM_METHOD, event.getMethod());
                // TODO(zqiu): set the passpoint matching status with the respect to the
                // current connected network (e.g. HomeProvider, RoamingProvider, None,
                // Declined).
            }
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    public PasspointManager(Context context, WifiInjector wifiInjector) {
        mContext = context;
        mHandler = wifiInjector.makePasspointEventHandler(new CallbackHandler(context));
    }

    /**
     * Notify the completion of an ANQP request.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void notifyANQPDone(long bssid, boolean success) {
        mHandler.notifyANQPDone(bssid, success);
    }

    /**
     * Notify the completion of an icon request.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void notifyIconDone(long bssid, IconEvent iconEvent) {
        mHandler.notifyIconDone(bssid, iconEvent);
    }

    /**
     * Notify the reception of a Wireless Network Management (WNM) frame.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void receivedWnmFrame(WnmData data) {
        mHandler.notifyWnmFrameReceived(data);
    }

    /**
     * Request the specified icon file |fileName| from the specified AP |bssid|.
     * @return true if the request is sent successfully, false otherwise
     */
    public boolean queryPasspointIcon(long bssid, String fileName) {
        return mHandler.requestIcon(bssid, fileName);
    }
}
