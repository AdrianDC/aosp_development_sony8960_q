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

import android.hardware.wifi.V1_0.IWifi;
import android.hardware.wifi.V1_0.IWifiApIface;
import android.hardware.wifi.V1_0.IWifiChip;
import android.hardware.wifi.V1_0.IWifiEventCallback;
import android.hardware.wifi.V1_0.IWifiIface;
import android.hardware.wifi.V1_0.IWifiNanIface;
import android.hardware.wifi.V1_0.IWifiP2pIface;
import android.hardware.wifi.V1_0.IWifiRttController;
import android.hardware.wifi.V1_0.IWifiStaIface;
import android.hardware.wifi.V1_0.IfaceType;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.MutableInt;
import android.util.SparseArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Handles device management through the HAL (HIDL) interface.
 */
public class HalDeviceManager {
    private static final String TAG = "HalDeviceManager";
    private static final boolean DBG = true;

    // public API
    public HalDeviceManager() {
        mInterfaceAvailableForRequestListeners.put(IfaceType.STA, new HashSet<>());
        mInterfaceAvailableForRequestListeners.put(IfaceType.AP, new HashSet<>());
        mInterfaceAvailableForRequestListeners.put(IfaceType.P2P, new HashSet<>());
        mInterfaceAvailableForRequestListeners.put(IfaceType.NAN, new HashSet<>());
    }

    /**
     * Actually starts the HalDeviceManager: separate from constructor since may want to phase
     * at a later time.
     *
     * TODO: if decide that no need for separating construction from initialization (e.g. both are
     * done at injector) then move to constructor.
     */
    public void initialize() {
        initializeInternal();
    }

    /**
     * Register a ManagerStatusCallback to get information about status of Wi-Fi. Use the
     * isStarted() method to check status immediately after registration - don't expect callbacks
     * for current status (no 'sticky' behavior).
     *
     * It is safe to re-register the same callback object - duplicates are detected and only a
     * single copy kept.
     *
     * @param callback ManagerStatusCallback callback object.
     * @param looper Looper on which to dispatch callbacks. Null implies current looper.
     */
    public void registerStatusCallback(ManagerStatusCallback callback, Looper looper) {
        synchronized (mLock) {
            if (!mManagerStatusCallbacks.add(new ManagerStatusCallbackProxy(callback,
                    looper == null ? Looper.myLooper() : looper))) {
                Log.w(TAG, "registerStatusCallback: duplicate registration ignored");
            }
        }
    }

    /**
     * Returns the current status of Wi-Fi: started (true) or stopped (false).
     *
     * Note: direct call to HIDL.
     */
    public boolean isStarted() {
        return isWifiStarted();
    }

    /**
     * Attempts to start Wi-Fi (using HIDL). Returns the success (true) or failure (false) or
     * the start operation. Will also dispatch any registered ManagerStatusCallback.onStart() on
     * success.
     *
     * Note: direct call to HIDL.
     */
    public boolean start() {
        return startWifi();
    }

    /**
     * Stops Wi-Fi. Will also dispatch any registeredManagerStatusCallback.onStop().
     *
     * Note: direct call to HIDL - failure is not-expected.
     */
    public void stop() {
        stopWifi();
    }

    /**
     * HAL device manager status callbacks.
     */
    public interface ManagerStatusCallback {
        /**
         * Indicates that Wi-Fi is up.
         */
        void onStart();

        /**
         * Indicates that Wi-Fi is down.
         */
        void onStop();
    }

    // interface-specific behavior

