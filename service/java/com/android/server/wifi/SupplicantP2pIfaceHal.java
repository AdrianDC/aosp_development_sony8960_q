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
import android.hardware.wifi.supplicant.V1_0.ISupplicantP2pIfaceCallback;
import android.hardware.wifi.supplicant.V1_0.IfaceType;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatus;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatusCode;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.os.RemoteException;
import android.util.Log;
import android.util.MutableBoolean;

import com.android.server.wifi.util.NativeUtil;

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
    private final IServiceNotification mServiceNotificationCallback =
            new IServiceNotification.Stub() {
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
    private Object mLock = new Object();
    private boolean mServiceCallbackInstalled = false;

    private final WifiMonitor mMonitor;
    private SupplicantP2pIfaceCallback mCallback = null;

    public SupplicantP2pIfaceHal(WifiMonitor monitor) {
        mMonitor = monitor;
    }

    /**
     * Registers a service notification for the ISupplicant service, which triggers intialization of
     * the ISupplicantP2pIface
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
                /* TODO(b/33639391) : Use the new ISupplicant.registerForNotifications() once it
                   exists */
                if (!mIServiceManager.registerForNotifications(
                        ISupplicant.kInterfaceName, "", mServiceNotificationCallback)) {
                    Log.e(TAG, "Failed to register for notifications to "
                            + ISupplicant.kInterfaceName);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }

                // Successful completion by the end of the 'try' block. This will prevent reporting
                // proper initialization after exception is caught.
                return true;
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to register a listener for ISupplicant service: "
                        + e);
                supplicantServiceDiedHandler();
            }
            return false;
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
        }

        if (mISupplicantP2pIface != null && mMonitor != null) {
            // TODO(ender): Get rid of hard-coded interface name, which is
            // assumed to be the group interface name in several other classes
            // ("p2p0" should probably become getName()).
            mCallback = new SupplicantP2pIfaceCallback("p2p0", mMonitor);
            if (!registerCallback(mCallback)) {
                Log.e(TAG, "Callback registration failed. Initialization incomplete.");
                return false;
            }
        }

        return true;
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
     * Signals whether Initialization completed successfully. Only necessary for testing, is not
     * needed to guard calls etc.
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
        if (status == null) {
            Log.w(TAG, operation + " failed: no status code returned.");
        } else if (status.code == SupplicantStatusCode.SUCCESS) {
            logd(operation + " completed successfully.");
        } else {
            Log.w(TAG, operation + " failed: " + status.code + " (" + status.debugMessage + ")");
        }
    }


    /**
     * Returns false if SupplicantP2pIface is null, and logs failure to call methodStr
     */
    private boolean checkSupplicantP2pIfaceAndLogFailure(String method) {
        if (mISupplicantP2pIface == null) {
            Log.e(TAG, "Can't call " + method + ": ISupplicantP2pIface is null");
            return false;
        }
        return true;
    }

    private int wpsInfoToConfigMethod(int info) {
        switch (info) {
            case WpsInfo.PBC:
                return ISupplicantP2pIface.WpsProvisionMethod.PBC;

            case WpsInfo.DISPLAY:
                return ISupplicantP2pIface.WpsProvisionMethod.DISPLAY;

            case WpsInfo.KEYPAD:
            case WpsInfo.LABEL:
                return ISupplicantP2pIface.WpsProvisionMethod.KEYPAD;

            default:
                Log.e(TAG, "Unsupported WPS provision method: " + info);
                return RESULT_NOT_VALID;
        }
    }

    /**
     * Retrieves the name of the network interface.
     *
     * @return name Name of the network interface, e.g., wlan0
     */
    public String getName() {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("getName")) return null;
            SupplicantResult<String> result = new SupplicantResult("getName()");

            try {
                mISupplicantP2pIface.getName(
                        (SupplicantStatus status, String name) -> {
                            result.setResult(status, name);
                        });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.getResult();
        }
    }


    /**
     * Register for callbacks from this interface.
     *
     * These callbacks are invoked for events that are specific to this interface.
     * Registration of multiple callback objects is supported. These objects must
     * be automatically deleted when the corresponding client process is dead or
     * if this interface is removed.
     *
     * @param callback An instance of the |ISupplicantP2pIfaceCallback| HIDL
     *        interface object.
     * @return boolean value indicating whether operation was successful.
     */
    public boolean registerCallback(ISupplicantP2pIfaceCallback receiver) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("registerCallback")) return false;
            SupplicantResult<Void> result = new SupplicantResult("registerCallback()");
            try {
                result.setResult(mISupplicantP2pIface.registerCallback(receiver));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.isSuccess();
        }
    }


    /**
     * Initiate a P2P service discovery with a (optional) timeout.
     *
     * @param timeoutInSec Max time to be spent is peforming discovery.
     *        Set to 0 to indefinely continue discovery untill and explicit
     *        |stopFind| is sent.
     * @return boolean value indicating whether operation was successful.
     */
    public boolean find(int timeout) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("find")) return false;

            if (timeout < 0) {
                Log.e(TAG, "Invalid timeout value: " + timeout);
                return false;
            }
            SupplicantResult<Void> result = new SupplicantResult("find(" + timeout + ")");
            try {
                result.setResult(mISupplicantP2pIface.find(timeout));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.isSuccess();
        }
    }

    public boolean find() {
        return find(0);
    }


    /**
     * Stop an ongoing P2P service discovery.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean stopFind() {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("stopFind")) return false;
            SupplicantResult<Void> result = new SupplicantResult("stopFind()");
            try {
                result.setResult(mISupplicantP2pIface.stopFind());
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.isSuccess();
        }
    }


    /**
     * Flush P2P peer table and state.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean flush() {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("flush")) return false;
            SupplicantResult<Void> result = new SupplicantResult("flush()");
            try {
                result.setResult(mISupplicantP2pIface.flush());
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.isSuccess();
        }
    }


    /**
     * This command can be used to flush all services from the
     * device.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean serviceFlush() {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("serviceFlush")) return false;
            SupplicantResult<Void> result = new SupplicantResult("serviceFlush()");
            try {
                result.setResult(mISupplicantP2pIface.flushServices());
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.isSuccess();
        }
    }


    /**
     * Turn on/off power save mode for the interface.
     *
     * @param groupIfName Group interface name to use.
     * @param enable Indicate if power save is to be turned on/off.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean setPowerSave(String groupIfName, boolean enable) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("setPowerSave")) return false;
            SupplicantResult<Void> result = new SupplicantResult(
                    "setPowerSave(" + groupIfName + ", " + enable + ")");
            try {
                result.setResult(mISupplicantP2pIface.setPowerSave(groupIfName, enable));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.isSuccess();
        }
    }


    /**
     * Set the Maximum idle time in seconds for P2P groups.
     * This value controls how long a P2P group is maintained after there
     * is no other members in the group. As a group owner, this means no
     * associated stations in the group. As a P2P client, this means no
     * group owner seen in scan results.
     *
     * @param groupIfName Group interface name to use.
     * @param timeoutInSec Timeout value in seconds.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean setGroupIdle(String groupIfName, int timeoutInSec) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("setGroupIdle")) return false;
            // Basic checking here. Leave actual parameter validation to supplicant.
            if (timeoutInSec < 0) {
                Log.e(TAG, "Invalid group timeout value " + timeoutInSec);
                return false;
            }

            SupplicantResult<Void> result = new SupplicantResult(
                    "setGroupIdle(" + groupIfName + ", " + timeoutInSec+ ")");
            try {
                result.setResult(mISupplicantP2pIface.setGroupIdle(groupIfName, timeoutInSec));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.isSuccess();
        }
    }


    /**
     * Set the postfix to be used for P2P SSID's.
     *
     * @param postfix String to be appended to SSID.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean setSsidPostfix(String postfix) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("setSsidPostfix")) return false;
            // Basic checking here. Leave actual parameter validation to supplicant.
            if (postfix == null) {
                Log.e(TAG, "Invalid SSID postfix value (null).");
                return false;
            }

            SupplicantResult<Void> result = new SupplicantResult("setSsidPostfix(" + postfix + ")");
            try {
                result.setResult(mISupplicantP2pIface.setSsidPostfix(
                        NativeUtil.decodeSsid("\"" + postfix + "\"")));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            } catch (Exception e) {
                Log.e(TAG, "Could not decode SSID.", e);
                return false;
            }

            return result.isSuccess();
        }
    }


    /**
     * Start P2P group formation with a discovered P2P peer. This includes
     * optional group owner negotiation, group interface setup, provisioning,
     * and establishing data connection.
     *
     * @param config Configuration to use to connect to remote device.
     * @param joinExistingGroup Indicates that this is a command to join an
     *        existing group as a client. It skips the group owner negotiation
     *        part. This must send a Provision Discovery Request message to the
     *        target group owner before associating for WPS provisioning.
     *
     * @return String containing generated pin, if selected provision method
     *        uses PIN.
     */
    public String connect(WifiP2pConfig config, boolean joinExistingGroup) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("setSsidPostfix")) return null;

            if (config == null) {
                Log.e(TAG, "Could not connect: null config.");
                return null;
            }

            if (config.deviceAddress == null) {
                Log.e(TAG, "Could not parse null mac address.");
                return null;
            }

            byte[] peerAddress = null;
            try {
                peerAddress = NativeUtil.macAddressToByteArray(config.deviceAddress);
            } catch (Exception e) {
                Log.e(TAG, "Could not parse peer mac address.", e);
                return null;
            }

            int provisionMethod = wpsInfoToConfigMethod(config.wps.setup);
            if (provisionMethod == RESULT_NOT_VALID) {
                Log.e(TAG, "Invalid WPS config method: " + config.wps.setup);
                return null;
            }

            // NOTE: preSelectedPin cannot be null, otherwise hal would crash.
            String preSelectedPin = "";
            if (provisionMethod == ISupplicantP2pIface.WpsProvisionMethod.DISPLAY) {
                preSelectedPin = config.wps.pin;
                if (preSelectedPin == null) {
                    Log.e(TAG, "PIN must be supplied when provision method is DISPLAY.");
                    return null;
                }
            }

            boolean persistent = (config.netId == WifiP2pGroup.PERSISTENT_NET_ID);

            int goIntent = 0;
            if (!joinExistingGroup) {
                int groupOwnerIntent = config.groupOwnerIntent;
                if (groupOwnerIntent < 0 || groupOwnerIntent > 15) {
                    groupOwnerIntent = DEFAULT_GROUP_OWNER_INTENT;
                }
            }

            SupplicantResult<String> result = new SupplicantResult(
                    "connect(" + config.deviceAddress + ")");
            try {
                mISupplicantP2pIface.connect(
                        peerAddress, provisionMethod, preSelectedPin, joinExistingGroup,
                        persistent, goIntent,
                        (SupplicantStatus status, String generatedPin) -> {
                            result.setResult(status, generatedPin);
                        });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.getResult();
        }
    }

    /**
     * Cancel an ongoing P2P group formation and joining-a-group related
     * operation. This operation unauthorizes the specific peer device (if any
     * had been authorized to start group formation), stops P2P find (if in
     * progress), stops pending operations for join-a-group, and removes the
     * P2P group interface (if one was used) that is in the WPS provisioning
     * step. If the WPS provisioning step has been completed, the group is not
     * terminated.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean cancelConnect() {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("cancelConnect")) return false;
            SupplicantResult<Void> result = new SupplicantResult("cancelConnect()");
            try {
                result.setResult(mISupplicantP2pIface.cancelConnect());
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.isSuccess();
        }
    }


    /**
     * Send P2P provision discovery request to the specified peer. The
     * parameters for this command are the P2P device address of the peer and the
     * desired configuration method.
     *
     * @param config Config class describing peer setup.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean provisionDiscovery(WifiP2pConfig config) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("provisionDiscovery")) return false;

            int targetMethod = wpsInfoToConfigMethod(config.wps.setup);
            if (targetMethod == -1) {
                Log.e(TAG, "Unrecognized WPS configuration method: " + config.wps.setup);
                return false;
            }

            if (config.deviceAddress == null) {
                Log.e(TAG, "Cannot parse null mac address.");
                return false;
            }
            byte[] macAddress = null;
            try {
                macAddress = NativeUtil.macAddressToByteArray(config.deviceAddress);
            } catch (Exception e) {
                Log.e(TAG, "Could not parse peer mac address.", e);
                return false;
            }

            SupplicantResult<Void> result = new SupplicantResult(
                    "provisionDiscovery(" + config.deviceAddress + ", " + config.wps.setup + ")");
            try {
                result.setResult(mISupplicantP2pIface.provisionDiscovery(macAddress, targetMethod));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            return result.isSuccess();
        }
    }


    /**
     * Invite a device to a persistent group.
     * If the peer device is the group owner of the persistent group, the peer
     * parameter is not needed. Otherwise it is used to specify which
     * device to invite. |goDeviceAddress| parameter may be used to override
     * the group owner device address for Invitation Request should it not be
     * known for some reason (this should not be needed in most cases).
     *
     * @param groupIfName Group interface name to use.
     * @param goDeviceAddress MAC address of the group owner device.
     * @param peerAddress MAC address of the device to invite.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean invite(WifiP2pGroup group, String peerAddress) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("invite")) return false;
            if (group == null) {
                Log.e(TAG, "Cannot invite to null group.");
                return false;
            }

            if (group.getOwner() == null) {
                Log.e(TAG, "Cannot invite to group with null owner.");
                return false;
            }

            if (group.getOwner().deviceAddress == null) {
                Log.e(TAG, "Group owner has no mac address.");
                return false;
            }

            byte[] ownerMacAddress = null;
            try {
                ownerMacAddress = NativeUtil.macAddressToByteArray(group.getOwner().deviceAddress);
            } catch (Exception e) {
                Log.e(TAG, "Group owner mac address parse error.", e);
                return false;
            }

            if (peerAddress == null) {
                Log.e(TAG, "Cannot parse peer mac address.");
                return false;
            }

            byte[] peerMacAddress;
            try {
                peerMacAddress = NativeUtil.macAddressToByteArray(peerAddress);
            } catch (Exception e) {
                Log.e(TAG, "Peer mac address parse error.", e);
                return false;
            }

            SupplicantResult<Void> result = new SupplicantResult(
                    "invite(" + group.getInterface() + ", " +
                    group.getOwner().deviceAddress + ", " + peerAddress + ")");
            try {
                result.setResult(mISupplicantP2pIface.invite(
                        group.getInterface(), ownerMacAddress, peerMacAddress));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.isSuccess();
        }
    }


    /**
     * Reject connection attempt from a peer (specified with a device
     * address). This is a mechanism to reject a pending group owner negotiation
     * with a peer and request to automatically block any further connection or
     * discovery of the peer.
     *
     * @param peerAddress MAC address of the device to reject.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean reject(String peerAddress) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("reject")) return false;

            if (peerAddress == null) {
                Log.e(TAG, "Cannot parse rejected peer's mac address.");
                return false;
            }
            byte[] macAddress = null;
            try {
                macAddress = NativeUtil.macAddressToByteArray(peerAddress);
            } catch (Exception e) {
                Log.e(TAG, "Could not parse peer mac address.", e);
                return false;
            }

            SupplicantResult<Void> result =
                    new SupplicantResult("reject(" + peerAddress + ")");
            try {
                result.setResult(mISupplicantP2pIface.reject(macAddress));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            return result.isSuccess();
        }
    }


    /**
     * Gets the MAC address of the device.
     *
     * @return MAC address of the device.
     */
    public String getDeviceAddress() {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("getDeviceAddress")) return null;
            SupplicantResult<String> result = new SupplicantResult("getDeviceAddress()");
            try {
                mISupplicantP2pIface.getDeviceAddress((SupplicantStatus status, byte[] address) -> {
                    String parsedAddress = null;
                    try {
                        parsedAddress = NativeUtil.macAddressFromByteArray(address);
                    } catch (Exception e) {
                        Log.e(TAG, "Could not process reported address.", e);
                    }
                    result.setResult(status, parsedAddress);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
                return null;
            }

            return result.getResult();
        }
    }


    /**
     * Gets the operational SSID of the device.
     *
     * @param peerAddress MAC address of the peer.
     *
     * @return SSID of the device.
     */
    public String getSsid(String address) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("getSsid")) return null;

            if (address == null) {
                Log.e(TAG, "Cannot parse peer mac address.");
                return null;
            }
            byte[] macAddress = null;
            try {
                macAddress = NativeUtil.macAddressToByteArray(address);
            } catch (Exception e) {
                Log.e(TAG, "Could not parse mac address.", e);
                return null;
            }

            SupplicantResult<String> result =
                    new SupplicantResult("getSsid(" + address + ")");
            try {
                mISupplicantP2pIface.getSsid(
                        macAddress, (SupplicantStatus status, ArrayList<Byte> ssid) -> {
                            String ssidString = null;
                            if (ssid != null) {
                                try {
                                    ssidString = NativeUtil.encodeSsid(ssid);
                                } catch (Exception e) {
                                    Log.e(TAG, "Could not encode SSID.", e);
                                }
                            }
                            result.setResult(status, ssidString);
                        });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
                return null;
            }

            return result.getResult();
        }
    }


    /**
     * Reinvoke a device from a persistent group.
     *
     * @param networkId Used to specify the persistent group.
     * @param peerAddress MAC address of the device to reinvoke.
     *
     * @return true, if operation was successful.
     */
    public boolean reinvoke(int networkId, String peerAddress) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("reinvoke")) return false;
            if (peerAddress == null) {
                Log.e(TAG, "Cannot parse peer mac address.");
                return false;
            }
            byte[] macAddress = null;
            try {
                macAddress = NativeUtil.macAddressToByteArray(peerAddress);
            } catch (Exception e) {
                Log.e(TAG, "Could not parse mac address.", e);
                return false;
            }

            SupplicantResult<Void> result = new SupplicantResult(
                    "reinvoke(" + networkId + ", " + peerAddress + ")");
            try {
                result.setResult(mISupplicantP2pIface.reinvoke(networkId, macAddress));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            return result.isSuccess();
        }
    }


    /**
     * Set up a P2P group owner manually (i.e., without group owner
     * negotiation with a specific peer). This is also known as autonomous
     * group owner.
     *
     * @param networkId Used to specify the restart of a persistent group.
     * @param isPersistent Used to request a persistent group to be formed.
     *
     * @return true, if operation was successful.
     */
    public boolean groupAdd(int networkId, boolean isPersistent) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("groupAdd")) return false;
            SupplicantResult<Void> result =
                    new SupplicantResult("groupAdd(" + networkId + ", " + isPersistent + ")");
            try {
                result.setResult(mISupplicantP2pIface.addGroup(isPersistent, networkId));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.isSuccess();
        }
    }

    /**
     * Set up a P2P group owner manually.
     * This is a helper method that invokes groupAdd(networkId, isPersistent) internally.
     *
     * @param isPersistent Used to request a persistent group to be formed.
     *
     * @return true, if operation was successful.
     */
    public boolean groupAdd(boolean isPersistent) {
        // Supplicant expects networkId to be -1 if not supplied.
        return groupAdd(-1, isPersistent);
    }


    /**
     * Terminate a P2P group. If a new virtual network interface was used for
     * the group, it must also be removed. The network interface name of the
     * group interface is used as a parameter for this command.
     *
     * @param groupIfName Group interface name to use.
     *
     * @return true, if operation was successful.
     */
    public boolean groupRemove(String groupName) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("groupRemove")) return false;
            SupplicantResult<Void> result = new SupplicantResult("groupRemove(" + groupName + ")");
            try {
                result.setResult(mISupplicantP2pIface.removeGroup(groupName));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.isSuccess();
        }
    }


    /**
     * Gets the capability of the group which the device is a
     * member of.
     *
     * @param peerAddress MAC address of the peer.
     *
     * @return combination of |GroupCapabilityMask| values.
     */
    public int getGroupCapability(String peerAddress) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("getGroupCapability")) {
                return RESULT_NOT_VALID;
            }

            if (peerAddress == null) {
                Log.e(TAG, "Cannot parse peer mac address.");
                return RESULT_NOT_VALID;
            }
            byte[] macAddress = null;
            try {
                macAddress = NativeUtil.macAddressToByteArray(peerAddress);
            } catch (Exception e) {
                Log.e(TAG, "Could not parse group address.", e);
                return RESULT_NOT_VALID;
            }

            SupplicantResult<Integer> capability = new SupplicantResult(
                    "getGroupCapability(" + peerAddress + ")");
            try {
                mISupplicantP2pIface.getGroupCapability(
                        macAddress, (SupplicantStatus status, int cap) -> {
                            capability.setResult(status, cap);
                        });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            if (!capability.isSuccess()) {
                return RESULT_NOT_VALID;
            }

            return capability.getResult();
        }
    }


    /**
     * Configure Extended Listen Timing.
     *
     * If enabled, listen state must be entered every |intervalInMillis| for at
     * least |periodInMillis|. Both values have acceptable range of 1-65535
     * (with interval obviously having to be larger than or equal to duration).
     * If the P2P module is not idle at the time the Extended Listen Timing
     * timeout occurs, the Listen State operation must be skipped.
     *
     * @param enable Enables or disables listening.
     * @param periodInMillis Period in milliseconds.
     * @param intervalInMillis Interval in milliseconds.
     *
     * @return true, if operation was successful.
     */
    public boolean configureExtListen(boolean enable, int periodInMillis, int intervalInMillis) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("configureExtListen")) return false;

            // If listening is disabled, wpa supplicant expects zeroes.
            if (!enable) {
                periodInMillis = 0;
                intervalInMillis = 0;
            }

            // Verify that the integers are not negative. Leave actual parameter validation to
            // supplicant.
            if (periodInMillis < 0 || intervalInMillis < 0) {
                Log.e(TAG, "Invalid parameters supplied to configureExtListen: " +
                        periodInMillis + ", " + intervalInMillis);
                return false;
            }

            SupplicantResult<Void> result = new SupplicantResult(
                    "configureExtListen(" + periodInMillis + ", " + intervalInMillis + ")");
            try {
                result.setResult(
                        mISupplicantP2pIface.configureExtListen(periodInMillis, intervalInMillis));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            return result.isSuccess();
        }
    }


    /**
     * Set P2P Listen channel.
     *
     * When specifying a social channel on the 2.4 GHz band (1/6/11) there is no
     * need to specify the operating class since it defaults to 81. When
     * specifying a social channel on the 60 GHz band (2), specify the 60 GHz
     * operating class (180).
     *
     * @param channel Wifi channel. eg, 1, 6, 11.
     * @param operatingClass Operating Class indicates the channel set of the AP
     *        indicated by this BSSID
     *
     * @return true, if operation was successful.
     */
    public boolean setListenChannel(int channel, int operatingClass) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("setListenChannel")) return false;
            // Verify that the integers are not negative. Leave actual parameter validation to
            // supplicant.
            if (channel < 0 || operatingClass < 0) {
                Log.e(TAG, "Invalid values supplied to setListenChannel: " +
                        channel + ", " + operatingClass);
                return false;
            }

            SupplicantResult<Void> result = new SupplicantResult(
                    "setListenChannel(" + channel + ", " + operatingClass + ")");
            try {
                result.setResult(mISupplicantP2pIface.setListenChannel(channel, operatingClass));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }
            return result.isSuccess();
        }
    }


    /**
     * This command can be used to add a upnp/bonjour service.
     *
     * @param query Hex dump of the query data.
     * @param return Hex dump of the response data.
     *
     * @return true, if operation was successful.
     */
    public boolean serviceAdd(WifiP2pServiceInfo servInfo) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("serviceAdd")) return false;

            if (servInfo == null) {
                Log.e(TAG, "Null service info passed.");
                return false;
            }

            for (String s : servInfo.getSupplicantQueryList()) {
                if (s == null) {
                    Log.e(TAG, "Invalid service description (null).");
                    return false;
                }

                String[] data = s.split(" ");
                if (data.length < 3) {
                    Log.e(TAG, "Service specification invalid: " + s);
                    return false;
                }

                SupplicantResult<Void> result = null;
                try {
                    if ("upnp".equals(data[0])) {
                        int version = 0;
                        try {
                            version = Integer.parseInt(data[1]);
                        } catch (NumberFormatException e) {
                            Log.e(TAG, "UPnP Service specification invalid: " + s, e);
                            return false;
                        }

                        result = new SupplicantResult(
                                "addUpnpService(" + data[1] + ", " + data[2] + ")");
                        result.setResult(mISupplicantP2pIface.addUpnpService(version, data[2]));
                    } else if ("bonjour".equals(data[0])) {
                        if (data[1] != null && data[2] != null) {
                            ArrayList<Byte> request = null;
                            ArrayList<Byte> response = null;
                            try {
                                request = NativeUtil.byteArrayToArrayList(
                                        NativeUtil.hexStringToByteArray(data[1]));
                                response = NativeUtil.byteArrayToArrayList(
                                        NativeUtil.hexStringToByteArray(data[2]));
                            } catch (Exception e) {
                                Log.e(TAG, "Invalid bonjour service description.");
                                return false;
                            }
                            result = new SupplicantResult(
                                    "addBonjourService(" + data[1] + ", " + data[2] + ")");
                            result.setResult(
                                    mISupplicantP2pIface.addBonjourService(request, response));
                        }
                    } else {
                        return false;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                    supplicantServiceDiedHandler();
                }

                if (result == null || !result.isSuccess()) return false;
            }

            return true;
        }
    }


    /**
     * This command can be used to remove a upnp/bonjour service.
     *
     * @param query Hex dump of the query data.
     *
     * @return true, if operation was successful.
     */
    public boolean serviceRemove(WifiP2pServiceInfo servInfo) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("serviceRemove")) return false;

            if (servInfo == null) {
                Log.e(TAG, "Null service info passed.");
                return false;
            }

            for (String s : servInfo.getSupplicantQueryList()) {
                if (s == null) {
                    Log.e(TAG, "Invalid service description (null).");
                    return false;
                }

                String[] data = s.split(" ");
                if (data.length < 3) {
                    Log.e(TAG, "Service specification invalid: " + s);
                    return false;
                }

                SupplicantResult<Void> result = null;
                try {
                    if ("upnp".equals(data[0])) {
                        int version = 0;
                        try {
                            version = Integer.parseInt(data[1]);
                        } catch (NumberFormatException e) {
                            Log.e(TAG, "UPnP Service specification invalid: " + s, e);
                            return false;
                        }
                        result = new SupplicantResult(
                                "removeUpnpService(" + data[1] + ", " + data[2] + ")");
                        result.setResult(mISupplicantP2pIface.removeUpnpService(version, data[2]));
                    } else if ("bonjour".equals(data[0])) {
                        if (data[1] != null) {
                            ArrayList<Byte> request = null;
                            try {
                                request = NativeUtil.byteArrayToArrayList(
                                    NativeUtil.hexStringToByteArray(data[1]));
                            } catch (Exception e) {
                                Log.e(TAG, "Invalid bonjour service description.");
                                return false;
                            }
                            result = new SupplicantResult("removeBonjourService(" + data[1] + ")");
                            result.setResult(mISupplicantP2pIface.removeBonjourService(request));
                        }
                    } else {
                        Log.e(TAG, "Unknown / unsupported P2P service requested: " + data[0]);
                        return false;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                    supplicantServiceDiedHandler();
                }

                if (result == null || !result.isSuccess()) return false;
            }

            return true;
        }
    }


    /**
     * Schedule a P2P service discovery request. The parameters for this command
     * are the device address of the peer device (or 00:00:00:00:00:00 for
     * wildcard query that is sent to every discovered P2P peer that supports
     * service discovery) and P2P Service Query TLV(s) as hexdump.
     *
     * @param peerAddress MAC address of the device to discover.
     * @param query Hex dump of the query data.
     * @return identifier Identifier for the request. Can be used to cancel the
     *         request.
     */
    public String requestServiceDiscovery(String peerAddress, String query) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("requestServiceDiscovery")) return null;

            if (peerAddress == null) {
                Log.e(TAG, "Cannot parse peer mac address.");
                return null;
            }
            byte[] macAddress = null;
            try {
                macAddress = NativeUtil.macAddressToByteArray(peerAddress);
            } catch (Exception e) {
                Log.e(TAG, "Could not process peer MAC address.", e);
                return null;
            }

            if (query == null) {
                Log.e(TAG, "Cannot parse service discovery query: " + query);
                return null;
            }
            ArrayList<Byte> binQuery = null;
            try {
                binQuery = NativeUtil.byteArrayToArrayList(NativeUtil.hexStringToByteArray(query));
            } catch (Exception e) {
                Log.e(TAG, "Could not parse service query.", e);
                return null;
            }

            SupplicantResult<Long> result = new SupplicantResult(
                    "requestServiceDiscovery(" + peerAddress + ", " + query + ")");
            try {
                mISupplicantP2pIface.requestServiceDiscovery(
                        macAddress, binQuery,
                        (SupplicantStatus status, long identifier) -> {
                            result.setResult(status, new Long(identifier));
                        });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            Long value = result.getResult();
            if (value == null) return null;
            return value.toString();
        }
    }


    /**
     * Cancel a previous service discovery request.
     *
     * @param identifier Identifier for the request to cancel.
     * @return true, if operation was successful.
     */
    public boolean cancelServiceDiscovery(String identifier) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("cancelServiceDiscovery")) return false;
            if (identifier == null) {
                Log.e(TAG, "cancelServiceDiscovery requires a valid tag.");
                return false;
            }

            int id = 0;
            try {
                id = Integer.parseInt(identifier);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Service discovery identifier invalid: " + identifier, e);
                return false;
            }

            SupplicantResult<Void> result = new SupplicantResult(
                    "cancelServiceDiscovery(" + identifier + ")");
            try {
                result.setResult(mISupplicantP2pIface.cancelServiceDiscovery(id));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            return result.isSuccess();
        }
    }


    /**
     * Send driver command to set Miracast mode.
     *
     * @param mode Mode of Miracast.
     * @return true, if operation was successful.
     */
    public boolean setMiracastMode(int mode) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("setMiracastMode")) return false;
            byte targetMode = ISupplicantP2pIface.MiracastMode.DISABLED;

            switch (mode) {
                case WifiP2pManager.MIRACAST_SOURCE:
                    targetMode = ISupplicantP2pIface.MiracastMode.SOURCE;
                    break;

                case WifiP2pManager.MIRACAST_SINK:
                    targetMode = ISupplicantP2pIface.MiracastMode.SINK;
                    break;
            }

            SupplicantResult<Void> result = new SupplicantResult(
                    "setMiracastMode(" + mode + ")");
            try {
                result.setResult(mISupplicantP2pIface.setMiracastMode(targetMode));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            return result.isSuccess();
        }
    }


    /**
     * Initiate WPS Push Button setup.
     * The PBC operation requires that a button is also pressed at the
     * AP/Registrar at about the same time (2 minute window).
     *
     * @param groupIfName Group interface name to use.
     * @param bssid BSSID of the AP. Use zero'ed bssid to indicate wildcard.
     * @return true, if operation was successful.
     */
    public boolean startWpsPbc(String groupIfName, String bssid) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("startWpsPbc")) return false;
            if (groupIfName == null) {
                Log.e(TAG, "Group name required when requesting WPS PBC.");
                return false;
            }

            // Null values should be fine, since bssid can be empty.
            byte[] macAddress = null;
            if (bssid != null) {
                try {
                    macAddress = NativeUtil.macAddressToByteArray(bssid);
                } catch (Exception e) {
                    Log.e(TAG, "Could not parse BSSID.", e);
                    return false;
                }
            }

            SupplicantResult<Void> result = new SupplicantResult(
                    "startWpsPbc(" + groupIfName + ", " + bssid + ")");
            try {
                result.setResult(mISupplicantP2pIface.startWpsPbc(groupIfName, macAddress));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            return result.isSuccess();
        }
    }


    /**
     * Initiate WPS Pin Keypad setup.
     *
     * @param groupIfName Group interface name to use.
     * @param pin 8 digit pin to be used.
     * @return true, if operation was successful.
     */
    public boolean startWpsPinKeypad(String groupIfName, String pin) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("startWpsPinKeypad")) return false;
            if (groupIfName == null) {
                Log.e(TAG, "Group name required when requesting WPS KEYPAD.");
                return false;
            }
            if (pin == null) {
                Log.e(TAG, "PIN required when requesting WPS KEYPAD.");
                return false;
            }

            SupplicantResult<Void> result = new SupplicantResult(
                    "startWpsPinKeypad(" + groupIfName + ", " + pin + ")");
            try {
                result.setResult(mISupplicantP2pIface.startWpsPinKeypad(groupIfName, pin));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            return result.isSuccess();
        }
    }


    /**
     * Initiate WPS Pin Display setup.
     *
     * @param groupIfName Group interface name to use.
     * @param bssid BSSID of the AP. Use zero'ed bssid to indicate wildcard.
     * @return true, if operation was successful.
     */
    public String startWpsPinDisplay(String groupIfName, String bssid) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("startWpsPinDisplay")) return null;
            if (groupIfName == null) {
                Log.e(TAG, "Group name required when requesting WPS KEYPAD.");
                return null;
            }

            // Null values should be fine, since bssid can be empty.
            byte[] macAddress = null;
            if (bssid != null) {
                try {
                    macAddress = NativeUtil.macAddressToByteArray(bssid);
                } catch (Exception e) {
                    Log.e(TAG, "Could not parse BSSID.", e);
                    return null;
                }
            }

            SupplicantResult<String> result = new SupplicantResult(
                    "startWpsPinDisplay(" + groupIfName + ", " + bssid + ")");
            try {
                mISupplicantP2pIface.startWpsPinDisplay(
                        groupIfName, macAddress,
                        (SupplicantStatus status, String generatedPin) -> {
                            result.setResult(status, generatedPin);
                        });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            return result.getResult();
        }
    }


    /**
     * Cancel any ongoing WPS operations.
     *
     * @param groupIfName Group interface name to use.
     * @return true, if operation was successful.
     */
    public boolean cancelWps(String groupIfName) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("cancelWps")) return false;
            if (groupIfName == null) {
                Log.e(TAG, "Group name required when requesting WPS KEYPAD.");
                return false;
            }

            SupplicantResult<Void> result = new SupplicantResult(
                    "cancelWps(" + groupIfName + ")");
            try {
                result.setResult(mISupplicantP2pIface.cancelWps(groupIfName));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            return result.isSuccess();
        }
    }


    /**
     * Enable/Disable Wifi Display.
     *
     * @param enable true to enable, false to disable.
     * @return true, if operation was successful.
     */
    public boolean enableWfd(boolean enable) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("enableWfd")) return false;

            SupplicantResult<Void> result = new SupplicantResult(
                    "enableWfd(" + enable + ")");
            try {
                result.setResult(mISupplicantP2pIface.enableWfd(enable));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            return result.isSuccess();
        }
    }


    /**
     * Set Wifi Display device info.
     *
     * @param info WFD device info as described in section 5.1.2 of WFD technical
     *        specification v1.0.0.
     * @return true, if operation was successful.
     */
    public boolean setWfdDeviceInfo(String info) {
        synchronized (mLock) {
            if (!checkSupplicantP2pIfaceAndLogFailure("setWfdDeviceInfo")) return false;

            if (info == null) {
                Log.e(TAG, "Cannot parse null WFD info string.");
                return false;
            }
            byte[] wfdInfo = null;
            try {
                wfdInfo = NativeUtil.hexStringToByteArray(info);
            } catch (Exception e) {
                Log.e(TAG, "Could not parse WFD Device Info string.");
                return false;
            }

            SupplicantResult<Void> result = new SupplicantResult(
                    "setWfdDeviceInfo(" + info + ")");
            try {
                result.setResult(mISupplicantP2pIface.setWfdDeviceInfo(wfdInfo));
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantP2pIface exception: " + e);
                supplicantServiceDiedHandler();
            }

            return result.isSuccess();
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
            logd("leaving " + mMethodName + " with result = " + value);
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
