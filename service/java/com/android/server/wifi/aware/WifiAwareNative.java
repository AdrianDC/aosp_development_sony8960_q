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

package com.android.server.wifi.aware;

import android.net.wifi.aware.ConfigRequest;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.WifiAwareCharacteristics;
import android.net.wifi.aware.WifiAwareDiscoverySessionCallback;
import android.os.Bundle;
import android.util.Log;

import com.android.server.wifi.WifiNative;

import libcore.util.HexEncoding;

import java.util.Arrays;

/**
 * Native calls to access the Wi-Fi Aware HAL.
 *
 * Relies on WifiNative to perform the actual HAL registration.
 */
public class WifiAwareNative {
    private static final String TAG = "WifiAwareNative";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final int WIFI_SUCCESS = 0;

    private static WifiAwareNative sWifiAwareNativeSingleton;

    private boolean mNativeHandlersIsInitialized = false;

    private static native int registerAwareNatives();

    /**
     * Returns the singleton WifiAwareNative used to manage the actual Aware HAL
     * interface.
     *
     * @return Singleton object.
     */
    public static WifiAwareNative getInstance() {
        // dummy reference - used to make sure that WifiNative is loaded before
        // us since it is the one to load the shared library and starts its
        // initialization.
        WifiNative dummy = WifiNative.getWlanNativeInterface();
        if (dummy == null) {
            Log.w(TAG, "can't get access to WifiNative");
            return null;
        }

        if (sWifiAwareNativeSingleton == null) {
            sWifiAwareNativeSingleton = new WifiAwareNative();
            registerAwareNatives();
        }

        return sWifiAwareNativeSingleton;
    }

    /**
     * A container class for Aware (vendor) implementation capabilities (or
     * limitations). Filled-in by the firmware.
     */
    public static class Capabilities {
        public int maxConcurrentAwareClusters;
        public int maxPublishes;
        public int maxSubscribes;
        public int maxServiceNameLen;
        public int maxMatchFilterLen;
        public int maxTotalMatchFilterLen;
        public int maxServiceSpecificInfoLen;
        public int maxVsaDataLen;
        public int maxMeshDataLen;
        public int maxNdiInterfaces;
        public int maxNdpSessions;
        public int maxAppInfoLen;
        public int maxQueuedTransmitMessages;

        /**
         * Converts the internal capabilities to a parcelable & potentially app-facing
         * characteristics bundle. Only some of the information is exposed.
         */
        public WifiAwareCharacteristics toPublicCharacteristics() {
            Bundle bundle = new Bundle();
            bundle.putInt(WifiAwareCharacteristics.KEY_MAX_SERVICE_NAME_LENGTH, maxServiceNameLen);
            bundle.putInt(WifiAwareCharacteristics.KEY_MAX_SERVICE_SPECIFIC_INFO_LENGTH,
                    maxServiceSpecificInfoLen);
            bundle.putInt(WifiAwareCharacteristics.KEY_MAX_MATCH_FILTER_LENGTH, maxMatchFilterLen);
            return new WifiAwareCharacteristics(bundle);
        }

        @Override
        public String toString() {
            return "Capabilities [maxConcurrentAwareClusters=" + maxConcurrentAwareClusters
                    + ", maxPublishes=" + maxPublishes + ", maxSubscribes=" + maxSubscribes
                    + ", maxServiceNameLen=" + maxServiceNameLen + ", maxMatchFilterLen="
                    + maxMatchFilterLen + ", maxTotalMatchFilterLen=" + maxTotalMatchFilterLen
                    + ", maxServiceSpecificInfoLen=" + maxServiceSpecificInfoLen
                    + ", maxVsaDataLen=" + maxVsaDataLen + ", maxMeshDataLen=" + maxMeshDataLen
                    + ", maxNdiInterfaces=" + maxNdiInterfaces + ", maxNdpSessions="
                    + maxNdpSessions + ", maxAppInfoLen=" + maxAppInfoLen
                    + ", maxQueuedTransmitMessages=" + maxQueuedTransmitMessages + "]";
        }
    }

    /* package */ static native int initAwareHandlersNative(Class<WifiNative> cls, int iface);

    private boolean isAwareInit() {
        synchronized (WifiNative.sLock) {
            if (!WifiNative.getWlanNativeInterface().isHalStarted()) {
                /*
                 * We should never start the HAL - that's done at a higher level
                 * by the Wi-Fi state machine.
                 */
                mNativeHandlersIsInitialized = false;
                return false;
            } else if (!mNativeHandlersIsInitialized) {
                int ret = initAwareHandlersNative(WifiNative.class, WifiNative.sWlan0Index);
                if (DBG) Log.d(TAG, "initAwareHandlersNative: res=" + ret);
                mNativeHandlersIsInitialized = ret == WIFI_SUCCESS;

                return mNativeHandlersIsInitialized;
            } else {
                return true;
            }
        }
    }

