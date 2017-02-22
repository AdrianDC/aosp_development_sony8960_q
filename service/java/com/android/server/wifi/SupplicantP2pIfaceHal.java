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

import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import android.hardware.wifi.supplicant.V1_0.ISupplicantIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantP2pIface;
import android.hardware.wifi.supplicant.V1_0.IfaceType;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatus;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatusCode;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.os.RemoteException;
import android.util.Log;
import android.util.MutableBoolean;

import java.util.ArrayList;
import java.util.Arrays;
/**
 * Native calls sending requests to the P2P Hals, and callbacks for receiving P2P events
 *
 * {@hide}
 */
public class SupplicantP2pIfaceHal {
    private static final boolean DBG = true;
    private static final String TAG = "SupplicantP2pIfaceHal";
    private static final String SERVICE_MANAGER_NAME = "manager";
    private static final int RESULT_NOT_VALID = -1;
    private static final int DEFAULT_GROUP_OWNER_INTENT = 6;

    // Supplicant HAL HIDL interface objects
    private IServiceManager mIServiceManager = null;
    private ISupplicant mISupplicant = null;
    private ISupplicantIface mHidlSupplicantIface = null;
    private ISupplicantP2pIface mISupplicantP2pIface = null;
    private Object mLock = new Object();

    /**
     * Registers a service notification for the ISupplicant service, which triggers intialization of
     * the ISupplicantStaIface
     * @return true if the service notification was successfully registered
     */
    public boolean initialize() {
        if (DBG) Log.i(TAG, "Registering ISupplicant service ready callback.");
        synchronized (mLock) {
            if (mIServiceManager != null) {
                Log.i(TAG, "Supplicant HAL already initialized.");
                // Already have an IServiceManager and serviceNotification registered, don't
                // don't register another.
                return true;
            }
            mISupplicant = null;
            mISupplicantP2pIface = null;
            try {
                mIServiceManager = getServiceManagerMockable();
                if (mIServiceManager == null) {
                    Log.e(TAG, "Failed to get HIDL Service Manager");
                    return false;
                }
                if (!mIServiceManager.linkToDeath(cookie -> {
                    Log.wtf(TAG, "IServiceManager died: cookie=" + cookie);
                    synchronized (mLock) {
                        supplicantServiceDiedHandler();
                        mIServiceManager = null; // Will need to register a new ServiceNotification
                    }
                }, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                    supplicantServiceDiedHandler();
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
                IServiceNotification serviceNotificationCb = new IServiceNotification.Stub() {
                    public void onRegistration(String fqName, String name, boolean preexisting) {
                        synchronized (mLock) {
                            if (DBG) {
                                Log.i(TAG, "IServiceNotification.onRegistration for: " + fqName
                                        + ", " + name + " preexisting=" + preexisting);
                            }
                            if (!initSupplicantService() || !initSupplicantP2pIface()) {
                                Log.e(TAG, "initalizing ISupplicantIfaces failed.");
                                supplicantServiceDiedHandler();
                            } else {
                                Log.i(TAG, "Completed initialization of ISupplicant interfaces.");
                            }
                        }
                    }
                };
                /* TODO(b/33639391) : Use the new ISupplicant.registerForNotifications() once it
                   exists */
                if (!mIServiceManager.registerForNotifications(ISupplicant.kInterfaceName,
                        "", serviceNotificationCb)) {
                    Log.e(TAG, "Failed to register for notifications to "
                            + ISupplicant.kInterfaceName);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to register a listener for ISupplicant service: "
                        + e);
                supplicantServiceDiedHandler();
            }
            return true;
        }
    }

    private boolean initSupplicantService() {
        synchronized (mLock) {
            try {
                mISupplicant = getSupplicantMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.getService exception: " + e);
                return false;
            }
            if (mISupplicant == null) {
                Log.e(TAG, "Got null ISupplicant service. Stopping supplicant HIDL startup");
                return false;
            }
        }
        return true;
    }

    private boolean initSupplicantP2pIface() {
        synchronized (mLock) {
            /** List all supplicant Ifaces */
            final ArrayList<ISupplicant.IfaceInfo> supplicantIfaces = new ArrayList();
            try {
                mISupplicant.listInterfaces((SupplicantStatus status,
                        ArrayList<ISupplicant.IfaceInfo> ifaces) -> {
                    if (status.code != SupplicantStatusCode.SUCCESS) {
                        Log.e(TAG, "Getting Supplicant Interfaces failed: " + status.code);
                        return;
                    }
                    supplicantIfaces.addAll(ifaces);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.listInterfaces exception: " + e);
                return false;
            }
            if (supplicantIfaces.size() == 0) {
                Log.e(TAG, "Got zero HIDL supplicant ifaces. Stopping supplicant HIDL startup.");
                return false;
            }
            SupplicantResult<ISupplicantIface> supplicantIface =
                    new SupplicantResult("getInterface()");
            for (ISupplicant.IfaceInfo ifaceInfo : supplicantIfaces) {
                if (ifaceInfo.type == IfaceType.P2P) {
                    try {
                        mISupplicant.getInterface(ifaceInfo,
                                (SupplicantStatus status, ISupplicantIface iface) -> {
                                if (status.code != SupplicantStatusCode.SUCCESS) {
                                    Log.e(TAG, "Failed to get ISupplicantIface " + status.code);
                                    return;
                                }
                                supplicantIface.setResult(status, iface);
                            });
                    } catch (RemoteException e) {
                        Log.e(TAG, "ISupplicant.getInterface exception: " + e);
                        return false;
                    }
                    break;
                }
            }

            if (supplicantIface.getResult() == null) {
                Log.e(TAG, "initSupplicantP2pIface got null iface");
                return false;
            }
            mISupplicantP2pIface = getP2pIfaceMockable(supplicantIface.getResult());

            return true;
        }
    }

    private void supplicantServiceDiedHandler() {
        synchronized (mLock) {
            mISupplicant = null;
            mISupplicantP2pIface = null;
        }
    }


    /**
     * Signals whether Initialization completed successfully.
     */
    public boolean isInitializationStarted() {
        return mIServiceManager != null;
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    public boolean isInitializationComplete() {
        return mISupplicantP2pIface != null;
    }

    /**
     * Wrapper functions to access static HAL methods, created to be mockable in unit tests
     */
    protected IServiceManager getServiceManagerMockable() throws RemoteException {
        return IServiceManager.getService(SERVICE_MANAGER_NAME);
    }

    protected ISupplicant getSupplicantMockable() throws RemoteException {
        return ISupplicant.getService();
    }

    protected ISupplicantP2pIface getP2pIfaceMockable(ISupplicantIface iface) {
        return ISupplicantP2pIface.asInterface(iface.asBinder());
    }


    protected static void logd(String s) {
        if (DBG) Log.d(TAG, s);
    }

    protected static void logCompletion(String operation, SupplicantStatus status) {
        if (status.code == SupplicantStatusCode.SUCCESS) {
            logd(operation + " completed successfully.");
        } else {
            Log.w(TAG, operation + " failed: " + status.code + " (" + status.debugMessage + ")");
        }
    }


    /** Container class allowing propagation of status and/or value
     * from callbacks.
     *
     * Primary purpose is to allow callback lambdas to provide results
     * to parent methods.
     */
    private static class SupplicantResult<E> {
        private String mMethodName;
        private SupplicantStatus mStatus;
        private E mValue;

        public SupplicantResult(String methodName) {
            mMethodName = methodName;
            mStatus = null;
            mValue = null;
            logd("entering " + mMethodName);
        }

        public void setResult(SupplicantStatus status, E value) {
            logCompletion(mMethodName, status);
            logd("leaving " + mMethodName);
            mStatus = status;
            mValue = value;
        }

        public void setResult(SupplicantStatus status) {
            logCompletion(mMethodName, status);
            logd("leaving " + mMethodName);
            mStatus = status;
        }

        public boolean isSuccess() {
            return (mStatus != null && mStatus.code == SupplicantStatusCode.SUCCESS);
        }

        public E getResult() {
            return (isSuccess() ? mValue : null);
        }
    }
}
