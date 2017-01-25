/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wifi;

import android.hardware.wifi.V1_0.IWifi;
import android.hardware.wifi.V1_0.IWifiEventCallback;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * Handles device management through the HAL (HIDL) interface.
 */
public class HalDeviceManager {
    private static final String TAG = "HalDeviceManager";
    private static final boolean DBG = true;

    // public API
    public HalDeviceManager() {
        // empty
    }

    /**
     * Actually starts the HalDeviceManager: separate from constructor since may want to phase
     * at a later time.
     *
     * TODO: if decide that no need for separating construction from initialization (e.g. both are
     * done at injector) then move to constructor.
     */
    public void initialize() {
        initializeInternal();
    }

    /**
     * Register a ManagerStatusCallback to get information about status of Wi-Fi. Use the
     * isStarted() method to check status immediately after registration - don't expect callbacks
     * for current status (no 'sticky' behavior).
     *
     * It is safe to re-register the same callback object - duplicates are detected and only a
     * single copy kept.
     *
     * @param callback ManagerStatusCallback callback object.
     * @param looper Looper on which to dispatch callbacks. Null implies current looper.
     */
    public void registerStatusCallback(ManagerStatusCallback callback, Looper looper) {
        synchronized (mLock) {
            if (!mManagerStatusCallbacks.add(new ManagerStatusCallbackProxy(callback,
                    looper == null ? Looper.myLooper() : looper))) {
                Log.w(TAG, "registerStatusCallback: duplicate registration ignored");
            }
        }
    }

    /**
     * Returns the current status of Wi-Fi: started (true) or stopped (false).
     *
     * Note: direct call to HIDL.
     */
    public boolean isStarted() {
        return isWifiStarted();
    }

    /**
     * Attempts to start Wi-Fi (using HIDL). Returns the success (true) or failure (false) or
     * the start operation. Will also dispatch any registered ManagerStatusCallback.onStart() on
     * success.
     *
     * Note: direct call to HIDL.
     */
    public boolean start() {
        return startWifi();
    }

    /**
     * Stops Wi-Fi. Will also dispatch any registeredManagerStatusCallback.onStop().
     *
     * Note: direct call to HIDL - failure is not-expected.
     */
    public void stop() {
        stopWifi();
    }

    /**
     * HAL device manager status callbacks.
     */
    public interface ManagerStatusCallback {
        /**
         * Indicates that Wi-Fi is up.
         */
        void onStart();

        /**
         * Indicates that Wi-Fi is down.
         */
        void onStop();
    }

    // internal state
    private final Object mLock = new Object();

    private IServiceManager mServiceManager;
    private IWifi mWifi;
    private final WifiEventCallback mWifiEventCallback = new WifiEventCallback();
    private final Set<ManagerStatusCallbackProxy> mManagerStatusCallbacks = new HashSet<>();

    /**
     * Wrapper function to access the HIDL services. Created to be mockable in unit-tests.
     */
    protected IWifi getWifiServiceMockable() {
        try {
            return IWifi.getService();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception getting IWifi service: " + e);
            return null;
        }
    }

    protected IServiceManager getServiceManagerMockable() {
        try {
            return IServiceManager.getService();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception getting IServiceManager: " + e);
            return null;
        }
    }

    // internal implementation
    private void initializeInternal() {
        initIServiceManagerIfNecessary();
    }

    /**
     * Failures of IServiceManager are most likely system breaking in any case. Behavior here
     * will be to WTF and continue.
     */
    private void initIServiceManagerIfNecessary() {
        if (DBG) Log.d(TAG, "initIServiceManagerIfNecessary");

        synchronized (mLock) {
            if (mServiceManager != null) {
                return;
            }

            mServiceManager = getServiceManagerMockable();
            if (mServiceManager == null) {
                Log.wtf(TAG, "Failed to get IServiceManager instance");
            } else {
                try {
                    if (!mServiceManager.linkToDeath(cookie -> {
                        Log.wtf(TAG, "IServiceManager died: cookie=" + cookie);
                        synchronized (mLock) {
                            mServiceManager = null;
                            // theoretically can call initServiceManager again here - but
                            // there's no point since most likely system is going to reboot
                        }
                    }, /* don't care */ 0)) {
                        Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                        mServiceManager = null;
                        return;
                    }

                    if (!mServiceManager.registerForNotifications(IWifi.kInterfaceName, "",
                            new IServiceNotification.Stub() {
                                @Override
                                public void onRegistration(String fqName, String name,
                                        boolean preexisting) {
                                    Log.d(TAG, "IWifi registration notification: fqName=" + fqName
                                            + ", name=" + name + ", preexisting=" + preexisting);
                                    mWifi = null; // get rid of old copy!
                                    initIWifiIfNecessary();
                                }
                            })) {
                        Log.wtf(TAG, "Failed to register a listener for IWifi service");
                        mServiceManager = null;
                    }
                } catch (RemoteException e) {
                    Log.wtf(TAG, "Exception while operating on IServiceManager: " + e);
                    mServiceManager = null;
                }
            }
        }
    }


