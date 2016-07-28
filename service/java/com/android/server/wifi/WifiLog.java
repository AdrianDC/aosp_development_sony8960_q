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

public interface WifiLog {
    /**
     * Log an error using the default tag for this WifiLog instance.
     * @param msg the message to be logged
     */
    void e(String msg);

    /**
     * Log a warning using the default tag for this WifiLog instance.
     * @param msg the message to be logged
     */
    void w(String msg);

    /**
     * Log an informational message using the default tag for this WifiLog instance.
     * @param msg the message to be logged
     */
    void i(String msg);

    /**
     * Log a debug message using the default tag for this WifiLog instance.
     * @param msg the message to be logged
     */
    void d(String msg);

    /**
     * Log a verbose message using the default tag for this WifiLog instance.
     * @param msg the message to be logged
     */
    void v(String msg);
}
