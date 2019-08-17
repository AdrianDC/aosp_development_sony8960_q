/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package android.appsecurity.cts;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.platform.test.annotations.AppModeFull;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.util.FileUtil;

import org.junit.After;
import org.junit.Before;

import java.io.File;

/**
 * Set of tests that verify that corrupt APKs are properly rejected by PackageManager and
 * do not cause the system to crash.
 */
@AppModeFull(reason = "the corrupt APKs were provided as-is and we cannot modify them to comply with instant mode")
public class CorruptApkTests extends BaseAppSecurityTest {
    private final String B71360999_PKG = "com.android.appsecurity.b71360999";
    private final String B71361168_PKG = "com.android.appsecurity.b71361168";
    private final String B79488511_PKG = "com.android.appsecurity.b79488511";

    private final String B71360999_APK = "CtsCorruptApkTests_b71360999.apk";
    private final String B71361168_APK = "CtsCorruptApkTests_b71361168.apk";
    private final String B79488511_APK = "CtsCorruptApkTests_b79488511.apk";
    @Before
    public void setUp() throws Exception {
        getDevice().uninstallPackage(B71360999_PKG);
        getDevice().uninstallPackage(B71361168_PKG);
        getDevice().uninstallPackage(B79488511_PKG);
    }

    @After
    public void tearDown() throws Exception {
        getDevice().uninstallPackage(B71360999_PKG);
        getDevice().uninstallPackage(B71361168_PKG);
        getDevice().uninstallPackage(B79488511_PKG);
    }

    /**
     * Tests that apks described in b/71360999 do not install successfully.
     */
    public void testFailToInstallCorruptStringPoolHeader_b71360999() throws Exception {
        if (getDevice().getApiLevel() < 28) {
            return;
        }
        assertInstallWithoutFatalError(B71360999_APK, B71360999_PKG);
    }

    /**
     * Tests that apks described in b/71361168 do not install successfully.
     */
    public void testFailToInstallCorruptStringPoolHeader_b71361168() throws Exception {
        if (getDevice().getApiLevel() < 28) {
            return;
        }
        assertInstallWithoutFatalError(B71361168_APK, B71361168_PKG);
    }

    /**
     * Tests that apks described in b/79488511 do not install successfully.
     */
    public void testFailToInstallCorruptStringPoolHeader_b79488511() throws Exception {
        if (getDevice().getApiLevel() < 28) {
            return;
        }
        assertInstallWithoutFatalError(B79488511_APK, B79488511_PKG);
    }

    /**
     * Asserts that installing the application does not cause a native error [typically
     * the result of a buffer overflow or an out-of-bounds read].
     */
    private void assertInstallWithoutFatalError(String apk, String pkg) throws Exception {
        getDevice().clearLogcat();

        new InstallMultiple().addApk(apk).runExpectingFailure();

        // This catches if the device fails to install the app because a segmentation fault
        // or out of bounds read created by the bug occurs
        final File tmpTxtFile = FileUtil.createTempFile("logcat", ".txt");
        final InputStreamSource source = getDevice().getLogcat(200 * 1024);
        try {
            assertNotNull(source);
            FileUtil.writeToFile(source.createInputStream(), tmpTxtFile);
            final String s = FileUtil.readStringFromFile(tmpTxtFile);
            assertFalse(s.contains("SIGSEGV"));
            assertFalse(s.contains("==ERROR"));
        } finally {
            source.close();
            FileUtil.deleteFile(tmpTxtFile);
        }
    }
}
