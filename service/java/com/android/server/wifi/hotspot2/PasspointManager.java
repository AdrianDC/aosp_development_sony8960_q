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

package com.android.server.wifi.hotspot2;

import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_BSSID;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_DATA;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_ICON_FILE;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_BSSID;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_DELAY;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_ESS;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_METHOD;
import static android.net.wifi.WifiManager.EXTRA_PASSPOINT_WNM_URL;
import static android.net.wifi.WifiManager.PASSPOINT_ICON_RECEIVED_ACTION;
import static android.net.wifi.WifiManager.PASSPOINT_WNM_FRAME_RECEIVED_ACTION;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;

import com.android.server.wifi.Clock;
import com.android.server.wifi.IMSIParameter;
import com.android.server.wifi.SIMAccessor;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class provides the APIs to manage Passpoint provider configurations.
 * It deals with the following:
 * - Maintaining a list of configured Passpoint providers for provider matching.
 * - Persisting the providers configurations to store when required.
 * - matching Passpoint providers based on the scan results
 * - Supporting WifiManager Public API calls:
 *   > addOrUpdatePasspointConfiguration()
 *   > removePasspointConfiguration()
 *   > getPasspointConfigurations()
 *
 * The provider matching requires obtaining additional information from the AP (ANQP elements).
 * The ANQP elements will be cached using {@link AnqpCache} to avoid unnecessary requests.
 *
 * NOTE: These API's are not thread safe and should only be used from WifiStateMachine thread.
 */
public class PasspointManager {
    private static final String TAG = "PasspointManager";

    private final PasspointEventHandler mHandler;
    private final SIMAccessor mSimAccessor;
    private final WifiKeyStore mKeyStore;
    private final PasspointObjectFactory mObjectFactory;
    private final Map<String, PasspointProvider> mProviders;
    private final AnqpCache mAnqpCache;
    private final ANQPRequestManager mAnqpRequestManager;

    // Counter used for assigning unique identifier to a provider.
    private long mProviderID;

    private class CallbackHandler implements PasspointEventHandler.Callbacks {
        private final Context mContext;
        CallbackHandler(Context context) {
            mContext = context;
        }

        @Override
        public void onANQPResponse(long bssid,
                Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
            // Notify request manager for the completion of a request.
            ScanDetail scanDetail =
                    mAnqpRequestManager.onRequestCompleted(bssid, anqpElements != null);
            if (anqpElements == null || scanDetail == null) {
                // Query failed or the request wasn't originated from us (not tracked by the
                // request manager). Nothing to be done.
                return;
            }

            // Add new entry to the cache.
            NetworkDetail networkDetail = scanDetail.getNetworkDetail();
            ANQPNetworkKey anqpKey = ANQPNetworkKey.buildKey(networkDetail.getSSID(),
                    networkDetail.getBSSID(), networkDetail.getHESSID(),
                    networkDetail.getAnqpDomainID());
            mAnqpCache.addEntry(anqpKey, anqpElements);

            // Update ANQP elements in the ScanDetail.
            scanDetail.propagateANQPInfo(anqpElements);
        }

        @Override
        public void onIconResponse(long bssid, String fileName, byte[] data) {
            Intent intent = new Intent(PASSPOINT_ICON_RECEIVED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(EXTRA_PASSPOINT_ICON_BSSID, bssid);
            intent.putExtra(EXTRA_PASSPOINT_ICON_FILE, fileName);
            if (data != null) {
                intent.putExtra(EXTRA_PASSPOINT_ICON_DATA, data);
            }
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }

        @Override
        public void onWnmFrameReceived(WnmData event) {
            // %012x HS20-SUBSCRIPTION-REMEDIATION "%u %s", osu_method, url
            // %012x HS20-DEAUTH-IMMINENT-NOTICE "%u %u %s", code, reauth_delay, url
            Intent intent = new Intent(PASSPOINT_WNM_FRAME_RECEIVED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);

            intent.putExtra(EXTRA_PASSPOINT_WNM_BSSID, event.getBssid());
            intent.putExtra(EXTRA_PASSPOINT_WNM_URL, event.getUrl());

            if (event.isDeauthEvent()) {
                intent.putExtra(EXTRA_PASSPOINT_WNM_ESS, event.isEss());
                intent.putExtra(EXTRA_PASSPOINT_WNM_DELAY, event.getDelay());
            } else {
                intent.putExtra(EXTRA_PASSPOINT_WNM_METHOD, event.getMethod());
                // TODO(zqiu): set the passpoint matching status with the respect to the
                // current connected network (e.g. HomeProvider, RoamingProvider, None,
                // Declined).
            }
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        }
    }

    public PasspointManager(Context context, WifiNative wifiNative, WifiKeyStore keyStore,
            Clock clock, SIMAccessor simAccessor, PasspointObjectFactory objectFactory) {
        mHandler = objectFactory.makePasspointEventHandler(wifiNative,
                new CallbackHandler(context));
        mKeyStore = keyStore;
        mSimAccessor = simAccessor;
        mObjectFactory = objectFactory;
        mProviders = new HashMap<>();
        mAnqpCache = objectFactory.makeAnqpCache(clock);
        mAnqpRequestManager = objectFactory.makeANQPRequestManager(mHandler, clock);
        mProviderID = 0;
        // TODO(zqiu): load providers from the persistent storage.
    }

