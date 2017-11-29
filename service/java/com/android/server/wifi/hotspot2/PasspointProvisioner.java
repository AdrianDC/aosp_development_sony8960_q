/*
 * Copyright 2017 The Android Open Source Project
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

import android.content.Context;
import android.net.Network;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.ProvisioningCallback;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

/**
 * Provides methods to carry out provisioning flow
 */
public class PasspointProvisioner {
    private static final String TAG = "PasspointProvisioner";

    private static final int PROVISIONING_STATUS = 0;
    private static final int PROVISIONING_FAILURE = 1;

    private final Context mContext;
    private final ProvisioningStateMachine mProvisioningStateMachine;
    private final OsuNetworkCallbacks mOsuNetworkCallbacks;
    private final OsuNetworkConnection mOsuNetworkConnection;

    private int mCallingUid;
    private boolean mVerboseLoggingEnabled = false;

    PasspointProvisioner(Context context, OsuNetworkConnection osuNetworkConnection) {
        mContext = context;
        mOsuNetworkConnection = osuNetworkConnection;
        mProvisioningStateMachine = new ProvisioningStateMachine();
        mOsuNetworkCallbacks = new OsuNetworkCallbacks();
    }

    /**
     * Sets up for provisioning
     * @param looper Looper on which the Provisioning state machine will run
     */
    public void init(Looper looper) {
        mProvisioningStateMachine.start(new Handler(looper));
        mOsuNetworkConnection.init(mProvisioningStateMachine.getHandler());
    }

    /**
     * Enable verbose logging to help debug failures
     * @param level integer indicating verbose logging enabled if > 0
     */
    public void enableVerboseLogging(int level) {
        mVerboseLoggingEnabled = (level > 0) ? true : false;
        mOsuNetworkConnection.enableVerboseLogging(level);
    }

    /**
     * Start provisioning flow with a given provider.
     * @param callingUid calling uid.
     * @param provider {@link OsuProvider} to provision with.
     * @param callback {@link IProvisioningCallback} to provide provisioning status.
     * @return boolean value, true if provisioning was started, false otherwise.
     *
     * Implements HS2.0 provisioning flow with a given HS2.0 provider.
     */
    public boolean startSubscriptionProvisioning(int callingUid, OsuProvider provider,
            IProvisioningCallback callback) {
        mCallingUid = callingUid;

        Log.v(TAG, "Provisioning started with " + provider.toString());

        mProvisioningStateMachine.getHandler().post(() -> {
            mProvisioningStateMachine.startProvisioning(provider, callback);
        });

        return true;
    }

    /**
     * Handles the provisioning flow state transitions
     */
    class ProvisioningStateMachine {
        private static final String TAG = "ProvisioningStateMachine";

        private static final int DEFAULT_STATE                             = 0;
        private static final int INITIAL_STATE                             = 1;
        private static final int WAITING_TO_CONNECT                        = 2;
        private static final int OSU_AP_CONNECTED                          = 3;
        private static final int FAILED_STATE                              = 4;

        private OsuProvider mOsuProvider;
        private IProvisioningCallback mProvisioningCallback;
        private Handler mHandler;
        private int mState;

        ProvisioningStateMachine() {
            mState = DEFAULT_STATE;
        }

        /**
         * Initializes and starts the state machine with a handler to handle incoming events
         */
        public void start(Handler handler) {
            mHandler = handler;
            changeState(INITIAL_STATE);
        }

        /**
         * Returns the handler on which a runnable can be posted
         * @return Handler State Machine's handler
         */
        public Handler getHandler() {
            return mHandler;
        }

