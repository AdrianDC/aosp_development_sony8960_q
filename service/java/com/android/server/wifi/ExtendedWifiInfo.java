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

package com.android.server.wifi;

import android.net.wifi.WifiInfo;

/**
 * Extends WifiInfo with the methods for computing the averaged packet rates
 */
public class ExtendedWifiInfo extends WifiInfo {
    private static final long RESET_TIME_STAMP = Long.MIN_VALUE;
    private static final double FILTER_TIME_CONSTANT = 3000.0;

    private long mLastPacketCountUpdateTimeStamp = RESET_TIME_STAMP;
    private boolean mEnableConnectedMacRandomization = false;

    @Override
    public void reset() {
        super.reset();
        mLastPacketCountUpdateTimeStamp = RESET_TIME_STAMP;
        if (mEnableConnectedMacRandomization) {
            setMacAddress(DEFAULT_MAC_ADDRESS);
        }
    }

    /**
     * Updates the packet rates using link layer stats
     *
     * @param stats WifiLinkLayerStats
     * @param timeStamp time in milliseconds
     */
    public void updatePacketRates(WifiLinkLayerStats stats, long timeStamp) {
        if (stats != null) {
            long txgood = stats.txmpdu_be + stats.txmpdu_bk + stats.txmpdu_vi + stats.txmpdu_vo;
            long txretries = stats.retries_be + stats.retries_bk
                    + stats.retries_vi + stats.retries_vo;
            long rxgood = stats.rxmpdu_be + stats.rxmpdu_bk + stats.rxmpdu_vi + stats.rxmpdu_vo;
            long txbad = stats.lostmpdu_be + stats.lostmpdu_bk
                    + stats.lostmpdu_vi + stats.lostmpdu_vo;

            if (mLastPacketCountUpdateTimeStamp != RESET_TIME_STAMP
                    && mLastPacketCountUpdateTimeStamp < timeStamp
                    && txBad <= txbad
                    && txSuccess <= txgood
                    && rxSuccess <= rxgood
                    && txRetries <= txretries) {
                long timeDelta = timeStamp - mLastPacketCountUpdateTimeStamp;
                double lastSampleWeight = Math.exp(-1.0 * timeDelta / FILTER_TIME_CONSTANT);
                double currentSampleWeight = 1.0 - lastSampleWeight;

                txBadRate = txBadRate * lastSampleWeight
                        + (txbad - txBad) * 1000.0 / timeDelta
                        * currentSampleWeight;
                txSuccessRate = txSuccessRate * lastSampleWeight
                        + (txgood - txSuccess) * 1000.0 / timeDelta
                        * currentSampleWeight;
                rxSuccessRate = rxSuccessRate * lastSampleWeight
                        + (rxgood - rxSuccess) * 1000.0 / timeDelta
                        * currentSampleWeight;
                txRetriesRate = txRetriesRate * lastSampleWeight
                        + (txretries - txRetries) * 1000.0 / timeDelta
                        * currentSampleWeight;
            } else {
                txBadRate = 0;
                txSuccessRate = 0;
                rxSuccessRate = 0;
                txRetriesRate = 0;
            }
            txBad = txbad;
            txSuccess = txgood;
            rxSuccess = rxgood;
            txRetries = txretries;
            mLastPacketCountUpdateTimeStamp = timeStamp;
        } else {
            txBad = 0;
            txSuccess = 0;
            rxSuccess = 0;
            txRetries = 0;
            txBadRate = 0;
            txSuccessRate = 0;
            rxSuccessRate = 0;
            txRetriesRate = 0;
            mLastPacketCountUpdateTimeStamp = RESET_TIME_STAMP;
        }
    }

    /**
     * This function is less powerful and used if the WifiLinkLayerStats API is not implemented
     * at the Wifi HAL
     *
     * @hide
     */
    public void updatePacketRates(long txPackets, long rxPackets) {
        //paranoia
        txBad = 0;
        txRetries = 0;
        txBadRate = 0;
        txRetriesRate = 0;
        if (txSuccess <= txPackets && rxSuccess <= rxPackets) {
            txSuccessRate = (txSuccessRate * 0.5)
                    + ((double) (txPackets - txSuccess) * 0.5);
            rxSuccessRate = (rxSuccessRate * 0.5)
                    + ((double) (rxPackets - rxSuccess) * 0.5);
        } else {
            txBadRate = 0;
            txRetriesRate = 0;
        }
        txSuccess = txPackets;
        rxSuccess = rxPackets;
    }

    /**
     * Updates whether Connected MAC Randomization is enabled.
     *
     * @hide
     */
    public void setEnableConnectedMacRandomization(boolean enableConnectedMacRandomization) {
        mEnableConnectedMacRandomization = enableConnectedMacRandomization;
    }

}
