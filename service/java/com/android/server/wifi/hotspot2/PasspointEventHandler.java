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

import android.util.Base64;
import android.util.Log;

import com.android.server.wifi.WifiNative;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * This class handles passpoint specific interactions with the AP, such as ANQP
 * elements requests, passpoint icon requests, and wireless network management
 * event notifications.
 */
public class PasspointEventHandler {
    private final WifiNative mSupplicantHook;
    private final Callbacks mCallbacks;

    private static final int ICON_CHUNK_SIZE = 1400;  // 2K*3/4 - overhead

    /**
     * Interface to be implemented by the client to receive callbacks for passpoint
     * related events.
     */
    public interface Callbacks {
        /**
         * Invoked on received of ANQP response. |anqpElements| will be null on failure.
         * @param bssid BSSID of the AP
         * @param anqpElements ANQP elements to be queried
         */
        void onANQPResponse(long bssid,
                            Map<Constants.ANQPElementType, ANQPElement> anqpElements);

        /**
         * Invoked on received of icon response. |filename| and |data| will be null
         * on failure.
         * @param bssid BSSID of the AP
         * @param filename Name of the icon file
         * @data icon data bytes
         */
        void onIconResponse(long bssid, String filename, byte[] data);

        /**
         * Invoked on received of Hotspot 2.0 Wireless Network Management frame.
         * @param data Wireless Network Management frame data
         */
        void onWnmFrameReceived(WnmData data);
    }

    public PasspointEventHandler(WifiNative supplicantHook, Callbacks callbacks) {
        mSupplicantHook = supplicantHook;
        mCallbacks = callbacks;
    }

    /**
     * Request the specified ANQP elements |elements| from the specified AP |bssid|.
     * @param bssid BSSID of the AP
     * @param elements ANQP elements to be queried
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean requestANQP(long bssid, List<Constants.ANQPElementType> elements) {
        String anqpGet = buildWPSQueryRequest(bssid, elements);
        if (anqpGet == null) {
            return false;
        }
        String result = mSupplicantHook.doCustomSupplicantCommand(anqpGet);
        if (result != null && result.startsWith("OK")) {
            Log.d(Utils.hs2LogTag(getClass()), "ANQP initiated on "
                    + Utils.macToString(bssid) + " (" + anqpGet + ")");
            return true;
        }
        else {
            Log.d(Utils.hs2LogTag(getClass()), "ANQP failed on " +
                    Utils.macToString(bssid) + ": " + result);
            return false;
        }
    }

    /**
     * Request a passpoint icon file |filename| from the specified AP |bssid|.
     * @param bssid BSSID of the AP
     * @param fileName name of the icon file
     * @return true if request is sent successfully, false otherwise
     */
    public boolean requestIcon(long bssid, String fileName) {
        String result = mSupplicantHook.doCustomSupplicantCommand("REQ_HS20_ICON " +
                Utils.macToString(bssid) + " " + fileName);
        return result != null && result.startsWith("OK");
    }

    /**
     * Invoked when ANQP query is completed.
     * TODO(zqiu): currently ANQP completion notification is through WifiMonitor,
     * this shouldn't be needed once we switch over to wificond for ANQP requests.
     * @param anqpEvent ANQP result data retrieved. ANQP elements could be empty in the event to
     *                  indicate any failures.
     */
    public void notifyANQPDone(AnqpEvent anqpEvent) {
        if (anqpEvent == null) return;
        mCallbacks.onANQPResponse(anqpEvent.getBssid(), anqpEvent.getElements());
    }

    /**
     * Invoked when icon query is completed.
     * TODO(zqiu): currently icon completion notification is through WifiMonitor,
     * this shouldn't be needed once we switch over to wificond for icon requests.
     * @param bssid BSSID of the AP
     * @param iconEvent icon event data
     */
    public void notifyIconDone(long bssid, IconEvent iconEvent) {
        String filename = null;
        byte[] data = null;
        if (iconEvent != null) {
            try {
                data = retrieveIcon(iconEvent);
                filename = iconEvent.getFileName();
            } catch (IOException ioe) {
                Log.e(Utils.hs2LogTag(getClass()), "Failed to retrieve icon: " +
                        ioe.toString() + ": " + iconEvent.getFileName());
            }
        }
        mCallbacks.onIconResponse(bssid, filename, data);
    }

