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

package com.android.server.wifi.nan;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanEventCallback;
import android.net.wifi.nan.IWifiNanSessionCallback;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.net.wifi.nan.WifiNanEventCallback;
import android.net.wifi.nan.WifiNanManager;
import android.net.wifi.nan.WifiNanSessionCallback;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.WakeupMessage;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Manages the state of the Wi-Fi NAN system service.
 */
public class WifiNanStateManager {
    private static final String TAG = "WifiNanStateManager";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    @VisibleForTesting
    public static final String HAL_COMMAND_TIMEOUT_TAG = TAG + " HAL Command Timeout";

    private static WifiNanStateManager sNanStateManagerSingleton;

    /*
     * State machine message types. There are sub-types for the messages (except
     * for TIMEOUT). Format: - Message.arg1: contains message sub-type -
     * Message.arg2: contains transaction ID for RESPONSES & TIMEOUT
     */
    private static final int MESSAGE_TYPE_NOTIFICATION = 1;
    private static final int MESSAGE_TYPE_COMMAND = 2;
    private static final int MESSAGE_TYPE_RESPONSE = 3;
    private static final int MESSAGE_TYPE_TIMEOUT = 4;

    /*
     * Message sub-types:
     */
    private static final int COMMAND_TYPE_CONNECT = 0;
    private static final int COMMAND_TYPE_DISCONNECT = 1;
    private static final int COMMAND_TYPE_TERMINATE_SESSION = 2;
    private static final int COMMAND_TYPE_PUBLISH = 3;
    private static final int COMMAND_TYPE_UPDATE_PUBLISH = 4;
    private static final int COMMAND_TYPE_SUBSCRIBE = 5;
    private static final int COMMAND_TYPE_UPDATE_SUBSCRIBE = 6;
    private static final int COMMAND_TYPE_SEND_MESSAGE = 7;
    private static final int COMMAND_TYPE_ENABLE_USAGE = 8;
    private static final int COMMAND_TYPE_DISABLE_USAGE = 9;

    private static final int RESPONSE_TYPE_ON_CONFIG_SUCCESS = 0;
    private static final int RESPONSE_TYPE_ON_CONFIG_FAIL = 1;
    private static final int RESPONSE_TYPE_ON_SESSION_CONFIG_SUCCESS = 2;
    private static final int RESPONSE_TYPE_ON_SESSION_CONFIG_FAIL = 3;
    private static final int RESPONSE_TYPE_ON_MESSAGE_SEND_SUCCESS = 4;
    private static final int RESPONSE_TYPE_ON_MESSAGE_SEND_FAIL = 5;

    private static final int NOTIFICATION_TYPE_CAPABILITIES_UPDATED = 0;
    private static final int NOTIFICATION_TYPE_INTERFACE_CHANGE = 1;
    private static final int NOTIFICATION_TYPE_CLUSTER_CHANGE = 2;
    private static final int NOTIFICATION_TYPE_MATCH = 3;
    private static final int NOTIFICATION_TYPE_SESSION_TERMINATED = 4;
    private static final int NOTIFICATION_TYPE_MESSAGE_RECEIVED = 5;
    private static final int NOTIFICATION_TYPE_NAN_DOWN = 6;

    /*
     * Keys used when passing (some) arguments to the Handler thread (too many
     * arguments to pass in the short-cut Message members).
     */
    private static final String MESSAGE_BUNDLE_KEY_SESSION_TYPE = "session_type";
    private static final String MESSAGE_BUNDLE_KEY_SESSION_ID = "session_id";
    private static final String MESSAGE_BUNDLE_KEY_CONFIG = "config";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE = "message";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE_PEER_ID = "message_peer_id";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE_ID = "message_id";
    private static final String MESSAGE_BUNDLE_KEY_SSI_LENGTH = "ssi_length";
    private static final String MESSAGE_BUNDLE_KEY_SSI_DATA = "ssi_data";
    private static final String MESSAGE_BUNDLE_KEY_FILTER_LENGTH = "filter_length";
    private static final String MESSAGE_BUNDLE_KEY_FILTER_DATA = "filter_data";
    private static final String MESSAGE_BUNDLE_KEY_MAC_ADDRESS = "mac_address";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE_DATA = "message_data";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE_LENGTH = "message_length";
    private static final String MESSAGE_BUNDLE_KEY_REQ_INSTANCE_ID = "req_instance_id";

    /*
     * Asynchronous access with no lock
     */
    private volatile boolean mUsageEnabled = false;

    /*
     * Synchronous access: state is only accessed through the state machine
     * handler thread: no need to use a lock.
     */
    private Context mContext;
    private WifiNanNative.Capabilities mCapabilities;
    private WifiNanStateMachine mSm;

    private final SparseArray<WifiNanClientState> mClients = new SparseArray<>();
    private ConfigRequest mCurrentNanConfiguration = null;

    private WifiNanStateManager() {
        // EMPTY: singleton pattern
    }

    /**
     * Access the singleton NAN state manager. Use a singleton since need to be
     * accessed (for now) from several other child classes.
     *
     * @return The state manager singleton.
     */
    public static WifiNanStateManager getInstance() {
        if (sNanStateManagerSingleton == null) {
            sNanStateManagerSingleton = new WifiNanStateManager();
        }

        return sNanStateManagerSingleton;
    }

    /**
     * Initialize the handler of the state manager with the specified thread
     * looper.
     *
     * @param looper Thread looper on which to run the handler.
     */
    public void start(Context context, Looper looper) {
        Log.i(TAG, "start()");

        mContext = context;
        mSm = new WifiNanStateMachine(TAG, looper);
        mSm.setDbg(DBG);
        mSm.start();
    }

    /*
     * COMMANDS
     */

