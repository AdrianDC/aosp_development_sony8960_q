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

import android.net.wifi.nan.IWifiNanSessionCallback;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.net.wifi.nan.WifiNanSessionCallback;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Manages the state of a single NAN discovery session (publish or subscribe).
 * Primary state consists of a callback through which session callbacks are
 * executed as well as state related to currently active discovery sessions:
 * publish/subscribe ID, and MAC address caching (hiding) from clients.
 */
public class WifiNanSessionState {
    private static final String TAG = "WifiNanSessionState";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private final SparseArray<String> mMacByRequestorInstanceId = new SparseArray<>();

    private int mSessionId;
    private IWifiNanSessionCallback mCallback;
    private int mEvents;

    private boolean mPubSubIdValid = false;
    private int mPubSubId;

    private static final int SESSION_TYPE_NOT_INIT = 0;
    private static final int SESSION_TYPE_PUBLISH = 1;
    private static final int SESSION_TYPE_SUBSCRIBE = 2;
    private int mSessionType = SESSION_TYPE_NOT_INIT;

    public WifiNanSessionState(int sessionId, IWifiNanSessionCallback callback, int events) {
        mSessionId = sessionId;
        mCallback = callback;
        mEvents = events;
    }

    /**
     * Destroy the current discovery session - stops publishing or subscribing
     * if currently active.
     */
    public void destroy() {
        stop(WifiNanStateManager.getInstance().createNextTransactionId());
        if (mPubSubIdValid) {
            mMacByRequestorInstanceId.clear();
            mCallback = null;
            mPubSubIdValid = false;
        }
    }

    public int getSessionId() {
        return mSessionId;
    }

    /**
     * Indicates whether the publish/subscribe ID (a HAL ID) corresponds to this
     * session.
     *
     * @param pubSubId The publish/subscribe HAL ID to be tested.
     * @return true if corresponds to this session, false otherwise.
     */
    public boolean isPubSubIdSession(int pubSubId) {
        return mPubSubIdValid && mPubSubId == pubSubId;
    }

    /**
     * Start a publish discovery session.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param config Configuration of the publish session.
     */
    public void publish(short transactionId, PublishConfig config) {
        if (mSessionType == SESSION_TYPE_SUBSCRIBE) {
            throw new IllegalStateException("A SUBSCRIBE session is being used for publish");
        }
        mSessionType = SESSION_TYPE_PUBLISH;

        WifiNanNative.getInstance().publish(transactionId, mPubSubIdValid ? mPubSubId : 0, config);
    }

    /**
     * Start a subscribe discovery session.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param config Configuration of the subscribe session.
     */
    public void subscribe(short transactionId, SubscribeConfig config) {
        if (mSessionType == SESSION_TYPE_PUBLISH) {
            throw new IllegalStateException("A PUBLISH session is being used for publish");
        }
        mSessionType = SESSION_TYPE_SUBSCRIBE;

        WifiNanNative.getInstance().subscribe(transactionId, mPubSubIdValid ? mPubSubId : 0,
                config);
    }

    /**
     * Send a message to a peer which is part of a discovery session.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param peerId ID of the peer. Obtained through previous communication (a
     *            match indication).
     * @param message Message byte array to send to the peer.
     * @param messageLength Length of the message byte array.
     * @param messageId A message ID provided by caller to be used in any
     *            callbacks related to the message (success/failure).
     */
    public void sendMessage(short transactionId, int peerId, byte[] message, int messageLength,
            int messageId) {
        if (!mPubSubIdValid) {
            Log.e(TAG, "sendMessage: attempting to send a message on a non-live session "
                    + "(no successful publish or subscribe");
            onMessageSendFail(messageId, WifiNanSessionCallback.FAIL_REASON_NO_MATCH_SESSION);
            return;
        }

        String peerMacStr = mMacByRequestorInstanceId.get(peerId);
        if (peerMacStr == null) {
            Log.e(TAG, "sendMessage: attempting to send a message to an address which didn't "
                    + "match/contact us");
            onMessageSendFail(messageId, WifiNanSessionCallback.FAIL_REASON_NO_MATCH_SESSION);
            return;
        }
        byte[] peerMac = HexEncoding.decode(peerMacStr.toCharArray(), false);

        WifiNanNative.getInstance().sendMessage(transactionId, mPubSubId, peerId, peerMac, message,
                messageLength);
    }

