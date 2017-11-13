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

package com.android.server.wifi;

import static com.android.server.wifi.util.ApConfigUtil.ERROR_GENERIC;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_NO_CHANNEL;
import static com.android.server.wifi.util.ApConfigUtil.SUCCESS;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.net.InterfaceConfiguration;
import android.net.wifi.IApInterface;
import android.net.wifi.IApInterfaceEventCallback;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiManager;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.wifi.util.ApConfigUtil;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Manage WiFi in AP mode.
 * The internal state machine runs under "WifiStateMachine" thread context.
 */
public class SoftApManager implements ActiveModeManager {
    private static final String TAG = "SoftApManager";

    private final Context mContext;

    private final WifiNative mWifiNative;

    private final String mCountryCode;

    private final SoftApStateMachine mStateMachine;

    private final Listener mListener;

    private final IApInterface mApInterface;
    private final String mApInterfaceName;

    private final INetworkManagementService mNwService;
    private final WifiApConfigStore mWifiApConfigStore;

    private final WifiMetrics mWifiMetrics;

    private final int mMode;
    private WifiConfiguration mApConfig;

    private int mNumAssociatedStations = 0;

    /**
     * Listener for AP Interface events.
     */
    public class ApInterfaceListener extends IApInterfaceEventCallback.Stub {
        @Override
        public void onNumAssociatedStationsChanged(int numStations) {
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_NUM_ASSOCIATED_STATIONS_CHANGED, numStations);
        }
    }

    /**
     * Listener for soft AP state changes.
     */
    public interface Listener {
        /**
         * Invoke when AP state changed.
         * @param state new AP state
         * @param failureReason reason when in failed state
         */
        void onStateChanged(int state, int failureReason);
    }

    public SoftApManager(Context context,
                         Looper looper,
                         WifiNative wifiNative,
                         String countryCode,
                         Listener listener,
                         @NonNull IApInterface apInterface,
                         @NonNull String ifaceName,
                         INetworkManagementService nms,
                         WifiApConfigStore wifiApConfigStore,
                         @NonNull SoftApModeConfiguration apConfig,
                         WifiMetrics wifiMetrics) {
        mStateMachine = new SoftApStateMachine(looper);

        mContext = context;
        mWifiNative = wifiNative;
        mCountryCode = countryCode;
        mListener = listener;
        mApInterface = apInterface;
        mApInterfaceName = ifaceName;
        mNwService = nms;
        mWifiApConfigStore = wifiApConfigStore;
        mMode = apConfig.getTargetMode();
        WifiConfiguration config = apConfig.getWifiConfiguration();
        if (config == null) {
            mApConfig = mWifiApConfigStore.getApConfiguration();
        } else {
            mApConfig = config;
        }
        mWifiMetrics = wifiMetrics;
    }

    /**
     * Start soft AP with the supplied config.
     */
    public void start() {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_START, mApConfig);
    }

    /**
     * Stop soft AP.
     */
    public void stop() {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_STOP);
    }

    /**
     * Get number of stations associated with this soft AP
     */
    @VisibleForTesting
    public int getNumAssociatedStations() {
        return mNumAssociatedStations;
    }

    /**
     * Set number of stations associated with this soft AP
     * @param numStations Number of connected stations
     */
    private void setNumAssociatedStations(int numStations) {
        if (mNumAssociatedStations == numStations) {
            return;
        }
        mNumAssociatedStations = numStations;
        Log.d(TAG, "Number of associated stations changed: " + mNumAssociatedStations);

        // TODO:(b/63906412) send it up to settings.
        mWifiMetrics.addSoftApNumAssociatedStationsChangedEvent(mNumAssociatedStations, mMode);
    }

    /**
     * Update AP state.
     * @param newState new AP state
     * @param currentState current AP state
     * @param reason Failure reason if the new AP state is in failure state
     */
    private void updateApState(int newState, int currentState, int reason) {
        if (mListener != null) {
            mListener.onStateChanged(newState, reason);
        }

        //send the AP state change broadcast
        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, newState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, currentState);
        if (newState == WifiManager.WIFI_AP_STATE_FAILED) {
            //only set reason number when softAP start failed
            intent.putExtra(WifiManager.EXTRA_WIFI_AP_FAILURE_REASON, reason);
        }

        intent.putExtra(WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME, mApInterfaceName);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_MODE, mMode);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    /**
     * Start a soft AP instance with the given configuration.
     * @param config AP configuration
     * @return integer result code
     */
    private int startSoftAp(WifiConfiguration config) {
        if (config == null || config.SSID == null) {
            Log.e(TAG, "Unable to start soft AP without valid configuration");
            return ERROR_GENERIC;
        }

        // Make a copy of configuration for updating AP band and channel.
        WifiConfiguration localConfig = new WifiConfiguration(config);

        int result = ApConfigUtil.updateApChannelConfig(
                mWifiNative, mCountryCode,
                mWifiApConfigStore.getAllowed2GChannel(), localConfig);

        if (result != SUCCESS) {
            Log.e(TAG, "Failed to update AP band and channel");
            return result;
        }

        // Setup country code if it is provided.
        if (mCountryCode != null) {
            // Country code is mandatory for 5GHz band, return an error if failed to set
            // country code when AP is configured for 5GHz band.
            if (!mWifiNative.setCountryCodeHal(mCountryCode.toUpperCase(Locale.ROOT))
                    && config.apBand == WifiConfiguration.AP_BAND_5GHZ) {
                Log.e(TAG, "Failed to set country code, required for setting up "
                        + "soft ap in 5GHz");
                return ERROR_GENERIC;
            }
        }

        int encryptionType = getIApInterfaceEncryptionType(localConfig);

        if (localConfig.hiddenSSID) {
            Log.d(TAG, "SoftAP is a hidden network");
        }

        try {
            // Note that localConfig.SSID is intended to be either a hex string or "double quoted".
            // However, it seems that whatever is handing us these configurations does not obey
            // this convention.
            boolean success = mApInterface.writeHostapdConfig(
                    localConfig.SSID.getBytes(StandardCharsets.UTF_8), localConfig.hiddenSSID,
                    localConfig.apChannel, encryptionType,
                    (localConfig.preSharedKey != null)
                            ? localConfig.preSharedKey.getBytes(StandardCharsets.UTF_8)
                            : new byte[0]);
            if (!success) {
                Log.e(TAG, "Failed to write hostapd configuration");
                return ERROR_GENERIC;
            }

            success = mApInterface.startHostapd(new ApInterfaceListener());
            if (!success) {
                Log.e(TAG, "Failed to start hostapd.");
                return ERROR_GENERIC;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in starting soft AP: " + e);
        }

        Log.d(TAG, "Soft AP is started");

        return SUCCESS;
    }

    private static int getIApInterfaceEncryptionType(WifiConfiguration localConfig) {
        int encryptionType;
        switch (localConfig.getAuthType()) {
            case KeyMgmt.NONE:
                encryptionType = IApInterface.ENCRYPTION_TYPE_NONE;
                break;
            case KeyMgmt.WPA_PSK:
                encryptionType = IApInterface.ENCRYPTION_TYPE_WPA;
                break;
            case KeyMgmt.WPA2_PSK:
                encryptionType = IApInterface.ENCRYPTION_TYPE_WPA2;
                break;
            default:
                // We really shouldn't default to None, but this was how NetworkManagementService
                // used to do this.
                encryptionType = IApInterface.ENCRYPTION_TYPE_NONE;
                break;
        }
        return encryptionType;
    }

    /**
     * Teardown soft AP.
     */
    private void stopSoftAp() {
        try {
            mApInterface.stopHostapd();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in stopping soft AP: " + e);
            return;
        }
        Log.d(TAG, "Soft AP is stopped");
    }

    private class SoftApStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_STOP = 1;
        public static final int CMD_AP_INTERFACE_BINDER_DEATH = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;
        public static final int CMD_NUM_ASSOCIATED_STATIONS_CHANGED = 4;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();

        private final StateMachineDeathRecipient mDeathRecipient =
                new StateMachineDeathRecipient(this, CMD_AP_INTERFACE_BINDER_DEATH);

        private NetworkObserver mNetworkObserver;

        private class NetworkObserver extends BaseNetworkObserver {
            private final String mIfaceName;

            NetworkObserver(String ifaceName) {
                mIfaceName = ifaceName;
            }

            @Override
            public void interfaceLinkStateChanged(String iface, boolean up) {
                if (mIfaceName.equals(iface)) {
                    SoftApStateMachine.this.sendMessage(
                            CMD_INTERFACE_STATUS_CHANGED, up ? 1 : 0, 0, this);
                }
            }
        }

        SoftApStateMachine(Looper looper) {
            super(TAG, looper);

            addState(mIdleState);
            addState(mStartedState);

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {
            @Override
            public void enter() {
                mDeathRecipient.unlinkToDeath();
                unregisterObserver();
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        // first a sanity check on the interface name.  If we failed to retrieve it,
                        // we are going to have a hard time setting up routing.
                        if (TextUtils.isEmpty(mApInterfaceName)) {
                            Log.e(TAG, "Not starting softap mode without an interface name.");
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                          WifiManager.WIFI_AP_STATE_DISABLED,
                                          WifiManager.SAP_START_FAILURE_GENERAL);
                            mWifiMetrics.incrementSoftApStartResult(
                                    false, WifiManager.SAP_START_FAILURE_GENERAL);
                            break;
                        }
                        updateApState(WifiManager.WIFI_AP_STATE_ENABLING,
                                WifiManager.WIFI_AP_STATE_DISABLED, 0);
                        setNumAssociatedStations(0);
                        if (!mDeathRecipient.linkToDeath(mApInterface.asBinder())) {
                            mDeathRecipient.unlinkToDeath();
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                    WifiManager.WIFI_AP_STATE_ENABLING,
                                    WifiManager.SAP_START_FAILURE_GENERAL);
                            mWifiMetrics.incrementSoftApStartResult(
                                    false, WifiManager.SAP_START_FAILURE_GENERAL);
                            break;
                        }

                        try {
                            mNetworkObserver = new NetworkObserver(mApInterfaceName);
                            mNwService.registerObserver(mNetworkObserver);
                        } catch (RemoteException e) {
                            mDeathRecipient.unlinkToDeath();
                            unregisterObserver();
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                          WifiManager.WIFI_AP_STATE_ENABLING,
                                          WifiManager.SAP_START_FAILURE_GENERAL);
                            mWifiMetrics.incrementSoftApStartResult(
                                    false, WifiManager.SAP_START_FAILURE_GENERAL);
                            break;
                        }

                        int result = startSoftAp((WifiConfiguration) message.obj);
                        if (result != SUCCESS) {
                            int failureReason = WifiManager.SAP_START_FAILURE_GENERAL;
                            if (result == ERROR_NO_CHANNEL) {
                                failureReason = WifiManager.SAP_START_FAILURE_NO_CHANNEL;
                            }
                            mDeathRecipient.unlinkToDeath();
                            unregisterObserver();
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                          WifiManager.WIFI_AP_STATE_ENABLING,
                                          failureReason);
                            mWifiMetrics.incrementSoftApStartResult(false, failureReason);
                            break;
                        }

                        transitionTo(mStartedState);
                        break;
                    default:
                        // Ignore all other commands.
                        break;
                }

                return HANDLED;
            }

            private void unregisterObserver() {
                if (mNetworkObserver == null) {
                    return;
                }
                try {
                    mNwService.unregisterObserver(mNetworkObserver);
                } catch (RemoteException e) { }
                mNetworkObserver = null;
            }
        }

        private class StartedState extends State {
            private boolean mIfaceIsUp;

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }
                mIfaceIsUp = isUp;
                if (isUp) {
                    Log.d(TAG, "SoftAp is ready for use");
                    updateApState(WifiManager.WIFI_AP_STATE_ENABLED,
                            WifiManager.WIFI_AP_STATE_ENABLING, 0);
                    mWifiMetrics.incrementSoftApStartResult(true, 0);
                } else {
                    // TODO: handle the case where the interface was up, but goes down
                }

                mWifiMetrics.addSoftApUpChangedEvent(isUp, mMode);
                setNumAssociatedStations(0);
            }

            @Override
            public void enter() {
                mIfaceIsUp = false;
                InterfaceConfiguration config = null;
                try {
                    config = mNwService.getInterfaceConfig(mApInterfaceName);
                } catch (RemoteException e) {
                }
                if (config != null) {
                    onUpChanged(config.isUp());
                }
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_NUM_ASSOCIATED_STATIONS_CHANGED:
                        if (message.arg1 < 0) {
                            Log.e(TAG, "Invalid number of associated stations: " + message.arg1);
                            break;
                        }
                        setNumAssociatedStations(message.arg1);
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        if (message.obj != mNetworkObserver) {
                            // This is from some time before the most recent configuration.
                            break;
                        }
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_AP_INTERFACE_BINDER_DEATH:
                    case CMD_STOP:
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        setNumAssociatedStations(0);
                        stopSoftAp();
                        if (message.what == CMD_AP_INTERFACE_BINDER_DEATH) {
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                          WifiManager.WIFI_AP_STATE_DISABLING,
                                          WifiManager.SAP_START_FAILURE_GENERAL);
                        } else {
                            updateApState(WifiManager.WIFI_AP_STATE_DISABLED,
                                    WifiManager.WIFI_AP_STATE_DISABLING, 0);
                        }
                        transitionTo(mIdleState);

                        // Need this here since we are exiting |Started| state and won't handle any
                        // future CMD_INTERFACE_STATUS_CHANGED events after this point
                        mWifiMetrics.addSoftApUpChangedEvent(false, mMode);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }
    }
}
