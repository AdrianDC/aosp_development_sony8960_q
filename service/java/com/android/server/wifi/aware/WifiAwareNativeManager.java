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

import android.annotation.NonNull;
import android.hardware.wifi.V1_0.IWifiNanIface;
import android.hardware.wifi.V1_0.IfaceType;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.HalDeviceManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Manages the interface to Wi-Fi Aware HIDL (HAL).
 */
public class WifiAwareNativeManager {
    private static final String TAG = "WifiAwareNativeManager";
    private static final boolean VDBG = false;
    /* package */ boolean mDbg = false;

    // to be used for synchronizing access to any of the WifiAwareNative objects
    private final Object mLock = new Object();

    private WifiAwareStateManager mWifiAwareStateManager;
    private HalDeviceManager mHalDeviceManager;
    private Handler mHandler;
    private WifiAwareNativeCallback mWifiAwareNativeCallback;
    private IWifiNanIface mWifiNanIface = null;
    private InterfaceDestroyedListener mInterfaceDestroyedListener =
            new InterfaceDestroyedListener();
    private InterfaceAvailableForRequestListener mInterfaceAvailableForRequestListener =
            new InterfaceAvailableForRequestListener();

    WifiAwareNativeManager(WifiAwareStateManager awareStateManager,
            HalDeviceManager halDeviceManager,
            WifiAwareNativeCallback wifiAwareNativeCallback) {
        mWifiAwareStateManager = awareStateManager;
        mHalDeviceManager = halDeviceManager;
        mWifiAwareNativeCallback = wifiAwareNativeCallback;
    }

    /**
     * Initialize the class - intended for late initialization.
     *
     * @param handler Handler on which to execute interface available callbacks.
     */
    public void start(Handler handler) {
        mHandler = handler;
        mHalDeviceManager.initialize();
        mHalDeviceManager.registerStatusListener(
                new HalDeviceManager.ManagerStatusListener() {
                    @Override
                    public void onStatusChanged() {
                        if (VDBG) Log.v(TAG, "onStatusChanged");
                        // only care about isStarted (Wi-Fi started) not isReady - since if not
                        // ready then Wi-Fi will also be down.
                        if (mHalDeviceManager.isStarted()) {
                            // 1. no problem registering duplicates - only one will be called
                            // 2. will be called immediately if available
                            mHalDeviceManager.registerInterfaceAvailableForRequestListener(
                                    IfaceType.NAN, mInterfaceAvailableForRequestListener, mHandler);
                        } else {
                            awareIsDown();
                        }
                    }
                }, mHandler);
        if (mHalDeviceManager.isStarted()) {
            mHalDeviceManager.registerInterfaceAvailableForRequestListener(
                    IfaceType.NAN, mInterfaceAvailableForRequestListener, mHandler);
            tryToGetAware();
        }
    }

    /**
     * Returns the native HAL WifiNanIface through which commands to the NAN HAL are dispatched.
     * Return may be null if not initialized/available.
     */
    @VisibleForTesting
    public IWifiNanIface getWifiNanIface() {
        synchronized (mLock) {
            return mWifiNanIface;
        }
    }

    /**
     * Attempt to obtain the HAL NAN interface. If available then enables Aware usage.
     */
    private void tryToGetAware() {
        synchronized (mLock) {
            if (mDbg) Log.v(TAG, "tryToGetAware: mWifiNanIface=" + mWifiNanIface);

            if (mWifiNanIface != null) {
                return;
            }
            IWifiNanIface iface = mHalDeviceManager.createNanIface(mInterfaceDestroyedListener,
                    mHandler);
            if (iface == null) {
                Log.e(TAG, "Was not able to obtain an IWifiNanIface");
            } else {
                if (mDbg) Log.v(TAG, "Obtained an IWifiNanIface");

                try {
                    WifiStatus status = iface.registerEventCallback(mWifiAwareNativeCallback);
                    if (status.code != WifiStatusCode.SUCCESS) {
                        Log.e(TAG, "IWifiNanIface.registerEventCallback error: " + statusString(
                                status));
                        mHalDeviceManager.removeIface(iface);
                        return;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "IWifiNanIface.registerEventCallback exception: " + e);
                    mHalDeviceManager.removeIface(iface);
                    return;
                }
                mWifiNanIface = iface;
                mWifiAwareStateManager.enableUsage();
            }
        }
    }

    private void awareIsDown() {
        synchronized (mLock) {
            if (mDbg) Log.v(TAG, "awareIsDown: mWifiNanIface=" + mWifiNanIface);
            if (mWifiNanIface != null) {
                mWifiNanIface = null;
                mWifiAwareStateManager.disableUsage();
            }
        }
    }

    private class InterfaceDestroyedListener implements
            HalDeviceManager.InterfaceDestroyedListener {
        @Override
        public void onDestroyed(@NonNull String ifaceName) {
            if (mDbg) Log.v(TAG, "Interface was destroyed");
            awareIsDown();
        }
    }

    private class InterfaceAvailableForRequestListener implements
            HalDeviceManager.InterfaceAvailableForRequestListener {
        @Override
        public void onAvailableForRequest() {
            if (mDbg) Log.v(TAG, "Interface is possibly available");
            tryToGetAware();
        }
    }

    private static String statusString(WifiStatus status) {
        if (status == null) {
            return "status=null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(status.code).append(" (").append(status.description).append(")");
        return sb.toString();
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WifiAwareNativeManager:");
        pw.println("  mWifiNanIface: " + mWifiNanIface);
        mWifiAwareNativeCallback.dump(fd, pw, args);
        mHalDeviceManager.dump(fd, pw, args);
    }
}
