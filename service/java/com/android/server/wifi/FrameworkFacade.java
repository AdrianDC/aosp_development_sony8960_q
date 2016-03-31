
package com.android.server.wifi;

import android.app.AppGlobals;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.net.ip.IpManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserManager;
import android.provider.Settings;
import android.security.KeyStore;

import java.util.ArrayList;

/**
 * This class allows overriding objects with mocks to write unit tests
 */
public class FrameworkFacade {
    public static final String TAG = "FrameworkFacade";

    public BaseWifiLogger makeBaseLogger() {
        return new BaseWifiLogger();
    }

    public BaseWifiLogger makeRealLogger(
            WifiStateMachine stateMachine, WifiNative wifiNative, int maxRingbufferSizeBytes) {
        return new WifiLogger(stateMachine, wifiNative, maxRingbufferSizeBytes);
    }

    public boolean setIntegerSetting(Context context, String name, int def) {
        return Settings.Global.putInt(context.getContentResolver(), name, def);
    }

    public int getIntegerSetting(Context context, String name, int def) {
        return Settings.Global.getInt(context.getContentResolver(), name, def);
    }

    public long getLongSetting(Context context, String name, long def) {
        return Settings.Global.getLong(context.getContentResolver(), name, def);
    }

    public String getStringSetting(Context context, String name) {
        return Settings.Global.getString(context.getContentResolver(), name);
    }

    public IBinder getService(String serviceName) {
        return ServiceManager.getService(serviceName);
    }

    public PendingIntent getBroadcast(Context context, int requestCode, Intent intent, int flags) {
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    public SupplicantStateTracker makeSupplicantStateTracker(Context context,
            WifiConfigManager configManager, Handler handler) {
        return new SupplicantStateTracker(context, configManager, handler);
    }

    /**
     * Create a new instance of WifiApConfigStore.
     * @param context reference to a Context
     * @param backupManagerProxy reference to a BackupManagerProxy
     * @return an instance of WifiApConfigStore
     */
    public WifiApConfigStore makeApConfigStore(Context context,
                                               BackupManagerProxy backupManagerProxy) {
        return new WifiApConfigStore(context, backupManagerProxy);
    }

    public long getTxPackets(String iface) {
        return TrafficStats.getTxPackets(iface);
    }

    public long getRxPackets(String iface) {
        return TrafficStats.getRxPackets(iface);
    }

    public IpManager makeIpManager(
            Context context, String iface, IpManager.Callback callback) {
        return new IpManager(context, iface, callback);
    }

    /**
     * Create a SoftApManager.
     * @param context current context
     * @param looper current thread looper
     * @param wifiNative reference to WifiNative
     * @param nmService reference to NetworkManagementService
     * @param cm reference to ConnectivityManager
     * @param countryCode Country code
     * @param allowed2GChannels list of allowed 2G channels
     * @param listener listener for SoftApManager
     * @return an instance of SoftApManager
     */
    public SoftApManager makeSoftApManager(
            Context context, Looper looper, WifiNative wifiNative,
            INetworkManagementService nmService, ConnectivityManager cm,
            String countryCode, ArrayList<Integer> allowed2GChannels,
            SoftApManager.Listener listener) {
        return new SoftApManager(
                context, looper, wifiNative, nmService, cm, countryCode,
                allowed2GChannels, listener);
    }

    /**
     * Checks whether the given uid has been granted the given permission.
     * @param permName the permission to check
     * @param uid The uid to check
     * @return {@link PackageManager.PERMISSION_GRANTED} if the permission has been granted and
     *         {@link PackageManager.PERMISSION_DENIED} otherwise
     */
    public int checkUidPermission(String permName, int uid) throws RemoteException {
        return AppGlobals.getPackageManager().checkUidPermission(permName, uid);
    }

    public WifiConfigManager makeWifiConfigManager(Context context,
            WifiStateMachine wifiStateMachine, WifiNative wifiNative,
            FrameworkFacade frameworkFacade, Clock clock, UserManager userManager,
            KeyStore keyStore) {
        return new WifiConfigManager(context, wifiStateMachine, wifiNative, frameworkFacade, clock,
                userManager, keyStore);
    }
}