    /**
     * Invoked when a Wireless Network Management (WNM) frame is received.
     * TODO(zqiu): currently WNM frame notification is through WifiMonitor,
     * this shouldn't be needed once we switch over to wificond for WNM frame monitoring.
     * @param data WNM frame data
     */
    public void notifyWnmFrameReceived(WnmData data) {
        mCallbacks.onWnmFrameReceived(data);
    }

    /**
     * Build a wpa_supplicant ANQP query command
     * @param bssid BSSID of the AP to be queried
     * @param querySet elements to query
     * @return A command string.
     */
    private static String buildWPSQueryRequest(long bssid,
                                               List<Constants.ANQPElementType> querySet) {

        boolean baseANQPElements = Constants.hasBaseANQPElements(querySet);
        StringBuilder sb = new StringBuilder();
        if (baseANQPElements) {
            sb.append("ANQP_GET ");
        }
        else {
            // ANQP_GET does not work for a sole hs20:8 (OSU) query
            sb.append("HS20_ANQP_GET ");
        }
        sb.append(Utils.macToString(bssid)).append(' ');

        boolean first = true;
        for (Constants.ANQPElementType elementType : querySet) {
            if (first) {
                first = false;
            }
            else {
                sb.append(',');
            }

            Integer id = Constants.getANQPElementID(elementType);
            if (id != null) {
                sb.append(id);
            }
            else {
                id = Constants.getHS20ElementID(elementType);
                if (baseANQPElements) {
                    sb.append("hs20:");
                }
                sb.append(id);
            }
        }

        return sb.toString();
    }

    private byte[] retrieveIcon(IconEvent iconEvent) throws IOException {
        byte[] iconData = new byte[iconEvent.getSize()];
        try {
            int offset = 0;
            while (offset < iconEvent.getSize()) {
                int size = Math.min(iconEvent.getSize() - offset, ICON_CHUNK_SIZE);

                String command = String.format("GET_HS20_ICON %s %s %d %d",
                        Utils.macToString(iconEvent.getBSSID()), iconEvent.getFileName(),
                        offset, size);
                Log.d(Utils.hs2LogTag(getClass()), "Issuing '" + command + "'");
                String response = mSupplicantHook.doCustomSupplicantCommand(command);
                if (response == null) {
                    throw new IOException("No icon data returned");
                }

                try {
                    byte[] fragment = Base64.decode(response, Base64.DEFAULT);
                    if (fragment.length == 0) {
                        throw new IOException("Null data for '" + command + "': " + response);
                    }
                    if (fragment.length + offset > iconData.length) {
                        throw new IOException("Icon chunk exceeds image size");
                    }
                    System.arraycopy(fragment, 0, iconData, offset, fragment.length);
                    offset += fragment.length;
                } catch (IllegalArgumentException iae) {
                    throw new IOException("Failed to parse response to '" + command
                            + "': " + response);
                }
            }
            if (offset != iconEvent.getSize()) {
                Log.w(Utils.hs2LogTag(getClass()), "Partial icon data: " + offset +
                        ", expected " + iconEvent.getSize());
            }
        }
        finally {
            // Delete the icon file in supplicant.
            Log.d(Utils.hs2LogTag(getClass()), "Deleting icon for " + iconEvent);
            String result = mSupplicantHook.doCustomSupplicantCommand("DEL_HS20_ICON " +
                    Utils.macToString(iconEvent.getBSSID()) + " " + iconEvent.getFileName());
            Log.d(Utils.hs2LogTag(getClass()), "Result: " + result);
        }

        return iconData;
    }
}