    /**
     * Create a STA interface if possible. Changes chip mode and removes conflicting interfaces if
     * needed and permitted by priority.
     *
     * @param destroyedListener Optional (nullable) listener to call when the allocated interface
     *                          is removed. Will only be registered and used if an interface is
     *                          created successfully.
     * @param availableForRequestListener Optional (nullable) listener to call when an interface of
     *                                    the requested type could be created - only used if this
     *                                    function was not able to create the requested interface -
     *                                    i.e. a null was returned.
     * @param looper The looper on which to dispatch the listeners. A null value indicates the
     *               current thread.
     * @return A newly created interface - or null if the interface could not be created.
     */
    public IWifiStaIface createStaIface(InterfaceDestroyedListener destroyedListener,
            InterfaceAvailableForRequestListener availableForRequestListener,
            Looper looper) {
        return (IWifiStaIface) createIface(IfaceType.STA, destroyedListener,
                availableForRequestListener, looper);
    }

    /**
     * Create AP interface if possible (see createStaIface doc).
     */
    public IWifiApIface createApIface(InterfaceDestroyedListener destroyedListener,
            InterfaceAvailableForRequestListener availableForRequestListener,
            Looper looper) {
        return (IWifiApIface) createIface(IfaceType.AP, destroyedListener,
                availableForRequestListener, looper);
    }

    /**
     * Create P2P interface if possible (see createStaIface doc).
     */
    public IWifiP2pIface createP2pIface(InterfaceDestroyedListener destroyedListener,
            InterfaceAvailableForRequestListener availableForRequestListener,
            Looper looper) {
        return (IWifiP2pIface) createIface(IfaceType.P2P, destroyedListener,
                availableForRequestListener, looper);
    }

    /**
     * Create NAN interface if possible (see createStaIface doc).
     */
    public IWifiNanIface createNanIface(InterfaceDestroyedListener destroyedListener,
            InterfaceAvailableForRequestListener availableForRequestListener,
            Looper looper) {
        return (IWifiNanIface) createIface(IfaceType.NAN, destroyedListener,
                availableForRequestListener, looper);
    }

    /**
     * Removes (releases/destroys) the input interface. Will trigger any registered
     * InterfaceDestroyedListeners and possibly some InterfaceAvailableForRequestListeners if we
     * can potentially create some other interfaces as a result of removing this interface.
     */
    public boolean removeIface(IWifiIface iface) {
        return removeIfaceInternal(iface);
    }

    /**
     * Returns the IWifiChip corresponding to the specified interface (or null on error).
     *
     * Note: clients must not perform chip mode changes or interface management (create/delete)
     * operations on IWifiChip directly. However, they can use the IWifiChip interface to perform
     * other functions - e.g. debug methods.
     */
    public IWifiChip getChip(IWifiIface iface) {
        if (DBG) Log.d(TAG, "getChip: iface(name)=" + getName(iface));

        synchronized (mLock) {
            IfaceCacheEntry cacheEntry = mInterfaceInfoCache.get(iface);
            if (cacheEntry == null) {
                Log.e(TAG, "getChip: no entry for iface(name)=" + getName(iface));
                return null;
            }

            return cacheEntry.chip;
        }
    }

    /**
     * Register an InterfaceDestroyedListener to the specified iface - returns true on success
     * and false on failure. This listener is in addition to the one registered when the interface
     * was created - allowing non creators to monitor interface status.
     *
     * Listener called-back on the specified looper - or on the current looper if a null is passed.
     */
    public boolean registerDestroyedListener(IWifiIface iface,
            InterfaceDestroyedListener destroyedListener,
            Looper looper) {
        if (DBG) Log.d(TAG, "registerDestroyedListener: iface(name)=" + getName(iface));

        synchronized (mLock) {
            IfaceCacheEntry cacheEntry = mInterfaceInfoCache.get(iface);
            if (cacheEntry == null) {
                Log.e(TAG, "registerDestroyedListener: no entry for iface(name)="
                        + getName(iface));
                return false;
            }

            return cacheEntry.destroyedListeners.add(
                    new InterfaceDestroyedListenerProxy(destroyedListener,
                            looper == null ? Looper.myLooper() : looper));
        }
    }

