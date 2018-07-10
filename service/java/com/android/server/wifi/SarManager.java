/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.telephony.TelephonyManager.CALL_STATE_IDLE;
import static android.telephony.TelephonyManager.CALL_STATE_OFFHOOK;
import static android.telephony.TelephonyManager.CALL_STATE_RINGING;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.os.Looper;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * This class provides the Support for SAR to control WiFi TX power limits.
 * It deals with the following:
 * - Tracking the STA state through calls from  the ClientModeManager.
 * - Tracking the SAP state through calls from SoftApManager
 * - Tracking the Scan-Only state through ScanOnlyModeManager
 * - Tracking the state of the Cellular calls or data.
 * - Tracking the sensor indicating proximity to user head/hand/body.
 * - It constructs the sar info and send it towards the HAL
 */
public class SarManager {
    /* For Logging */
    private static final String TAG = "WifiSarManager";
    private boolean mVerboseLoggingEnabled = true;

    private SarInfo mSarInfo;

    /* Configuration for SAR support */
    private boolean mSupportSarTxPowerLimit;
    private boolean mSupportSarVoiceCall;
    private boolean mSupportSarSoftAp;
    private boolean mSupportSarSensor;
    /* Sensor event definitions */
    private int mSarSensorEventFreeSpace;
    private int mSarSensorEventNearBody;
    private int mSarSensorEventNearHand;
    private int mSarSensorEventNearHead;

    /**
     * Other parameters passed in or created in the constructor.
     */
    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final WifiPhoneStateListener mPhoneStateListener;
    private final WifiNative mWifiNative;
    private final SarSensorEventListener mSensorListener;
    private final SensorManager mSensorManager;
    private final Looper mLooper;

    /**
     * Create new instance of SarManager.
     */
    SarManager(Context context,
               TelephonyManager telephonyManager,
               Looper looper,
               WifiNative wifiNative,
               SensorManager sensorManager) {
        mContext = context;
        mTelephonyManager = telephonyManager;
        mWifiNative = wifiNative;
        mLooper = looper;
        mSensorManager = sensorManager;
        mPhoneStateListener = new WifiPhoneStateListener(looper);
        mSensorListener = new SarSensorEventListener();

        readSarConfigs();
        if (mSupportSarTxPowerLimit) {
            mSarInfo = new SarInfo();
            setSarConfigsInInfo();
            registerListeners();
        }
    }

    private void readSarConfigs() {
        mSupportSarTxPowerLimit = mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_sar_tx_power_limit);
        /* In case SAR is disabled,
           then all SAR inputs are automatically disabled as well (irrespective of the config) */
        if (!mSupportSarTxPowerLimit) {
            mSupportSarVoiceCall = false;
            mSupportSarSoftAp = false;
            mSupportSarSensor = false;
            return;
        }

        /* Voice calls are supported when SAR is supported */
        mSupportSarVoiceCall = true;

