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
import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanEventCallback;
import android.net.wifi.nan.IWifiNanManager;
import android.net.wifi.nan.IWifiNanSessionCallback;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;

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
    private final boolean mNanSupported;

    private final Object mLock = new Object();
    private final SparseArray<IBinder.DeathRecipient> mDeathRecipientsByClientId =
            new SparseArray<>();
    private int mNextClientId = 1;
    private final SparseArray<Integer> mUidByClientId = new SparseArray<>();
    private int mNextSessionId = 1;

    public WifiNanServiceImpl(Context context) {
        mContext = context.getApplicationContext();

        mNanSupported = mContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_WIFI_NAN);
        if (DBG) Log.w(TAG, "WifiNanServiceImpl: mNanSupported=" + mNanSupported);

        mStateManager = WifiNanStateManager.getInstance();
    }

    /**
     * Start the service: allocate a new thread (for now), start the handlers of
     * the components of the service.
     */
    public void start() {
        Log.i(TAG, "Starting Wi-Fi NAN service");

        // TODO: share worker thread with other Wi-Fi handlers
        HandlerThread wifiNanThread = new HandlerThread("wifiNanService");
        wifiNanThread.start();

        mStateManager.start(wifiNanThread.getLooper());
    }

    @Override
    public int connect(final IBinder binder, IWifiNanEventCallback callback, int events) {
        enforceAccessPermission();
        enforceChangePermission();

        final int uid = getCallingUid();

        final int clientId;
        synchronized (mLock) {
            clientId = mNextClientId++;
            mUidByClientId.put(clientId, uid);
        }

        if (VDBG) Log.v(TAG, "connect: uid=" + uid + ", clientId=" + clientId);

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
        synchronized (mLock) {
            mDeathRecipientsByClientId.put(clientId, dr);
        }
        try {
            binder.linkToDeath(dr, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "Error on linkToDeath - " + e);
        }

        mStateManager.connect(clientId, callback, events);

        return clientId;
    }

    @Override
    public void disconnect(int clientId, IBinder binder) {
        enforceAccessPermission();
        enforceChangePermission();

        int uid = getCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) Log.v(TAG, "disconnect: uid=" + uid + ", clientId=" + clientId);

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
    public void requestConfig(int clientId, ConfigRequest configRequest) {
        enforceAccessPermission();
        enforceChangePermission();

        int uid = getCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG, "requestConfig: uid=" + uid + "clientId=" + clientId + ", configRequest="
                    + configRequest);
        }

        mStateManager.requestConfig(clientId, configRequest);
    }

    @Override
    public void stopSession(int clientId, int sessionId) {
        enforceAccessPermission();
        enforceChangePermission();

        int uid = getCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG, "stopSession: sessionId=" + sessionId + ", uid=" + uid + ", clientId="
                    + clientId);
        }

        mStateManager.stopSession(clientId, sessionId);
    }

    @Override
    public void destroySession(int clientId, int sessionId) {
        enforceAccessPermission();
        enforceChangePermission();

        int uid = getCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG, "destroySession: sessionId=" + sessionId + ", uid=" + uid + ", clientId="
                    + clientId);
        }

        mStateManager.destroySession(clientId, sessionId);
    }

    @Override
    public int createSession(int clientId, IWifiNanSessionCallback callback, int events) {
        enforceAccessPermission();
        enforceChangePermission();

        int uid = getCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) Log.v(TAG, "createSession: uid=" + uid + ", clientId=" + clientId);

        int sessionId;
        synchronized (mLock) {
            sessionId = mNextSessionId++;
        }

        mStateManager.createSession(clientId, sessionId, callback, events);

        return sessionId;
    }

    @Override
    public void publish(int clientId, int sessionId, PublishConfig publishConfig) {
        enforceAccessPermission();
        enforceChangePermission();

        int uid = getCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG, "publish: uid=" + uid + ", clientId=" + clientId + ", sessionId=" + sessionId
                    + ", config=" + publishConfig);
        }

        mStateManager.publish(clientId, sessionId, publishConfig);
    }

    @Override
    public void subscribe(int clientId, int sessionId, SubscribeConfig subscribeConfig) {
        enforceAccessPermission();
        enforceChangePermission();

        int uid = getCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG, "subscribe: uid=" + uid + ", clientId=" + clientId + ", sessionId="
                    + sessionId + ", config=" + subscribeConfig);
        }

        mStateManager.subscribe(clientId, sessionId, subscribeConfig);
    }

    @Override
    public void sendMessage(int clientId, int sessionId, int peerId, byte[] message,
            int messageLength, int messageId) {
        enforceAccessPermission();
        enforceChangePermission();

        int uid = getCallingUid();
        enforceClientValidity(uid, clientId);
        if (VDBG) {
            Log.v(TAG,
                    "sendMessage: sessionId=" + sessionId + ", uid=" + uid + ", clientId="
                            + clientId + ", peerId=" + peerId + ", messageLength=" + messageLength
                            + ", messageId=" + messageId);
        }

        mStateManager.sendMessage(clientId, sessionId, peerId, message, messageLength, messageId);
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
        pw.println("  mNanSupported: " + mNanSupported);
        pw.println("  mNextClientId: " + mNextClientId);
        pw.println("  mNextSessionId: " + mNextSessionId);
        pw.println("  mDeathRecipientsByClientId: " + mDeathRecipientsByClientId);
        pw.println("  mUidByClientId: " + mUidByClientId);
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
}
