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
import android.net.wifi.IWifiScannerImpl;
import android.net.wifi.IWificond;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiSsid;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.util.InformationElementUtil;
import com.android.server.wifi.wificond.NativeScanResult;

import java.util.ArrayList;

/**
 * This class provides methods for WifiNative to send control commands to wificond.
 * NOTE: This class should only be used from WifiNative.
 */
public class WificondControl {

    private static final String TAG = "WificondControl";
    private static final int MAC_ADDR_LEN = 6;
    private IWificond mWificond;
    private IClientInterface mClientInterface;
    private IApInterface mApInterface;
    private IWifiScannerImpl mWificondScanner;
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
        try {
            mWificondScanner = mClientInterface.getWifiScannerImpl();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to refresh wificond scanner due to remote exception");
        }

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
        mWificondScanner = null;

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

    /**
    * Request signal polling to wificond.
    * Returns an SignalPollResult object.
    * Returns null on failure.
    */
    public WifiNative.SignalPollResult signalPoll() {
        if (mClientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return null;
        }

        int[] resultArray;
        try {
            resultArray = mClientInterface.signalPoll();
            if (resultArray == null || resultArray.length != 3) {
                Log.e(TAG, "Invalid signal poll result from wificond");
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to do signal polling  due to remote exception");
            return null;
        }
        WifiNative.SignalPollResult pollResult = new WifiNative.SignalPollResult();
        pollResult.currentRssi = resultArray[0];
        pollResult.txBitrate = resultArray[1];
        pollResult.associationFrequency = resultArray[2];
        return pollResult;
    }

    /**
    * Fetch TX packet counters on current connection from wificond.
    * Returns an TxPacketCounters object.
    * Returns null on failure.
    */
    public WifiNative.TxPacketCounters getTxPacketCounters() {
        if (mClientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return null;
        }

        int[] resultArray;
        try {
            resultArray = mClientInterface.getPacketCounters();
            if (resultArray == null || resultArray.length != 2) {
                Log.e(TAG, "Invalid signal poll result from wificond");
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to do signal polling  due to remote exception");
            return null;
        }
        WifiNative.TxPacketCounters counters = new WifiNative.TxPacketCounters();
        counters.txSucceeded = resultArray[0];
        counters.txFailed = resultArray[1];
        return counters;
    }

    private static String parseMacToString(byte[] mac) {
        if (mac == null || mac.length != MAC_ADDR_LEN) {
            Log.e(TAG, "Invalid MAC adddress size");
            return "";
        }
        return String.format("%02x:%02x:%02x:%02x:%02x:%02x",
                mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    }

    /**
    * Fetch the latest scan result from kernel via wificond.
    * @return Returns an ArrayList of ScanDetail.
    * Returns an empty ArrayList on failure.
    */
    public ArrayList<ScanDetail> getScanResults() {
        ArrayList<ScanDetail> results = new ArrayList<>();
        if (mWificondScanner == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return results;
        }
        try {
            NativeScanResult[] nativeResults = mWificondScanner.getScanResults();
            for (NativeScanResult result : nativeResults) {
                WifiSsid wifiSsid = WifiSsid.createFromAsciiEncoded(new String(result.ssid));
                String bssid = parseMacToString(result.bssid);
                ScanResult.InformationElement[] ies =
                        InformationElementUtil.parseInformationElements(result.infoElement);
                InformationElementUtil.Capabilities capabilities =
                        new InformationElementUtil.Capabilities();
                capabilities.from(ies, result.capability);
                String flags = capabilities.generateCapabilitiesString();
                NetworkDetail networkDetail =
                        new NetworkDetail(bssid, ies, null, result.frequency);

                if (!wifiSsid.toString().equals(networkDetail.getTrimmedSSID())) {
                    Log.e(TAG, "Inconsistent SSID on BSSID: " + bssid);
                    continue;
                }
                ScanDetail scanDetail = new ScanDetail(networkDetail, wifiSsid, bssid, flags,
                        result.signalMbm / 100, result.frequency, result.tsf, ies, null);
                results.add(scanDetail);
            }
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to create ScanDetail ArrayList");
        }
        return results;
    }
}