        mSupportSarSoftAp = mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_soft_ap_sar_tx_power_limit);

        mSupportSarSensor = mContext.getResources().getBoolean(
                R.bool.config_wifi_framework_enable_body_proximity_sar_tx_power_limit);

        /* Read the sar sensor event Ids */
        if (mSupportSarSensor) {
            mSarSensorEventFreeSpace = mContext.getResources().getInteger(
                    R.integer.config_wifi_framework_sar_free_space_event_id);
            mSarSensorEventNearBody = mContext.getResources().getInteger(
                    R.integer.config_wifi_framework_sar_near_body_event_id);
            mSarSensorEventNearHand = mContext.getResources().getInteger(
                    R.integer.config_wifi_framework_sar_near_hand_event_id);
            mSarSensorEventNearHead = mContext.getResources().getInteger(
                    R.integer.config_wifi_framework_sar_near_head_event_id);
        }
    }

    private void setSarConfigsInInfo() {
        mSarInfo.sarVoiceCallSupported = mSupportSarVoiceCall;
        mSarInfo.sarSapSupported = mSupportSarSoftAp;
        mSarInfo.sarSensorSupported = mSupportSarSensor;
    }

    private void registerListeners() {
        if (mSupportSarVoiceCall) {
            /* Listen for Phone State changes */
            registerPhoneStateListener();
        }

        /* Only listen for SAR sensor if supported */
        if (mSupportSarSensor) {
            /* Register the SAR sensor listener.
             * If this fails, we will assume worst case (near head) */
            if (!registerSensorListener()) {
                Log.e(TAG, "Failed to register sensor listener, setting Sensor to NearHead");
                /*TODO Need to add a metric to determine how often this happens */
                mSarInfo.sensorState = SarInfo.SAR_SENSOR_NEAR_HEAD;
            }
        }
    }

    /**
     * Register the phone state listener.
     */
    private void registerPhoneStateListener() {
        Log.i(TAG, "Registering for telephony call state changes");
        mTelephonyManager.listen(
                mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
    }

    /**
     * Register the body/hand/head proximity sensor.
     */
    private boolean registerSensorListener() {
        Log.i(TAG, "Registering for Sensor notification Listener");
        return mSensorListener.register();
    }

    /**
     * Update Wifi Client State
     */
    public void setClientWifiState(int state) {
        boolean newIsEnabled;
        /* No action is taken if SAR is not supported */
        if (!mSupportSarTxPowerLimit) {
            return;
        }

        if (state == WifiManager.WIFI_STATE_DISABLED) {
            newIsEnabled = false;
        } else if (state == WifiManager.WIFI_STATE_ENABLED) {
            newIsEnabled = true;
        } else {
            /* No change so exiting with no action */
            return;
        }

        /* Report change to HAL if needed */
        if (mSarInfo.isWifiClientEnabled != newIsEnabled) {
            mSarInfo.isWifiClientEnabled = newIsEnabled;
            updateSarScenario();
        }
    }

    /**
     * Update Wifi SoftAP State
     */
    public void setSapWifiState(int state) {
        boolean newIsEnabled;
        /* No action is taken if SAR is not supported */
        if (!mSupportSarTxPowerLimit) {
            return;
        }

        if (state == WifiManager.WIFI_AP_STATE_DISABLED) {
            newIsEnabled = false;
        } else if (state == WifiManager.WIFI_AP_STATE_ENABLED) {
            newIsEnabled = true;
        } else {
            /* No change so exiting with no action */
            return;
        }

        /* Report change to HAL if needed */
        if (mSarInfo.isWifiSapEnabled != newIsEnabled) {
            mSarInfo.isWifiSapEnabled = newIsEnabled;
            updateSarScenario();
        }
    }

    /**
     * Update Wifi ScanOnly State
     */
    public void setScanOnlyWifiState(int state) {
        boolean newIsEnabled;
        /* No action is taken if SAR is not supported */
        if (!mSupportSarTxPowerLimit) {
            return;
        }

        if (state == WifiManager.WIFI_STATE_DISABLED) {
            newIsEnabled = false;
        } else if (state == WifiManager.WIFI_STATE_ENABLED) {
            newIsEnabled = true;
        } else {
            /* No change so exiting with no action */
            return;
        }

        /* Report change to HAL if needed */
        if (mSarInfo.isWifiScanOnlyEnabled != newIsEnabled) {
            mSarInfo.isWifiScanOnlyEnabled = newIsEnabled;
            updateSarScenario();
        }
    }

    /**
     * Report Cell state event
     */
    private void onCellStateChangeEvent(int state) {
        boolean newIsVoiceCall;
        switch (state) {
            case CALL_STATE_OFFHOOK:
            case CALL_STATE_RINGING:
                newIsVoiceCall = true;
                break;

            case CALL_STATE_IDLE:
                newIsVoiceCall = false;
                break;

            default:
                Log.e(TAG, "Invalid Cell State: " + state);
                return;
        }

        /* Report change to HAL if needed */
        if (mSarInfo.isVoiceCall != newIsVoiceCall) {
            mSarInfo.isVoiceCall = newIsVoiceCall;
            updateSarScenario();
        }
    }

    /**
     * Report an event from the SAR sensor
     */
    private void onSarSensorEvent(int sarSensorEvent) {
        int newSensorState;
        if (sarSensorEvent == mSarSensorEventFreeSpace) {
            newSensorState = SarInfo.SAR_SENSOR_FREE_SPACE;
        } else if (sarSensorEvent == mSarSensorEventNearBody) {
            newSensorState = SarInfo.SAR_SENSOR_NEAR_BODY;
        } else if (sarSensorEvent == mSarSensorEventNearHand) {
            newSensorState = SarInfo.SAR_SENSOR_NEAR_HAND;
        } else if (sarSensorEvent == mSarSensorEventNearHead) {
            newSensorState = SarInfo.SAR_SENSOR_NEAR_HEAD;
        } else {
            Log.e(TAG, "Invalid SAR sensor event id: " + sarSensorEvent);
            return;
        }

        /* Report change to HAL if needed */
        if (mSarInfo.sensorState != newSensorState) {
            Log.d(TAG, "Setting Sensor state to " + SarInfo.sensorStateToString(newSensorState));
            mSarInfo.sensorState = newSensorState;
            updateSarScenario();
        }
    }

    /**
     * Enable/disable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            mVerboseLoggingEnabled = true;
        } else {
            mVerboseLoggingEnabled = false;
        }
    }

    /**
     * dump()
     * Dumps SarManager state (as well as its SarInfo member variable state)
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of SarManager");
        pw.println("isSarSupported: " + mSupportSarTxPowerLimit);
        pw.println("isSarVoiceCallSupported: " + mSupportSarVoiceCall);
        pw.println("isSarSoftApSupported: " + mSupportSarSoftAp);
        pw.println("isSarSensorSupported: " + mSupportSarSensor);
        pw.println("");
        if (mSarInfo != null) {
            mSarInfo.dump(fd, pw, args);
        }
    }

    /**
     * Listen for phone call state events to set/reset TX power limits for SAR requirements.
     */
    private class WifiPhoneStateListener extends PhoneStateListener {
        WifiPhoneStateListener(Looper looper) {
            super(looper);
        }

        /**
         * onCallStateChanged()
         * This callback is called when a SAR sensor event is received
         * Note that this runs in the WifiStateMachineHandlerThread
         * since the corresponding Looper was passed to the WifiPhoneStateListener constructor.
         */
        @Override
        public void onCallStateChanged(int state, String incomingNumber) {
            Log.d(TAG, "Received Phone State Change: " + state);

            /* In case of an unsolicited event */
            if (!mSupportSarTxPowerLimit || !mSupportSarVoiceCall) {
                return;
            }
            onCellStateChangeEvent(state);
        }
    }

    private class SarSensorEventListener implements SensorEventListener {

        private Sensor mSensor;

        /**
         * Register the SAR listener to get SAR sensor events
         */
        private boolean register() {
            /* Get the sensor type from configuration */
            String sensorType = mContext.getResources().getString(
                    R.string.config_wifi_sar_sensor_type);
            if (TextUtils.isEmpty(sensorType)) {
                Log.e(TAG, "Empty SAR sensor type");
                return false;
            }

            /* Get the sensor object */
            Sensor sensor = null;
            List<Sensor> sensorList = mSensorManager.getSensorList(Sensor.TYPE_ALL);
            for (Sensor s : sensorList) {
                if (sensorType.equals(s.getStringType())) {
                    sensor = s;
                    break;
                }
            }
            if (sensor == null) {
                Log.e(TAG, "Failed to Find the SAR Sensor");
                return false;
            }

            /* Now register the listener */
            if (!mSensorManager.registerListener(this, sensor,
                    SensorManager.SENSOR_DELAY_NORMAL)) {
                Log.e(TAG, "Failed to register SAR Sensor Listener");
                return false;
            }

            return true;
        }

        /**
         * onSensorChanged()
         * This callback is called when a SAR sensor event is received
         * Note that this runs in the WifiStateMachineHandlerThread
         * since, the corresponding Looper was passed to the SensorManager instance.
         */
        @Override
        public void onSensorChanged(SensorEvent event) {
            onSarSensorEvent((int) event.values[0]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    }

    /**
     * updateSarScenario()
     * Update HAL with the new SAR scenario if needed.
     */
    private void updateSarScenario() {
        if (!mSarInfo.shouldReport()) {
            return;
        }

        /* Report info to HAL*/
        if (mWifiNative.selectTxPowerScenario(mSarInfo)) {
            mSarInfo.reportingSuccessful();
        } else {
            Log.e(TAG, "Failed in WifiNative.selectTxPowerScenario()");
        }

        return;
    }
}
