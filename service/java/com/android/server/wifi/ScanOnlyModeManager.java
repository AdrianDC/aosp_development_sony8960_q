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

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.net.InterfaceConfiguration;
import android.net.wifi.IClientInterface;
import android.net.wifi.WifiManager;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.BaseNetworkObserver;

/**
 * Manager WiFi in Scan Only Mode - no network connections.
 */
public class ScanOnlyModeManager implements ActiveModeManager {

    private final ScanOnlyModeStateMachine mStateMachine;

    private static final String TAG = "ScanOnlyModeManager";

    private final Context mContext;
    private final WifiNative mWifiNative;

    private final INetworkManagementService mNwService;
    private final WifiMetrics mWifiMetrics;
    private final WifiMonitor mWifiMonitor;
    private final Listener mListener;

    private IClientInterface mClientInterface;
    private String mClientInterfaceName;


    ScanOnlyModeManager(Context context, @NonNull Looper looper, WifiNative wifiNative,
            Listener listener, INetworkManagementService networkManagementService,
            WifiMetrics wifiMetrics, WifiMonitor wifiMonitor) {
        mContext = context;
        mWifiNative = wifiNative;
        mListener = listener;
        mNwService = networkManagementService;
        mWifiMetrics = wifiMetrics;
        mWifiMonitor = wifiMonitor;
        mStateMachine = new ScanOnlyModeStateMachine(looper);
    }

    /**
     * Start scan only mode.
     */
    public void start() {
        mStateMachine.sendMessage(ScanOnlyModeStateMachine.CMD_START);
    }

    /**
     * Cancel any pending scans and stop scan mode.
     */
    public void stop() {
        mStateMachine.sendMessage(ScanOnlyModeStateMachine.CMD_STOP);
    }

    /**
     * Helper function to increment the appropriate setup failure metrics.
     *
     * Note: metrics about these failures will move to where the issues are actually detected
     * (b/69426063)
     */
    private void incrementMetricsForSetupFailure(int failureReason) {
        if (failureReason == WifiNative.SETUP_FAILURE_HAL) {
            mWifiMetrics.incrementNumWifiOnFailureDueToHal();
        } else if (failureReason == WifiNative.SETUP_FAILURE_WIFICOND) {
            mWifiMetrics.incrementNumWifiOnFailureDueToWificond();
        }
    }

    /**
     * Listener for ScanOnlyMode state changes.
     */
    public interface Listener {
        /**
         * Invoke when wifi state changes.
         * @param state new wifi state
         */
        void onStateChanged(int state);
    }

    /**
     * Update Wifi state.
     * @param state new Wifi state
     */
    private void updateWifiState(int state) {
        if (mListener != null) {
            mListener.onStateChanged(state);
        }
    }

    private class ScanOnlyModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_STOP = 1;
        public static final int CMD_CLIENT_INTERFACE_BINDER_DEATH = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();

        private NetworkObserver mNetworkObserver;
        private boolean mIfaceIsUp = false;

        private class NetworkObserver extends BaseNetworkObserver {
            private final String mIfaceName;
            NetworkObserver(String ifaceName) {
                mIfaceName = ifaceName;
            }

            @Override
            public void interfaceLinkStateChanged(String iface, boolean up) {
                Log.d(TAG, "iface update: " + iface + " our iface: " + mIfaceName);
                if (mIfaceName.equals(iface)) {
                    ScanOnlyModeStateMachine.this.sendMessage(
                            CMD_INTERFACE_STATUS_CHANGED, up ? 1 : 0 , 0, this);
                    Log.d(TAG, "sent interface status changed message");
                }
            }
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