    /**
     * Tell the Aware JNI to re-initialize the Aware callback pointers next time it starts up.
     */
    public void deInitAware() {
        if (VDBG) {
            Log.v(TAG, "deInitAware: mNativeHandlersIsInitialized=" + mNativeHandlersIsInitialized);
        }
        mNativeHandlersIsInitialized = false;
    }

    private WifiAwareNative() {
        // do nothing
    }

    private static native int getCapabilitiesNative(short transactionId, Class<WifiNative> cls,
            int iface);

    /**
     * Query the Aware firmware's capabilities.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     */
    public boolean getCapabilities(short transactionId) {
        if (VDBG) Log.d(TAG, "getCapabilities");
        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = getCapabilitiesNative(transactionId, WifiNative.class,
                        WifiNative.sWlan0Index);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "getCapabilities: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "getCapabilities: cannot initialize Aware");
            return false;
        }
    }

    private static native int enableAndConfigureNative(short transactionId, Class<WifiNative> cls,
            int iface, ConfigRequest configRequest);

    private static native int updateConfigurationNative(short transactionId, Class<WifiNative> cls,
            int iface, ConfigRequest configRequest);

    /**
     * Enable and configure Aware.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param configRequest Requested Aware configuration.
     * @param initialConfiguration Specifies whether initial configuration
     *            (true) or an update (false) to the configuration.
     */
    public boolean enableAndConfigure(short transactionId, ConfigRequest configRequest,
            boolean initialConfiguration) {
        if (VDBG) Log.d(TAG, "enableAndConfigure: configRequest=" + configRequest);
        if (isAwareInit()) {
            int ret;
            if (initialConfiguration) {
                synchronized (WifiNative.sLock) {
                    ret = enableAndConfigureNative(transactionId, WifiNative.class,
                            WifiNative.sWlan0Index, configRequest);
                }
                if (ret != WIFI_SUCCESS) {
                    Log.w(TAG, "enableAndConfigureNative: HAL API returned non-success -- " + ret);
                }
            } else {
                synchronized (WifiNative.sLock) {
                    ret = updateConfigurationNative(transactionId, WifiNative.class,
                            WifiNative.sWlan0Index, configRequest);
                }
                if (ret != WIFI_SUCCESS) {
                    Log.w(TAG, "updateConfigurationNative: HAL API returned non-success -- " + ret);
                }
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "enableAndConfigure: AwareInit fails");
            return false;
        }
    }

    private static native int disableNative(short transactionId, Class<WifiNative> cls, int iface);

    /**
     * Disable Aware.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     */
    public boolean disable(short transactionId) {
        if (VDBG) Log.d(TAG, "disableAware");
        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = disableNative(transactionId, WifiNative.class, WifiNative.sWlan0Index);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "disableNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "disable: cannot initialize Aware");
            return false;
        }
    }

    private static native int publishNative(short transactionId, int publishId,
            Class<WifiNative> cls, int iface, PublishConfig publishConfig);

    /**
     * Start or modify a service publish session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param publishId ID of the requested session - 0 to request a new publish
     *            session.
     * @param publishConfig Configuration of the discovery session.
     */
    public boolean publish(short transactionId, int publishId, PublishConfig publishConfig) {
        if (VDBG) {
            Log.d(TAG, "publish: transactionId=" + transactionId + ", config=" + publishConfig);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = publishNative(transactionId, publishId, WifiNative.class,
                        WifiNative.sWlan0Index, publishConfig);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "publishNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "publish: cannot initialize Aware");
            return false;
        }
    }

    private static native int subscribeNative(short transactionId, int subscribeId,
            Class<WifiNative> cls, int iface, SubscribeConfig subscribeConfig);

    /**
     * Start or modify a service subscription session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param subscribeId ID of the requested session - 0 to request a new
     *            subscribe session.
     * @param subscribeConfig Configuration of the discovery session.
     */
    public boolean subscribe(short transactionId, int subscribeId,
            SubscribeConfig subscribeConfig) {
        if (VDBG) {
            Log.d(TAG, "subscribe: transactionId=" + transactionId + ", config=" + subscribeConfig);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = subscribeNative(transactionId, subscribeId, WifiNative.class,
                        WifiNative.sWlan0Index, subscribeConfig);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "subscribeNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "subscribe: cannot initialize Aware");
            return false;
        }
    }

    private static native int sendMessageNative(short transactionId, Class<WifiNative> cls,
            int iface, int pubSubId, int requestorInstanceId, byte[] dest, byte[] message);

    /**
     * Send a message through an existing discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the existing publish/subscribe session.
     * @param requestorInstanceId ID of the peer to communicate with - obtained
     *            through a previous discovery (match) operation with that peer.
     * @param dest MAC address of the peer to communicate with - obtained
     *            together with requestorInstanceId.
     * @param message Message.
     * @param messageId Arbitary integer from host (not sent to HAL - useful for
     *                  testing/debugging at this level)
     */
    public boolean sendMessage(short transactionId, int pubSubId, int requestorInstanceId,
            byte[] dest, byte[] message, int messageId) {
        if (VDBG) {
            Log.d(TAG,
                    "sendMessage: transactionId=" + transactionId + ", pubSubId=" + pubSubId
                            + ", requestorInstanceId=" + requestorInstanceId + ", dest="
                            + String.valueOf(HexEncoding.encode(dest)) + ", messageId="
                            + messageId);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = sendMessageNative(transactionId, WifiNative.class, WifiNative.sWlan0Index,
                        pubSubId, requestorInstanceId, dest, message);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "sendMessageNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "sendMessage: cannot initialize Aware");
            return false;
        }
    }

    private static native int stopPublishNative(short transactionId, Class<WifiNative> cls,
            int iface, int pubSubId);

    /**
     * Terminate a publish discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the publish/subscribe session - obtained when
     *            creating a session.
     */
    public boolean stopPublish(short transactionId, int pubSubId) {
        if (VDBG) {
            Log.d(TAG, "stopPublish: transactionId=" + transactionId + ", pubSubId=" + pubSubId);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = stopPublishNative(transactionId, WifiNative.class, WifiNative.sWlan0Index,
                        pubSubId);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "stopPublishNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "stopPublish: cannot initialize Aware");
            return false;
        }
    }

    private static native int stopSubscribeNative(short transactionId, Class<WifiNative> cls,
            int iface, int pubSubId);

    /**
     * Terminate a subscribe discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the publish/subscribe session - obtained when
     *            creating a session.
     */
    public boolean stopSubscribe(short transactionId, int pubSubId) {
        if (VDBG) {
            Log.d(TAG, "stopSubscribe: transactionId=" + transactionId + ", pubSubId=" + pubSubId);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = stopSubscribeNative(transactionId, WifiNative.class, WifiNative.sWlan0Index,
                        pubSubId);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "stopSubscribeNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "stopSubscribe: cannot initialize Aware");
            return false;
        }
    }

    private static native int createAwareNetworkInterfaceNative(short transactionId,
                                                              Class<WifiNative> cls, int iface,
                                                              String interfaceName);

    /**
     * Create a Aware network interface. This only creates the Linux interface - it doesn't actually
     * create the data connection.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param interfaceName The name of the interface, e.g. "aware0".
     */
    public boolean createAwareNetworkInterface(short transactionId, String interfaceName) {
        if (VDBG) {
            Log.v(TAG, "createAwareNetworkInterface: transactionId=" + transactionId + ", "
                    + "interfaceName=" + interfaceName);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = createAwareNetworkInterfaceNative(transactionId, WifiNative.class, WifiNative
                        .sWlan0Index, interfaceName);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "createAwareNetworkInterfaceNative: HAL API returned non-success -- "
                        + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "createAwareNetworkInterface: cannot initialize Aware");
            return false;
        }
    }

    private static native int deleteAwareNetworkInterfaceNative(short transactionId,
                                                              Class<WifiNative> cls, int iface,
                                                              String interfaceName);

    /**
     * Deletes a Aware network interface. The data connection can (should?) be torn down previously.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param interfaceName The name of the interface, e.g. "aware0".
     */
    public boolean deleteAwareNetworkInterface(short transactionId, String interfaceName) {
        if (VDBG) {
            Log.v(TAG, "deleteAwareNetworkInterface: transactionId=" + transactionId + ", "
                    + "interfaceName=" + interfaceName);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = deleteAwareNetworkInterfaceNative(transactionId, WifiNative.class, WifiNative
                        .sWlan0Index, interfaceName);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "deleteAwareNetworkInterfaceNative: HAL API returned non-success -- "
                        + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "deleteAwareNetworkInterface: cannot initialize Aware");
            return false;
        }
    }

    private static native int initiateDataPathNative(short transactionId, Class<WifiNative> cls,
            int iface, int peerId, int channelRequestType, int channel, byte[] peer,
            String interfaceName, byte[] message);

    public static final int CHANNEL_REQUEST_TYPE_NONE = 0;
    public static final int CHANNEL_REQUEST_TYPE_REQUESTED = 1;
    public static final int CHANNEL_REQUEST_TYPE_REQUIRED = 2;

    /**
     * Initiates setting up a data-path between device and peer.
     *
     * @param transactionId      Transaction ID for the transaction - used in the async callback to
     *                           match with the original request.
     * @param peerId             ID of the peer ID to associate the data path with. A value of 0
     *                           indicates that not associated with an existing session.
     * @param channelRequestType Indicates whether the specified channel is available, if available
     *                           requested or forced (resulting in failure if cannot be
     *                           accommodated).
     * @param channel            The channel on which to set up the data-path.
     * @param peer               The MAC address of the peer to create a connection with.
     * @param interfaceName      The interface on which to create the data connection.
     * @param message An arbitrary byte array to forward to the peer as part of the data path
     *                request.
     */
    public boolean initiateDataPath(short transactionId, int peerId, int channelRequestType,
            int channel, byte[] peer, String interfaceName, byte[] message) {
        if (VDBG) {
            Log.v(TAG, "initiateDataPath: transactionId=" + transactionId + ", peerId=" + peerId
                    + ", channelRequestType=" + channelRequestType + ", channel=" + channel
                    + ", peer=" + String.valueOf(HexEncoding.encode(peer)) + ", interfaceName="
                    + interfaceName);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = initiateDataPathNative(transactionId, WifiNative.class, WifiNative
                        .sWlan0Index, peerId, channelRequestType, channel, peer, interfaceName,
                        message);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "initiateDataPathNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "initiateDataPath: cannot initialize Aware");
            return false;
        }
    }

    private static native int respondToDataPathRequestNative(short transactionId,
            Class<WifiNative> cls, int iface, boolean accept, int ndpId, String interfaceName,
            byte[] message);

    /**
     * Responds to a data request from a peer.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param accept Accept (true) or reject (false) the original call.
     * @param ndpId The NDP (Aware data path) ID. Obtained from the request callback.
     * @param interfaceName The interface on which the data path will be setup. Obtained from the
     *                      request callback.
     * @param message An arbitrary byte array to forward to the peer in the respond message.
     */
    public boolean respondToDataPathRequest(short transactionId, boolean accept, int ndpId,
            String interfaceName, byte[] message) {
        if (VDBG) {
            Log.v(TAG, "respondToDataPathRequest: transactionId=" + transactionId + ", accept="
                    + accept + ", int ndpId=" + ndpId + ", interfaceName=" + interfaceName);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = respondToDataPathRequestNative(transactionId, WifiNative.class, WifiNative
                        .sWlan0Index, accept, ndpId, interfaceName, message);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG,
                        "respondToDataPathRequestNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "respondToDataPathRequest: cannot initialize Aware");
            return false;
        }
    }

    private static native int endDataPathNative(short transactionId, Class<WifiNative> cls,
            int iface, int ndpId);

    /**
     * Terminate an existing data-path (does not delete the interface).
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param ndpId The NDP (Aware data path) ID to be terminated.
     */
    public boolean endDataPath(short transactionId, int ndpId) {
        if (VDBG) {
            Log.v(TAG, "endDataPath: transactionId=" + transactionId + ", ndpId=" + ndpId);
        }

        if (isAwareInit()) {
            int ret;
            synchronized (WifiNative.sLock) {
                ret = endDataPathNative(transactionId, WifiNative.class, WifiNative.sWlan0Index,
                        ndpId);
            }
            if (ret != WIFI_SUCCESS) {
                Log.w(TAG, "endDataPathNative: HAL API returned non-success -- " + ret);
            }
            return ret == WIFI_SUCCESS;
        } else {
            Log.w(TAG, "endDataPath: cannot initialize Aware");
            return false;
        }
    }

    // EVENTS

    // AwareResponseType for API responses: will add values as needed
    public static final int AWARE_RESPONSE_ENABLED = 0;
    public static final int AWARE_RESPONSE_PUBLISH = 2;
    public static final int AWARE_RESPONSE_PUBLISH_CANCEL = 3;
    public static final int AWARE_RESPONSE_TRANSMIT_FOLLOWUP = 4;
    public static final int AWARE_RESPONSE_SUBSCRIBE = 5;
    public static final int AWARE_RESPONSE_SUBSCRIBE_CANCEL = 6;
    public static final int AWARE_RESPONSE_CONFIG = 8;
    public static final int AWARE_RESPONSE_GET_CAPABILITIES = 12;
    public static final int AWARE_RESPONSE_DP_INTERFACE_CREATE = 13;
    public static final int AWARE_RESPONSE_DP_INTERFACE_DELETE = 14;
    public static final int AWARE_RESPONSE_DP_INITIATOR_RESPONSE = 15;
    public static final int AWARE_RESPONSE_DP_RESPONDER_RESPONSE = 16;
    public static final int AWARE_RESPONSE_DP_END = 17;

    // TODO: place-holder until resolve error codes/feedback to user b/29443148
    public static final int AWARE_STATUS_ERROR = -1;

    // direct copy from wifi_nan.h: need to keep in sync
    /* Aware Protocol Response Codes */
    public static final int AWARE_STATUS_SUCCESS = 0;
    public static final int AWARE_STATUS_TIMEOUT = 1;
    public static final int AWARE_STATUS_DE_FAILURE = 2;
    public static final int AWARE_STATUS_INVALID_MSG_VERSION = 3;
    public static final int AWARE_STATUS_INVALID_MSG_LEN = 4;
    public static final int AWARE_STATUS_INVALID_MSG_ID = 5;
    public static final int AWARE_STATUS_INVALID_HANDLE = 6;
    public static final int AWARE_STATUS_NO_SPACE_AVAILABLE = 7;
    public static final int AWARE_STATUS_INVALID_PUBLISH_TYPE = 8;
    public static final int AWARE_STATUS_INVALID_TX_TYPE = 9;
    public static final int AWARE_STATUS_INVALID_MATCH_ALGORITHM = 10;
    public static final int AWARE_STATUS_DISABLE_IN_PROGRESS = 11;
    public static final int AWARE_STATUS_INVALID_TLV_LEN = 12;
    public static final int AWARE_STATUS_INVALID_TLV_TYPE = 13;
    public static final int AWARE_STATUS_MISSING_TLV_TYPE = 14;
    public static final int AWARE_STATUS_INVALID_TOTAL_TLVS_LEN = 15;
    public static final int AWARE_STATUS_INVALID_MATCH_HANDLE = 16;
    public static final int AWARE_STATUS_INVALID_TLV_VALUE = 17;
    public static final int AWARE_STATUS_INVALID_TX_PRIORITY = 18;
    public static final int AWARE_STATUS_INVALID_CONNECTION_MAP = 19;
    public static final int AWARE_STATUS_INVALID_TCA_ID = 20;
    public static final int AWARE_STATUS_INVALID_STATS_ID = 21;
    public static final int AWARE_STATUS_AWARE_NOT_ALLOWED = 22;
    public static final int AWARE_STATUS_NO_OTA_ACK = 23;
    public static final int AWARE_STATUS_TX_FAIL = 24;
    public static final int AWARE_STATUS_ALREADY_ENABLED = 25;

    /* Aware Configuration Response codes */
    public static final int AWARE_STATUS_INVALID_RSSI_CLOSE_VALUE = 4096;
    public static final int AWARE_STATUS_INVALID_RSSI_MIDDLE_VALUE = 4097;
    public static final int AWARE_STATUS_INVALID_HOP_COUNT_LIMIT = 4098;
    public static final int AWARE_STATUS_INVALID_MASTER_PREFERENCE_VALUE = 4099;
    public static final int AWARE_STATUS_INVALID_LOW_CLUSTER_ID_VALUE = 4100;
    public static final int AWARE_STATUS_INVALID_HIGH_CLUSTER_ID_VALUE = 4101;
    public static final int AWARE_STATUS_INVALID_BACKGROUND_SCAN_PERIOD = 4102;
    public static final int AWARE_STATUS_INVALID_RSSI_PROXIMITY_VALUE = 4103;
    public static final int AWARE_STATUS_INVALID_SCAN_CHANNEL = 4104;
    public static final int AWARE_STATUS_INVALID_POST_AWARE_CONNECTIVITY_CAPABILITIES_BITMAP = 4105;
    public static final int AWARE_STATUS_INVALID_FA_MAP_NUMCHAN_VALUE = 4106;
    public static final int AWARE_STATUS_INVALID_FA_MAP_DURATION_VALUE = 4107;
    public static final int AWARE_STATUS_INVALID_FA_MAP_CLASS_VALUE = 4108;
    public static final int AWARE_STATUS_INVALID_FA_MAP_CHANNEL_VALUE = 4109;
    public static final int AWARE_STATUS_INVALID_FA_MAP_AVAILABILITY_INTERVAL_BITMAP_VALUE = 4110;
    public static final int AWARE_STATUS_INVALID_FA_MAP_MAP_ID = 4111;
    public static final int AWARE_STATUS_INVALID_POST_AWARE_DISCOVERY_CONN_TYPE_VALUE = 4112;
    public static final int AWARE_STATUS_INVALID_POST_AWARE_DISCOVERY_DEVICE_ROLE_VALUE = 4113;
    public static final int AWARE_STATUS_INVALID_POST_AWARE_DISCOVERY_DURATION_VALUE = 4114;
    public static final int AWARE_STATUS_INVALID_POST_AWARE_DISCOVERY_BITMAP_VALUE = 4115;
    public static final int AWARE_STATUS_MISSING_FUTHER_AVAILABILITY_MAP = 4116;
    public static final int AWARE_STATUS_INVALID_BAND_CONFIG_FLAGS = 4117;
    public static final int AWARE_STATUS_INVALID_RANDOM_FACTOR_UPDATE_TIME_VALUE = 4118;
    public static final int AWARE_STATUS_INVALID_ONGOING_SCAN_PERIOD = 4119;
    public static final int AWARE_STATUS_INVALID_DW_INTERVAL_VALUE = 4120;
    public static final int AWARE_STATUS_INVALID_DB_INTERVAL_VALUE = 4121;

    /* publish/subscribe termination reasons */
    public static final int AWARE_TERMINATED_REASON_INVALID = 8192;
    public static final int AWARE_TERMINATED_REASON_TIMEOUT = 8193;
    public static final int AWARE_TERMINATED_REASON_USER_REQUEST = 8194;
    public static final int AWARE_TERMINATED_REASON_FAILURE = 8195;
    public static final int AWARE_TERMINATED_REASON_COUNT_REACHED = 8196;
    public static final int AWARE_TERMINATED_REASON_DE_SHUTDOWN = 8197;
    public static final int AWARE_TERMINATED_REASON_DISABLE_IN_PROGRESS = 8198;
    public static final int AWARE_TERMINATED_REASON_POST_DISC_ATTR_EXPIRED = 8199;
    public static final int AWARE_TERMINATED_REASON_POST_DISC_LEN_EXCEEDED = 8200;
    public static final int AWARE_TERMINATED_REASON_FURTHER_AVAIL_MAP_EMPTY = 8201;

    /* 9000-9500 NDP Status type */
    public static final int AWARE_STATUS_NDP_UNSUPPORTED_CONCURRENCY = 9000;
    public static final int AWARE_STATUS_NDP_AWARE_DATA_IFACE_CREATE_FAILED = 9001;
    public static final int AWARE_STATUS_NDP_AWARE_DATA_IFACE_DELETE_FAILED = 9002;
    public static final int AWARE_STATUS_NDP_DATA_INITIATOR_REQUEST_FAILED = 9003;
    public static final int AWARE_STATUS_NDP_DATA_RESPONDER_REQUEST_FAILED = 9004;
    public static final int AWARE_STATUS_NDP_INVALID_SERVICE_INSTANCE_ID = 9005;
    public static final int AWARE_STATUS_NDP_INVALID_NDP_INSTANCE_ID = 9006;
    public static final int AWARE_STATUS_NDP_INVALID_RESPONSE_CODE = 9007;
    public static final int AWARE_STATUS_NDP_INVALID_APP_INFO_LEN = 9008;

    /* OTA failures and timeouts during negotiation */
    public static final int AWARE_STATUS_NDP_MGMT_FRAME_REQUEST_FAILED = 9009;
    public static final int AWARE_STATUS_NDP_MGMT_FRAME_RESPONSE_FAILED = 9010;
    public static final int AWARE_STATUS_NDP_MGMT_FRAME_CONFIRM_FAILED = 9011;
    public static final int AWARE_STATUS_NDP_END_FAILED = 9012;
    public static final int AWARE_STATUS_NDP_MGMT_FRAME_END_REQUEST_FAILED = 9013;

    /* 9500 onwards vendor specific error codes */
    public static final int AWARE_STATUS_NDP_VENDOR_SPECIFIC_ERROR = 9500;

    private static int translateHalStatusToAwareSessionCallbackTerminate(int halStatus) {
        switch (halStatus) {
            case AWARE_TERMINATED_REASON_TIMEOUT:
            case AWARE_TERMINATED_REASON_USER_REQUEST:
            case AWARE_TERMINATED_REASON_COUNT_REACHED:
                return WifiAwareDiscoverySessionCallback.TERMINATE_REASON_DONE;

            case AWARE_TERMINATED_REASON_INVALID:
            case AWARE_TERMINATED_REASON_FAILURE:
            case AWARE_TERMINATED_REASON_DE_SHUTDOWN:
            case AWARE_TERMINATED_REASON_DISABLE_IN_PROGRESS:
            case AWARE_TERMINATED_REASON_POST_DISC_ATTR_EXPIRED:
            case AWARE_TERMINATED_REASON_POST_DISC_LEN_EXCEEDED:
            case AWARE_TERMINATED_REASON_FURTHER_AVAIL_MAP_EMPTY:
                return WifiAwareDiscoverySessionCallback.TERMINATE_REASON_FAIL;
        }

        return WifiAwareDiscoverySessionCallback.TERMINATE_REASON_FAIL;
    }

    // callback from native
    private static void onAwareNotifyResponse(short transactionId, int responseType, int status,
            int value) {
        if (VDBG) {
            Log.v(TAG,
                    "onAwareNotifyResponse: transactionId=" + transactionId + ", responseType="
                    + responseType + ", status=" + status + ", value=" + value);
        }
        WifiAwareStateManager stateMgr = WifiAwareStateManager.getInstance();

        switch (responseType) {
            case AWARE_RESPONSE_ENABLED:
                /* fall through */
            case AWARE_RESPONSE_CONFIG:
                if (status == AWARE_STATUS_SUCCESS) {
                    stateMgr.onConfigSuccessResponse(transactionId);
                } else {
                    stateMgr.onConfigFailedResponse(transactionId, status);
                }
                break;
            case AWARE_RESPONSE_PUBLISH_CANCEL:
                if (status != AWARE_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onAwareNotifyResponse: AWARE_RESPONSE_PUBLISH_CANCEL error - status="
                                    + status + ", value=" + value);
                }
                break;
            case AWARE_RESPONSE_TRANSMIT_FOLLOWUP:
                if (status == AWARE_STATUS_SUCCESS) {
                    stateMgr.onMessageSendQueuedSuccessResponse(transactionId);
                } else {
                    stateMgr.onMessageSendQueuedFailResponse(transactionId, status);
                }
                break;
            case AWARE_RESPONSE_SUBSCRIBE_CANCEL:
                if (status != AWARE_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onAwareNotifyResponse: AWARE_RESPONSE_PUBLISH_CANCEL error - status="
                                    + status + ", value=" + value);
                }
                break;
            case AWARE_RESPONSE_DP_INTERFACE_CREATE:
                if (status != AWARE_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onAwareNotifyResponse: AWARE_RESPONSE_DP_INTERFACE_CREATE error - "
                                    + "status="
                                    + status + ", value=" + value);
                }
                stateMgr.onCreateDataPathInterfaceResponse(transactionId,
                        status == AWARE_STATUS_SUCCESS, status);
                break;
            case AWARE_RESPONSE_DP_INTERFACE_DELETE:
                if (status != AWARE_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onAwareNotifyResponse: AWARE_RESPONSE_DP_INTERFACE_DELETE error - "
                                    + "status="
                                    + status + ", value=" + value);
                }
                stateMgr.onDeleteDataPathInterfaceResponse(transactionId,
                        status == AWARE_STATUS_SUCCESS, status);
                break;
            case AWARE_RESPONSE_DP_RESPONDER_RESPONSE:
                if (status != AWARE_STATUS_SUCCESS) {
                    Log.e(TAG,
                            "onAwareNotifyResponse: AWARE_RESPONSE_DP_RESPONDER_RESPONSE error - "
                                    + "status=" + status + ", value=" + value);
                }
                stateMgr.onRespondToDataPathSetupRequestResponse(transactionId,
                        status == AWARE_STATUS_SUCCESS, status);
                break;
            case AWARE_RESPONSE_DP_END:
                if (status != AWARE_STATUS_SUCCESS) {
                    Log.e(TAG, "onAwareNotifyResponse: AWARE_RESPONSE_DP_END error - status="
                            + status + ", value=" + value);
                }
                stateMgr.onEndDataPathResponse(transactionId, status == AWARE_STATUS_SUCCESS,
                        status);
                break;
            default:
                Log.e(TAG, "onAwareNotifyResponse: unclassified responseType=" + responseType);
                break;
        }
    }

    private static void onAwareNotifyResponsePublishSubscribe(short transactionId, int responseType,
            int status, int value, int pubSubId) {
        if (VDBG) {
            Log.v(TAG,
                    "onAwareNotifyResponsePublishSubscribe: transactionId=" + transactionId
                            + ", responseType=" + responseType + ", status=" + status + ", value="
                            + value + ", pubSubId=" + pubSubId);
        }

        switch (responseType) {
            case AWARE_RESPONSE_PUBLISH:
                if (status == AWARE_STATUS_SUCCESS) {
                    WifiAwareStateManager.getInstance().onSessionConfigSuccessResponse(
                            transactionId, true, pubSubId);
                } else {
                    WifiAwareStateManager.getInstance().onSessionConfigFailResponse(transactionId,
                            true, status);
                }
                break;
            case AWARE_RESPONSE_SUBSCRIBE:
                if (status == AWARE_STATUS_SUCCESS) {
                    WifiAwareStateManager.getInstance().onSessionConfigSuccessResponse(
                            transactionId, false, pubSubId);
                } else {
                    WifiAwareStateManager.getInstance().onSessionConfigFailResponse(transactionId,
                            false, status);
                }
                break;
            default:
                Log.wtf(TAG, "onAwareNotifyResponsePublishSubscribe: unclassified responseType="
                        + responseType);
                break;
        }
    }

    private static void onAwareNotifyResponseCapabilities(short transactionId, int status,
            int value, Capabilities capabilities) {
        if (VDBG) {
            Log.v(TAG, "onAwareNotifyResponseCapabilities: transactionId=" + transactionId
                    + ", status=" + status + ", value=" + value + ", capabilities=" + capabilities);
        }

        if (status == AWARE_STATUS_SUCCESS) {
            WifiAwareStateManager.getInstance().onCapabilitiesUpdateResponse(transactionId,
                    capabilities);
        } else {
            Log.e(TAG, "onAwareNotifyResponseCapabilities: error status=" + status
                    + ", value=" + value);
        }
    }

    private static void onAwareNotifyResponseDataPathInitiate(short transactionId, int status,
            int value, int ndpId) {
        if (VDBG) {
            Log.v(TAG,
                    "onAwareNotifyResponseDataPathInitiate: transactionId=" + transactionId
                            + ", status=" + status + ", value=" + value + ", ndpId=" + ndpId);
        }
        if (status == AWARE_STATUS_SUCCESS) {
            WifiAwareStateManager.getInstance().onInitiateDataPathResponseSuccess(transactionId,
                    ndpId);
        } else {
            WifiAwareStateManager.getInstance().onInitiateDataPathResponseFail(transactionId,
                    status);
        }
    }

    public static final int AWARE_EVENT_ID_DISC_MAC_ADDR = 0;
    public static final int AWARE_EVENT_ID_STARTED_CLUSTER = 1;
    public static final int AWARE_EVENT_ID_JOINED_CLUSTER = 2;

    // callback from native
    private static void onDiscoveryEngineEvent(int eventType, byte[] mac) {
        if (VDBG) {
            Log.v(TAG, "onDiscoveryEngineEvent: eventType=" + eventType + ", mac="
                    + String.valueOf(HexEncoding.encode(mac)));
        }

        if (eventType == AWARE_EVENT_ID_DISC_MAC_ADDR) {
            WifiAwareStateManager.getInstance().onInterfaceAddressChangeNotification(mac);
        } else if (eventType == AWARE_EVENT_ID_STARTED_CLUSTER) {
            WifiAwareStateManager.getInstance().onClusterChangeNotification(
                    WifiAwareClientState.CLUSTER_CHANGE_EVENT_STARTED, mac);
        } else if (eventType == AWARE_EVENT_ID_JOINED_CLUSTER) {
            WifiAwareStateManager.getInstance().onClusterChangeNotification(
                    WifiAwareClientState.CLUSTER_CHANGE_EVENT_JOINED, mac);
        } else {
            Log.w(TAG, "onDiscoveryEngineEvent: invalid eventType=" + eventType);
        }
    }

    // callback from native
    private static void onMatchEvent(int pubSubId, int requestorInstanceId, byte[] mac,
            byte[] serviceSpecificInfo, byte[] matchFilter) {
        if (VDBG) {
            Log.v(TAG, "onMatchEvent: pubSubId=" + pubSubId + ", requestorInstanceId="
                    + requestorInstanceId + ", mac=" + String.valueOf(HexEncoding.encode(mac))
                    + ", serviceSpecificInfo=" + Arrays.toString(serviceSpecificInfo)
                    + ", matchFilter=" + Arrays.toString(matchFilter));
        }

        WifiAwareStateManager.getInstance().onMatchNotification(pubSubId, requestorInstanceId, mac,
                serviceSpecificInfo, matchFilter);
    }

    // callback from native
    private static void onPublishTerminated(int publishId, int status) {
        if (VDBG) Log.v(TAG, "onPublishTerminated: publishId=" + publishId + ", status=" + status);

        WifiAwareStateManager.getInstance().onSessionTerminatedNotification(publishId,
                translateHalStatusToAwareSessionCallbackTerminate(status), true);
    }

    // callback from native
    private static void onSubscribeTerminated(int subscribeId, int status) {
        if (VDBG) {
            Log.v(TAG, "onSubscribeTerminated: subscribeId=" + subscribeId + ", status=" + status);
        }

        WifiAwareStateManager.getInstance().onSessionTerminatedNotification(subscribeId,
                translateHalStatusToAwareSessionCallbackTerminate(status), false);
    }

    // callback from native
    private static void onFollowupEvent(int pubSubId, int requestorInstanceId, byte[] mac,
            byte[] message) {
        if (VDBG) {
            Log.v(TAG, "onFollowupEvent: pubSubId=" + pubSubId + ", requestorInstanceId="
                    + requestorInstanceId + ", mac=" + String.valueOf(HexEncoding.encode(mac)));
        }

        WifiAwareStateManager.getInstance().onMessageReceivedNotification(pubSubId,
                requestorInstanceId, mac, message);
    }

    // callback from native
    private static void onDisabledEvent(int status) {
        if (VDBG) Log.v(TAG, "onDisabledEvent: status=" + status);

        WifiAwareStateManager.getInstance().onAwareDownNotification(status);
    }

    // callback from native
    private static void onTransmitFollowupEvent(short transactionId, int reason) {
        if (VDBG) {
            Log.v(TAG, "onTransmitFollowupEvent: transactionId=" + transactionId + ", reason="
                    + reason);
        }

        if (reason == AWARE_STATUS_SUCCESS) {
            WifiAwareStateManager.getInstance().onMessageSendSuccessNotification(transactionId);
        } else {
            WifiAwareStateManager.getInstance().onMessageSendFailNotification(transactionId,
                    reason);
        }
    }

    private static void onDataPathRequest(int pubSubId, byte[] mac, int ndpId, byte[] message) {
        if (VDBG) {
            Log.v(TAG, "onDataPathRequest: pubSubId=" + pubSubId + ", mac=" + String.valueOf(
                    HexEncoding.encode(mac)) + ", ndpId=" + ndpId);
        }

        WifiAwareStateManager.getInstance()
                .onDataPathRequestNotification(pubSubId, mac, ndpId, message);
    }

    private static void onDataPathConfirm(int ndpId, byte[] mac, boolean accept, int reason,
            byte[] message) {
        if (VDBG) {
            Log.v(TAG, "onDataPathConfirm: ndpId=" + ndpId + ", mac=" + String.valueOf(HexEncoding
                    .encode(mac)) + ", accept=" + accept + ", reason=" + reason);
        }

        WifiAwareStateManager.getInstance()
                .onDataPathConfirmNotification(ndpId, mac, accept, reason, message);
    }

    private static void onDataPathEnd(int ndpId) {
        if (VDBG) {
            Log.v(TAG, "onDataPathEndNotification: ndpId=" + ndpId);
        }

        WifiAwareStateManager.getInstance().onDataPathEndNotification(ndpId);
    }
}
