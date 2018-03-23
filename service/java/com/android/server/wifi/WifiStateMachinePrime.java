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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.WifiNative.StatusListener;

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

    // Holder for active mode managers
    private final ArraySet<ActiveModeManager> mActiveModeManagers;
    // DefaultModeManager used to service API calls when there are not active mode managers.
    private DefaultModeManager mDefaultModeManager;

    private final WifiInjector mWifiInjector;
    private final Looper mLooper;
    private final WifiNative mWifiNative;
    private final IBatteryStats mBatteryStats;
    private final SelfRecovery mSelfRecovery;
    private BaseWifiDiagnostics mWifiDiagnostics;

    private Queue<SoftApModeConfiguration> mApConfigQueue = new ConcurrentLinkedQueue<>();

    // The base for wifi message types
    static final int BASE = Protocol.BASE_WIFI;

    // The message identifiers below are mapped to those in WifiStateMachine when applicable.
    // Start the soft access point
    static final int CMD_START_AP                                       = BASE + 21;
    // Indicates soft ap start failed
    static final int CMD_START_AP_FAILURE                               = BASE + 22;
    // Stop the soft access point
    static final int CMD_STOP_AP                                        = BASE + 23;
    // Soft access point teardown is completed
    static final int CMD_AP_STOPPED                                     = BASE + 24;

    // Start Scan Only mode
    static final int CMD_START_SCAN_ONLY_MODE                           = BASE + 200;
    // Indicates that start Scan only mode failed
    static final int CMD_START_SCAN_ONLY_MODE_FAILURE                   = BASE + 201;
    // Indicates that scan only mode stopped
    static final int CMD_STOP_SCAN_ONLY_MODE                            = BASE + 202;
    // ScanOnly mode teardown is complete
    static final int CMD_SCAN_ONLY_MODE_STOPPED                         = BASE + 203;
    // ScanOnly mode failed
    static final int CMD_SCAN_ONLY_MODE_FAILED                          = BASE + 204;

    // Start Client mode
    static final int CMD_START_CLIENT_MODE                              = BASE + 300;
    // Indicates that start client mode failed
    static final int CMD_START_CLIENT_MODE_FAILURE                      = BASE + 301;
    // Indicates that client mode stopped
    static final int CMD_STOP_CLIENT_MODE                               = BASE + 302;
    // Client mode teardown is complete
    static final int CMD_CLIENT_MODE_STOPPED                            = BASE + 303;
    // Client mode failed
    static final int CMD_CLIENT_MODE_FAILED                             = BASE + 304;

    private StatusListener mWifiNativeStatusListener;

    private WifiManager.SoftApCallback mSoftApCallback;
    private ScanOnlyModeManager.Listener mScanOnlyCallback;
    private ClientModeManager.Listener mClientModeCallback;

    /**
     * Called from WifiServiceImpl to register a callback for notifications from SoftApManager
     */
    public void registerSoftApCallback(@NonNull WifiManager.SoftApCallback callback) {
        mSoftApCallback = callback;
    }

    /**
     * Called from WifiController to register a callback for notifications from ScanOnlyModeManager
     */
    public void registerScanOnlyCallback(@NonNull ScanOnlyModeManager.Listener callback) {
        mScanOnlyCallback = callback;
    }

    /**
     * Called from WifiController to register a callback for notifications from ClientModeManager
     */
    public void registerClientModeCallback(@NonNull ClientModeManager.Listener callback) {
        // uncomment when client mode moves here
        // mClientModeCallback = callback;
    }

    WifiStateMachinePrime(WifiInjector wifiInjector,
                          Looper looper,
                          WifiNative wifiNative,
                          DefaultModeManager defaultModeManager,
                          IBatteryStats batteryStats) {
        mWifiInjector = wifiInjector;
        mLooper = looper;
        mWifiNative = wifiNative;
        mActiveModeManagers = new ArraySet();
        mDefaultModeManager = defaultModeManager;
        mBatteryStats = batteryStats;
        mSelfRecovery = mWifiInjector.getSelfRecovery();
        mWifiDiagnostics = mWifiInjector.makeWifiDiagnostics(mWifiNative);
        mModeStateMachine = new ModeStateMachine();
        mWifiNativeStatusListener = new WifiNativeStatusListener();
        mWifiNative.registerStatusListener(mWifiNativeStatusListener);
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
        return mModeStateMachine.getCurrentMode();
    }

    private void changeMode(int newMode) {
        mModeStateMachine.sendMessage(newMode);
    }

    private class ModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START_CLIENT_MODE    = 0;
        public static final int CMD_START_SCAN_ONLY_MODE = 1;
        public static final int CMD_START_SOFT_AP_MODE   = 2;
        public static final int CMD_DISABLE_WIFI         = 3;

        private final State mWifiDisabledState = new WifiDisabledState();
        private final State mClientModeActiveState = new ClientModeActiveState();
        private final State mScanOnlyModeActiveState = new ScanOnlyModeActiveState();
        private final State mSoftAPModeActiveState = new SoftAPModeActiveState();

        ModeStateMachine() {
            super(TAG, mLooper);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mClientModeActiveState);
            addState(mScanOnlyModeActiveState);
            addState(mSoftAPModeActiveState);
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
                    mModeStateMachine.transitionTo(mClientModeActiveState);
                    break;
                case ModeStateMachine.CMD_START_SCAN_ONLY_MODE:
                    Log.d(TAG, "Switching from " + getCurrentMode() + " to ScanOnlyMode");
                    mModeStateMachine.transitionTo(mScanOnlyModeActiveState);
                    break;
                case ModeStateMachine.CMD_START_SOFT_AP_MODE:
                    Log.d(TAG, "Switching from " + getCurrentMode() + " to SoftApMode");
                    mModeStateMachine.transitionTo(mSoftAPModeActiveState);
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
            // TODO: Remove this big hammer. We cannot support concurrent interfaces with this!
            mWifiNative.teardownAllInterfaces();
        }

        class ModeActiveState extends State {
            ActiveModeManager mManager;
            @Override
            public boolean processMessage(Message message) {
                // handle messages for changing modes here
                return NOT_HANDLED;
            }

            @Override
            public void exit() {
                // Active states must have a mode manager, so this should not be null, but it isn't
                // obvious from the structure - add a null check here, just in case this is missed
                // in the future
                if (mManager != null) {
                    mManager.stop();
                    mActiveModeManagers.remove(mManager);
                }
                updateBatteryStatsWifiState(false);
            }
        }

        class WifiDisabledState extends ModeActiveState {
            @Override
            public void enter() {
                Log.d(TAG, "Entering WifiDisabledState");
                // make sure everything is torn down - remove when client mode is moved here
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

            @Override
            public void exit() {
                // do not have an active mode manager...  nothing to clean up
            }

        }

        class ClientModeActiveState extends ModeActiveState {
            private class ClientListener implements ClientModeManager.Listener {
                @Override
                public void onStateChanged(int state) {
                    Log.d(TAG, "State changed from client mode.");
                    if (state == WifiManager.WIFI_STATE_UNKNOWN) {
                        // error while setting up client mode or an unexpected failure.
                        mModeStateMachine.sendMessage(CMD_CLIENT_MODE_FAILED);
                    } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                        // client mode stopped
                        mModeStateMachine.sendMessage(CMD_CLIENT_MODE_STOPPED);
                    } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                        // client mode is ready to go
                        Log.d(TAG, "client mode active");
                    } else {
                        // only care if client mode stopped or started, dropping
                    }
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "Entering ClientModeActiveState");

                mManager = mWifiInjector.makeClientModeManager(new ClientListener());
                // DO NOT CALL START YET
                // mActiveModemanager.start();
                mActiveModeManagers.add(mManager);

                updateBatteryStatsWifiState(true);
            }

            @Override
            public void exit() {
                Log.d(TAG, "Exiting ClientModeActiveState");

                // OVERRIDE exit() SO WE DO NOT CALL STOP (but we do need to report wifi off)

                updateBatteryStatsWifiState(false);
            }

            @Override
            public boolean processMessage(Message message) {
                if (checkForAndHandleModeChange(message)) {
                    return HANDLED;
                }
                return NOT_HANDLED;
            }
        }

        class ScanOnlyModeActiveState extends ModeActiveState {
            private class ScanOnlyListener implements ScanOnlyModeManager.Listener {
                @Override
                public void onStateChanged(int state) {
                    Log.d(TAG, "State changed from scan only mode.");
                    if (state == WifiManager.WIFI_STATE_UNKNOWN) {
                        Log.d(TAG, "ScanOnly mode failed");
                        // error while setting up scan mode or an unexpected failure.
                        mModeStateMachine.sendMessage(CMD_SCAN_ONLY_MODE_FAILED);
                    } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                        Log.d(TAG, "ScanOnly mode stopped");
                        //scan only mode stopped
                        mModeStateMachine.sendMessage(CMD_SCAN_ONLY_MODE_STOPPED);
                    } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                        // scan mode is ready to go
                        Log.d(TAG, "scan mode active");
                    } else {
                        Log.d(TAG, "unexpected state update: " + state);
                    }
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "Entering ScanOnlyModeActiveState");

                // make sure everything is torn down - remove when client mode is moved here
                cleanup();

                mManager = mWifiInjector.makeScanOnlyModeManager(new ScanOnlyListener());
                mManager.start();
                mActiveModeManagers.add(mManager);
                updateBatteryStatsWifiState(true);
            }

            @Override
            public boolean processMessage(Message message) {
                if (checkForAndHandleModeChange(message)) {
                    return HANDLED;
                }

                switch(message.what) {
                    case CMD_START_SCAN_ONLY_MODE:
                        Log.d(TAG, "Received CMD_START_SCAN_ONLY_MODE when active - drop");
                        break;
                    case CMD_SCAN_ONLY_MODE_FAILED:
                        Log.d(TAG, "ScanOnlyMode failed, return to WifiDisabledState.");
                        // notify WifiController that ScanOnlyMode failed
                        mScanOnlyCallback.onStateChanged(WifiManager.WIFI_STATE_UNKNOWN);
                        mModeStateMachine.transitionTo(mWifiDisabledState);
                        break;
                    case CMD_SCAN_ONLY_MODE_STOPPED:
                        Log.d(TAG, "ScanOnlyMode stopped, return to WifiDisabledState.");
                        // notify WifiController that ScanOnlyMode stopped
                        mScanOnlyCallback.onStateChanged(WifiManager.WIFI_STATE_DISABLED);
                        mModeStateMachine.transitionTo(mWifiDisabledState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class SoftAPModeActiveState extends ModeActiveState {
            private class SoftApCallbackImpl implements WifiManager.SoftApCallback {
                @Override
                public void onStateChanged(int state, int reason) {
                    if (state == WifiManager.WIFI_AP_STATE_DISABLED) {
                        mModeStateMachine.sendMessage(CMD_AP_STOPPED);
                    } else if (state == WifiManager.WIFI_AP_STATE_FAILED) {
                        mModeStateMachine.sendMessage(CMD_START_AP_FAILURE);
                    }

                    if (mSoftApCallback != null) {
                        mSoftApCallback.onStateChanged(state, reason);
                    } else {
                        Log.wtf(TAG, "SoftApCallback is null. Dropping StateChanged event.");
                    }
                }

                @Override
                public void onNumClientsChanged(int numClients) {
                    if (mSoftApCallback != null) {
                        mSoftApCallback.onNumClientsChanged(numClients);
                    } else {
                        Log.d(TAG, "SoftApCallback is null. Dropping NumClientsChanged event.");
                    }
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "Entering SoftApModeActiveState");

                // make sure everything is torn down - remove when client mode is moved here
                cleanup();

                SoftApModeConfiguration softApModeConfig = mApConfigQueue.poll();
                WifiConfiguration config = softApModeConfig.getWifiConfiguration();
                // TODO (b/67601382): add checks for valid softap configs
                if (config != null && config.SSID != null) {
                    Log.d(TAG, "Passing config to SoftApManager! " + config);
                } else {
                    config = null;
                }
                mManager = mWifiInjector.makeSoftApManager(
                        new SoftApCallbackImpl(), softApModeConfig);
                mManager.start();
                mActiveModeManagers.add(mManager);
                updateBatteryStatsWifiState(true);
            }

            @Override
            public boolean processMessage(Message message) {
                if (checkForAndHandleModeChange(message)) {
                    return HANDLED;
                }

                switch(message.what) {
                    case CMD_START_AP:
                        Log.d(TAG, "Received CMD_START_AP when active - invalid message - drop");
                        break;
                    case CMD_STOP_AP:
                        mManager.stop();
                        break;
                    case CMD_START_AP_FAILURE:
                        Log.d(TAG, "Failed to start SoftApMode.  Return to WifiDisabledState.");
                        mModeStateMachine.transitionTo(mWifiDisabledState);
                        break;
                    case CMD_AP_STOPPED:
                        Log.d(TAG, "SoftApModeActiveState stopped. Return to WifiDisabledState.");
                        mModeStateMachine.transitionTo(mWifiDisabledState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }
    }  // class ModeStateMachine


    /**
     *  Helper method to report wifi state as on/off (doesn't matter which mode).
     *
     *  @param enabled boolean indicating if wifi is on or off
     */
    private void updateBatteryStatsWifiState(boolean enabled) {
        try {
            if (enabled) {
                mBatteryStats.noteWifiOn();
            } else {
                mBatteryStats.noteWifiOff();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to note battery stats in wifi");
        }
    }

    // callback used to receive callbacks about underlying native failures
    private final class WifiNativeStatusListener implements StatusListener {
        Handler mHandler = new Handler(mLooper);

        @Override
        public void onStatusChanged(boolean isReady) {
            if (!isReady) {
                mHandler.post(() -> {
                    Log.e(TAG, "One of the native daemons died. Triggering recovery");
                    mWifiDiagnostics.captureBugReportData(
                            WifiDiagnostics.REPORT_REASON_WIFINATIVE_FAILURE);

                    // immediately trigger SelfRecovery if we receive a notice about an
                    // underlying daemon failure
                    mWifiInjector.getSelfRecovery().trigger(SelfRecovery.REASON_WIFINATIVE_FAILURE);
                });
            }
        }
    };
}