    /**
     * Initialize IWifi and register death listener and event callback.
     *
     * - It is possible that IWifi is not ready - we have a listener on IServiceManager for it.
     * - It is not expected that any of the registrations will fail. Possible indication that
     *   service died after we obtained a handle to it.
     *
     * Here and elsewhere we assume that death listener will do the right thing!
    */
    private void initIWifiIfNecessary() {
        if (DBG) Log.d(TAG, "initIWifiIfNecessary");

        synchronized (mLock) {
            if (mWifi != null) {
                return;
            }

            try {
                mWifi = getWifiServiceMockable();
                if (mWifi == null) {
                    Log.e(TAG, "IWifi not (yet) available - but have a listener for it ...");
                    return;
                }

                if (!mWifi.linkToDeath(cookie -> {
                    Log.e(TAG, "IWifi HAL service died! Have a listener for it ... cookie="
                            + cookie);
                    synchronized (mLock) { // prevents race condition with surrounding method
                        mWifi = null;
                        managerStatusCallbackDispatchStop();
                        // don't restart: wait for registration notification
                    }
                }, /* don't care */ 0)) {
                    Log.e(TAG, "Error on linkToDeath on IWifi - will retry later");
                    return;
                }

                WifiStatus status = mWifi.registerEventCallback(mWifiEventCallback);
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "IWifi.registerEventCallback failed: " + statusString(status));
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while operating on IWifi: " + e);
            }
        }
    }

    private boolean isWifiStarted() {
        if (DBG) Log.d(TAG, "isWifiStart");

        synchronized (mLock) {
            try {
                if (mWifi == null) {
                    Log.w(TAG, "isWifiStarted called but mWifi is null!?");
                    return false;
                } else {
                    return mWifi.isStarted();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "isWifiStarted exception: " + e);
                return false;
            }
        }
    }

    private boolean startWifi() {
        if (DBG) Log.d(TAG, "startWifi");

        synchronized (mLock) {
            try {
                if (mWifi == null) {
                    Log.w(TAG, "startWifi called but mWifi is null!?");
                    return false;
                } else {
                    WifiStatus status = mWifi.start();
                    boolean success = status.code == WifiStatusCode.SUCCESS;
                    if (success) {
                        managerStatusCallbackDispatchStart();
                    } else {
                        Log.e(TAG, "Cannot start IWifi: " + statusString(status));
                    }
                    return success;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "startWifi exception: " + e);
                return false;
            }
        }
    }

    private void stopWifi() {
        if (DBG) Log.d(TAG, "stopWifi");

        synchronized (mLock) {
            try {
                if (mWifi == null) {
                    Log.w(TAG, "stopWifi called but mWifi is null!?");
                } else {
                    WifiStatus status = mWifi.stop();
                    if (status.code != WifiStatusCode.SUCCESS) {
                        Log.e(TAG, "Cannot stop IWifi: " + statusString(status));
                    }

                    // calling onStop for the callbacks even on failure since WTF??
                    managerStatusCallbackDispatchStop();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "stopWifi exception: " + e);
            }
        }
    }

    private class WifiEventCallback extends IWifiEventCallback.Stub {
        @Override
        public void onStart() throws RemoteException {
            if (DBG) Log.d(TAG, "IWifiEventCallback.onStart");
            // NOP: only happens in reaction to my calls - will handle directly
        }

        @Override
        public void onStop() throws RemoteException {
            if (DBG) Log.d(TAG, "IWifiEventCallback.onStop");
            // NOP: only happens in reaction to my calls - will handle directly
        }

        @Override
        public void onFailure(WifiStatus status) throws RemoteException {
            Log.e(TAG, "IWifiEventCallback.onFailure: " + statusString(status));
            managerStatusCallbackDispatchStop();

            // No need to do anything else: listeners may (will) re-start Wi-Fi
        }
    }

    private void managerStatusCallbackDispatchStart() {
        synchronized (mLock) {
            for (ManagerStatusCallbackProxy cb : mManagerStatusCallbacks) {
                cb.onStart();
            }
        }
    }

    private void managerStatusCallbackDispatchStop() {
        synchronized (mLock) {
            for (ManagerStatusCallbackProxy cb : mManagerStatusCallbacks) {
                cb.onStop();
            }
        }
    }

    private class ManagerStatusCallbackProxy  {
        private static final int CALLBACK_ON_START = 0;
        private static final int CALLBACK_ON_STOP = 1;

        private ManagerStatusCallback mCallback;
        private Handler mHandler;

        void onStart() {
            mHandler.sendMessage(mHandler.obtainMessage(CALLBACK_ON_START));
        }

        void onStop() {
            mHandler.sendMessage(mHandler.obtainMessage(CALLBACK_ON_STOP));
        }

        // override equals & hash to make sure that the container HashSet is unique with respect to
        // the contained callback
        @Override
        public boolean equals(Object obj) {
            return mCallback == ((ManagerStatusCallbackProxy) obj).mCallback;
        }

        @Override
        public int hashCode() {
            return mCallback.hashCode();
        }

        ManagerStatusCallbackProxy(ManagerStatusCallback callback, Looper looper) {
            mCallback = callback;
            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    if (DBG) {
                        Log.d(TAG, "ManagerStatusCallbackProxy.handleMessage: what=" + msg.what);
                    }
                    switch (msg.what) {
                        case CALLBACK_ON_START:
                            mCallback.onStart();
                            break;
                        case CALLBACK_ON_STOP:
                            mCallback.onStop();
                            break;
                        default:
                            Log.e(TAG,
                                    "ManagerStatusCallbackProxy.handleMessage: unknown message "
                                            + "what="
                                            + msg.what);
                    }
                }
            };
        }
    }

    private String statusString(WifiStatus status) {
        StringBuilder sb = new StringBuilder();
        sb.append(status.code).append(" (").append(status.description).append(")");
        return sb.toString();
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("HalDeviceManager:");
        pw.println("  mServiceManager: " + mServiceManager);
        pw.println("  mWifi: " + mWifi);
        pw.println("  mManagerStatusCallbacks: " + mManagerStatusCallbacks);
    }
}