    /**
     * Place a request for a new client connection on the state machine queue.
     */
    public void connect(int clientId, IWifiNanEventCallback callback, ConfigRequest configRequest) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_CONNECT;
        msg.arg2 = clientId;
        msg.obj = callback;
        msg.getData().putParcelable(MESSAGE_BUNDLE_KEY_CONFIG, configRequest);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to disconnect (destroy) an existing client on the state
     * machine queue.
     */
    public void disconnect(int clientId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_DISCONNECT;
        msg.arg2 = clientId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to stop a discovery session on the state machine queue.
     */
    public void terminateSession(int clientId, int sessionId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_TERMINATE_SESSION;
        msg.arg2 = clientId;
        msg.obj = new Integer(sessionId);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to start a new publish discovery session on the state
     * machine queue.
     */
    public void publish(int clientId, PublishConfig publishConfig,
            IWifiNanSessionCallback callback) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_PUBLISH;
        msg.arg2 = clientId;
        msg.obj = callback;
        msg.getData().putParcelable(MESSAGE_BUNDLE_KEY_CONFIG, publishConfig);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to modify an existing publish discovery session on the
     * state machine queue.
     */
    public void updatePublish(int clientId, int sessionId, PublishConfig publishConfig) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_UPDATE_PUBLISH;
        msg.arg2 = clientId;
        msg.obj = publishConfig;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_SESSION_ID, sessionId);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to start a new subscribe discovery session on the state
     * machine queue.
     */
    public void subscribe(int clientId, SubscribeConfig subscribeConfig,
            IWifiNanSessionCallback callback) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_SUBSCRIBE;
        msg.arg2 = clientId;
        msg.obj = callback;
        msg.getData().putParcelable(MESSAGE_BUNDLE_KEY_CONFIG, subscribeConfig);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to modify an existing subscribe discovery session on the
     * state machine queue.
     */
    public void updateSubscribe(int clientId, int sessionId, SubscribeConfig subscribeConfig) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_UPDATE_SUBSCRIBE;
        msg.arg2 = clientId;
        msg.obj = subscribeConfig;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_SESSION_ID, sessionId);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to send a message on a discovery session on the state
     * machine queue.
     */
    public void sendMessage(int clientId, int sessionId, int peerId, byte[] message,
            int messageLength, int messageId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_SEND_MESSAGE;
        msg.arg2 = clientId;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_SESSION_ID, sessionId);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_MESSAGE_PEER_ID, peerId);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE, message);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID, messageId);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_MESSAGE_LENGTH, messageLength);
        mSm.sendMessage(msg);
    }

    /**
     * Enable usage of NAN. Doesn't actually turn on NAN (form clusters) - that
     * only happens when a connection is created.
     */
    public void enableUsage() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_ENABLE_USAGE;
        mSm.sendMessage(msg);
    }

    /**
     * Disable usage of NAN. Terminates all existing clients with onNanDown().
     */
    public void disableUsage() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_DISABLE_USAGE;
        mSm.sendMessage(msg);
    }

    /**
     * Checks whether NAN usage is enabled (not necessarily that NAN is up right
     * now) or disabled.
     *
     * @return A boolean indicating whether NAN usage is enabled (true) or
     *         disabled (false).
     */
    public boolean isUsageEnabled() {
        return mUsageEnabled;
    }

    /*
     * RESPONSES
     */

    /**
     * Place a callback request on the state machine queue: configuration
     * request completed (successfully).
     */
    public void onConfigSuccessResponse(short transactionId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_CONFIG_SUCCESS;
        msg.arg2 = transactionId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: configuration
     * request failed.
     */
    public void onConfigFailedResponse(short transactionId, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_CONFIG_FAIL;
        msg.arg2 = transactionId;
        msg.obj = new Integer(reason);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: session
     * configuration (new or update) request succeeded.
     */
    public void onSessionConfigSuccessResponse(short transactionId, boolean isPublish,
            int pubSubId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_SESSION_CONFIG_SUCCESS;
        msg.arg2 = transactionId;
        msg.obj = new Integer(pubSubId);
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE, isPublish);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: session
     * configuration (new or update) request failed.
     */
    public void onSessionConfigFailResponse(short transactionId, boolean isPublish, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_SESSION_CONFIG_FAIL;
        msg.arg2 = transactionId;
        msg.obj = new Integer(reason);
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE, isPublish);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: message has been
     * sent successfully (i.e. an ACK was received from the targeted peer).
     */
    public void onMessageSendSuccessResponse(short transactionId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_MESSAGE_SEND_SUCCESS;
        msg.arg2 = transactionId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: attempt to send a
     * message has failed.
     */
    public void onMessageSendFailResponse(short transactionId, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_MESSAGE_SEND_FAIL;
        msg.arg2 = transactionId;
        msg.obj = new Integer(reason);
        mSm.sendMessage(msg);
    }

    /*
     * NOTIFICATIONS
     */

    /**
     * Place a callback request on the state machine queue: update vendor
     * capabilities of the NAN stack. This is actually a RESPONSE from the HAL -
     * but treated as a NOTIFICATION.
     */
    public void onCapabilitiesUpdateNotification(WifiNanNative.Capabilities capabilities) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_CAPABILITIES_UPDATED;
        msg.obj = capabilities;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: the discovery
     * interface has changed.
     */
    public void onInterfaceAddressChangeNotification(byte[] mac) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_INTERFACE_CHANGE;
        msg.obj = mac;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: the cluster
     * membership has changed (e.g. due to starting a new cluster or joining
     * another cluster).
     */
    public void onClusterChangeNotification(int flag, byte[] clusterId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_CLUSTER_CHANGE;
        msg.arg2 = flag;
        msg.obj = clusterId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: a discovery match
     * has occurred - e.g. our subscription discovered someone else publishing a
     * matching service (to the one we were looking for).
     */
    public void onMatchNotification(int pubSubId, int requestorInstanceId, byte[] peerMac,
            byte[] serviceSpecificInfo, int serviceSpecificInfoLength, byte[] matchFilter,
            int matchFilterLength) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_MATCH;
        msg.arg2 = pubSubId;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_REQ_INSTANCE_ID, requestorInstanceId);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, peerMac);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_SSI_DATA, serviceSpecificInfo);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_SSI_LENGTH, serviceSpecificInfoLength);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_FILTER_DATA, matchFilter);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_FILTER_LENGTH, matchFilterLength);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: a session (publish
     * or subscribe) has terminated (per plan or due to an error).
     */
    public void onSessionTerminatedNotification(int pubSubId, int reason, boolean isPublish) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_SESSION_TERMINATED;
        msg.arg2 = pubSubId;
        msg.obj = new Integer(reason);
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE, isPublish);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: a message has been
     * received as part of a discovery session.
     */
    public void onMessageReceivedNotification(int pubSubId, int requestorInstanceId, byte[] peerMac,
            byte[] message, int messageLength) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_MESSAGE_RECEIVED;
        msg.arg2 = pubSubId;
        msg.obj = new Integer(requestorInstanceId);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, peerMac);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE_DATA, message);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_MESSAGE_LENGTH, messageLength);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: NAN is going down.
     */
    public void onNanDownNotification(int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_NAN_DOWN;
        msg.arg2 = reason;
        mSm.sendMessage(msg);
    }

    /**
     * State machine.
     */
    private class WifiNanStateMachine extends StateMachine {
        private static final int TRANSACTION_ID_IGNORE = 0;

        private DefaultState mDefaultState = new DefaultState();
        private WaitState mWaitState = new WaitState();
        private WaitForResponseState mWaitForResponseState = new WaitForResponseState();

        private short mNextTransactionId = 1;
        public int mNextSessionId = 1;

        private Message mCurrentCommand;
        private short mCurrentTransactionId = TRANSACTION_ID_IGNORE;

        WifiNanStateMachine(String name, Looper looper) {
            super(name, looper);

            addState(mDefaultState);
            /* --> */ addState(mWaitState, mDefaultState);
            /* --> */ addState(mWaitForResponseState, mDefaultState);

            setInitialState(mWaitState);
        }

        private class DefaultState extends State {
            @Override
            public boolean processMessage(Message msg) {
                if (VDBG) {
                    Log.v(TAG, getName() + msg.toString());
                }

                switch (msg.what) {
                    case MESSAGE_TYPE_NOTIFICATION:
                        processNotification(msg);
                        return HANDLED;
                    default:
                        /* fall-through */
                }

                Log.wtf(TAG,
                        "DefaultState: should not get non-NOTIFICATION in this state: msg=" + msg);
                return NOT_HANDLED;
            }
        }

        private class WaitState extends State {
            @Override
            public boolean processMessage(Message msg) {
                if (VDBG) {
                    Log.v(TAG, getName() + msg.toString());
                }

                switch (msg.what) {
                    case MESSAGE_TYPE_COMMAND:
                        if (processCommand(msg)) {
                            transitionTo(mWaitForResponseState);
                        }
                        return HANDLED;
                    case MESSAGE_TYPE_RESPONSE:
                        /* fall-through */
                    case MESSAGE_TYPE_TIMEOUT:
                        /*
                         * remnants/delayed/out-of-sync messages - but let
                         * WaitForResponseState deal with them (identified as
                         * out-of-date by transaction ID).
                         */
                        deferMessage(msg);
                        return HANDLED;
                    default:
                        /* fall-through */
                }

                return NOT_HANDLED;
            }
        }

        private class WaitForResponseState extends State {
            private static final long NAN_COMMAND_TIMEOUT = 5_000;
            private WakeupMessage mTimeoutMessage;

            @Override
            public void enter() {
                mTimeoutMessage = new WakeupMessage(mContext, getHandler(), HAL_COMMAND_TIMEOUT_TAG,
                        MESSAGE_TYPE_TIMEOUT, mCurrentCommand.arg1, mCurrentTransactionId);
                mTimeoutMessage.schedule(SystemClock.elapsedRealtime() + NAN_COMMAND_TIMEOUT);
            }

            @Override
            public void exit() {
                mTimeoutMessage.cancel();
            }

            @Override
            public boolean processMessage(Message msg) {
                if (VDBG) {
                    Log.v(TAG, getName() + msg.toString());
                }

                switch (msg.what) {
                    case MESSAGE_TYPE_COMMAND:
                        /*
                         * don't want COMMANDs in this state - defer until back
                         * in WaitState
                         */
                        deferMessage(msg);
                        return HANDLED;
                    case MESSAGE_TYPE_RESPONSE:
                        if (msg.arg2 == mCurrentTransactionId) {
                            processResponse(msg);
                            transitionTo(mWaitState);
                        } else {
                            Log.w(TAG,
                                    "WaitForResponseState: processMessage: non-matching "
                                            + "transaction ID on RESPONSE (a very late "
                                            + "response) -- msg=" + msg);
                            /* no transition */
                        }
                        return HANDLED;
                    case MESSAGE_TYPE_TIMEOUT:
                        if (msg.arg2 == mCurrentTransactionId) {
                            processTimeout(msg);
                            transitionTo(mWaitState);
                        } else {
                            Log.w(TAG, "WaitForResponseState: processMessage: non-matching "
                                    + "transaction ID on TIMEOUT (either a non-cancelled "
                                    + "timeout or a race condition with cancel) -- msg=" + msg);
                            /* no transition */
                        }
                        return HANDLED;
                    default:
                        /* fall-through */
                }

                return NOT_HANDLED;
            }
        }

        private void processNotification(Message msg) {
            if (VDBG) {
                Log.v(TAG, "processNotification: msg=" + msg);
            }

            switch (msg.arg1) {
                case NOTIFICATION_TYPE_CAPABILITIES_UPDATED: {
                    WifiNanNative.Capabilities capabilities = (WifiNanNative.Capabilities) msg.obj;
                    onCapabilitiesUpdatedLocal(capabilities);
                    break;
                }
                case NOTIFICATION_TYPE_INTERFACE_CHANGE: {
                    byte[] mac = (byte[]) msg.obj;

                    onInterfaceAddressChangeLocal(mac);
                    break;
                }
                case NOTIFICATION_TYPE_CLUSTER_CHANGE: {
                    int flag = msg.arg2;
                    byte[] clusterId = (byte[]) msg.obj;

                    onClusterChangeLocal(flag, clusterId);
                    break;
                }
                case NOTIFICATION_TYPE_MATCH: {
                    int pubSubId = msg.arg2;
                    int requestorInstanceId = msg.getData()
                            .getInt(MESSAGE_BUNDLE_KEY_REQ_INSTANCE_ID);
                    byte[] peerMac = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS);
                    byte[] serviceSpecificInfo = msg.getData()
                            .getByteArray(MESSAGE_BUNDLE_KEY_SSI_DATA);
                    int serviceSpecificInfoLength = msg.getData()
                            .getInt(MESSAGE_BUNDLE_KEY_SSI_LENGTH);
                    byte[] matchFilter = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_FILTER_DATA);
                    int matchFilterLength = msg.getData().getInt(MESSAGE_BUNDLE_KEY_FILTER_LENGTH);

                    onMatchLocal(pubSubId, requestorInstanceId, peerMac, serviceSpecificInfo,
                            serviceSpecificInfoLength, matchFilter, matchFilterLength);
                    break;
                }
                case NOTIFICATION_TYPE_SESSION_TERMINATED: {
                    int pubSubId = msg.arg2;
                    int reason = (Integer) msg.obj;
                    boolean isPublish = msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE);

                    onSessionTerminatedLocal(pubSubId, isPublish, reason);
                    break;
                }
                case NOTIFICATION_TYPE_MESSAGE_RECEIVED: {
                    int pubSubId = msg.arg2;
                    int requestorInstanceId = (Integer) msg.obj;
                    byte[] peerMac = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS);
                    byte[] message = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE_DATA);
                    int messageLength = msg.getData().getInt(MESSAGE_BUNDLE_KEY_MESSAGE_LENGTH);

                    onMessageReceivedLocal(pubSubId, requestorInstanceId, peerMac, message,
                            messageLength);
                    break;
                }
                case NOTIFICATION_TYPE_NAN_DOWN: {
                    int reason = msg.arg2;

                    /*
                     * TODO: b/28615938. Use reason code to determine whether or not need clean-up
                     * local state (only needed if NAN_DOWN is due to internal firmware reason, e.g.
                     * concurrency, rather than due to a requested shutdown).
                     */

                    onNanDownLocal();

                    break;
                }
                default:
                    Log.wtf(TAG, "processNotification: this isn't a NOTIFICATION -- msg=" + msg);
                    return;
            }
        }

        /**
         * Execute the command specified by the input Message. Returns a true if
         * need to wait for a RESPONSE, otherwise a false. We may not have to
         * wait for a RESPONSE if there was an error in the state (so no command
         * is sent to HAL) OR if we choose not to wait for response - e.g. for
         * disconnected/terminate commands failure is not possible.
         */
        private boolean processCommand(Message msg) {
            if (VDBG) {
                Log.v(TAG, "processCommand: msg=" + msg);
            }

            if (mCurrentCommand != null) {
                Log.wtf(TAG,
                        "processCommand: receiving a command (msg=" + msg
                                + ") but current (previous) command isn't null (prev_msg="
                                + mCurrentCommand + ")");
                mCurrentCommand = null;
            }

            mCurrentTransactionId = mNextTransactionId++;

            boolean waitForResponse = true;

            switch (msg.arg1) {
                case COMMAND_TYPE_CONNECT: {
                    int clientId = msg.arg2;
                    IWifiNanEventCallback callback = (IWifiNanEventCallback) msg.obj;
                    ConfigRequest configRequest = (ConfigRequest) msg.getData()
                            .getParcelable(MESSAGE_BUNDLE_KEY_CONFIG);

                    waitForResponse = connectLocal(mCurrentTransactionId, clientId, callback,
                            configRequest);
                    break;
                }
                case COMMAND_TYPE_DISCONNECT: {
                    int clientId = msg.arg2;

                    waitForResponse = disconnectLocal(mCurrentTransactionId, clientId);
                    break;
                }
                case COMMAND_TYPE_TERMINATE_SESSION: {
                    int clientId = msg.arg2;
                    int sessionId = (Integer) msg.obj;

                    terminateSessionLocal(clientId, sessionId);
                    waitForResponse = false;
                    break;
                }
                case COMMAND_TYPE_PUBLISH: {
                    int clientId = msg.arg2;
                    IWifiNanSessionCallback callback = (IWifiNanSessionCallback) msg.obj;
                    PublishConfig publishConfig = (PublishConfig) msg.getData()
                            .getParcelable(MESSAGE_BUNDLE_KEY_CONFIG);

                    waitForResponse = publishLocal(mCurrentTransactionId, clientId, publishConfig,
                            callback);
                    break;
                }
                case COMMAND_TYPE_UPDATE_PUBLISH: {
                    int clientId = msg.arg2;
                    int sessionId = msg.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
                    PublishConfig publishConfig = (PublishConfig) msg.obj;

                    waitForResponse = updatePublishLocal(mCurrentTransactionId, clientId, sessionId,
                            publishConfig);
                    break;
                }
                case COMMAND_TYPE_SUBSCRIBE: {
                    int clientId = msg.arg2;
                    IWifiNanSessionCallback callback = (IWifiNanSessionCallback) msg.obj;
                    SubscribeConfig subscribeConfig = (SubscribeConfig) msg.getData()
                            .getParcelable(MESSAGE_BUNDLE_KEY_CONFIG);

                    waitForResponse = subscribeLocal(mCurrentTransactionId, clientId,
                            subscribeConfig, callback);
                    break;
                }
                case COMMAND_TYPE_UPDATE_SUBSCRIBE: {
                    int clientId = msg.arg2;
                    int sessionId = msg.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
                    SubscribeConfig subscribeConfig = (SubscribeConfig) msg.obj;

                    waitForResponse = updateSubscribeLocal(mCurrentTransactionId, clientId,
                            sessionId, subscribeConfig);
                    break;
                }
                case COMMAND_TYPE_SEND_MESSAGE: {
                    Bundle data = msg.getData();

                    int clientId = msg.arg2;
                    int sessionId = msg.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
                    int peerId = data.getInt(MESSAGE_BUNDLE_KEY_MESSAGE_PEER_ID);
                    byte[] message = data.getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE);
                    int messageId = data.getInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID);
                    int messageLength = data.getInt(MESSAGE_BUNDLE_KEY_MESSAGE_LENGTH);

                    waitForResponse = sendFollowonMessageLocal(mCurrentTransactionId, clientId,
                            sessionId, peerId, message, messageLength, messageId);
                    break;
                }
                case COMMAND_TYPE_ENABLE_USAGE:
                    enableUsageLocal();
                    waitForResponse = false;
                    break;
                case COMMAND_TYPE_DISABLE_USAGE:
                    disableUsageLocal();
                    waitForResponse = false;
                    break;
                default:
                    waitForResponse = false;
                    Log.wtf(TAG, "processCommand: this isn't a COMMAND -- msg=" + msg);
                    /* fall-through */
            }

            if (!waitForResponse) {
                mCurrentTransactionId = TRANSACTION_ID_IGNORE;
            } else {
                mCurrentCommand = obtainMessage(msg.what);
                mCurrentCommand.copyFrom(msg);
            }

            return waitForResponse;
        }

        private void processResponse(Message msg) {
            if (VDBG) {
                Log.v(TAG, "processResponse: msg=" + msg);
            }

            if (mCurrentCommand == null) {
                Log.wtf(TAG, "processResponse: no existing command stored!? msg=" + msg);
                mCurrentTransactionId = TRANSACTION_ID_IGNORE;
                return;
            }

            switch (msg.arg1) {
                case RESPONSE_TYPE_ON_CONFIG_SUCCESS:
                    onConfigCompletedLocal(mCurrentCommand);
                    break;
                case RESPONSE_TYPE_ON_CONFIG_FAIL: {
                    int reason = (Integer) msg.obj;

                    onConfigFailedLocal(mCurrentCommand, reason);
                    break;
                }
                case RESPONSE_TYPE_ON_SESSION_CONFIG_SUCCESS: {
                    int pubSubId = (Integer) msg.obj;
                    boolean isPublish = msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE);

                    onSessionConfigSuccessLocal(mCurrentCommand, pubSubId, isPublish);
                    break;
                }
                case RESPONSE_TYPE_ON_SESSION_CONFIG_FAIL: {
                    int reason = (Integer) msg.obj;
                    boolean isPublish = msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE);

                    onSessionConfigFailLocal(mCurrentCommand, isPublish, reason);
                    break;
                }
                case RESPONSE_TYPE_ON_MESSAGE_SEND_SUCCESS:
                    onMessageSendSuccessLocal(mCurrentCommand);
                    break;
                case RESPONSE_TYPE_ON_MESSAGE_SEND_FAIL: {
                    int reason = (Integer) msg.obj;

                    onMessageSendFailLocal(mCurrentCommand, reason);
                    break;
                }
                default:
                    Log.wtf(TAG, "processResponse: this isn't a RESPONSE -- msg=" + msg);
                    mCurrentCommand = null;
                    mCurrentTransactionId = TRANSACTION_ID_IGNORE;
                    return;
            }

            mCurrentCommand = null;
            mCurrentTransactionId = TRANSACTION_ID_IGNORE;
        }

        private void processTimeout(Message msg) {
            if (VDBG) {
                Log.v(TAG, "processTimeout: msg=" + msg);
            }

            if (mCurrentCommand == null) {
                Log.wtf(TAG, "processTimeout: no existing command stored!? msg=" + msg);
                mCurrentTransactionId = TRANSACTION_ID_IGNORE;
                return;
            }

            /*
             * Only have to handle those COMMANDs which wait for a response.
             */
            switch (msg.arg1) {
                case COMMAND_TYPE_CONNECT: {
                    onConfigFailedLocal(mCurrentCommand, WifiNanEventCallback.REASON_OTHER);
                    break;
                }
                case COMMAND_TYPE_DISCONNECT: {
                    /*
                     * Will only get here on DISCONNECT if was downgrading. The
                     * callback will do a NOP - but should still call it.
                     */
                    onConfigFailedLocal(mCurrentCommand, WifiNanEventCallback.REASON_OTHER);
                    break;
                }
                case COMMAND_TYPE_TERMINATE_SESSION: {
                    Log.wtf(TAG, "processTimeout: TERMINATE_SESSION - shouldn't be waiting!");
                    break;
                }
                case COMMAND_TYPE_PUBLISH: {
                    onSessionConfigFailLocal(mCurrentCommand, true,
                            WifiNanSessionCallback.REASON_OTHER);
                    break;
                }
                case COMMAND_TYPE_UPDATE_PUBLISH: {
                    onSessionConfigFailLocal(mCurrentCommand, true,
                            WifiNanSessionCallback.REASON_OTHER);
                    break;
                }
                case COMMAND_TYPE_SUBSCRIBE: {
                    onSessionConfigFailLocal(mCurrentCommand, false,
                            WifiNanSessionCallback.REASON_OTHER);
                    break;
                }
                case COMMAND_TYPE_UPDATE_SUBSCRIBE: {
                    onSessionConfigFailLocal(mCurrentCommand, false,
                            WifiNanSessionCallback.REASON_OTHER);
                    break;
                }
                case COMMAND_TYPE_SEND_MESSAGE: {
                    onMessageSendFailLocal(mCurrentCommand, WifiNanSessionCallback.REASON_TX_FAIL);
                    break;
                }
                case COMMAND_TYPE_ENABLE_USAGE:
                    Log.wtf(TAG, "processTimeout: ENABLE_USAGE - shouldn't be waiting!");
                    break;
                case COMMAND_TYPE_DISABLE_USAGE:
                    Log.wtf(TAG, "processTimeout: DISABLE_USAGE - shouldn't be waiting!");
                    break;
                default:
                    Log.wtf(TAG, "processTimeout: this isn't a COMMAND -- msg=" + msg);
                    /* fall-through */
            }
        }

        @Override
        protected String getLogRecString(Message msg) {
            StringBuffer sb = new StringBuffer(WifiNanStateManager.messageToString(msg));

            if (msg.what == MESSAGE_TYPE_COMMAND
                    && mCurrentTransactionId != TRANSACTION_ID_IGNORE) {
                sb.append(" (Transaction ID=" + mCurrentTransactionId + ")");
            }

            return sb.toString();
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("WifiNanStateMachine:");
            pw.println("  mNextTransactionId: " + mNextTransactionId);
            pw.println("  mNextSessionId: " + mNextSessionId);
            pw.println("  mCurrentCommand: " + mCurrentCommand);
            pw.println("  mCurrentTransaction: " + mCurrentTransactionId);
            super.dump(fd, pw, args);
        }
    }

    private void sendNanStateChangedBroadcast(boolean enabled) {
        if (VDBG) {
            Log.v(TAG, "sendNanStateChangedBroadcast: enabled=" + enabled);
        }
        final Intent intent = new Intent(WifiNanManager.WIFI_NAN_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        if (enabled) {
            intent.putExtra(WifiNanManager.EXTRA_WIFI_STATE, WifiNanManager.WIFI_NAN_STATE_ENABLED);
        } else {
            intent.putExtra(WifiNanManager.EXTRA_WIFI_STATE,
                    WifiNanManager.WIFI_NAN_STATE_DISABLED);
        }
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /*
     * COMMANDS
     */

    private boolean connectLocal(short transactionId, int clientId, IWifiNanEventCallback callback,
            ConfigRequest configRequest) {
        if (VDBG) {
            Log.v(TAG, "connectLocal(): transactionId=" + transactionId + ", clientId=" + clientId
                    + ", callback=" + callback + ", configRequest=" + configRequest);
        }

        if (!mUsageEnabled) {
            Log.w(TAG, "connect(): called with mUsageEnabled=false");
            return false;
        }

        if (mClients.get(clientId) != null) {
            Log.e(TAG, "connectLocal: entry already exists for clientId=" + clientId);
        }

        if (mCurrentNanConfiguration != null
                && !mCurrentNanConfiguration.equalsOnTheAir(configRequest)) {
            try {
                callback.onConnectFail(
                        WifiNanEventCallback.REASON_ALREADY_CONNECTED_INCOMPAT_CONFIG);
            } catch (RemoteException e) {
                Log.w(TAG, "connectLocal onConnectFail(): RemoteException (FYI): " + e);
            }
            return false;
        }

        ConfigRequest merged = mergeConfigRequests(configRequest);
        if (mCurrentNanConfiguration != null && mCurrentNanConfiguration.equals(merged)) {
            try {
                callback.onConnectSuccess();
                mClients.append(clientId,
                        new WifiNanClientState(clientId, callback, configRequest));
            } catch (RemoteException e) {
                Log.w(TAG, "connectLocal onConnectSuccess(): RemoteException (FYI): " + e);
            }
            return false;
        }

        return WifiNanNative.getInstance().enableAndConfigure(transactionId, merged,
                mCurrentNanConfiguration == null);
    }

    private boolean disconnectLocal(short transactionId, int clientId) {
        if (VDBG) {
            Log.v(TAG,
                    "disconnectLocal(): transactionId=" + transactionId + ", clientId=" + clientId);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "disconnectLocal: no entry for clientId=" + clientId);
            return false;
        }
        mClients.delete(clientId);
        client.destroy();

        if (mClients.size() == 0) {
            mCurrentNanConfiguration = null;
            WifiNanNative.getInstance().disable((short) 0);
            return false;
        }

        ConfigRequest merged = mergeConfigRequests(null);
        if (merged.equals(mCurrentNanConfiguration)) {
            return false;
        }

        return WifiNanNative.getInstance().enableAndConfigure(transactionId, merged, false);
    }

    private void terminateSessionLocal(int clientId, int sessionId) {
        if (VDBG) {
            Log.v(TAG,
                    "terminateSessionLocal(): clientId=" + clientId + ", sessionId=" + sessionId);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "terminateSession: no client exists for clientId=" + clientId);
            return;
        }

        client.terminateSession(sessionId);
    }

    private boolean publishLocal(short transactionId, int clientId, PublishConfig publishConfig,
            IWifiNanSessionCallback callback) {
        if (VDBG) {
            Log.v(TAG, "publishLocal(): transactionId=" + transactionId + ", clientId=" + clientId
                    + ", publishConfig=" + publishConfig + ", callback=" + callback);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "publishLocal: no client exists for clientId=" + clientId);
            return false;
        }

        return WifiNanNative.getInstance().publish(transactionId, 0, publishConfig);
    }

    private boolean updatePublishLocal(short transactionId, int clientId, int sessionId,
            PublishConfig publishConfig) {
        if (VDBG) {
            Log.v(TAG, "updatePublishLocal(): transactionId=" + transactionId + ", clientId="
                    + clientId + ", sessionId=" + sessionId + ", publishConfig=" + publishConfig);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "updatePublishLocal: no client exists for clientId=" + clientId);
            return false;
        }

        WifiNanSessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "updatePublishLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return false;
        }

        return session.updatePublish(transactionId, publishConfig);
    }

    private boolean subscribeLocal(short transactionId, int clientId,
            SubscribeConfig subscribeConfig, IWifiNanSessionCallback callback) {
        if (VDBG) {
            Log.v(TAG, "subscribeLocal(): transactionId=" + transactionId + ", clientId=" + clientId
                    + ", subscribeConfig=" + subscribeConfig + ", callback=" + callback);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "subscribeLocal: no client exists for clientId=" + clientId);
            return false;
        }

        return WifiNanNative.getInstance().subscribe(transactionId, 0, subscribeConfig);
    }

    private boolean updateSubscribeLocal(short transactionId, int clientId, int sessionId,
            SubscribeConfig subscribeConfig) {
        if (VDBG) {
            Log.v(TAG,
                    "updateSubscribeLocal(): transactionId=" + transactionId + ", clientId="
                            + clientId + ", sessionId=" + sessionId + ", subscribeConfig="
                            + subscribeConfig);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "updateSubscribeLocal: no client exists for clientId=" + clientId);
            return false;
        }

        WifiNanSessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "updateSubscribeLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return false;
        }

        return session.updateSubscribe(transactionId, subscribeConfig);
    }

    private boolean sendFollowonMessageLocal(short transactionId, int clientId, int sessionId,
            int peerId, byte[] message, int messageLength, int messageId) {
        if (VDBG) {
            Log.v(TAG,
                    "sendFollowonMessageLocal(): transactionId=" + transactionId + ", clientId="
                            + clientId + ", sessionId=" + sessionId + ", peerId=" + peerId
                            + ", messageLength=" + messageLength + ", messageId=" + messageId);
        }

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "sendFollowonMessageLocal: no client exists for clientId=" + clientId);
            return false;
        }

        WifiNanSessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "sendFollowonMessageLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return false;
        }

        return session.sendMessage(transactionId, peerId, message, messageLength, messageId);
    }

    private void enableUsageLocal() {
        if (mUsageEnabled) {
            return;
        }

        mUsageEnabled = true;
        sendNanStateChangedBroadcast(true);
    }

    private void disableUsageLocal() {
        if (!mUsageEnabled) {
            return;
        }

        mUsageEnabled = false;
        WifiNanNative.getInstance().disable((short) 0);
        onNanDownLocal();

        sendNanStateChangedBroadcast(false);
    }

    /*
     * RESPONSES
     */

    private void onConfigCompletedLocal(Message completedCommand) {
        if (VDBG) {
            Log.v(TAG, "onConfigCompleted: completedCommand=" + completedCommand);
        }

        if (completedCommand.arg1 == COMMAND_TYPE_CONNECT) {
            Bundle data = completedCommand.getData();

            int clientId = completedCommand.arg2;
            IWifiNanEventCallback callback = (IWifiNanEventCallback) completedCommand.obj;
            ConfigRequest configRequest = (ConfigRequest) data
                    .getParcelable(MESSAGE_BUNDLE_KEY_CONFIG);

            mClients.put(clientId, new WifiNanClientState(clientId, callback, configRequest));
            try {
                callback.onConnectSuccess();
            } catch (RemoteException e) {
                Log.w(TAG,
                        "onConfigCompletedLocal onConnectSuccess(): RemoteException (FYI): " + e);
            }
        } else if (completedCommand.arg1 == COMMAND_TYPE_DISCONNECT) {
            /*
             * NOP (i.e. updated configuration after disconnecting a client)
             */
        } else {
            Log.wtf(TAG, "onConfigCompletedLocal: unexpected completedCommand=" + completedCommand);
            return;
        }

        mCurrentNanConfiguration = mergeConfigRequests(null);
    }

    private void onConfigFailedLocal(Message failedCommand, int reason) {
        if (VDBG) {
            Log.v(TAG,
                    "onConfigFailedLocal: failedCommand=" + failedCommand + ", reason=" + reason);
        }

        if (failedCommand.arg1 == COMMAND_TYPE_CONNECT) {
            IWifiNanEventCallback callback = (IWifiNanEventCallback) failedCommand.obj;

            try {
                callback.onConnectFail(reason);
            } catch (RemoteException e) {
                Log.w(TAG, "onConfigFailedLocal onConnectFail(): RemoteException (FYI): " + e);
            }
        } else if (failedCommand.arg1 == COMMAND_TYPE_DISCONNECT) {
            /*
             * NOP (tried updating configuration after disconnecting a client -
             * shouldn't fail but there's nothing to do - the old configuration
             * is still up-and-running).
             */
        } else {
            Log.wtf(TAG, "onConfigFailedLocal: unexpected failedCommand=" + failedCommand);
            return;
        }

    }

    private void onSessionConfigSuccessLocal(Message completedCommand, int pubSubId,
            boolean isPublish) {
        if (VDBG) {
            Log.v(TAG, "onSessionConfigSuccessLocal: completedCommand=" + completedCommand
                    + ", pubSubId=" + pubSubId + ", isPublish=" + isPublish);
        }

        if (completedCommand.arg1 == COMMAND_TYPE_PUBLISH
                || completedCommand.arg1 == COMMAND_TYPE_SUBSCRIBE) {
            int clientId = completedCommand.arg2;
            IWifiNanSessionCallback callback = (IWifiNanSessionCallback) completedCommand.obj;

            WifiNanClientState client = mClients.get(clientId);
            if (client == null) {
                Log.e(TAG,
                        "onSessionConfigSuccessLocal: no client exists for clientId=" + clientId);
                return;
            }

            int sessionId = mSm.mNextSessionId++;
            try {
                callback.onSessionStarted(sessionId);
            } catch (RemoteException e) {
                Log.e(TAG, "onSessionConfigSuccessLocal: onSessionStarted() RemoteException=" + e);
                return;
            }

            WifiNanSessionState session = new WifiNanSessionState(sessionId, pubSubId, callback,
                    isPublish);
            client.addSession(session);
        } else if (completedCommand.arg1 == COMMAND_TYPE_UPDATE_PUBLISH
                || completedCommand.arg1 == COMMAND_TYPE_UPDATE_SUBSCRIBE) {
            int clientId = completedCommand.arg2;
            int sessionId = completedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);

            WifiNanClientState client = mClients.get(clientId);
            if (client == null) {
                Log.e(TAG,
                        "onSessionConfigSuccessLocal: no client exists for clientId=" + clientId);
                return;
            }

            WifiNanSessionState session = client.getSession(sessionId);
            if (session == null) {
                Log.e(TAG, "onSessionConfigSuccessLocal: no session exists for clientId=" + clientId
                        + ", sessionId=" + sessionId);
                return;
            }

            try {
                session.getCallback().onSessionConfigSuccess();
            } catch (RemoteException e) {
                Log.e(TAG, "onSessionConfigSuccessLocal: onSessionConfigSuccess() RemoteException="
                        + e);
            }
        } else {
            Log.wtf(TAG,
                    "onSessionConfigSuccessLocal: unexpected completedCommand=" + completedCommand);
        }
    }

    private void onSessionConfigFailLocal(Message failedCommand, boolean isPublish, int reason) {
        if (VDBG) {
            Log.v(TAG, "onSessionConfigFailLocal: failedCommand=" + failedCommand + ", isPublish="
                    + isPublish + ", reason=" + reason);
        }

        if (failedCommand.arg1 == COMMAND_TYPE_PUBLISH
                || failedCommand.arg1 == COMMAND_TYPE_SUBSCRIBE) {
            IWifiNanSessionCallback callback = (IWifiNanSessionCallback) failedCommand.obj;
            try {
                callback.onSessionConfigFail(reason);
            } catch (RemoteException e) {
                Log.w(TAG, "onSessionConfigFailLocal onSessionConfigFail(): RemoteException (FYI): "
                        + e);
            }
        } else if (failedCommand.arg1 == COMMAND_TYPE_UPDATE_PUBLISH
                || failedCommand.arg1 == COMMAND_TYPE_UPDATE_SUBSCRIBE) {
            int clientId = failedCommand.arg2;
            int sessionId = failedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);

            WifiNanClientState client = mClients.get(clientId);
            if (client == null) {
                Log.e(TAG, "onSessionConfigFailLocal: no client exists for clientId=" + clientId);
                return;
            }

            WifiNanSessionState session = client.getSession(sessionId);
            if (session == null) {
                Log.e(TAG, "onSessionConfigFailLocal: no session exists for clientId=" + clientId
                        + ", sessionId=" + sessionId);
                return;
            }

            try {
                session.getCallback().onSessionConfigFail(reason);
            } catch (RemoteException e) {
                Log.e(TAG, "onSessionConfigFailLocal: onSessionConfigFail() RemoteException=" + e);
            }
        } else {
            Log.wtf(TAG, "onSessionConfigFailLocal: unexpected failedCommand=" + failedCommand);
        }
    }

    private void onMessageSendSuccessLocal(Message completedCommand) {
        if (VDBG) {
            Log.v(TAG, "onMessageSendSuccess: completedCommand=" + completedCommand);
        }

        int clientId = completedCommand.arg2;
        int sessionId = completedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
        int messageId = completedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID);

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "onMessageSendSuccessLocal: no client exists for clientId=" + clientId);
            return;
        }

        WifiNanSessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "onMessageSendSuccessLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return;
        }

        try {
            session.getCallback().onMessageSendSuccess(messageId);
        } catch (RemoteException e) {
            Log.w(TAG, "onMessageSendSuccessLocal: RemoteException (FYI): " + e);
        }
    }

    private void onMessageSendFailLocal(Message failedCommand, int reason) {
        if (VDBG) {
            Log.v(TAG, "onMessageSendFail: failedCommand=" + failedCommand + ", reason=" + reason);
        }

        int clientId = failedCommand.arg2;
        int sessionId = failedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
        int messageId = failedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID);

        WifiNanClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "onMessageSendFailLocal: no client exists for clientId=" + clientId);
            return;
        }

        WifiNanSessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "onMessageSendFailLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return;
        }

        try {
            session.getCallback().onMessageSendFail(messageId, reason);
        } catch (RemoteException e) {
            Log.e(TAG, "onMessageSendFailLocal: onMessageSendFail RemoteException=" + e);
        }
    }

    /*
     * NOTIFICATIONS
     */

    private void onCapabilitiesUpdatedLocal(WifiNanNative.Capabilities capabilities) {
        if (VDBG) {
            Log.v(TAG, "onCapabilitiesUpdatedLocal: capabilites=" + capabilities);
        }

        mCapabilities = capabilities;
    }

    private void onInterfaceAddressChangeLocal(byte[] mac) {
        if (VDBG) {
            Log.v(TAG, "onInterfaceAddressChange: mac=" + String.valueOf(HexEncoding.encode(mac)));
        }

        int interested = 0;
        for (int i = 0; i < mClients.size(); ++i) {
            WifiNanClientState client = mClients.valueAt(i);
            interested += client.onInterfaceAddressChange(mac);
        }

        if (interested == 0) {
            Log.e(TAG, "onInterfaceAddressChange: event received but no callbacks registered "
                    + "for this event - should be disabled from fw!");
        }
    }

    private void onClusterChangeLocal(int flag, byte[] clusterId) {
        if (VDBG) {
            Log.v(TAG, "onClusterChange: flag=" + flag + ", clusterId="
                    + String.valueOf(HexEncoding.encode(clusterId)));
        }

        int interested = 0;
        for (int i = 0; i < mClients.size(); ++i) {
            WifiNanClientState client = mClients.valueAt(i);
            interested += client.onClusterChange(flag, clusterId);
        }

        if (interested == 0) {
            Log.e(TAG, "onClusterChange: event received but no callbacks registered for this "
                    + "event - should be disabled from fw!");
        }
    }

    private void onMatchLocal(int pubSubId, int requestorInstanceId, byte[] peerMac,
            byte[] serviceSpecificInfo, int serviceSpecificInfoLength, byte[] matchFilter,
            int matchFilterLength) {
        if (VDBG) {
            Log.v(TAG, "onMatch: pubSubId=" + pubSubId + ", requestorInstanceId="
                    + requestorInstanceId + ", peerMac="
                    + String.valueOf(HexEncoding.encode(peerMac)) + ", serviceSpecificInfoLength="
                    + serviceSpecificInfoLength + ", serviceSpecificInfo="
                    + Arrays.toString(serviceSpecificInfo) + ", matchFilterLength="
                    + matchFilterLength + ", matchFilter=" + Arrays.toString(matchFilter));
        }

        Pair<WifiNanClientState, WifiNanSessionState> data = getClientSessionForPubSubId(pubSubId);
        if (data == null) {
            Log.e(TAG, "onMatch: no session found for pubSubId=" + pubSubId);
            return;
        }

        data.second.onMatch(requestorInstanceId, peerMac, serviceSpecificInfo,
                serviceSpecificInfoLength, matchFilter, matchFilterLength);
    }

    private void onSessionTerminatedLocal(int pubSubId, boolean isPublish, int reason) {
        if (VDBG) {
            Log.v(TAG, "onSessionTerminatedLocal: pubSubId=" + pubSubId + ", isPublish=" + isPublish
                    + ", reason=" + reason);
        }

        Pair<WifiNanClientState, WifiNanSessionState> data = getClientSessionForPubSubId(pubSubId);
        if (data == null) {
            Log.e(TAG, "onSessionTerminatedLocal: no session found for pubSubId=" + pubSubId);
            return;
        }

        try {
            data.second.getCallback().onSessionTerminated(reason);
        } catch (RemoteException e) {
            Log.w(TAG,
                    "onSessionTerminatedLocal onSessionTerminated(): RemoteException (FYI): " + e);
        }
        data.first.removeSession(data.second.getSessionId());
    }

    private void onMessageReceivedLocal(int pubSubId, int requestorInstanceId, byte[] peerMac,
            byte[] message, int messageLength) {
        if (VDBG) {
            Log.v(TAG,
                    "onMessageReceivedLocal: pubSubId=" + pubSubId + ", requestorInstanceId="
                            + requestorInstanceId + ", peerMac="
                            + String.valueOf(HexEncoding.encode(peerMac)) + ", messageLength="
                            + messageLength);
        }

        Pair<WifiNanClientState, WifiNanSessionState> data = getClientSessionForPubSubId(pubSubId);
        if (data == null) {
            Log.e(TAG, "onMessageReceivedLocal: no session found for pubSubId=" + pubSubId);
            return;
        }

        data.second.onMessageReceived(requestorInstanceId, peerMac, message, messageLength);
    }

    private void onNanDownLocal() {
        if (VDBG) {
            Log.v(TAG, "onNanDown");
        }

        mClients.clear();
        mCurrentNanConfiguration = null;
    }

    /*
     * Utilities
     */

    private Pair<WifiNanClientState, WifiNanSessionState> getClientSessionForPubSubId(
            int pubSubId) {
        for (int i = 0; i < mClients.size(); ++i) {
            WifiNanClientState client = mClients.valueAt(i);
            WifiNanSessionState session = client.getNanSessionStateForPubSubId(pubSubId);
            if (session != null) {
                return new Pair<>(client, session);
            }
        }

        return null;
    }

    private ConfigRequest mergeConfigRequests(ConfigRequest configRequest) {
        if (VDBG) {
            Log.v(TAG, "mergeConfigRequests(): mClients=[" + mClients + "], configRequest="
                    + configRequest);
        }

        if (mClients.size() == 0 && configRequest == null) {
            Log.e(TAG, "mergeConfigRequests: invalid state - called with 0 clients registered!");
            return null;
        }

        // TODO: continue working on merge algorithm:
        // - if any request 5g: enable
        // - maximal master preference
        // - cluster range covering all requests: assume that [0,max] is a
        // non-request
        // - if any request identity change: enable
        boolean support5gBand = false;
        int masterPreference = 0;
        boolean clusterIdValid = false;
        int clusterLow = 0;
        int clusterHigh = ConfigRequest.CLUSTER_ID_MAX;
        boolean identityChange = false;
        if (configRequest != null) {
            support5gBand = configRequest.mSupport5gBand;
            masterPreference = configRequest.mMasterPreference;
            clusterIdValid = true;
            clusterLow = configRequest.mClusterLow;
            clusterHigh = configRequest.mClusterHigh;
            identityChange = configRequest.mEnableIdentityChangeCallback;
        }
        for (int i = 0; i < mClients.size(); ++i) {
            ConfigRequest cr = mClients.valueAt(i).getConfigRequest();

            if (cr.mSupport5gBand) {
                support5gBand = true;
            }

            masterPreference = Math.max(masterPreference, cr.mMasterPreference);

            if (cr.mClusterLow != 0 || cr.mClusterHigh != ConfigRequest.CLUSTER_ID_MAX) {
                if (!clusterIdValid) {
                    clusterLow = cr.mClusterLow;
                    clusterHigh = cr.mClusterHigh;
                } else {
                    clusterLow = Math.min(clusterLow, cr.mClusterLow);
                    clusterHigh = Math.max(clusterHigh, cr.mClusterHigh);
                }
                clusterIdValid = true;
            }

            if (cr.mEnableIdentityChangeCallback) {
                identityChange = true;
            }
        }
        return new ConfigRequest.Builder().setSupport5gBand(support5gBand)
                .setMasterPreference(masterPreference).setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh).setEnableIdentityChangeCallback(identityChange)
                .build();
    }

    private static String messageToString(Message msg) {
        StringBuilder sb = new StringBuilder();

        switch (msg.what) {
            case MESSAGE_TYPE_NOTIFICATION:
                sb.append("NOTIFICATION/");
                switch (msg.arg1) {
                    case NOTIFICATION_TYPE_CAPABILITIES_UPDATED:
                        sb.append("CAPABILITIES_UPDATED");
                        break;
                    case NOTIFICATION_TYPE_INTERFACE_CHANGE:
                        sb.append("INTERFACE_CHANGE");
                        break;
                    case NOTIFICATION_TYPE_CLUSTER_CHANGE:
                        sb.append("CLUSTER_CHANGE");
                        break;
                    case NOTIFICATION_TYPE_MATCH:
                        sb.append("MATCH");
                        break;
                    case NOTIFICATION_TYPE_SESSION_TERMINATED:
                        sb.append("SESSION_TERMINATED");
                        break;
                    case NOTIFICATION_TYPE_MESSAGE_RECEIVED:
                        sb.append("MESSAGE_RECEIVED");
                        break;
                    case NOTIFICATION_TYPE_NAN_DOWN:
                        sb.append("NAN_DOWN");
                        break;
                    default:
                        sb.append("<unknown>");
                        break;
                }
                break;
            case MESSAGE_TYPE_COMMAND:
                sb.append("COMMAND/");
                switch (msg.arg1) {
                    case COMMAND_TYPE_CONNECT:
                        sb.append("CONNECT");
                        break;
                    case COMMAND_TYPE_DISCONNECT:
                        sb.append("DISCONNECT");
                        break;
                    case COMMAND_TYPE_TERMINATE_SESSION:
                        sb.append("TERMINATE_SESSION");
                        break;
                    case COMMAND_TYPE_PUBLISH:
                        sb.append("PUBLISH");
                        break;
                    case COMMAND_TYPE_UPDATE_PUBLISH:
                        sb.append("UPDATE_PUBLISH");
                        break;
                    case COMMAND_TYPE_SUBSCRIBE:
                        sb.append("SUBSCRIBE");
                        break;
                    case COMMAND_TYPE_UPDATE_SUBSCRIBE:
                        sb.append("UPDATE_SUBSCRIBE");
                        break;
                    case COMMAND_TYPE_SEND_MESSAGE:
                        sb.append("SEND_MESSAGE");
                        break;
                    case COMMAND_TYPE_ENABLE_USAGE:
                        sb.append("ENABLE_USAGE");
                        break;
                    case COMMAND_TYPE_DISABLE_USAGE:
                        sb.append("DISABLE_USAGE");
                        break;
                    default:
                        sb.append("<unknown>");
                        break;
                }
                break;
            case MESSAGE_TYPE_RESPONSE:
                sb.append("RESPONSE/");
                switch (msg.arg1) {
                    case RESPONSE_TYPE_ON_CONFIG_SUCCESS:
                        sb.append("ON_CONFIG_SUCCESS");
                        break;
                    case RESPONSE_TYPE_ON_CONFIG_FAIL:
                        sb.append("ON_CONFIG_FAIL");
                        break;
                    case RESPONSE_TYPE_ON_SESSION_CONFIG_SUCCESS:
                        sb.append("ON_SESSION_CONFIG_SUCCESS");
                        break;
                    case RESPONSE_TYPE_ON_SESSION_CONFIG_FAIL:
                        sb.append("ON_SESSION_CONFIG_FAIL");
                        break;
                    case RESPONSE_TYPE_ON_MESSAGE_SEND_SUCCESS:
                        sb.append("ON_MESSAGE_SEND_SUCCESS");
                        break;
                    case RESPONSE_TYPE_ON_MESSAGE_SEND_FAIL:
                        sb.append("ON_MESSAGE_SEND_FAIL");
                        break;
                    default:
                        sb.append("<unknown>");
                        break;

                }
                break;
            case MESSAGE_TYPE_TIMEOUT:
                sb.append("TIMEOUT");
                break;
            default:
                sb.append("<unknown>");
        }

        if (msg.what == MESSAGE_TYPE_RESPONSE || msg.what == MESSAGE_TYPE_TIMEOUT) {
            sb.append(" (Transaction ID=" + msg.arg2 + ")");
        }

        return sb.toString();
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NanStateManager:");
        pw.println("  mClients: [" + mClients + "]");
        pw.println("  mUsageEnabled: " + mUsageEnabled);
        pw.println("  mCapabilities: [" + mCapabilities + "]");
        pw.println("  mCurrentNanConfiguration: " + mCurrentNanConfiguration);
        for (int i = 0; i < mClients.size(); ++i) {
            mClients.valueAt(i).dump(fd, pw, args);
        }
        mSm.dump(fd, pw, args);
    }
}
