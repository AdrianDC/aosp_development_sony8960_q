/*
 * Copyright 2018 The Android Open Source Project
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

import com.android.internal.R;

/**
 * Holds parameters used for scoring networks.
 *
 * Doing this in one place means that there's a better chance of consistency between
 * connected score and network selection.
 *
 */
public class ScoringParams {
    private static final int EXIT = 0;
    private static final int ENTRY = 1;
    private static final int SUFFICIENT = 2;
    private static final int GOOD = 3;
    private final int[] mRssi2 = {-83, -80, -73, -60};
    private final int[] mRssi5 = {-80, -77, -70, -57};

    public ScoringParams() {
    }

    public ScoringParams(Context context) {
        mRssi2[EXIT] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz);
        mRssi2[ENTRY] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_entry_rssi_threshold_24GHz);
        mRssi2[SUFFICIENT] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        mRssi2[GOOD] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz);
        mRssi5[EXIT] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz);
        mRssi5[ENTRY] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_entry_rssi_threshold_5GHz);
        mRssi5[SUFFICIENT] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
        mRssi5[GOOD] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz);
    }

    private static final int MINIMUM_5GHZ_BAND_FREQUENCY_IN_MEGAHERTZ = 5000;

    /** Constant to denote someplace in the 2.4 GHz band */
    public static final int BAND2 = 2400;

    /** Constant to denote someplace in the 5 GHz band */
    public static final int BAND5 = 5000;

    /**
     * Returns the RSSI value at which the connection is deemed to be unusable,
     * in the absence of other indications.
     */
    public int getExitRssi(int frequencyMegaHertz) {
        return getRssiArray(frequencyMegaHertz)[EXIT];
    }

    /**
     * Returns the minimum scan RSSI for making a connection attempt.
     */
    public int getEntryRssi(int frequencyMegaHertz) {
        return getRssiArray(frequencyMegaHertz)[ENTRY];
    }

    /**
     * Returns a connected RSSI value that indicates the connection is
     * good enough that we needn't scan for alternatives.
     */
    public int getSufficientRssi(int frequencyMegaHertz) {
        return getRssiArray(frequencyMegaHertz)[SUFFICIENT];
    }

    /**
     * Returns a connected RSSI value that indicates a good connection.
     */
    public int getGoodRssi(int frequencyMegaHertz) {
        return getRssiArray(frequencyMegaHertz)[GOOD];
    }

    private int[] getRssiArray(int frequencyMegaHertz) {
        if (frequencyMegaHertz < MINIMUM_5GHZ_BAND_FREQUENCY_IN_MEGAHERTZ) {
            return mRssi2;
        } else {
            return mRssi5;
        }
    }
}