    /**
     * Add or update a Passpoint provider with the given configuration.
     *
     * Each provider is uniquely identified by its FQDN (Fully Qualified Domain Name).
     * In the case when there is an existing configuration with the same FQDN
     * a provider with the new configuration will replace the existing provider.
     *
     * @param config Configuration of the Passpoint provider to be added
     * @return true if provider is added, false otherwise
     */
    public boolean addOrUpdateProvider(PasspointConfiguration config) {
        if (config == null) {
            Log.e(TAG, "Configuration not provided");
            return false;
        }
        if (!config.validate()) {
            Log.e(TAG, "Invalid configuration");
            return false;
        }

        // Verify IMSI against the IMSI of the installed SIM cards for SIM credential.
        if (config.getCredential().getSimCredential() != null) {
            if (mSimAccessor.getMatchingImsis(IMSIParameter.build(
                    config.getCredential().getSimCredential().getImsi())) == null) {
                Log.e(TAG, "IMSI does not match any SIM card");
                return false;
            }
        }

        // Create a provider and install the necessary certificates and keys.
        PasspointProvider newProvider = mObjectFactory.makePasspointProvider(
                config, mKeyStore, mSimAccessor, mProviderID++);

        if (!newProvider.installCertsAndKeys()) {
            Log.e(TAG, "Failed to install certificates and keys to keystore");
            return false;
        }

        // Remove existing provider with the same FQDN.
        if (mProviders.containsKey(config.getHomeSp().getFqdn())) {
            Log.d(TAG, "Replacing configuration for " + config.getHomeSp().getFqdn());
            removeProvider(config.getHomeSp().getFqdn());
        }

        mProviders.put(config.getHomeSp().getFqdn(), newProvider);

        // TODO(b/31065385): Persist updated providers configuration to the persistent storage.

        return true;
    }

    /**
     * Remove a Passpoint provider identified by the given FQDN.
     *
     * @param fqdn The FQDN of the provider to remove
     * @return true if a provider is removed, false otherwise
     */
    public boolean removeProvider(String fqdn) {
        if (!mProviders.containsKey(fqdn)) {
            Log.e(TAG, "Config doesn't exist");
            return false;
        }

        mProviders.get(fqdn).uninstallCertsAndKeys();
        mProviders.remove(fqdn);
        return true;
    }

    /**
     * Return the installed Passpoint provider configurations.
     *
     * An empty list will be returned when no provider is installed.
     *
     * @return A list of {@link PasspointConfiguration}
     */
    public List<PasspointConfiguration> getProviderConfigs() {
        List<PasspointConfiguration> configs = new ArrayList<>();
        for (Map.Entry<String, PasspointProvider> entry : mProviders.entrySet()) {
            configs.add(entry.getValue().getConfig());
        }
        return configs;
    }

    /**
     * Find the providers that can provide service through the given AP, which means the
     * providers contained credential to authenticate with the given AP.
     *
     * An empty list will returned in the case when no match is found.
     *
     * @param scanDetail The detail information of the AP
     * @return List of {@link PasspointProvider}
     */
    public List<Pair<PasspointProvider, PasspointMatch>> matchProvider(ScanDetail scanDetail) {
        // Nothing to be done if no Passpoint provider is installed.
        if (mProviders.isEmpty()) {
            return new ArrayList<Pair<PasspointProvider, PasspointMatch>>();
        }

        // Lookup ANQP data in the cache.
        NetworkDetail networkDetail = scanDetail.getNetworkDetail();
        ANQPNetworkKey anqpKey = ANQPNetworkKey.buildKey(networkDetail.getSSID(),
                networkDetail.getBSSID(), networkDetail.getHESSID(),
                networkDetail.getAnqpDomainID());
        ANQPData anqpEntry = mAnqpCache.getEntry(anqpKey);

        if (anqpEntry == null) {
            mAnqpRequestManager.requestANQPElements(networkDetail.getBSSID(), scanDetail,
                    networkDetail.getAnqpOICount() > 0,
                    networkDetail.getHSRelease() == NetworkDetail.HSRelease.R2);
            return new ArrayList<Pair<PasspointProvider, PasspointMatch>>();
        }

        List<Pair<PasspointProvider, PasspointMatch>> results = new ArrayList<>();
        for (Map.Entry<String, PasspointProvider> entry : mProviders.entrySet()) {
            PasspointProvider provider = entry.getValue();
            PasspointMatch matchStatus = provider.match(anqpEntry.getElements());
            if (matchStatus == PasspointMatch.HomeProvider
                    || matchStatus == PasspointMatch.RoamingProvider) {
                results.add(new Pair<PasspointProvider, PasspointMatch>(provider, matchStatus));
            }
        }
        return results;
    }

    /**
     * Sweep the ANQP cache to remove expired entries.
     */
    public void sweepCache() {
        mAnqpCache.sweep();
    }

    /**
     * Notify the completion of an ANQP request.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void notifyANQPDone(long bssid, boolean success) {
        mHandler.notifyANQPDone(bssid, success);
    }

    /**
     * Notify the completion of an icon request.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void notifyIconDone(long bssid, IconEvent iconEvent) {
        mHandler.notifyIconDone(bssid, iconEvent);
    }

    /**
     * Notify the reception of a Wireless Network Management (WNM) frame.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void receivedWnmFrame(WnmData data) {
        mHandler.notifyWnmFrameReceived(data);
    }

    /**
     * Request the specified icon file |fileName| from the specified AP |bssid|.
     * @return true if the request is sent successfully, false otherwise
     */
    public boolean queryPasspointIcon(long bssid, String fileName) {
        return mHandler.requestIcon(bssid, fileName);
    }
}
