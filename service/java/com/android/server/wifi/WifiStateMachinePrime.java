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
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * This class provides the implementation for different WiFi operating modes.
 *
 * NOTE: The class is a WIP and is in active development.  It is intended to replace the existing
 * WifiStateMachine.java class when the rearchitecture is complete.
 */
public class WifiStateMachinePrime {
    private static final String TAG = "WifiStateMachinePrime";

    private ModeStateMachine mModeStateMachine;

    private final WifiInjector mWifiInjector;
    private final Looper mLooper;
    private final WifiNative mWifiNative;
    private final INetworkManagementService mNMService;

    private Queue<SoftApModeConfiguration> mApConfigQueue = new ConcurrentLinkedQueue<>();

    private String mInterfaceName;

    /* The base for wifi message types */
    static final int BASE = Protocol.BASE_WIFI;

    /* Start the soft access point */
    static final int CMD_START_AP                                       = BASE + 21;
    /* Indicates soft ap start failed */
    static final int CMD_START_AP_FAILURE                               = BASE + 22;
    /* Stop the soft access point */
    static final int CMD_STOP_AP                                        = BASE + 23;
    /* Soft access point teardown is completed. */
    static final int CMD_AP_STOPPED                                     = BASE + 24;

    WifiStateMachinePrime(WifiInjector wifiInjector,
                          Looper looper,
                          WifiNative wifiNative,
                          INetworkManagementService nmService) {
        mWifiInjector = wifiInjector;
        mLooper = looper;
        mWifiNative = wifiNative;
        mNMService = nmService;

        mInterfaceName = mWifiNative.getInterfaceName();

        // make sure we do not have leftover state in the event of a restart
        mWifiNative.tearDown();
    }

    /**
     * Method to switch wifi into client mode where connections to configured networks will be
     * attempted.
     */
    public void enterClientMode() {
        changeMode(ModeStateMachine.CMD_START_CLIENT_MODE);
    }

    /**
     * Method to switch wifi into scan only mode where network connection attempts will not be made.
     *
     * This mode is utilized by location scans.  If wifi is disabled by a user, but they have
     * previously configured their device to perform location scans, this mode allows wifi to
     * fulfill the location scan requests but will not be used for connectivity.
     */
    public void enterScanOnlyMode() {
        changeMode(ModeStateMachine.CMD_START_SCAN_ONLY_MODE);
    }

    /**
     * Method to enable soft ap for wifi hotspot.
     *
     * The supplied SoftApModeConfiguration includes the target softap WifiConfiguration (or null if
     * the persisted config is to be used) and the target operating mode (ex,
     * {@link WifiManager.IFACE_IP_MODE_TETHERED} {@link WifiManager.IFACE_IP_MODE_LOCAL_ONLY}).
     *
     * @param wifiConfig SoftApModeConfiguration for the hostapd softap
     */
    public void enterSoftAPMode(@NonNull SoftApModeConfiguration wifiConfig) {
        mApConfigQueue.offer(wifiConfig);
        changeMode(ModeStateMachine.CMD_START_SOFT_AP_MODE);
    }

    /**
     * Method to fully disable wifi.
     *
     * This mode will completely shut down wifi and will not perform any network scans.
     */
    public void disableWifi() {
        changeMode(ModeStateMachine.CMD_DISABLE_WIFI);
    }

    protected String getCurrentMode() {
        if (mModeStateMachine != null) {
            return mModeStateMachine.getCurrentMode();
        }
        return "WifiDisabledState";
    }

    private void changeMode(int newMode) {
        if (mModeStateMachine == null) {
            if (newMode == ModeStateMachine.CMD_DISABLE_WIFI) {
                // command is to disable wifi, but it is already disabled.
                Log.e(TAG, "Received call to disable wifi when it is already disabled.");
                return;
            }
            // state machine was not initialized yet, we must be starting up.
            mModeStateMachine = new ModeStateMachine();
        }
        mModeStateMachine.sendMessage(newMode);
    }

