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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.support.test.filters.SmallTest;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

/**
 * Unit tests for {@link com.android.server.wifi.ScoringParams}.
 */
@SmallTest
public class ScoringParamsTest {

    ScoringParams mScoringParams;

    int mBad2GHz, mEntry2GHz, mSufficient2GHz, mGood2GHz;
    int mBad5GHz, mEntry5GHz, mSufficient5GHz, mGood5GHz;

    @Mock Context mContext;
    @Spy private MockResources mResources = new MockResources();

    private int setupIntegerResource(int resourceName, int value) {
        doReturn(value).when(mResources).getInteger(resourceName);
        return value;
    }

    /**
     * Sets up resource values for testing
     *
     * See frameworks/base/core/res/res/values/config.xml
     */
    private void setUpResources(Resources resources) {
        mBad2GHz = setupIntegerResource(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_24GHz, -88);
        mEntry2GHz = setupIntegerResource(
                R.integer.config_wifi_framework_wifi_score_entry_rssi_threshold_24GHz, -77);
        mSufficient2GHz = setupIntegerResource(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_24GHz, -66);
        mGood2GHz = setupIntegerResource(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_24GHz, -55);
        mBad5GHz = setupIntegerResource(
                R.integer.config_wifi_framework_wifi_score_bad_rssi_threshold_5GHz, -80);
        mEntry5GHz = setupIntegerResource(
                R.integer.config_wifi_framework_wifi_score_entry_rssi_threshold_5GHz, -70);
        mSufficient5GHz = setupIntegerResource(
                R.integer.config_wifi_framework_wifi_score_low_rssi_threshold_5GHz, -60);
        mGood5GHz = setupIntegerResource(
                R.integer.config_wifi_framework_wifi_score_good_rssi_threshold_5GHz, -50);
    }

    /**
     * Sets up for unit test
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        setUpResources(mResources);
        when(mContext.getResources()).thenReturn(mResources);
        mScoringParams = new ScoringParams();
    }

    /**
     * Check that thresholds are properly ordered, and in range.
     */
    private void checkThresholds(int frequency) {
        assertTrue(-127 <= mScoringParams.getExitRssi(frequency));
        assertTrue(mScoringParams.getExitRssi(frequency)
                <= mScoringParams.getEntryRssi(frequency));
        assertTrue(mScoringParams.getEntryRssi(frequency)
                <= mScoringParams.getSufficientRssi(frequency));
        assertTrue(mScoringParams.getSufficientRssi(frequency)
                <= mScoringParams.getGoodRssi(frequency));
        assertTrue(mScoringParams.getGoodRssi(frequency) < 0);
    }

    /**
     * Test basic constuctor
     */
    @Test
    public void testBasicConstructor() {
        mScoringParams = new ScoringParams();
        checkThresholds(2412);
        checkThresholds(5020);
    }

    /**
     * Check that we get the config.xml values, if that's what we want
     */
    @Test
    public void testContextConstructor() {
        mScoringParams = new ScoringParams(mContext);

        assertEquals(mBad2GHz, mScoringParams.getExitRssi(2412));
        assertEquals(mEntry2GHz, mScoringParams.getEntryRssi(2480));
        assertEquals(mSufficient2GHz, mScoringParams.getSufficientRssi(2400));
        assertEquals(mGood2GHz, mScoringParams.getGoodRssi(2499));
        assertEquals(mGood2GHz, mScoringParams.getGoodRssi(ScoringParams.BAND2));

        assertEquals(mBad5GHz, mScoringParams.getExitRssi(5000));
        assertEquals(mEntry5GHz, mScoringParams.getEntryRssi(5010));
        assertEquals(mSufficient5GHz, mScoringParams.getSufficientRssi(5100));
        assertEquals(mGood5GHz, mScoringParams.getGoodRssi(5678));
        assertEquals(mGood5GHz, mScoringParams.getGoodRssi(ScoringParams.BAND5));
    }
}
