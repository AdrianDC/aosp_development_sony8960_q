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

import android.annotation.NonNull;
import android.content.Context;
import android.database.ContentObserver;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Log;

import com.android.internal.R;

/**
 * Holds parameters used for scoring networks.
 *
 * Doing this in one place means that there's a better chance of consistency between
 * connected score and network selection.
 *
 */
public class ScoringParams {
    private static final String TAG = "WifiScoringParams";
    private static final int EXIT = 0;
    private static final int ENTRY = 1;
    private static final int SUFFICIENT = 2;
    private static final int GOOD = 3;

    /**
     * Parameter values are stored in a separate container so that a new collection of values can
     * be checked for consistency before activating them.
     */
    private class Values {
        /** RSSI thresholds for 2.4 GHz band (dBm) */
        public static final String KEY_RSSI2 = "rssi2";
        public final int[] rssi2 = {-83, -80, -73, -60};

        /** RSSI thresholds for 5 GHz band (dBm) */
        public static final String KEY_RSSI5 = "rssi5";
        public final int[] rssi5 = {-80, -77, -70, -57};

        /** Number of seconds for RSSI forecast */
        public static final String KEY_HORIZON = "horizon";
        public static final int MIN_HORIZON = -9;
        public static final int MAX_HORIZON = 60;
        public int horizon = 15;

        Values() {
        }

        Values(Values source) {
            for (int i = 0; i < rssi2.length; i++) {
                rssi2[i] = source.rssi2[i];
            }
            for (int i = 0; i < rssi5.length; i++) {
                rssi5[i] = source.rssi5[i];
            }
            horizon = source.horizon;
        }

        public void validate() throws IllegalArgumentException {
            validateRssiArray(rssi2);
            validateRssiArray(rssi5);
            validateRange(horizon, MIN_HORIZON, MAX_HORIZON);
        }

        private void validateRssiArray(int[] rssi) throws IllegalArgumentException {
            int low = WifiInfo.MIN_RSSI;
            int high = Math.min(WifiInfo.MAX_RSSI, -1); // Stricter than Wifiinfo
            for (int i = 0; i < rssi.length; i++) {
                validateRange(rssi[i], low, high);
                low = rssi[i];
            }
        }

        private void validateRange(int k, int low, int high) throws IllegalArgumentException {
            if (k < low || k > high) {
                throw new IllegalArgumentException();
            }
        }

        public void parseString(String kvList) throws IllegalArgumentException {
            KeyValueListParser parser = new KeyValueListParser(',');
            parser.setString(kvList);
            if (parser.size() != ("" + kvList).split(",").length) {
                throw new IllegalArgumentException("dup keys");
            }
            updateIntArray(rssi2, parser, KEY_RSSI2);
            updateIntArray(rssi5, parser, KEY_RSSI5);
            horizon = updateInt(parser, KEY_HORIZON, horizon);
        }

        private int updateInt(KeyValueListParser parser, String key, int defaultValue)
                throws IllegalArgumentException {
            String value = parser.getString(key, null);
            if (value == null) return defaultValue;
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException();
            }
        }

        private void updateIntArray(final int[] dest, KeyValueListParser parser, String key)
                throws IllegalArgumentException {
            if (parser.getString(key, null) == null) return;
            int[] ints = parser.getIntArray(key, null);
            if (ints == null) throw new IllegalArgumentException();
            if (ints.length != dest.length) throw new IllegalArgumentException();
            for (int i = 0; i < dest.length; i++) {
                dest[i] = ints[i];
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            appendKey(sb, KEY_RSSI2);
            appendInts(sb, rssi2);
            appendKey(sb, KEY_RSSI5);
            appendInts(sb, rssi5);
            appendKey(sb, KEY_HORIZON);
            sb.append(horizon);
            return sb.toString();
        }

        private void appendKey(StringBuilder sb, String key) {
            if (sb.length() != 0) sb.append(",");
            sb.append(key).append("=");
        }

        private void appendInts(StringBuilder sb, final int[] a) {
            final int n = a.length;
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(":");
                sb.append(a[i]);
            }
        }
    }

    @NonNull private Values mVal = new Values();

    public ScoringParams() {
    }

    public ScoringParams(Context context) {
        loadResources(context);
    }

    public ScoringParams(Context context, FrameworkFacade facade, Handler handler) {
        loadResources(context);
        setupContentObserver(context, facade, handler);
    }

    private void loadResources(Context context) {
        mVal.rssi2[EXIT] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz);
        mVal.rssi2[ENTRY] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_entry_rssi_threshold_24GHz);
        mVal.rssi2[SUFFICIENT] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz);
        mVal.rssi2[GOOD] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz);
        mVal.rssi5[EXIT] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz);
        mVal.rssi5[ENTRY] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_entry_rssi_threshold_5GHz);
        mVal.rssi5[SUFFICIENT] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz);
        mVal.rssi5[GOOD] = context.getResources().getInteger(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz);
        try {
            mVal.validate();
        } catch (IllegalArgumentException e) {
            Log.wtf(TAG, "Inconsistent config_wifi_framework_ resources: " + this, e);
        }
    }

    private void setupContentObserver(Context context, FrameworkFacade facade, Handler handler) {
        final ScoringParams self = this;
        String defaults = self.toString();
        ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                String params = facade.getStringSetting(
                        context, Settings.Global.WIFI_SCORE_PARAMS);
                self.update(defaults);
                if (!self.update(params)) {
                    Log.e(TAG, "Error in " + Settings.Global.WIFI_SCORE_PARAMS + ": "
                            + sanitize(params));
                }
                Log.i(TAG, self.toString());
            }
        };
        facade.registerContentObserver(context,
                Settings.Global.getUriFor(Settings.Global.WIFI_SCORE_PARAMS),
                true,
                observer);
        observer.onChange(false);
    }

    private static final String COMMA_KEY_VAL_STAR = "^(,[A-Za-z_][A-Za-z0-9_]*=[0-9.:+-]+)*$";

    /**
     * Updates the parameters from the given parameter string.
     * If any errors are detected, no change is made.
     * @param kvList is a comma-separated key=value list.
     * @return true for success
     */
    public boolean update(String kvList) {
        if (kvList == null || "".equals(kvList)) {
            return true;
        }
        if (!("," + kvList).matches(COMMA_KEY_VAL_STAR)) {
            return false;
        }
        Values v = new Values(mVal);
        try {
            v.parseString(kvList);
            v.validate();
            mVal = v;
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Sanitize a string to make it safe for printing.
     * @param params is the untrusted string
     * @return string with questionable characters replaced with question marks
     */
    public String sanitize(String params) {
        if (params == null) return "";
        String printable = params.replaceAll("[^A-Za-z_0-9=,:.+-]", "?");
        if (printable.length() > 100) {
            printable = printable.substring(0, 98) + "...";
        }
        return printable;
    }

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

    /**
     * Returns the number of seconds to use for rssi forecast.
     */
    public int getHorizonSeconds() {
        return mVal.horizon;
    }

    private static final int MINIMUM_5GHZ_BAND_FREQUENCY_IN_MEGAHERTZ = 5000;

    private int[] getRssiArray(int frequency) {
        if (frequency < MINIMUM_5GHZ_BAND_FREQUENCY_IN_MEGAHERTZ) {
            return mVal.rssi2;
        } else {
            return mVal.rssi5;
        }
    }

    @Override
    public String toString() {
        return mVal.toString();
    }
}