    private class ModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START_CLIENT_MODE    = 0;
        public static final int CMD_START_SCAN_ONLY_MODE = 1;
        public static final int CMD_START_SOFT_AP_MODE   = 2;
        public static final int CMD_DISABLE_WIFI         = 3;

        // Create the base modes for WSM.
        private final State mClientModeState = new ClientModeState();
        private final State mScanOnlyModeState = new ScanOnlyModeState();
        private final State mSoftAPModeState = new SoftAPModeState();
        private final State mWifiDisabledState = new WifiDisabledState();

        // Create the active versions of the modes for WSM.
        private final State mClientModeActiveState = new ClientModeActiveState();
        private final State mScanOnlyModeActiveState = new ScanOnlyModeActiveState();
        private final State mSoftAPModeActiveState = new SoftAPModeActiveState();

        ModeStateMachine() {
            super(TAG, mLooper);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mClientModeState);
              addState(mClientModeActiveState, mClientModeState);
            addState(mScanOnlyModeState);
              addState(mScanOnlyModeActiveState, mScanOnlyModeState);
            addState(mSoftAPModeState);
              addState(mSoftAPModeActiveState, mSoftAPModeState);
            addState(mWifiDisabledState);
            // CHECKSTYLE:ON IndentationCheck

            Log.d(TAG, "Starting Wifi in WifiDisabledState");
            setInitialState(mWifiDisabledState);
            start();
        }

        private String getCurrentMode() {
            return getCurrentState().getName();
        }

        private boolean checkForAndHandleModeChange(Message message) {
            switch(message.what) {
                case ModeStateMachine.CMD_START_CLIENT_MODE:
                    Log.d(TAG, "Switching from " + getCurrentMode() + " to ClientMode");
                    mModeStateMachine.transitionTo(mClientModeState);
                    break;
                case ModeStateMachine.CMD_START_SCAN_ONLY_MODE:
                    Log.d(TAG, "Switching from " + getCurrentMode() + " to ScanOnlyMode");
                    mModeStateMachine.transitionTo(mScanOnlyModeState);
                    break;
                case ModeStateMachine.CMD_START_SOFT_AP_MODE:
                    Log.d(TAG, "Switching from " + getCurrentMode() + " to SoftApMode");
                    mModeStateMachine.transitionTo(mSoftAPModeState);
                    break;
                case ModeStateMachine.CMD_DISABLE_WIFI:
                    Log.d(TAG, "Switching from " + getCurrentMode() + " to WifiDisabled");
                    mModeStateMachine.transitionTo(mWifiDisabledState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private void cleanup() {
            mWifiNative.disableSupplicant();
            mWifiNative.tearDown();
        }

        class ClientModeState extends State {
            @Override
            public void enter() {
            }

            @Override
            public boolean processMessage(Message message) {
                if (checkForAndHandleModeChange(message)) {
                    return HANDLED;
                }
                return NOT_HANDLED;
            }

            @Override
            public void exit() {
                cleanup();
            }
        }

        class ScanOnlyModeState extends State {
            @Override
            public void enter() {
            }

            @Override
            public boolean processMessage(Message message) {
                if (checkForAndHandleModeChange(message)) {
                    return HANDLED;
                }
                return NOT_HANDLED;
            }

            @Override
            public void exit() {
                // Do not tear down interfaces yet since this mode is not actively controlled or
                // used in tests at this time.
                // tearDownInterfaces();
            }
        }

        class SoftAPModeState extends State {

            @Override
            public void enter() {
                // For now - need to clean up from other mode management in WSM
                cleanup();

                final Message message = mModeStateMachine.getCurrentMessage();
                if (message.what != ModeStateMachine.CMD_START_SOFT_AP_MODE) {
                    Log.d(TAG, "Entering SoftAPMode (idle)");
                    return;
                }

                mModeStateMachine.transitionTo(mSoftAPModeActiveState);
            }

            @Override
            public boolean processMessage(Message message) {
                if (checkForAndHandleModeChange(message)) {
                    return HANDLED;
                }

                switch(message.what) {
                    case CMD_START_AP:
                        Log.d(TAG, "Received CMD_START_AP (now invalid message) - dropping");
                        break;
                    case CMD_STOP_AP:
                        // not in active state, nothing to stop.
                        break;
                    case CMD_START_AP_FAILURE:
                        // with interface management in softapmanager, no setup failures can be seen
                        // here
                        break;
                    case CMD_AP_STOPPED:
                        Log.d(TAG, "SoftApModeActiveState stopped.  Wait for next mode command.");
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                // while in transition, cleanup is done on entering states.  in the future, each
                // mode will clean up their own state on exit
                //cleanup();
            }

            private void initializationFailed(String message) {
                Log.e(TAG, message);
                mModeStateMachine.sendMessage(CMD_START_AP_FAILURE);
            }
        }

        class WifiDisabledState extends State {
            @Override
            public void enter() {
                Log.d(TAG, "Entering WifiDisabledState");
                // make sure everything is torn down
                cleanup();
            }

            @Override
            public boolean processMessage(Message message) {
                Log.d(TAG, "received a message in WifiDisabledState: " + message);
                if (checkForAndHandleModeChange(message)) {
                    return HANDLED;
                }
                return NOT_HANDLED;
            }

        }

        class ModeActiveState extends State {
            ActiveModeManager mActiveModeManager;

            @Override
            public boolean processMessage(Message message) {
                // handle messages for changing modes here
                return NOT_HANDLED;
            }

            @Override
            public void exit() {
                // clean up objects from an active state - check with mode handlers to make sure
                // they are stopping properly.
                mActiveModeManager.stop();
            }
        }

        class ClientModeActiveState extends ModeActiveState {
            @Override
            public void enter() {
                this.mActiveModeManager = new ClientModeManager();
            }
        }

        class ScanOnlyModeActiveState extends ModeActiveState {
            @Override
            public void enter() {
                this.mActiveModeManager = new ScanOnlyModeManager();
            }
        }

        class SoftAPModeActiveState extends ModeActiveState {
            private class SoftApListener implements SoftApManager.Listener {
                @Override
                public void onStateChanged(int state, int reason) {
                    if (state == WifiManager.WIFI_AP_STATE_DISABLED) {
                        mModeStateMachine.sendMessage(CMD_AP_STOPPED);
                    } else if (state == WifiManager.WIFI_AP_STATE_FAILED) {
                        mModeStateMachine.sendMessage(CMD_START_AP_FAILURE);
                    }
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "Entering SoftApModeActiveState");
                SoftApModeConfiguration softApModeConfig = mApConfigQueue.poll();
                WifiConfiguration config = softApModeConfig.getWifiConfiguration();
                // TODO (b/67601382): add checks for valid softap configs
                if (config != null && config.SSID != null) {
                    Log.d(TAG, "Passing config to SoftApManager! " + config);
                } else {
                    config = null;
                }
                this.mActiveModeManager = mWifiInjector.makeSoftApManager(mNMService,
                        new SoftApListener(), softApModeConfig);
                this.mActiveModeManager.start();
            }

            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    case CMD_START_AP:
                        Log.d(TAG, "Received CMD_START_AP when active - invalid message - drop");
                        break;
                    case CMD_STOP_AP:
                        mActiveModeManager.stop();
                        break;
                    case CMD_START_AP_FAILURE:
                        Log.d(TAG, "Failed to start SoftApMode.  Return to SoftApMode (inactive).");
                        mModeStateMachine.transitionTo(mSoftAPModeState);
                        break;
                    case CMD_AP_STOPPED:
                        Log.d(TAG, "SoftApModeActiveState stopped."
                                + "  Return to SoftApMode (inactive).");
                        mModeStateMachine.transitionTo(mSoftAPModeState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }
    }  // class ModeStateMachine
}
