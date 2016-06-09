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

import static org.mockito.Mockito.*;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.app.test.TestAlarmManager;
import android.content.Context;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.WifiConfigStoreNew.StoreFile;

import org.junit.Before;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link com.android.server.wifi.WifiConfigStoreNew}.
 */
@SmallTest
public class WifiConfigStoreNewTest {
    // Test constants.
    private static final int TEST_NETWORK_ID = -1;
    private static final int TEST_UID = 1;
    private static final String TEST_SSID = "WifiConfigStoreSSID_";
    private static final String TEST_PSK = "WifiConfigStorePsk";
    private static final String[] TEST_WEP_KEYS =
            {"WifiConfigStoreWep1", "WifiConfigStoreWep2",
                    "WifiConfigStoreWep3", "WifiConfigStoreWep3"};
    private static final int TEST_WEP_TX_KEY_INDEX = 1;
    private static final String TEST_FQDN = "WifiConfigStoreFQDN";
    private static final String TEST_PROVIDER_FRIENDLY_NAME = "WifiConfigStoreFriendlyName";
    private static final String TEST_STATIC_IP_LINK_ADDRESS = "192.168.48.2";
    private static final int TEST_STATIC_IP_LINK_PREFIX_LENGTH = 8;
    private static final String TEST_STATIC_IP_GATEWAY_ADDRESS = "192.168.48.1";
    private static final String[] TEST_STATIC_IP_DNS_SERVER_ADDRESSES =
            new String[]{"192.168.48.1", "192.168.48.10"};
    private static final String TEST_STATIC_PROXY_HOST = "192.168.48.1";
    private static final int TEST_STATIC_PROXY_PORT = 8000;
    private static final String TEST_STATIC_PROXY_EXCLUSION_LIST = "";
    private static final String TEST_PAC_PROXY_LOCATION = "http://";

    // Test mocks
    @Mock private Context mContext;
    private TestAlarmManager mAlarmManager;
    private TestLooper mLooper;
    private MockStoreFile mSharedStore;
    private MockStoreFile mUserStore;

    /**
     * Test instance of WifiConfigStore.
     */
    private WifiConfigStoreNew mWifiConfigStore;

    /**
     * Setup mocks before the test starts.
     */
    private void setupMocks() throws Exception {
        MockitoAnnotations.initMocks(this);
        mAlarmManager = new TestAlarmManager();
        mLooper = new TestLooper();
        when(mContext.getSystemService(Context.ALARM_SERVICE))
                .thenReturn(mAlarmManager.getAlarmManager());
        mUserStore = new MockStoreFile();
        mSharedStore = new MockStoreFile();
    }

    /**
     * Setup the test environment.
     */
    @Before
    public void setUp() throws Exception {
        setupMocks();

        mWifiConfigStore =
                new WifiConfigStoreNew(mContext, mLooper.getLooper(), mSharedStore, mUserStore);

        // Enable verbose logging before tests.
        mWifiConfigStore.enableVerboseLogging(1);
    }

    /**
     * Mock Store File to redirect all file writes from WifiConfigStoreNew to local buffers.
     * This can be used to examine the data output by WifiConfigStoreNew.
     */
    public class MockStoreFile extends StoreFile {
        private byte[] mStoreBytes;
        private boolean mStoreWritten;

        public MockStoreFile() {
            super(new File("MockStoreFile"));
        }

        @Override
        public byte[] readRawData() {
            return mStoreBytes;
        }

        @Override
        public void storeRawDataToWrite(byte[] data) {
            mStoreBytes = data;
            mStoreWritten = false;
        }

        @Override
        public void writeBufferedRawData() {
            mStoreWritten = true;
        }

        public byte[] getStoreBytes() {
            return mStoreBytes;
        }

        public boolean isStoreWritten() {
            return mStoreWritten;
        }
    }
}
