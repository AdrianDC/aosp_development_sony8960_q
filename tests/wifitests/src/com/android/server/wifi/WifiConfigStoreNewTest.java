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

import static com.android.server.wifi.WifiConfigStoreDataTest.assertConfigStoreDataEqual;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.test.TestAlarmManager;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.WifiConfigStoreNew.StoreFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

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

    // Test mocks
    @Mock private Context mContext;
    private TestAlarmManager mAlarmManager;
    private TestLooper mLooper;
    @Mock private Clock mClock;
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
                new WifiConfigStoreNew(
                        mContext, mLooper.getLooper(), mClock, mSharedStore, mUserStore);

        // Enable verbose logging before tests.
        mWifiConfigStore.enableVerboseLogging(1);
    }

    /**
     * Called after each test
     */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    /**
     * Tests the write API with the force flag set to true.
     * Expected behavior: This should trigger an immediate write to the store files and no alarms
     * should be started.
     */
    @Test
    public void testForceWrite() throws Exception {
        mWifiConfigStore.write(true, getEmptyStoreData());

        assertFalse(mAlarmManager.isPending(WifiConfigStoreNew.BUFFERED_WRITE_ALARM_TAG));
        assertTrue(mSharedStore.isStoreWritten());
        assertTrue(mUserStore.isStoreWritten());
    }

    /**
     * Tests the write API with the force flag set to false.
     * Expected behavior: This should set an alarm to write to the store files.
     */
    @Test
    public void testBufferedWrite() throws Exception {
        mWifiConfigStore.write(false, getEmptyStoreData());

        assertTrue(mAlarmManager.isPending(WifiConfigStoreNew.BUFFERED_WRITE_ALARM_TAG));
        assertFalse(mSharedStore.isStoreWritten());
        assertFalse(mUserStore.isStoreWritten());

        // Now send the alarm and ensure that the writes happen.
        mAlarmManager.dispatch(WifiConfigStoreNew.BUFFERED_WRITE_ALARM_TAG);
        mLooper.dispatchAll();
        assertTrue(mSharedStore.isStoreWritten());
        assertTrue(mUserStore.isStoreWritten());
    }

    /**
     * Tests the force write after a buffered write.
     * Expected behaviour: The force write should override the previous buffered write and stop the
     * buffer write alarms.
     */
    @Test
    public void testForceWriteAfterBufferedWrite() throws Exception {
        WifiConfigStoreData bufferedStoreData = createSingleOpenNetworkStoreData();
        mWifiConfigStore.write(false, bufferedStoreData);

        assertTrue(mAlarmManager.isPending(WifiConfigStoreNew.BUFFERED_WRITE_ALARM_TAG));
        assertFalse(mSharedStore.isStoreWritten());
        assertFalse(mUserStore.isStoreWritten());

        // Now send a force write and ensure that the writes have been performed and alarms have
        // been stopped.
        WifiConfigStoreData forcedStoreData = createSinglePskNetworkStoreData();
        mWifiConfigStore.write(true, forcedStoreData);

        assertFalse(mAlarmManager.isPending(WifiConfigStoreNew.BUFFERED_WRITE_ALARM_TAG));
        assertTrue(mSharedStore.isStoreWritten());
        assertTrue(mUserStore.isStoreWritten());

        // Now deserialize the data and ensure that the configuration retrieved matches the force
        // write data.
        WifiConfigStoreData retrievedStoreData =
                WifiConfigStoreData.parseRawData(
                        mSharedStore.getStoreBytes(), mUserStore.getStoreBytes());

        assertConfigStoreDataEqual(forcedStoreData, retrievedStoreData);
    }

    /**
     * Tests the read API behaviour when there is no file on the device.
     * Expected behaviour: The read should return an empty store data instance when the file not
     * found exception is raised.
     */
    @Test
    public void testReadWithNoStoreFile() throws Exception {
        // Reading the mock store without a write should simulate the file not found case because
        // |readRawData| would return null.
        WifiConfigStoreData readData = mWifiConfigStore.read();
        assertConfigStoreDataEqual(getEmptyStoreData(), readData);
    }

    /**
     * Tests the read API behaviour after a write to the store file.
     * Expected behaviour: The read should return the same data that was last written.
     */
    @Test
    public void testReadAfterWrite() throws Exception {
        WifiConfigStoreData writeData = getSingleOpenNetworkStoreData();
        mWifiConfigStore.write(true, writeData);
        WifiConfigStoreData readData = mWifiConfigStore.read();

        assertConfigStoreDataEqual(writeData, readData);
    }

    /**
     * Returns an empty store data object.
     */
    private WifiConfigStoreData getEmptyStoreData() {
        return new WifiConfigStoreData(
                new ArrayList<WifiConfiguration>(), new HashSet<String>(),
                new HashSet<String>(), WifiConfigStoreData.NETWORK_ID_START);
    }

    /**
     * Returns an store data object with a single open network.
     */
    private WifiConfigStoreData createSingleOpenNetworkStoreData() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createOpenNetwork(0));
        return new WifiConfigStoreData(
                configurations, new HashSet<String>(), new HashSet<String>(), 0);
    }

    /**
     * Returns an store data object with a single psk network.
     */
    private WifiConfigStoreData createSinglePskNetworkStoreData() {
        List<WifiConfiguration> configurations = new ArrayList<>();
        configurations.add(createPskNetwork(1));
        return new WifiConfigStoreData(
                configurations, new HashSet<String>(), new HashSet<String>(), 0);
    }

    private WifiConfiguration createOpenNetwork(int id) {
        String ssid = "\"" + TEST_SSID + id + "\"";
        return WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                true, true, null, null,
                WifiConfigurationTestUtil.SECURITY_NONE);
    }

    private WifiConfiguration createPskNetwork(int id) {
        String ssid = "\"" + TEST_SSID + id + "\"";
        WifiConfiguration configuration =
                WifiConfigurationTestUtil.generateWifiConfig(TEST_NETWORK_ID, TEST_UID, ssid,
                        true, true, null, null,
                        WifiConfigurationTestUtil.SECURITY_PSK);
        configuration.preSharedKey = TEST_PSK;
        return configuration;
    }

    /**
     * Mock Store File to redirect all file writes from WifiConfigStoreNew to local buffers.
     * This can be used to examine the data output by WifiConfigStoreNew.
     */
    private class MockStoreFile extends StoreFile {
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
