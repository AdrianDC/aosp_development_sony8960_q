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

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.RttManager;
import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.IWifiNanEventCallback;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Manages the service-side NAN state of an individual "client". A client
 * corresponds to a single instantiation of the WifiNanManager - there could be
 * multiple ones per UID/process (each of which is a separate client with its
 * own session namespace). The client state is primarily: (1) callback (a
 * singleton per client) through which NAN-wide events are called, and (2) a set
 * of discovery sessions (publish and/or subscribe) which are created through
 * this client and whose lifetime is tied to the lifetime of the client.
 */
public class WifiNanClientState {
    private static final String TAG = "WifiNanClientState";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    /* package */ static final int CLUSTER_CHANGE_EVENT_STARTED = 0;
    /* package */ static final int CLUSTER_CHANGE_EVENT_JOINED = 1;

    private final Context mContext;
    private final IWifiNanEventCallback mCallback;
    private final SparseArray<WifiNanSessionState> mSessions = new SparseArray<>();

    private final int mClientId;
    private ConfigRequest mConfigRequest;
    private final int mUid;
    private final int mPid;
    private final String mCallingPackage;

    private AppOpsManager mAppOps;

    private static final byte[] ALL_ZERO_MAC = new byte[] {0, 0, 0, 0, 0, 0};
    private byte[] mLastDiscoveryInterfaceMac = ALL_ZERO_MAC;

    public WifiNanClientState(Context context, int clientId, int uid, int pid,
            String callingPackage, IWifiNanEventCallback callback, ConfigRequest configRequest) {
        mContext = context;
        mClientId = clientId;
        mUid = uid;
        mPid = pid;
        mCallingPackage = callingPackage;
        mCallback = callback;
        mConfigRequest = configRequest;

        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
    }

    /**
     * Destroy the current client - corresponds to a disconnect() request from
     * the client. Destroys all discovery sessions belonging to this client.
     */
    public void destroy() {
        for (int i = 0; i < mSessions.size(); ++i) {
            mSessions.valueAt(i).terminate();
        }
        mSessions.clear();
        mConfigRequest = null;
    }

    public ConfigRequest getConfigRequest() {
        return mConfigRequest;
    }

    public int getClientId() {
        return mClientId;
    }

    public int getUid() {
        return mUid;
    }

    /**
     * Searches the discovery sessions of this client and returns the one
     * corresponding to the publish/subscribe ID. Used on callbacks from HAL to
     * map callbacks to the correct discovery session.
     *
     * @param pubSubId The publish/subscribe match session ID.
     * @return NAN session corresponding to the requested ID.
     */
    public WifiNanSessionState getNanSessionStateForPubSubId(int pubSubId) {
        for (int i = 0; i < mSessions.size(); ++i) {
            WifiNanSessionState session = mSessions.valueAt(i);
            if (session.isPubSubIdSession(pubSubId)) {
                return session;
            }
        }

        return null;
    }

    /**
     * Add the session to the client database.
     *
     * @param session Session to be added.
     */
    public void addSession(WifiNanSessionState session) {
        int sessionId = session.getSessionId();
        if (mSessions.get(sessionId) != null) {
            Log.w(TAG, "createSession: sessionId already exists (replaced) - " + sessionId);
        }

        mSessions.put(sessionId, session);
    }

    /**
     * Remove the specified session from the client database - without doing a
     * terminate on the session. The assumption is that it is already
     * terminated.
     *
     * @param sessionId The session ID of the session to be removed.
     */
    public void removeSession(int sessionId) {
        if (mSessions.get(sessionId) == null) {
            Log.e(TAG, "removeSession: sessionId doesn't exist - " + sessionId);
            return;
        }

        mSessions.delete(sessionId);
    }

    /**
     * Destroy the discovery session: terminates discovery and frees up
     * resources.
     *
     * @param sessionId The session ID of the session to be destroyed.
     */
    public void terminateSession(int sessionId) {
        WifiNanSessionState session = mSessions.get(sessionId);
        if (session == null) {
            Log.e(TAG, "terminateSession: sessionId doesn't exist - " + sessionId);
            return;
        }

        session.terminate();
        mSessions.delete(sessionId);
    }

    /**
     * Retrieve a session.
     *
     * @param sessionId Session ID of the session to be retrieved.
     * @return Session or null if there's no session corresponding to the
     *         sessionId.
     */
    public WifiNanSessionState getSession(int sessionId) {
        return mSessions.get(sessionId);
    }

