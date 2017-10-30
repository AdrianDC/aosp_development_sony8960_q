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
import android.net.wifi.IClientInterface;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

/**
 * Manager WiFi in Scan Only Mode - no network connections.
 */
public class ScanOnlyModeManager implements ActiveModeManager {

    private final ScanOnlyModeStateMachine mStateMachine;

    private static final String TAG = "ScanOnlyModeManager";

    private final WifiNative mWifiNative;
    private final WifiMetrics mWifiMetrics;

    private IClientInterface mClientInterface;
    private String mClientInterfaceName;

    ScanOnlyModeManager(@NonNull Looper looper, WifiNative wifiNative, WifiMetrics wifiMetrics) {
        mStateMachine = new ScanOnlyModeStateMachine(looper);
        mWifiNative = wifiNative;
        mWifiMetrics = wifiMetrics;
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
        // explicitly exit the current state this is a no-op if it is not running, otherwise it will
        // stop and clean up the state.
        mStateMachine.sendMessage(ScanOnlyModeStateMachine.CMD_STOP);
        mStateMachine.getCurrentState().exit();
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

    private class ScanOnlyModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_STOP = 1;
        public static final int CMD_CLIENT_INTERFACE_BINDER_DEATH = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();

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
                            break;
                        }
                        try {
                            mClientInterfaceName = mClientInterface.getInterfaceName();
                        } catch (RemoteException e) {
                            Log.e(TAG, "Failed to retrieve ClientInterface name.");
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

            @Override
            public void enter() {
                Log.d(TAG, "entering StartedState");
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
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }
    }
}
