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

package com.android.server.wifi.nan;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;

import android.net.wifi.nan.ConfigRequest;
import android.net.wifi.nan.PublishConfig;
import android.net.wifi.nan.SubscribeConfig;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.junit.rules.ExpectedException;

/**
 * Unit test harness for WifiNanManager class.
 */
@SmallTest
public class WifiNanManagerTest {
    @Rule
    public ErrorCollector collector = new ErrorCollector();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    /*
     * ConfigRequest Tests
     */

    @Test
    public void testConfigRequestBuilder() {
        final int clusterHigh = 100;
        final int clusterLow = 5;
        final int masterPreference = 55;
        final boolean supportBand5g = true;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterHigh(clusterHigh)
                .setClusterLow(clusterLow).setMasterPreference(masterPreference)
                .setSupport5gBand(supportBand5g).build();

        collector.checkThat("mClusterHigh", clusterHigh, equalTo(configRequest.mClusterHigh));
        collector.checkThat("mClusterLow", clusterLow, equalTo(configRequest.mClusterLow));
        collector.checkThat("mMasterPreference", masterPreference,
                equalTo(configRequest.mMasterPreference));
        collector.checkThat("mSupport5gBand", supportBand5g, equalTo(configRequest.mSupport5gBand));
    }

    @Test
    public void testConfigRequestBuilderMasterPrefNegative() {
        thrown.expect(IllegalArgumentException.class);
        ConfigRequest.Builder builder = new ConfigRequest.Builder();
        builder.setMasterPreference(-1);
    }

    @Test
    public void testConfigRequestBuilderMasterPrefReserved1() {
        thrown.expect(IllegalArgumentException.class);
        new ConfigRequest.Builder().setMasterPreference(1);
    }

    @Test
    public void testConfigRequestBuilderMasterPrefReserved255() {
        thrown.expect(IllegalArgumentException.class);
        new ConfigRequest.Builder().setMasterPreference(255);
    }

    @Test
    public void testConfigRequestBuilderMasterPrefTooLarge() {
        thrown.expect(IllegalArgumentException.class);
        new ConfigRequest.Builder().setMasterPreference(256);
    }

    @Test
    public void testConfigRequestBuilderClusterLowNegative() {
        thrown.expect(IllegalArgumentException.class);
        new ConfigRequest.Builder().setClusterLow(-1);
    }

    @Test
    public void testConfigRequestBuilderClusterHighNegative() {
        thrown.expect(IllegalArgumentException.class);
        new ConfigRequest.Builder().setClusterHigh(-1);
    }

    @Test
    public void testConfigRequestBuilderClusterLowAboveMax() {
        thrown.expect(IllegalArgumentException.class);
        new ConfigRequest.Builder().setClusterLow(ConfigRequest.CLUSTER_ID_MAX + 1);
    }

    @Test
    public void testConfigRequestBuilderClusterHighAboveMax() {
        thrown.expect(IllegalArgumentException.class);
        new ConfigRequest.Builder().setClusterHigh(ConfigRequest.CLUSTER_ID_MAX + 1);
    }

    @Test
    public void testConfigRequestBuilderClusterLowLargerThanHigh() {
        thrown.expect(IllegalArgumentException.class);
        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterLow(100)
                .setClusterHigh(5).build();
    }

    @Test
    public void testConfigRequestParcel() {
        final int clusterHigh = 189;
        final int clusterLow = 25;
        final int masterPreference = 177;
        final boolean supportBand5g = true;

        ConfigRequest configRequest = new ConfigRequest.Builder().setClusterHigh(clusterHigh)
                .setClusterLow(clusterLow).setMasterPreference(masterPreference)
                .setSupport5gBand(supportBand5g).build();

        Parcel parcelW = Parcel.obtain();
        configRequest.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        ConfigRequest rereadConfigRequest = ConfigRequest.CREATOR.createFromParcel(parcelR);

        assertEquals(configRequest, rereadConfigRequest);
    }

    /*
     * SubscribeConfig Tests
     */

