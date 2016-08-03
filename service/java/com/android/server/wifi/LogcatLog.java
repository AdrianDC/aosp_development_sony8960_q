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

import android.util.Log;

import com.android.internal.annotations.Immutable;

// @ThreadSafe
// This class is trivially thread-safe, as instances are immutable.
@Immutable
class LogcatLog implements WifiLog {
    private final String mTag;

    LogcatLog(String tag) {
        mTag = tag;
    }

    public void e(String msg) {
        Log.e(mTag, msg);
    }

    public void w(String msg) {
        Log.w(mTag, msg);
    }

    public void i(String msg) {
        Log.i(mTag, msg);
    }

    public void d(String msg) {
        Log.d(mTag, msg);
    }

    public void v(String msg) {
        Log.v(mTag, msg);
    }
}
