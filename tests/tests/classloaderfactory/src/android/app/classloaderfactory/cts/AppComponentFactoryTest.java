/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.app.classloaderfactory.cts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AppComponentFactoryTest {
    public static final String SECONDARY_APK_PATH =
            "/data/local/tmp/classloaderfactory-test/secondary.jar";
    private static final String CLASS_PACKAGE_NAME =
            AppComponentFactoryTest.class.getPackage().getName();

    private final Context mContext = InstrumentationRegistry.getContext();

    @Test
    public void testApplication() {
        // Test that application context is MyApplication class from the secondary APK.
        // The assertion fails if framework did not use the custom class loader.
        assertEquals(mContext.getApplicationContext().getClass().getName(),
                CLASS_PACKAGE_NAME + ".MyApplication");
        assertNotEquals(mContext.getApplicationContext().getClass().getClassLoader(),
                AppComponentFactoryTest.class.getClassLoader());
    }

    @Test
    public void testActivity() {
        // Start MyActivity from the secondary APK. This will throw ActivityNotFoundException
        // if framework does not use the custom class loader.
        mContext.startActivity(new Intent()
                .setComponent(new ComponentName(mContext.getPackageName(),
                        CLASS_PACKAGE_NAME + ".MyActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
