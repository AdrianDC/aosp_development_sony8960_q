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

import android.net.wifi.IApInterface;
import android.net.wifi.IClientInterface;
import android.net.wifi.IWificond;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

/**
 * This class provides methods for WifiNative to send control commands to wificond.
 * NOTE: This class should only be used from WifiNative.
 */
public class WificondControl {

    private static final String TAG = "WificondControl";
    private IWificond mWificond;
    private IClientInterface mClientInterface;
    private IApInterface mApInterface;
    private WifiInjector mWifiInjector;

    WificondControl(WifiInjector wifiInjector) {
        mWifiInjector = wifiInjector;
    }

    /**
    * Setup driver for client mode via wificond.
    * @return An IClientInterface as wificond client interface binder handler.
    * Returns null on failure.
    */
    public IClientInterface setupDriverForClientMode() {
        mWificond = mWifiInjector.makeWificond();
        if (mWificond == null) {
            Log.e(TAG, "Failed to get reference to wificond");
            return null;
        }

        IClientInterface clientInterface = null;
        try {
            clientInterface = mWificond.createClientInterface();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to get IClientInterface due to remote exception");
            return null;
        }

        if (clientInterface == null) {
            Log.e(TAG, "Could not get IClientInterface instance from wificond");
            return null;
        }
        Binder.allowBlocking(clientInterface.asBinder());

        // Refresh Handlers
        mClientInterface = clientInterface;

        return clientInterface;
    }

    /**
    * Setup driver for softAp mode via wificond.
    * @return An IApInterface as wificond Ap interface binder handler.
    * Returns null on failure.
    */
    public IApInterface setupDriverForSoftApMode() {
        mWificond = mWifiInjector.makeWificond();
        if (mWificond == null) {
            Log.e(TAG, "Failed to get reference to wificond");
            return null;
        }

        IApInterface apInterface = null;
        try {
            apInterface = mWificond.createApInterface();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to get IApInterface due to remote exception");
            return null;
        }

        if (apInterface == null) {
            Log.e(TAG, "Could not get IApInterface instance from wificond");
            return null;
        }
        Binder.allowBlocking(apInterface.asBinder());

        // Refresh Handlers
        mApInterface = apInterface;

        return apInterface;
    }

    /**
    * Teardown all interfaces configured in wificond.
    * @return Returns true on success.
    */
    public boolean tearDownInterfaces() {
        // Explicitly refresh the wificodn handler because |tearDownInterfaces()|
        // could be used to cleanup before we setup any interfaces.
        mWificond = mWifiInjector.makeWificond();
        if (mWificond == null) {
            Log.e(TAG, "Failed to get reference to wificond");
            return false;
        }

        try {
            mWificond.tearDownInterfaces();
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to tear down interfaces due to remote exception");
        }
        return false;
    }

    /**
    * Disable wpa_supplicant via wificond.
    * @return Returns true on success.
    */
    public boolean disableSupplicant() {
        if (mClientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return false;
        }
        try {
            return mClientInterface.disableSupplicant();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to disable supplicant due to remote exception");
        }
        return false;
    }

    /**
    * Enable wpa_supplicant via wificond.
    * @return Returns true on success.
    */
    public boolean enableSupplicant() {
        if (mClientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return false;
        }

        try {
            return mClientInterface.enableSupplicant();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to enable supplicant due to remote exception");
        }
        return false;
    }
}