    @Test
    public void testSubscribeConfigBuilder() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] txFilter = {
                0, 1, 16, 1, 22 };
        final byte[] rxFilter = {
                1, 127, 0, 1, -5, 1, 22 };
        final int subscribeType = SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE;
        final int subscribeCount = 10;
        final int subscribeTtl = 15;

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo).setTxFilter(txFilter, txFilter.length)
                .setRxFilter(rxFilter, rxFilter.length).setSubscribeType(subscribeType)
                .setSubscribeCount(subscribeCount).setTtlSec(subscribeTtl).build();

        collector.checkThat("mServiceName", serviceName, equalTo(subscribeConfig.mServiceName));
        collector.checkThat("mServiceSpecificInfo",
                utilAreArraysEqual(serviceSpecificInfo.getBytes(), serviceSpecificInfo.length(),
                        subscribeConfig.mServiceSpecificInfo,
                        subscribeConfig.mServiceSpecificInfoLength),
                equalTo(true));
        collector.checkThat("mTxFilter", utilAreArraysEqual(txFilter, txFilter.length,
                subscribeConfig.mTxFilter, subscribeConfig.mTxFilterLength), equalTo(true));
        collector.checkThat("mRxFilter", utilAreArraysEqual(rxFilter, rxFilter.length,
                subscribeConfig.mRxFilter, subscribeConfig.mRxFilterLength), equalTo(true));
        collector.checkThat("mSubscribeType", subscribeType,
                equalTo(subscribeConfig.mSubscribeType));
        collector.checkThat("mSubscribeCount", subscribeCount,
                equalTo(subscribeConfig.mSubscribeCount));
        collector.checkThat("mTtlSec", subscribeTtl, equalTo(subscribeConfig.mTtlSec));
    }

    @Test
    public void testSubscribeConfigParcel() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] txFilter = {
                0, 1, 16, 1, 22 };
        final byte[] rxFilter = {
                1, 127, 0, 1, -5, 1, 22 };
        final int subscribeType = SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE;
        final int subscribeCount = 10;
        final int subscribeTtl = 15;

        SubscribeConfig subscribeConfig = new SubscribeConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo).setTxFilter(txFilter, txFilter.length)
                .setTxFilter(rxFilter, rxFilter.length).setSubscribeType(subscribeType)
                .setSubscribeCount(subscribeCount).setTtlSec(subscribeTtl).build();

        Parcel parcelW = Parcel.obtain();
        subscribeConfig.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        SubscribeConfig rereadSubscribeConfig = SubscribeConfig.CREATOR.createFromParcel(parcelR);

        assertEquals(subscribeConfig, rereadSubscribeConfig);
    }

    @Test
    public void testSubscribeConfigBuilderBadSubscribeType() {
        thrown.expect(IllegalArgumentException.class);
        new SubscribeConfig.Builder().setSubscribeType(10);
    }

    @Test
    public void testSubscribeConfigBuilderNegativeCount() {
        thrown.expect(IllegalArgumentException.class);
        new SubscribeConfig.Builder().setSubscribeCount(-1);
    }

    @Test
    public void testSubscribeConfigBuilderNegativeTtl() {
        thrown.expect(IllegalArgumentException.class);
        new SubscribeConfig.Builder().setTtlSec(-100);
    }

    /*
     * PublishConfig Tests
     */

    @Test
    public void testPublishConfigBuilder() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] txFilter = {
                0, 1, 16, 1, 22 };
        final byte[] rxFilter = {
                1, 127, 0, 1, -5, 1, 22 };
        final int publishType = PublishConfig.PUBLISH_TYPE_SOLICITED;
        final int publishCount = 10;
        final int publishTtl = 15;

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo).setTxFilter(txFilter, txFilter.length)
                .setRxFilter(rxFilter, rxFilter.length).setPublishType(publishType)
                .setPublishCount(publishCount).setTtlSec(publishTtl).build();

        collector.checkThat("mServiceName", serviceName, equalTo(publishConfig.mServiceName));
        collector.checkThat("mServiceSpecificInfo",
                utilAreArraysEqual(serviceSpecificInfo.getBytes(), serviceSpecificInfo.length(),
                        publishConfig.mServiceSpecificInfo,
                        publishConfig.mServiceSpecificInfoLength),
                equalTo(true));
        collector.checkThat("mTxFilter", utilAreArraysEqual(txFilter, txFilter.length,
                publishConfig.mTxFilter, publishConfig.mTxFilterLength), equalTo(true));
        collector.checkThat("mRxFilter", utilAreArraysEqual(rxFilter, rxFilter.length,
                publishConfig.mRxFilter, publishConfig.mRxFilterLength), equalTo(true));
        collector.checkThat("mPublishType", publishType, equalTo(publishConfig.mPublishType));
        collector.checkThat("mPublishCount", publishCount, equalTo(publishConfig.mPublishCount));
        collector.checkThat("mTtlSec", publishTtl, equalTo(publishConfig.mTtlSec));
    }

    @Test
    public void testPublishConfigParcel() {
        final String serviceName = "some_service_or_other";
        final String serviceSpecificInfo = "long arbitrary string with some info";
        final byte[] txFilter = {
                0, 1, 16, 1, 22 };
        final byte[] rxFilter = {
                1, 127, 0, 1, -5, 1, 22 };
        final int publishType = PublishConfig.PUBLISH_TYPE_SOLICITED;
        final int publishCount = 10;
        final int publishTtl = 15;

        PublishConfig publishConfig = new PublishConfig.Builder().setServiceName(serviceName)
                .setServiceSpecificInfo(serviceSpecificInfo).setTxFilter(txFilter, txFilter.length)
                .setTxFilter(rxFilter, rxFilter.length).setPublishType(publishType)
                .setPublishCount(publishCount).setTtlSec(publishTtl).build();

        Parcel parcelW = Parcel.obtain();
        publishConfig.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        PublishConfig rereadPublishConfig = PublishConfig.CREATOR.createFromParcel(parcelR);

        assertEquals(publishConfig, rereadPublishConfig);
    }

    @Test
    public void testPublishConfigBuilderBadPublishType() {
        thrown.expect(IllegalArgumentException.class);
        new PublishConfig.Builder().setPublishType(5);
    }

    @Test
    public void testPublishConfigBuilderNegativeCount() {
        thrown.expect(IllegalArgumentException.class);
        new PublishConfig.Builder().setPublishCount(-4);
    }

    @Test
    public void testPublishConfigBuilderNegativeTtl() {
        thrown.expect(IllegalArgumentException.class);
        new PublishConfig.Builder().setTtlSec(-10);
    }

    /*
     * Utilities
     */

    private static boolean utilAreArraysEqual(byte[] x, int xLength, byte[] y, int yLength) {
        if (xLength != yLength) {
            return false;
        }

        if (x != null && y != null) {
            for (int i = 0; i < xLength; ++i) {
                if (x[i] != y[i]) {
                    return false;
                }
            }
        } else if (xLength != 0) {
            return false; // invalid != invalid
        }

        return true;
    }
}
