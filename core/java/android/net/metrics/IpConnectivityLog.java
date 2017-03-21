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

package android.net.metrics;

import android.net.ConnectivityMetricsEvent;
import android.net.IIpConnectivityMetrics;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Class for logging IpConnectvity events with IpConnectivityMetrics
 * {@hide}
 */
public class IpConnectivityLog {
    private static final String TAG = IpConnectivityLog.class.getSimpleName();
    private static final boolean DBG = false;

    public static final String SERVICE_NAME = "connmetrics";

    private IIpConnectivityMetrics mService;

    public IpConnectivityLog() {
    }

    @VisibleForTesting
    public IpConnectivityLog(IIpConnectivityMetrics service) {
        mService = service;
    }

    private boolean checkLoggerService() {
        if (mService != null) {
            return true;
        }
        final IIpConnectivityMetrics service =
                IIpConnectivityMetrics.Stub.asInterface(ServiceManager.getService(SERVICE_NAME));
        if (service == null) {
            return false;
        }
        // Two threads racing here will write the same pointer because getService
        // is idempotent once MetricsLoggerService is initialized.
        mService = service;
        return true;
    }

    /**
     * Log a ConnectivityMetricsEvent.
     * @param ev the event to log. If the event timestamp is 0,
     * the timestamp is set to the current time in milliseconds.
     * @return true if the event was successfully logged.
     */
    public boolean log(ConnectivityMetricsEvent ev) {
        if (!checkLoggerService()) {
            if (DBG) {
                Log.d(TAG, SERVICE_NAME + " service was not ready");
            }
            return false;
        }
        if (ev.timestamp == 0) {
            ev.timestamp = System.currentTimeMillis();
        }
        try {
            int left = mService.logEvent(ev);
            return left >= 0;
        } catch (RemoteException e) {
            Log.e(TAG, "Error logging event", e);
            return false;
        }
    }

    /**
     * Log an IpConnectivity event.
     * @param timestamp is the epoch timestamp of the event in ms.
     * If the timestamp is 0, the timestamp is set to the current time in milliseconds.
     * @param data is a Parcelable instance representing the event.
     * @return true if the event was successfully logged.
     */
    public boolean log(long timestamp, Parcelable data) {
        ConnectivityMetricsEvent ev = makeEv(data);
        ev.timestamp = timestamp;
        return log(ev);
    }

    /**
     * Log an IpConnectivity event.
     * @param ifname the network interface associated with the event.
     * @param data is a Parcelable instance representing the event.
     * @return true if the event was successfully logged.
     */
    public boolean log(String ifname, Parcelable data) {
        ConnectivityMetricsEvent ev = makeEv(data);
        ev.ifname = ifname;
        return log(ev);
    }

    /**
     * Log an IpConnectivity event.
     * @param data is a Parcelable instance representing the event.
     * @return true if the event was successfully logged.
     */
    public boolean log(Parcelable data) {
        return log(makeEv(data));
    }

    private static ConnectivityMetricsEvent makeEv(Parcelable data) {
        ConnectivityMetricsEvent ev = new ConnectivityMetricsEvent();
        ev.data = data;
        return ev;
    }
}
