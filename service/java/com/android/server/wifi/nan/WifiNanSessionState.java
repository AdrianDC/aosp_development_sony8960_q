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
    private boolean mIsPublishSession;

    private boolean mSessionValid = false;
    private int mPubSubId;

    public WifiNanSessionState(int sessionId, IWifiNanSessionCallback callback,
            boolean isPublishSession) {
        mSessionId = sessionId;
        mCallback = callback;
        mIsPublishSession = isPublishSession;
    }

    /**
     * Destroy the current discovery session - stops publishing or subscribing
     * if currently active.
     */
    public void terminate() {
        if (!mSessionValid) {
            if (DBG) {
                Log.d(TAG, "terminate: attempting to terminate an already terminated session");
            }
            return;
        }

        short transactionId = WifiNanStateManager.getInstance().createNextTransactionId();
        if (mIsPublishSession) {
            WifiNanNative.getInstance().stopPublish(transactionId, mPubSubId);
        } else {
            WifiNanNative.getInstance().stopSubscribe(transactionId, mPubSubId);
        }

        mSessionValid = false;
        mCallback = null;
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
        return mSessionValid && mPubSubId == pubSubId;
    }

    /**
     * Start a publish discovery session.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param config Configuration of the publish session.
     */
    public void publish(short transactionId, PublishConfig config) {
        WifiNanNative.getInstance().publish(transactionId, 0, config);
    }

    /**
     * Modify a publish discovery session.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param config Configuration of the publish session.
     */
    public void updatePublish(short transactionId, PublishConfig config) {
        if (!mIsPublishSession) {
            Log.e(TAG, "A SUBSCRIBE session is being used to publish");
            WifiNanStateManager.getInstance().onPublishFail(transactionId,
                    WifiNanSessionCallback.FAIL_REASON_OTHER);
            return;
        }
        if (!mSessionValid) {
            Log.e(TAG, "Attempting a re-publish on a terminated session");
            onPublishFail(WifiNanSessionCallback.FAIL_REASON_SESSION_TERMINATED);
            return;
        }

        WifiNanNative.getInstance().publish(transactionId, mPubSubId, config);
    }

    /**
     * Start a subscribe discovery session.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param config Configuration of the subscribe session.
     */
    public void subscribe(short transactionId, SubscribeConfig config) {
        WifiNanNative.getInstance().subscribe(transactionId, 0, config);
    }

    /**
     * Modify a subscribe discovery session.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param config Configuration of the subscribe session.
     */
    public void updateSubscribe(short transactionId, SubscribeConfig config) {
        if (mIsPublishSession) {
            Log.e(TAG, "A PUBLISH session is being used to subscribe");
            WifiNanStateManager.getInstance().onSubscribeFail(transactionId,
                    WifiNanSessionCallback.FAIL_REASON_OTHER);
            return;
        }
        if (!mSessionValid) {
            Log.e(TAG, "Attempting a re-subscribe on a terminated session");
            onSubscribeFail(WifiNanSessionCallback.FAIL_REASON_SESSION_TERMINATED);
            return;
        }

        WifiNanNative.getInstance().subscribe(transactionId, mPubSubId, config);
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
        if (!mSessionValid) {
            Log.e(TAG, "sendMessage: attempting to send a message on a terminated session "
                    + "(no successful publish or subscribe");
            onMessageSendFail(messageId, WifiNanSessionCallback.FAIL_REASON_SESSION_TERMINATED);
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
     * Callback from HAL updating session in case of publish session creation
     * success (i.e. publish session configured correctly and is active).
     *
     * @param publishId The HAL id of the (now active) publish session.
     */
    public void onPublishSuccess(int publishId) {
        if (mSessionValid) {
            // indicates an update-publish: no need to inform client
            return;
        }
        mPubSubId = publishId;
        mSessionValid = true;
        try {
            if (mCallback != null) {
                mCallback.onSessionStarted(mSessionId);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onPublishSuccess: RemoteException (FYI): " + e);
        }
    }

    /**
     * Callback from HAL updating session in case of publish session creation
     * fail. Propagates call to client if registered.
     *
     * @param status Reason code for failure.
     */
    public void onPublishFail(int status) {
        try {
            if (mCallback != null) {
                mCallback.onSessionConfigFail(status);
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
        mSessionValid = false;
        try {
            if (mCallback != null) {
                mCallback.onSessionTerminated(status);
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
        if (mSessionValid) {
            // indicates an update-publish: no need to inform client
            return;
        }

        mPubSubId = subscribeId;
        mSessionValid = true;
        try {
            if (mCallback != null) {
                mCallback.onSessionStarted(mSessionId);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "onSubscribeSuccess: RemoteException (FYI): " + e);
        }
    }

    /**
     * Callback from HAL updating session in case of subscribe session creation
     * fail. Propagates call to client if registered.
     *
     * @param status Reason code for failure.
     */
    public void onSubscribeFail(int status) {
        try {
            if (mCallback != null) {
                mCallback.onSessionConfigFail(status);
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
        mSessionValid = false;
        try {
            if (mCallback != null) {
                mCallback.onSessionTerminated(status);
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
            if (mCallback != null) {
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
            if (mCallback != null) {
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
            if (mCallback != null) {
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
            if (mCallback != null) {
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
        pw.println("  mIsPublishSession: " + mIsPublishSession);
        pw.println("  mPubSubId: " + (mSessionValid ? Integer.toString(mPubSubId) : "not valid"));
        pw.println("  mMacByRequestorInstanceId: [" + mMacByRequestorInstanceId + "]");
    }
}