        ScanOnlyModeStateMachine(Looper looper) {
            super(TAG, looper);

            addState(mIdleState);
            addState(mStartedState);

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {

            @Override
            public void enter() {
                Log.d(TAG, "entering IdleState");
                unregisterObserver();
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        mClientInterface = null;
                        Pair<Integer, IClientInterface> statusAndInterface =
                                mWifiNative.setupForClientMode(mWifiNative.getInterfaceName());
                        if (statusAndInterface.first == WifiNative.SETUP_SUCCESS) {
                            mClientInterface = statusAndInterface.second;
                        } else {
                            incrementMetricsForSetupFailure(statusAndInterface.first);
                        }
                        if (mClientInterface == null) {
                            Log.e(TAG, "Failed to create ClientInterface.");
                            sendScanAvailableBroadcast(false);
                            updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
                            break;
                        }
                        try {
                            mClientInterfaceName = mClientInterface.getInterfaceName();
                        } catch (RemoteException e) {
                            Log.e(TAG, "Failed to retrieve ClientInterface name.");
                            sendScanAvailableBroadcast(false);
                            updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
                            break;
                        }

                        if (TextUtils.isEmpty(mClientInterfaceName)) {
                            Log.e(TAG, "Failed to retrieve ClientInterface name. Sit in Idle");
                            sendScanAvailableBroadcast(false);
                            updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
                            break;
                        }

                        if (!mWifiNative.enableSupplicant()) {
                            sendScanAvailableBroadcast(false);
                            updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
                            break;
                        }
                        mWifiMonitor.startMonitoring(mClientInterfaceName, true);


                        try {
                            mNetworkObserver =
                                    new NetworkObserver(mClientInterface.getInterfaceName());
                            mNwService.registerObserver(mNetworkObserver);
                        } catch (RemoteException e) {
                            mWifiNative.disableSupplicant();
                            unregisterObserver();
                            sendScanAvailableBroadcast(false);
                            updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
                            break;
                        }

                        transitionTo(mStartedState);
                        break;
                    case CMD_STOP:
                        // This should be safe to ignore.
                        Log.d(TAG, "received CMD_STOP when idle, ignoring");
                        break;
                    default:
                        Log.d(TAG, "received an invalid message: " + message);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        private class StartedState extends State {

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }
                mIfaceIsUp = isUp;
                if (isUp) {
                    Log.d(TAG, "Wifi is ready to use for scanning");
                    sendScanAvailableBroadcast(true);
                    updateWifiState(WifiManager.WIFI_STATE_ENABLED);
                } else {
                    // if the interface goes down we should exit and go back to idle state.
                    Log.d(TAG, "interface down - stop scan mode");
                    mStateMachine.sendMessage(CMD_STOP);
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "entering StartedState");
                mIfaceIsUp = false;
                InterfaceConfiguration config = null;
                try {
                    config = mNwService.getInterfaceConfig(mClientInterfaceName);
                } catch (RemoteException e) {
                }
                if (config != null) {
                    onUpChanged(config.isUp());
                }

                if (mIfaceIsUp) {
                    // we already received the interface up notification when we were setting up
                    sendScanAvailableBroadcast(true);
                    updateWifiState(WifiManager.WIFI_STATE_ENABLED);
                }
            }

            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_STOP:
                        Log.d(TAG, "Stopping scan mode.");
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        if (message.obj != mNetworkObserver) {
                            // This is not from our current observer
                            break;
                        }
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_CLIENT_INTERFACE_BINDER_DEATH:
                        Log.d(TAG, "interface binder death!  restart services?");
                        updateWifiState(WifiManager.WIFI_STATE_UNKNOWN);
                        transitionTo(mIdleState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            /**
             * Clean up state, unregister listeners and send broadcast to tell WifiScanner
             * that wifi is disabled.
             */
            @Override
            public void exit() {
                // let WifiScanner know that wifi is down.
                sendScanAvailableBroadcast(false);
                updateWifiState(WifiManager.WIFI_STATE_DISABLED);

                unregisterObserver();
            }
        }

        private void sendScanAvailableBroadcast(boolean available) {
            Log.d(TAG, "sending scan available broadcast: " + available);
            final Intent intent = new Intent(WifiManager.WIFI_SCAN_AVAILABLE);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            if (available) {
                intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_ENABLED);
            } else {
                intent.putExtra(WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_DISABLED);
            }
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }
    }
}