    /**
     * Stops an active discovery session - without clearing out any state.
     * Allows restarting/continuing discovery at a later time.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     */
    public void stop(short transactionId) {
        if (!mPubSubIdValid || mSessionType == SESSION_TYPE_NOT_INIT) {
            Log.e(TAG, "sendMessage: attempting to stop pub/sub on a non-live session (no "
                    + "successful publish or subscribe");
            return;
        }

        if (mSessionType == SESSION_TYPE_PUBLISH) {
            WifiNanNative.getInstance().stopPublish(transactionId, mPubSubId);
        } else if (mSessionType == SESSION_TYPE_SUBSCRIBE) {
            WifiNanNative.getInstance().stopSubscribe(transactionId, mPubSubId);
        }
    }

    /**
     * Callback from HAL updating session in case of publish session creation
     * success (i.e. publish session configured correctly and is active).
     *
     * @param publishId The HAL id of the (now active) publish session.
     */
    public void onPublishSuccess(int publishId) {
        mPubSubId = publishId;
        mPubSubIdValid = true;
    }

    /**
     * Callback from HAL updating session in case of publish session creation
     * fail. Propagates call to client if registered.
     *
     * @param status Reason code for failure.
     */
    public void onPublishFail(int status) {
        mPubSubIdValid = false;
        try {
            if (mCallback != null
                    && (mEvents & WifiNanSessionCallback.FLAG_LISTEN_PUBLISH_FAIL) != 0) {
                mCallback.onPublishFail(status);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onPublishFail: RemoteException (FYI): " + e);
        }
    }

    /**
     * Callback from HAL updating session when the publish session has
     * terminated (per plan or due to failure). Propagates call to client if
     * registered.
     *
     * @param status Reason code for session termination.
     */
    public void onPublishTerminated(int status) {
        mPubSubIdValid = false;
        try {
            if (mCallback != null
                    && (mEvents & WifiNanSessionCallback.FLAG_LISTEN_PUBLISH_TERMINATED) != 0) {
                mCallback.onPublishTerminated(status);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onPublishTerminated: RemoteException (FYI): " + e);
        }
    }

    /**
     * Callback from HAL updating session in case of subscribe session creation
     * success (i.e. subscribe session configured correctly and is active).
     *
     * @param subscribeId The HAL id of the (now active) subscribe session.
     */
    public void onSubscribeSuccess(int subscribeId) {
        mPubSubId = subscribeId;
        mPubSubIdValid = true;
    }

    /**
     * Callback from HAL updating session in case of subscribe session creation
     * fail. Propagates call to client if registered.
     *
     * @param status Reason code for failure.
     */
    public void onSubscribeFail(int status) {
        mPubSubIdValid = false;
        try {
            if (mCallback != null
                    && (mEvents & WifiNanSessionCallback.FLAG_LISTEN_SUBSCRIBE_FAIL) != 0) {
                mCallback.onSubscribeFail(status);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onSubscribeFail: RemoteException (FYI): " + e);
        }
    }

    /**
     * Callback from HAL updating session when the subscribe session has
     * terminated (per plan or due to failure). Propagates call to client if
     * registered.
     *
     * @param status Reason code for session termination.
     */
    public void onSubscribeTerminated(int status) {
        mPubSubIdValid = false;
        try {
            if (mCallback != null
                    && (mEvents & WifiNanSessionCallback.FLAG_LISTEN_SUBSCRIBE_TERMINATED) != 0) {
                mCallback.onSubscribeTerminated(status);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onSubscribeTerminated: RemoteException (FYI): " + e);
        }
    }

    /**
     * Callback from HAL when message is sent successfully (i.e. an ACK was
     * received). Propagates call to client if registered.
     *
     * @param messageId ID provided by caller with the message.
     */
    public void onMessageSendSuccess(int messageId) {
        try {
            if (mCallback != null
                    && (mEvents & WifiNanSessionCallback.FLAG_LISTEN_MESSAGE_SEND_SUCCESS) != 0) {
                mCallback.onMessageSendSuccess(messageId);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onMessageSendSuccess: RemoteException (FYI): " + e);
        }
    }

    /**
     * Callback from HAL when message fails to be transmitted - including when
     * transmitted but no ACK received from intended receiver. Propagates call
     * to client if registered.
     *
     * @param messageId ID provided by caller with the message.
     * @param status Reason code for transmit failure.
     */
    public void onMessageSendFail(int messageId, int status) {
        try {
            if (mCallback != null
                    && (mEvents & WifiNanSessionCallback.FLAG_LISTEN_MESSAGE_SEND_FAIL) != 0) {
                mCallback.onMessageSendFail(messageId, status);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onMessageSendFail: RemoteException (FYI): " + e);
        }
    }

    /**
     * Callback from HAL when a discovery occurs - i.e. when a match to an
     * active subscription request or to a solicited publish request occurs.
     * Propagates to client if registered.
     *
     * @param requestorInstanceId The ID used to identify the peer in this
     *            matched session.
     * @param peerMac The MAC address of the peer. Never propagated to client
     *            due to privacy concerns.
     * @param serviceSpecificInfo Information from the discovery advertisement
     *            (usually not used in the match decisions).
     * @param serviceSpecificInfoLength Length of the above information field.
     * @param matchFilter The filter from the discovery advertisement (which was
     *            used in the match decision).
     * @param matchFilterLength Length of the above filter field.
     */
    public void onMatch(int requestorInstanceId, byte[] peerMac, byte[] serviceSpecificInfo,
            int serviceSpecificInfoLength, byte[] matchFilter, int matchFilterLength) {
        String prevMac = mMacByRequestorInstanceId.get(requestorInstanceId);
        mMacByRequestorInstanceId.put(requestorInstanceId, new String(HexEncoding.encode(peerMac)));

        if (DBG) Log.d(TAG, "onMatch: previous peer MAC replaced - " + prevMac);

        try {
            if (mCallback != null && (mEvents & WifiNanSessionCallback.FLAG_LISTEN_MATCH) != 0) {
                mCallback.onMatch(requestorInstanceId, serviceSpecificInfo,
                        serviceSpecificInfoLength, matchFilter, matchFilterLength);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onMatch: RemoteException (FYI): " + e);
        }
    }

    /**
     * Callback from HAL when a message is received from a peer in a discovery
     * session. Propagated to client if registered.
     *
     * @param requestorInstanceId An ID used to identify the peer.
     * @param peerMac The MAC address of the peer sending the message. This
     *            information is never propagated to the client due to privacy
     *            concerns.
     * @param message The received message.
     * @param messageLength The length of the received message.
     */
    public void onMessageReceived(int requestorInstanceId, byte[] peerMac, byte[] message,
            int messageLength) {
        String prevMac = mMacByRequestorInstanceId.get(requestorInstanceId);
        mMacByRequestorInstanceId.put(requestorInstanceId, new String(HexEncoding.encode(peerMac)));

        if (DBG) {
            Log.d(TAG, "onMessageReceived: previous peer MAC replaced - " + prevMac);
        }

        try {
            if (mCallback != null
                    && (mEvents & WifiNanSessionCallback.FLAG_LISTEN_MESSAGE_RECEIVED) != 0) {
                mCallback.onMessageReceived(requestorInstanceId, message, messageLength);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onMessageReceived: RemoteException (FYI): " + e);
        }
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NanSessionState:");
        pw.println("  mSessionId: " + mSessionId);
        pw.println("  mSessionType: " + mSessionType);
        pw.println("  mEvents: " + mEvents);
        pw.println("  mPubSubId: " + (mPubSubIdValid ? Integer.toString(mPubSubId) : "not valid"));
        pw.println("  mMacByRequestorInstanceId: [" + mMacByRequestorInstanceId + "]");
    }
}
