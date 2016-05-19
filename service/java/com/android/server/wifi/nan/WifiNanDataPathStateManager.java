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

import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages NAN data-path lifetime: interface creation/deletion, data-path setup and tear-down.
 */
public class WifiNanDataPathStateManager {
    private static final String TAG = "WifiNanDataPathStateMgr";

    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final String NAN_INTERFACE_PREFIX = "nan";

    private final WifiNanStateManager mMgr;
    private Set<String> mInterfaces = new HashSet<>();

    public WifiNanDataPathStateManager(WifiNanStateManager mgr) {
        mMgr = mgr;
    }

    /**
     * Create all NAN data-path interfaces which are possible on the device - based on the
     * capabilities of the firmware.
     */
    public void createAllInterfaces() {
        if (VDBG) Log.v(TAG, "createAllInterfaces");

        if (mMgr.mCapabilities == null) {
            Log.e(TAG, "createAllInterfaces: capabilities aren't initialized yet!");
            return;
        }

        for (int i = 0; i < mMgr.mCapabilities.maxNdiInterfaces; ++i) {
            String name = NAN_INTERFACE_PREFIX + i;
            if (mInterfaces.contains(name)) {
                Log.e(TAG, "createAllInterfaces(): interface already up, " + name
                        + ", possibly failed to delete - deleting/creating again to be safe");
                mMgr.deleteDataPathInterface(name);

                // critical to remove so that don't get infinite loop if the delete fails again
                mInterfaces.remove(name);
            }

            mMgr.createDataPathInterface(name);
        }
    }

    /**
     * Delete all NAN data-path interfaces which are currently up.
     */
    public void deleteAllInterfaces() {
        if (VDBG) Log.v(TAG, "deleteAllInterfaces");

        for (String name : mInterfaces) {
            mMgr.deleteDataPathInterface(name);
        }
    }

    /**
     * Called when firmware indicates the an interface was created.
     */
    public void onInterfaceCreated(String interfaceName) {
        if (VDBG) Log.v(TAG, "onInterfaceCreated: interfaceName=" + interfaceName);

        if (mInterfaces.contains(interfaceName)) {
            Log.w(TAG, "onInterfaceCreated: already contains interface -- " + interfaceName);
        }

        mInterfaces.add(interfaceName);
    }

    /**
     * Called when firmware indicates the an interface was deleted.
     */
    public void onInterfaceDeleted(String interfaceName) {
        if (VDBG) Log.v(TAG, "onInterfaceDeleted: interfaceName=" + interfaceName);

        if (!mInterfaces.contains(interfaceName)) {
            Log.w(TAG, "onInterfaceDeleted: interface not on list -- " + interfaceName);
        }

        mInterfaces.remove(interfaceName);
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WifiNanDataPathStateManager:");
        pw.println("  mInterfaces: " + mInterfaces);
    }
}
