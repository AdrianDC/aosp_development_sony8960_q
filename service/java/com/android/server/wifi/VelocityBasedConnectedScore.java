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

import android.content.Context;
import android.net.wifi.WifiInfo;

import com.android.internal.R;
import com.android.server.wifi.util.KalmanFilter;
import com.android.server.wifi.util.Matrix;

/**
 * Class used to calculate scores for connected wifi networks and report it to the associated
 * network agent.
 */
public class VelocityBasedConnectedScore extends ConnectedScore {

    // Device configs. The values are examples.
    private final int mThresholdMinimumRssi5;      // -82
    private final int mThresholdMinimumRssi24;     // -85

    private int mFrequency = 5000;
    private int mRssi = 0;
    private final KalmanFilter mFilter;
    private long mLastMillis;

    public VelocityBasedConnectedScore(Context context, Clock clock) {
        super(clock);
        mThresholdMinimumRssi5 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz);
        mThresholdMinimumRssi24 = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz);
        mFilter = new KalmanFilter();
        mFilter.mH = new Matrix(2, new double[]{1.0, 0.0});
        mFilter.mR = new Matrix(1, new double[]{1.0});
    }

    /**
     * Set the Kalman filter's state transition matrix F and process noise covariance Q given
     * a time step.
     *
     * @param dt delta time, in seconds
     */
    private void setDeltaTimeSeconds(double dt) {
        mFilter.mF = new Matrix(2, new double[]{1.0, dt, 0.0, 1.0});
        Matrix tG = new Matrix(1, new double[]{0.5 * dt * dt, dt});
        double stda = 0.02; // standard deviation of modelled acceleration
        mFilter.mQ = tG.dotTranspose(tG).dot(new Matrix(2, new double[]{
                stda * stda, 0.0,
                0.0, stda * stda}));
    }
    /**
     * Reset the filter state.
     */
    @Override
    public void reset() {
        mLastMillis = 0;
    }

    /**
     * Updates scoring state using RSSI and measurement noise estimate
     * <p>
     * This is useful if an RSSI comes from another source (e.g. scan results) and the
     * expected noise varies by source.
     *
     * @param rssi              signal strength (dB).
     * @param millis            millisecond-resolution time.
     * @param standardDeviation of the RSSI.
     */
    @Override
    public void updateUsingRssi(int rssi, long millis, double standardDeviation) {
        if (millis <= 0) return;
        if (mLastMillis <= 0 || millis < mLastMillis) {
            double initialVariance = 9.0 * standardDeviation * standardDeviation;
            mFilter.mx = new Matrix(1, new double[]{rssi, 0.0});
            mFilter.mP = new Matrix(2, new double[]{initialVariance, 0.0, 0.0, 0.0});
            mLastMillis = millis;
            return;
        }
        double dt = (millis - mLastMillis) * 0.001;
        mFilter.mR.put(0, 0, standardDeviation * standardDeviation);
        setDeltaTimeSeconds(dt);
        mFilter.predict();
        mLastMillis = millis;
        mFilter.update(new Matrix(1, new double[]{rssi}));
    }

    /**
     * Updates the state.
     */
    @Override
    public void updateUsingWifiInfo(WifiInfo wifiInfo, long millis) {
        int frequency = wifiInfo.getFrequency();
        if (frequency != mFrequency) {
            reset(); // Probably roamed
            mFrequency = frequency;
        }
        updateUsingRssi(wifiInfo.getRssi(), millis, mDefaultRssiStandardDeviation);
    }

    /**
     * Velocity scorer - predict the rssi a few seconds from now
     */
    @Override
    public int generateScore() {
        int badRssi = mFrequency >= 5000 ? mThresholdMinimumRssi5 : mThresholdMinimumRssi24;
        double horizonSeconds = 15.0;
        Matrix x = new Matrix(mFilter.mx);
        double filteredRssi = x.get(0, 0);
        setDeltaTimeSeconds(horizonSeconds);
        x = mFilter.mF.dot(x);
        double forecastRssi = x.get(0, 0);
        if (forecastRssi > filteredRssi) {
            forecastRssi = filteredRssi; // Be pessimistic about predicting an actual increase
        }
        int score = (int) (Math.round(forecastRssi) - badRssi) + WIFI_TRANSITION_SCORE;
        return score;
    }
}
