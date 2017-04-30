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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

/**
 * Tracks information about applications requesting use of the LocalOnlyHotspot.
 *
 * @hide
 */
public class LocalOnlyHotspotRequestInfo implements IBinder.DeathRecipient {
    private final int mUid;
    private final IBinder mBinder;
    private final RequestingApplicationDeathCallback mCallback;
    private final Messenger mMessenger;

    /**
     * Callback for use with LocalOnlyHotspot to unregister requesting applications upon death.
     */
    public interface RequestingApplicationDeathCallback {
        /**
         * Called when requesting app has died.
         */
        void onLocalOnlyHotspotRequestorDeath(LocalOnlyHotspotRequestInfo requestor);
    }

    LocalOnlyHotspotRequestInfo(@NonNull IBinder binder, @NonNull Messenger messenger,
            @NonNull RequestingApplicationDeathCallback callback) {
        mUid = Binder.getCallingUid();
        mBinder = Preconditions.checkNotNull(binder);
        mMessenger = Preconditions.checkNotNull(messenger);
        mCallback = Preconditions.checkNotNull(callback);

        try {
            mBinder.linkToDeath(this, 0);
        } catch (RemoteException e) {
            binderDied();
        }
    }

    /**
     * Allow caller to unlink this object from binder death.
     */
    public void unlinkDeathRecipient() {
        mBinder.unlinkToDeath(this, 0);
    }

    /**
     * Application requesting LocalOnlyHotspot died
     */
    @Override
    public void binderDied() {
        mCallback.onLocalOnlyHotspotRequestorDeath(this);
    }

    /**
     * Send a message to WifiManager for the calling application.
     *
     * @param what Message type to send
     * @param arg1 arg1 for the message
     *
     * @throws RemoteException
     */
    public void sendMessage(int what, int arg1) throws RemoteException {
        Message message = Message.obtain();
        message.what = what;
        message.arg1 = arg1;
        mMessenger.send(message);
    }

    public int getUid() {
        return mUid;
    }
}