    /**
     * Return the name of the input interface or null on error.
     */
    public static String getName(IWifiIface iface) {
        if (iface == null) {
            return "<null>";
        }

        Mutable<String> nameResp = new Mutable<>();
        try {
            iface.getName((WifiStatus status, String name) -> {
                if (status.code == WifiStatusCode.SUCCESS) {
                    nameResp.value = name;
                } else {
                    Log.e(TAG, "Error on getName: " + statusString(status));
                }
            });
        } catch (RemoteException e) {
            Log.e(TAG, "Exception on getName: " + e);
        }

        return nameResp.value;
    }

    /**
     * Called when interface is destroyed.
     */
    public interface InterfaceDestroyedListener {
        /**
         * Called for every interface on which registered when destroyed - whether
         * destroyed by releaseIface() or through chip mode change or through Wi-Fi
         * going down.
         *
         * Can be registered when the interface is requested with createXxxIface() - will
         * only be valid if the interface creation was successful - i.e. a non-null was returned.
         */
        void onDestroyed();
    }

    /**
     * Called when an interface type is possibly available for creation.
     */
    public interface InterfaceAvailableForRequestListener {
        /**
         * Registered when an interface type is requested - becomes active if the request was
         * denied - i.e. a null was returned by createXxxIface(). Will be called when there's a
         * chance that a new request will be granted.
         * - Only active if the original request was denied
         * - Only called once (at most)
         */
        void onAvailableForRequest();
    }

    /**
     * Creates a IWifiRttController corresponding to the input interface. A direct match to the
     * IWifiChip.createRttController() method.
     *
     * Returns the created IWifiRttController or a null on error.
     */
    public IWifiRttController createRttController(IWifiIface boundIface) {
        if (DBG) Log.d(TAG, "createRttController: boundIface(name)=" + getName(boundIface));
        synchronized (mLock) {
            if (mWifi == null) {
                Log.e(TAG, "createRttController: null IWifi -- boundIface(name)="
                        + getName(boundIface));
                return null;
            }

            IWifiChip chip = getChip(boundIface);
            if (chip == null) {
                Log.e(TAG, "createRttController: null IWifiChip -- boundIface(name)="
                        + getName(boundIface));
                return null;
            }

            Mutable<IWifiRttController> rttResp = new Mutable<>();
            try {
                chip.createRttController(boundIface,
                        (WifiStatus status, IWifiRttController rtt) -> {
                            if (status.code == WifiStatusCode.SUCCESS) {
                                rttResp.value = rtt;
                            } else {
                                Log.e(TAG, "IWifiChip.createRttController failed: " + statusString(
                                        status));
                            }
                        });
            } catch (RemoteException e) {
                Log.e(TAG, "IWifiChip.createRttController exception: " + e);
            }

            return rttResp.value;
        }
    }

    // internal state
    private final Object mLock = new Object();

    private IServiceManager mServiceManager;
    private IWifi mWifi;
    private final WifiEventCallback mWifiEventCallback = new WifiEventCallback();
    private final Set<ManagerStatusCallbackProxy> mManagerStatusCallbacks = new HashSet<>();
    private final SparseArray<Set<InterfaceAvailableForRequestListenerProxy>>
            mInterfaceAvailableForRequestListeners = new SparseArray<>();
    private final Map<IWifiIface, IfaceCacheEntry> mInterfaceInfoCache = new HashMap<>();

