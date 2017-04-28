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

package com.android.server.wifi.aware;

import android.app.AppGlobals;
import android.content.pm.IPackageManager;
import android.os.Binder;
import android.os.ShellCommand;

import java.io.PrintWriter;

/**
 * Interprets and executes 'adb shell cmd wifiaware [args]'.
 */
public class WifiAwareShellCommand extends ShellCommand {
    private final WifiAwareStateManager mStateManager;
    private final IPackageManager mPM;

    WifiAwareShellCommand(WifiAwareStateManager stateManager) {
        mStateManager = stateManager;
        mPM = AppGlobals.getPackageManager();
    }

    @Override
    public int onCommand(String cmd) {
        checkRootPermission();

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd != null ? cmd : "") {
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Exception: " + e);
        }
        return -1;
    }

    private void checkRootPermission() {
        final int uid = Binder.getCallingUid();
        if (uid == 0) {
            // Root can do anything.
            return;
        }
        throw new SecurityException("Uid " + uid + " does not have access to wifiaware commands");
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();

        pw.println("Wi-Fi Aware (wifiaware) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
    }
}
