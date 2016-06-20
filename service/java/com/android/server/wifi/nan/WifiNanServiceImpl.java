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
import android.content.pm.PackageManager;
import android.net.wifi.RttManager;
import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanEventCallback;
import android.net.wifi.nan.IWifiNanManager;
import android.net.wifi.nan.IWifiNanSessionCallback;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.net.wifi.nan.WifiNanEventCallback;
import android.net.wifi.nan.WifiNanSession;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Implementation of the IWifiNanManager AIDL interface. Performs validity
 * (permission and clientID-UID mapping) checks and delegates execution to the
 * WifiNanStateManager singleton handler. Limited state to feedback which has to
 * be provided instantly: client and session IDs.
 */
public class WifiNanServiceImpl extends IWifiNanManager.Stub {
    private static final String TAG = "WifiNanService";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private Context mContext;
    private WifiNanStateManager mStateManager;

    private final Object mLock = new Object();
    private final SparseArray<IBinder.DeathRecipient> mDeathRecipientsByClientId =
            new SparseArray<>();
    private int mNextClientId = 1;
    private int mNextRangingId = 1;
    private final SparseArray<Integer> mUidByClientId = new SparseArray<>();

    public WifiNanServiceImpl(Context context) {
        mContext = context.getApplicationContext();
        mStateManager = WifiNanStateManager.getInstance();
    }

    /**
     * Proxy for the final native call of the parent class. Enables mocking of
     * the function.
     */
    public int getMockableCallingUid() {
        return getCallingUid();
    }

    /**
     * Start the service: allocate a new thread (for now), start the handlers of
     * the components of the service.
     */
    public void start() {
        Log.i(TAG, "Starting Wi-Fi NAN service");

        // TODO: share worker thread with other Wi-Fi handlers (b/27924886)
        HandlerThread wifiNanThread = new HandlerThread("wifiNanService");
        wifiNanThread.start();

        mStateManager.start(mContext, wifiNanThread.getLooper());
    }

    /**
     * Start/initialize portions of the service which require the boot stage to be complete.
     */
    public void startLate() {
        Log.i(TAG, "Late initialization of Wi-Fi NAN service");

        mStateManager.startLate();
    }

    @Override
    public void enableUsage() {
        enforceAccessPermission();
        enforceChangePermission();
        /*
         * TODO: enforce additional permissions b/27696149.
         */

        mStateManager.enableUsage();
    }

    @Override
    public void disableUsage() {
        enforceAccessPermission();
        enforceChangePermission();
        /*
         * TODO: enforce additional permissions b/27696149.
         */

        mStateManager.disableUsage();

        /*
         * Potential leak (b/27796984) since we keep app information here (uid,
         * binder-link-to-death), while clearing all state information. However:
         * (1) can't clear all information since don't have binder, (2)
         * information will clear once app dies, (3) allows us to do security
         * checks in the future.
         */
    }

    @Override
    public boolean isUsageEnabled() {
        enforceAccessPermission();

        return mStateManager.isUsageEnabled();
    }