        /**
         * Start Provisioning with the Osuprovider and invoke callbacks
         * @param provider OsuProvider to provision with
         * @param callback IProvisioningCallback to invoke callbacks on
         */
        public void startProvisioning(OsuProvider provider, IProvisioningCallback callback) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "startProvisioning received in state=" + mState);
            }
            if (mState != INITIAL_STATE) {
                Log.v(TAG, "State Machine needs to be reset before starting provisioning");
                resetStateMachine();
            }
            mProvisioningCallback = callback;
            mOsuProvider = provider;

            // Register for network and wifi state events during provisioning flow
            mOsuNetworkConnection.setEventCallback(mOsuNetworkCallbacks);

            if (mOsuNetworkConnection.connect(mOsuProvider.getOsuSsid(),
                    mOsuProvider.getNetworkAccessIdentifier())) {
                invokeProvisioningCallback(PROVISIONING_STATUS,
                        ProvisioningCallback.OSU_STATUS_AP_CONNECTING);
                changeState(WAITING_TO_CONNECT);
            } else {
                invokeProvisioningCallback(PROVISIONING_FAILURE,
                        ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
                enterFailedState();
            }
        }

        /**
         * Handle Wifi Disable event
         */
        public void handleWifiDisabled() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Wifi Disabled in state=" + mState);
            }
            if (mState == INITIAL_STATE || mState == FAILED_STATE) {
                Log.w(TAG, "Wifi Disable unhandled in state=" + mState);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_FAILURE,
                    ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
            enterFailedState();
        }

        private void resetStateMachine() {
            // Set to null so that no callbacks are invoked during reset
            mProvisioningCallback = null;
            if (mState != INITIAL_STATE || mState != FAILED_STATE) {
                // Transition through Failed state to clean up
                enterFailedState();
            }
            changeState(INITIAL_STATE);
        }

        /**
         * Connected event received
         * @param network Network object for this connection
         */
        public void handleConnectedEvent(Network network) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Connected event received in state=" + mState);
            }
            if (mState != WAITING_TO_CONNECT) {
                // Not waiting for a connection
                Log.w(TAG, "Connection event unhandled in state=" + mState);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_STATUS,
                    ProvisioningCallback.OSU_STATUS_AP_CONNECTED);
            changeState(OSU_AP_CONNECTED);
        }

        /**
         * Disconnect event received
         */
        public void handleDisconnect() {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Connection failed in state=" + mState);
            }
            if (mState == INITIAL_STATE || mState == FAILED_STATE) {
                Log.w(TAG, "Disconnect event unhandled in state=" + mState);
                return;
            }
            invokeProvisioningCallback(PROVISIONING_FAILURE,
                    ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
            enterFailedState();
        }

        private void changeState(int nextState) {
            if (nextState != mState) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Changing state from " + mState + " -> " + nextState);
                }
                mState = nextState;
            }
        }

        private void invokeProvisioningCallback(int callbackType, int status) {
            if (mProvisioningCallback == null) {
                Log.e(TAG, "Provisioning callback " + callbackType + " with status " + status
                        + " not invoked, callback is null");
                return;
            }
            try {
                if (callbackType == PROVISIONING_STATUS) {
                    mProvisioningCallback.onProvisioningStatus(status);
                } else {
                    mProvisioningCallback.onProvisioningFailure(status);
                }
            } catch (RemoteException e) {
                if (callbackType == PROVISIONING_STATUS) {
                    Log.e(TAG, "Remote Exception while posting Provisioning status " + status);
                } else {
                    Log.e(TAG, "Remote Exception while posting Provisioning failure " + status);
                }
            }
        }

        private void enterFailedState() {
            changeState(FAILED_STATE);
            mOsuNetworkConnection.setEventCallback(null);
            mOsuNetworkConnection.disconnectIfNeeded();
        }
    }

    /**
     * Callbacks for network and wifi events
     */
    class OsuNetworkCallbacks implements OsuNetworkConnection.Callbacks {

        OsuNetworkCallbacks() {
        }

        @Override
        public void onConnected(Network network) {
            Log.v(TAG, "onConnected to " + network);
            if (network == null) {
                mProvisioningStateMachine.getHandler().post(() -> {
                    mProvisioningStateMachine.handleDisconnect();
                });
            } else {
                mProvisioningStateMachine.getHandler().post(() -> {
                    mProvisioningStateMachine.handleConnectedEvent(network);
                });
            }
        }

        @Override
        public void onDisconnected() {
            Log.v(TAG, "onDisconnected");
            mProvisioningStateMachine.getHandler().post(() -> {
                mProvisioningStateMachine.handleDisconnect();
            });
        }

        @Override
        public void onTimeOut() {
            Log.v(TAG, "Timed out waiting for connection to OSU AP");
            mProvisioningStateMachine.getHandler().post(() -> {
                mProvisioningStateMachine.handleDisconnect();
            });
        }

        @Override
        public void onWifiEnabled() {
            Log.v(TAG, "onWifiEnabled");
        }

        @Override
        public void onWifiDisabled() {
            Log.v(TAG, "onWifiDisabled");
            mProvisioningStateMachine.getHandler().post(() -> {
                mProvisioningStateMachine.handleWifiDisabled();
            });
        }
    }
}
