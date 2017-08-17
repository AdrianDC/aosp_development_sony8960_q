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

import android.annotation.NonNull;
import android.net.wifi.ScanResult;

import java.util.List;

/**
 * Helps recommend the best available network for {@link OpenNetworkNotifier}.
 * @hide
 */
public class OpenNetworkRecommender {

    /**
     * Recommends the network with the best signal strength.
     *
     * @param networks List of scan details to pick a recommendation. This list should not be null
     *                 or empty.
     * @param currentRecommendation The currently recommended network.
     */
    public ScanResult recommendNetwork(
            @NonNull List<ScanDetail> networks, ScanResult currentRecommendation) {
        ScanResult currentUpdatedRecommendation = null;
        ScanResult result = null;
        int highestRssi = Integer.MIN_VALUE;
        for (ScanDetail scanDetail : networks) {
            ScanResult scanResult = scanDetail.getScanResult();

            if (currentRecommendation != null
                    && currentRecommendation.SSID.equals(scanResult.SSID)) {
                currentUpdatedRecommendation = scanResult;
            }

            if (scanResult.level > highestRssi) {
                result = scanResult;
                highestRssi = scanResult.level;
            }
        }
        if (currentUpdatedRecommendation != null
                && currentUpdatedRecommendation.level >= result.level) {
            return currentUpdatedRecommendation;
        } else {
            return result;
        }
    }
}
