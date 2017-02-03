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

import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import android.hardware.wifi.supplicant.V1_0.ISupplicantIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantNetwork;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaNetwork;
import android.hardware.wifi.supplicant.V1_0.IfaceType;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatus;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatusCode;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;
import android.util.MutableBoolean;

import libcore.util.HexEncoding;

import java.util.ArrayList;
/**
 * Hal calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 *
 * {@hide}
 */
public class WifiSupplicantHal {
    /** Invalid Supplicant Iface type */
    public static final int INVALID_IFACE_TYPE = -1;
    private static final boolean DBG = false;
    private static final String TAG = "WifiSupplicantHal";
    private static final String SERVICE_MANAGER_NAME = "manager";
    private IServiceManager mIServiceManager = null;
    // Supplicant HAL interface objects
    private ISupplicant mISupplicant;
    private ISupplicantStaIface mISupplicantStaIface;
    private final Object mLock = new Object();
    private final HandlerThread mHandlerThread;
    public WifiSupplicantHal(HandlerThread handlerThread) {
        mHandlerThread = handlerThread;
    }

    /**
     * Registers a service notification for the ISupplicant service, which triggers intialization of
     * the ISupplicantStaIface
     * @return true if the service notification was successfully registered
     */
    public boolean initialize() {
        if (DBG) Log.i(TAG, "Registering ISupplicant service ready callback.");
        synchronized (mLock) {
            mISupplicant = null;
            mISupplicantStaIface = null;
            if (mIServiceManager != null) {
                // Already have an IServiceManager and serviceNotification registered, don't
                // don't register another.
                return true;
            }
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
                            if (!initSupplicantService() || !initSupplicantStaIface()) {
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

    private boolean initSupplicantStaIface() {
        synchronized (mLock) {
            /** List all supplicant Ifaces */
            final ArrayList<ISupplicant.IfaceInfo> supplicantIfaces = new ArrayList<>();
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
            Mutable<ISupplicantIface> supplicantIface = new Mutable<>();
            for (ISupplicant.IfaceInfo ifaceInfo : supplicantIfaces) {
                if (ifaceInfo.type == IfaceType.STA) {
                    try {
                        mISupplicant.getInterface(ifaceInfo,
                                (SupplicantStatus status, ISupplicantIface iface) -> {
                                if (status.code != SupplicantStatusCode.SUCCESS) {
                                    Log.e(TAG, "Failed to get ISupplicantIface " + status.code);
                                    return;
                                }
                                supplicantIface.value = iface;
                            });
                    } catch (RemoteException e) {
                        Log.e(TAG, "ISupplicant.getInterface exception: " + e);
                        return false;
                    }
                    break;
                }
            }
            if (supplicantIface.value == null) {
                Log.e(TAG, "initSupplicantStaIface got null iface");
                return false;
            }
            mISupplicantStaIface = getStaIfaceMockable(supplicantIface.value);
            return true;
        }
    }

    private void supplicantServiceDiedHandler() {
        synchronized (mLock) {
            mISupplicant = null;
            mISupplicantStaIface = null;
        }
    }

    /**
     * Signals whether Initialization completed successfully. Only necessary for testing, is not
     * needed to guard calls etc.
     */
    public boolean isInitializationComplete() {
        return mISupplicantStaIface != null;
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

    protected ISupplicantStaIface getStaIfaceMockable(ISupplicantIface iface) {
        return ISupplicantStaIface.asInterface(iface.asBinder());
    }

    /**
     * @return returns the name of Iface or null if the call fails
     */
    private String getName() {
        synchronized (mLock) {
            MutableBoolean statusSuccess = new MutableBoolean(false);
            final String methodStr = "getName";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            final StringBuilder builder = new StringBuilder();
            try {
                mISupplicantStaIface.getName((SupplicantStatus status, String name) -> {
                    statusSuccess.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (!statusSuccess.value) {
                        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
                    } else {
                        builder.append(name);
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (statusSuccess.value) {
                return builder.toString();
            } else {
                return null;
            }
        }
    }
    /**
     * @return the IfaceType value for this interface, or INVALID_IFACE_TYPE if call fails
     */
    private int getType() {
        synchronized (mLock) {
            MutableBoolean statusSuccess = new MutableBoolean(false);
            Mutable<Integer> gotType = new Mutable<>();
            final String methodStr = "getType";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return INVALID_IFACE_TYPE;
            try {
                mISupplicantStaIface.getType((SupplicantStatus status, int type) -> {
                    statusSuccess.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (!statusSuccess.value) {
                        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
                    } else {
                        gotType.value = type;
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (statusSuccess.value) {
                return gotType.value;
            } else {
                return INVALID_IFACE_TYPE;
            }
        }
    }
    /**
     * @return The ISupplicantNetwork object for the new network, or null if the call fails
     */
    private SupplicantStaNetworkHal addNetwork() {
        synchronized (mLock) {
            MutableBoolean statusSuccess = new MutableBoolean(false);
            Mutable<ISupplicantNetwork> newNetwork = new Mutable<>();
            final String methodStr = "addNetwork";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            try {
                mISupplicantStaIface.addNetwork((SupplicantStatus status,
                        ISupplicantNetwork network) -> {
                    statusSuccess.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (!statusSuccess.value) {
                        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
                    } else {
                        newNetwork.value = network;
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (statusSuccess.value) {
                return new SupplicantStaNetworkHal(ISupplicantStaNetwork.asInterface(
                        newNetwork.value.asBinder()));
            } else {
                return null;
            }
        }
    }
    /**
     * Remove network from supplicant with network Id
     * @return true on success of the command
     */
    private boolean removeNetwork(int id) {
        synchronized (mLock) {
            final String methodStr = "removeNetwork";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.removeNetwork(id);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /**
     * @return The ISupplicantNetwork object for the given SupplicantNetworkId int, returns null if
     * the call fails
     */
    private SupplicantStaNetworkHal getNetwork(int id) {
        synchronized (mLock) {
            MutableBoolean statusSuccess = new MutableBoolean(false);
            Mutable<ISupplicantNetwork> gotNetwork = new Mutable<>();
            final String methodStr = "getNetwork";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            try {
                mISupplicantStaIface.getNetwork(id, (SupplicantStatus status,
                        ISupplicantNetwork network) -> {
                    statusSuccess.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (!statusSuccess.value) {
                        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
                    } else {
                        gotNetwork.value = network;
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (statusSuccess.value) {
                return new SupplicantStaNetworkHal(ISupplicantStaNetwork.asInterface(
                        gotNetwork.value.asBinder()));
            } else {
                return null;
            }
        }
    }

    /**
     * @return a list of SupplicantNetworkID ints for all networks controlled by supplicant, returns
     * null if the call fails
     */
    private java.util.ArrayList<Integer> listNetworks() {
        synchronized (mLock) {
            MutableBoolean statusSuccess = new MutableBoolean(false);
            Mutable<ArrayList<Integer>> networkIdList = new Mutable<>();
            final String methodStr = "listNetworks";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            try {
                mISupplicantStaIface.listNetworks((SupplicantStatus status,
                        java.util.ArrayList<Integer> networkIds) -> {
                    statusSuccess.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (!statusSuccess.value) {
                        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
                    } else {
                        networkIdList.value = networkIds;
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (statusSuccess.value) {
                return networkIdList.value;
            } else {
                return null;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean reassociate() {
        synchronized (mLock) {
            final String methodStr = "reassociate";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.reassociate();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean reconnect() {
        synchronized (mLock) {
            final String methodStr = "reconnect";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.reconnect();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean disconnect() {
        synchronized (mLock) {
            final String methodStr = "disconnect";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.disconnect();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean setPowerSave(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setPowerSave";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setPowerSave(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean initiateTdlsDiscover(byte[/* 6 */] macAddress) {
        synchronized (mLock) {
            final String methodStr = "initiateTdlsDiscover";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateTdlsDiscover(macAddress);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean initiateTdlsSetup(byte[/* 6 */] macAddress) {
        synchronized (mLock) {
            final String methodStr = "initiateTdlsSetup";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateTdlsSetup(macAddress);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean initiateTdlsTeardown(byte[/* 6 */] macAddress) {
        synchronized (mLock) {
            final String methodStr = "initiateTdlsTeardown";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateTdlsTeardown(macAddress);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean initiateAnqpQuery(byte[/* 6 */] macAddress,
            java.util.ArrayList<Short> infoElements, java.util.ArrayList<Integer> subTypes) {
        synchronized (mLock) {
            final String methodStr = "initiateAnqpQuery";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateAnqpQuery(macAddress,
                        infoElements, subTypes);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean initiateHs20IconQuery(byte[/* 6 */] macAddress, String fileName) {
        synchronized (mLock) {
            final String methodStr = "initiateHs20IconQuery";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateHs20IconQuery(macAddress,
                        fileName);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /**
     * Makes a callback to HIDL to getMacAddress from supplicant
     * @return string containing the MAC address, or null on a failed call
     */
    private String getMacAddress() {
        synchronized (mLock) {
            MutableBoolean statusSuccess = new MutableBoolean(false);
            final String methodStr = "getMacAddress";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            Mutable<String> gotMac = new Mutable<>();
            try {
                mISupplicantStaIface.getMacAddress((SupplicantStatus status,
                        byte[/* 6 */] macAddr) -> {
                    statusSuccess.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (!statusSuccess.value) {
                        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
                    } else {
                        gotMac.value = String.valueOf(HexEncoding.encode(macAddr));
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception: " + e);
                supplicantServiceDiedHandler();
            }
            if (statusSuccess.value) {
                return gotMac.value;
            } else {
                return null;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean startRxFilter() {
        synchronized (mLock) {
            final String methodStr = "startRxFilter";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.startRxFilter();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean stopRxFilter() {
        synchronized (mLock) {
            final String methodStr = "stopRxFilter";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.stopRxFilter();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean addRxFilter(byte type) {
        synchronized (mLock) {
            final String methodStr = "addRxFilter";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.addRxFilter(type);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean removeRxFilter(byte type) {
        synchronized (mLock) {
            final String methodStr = "removeRxFilter";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.removeRxFilter(type);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean setBtCoexistenceMode(byte mode) {
        synchronized (mLock) {
            final String methodStr = "setBtCoexistenceMode";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setBtCoexistenceMode(mode);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean setBtCoexistenceScanModeEnabled(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setBtCoexistenceScanModeEnabled";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaIface.setBtCoexistenceScanModeEnabled(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean setSuspendModeEnabled(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setSuspendModeEnabled";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setSuspendModeEnabled(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }
    /** See SupplicantStaIface.hal for documentation */
    private boolean setCountryCode(byte[/* 2 */] code) {
        synchronized (mLock) {
            final String methodStr = "setCountryCode";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setCountryCode(code);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + ": exception:" + e);
                supplicantServiceDiedHandler();
                return false;
            }
        }
    }

    /**
     * Returns false if SupplicantStaIface is null, and logs failure to call methodStr
     */
    private boolean checkSupplicantStaIfaceAndLogFailure(final String methodStr) {
        if (DBG) Log.i(TAG, methodStr);
        if (mISupplicantStaIface == null) {
            Log.e(TAG, "Can't call " + methodStr + ", ISupplicantStaIface is null");
            return false;
        }
        return true;
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private static boolean checkStatusAndLogFailure(SupplicantStatus status,
            final String methodStr) {
        if (DBG) Log.i(TAG, methodStr);
        if (status.code != SupplicantStatusCode.SUCCESS) {
            Log.e(TAG, methodStr + " failed: " + supplicantStatusCodeToString(status.code) + ", "
                    + status.debugMessage);
            return false;
        }
        return true;
    }

    /**
     * Converts SupplicantStatus code values to strings for debug logging
     * TODO(b/34811152) Remove this, or make it more break resistance
     */
    public static String supplicantStatusCodeToString(int code) {
        switch (code) {
            case 0:
                return "SUCCESS";
            case 1:
                return "FAILURE_UNKNOWN";
            case 2:
                return "FAILURE_ARGS_INVALID";
            case 3:
                return "FAILURE_IFACE_INVALID";
            case 4:
                return "FAILURE_IFACE_UNKNOWN";
            case 5:
                return "FAILURE_IFACE_EXISTS";
            case 6:
                return "FAILURE_IFACE_DISABLED";
            case 7:
                return "FAILURE_IFACE_NOT_DISCONNECTED";
            case 8:
                return "FAILURE_NETWORK_INVALID";
            case 9:
                return "FAILURE_NETWORK_UNKNOWN";
            default:
                return "??? UNKNOWN_CODE";
        }
    }

    private static class Mutable<E> {
        public E value;

        Mutable() {
            value = null;
        }

        Mutable(E value) {
            this.value = value;
        }
    }
}