    /**
     * Called to dispatch the NAN interface address change to the client - as an
     * identity change (interface address information not propagated to client -
     * privacy concerns).
     *
     * @param mac The new MAC address of the discovery interface - optionally propagated to the
     *            client.
     */
    public void onInterfaceAddressChange(byte[] mac) {
        if (VDBG) {
            Log.v(TAG,
                    "onInterfaceAddressChange: mClientId=" + mClientId
                            + ", mEnableIdentityChangeCallback="
                            + mConfigRequest.mEnableIdentityChangeCallback + ", mac="
                            + String.valueOf(HexEncoding.encode(mac))
                            + ", mLastDiscoveryInterfaceMac=" + String.valueOf(
                            HexEncoding.encode(mLastDiscoveryInterfaceMac)));
        }
        if (mConfigRequest.mEnableIdentityChangeCallback && !Arrays.equals(mac,
                mLastDiscoveryInterfaceMac)) {
            try {
                boolean hasPermission = hasLocationingPermission();
                if (VDBG) Log.v(TAG, "hasPermission=" + hasPermission);
                mCallback.onIdentityChanged(hasPermission ? mac : ALL_ZERO_MAC);
            } catch (RemoteException e) {
                Log.w(TAG, "onIdentityChanged: RemoteException - ignored: " + e);
            }
        }

        mLastDiscoveryInterfaceMac = mac;
    }

    /**
     * Called to dispatch the NAN cluster change (due to joining of a new
     * cluster or starting a cluster) to the client - as an identity change
     * (interface address information not propagated to client - privacy
     * concerns). Dispatched if the client registered for the identity changed
     * event.
     *
     * @param mac The cluster ID of the cluster started or joined.
     * @param currentDiscoveryInterfaceMac The MAC address of the discovery interface.
     */
    public void onClusterChange(int flag, byte[] mac, byte[] currentDiscoveryInterfaceMac) {
        if (VDBG) {
            Log.v(TAG,
                    "onClusterChange: mClientId=" + mClientId + ", mEnableIdentityChangeCallback="
                            + mConfigRequest.mEnableIdentityChangeCallback + ", mac="
                            + String.valueOf(HexEncoding.encode(mac))
                            + ", currentDiscoveryInterfaceMac=" + String.valueOf(
                            HexEncoding.encode(currentDiscoveryInterfaceMac))
                            + ", mLastDiscoveryInterfaceMac=" + String.valueOf(
                            HexEncoding.encode(mLastDiscoveryInterfaceMac)));
        }
        if (mConfigRequest.mEnableIdentityChangeCallback && !Arrays.equals(
                currentDiscoveryInterfaceMac, mLastDiscoveryInterfaceMac)) {
            try {
                boolean hasPermission = hasLocationingPermission();
                if (VDBG) Log.v(TAG, "hasPermission=" + hasPermission);
                mCallback.onIdentityChanged(hasPermission ? mac : ALL_ZERO_MAC);
            } catch (RemoteException e) {
                Log.w(TAG, "onIdentityChanged: RemoteException - ignored: " + e);
            }
        }

        mLastDiscoveryInterfaceMac = currentDiscoveryInterfaceMac;
    }

    private boolean hasLocationingPermission() {
        // FINE provides COARSE, so only have to check for the latter
        return mContext.checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION, mPid, mUid)
                == PackageManager.PERMISSION_GRANTED && mAppOps.noteOp(
                AppOpsManager.OP_COARSE_LOCATION, mUid, mCallingPackage)
                == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Called on RTT success - forwards call to client.
     */
    public void onRangingSuccess(int rangingId, RttManager.ParcelableRttResults results) {
        if (VDBG) {
            Log.v(TAG, "onRangingSuccess: rangingId=" + rangingId + ", results=" + results);
        }
        try {
            mCallback.onRangingSuccess(rangingId, results);
        } catch (RemoteException e) {
            Log.w(TAG, "onRangingSuccess: RemoteException - ignored: " + e);
        }
    }

    /**
     * Called on RTT failure - forwards call to client.
     */
    public void onRangingFailure(int rangingId, int reason, String description) {
        if (VDBG) {
            Log.v(TAG, "onRangingSuccess: rangingId=" + rangingId + ", reason=" + reason
                    + ", description=" + description);
        }
        try {
            mCallback.onRangingFailure(rangingId, reason, description);
        } catch (RemoteException e) {
            Log.w(TAG, "onRangingFailure: RemoteException - ignored: " + e);
        }
    }

    /**
     * Called on RTT operation aborted - forwards call to client.
     */
    public void onRangingAborted(int rangingId) {
        if (VDBG) Log.v(TAG, "onRangingSuccess: rangingId=" + rangingId);
        try {
            mCallback.onRangingAborted(rangingId);
        } catch (RemoteException e) {
            Log.w(TAG, "onRangingAborted: RemoteException - ignored: " + e);
        }
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NanClientState:");
        pw.println("  mClientId: " + mClientId);
        pw.println("  mConfigRequest: " + mConfigRequest);
        pw.println("  mCallback: " + mCallback);
        pw.println("  mSessions: [" + mSessions + "]");
        for (int i = 0; i < mSessions.size(); ++i) {
            mSessions.valueAt(i).dump(fd, pw, args);
        }
    }
}