    private class IfaceCacheEntry {
        public IWifiChip chip;
        public String name;
        public int type;
        public Set<InterfaceDestroyedListenerProxy> destroyedListeners = new HashSet<>();

        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("{name=").append(name).append(", type=").append(type)
                    .append(", destroyedListeners.size()=").append(destroyedListeners.size())
                    .append("}");
            return sb.toString();
        }
    }

    /**
     * Wrapper function to access the HIDL services. Created to be mockable in unit-tests.
     */
    protected IWifi getWifiServiceMockable() {
        try {
            return IWifi.getService();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception getting IWifi service: " + e);
            return null;
        }
    }

    protected IServiceManager getServiceManagerMockable() {
        try {
            return IServiceManager.getService();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception getting IServiceManager: " + e);
            return null;
        }
    }

    // internal implementation

    private void initializeInternal() {
        initIServiceManagerIfNecessary();
    }

    private void teardownInternal() {
        managerStatusCallbackDispatchStop();
        dispatchAllDestroyedListeners();
        mInterfaceAvailableForRequestListeners.get(IfaceType.STA).clear();
        mInterfaceAvailableForRequestListeners.get(IfaceType.AP).clear();
        mInterfaceAvailableForRequestListeners.get(IfaceType.P2P).clear();
        mInterfaceAvailableForRequestListeners.get(IfaceType.NAN).clear();
    }

    /**
     * Failures of IServiceManager are most likely system breaking in any case. Behavior here
     * will be to WTF and continue.
     */
    private void initIServiceManagerIfNecessary() {
        if (DBG) Log.d(TAG, "initIServiceManagerIfNecessary");

        synchronized (mLock) {
            if (mServiceManager != null) {
                return;
            }

            mServiceManager = getServiceManagerMockable();
            if (mServiceManager == null) {
                Log.wtf(TAG, "Failed to get IServiceManager instance");
            } else {
                try {
                    if (!mServiceManager.linkToDeath(cookie -> {
                        Log.wtf(TAG, "IServiceManager died: cookie=" + cookie);
                        synchronized (mLock) {
                            mServiceManager = null;
                            // theoretically can call initServiceManager again here - but
                            // there's no point since most likely system is going to reboot
                        }
                    }, /* don't care */ 0)) {
                        Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                        mServiceManager = null;
                        return;
                    }

                    if (!mServiceManager.registerForNotifications(IWifi.kInterfaceName, "",
                            new IServiceNotification.Stub() {
                                @Override
                                public void onRegistration(String fqName, String name,
                                        boolean preexisting) {
                                    Log.d(TAG, "IWifi registration notification: fqName=" + fqName
                                            + ", name=" + name + ", preexisting=" + preexisting);
                                    mWifi = null; // get rid of old copy!
                                    initIWifiIfNecessary();
                                }
                            })) {
                        Log.wtf(TAG, "Failed to register a listener for IWifi service");
                        mServiceManager = null;
                    }
                } catch (RemoteException e) {
                    Log.wtf(TAG, "Exception while operating on IServiceManager: " + e);
                    mServiceManager = null;
                }
            }
        }
    }


    /**
     * Initialize IWifi and register death listener and event callback.
     *
     * - It is possible that IWifi is not ready - we have a listener on IServiceManager for it.
     * - It is not expected that any of the registrations will fail. Possible indication that
     *   service died after we obtained a handle to it.
     *
     * Here and elsewhere we assume that death listener will do the right thing!
    */
    private void initIWifiIfNecessary() {
        if (DBG) Log.d(TAG, "initIWifiIfNecessary");

        synchronized (mLock) {
            if (mWifi != null) {
                return;
            }

            try {
                mWifi = getWifiServiceMockable();
                if (mWifi == null) {
                    Log.e(TAG, "IWifi not (yet) available - but have a listener for it ...");
                    return;
                }

                if (!mWifi.linkToDeath(cookie -> {
                    Log.e(TAG, "IWifi HAL service died! Have a listener for it ... cookie="
                            + cookie);
                    synchronized (mLock) { // prevents race condition with surrounding method
                        mWifi = null;
                        teardownInternal();
                        // don't restart: wait for registration notification
                    }
                }, /* don't care */ 0)) {
                    Log.e(TAG, "Error on linkToDeath on IWifi - will retry later");
                    return;
                }

                WifiStatus status = mWifi.registerEventCallback(mWifiEventCallback);
                if (status.code != WifiStatusCode.SUCCESS) {
                    Log.e(TAG, "IWifi.registerEventCallback failed: " + statusString(status));
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while operating on IWifi: " + e);
            }
        }
    }

    private boolean isWifiStarted() {
        if (DBG) Log.d(TAG, "isWifiStart");

        synchronized (mLock) {
            try {
                if (mWifi == null) {
                    Log.w(TAG, "isWifiStarted called but mWifi is null!?");
                    return false;
                } else {
                    return mWifi.isStarted();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "isWifiStarted exception: " + e);
                return false;
            }
        }
    }

    private boolean startWifi() {
        if (DBG) Log.d(TAG, "startWifi");

        synchronized (mLock) {
            try {
                if (mWifi == null) {
                    Log.w(TAG, "startWifi called but mWifi is null!?");
                    return false;
                } else {
                    WifiStatus status = mWifi.start();
                    boolean success = status.code == WifiStatusCode.SUCCESS;
                    if (success) {
                        managerStatusCallbackDispatchStart();
                    } else {
                        Log.e(TAG, "Cannot start IWifi: " + statusString(status));
                    }
                    return success;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "startWifi exception: " + e);
                return false;
            }
        }
    }

    private void stopWifi() {
        if (DBG) Log.d(TAG, "stopWifi");

        synchronized (mLock) {
            try {
                if (mWifi == null) {
                    Log.w(TAG, "stopWifi called but mWifi is null!?");
                } else {
                    WifiStatus status = mWifi.stop();
                    if (status.code != WifiStatusCode.SUCCESS) {
                        Log.e(TAG, "Cannot stop IWifi: " + statusString(status));
                    }

                    // even on failure since WTF??
                    teardownInternal();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "stopWifi exception: " + e);
            }
        }
    }

    private class WifiEventCallback extends IWifiEventCallback.Stub {
        @Override
        public void onStart() throws RemoteException {
            if (DBG) Log.d(TAG, "IWifiEventCallback.onStart");
            // NOP: only happens in reaction to my calls - will handle directly
        }

        @Override
        public void onStop() throws RemoteException {
            if (DBG) Log.d(TAG, "IWifiEventCallback.onStop");
            // NOP: only happens in reaction to my calls - will handle directly
        }

        @Override
        public void onFailure(WifiStatus status) throws RemoteException {
            Log.e(TAG, "IWifiEventCallback.onFailure: " + statusString(status));
            teardownInternal();

            // No need to do anything else: listeners may (will) re-start Wi-Fi
        }
    }

    private void managerStatusCallbackDispatchStart() {
        synchronized (mLock) {
            for (ManagerStatusCallbackProxy cb : mManagerStatusCallbacks) {
                cb.onStart();
            }
        }
    }

    private void managerStatusCallbackDispatchStop() {
        synchronized (mLock) {
            for (ManagerStatusCallbackProxy cb : mManagerStatusCallbacks) {
                cb.onStop();
            }
        }
    }

    private class ManagerStatusCallbackProxy  {
        private static final int CALLBACK_ON_START = 0;
        private static final int CALLBACK_ON_STOP = 1;

        private ManagerStatusCallback mCallback;
        private Handler mHandler;

        void onStart() {
            mHandler.sendMessage(mHandler.obtainMessage(CALLBACK_ON_START));
        }

        void onStop() {
            mHandler.sendMessage(mHandler.obtainMessage(CALLBACK_ON_STOP));
        }

        // override equals & hash to make sure that the container HashSet is unique with respect to
        // the contained callback
        @Override
        public boolean equals(Object obj) {
            return mCallback == ((ManagerStatusCallbackProxy) obj).mCallback;
        }

        @Override
        public int hashCode() {
            return mCallback.hashCode();
        }

        ManagerStatusCallbackProxy(ManagerStatusCallback callback, Looper looper) {
            mCallback = callback;
            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    if (DBG) {
                        Log.d(TAG, "ManagerStatusCallbackProxy.handleMessage: what=" + msg.what);
                    }
                    switch (msg.what) {
                        case CALLBACK_ON_START:
                            mCallback.onStart();
                            break;
                        case CALLBACK_ON_STOP:
                            mCallback.onStop();
                            break;
                        default:
                            Log.e(TAG,
                                    "ManagerStatusCallbackProxy.handleMessage: unknown message "
                                            + "what="
                                            + msg.what);
                    }
                }
            };
        }
    }

    private IWifiIface createIface(int ifaceType, InterfaceDestroyedListener destroyedListener,
            InterfaceAvailableForRequestListener availableListener,
            Looper looper) {
        IWifiIface iface = createIfaceIfPossible(ifaceType, destroyedListener, looper);
        if (iface == null && availableListener != null) {
            mInterfaceAvailableForRequestListeners.get(ifaceType).add(
                    new InterfaceAvailableForRequestListenerProxy(availableListener,
                            looper == null ? Looper.myLooper() : looper));
        }

        return iface;
    }

    private IWifiIface createIfaceIfPossible(int ifaceType,
            InterfaceDestroyedListener destroyedListener, Looper looper) {
        // TODO
        return null;
    }

    private boolean removeIfaceInternal(IWifiIface iface) {
        if (DBG) Log.d(TAG, "removeIfaceInternal: iface(name)=" + getName(iface));

        synchronized (mLock) {
            if (mWifi == null) {
                Log.e(TAG, "removeIfaceInternal: null IWifi -- iface(name)=" + getName(iface));
                return false;
            }

            IWifiChip chip = getChip(iface);
            if (chip == null) {
                Log.e(TAG, "removeIfaceInternal: null IWifiChip -- iface(name)=" + getName(iface));
                return false;
            }

            String name = getName(iface);
            if (name == null) {
                Log.e(TAG, "removeIfaceInternal: can't get name");
                return false;
            }

            int type = getType(iface);
            if (type == -1) {
                Log.e(TAG, "removeIfaceInternal: can't get type -- iface(name)=" + getName(iface));
                return false;
            }

            WifiStatus status = null;
            try {
                switch (type) {
                    case IfaceType.STA:
                        status = chip.removeStaIface(name);
                        break;
                    case IfaceType.AP:
                        status = chip.removeApIface(name);
                        break;
                    case IfaceType.P2P:
                        status = chip.removeP2pIface(name);
                        break;
                    case IfaceType.NAN:
                        status = chip.removeNanIface(name);
                        break;
                    default:
                        Log.wtf(TAG, "removeIfaceInternal: invalid type=" + type);
                        return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "IWifiChip.removeXxxIface exception: " + e);
            }

            // dispatch listeners no matter what status
            dispatchDestroyedListeners(iface);

            if (status != null && status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "IWifiChip.removeXxxIface failed: " + statusString(status));
                return false;
            }
        }
    }

    // dispatch all available for request listeners of the specified type AND clean-out the list:
    // listeners are called once at most!
    // TODO: no one is calling this now
    private void dispatchAvailableForRequestListeners(int ifaceType) {
        if (DBG) Log.d(TAG, "dispatchAvailableForRequestListeners: ifaceType=" + ifaceType);

        for (InterfaceAvailableForRequestListenerProxy listener:
                mInterfaceAvailableForRequestListeners.get(ifaceType)) {
            listener.trigger();
        }
        mInterfaceAvailableForRequestListeners.get(ifaceType).clear();
    }

    // dispatch all destroyed listeners registered for the specified interface AND remove the
    // cache entry
    private void dispatchDestroyedListeners(IWifiIface iface) {
        if (DBG) Log.d(TAG, "dispatchDestroyedListeners: iface(name)=" + getName(iface));

        synchronized (mLock) {
            IfaceCacheEntry entry = mInterfaceInfoCache.get(iface);
            if (entry == null) {
                Log.e(TAG, "dispatchDestroyedListeners: no cache entry for iface(name)="
                        + getName(iface));
                return;
            }

            for (InterfaceDestroyedListenerProxy listener : entry.destroyedListeners) {
                listener.trigger();
            }
            entry.destroyedListeners.clear(); // for insurance (though cache entry is removed)
            mInterfaceInfoCache.remove(iface);
        }
    }

    // dispatch all destroyed listeners registered to all interfaces
    private void dispatchAllDestroyedListeners() {
        if (DBG) Log.d(TAG, "dispatchAllDestroyedListeners");

        synchronized (mLock) {
            Iterator<Map.Entry<IWifiIface, IfaceCacheEntry>> it =
                    mInterfaceInfoCache.entrySet().iterator();
            while (it.hasNext()) {
                IfaceCacheEntry entry = it.next().getValue();
                for (InterfaceDestroyedListenerProxy listener : entry.destroyedListeners) {
                    listener.trigger();
                }
                entry.destroyedListeners.clear(); // for insurance (though cache entry is removed)
                it.remove();
            }
        }
    }

    private abstract class ListenerProxy<LISTENER>  {
        private static final int LISTENER_TRIGGERED = 0;

        protected LISTENER mListener;
        private Handler mHandler;

        // override equals & hash to make sure that the container HashSet is unique with respect to
        // the contained listener
        @Override
        public boolean equals(Object obj) {
            return mListener == ((ListenerProxy<LISTENER>) obj).mListener;
        }

        @Override
        public int hashCode() {
            return mListener.hashCode();
        }

        void trigger() {
            mHandler.sendMessage(mHandler.obtainMessage(LISTENER_TRIGGERED));
        }

        protected abstract void action();

        ListenerProxy(LISTENER listener, Looper looper) {
            mListener = listener;
            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    if (DBG) {
                        Log.d(TAG, "ListenerProxy.handleMessage: what=" + msg.what);
                    }
                    switch (msg.what) {
                        case LISTENER_TRIGGERED:
                            action();
                            break;
                        default:
                            Log.e(TAG, "ListenerProxy.handleMessage: unknown message what="
                                    + msg.what);
                    }
                }
            };
        }
    }

    private class InterfaceDestroyedListenerProxy extends
            ListenerProxy<InterfaceDestroyedListener> {
        InterfaceDestroyedListenerProxy(InterfaceDestroyedListener destroyedListener,
                Looper looper) {
            super(destroyedListener, looper);
        }

        @Override
        protected void action() {
            mListener.onDestroyed();
        }
    }

    private class InterfaceAvailableForRequestListenerProxy extends
            ListenerProxy<InterfaceAvailableForRequestListener> {
        InterfaceAvailableForRequestListenerProxy(
                InterfaceAvailableForRequestListener destroyedListener, Looper looper) {
            super(destroyedListener, looper);
        }

        @Override
        protected void action() {
            mListener.onAvailableForRequest();
        }
    }

    // general utilities

    private static String statusString(WifiStatus status) {
        if (status == null) {
            return "status=null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(status.code).append(" (").append(status.description).append(")");
        return sb.toString();
    }

    // Will return -1 for invalid results! Otherwise will return one of the 4 valid values.
    private static int getType(IWifiIface iface) {
        MutableInt typeResp = new MutableInt(-1);
        try {
            iface.getType((WifiStatus status, int type) -> {
                if (status.code == WifiStatusCode.SUCCESS) {
                    typeResp.value = type;
                } else {
                    Log.e(TAG, "Error on getType: " + statusString(status));
                }
            });
        } catch (RemoteException e) {
            Log.e(TAG, "Exception on getType: " + e);
        }

        return typeResp.value;
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

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("HalDeviceManager:");
        pw.println("  mServiceManager: " + mServiceManager);
        pw.println("  mWifi: " + mWifi);
        pw.println("  mManagerStatusCallbacks: " + mManagerStatusCallbacks);
        pw.println("  mInterfaceAvailableForRequestListeners: "
                + mInterfaceAvailableForRequestListeners);
        pw.println("  mInterfaceInfoCache: " + mInterfaceInfoCache);
    }
}
