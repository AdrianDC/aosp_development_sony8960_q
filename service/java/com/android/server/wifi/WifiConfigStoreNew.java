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

import android.app.AlarmManager;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.AtomicFile;

import java.io.File;

/**
 * This class provides the API's to save/load/modify network configurations from a persistent
 * store. Uses keystore for certificate/key management operations.
 * NOTE: This class should only be used from WifiConfigManager!!!
 */
public class WifiConfigStoreNew {
    /**
     * Alarm tag to use for starting alarms for buffering file writes.
     */
    @VisibleForTesting
    public static final String FILE_WRITE_BUFFER_ALARM_TAG = "WriteBufferAlarm";
    /**
     * Log tag.
     */
    private static final String TAG = "WifiConfigStoreNew";
    /**
     * Config store file name for both shared & user specific stores.
     */
    private static final String STORE_FILE_NAME = "WifiConfigStore.xml";
    /**
     * Directory to store the shared config store file.
     */
    private static final String SHARED_STORE_DIRECTORY =
            new File(Environment.getDataDirectory(), "misc/wifi").getAbsolutePath();
    /**
     * Time interval for buffering file writes for non-forced writes
     */
    private static final int FILE_WRITE_BUFFER_ALARM_INTERVAL_MS = 10 * 1000;
    /**
     * Handler instance to post alarm timeouts to
     */
    private final Handler mEventHandler;
    /**
     * Alarm manager instance to start buffer timeout alarms.
     */
    private AlarmManager mAlarmManager;
    /**
     * Shared config store file instance.
     */
    private StoreFile mSharedStore;
    /**
     * User specific store file instance.
     */
    private StoreFile mUserStore;
    /**
     * Verbose logging flag.
     */
    private boolean mVerboseLoggingEnabled = false;

    /**
     * Create a new instance of WifiConfigStore.
     * Note: The store file instances have been made inputs to this class to ease unit-testing.
     *
     * @param context     context to use for retrieving the alarm manager.
     * @param looper      looper instance to post alarm timeouts to.
     * @param sharedStore StoreFile instance pointing to the shared store file. This should
     *                    be retrieved using {@link #createSharedFile()} method.
     * @param userStore   StoreFile instance pointing to the user specific store file. This should
     *                    be retrieved using {@link #createUserFile(int)} method.
     */
    public WifiConfigStoreNew(Context context, Looper looper,
            StoreFile sharedStore, StoreFile userStore) {

        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mEventHandler = new Handler(looper);

        // Initialize the store files.
        mSharedStore = sharedStore;
        handleUserSwitch(userStore);
    }

    /**
     * Create a new instance of the shared store file.
     *
     * @return new instance of the store file.
     */
    public static StoreFile createSharedFile() {
        return new StoreFile(new File(SHARED_STORE_DIRECTORY, STORE_FILE_NAME));
    }

    /**
     * Create a new instance of the user specific store file.
     * The user store file is inside the user's encrypted data directory.
     *
     * @param userId userId corresponding to the currently logged-in user.
     * @return new instance of the store file.
     */
    public static StoreFile createUserFile(int userId) {
        final String userDir = Environment.getDataSystemCeDirectory(userId).getAbsolutePath();
        return new StoreFile(new File(userDir, STORE_FILE_NAME));
    }

    /**
     * Enable verbose logging.
     *
     * @param verbose verbosity level.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = (verbose > 0);
    }

    /**
     * API to write the provided store data to config stores.
     * The method writes the user specific configurations to user specific config store and the
     * shared configurations to shared config store.
     * Also stores other global data like blacklists, etc.
     *
     * @param forceSync boolean to force write the config stores now. if false, the writes are
     *                  buffered and written after the configured interval.
     * @param storeData The entire data to be stored across all the config store files.
     */
    public void write(boolean forceSync, WifiConfigStoreData storeData) {
        byte[] sharedDataBytes = storeData.createSharedRawData();
        byte[] userDataBytes = storeData.createUserRawData();

        mSharedStore.storeRawDataToWrite(sharedDataBytes);
        mUserStore.storeRawDataToWrite(userDataBytes);

        if (forceSync) {
            mSharedStore.writeBufferedRawData();
            mUserStore.writeBufferedRawData();
        } else {
            // start alarm and blah, blah.
        }
    }

    /**
     * API to read the store data from the config stores.
     * The method reads the user specific configurations from user specific config store and the
     * shared configurations from the shared config store.
     * Also retrieves other global data like blacklists, etc.
     *
     * @return storeData The entire data retrieved across all the config store files.
     */
    public WifiConfigStoreData read() {
        byte[] sharedDataBytes = mSharedStore.readRawData();
        byte[] userDataBytes = mUserStore.readRawData();

        return WifiConfigStoreData.parseRawData(sharedDataBytes, userDataBytes);
    }

    /**
     * Handle a user switch. This changes the user specific store.
     *
     * @param userStore StoreFile instance pointing to the user specific store file. This should
     *                  be retrieved using {@link #createUserFile(int)} method.
     */
    public void handleUserSwitch(StoreFile userStore) {
        if (mUserStore != null) {
            // Flush out any stored data if present before switching the user stores.
            mUserStore.writeBufferedRawData();
        }
        mUserStore = userStore;
    }

    /**
     * Class to encapsulate all file writes. This is a wrapper over {@link AtomicFile} to write/read
     * raw data from the persistent file. This class provides helper methods to read/write the
     * entire file into a byte array.
     * This helps to separate out the processing/parsing from the actual file writing.
     */
    public static class StoreFile {
        /**
         * The store file to be written to.
         */
        private final AtomicFile mAtomicFile;
        /**
         * This is an intermediate buffer to store the data to be written.
         */
        private byte[] mWriteData;

        public StoreFile(File file) {
            mAtomicFile = new AtomicFile(file);
        }

        /**
         * Read the entire raw data from the store file and return in a byte array.
         *
         * @return raw data read from the file.
         */
        public byte[] readRawData() {
            return null;
        }

        /**
         * Store the provided byte array to be written when {@link #writeBufferedRawData()} method
         * is invoked.
         * This intermediate step is needed to help in buffering file writes.
         *
         * @param data raw data to be written to the file.
         */
        public void storeRawDataToWrite(byte[] data) {
            mWriteData = data;
        }

        /**
         * Write the stored raw data to the store file.
         * After the write to file, the mWriteData member is reset.
         */
        public void writeBufferedRawData() {
            if (mWriteData != null) {
                // write data.
            }
            mWriteData = null;
        }
    }
}