    @Override
    public int connect(final IBinder binder, IWifiNanEventCallback callback,
            ConfigRequest configRequest) {
        enforceAccessPermission();
        enforceChangePermission();
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }
        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }

        if (configRequest != null) {
            /*
             * TODO: enforce additional permissions if configuration is
             * non-standard (i.e. the system API). (b/27696149)
             */
        } else {
            configRequest = new ConfigRequest.Builder().build();
        }
        configRequest.validate();

        final int uid = getMockableCallingUid();

        final int clientId;
        synchronized (mLock) {
            clientId = mNextClientId++;
        }

        if (VDBG) {
            Log.v(TAG, "connect: uid=" + uid + ", clientId=" + clientId + ", configRequest"
                    + configRequest);
        }

        IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                if (DBG) Log.d(TAG, "binderDied: clientId=" + clientId);
                binder.unlinkToDeath(this, 0);

                synchronized (mLock) {
                    mDeathRecipientsByClientId.delete(clientId);
                    mUidByClientId.delete(clientId);
                }

                mStateManager.disconnect(clientId);
            }
        };

        try {
            binder.linkToDeath(dr, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Error on linkToDeath - " + e);
            try {
                callback.onConnectFail(WifiNanEventCallback.REASON_OTHER);
            } catch (RemoteException e1) {
                Log.e(TAG, "Error on onConnectFail()");
            }
            return 0;
        }

        synchronized (mLock) {
            mDeathRecipientsByClientId.put(clientId, dr);
            mUidByClientId.put(clientId, uid);
        }

        mStateManager.connect(clientId, uid, callback, configRequest);

        return clientId;
    }

    @Override
    public void disconnect(int clientId, IBinder binder) {
        enforceAccessPermission();
        enforceChangePermission();

        int uid = getMockableCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) Log.v(TAG, "disconnect: uid=" + uid + ", clientId=" + clientId);

        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }

        synchronized (mLock) {
            IBinder.DeathRecipient dr = mDeathRecipientsByClientId.get(clientId);
            if (dr != null) {
                binder.unlinkToDeath(dr, 0);
                mDeathRecipientsByClientId.delete(clientId);
            }
            mUidByClientId.delete(clientId);
        }

        mStateManager.disconnect(clientId);
    }

    @Override
    public void terminateSession(int clientId, int sessionId) {
        enforceAccessPermission();
        enforceChangePermission();

        int uid = getMockableCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG, "terminateSession: sessionId=" + sessionId + ", uid=" + uid + ", clientId="
                    + clientId);
        }

        mStateManager.terminateSession(clientId, sessionId);
    }

    @Override
    public void publish(int clientId, PublishConfig publishConfig,
            IWifiNanSessionCallback callback) {
        enforceAccessPermission();
        enforceChangePermission();

        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }
        if (publishConfig == null) {
            throw new IllegalArgumentException("PublishConfig must not be null");
        }
        publishConfig.validate();

        int uid = getMockableCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG, "publish: uid=" + uid + ", clientId=" + clientId + ", publishConfig="
                    + publishConfig + ", callback=" + callback);
        }

        mStateManager.publish(clientId, publishConfig, callback);
    }

    @Override
    public void updatePublish(int clientId, int sessionId, PublishConfig publishConfig) {
        enforceAccessPermission();
        enforceChangePermission();

        if (publishConfig == null) {
            throw new IllegalArgumentException("PublishConfig must not be null");
        }
        publishConfig.validate();

        int uid = getMockableCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG, "updatePublish: uid=" + uid + ", clientId=" + clientId + ", sessionId="
                    + sessionId + ", config=" + publishConfig);
        }

        mStateManager.updatePublish(clientId, sessionId, publishConfig);
    }

    @Override
    public void subscribe(int clientId, SubscribeConfig subscribeConfig,
            IWifiNanSessionCallback callback) {
        enforceAccessPermission();
        enforceChangePermission();

        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }
        if (subscribeConfig == null) {
            throw new IllegalArgumentException("SubscribeConfig must not be null");
        }
        subscribeConfig.validate();

        int uid = getMockableCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG, "subscribe: uid=" + uid + ", clientId=" + clientId + ", config="
                    + subscribeConfig + ", callback=" + callback);
        }

        mStateManager.subscribe(clientId, subscribeConfig, callback);
    }

    @Override
    public void updateSubscribe(int clientId, int sessionId, SubscribeConfig subscribeConfig) {
        enforceAccessPermission();
        enforceChangePermission();

        if (subscribeConfig == null) {
            throw new IllegalArgumentException("SubscribeConfig must not be null");
        }
        subscribeConfig.validate();

        int uid = getMockableCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG, "updateSubscribe: uid=" + uid + ", clientId=" + clientId + ", sessionId="
                    + sessionId + ", config=" + subscribeConfig);
        }

        mStateManager.updateSubscribe(clientId, sessionId, subscribeConfig);
    }

    @Override
    public void sendMessage(int clientId, int sessionId, int peerId, byte[] message,
            int messageLength, int messageId, int retryCount) {
        enforceAccessPermission();
        enforceChangePermission();

        if (messageLength != 0 && (message == null || message.length < messageLength)) {
            throw new IllegalArgumentException(
                    "Non-matching combination of message and messageLength");
        }
        if (retryCount < 0 || retryCount > WifiNanSession.MAX_SEND_RETRY_COUNT) {
            throw new IllegalArgumentException("Invalid 'retryCount' must be non-negative "
                    + "and <= WifiNanSession.MAX_SEND_RETRY_COUNT");
        }

        int uid = getMockableCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG,
                    "sendMessage: sessionId=" + sessionId + ", uid=" + uid + ", clientId="
                            + clientId + ", peerId=" + peerId + ", messageLength=" + messageLength
                            + ", messageId=" + messageId + ", retryCount=" + retryCount);
        }

        mStateManager.sendMessage(clientId, sessionId, peerId, message, messageLength, messageId,
                retryCount);
    }

    @Override
    public int startRanging(int clientId, int sessionId, RttManager.ParcelableRttParams params) {
        enforceAccessPermission();
        enforceLocationPermission();

        int uid = getMockableCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG, "startRanging: clientId=" + clientId + ", sessionId=" + sessionId + ", "
                    + ", parms=" + Arrays.toString(params.mParams));
        }

        if (params.mParams.length == 0) {
            throw new IllegalArgumentException("Empty ranging parameters");
        }

        int rangingId;
        synchronized (mLock) {
            rangingId = mNextRangingId++;
        }
        mStateManager.startRanging(clientId, sessionId, params.mParams, rangingId);
        return rangingId;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiNanService from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
            return;
        }
        pw.println("Wi-Fi NAN Service");
        synchronized (mLock) {
            pw.println("  mNextClientId: " + mNextClientId);
            pw.println("  mDeathRecipientsByClientId: " + mDeathRecipientsByClientId);
            pw.println("  mUidByClientId: " + mUidByClientId);
        }
        mStateManager.dump(fd, pw, args);
    }

    private void enforceClientValidity(int uid, int clientId) {
        Integer uidLookup;
        synchronized (mLock) {
            uidLookup = mUidByClientId.get(clientId);
        }

        boolean valid = uidLookup != null && uidLookup == uid;
        if (!valid) {
            throw new SecurityException("Attempting to use invalid uid+clientId mapping: uid=" + uid
                    + ", clientId=" + clientId);
        }
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE, TAG);
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE, TAG);
    }

    private void enforceLocationPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION,
                TAG);
    }
}
