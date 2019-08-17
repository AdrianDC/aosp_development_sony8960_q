/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.telephony.cts;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.AvailableNetworkInfo;
import android.telephony.CallAttributes;
import android.telephony.CallQuality;
import android.telephony.CellLocation;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.PhoneStateListener;
import android.telephony.PreciseCallState;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.UiccCardInfo;
import android.telephony.cts.locationaccessingapp.CtsLocationAccessService;
import android.telephony.cts.locationaccessingapp.ICtsLocationAccessControl;
import android.telephony.emergency.EmergencyNumber;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.ShellIdentityUtils;
import com.android.compatibility.common.util.TestThread;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Build, install and run the tests by running the commands below:
 *  make cts -j64
 *  cts-tradefed run cts -m CtsTelephonyTestCases --test android.telephony.cts.TelephonyManagerTest
 */
@RunWith(AndroidJUnit4.class)
public class TelephonyManagerTest {
    private TelephonyManager mTelephonyManager;
    private PackageManager mPackageManager;
    private SubscriptionManager mSubscriptionManager;
    private boolean mOnCellLocationChangedCalled = false;
    private ServiceState mServiceState;
    private final Object mLock = new Object();
    private static final int TOLERANCE = 1000;
    private PhoneStateListener mListener;
    private static ConnectivityManager mCm;
    private static final String TAG = "TelephonyManagerTest";

    @Before
    public void setUp() throws Exception {
        mTelephonyManager =
                (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        mCm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        mSubscriptionManager = (SubscriptionManager) getContext()
                .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        mPackageManager = getContext().getPackageManager();
    }

    @After
    public void tearDown() throws Exception {
        if (mListener != null) {
            // unregister the listener
            mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_NONE);
        }
    }

    @Test
    public void testListen() throws Throwable {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        if (mTelephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
            // TODO: temp workaround, need to adjust test to for CDMA
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();
                mListener = new PhoneStateListener() {
                    @Override
                    public void onCellLocationChanged(CellLocation location) {
                        if(!mOnCellLocationChangedCalled) {
                            synchronized (mLock) {
                                mOnCellLocationChangedCalled = true;
                                mLock.notify();
                            }
                        }
                    }
                };

                synchronized (mLock) {
                    mLock.notify(); // mListener is ready
                }

                Looper.loop();
            }
        });

        synchronized (mLock) {
            t.start();
            mLock.wait(TOLERANCE); // wait for mListener
        }

        // Test register
        synchronized (mLock) {
            // .listen generates an onCellLocationChanged event
            mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_CELL_LOCATION);
            mLock.wait(TOLERANCE);

            assertTrue("Test register, mOnCellLocationChangedCalled should be true.",
                mOnCellLocationChangedCalled);
        }

        synchronized (mLock) {
            mOnCellLocationChangedCalled = false;
            CellLocation.requestLocationUpdate();
            mLock.wait(TOLERANCE);

            assertTrue("Test register, mOnCellLocationChangedCalled should be true.",
                mOnCellLocationChangedCalled);
        }

        // unregister the listener
        mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_NONE);
        Thread.sleep(TOLERANCE);

        // Test unregister
        synchronized (mLock) {
            mOnCellLocationChangedCalled = false;
            // unregister again, to make sure doing so does not call the listener
            mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_NONE);
            CellLocation.requestLocationUpdate();
            mLock.wait(TOLERANCE);

            assertFalse("Test unregister, mOnCellLocationChangedCalled should be false.",
                mOnCellLocationChangedCalled);
        }
    }

    /**
     * The getter methods here are all related to the information about the telephony.
     * These getters are related to concrete location, phone, service provider company, so
     * it's no need to get details of these information, just make sure they are in right
     * condition(>0 or not null).
     */
    @Test
    public void testTelephonyManager() {
        assertTrue(mTelephonyManager.getNetworkType() >= TelephonyManager.NETWORK_TYPE_UNKNOWN);
        assertTrue(mTelephonyManager.getPhoneType() >= TelephonyManager.PHONE_TYPE_NONE);
        assertTrue(mTelephonyManager.getSimState() >= TelephonyManager.SIM_STATE_UNKNOWN);
        assertTrue(mTelephonyManager.getDataActivity() >= TelephonyManager.DATA_ACTIVITY_NONE);
        assertTrue(mTelephonyManager.getDataState() >= TelephonyManager.DATA_DISCONNECTED);
        assertTrue(mTelephonyManager.getCallState() >= TelephonyManager.CALL_STATE_IDLE);

        for (int i = 0; i < mTelephonyManager.getPhoneCount(); ++i) {
            assertTrue(mTelephonyManager.getSimState(i) >= TelephonyManager.SIM_STATE_UNKNOWN);
        }

        // Make sure devices without MMS service won't fail on this
        if (mTelephonyManager.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE) {
            assertFalse(mTelephonyManager.getMmsUserAgent().isEmpty());
            assertFalse(mTelephonyManager.getMmsUAProfUrl().isEmpty());
        }

        // The following methods may return any value depending on the state of the device. Simply
        // call them to make sure they do not throw any exceptions.
        mTelephonyManager.getVoiceMailNumber();
        mTelephonyManager.getSimOperatorName();
        mTelephonyManager.getNetworkCountryIso();
        mTelephonyManager.getCellLocation();
        mTelephonyManager.getSimCarrierId();
        mTelephonyManager.getSimCarrierIdName();
        mTelephonyManager.getSimSpecificCarrierId();
        mTelephonyManager.getSimSpecificCarrierIdName();
        mTelephonyManager.getCarrierIdFromSimMccMnc();
        mTelephonyManager.getSimSerialNumber();
        mTelephonyManager.getSimOperator();
        mTelephonyManager.getSignalStrength();
        mTelephonyManager.getNetworkOperatorName();
        mTelephonyManager.getSubscriberId();
        mTelephonyManager.getLine1Number();
        mTelephonyManager.getNetworkOperator();
        mTelephonyManager.getSimCountryIso();
        mTelephonyManager.getVoiceMailAlphaTag();
        mTelephonyManager.isNetworkRoaming();
        mTelephonyManager.getDeviceId();
        mTelephonyManager.getDeviceId(mTelephonyManager.getSlotIndex());
        mTelephonyManager.getDeviceSoftwareVersion();
        mTelephonyManager.getImei();
        mTelephonyManager.getImei(mTelephonyManager.getSlotIndex());
        mTelephonyManager.getPhoneCount();
        mTelephonyManager.getDataEnabled();
        mTelephonyManager.getNetworkSpecifier();
        mTelephonyManager.getNai();
        TelecomManager telecomManager = (TelecomManager) getContext()
                .getSystemService(Context.TELECOM_SERVICE);
        PhoneAccountHandle defaultAccount = telecomManager
                .getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL);
        mTelephonyManager.getVoicemailRingtoneUri(defaultAccount);
        mTelephonyManager.isVoicemailVibrationEnabled(defaultAccount);
        mTelephonyManager.getCarrierConfig();
    }

    @Test
    public void testCellLocationFinePermission() {
        withRevokedPermission(() -> {
            try {
                CellLocation cellLocation = (CellLocation) performLocationAccessCommand(
                        CtsLocationAccessService.COMMAND_GET_CELL_LOCATION);
                assertTrue(cellLocation == null || cellLocation.isEmpty());
            } catch (SecurityException e) {
                // expected
            }

            try {
                List cis = (List) performLocationAccessCommand(
                        CtsLocationAccessService.COMMAND_GET_CELL_INFO);
                assertTrue(cis == null || cis.isEmpty());
            } catch (SecurityException e) {
                // expected
            }
        }, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    @Test
    public void testServiceStateLocationSanitization() {
        withRevokedPermission(() -> {
                    ServiceState ss = (ServiceState) performLocationAccessCommand(
                            CtsLocationAccessService.COMMAND_GET_SERVICE_STATE);
                    assertServiceStateSanitization(ss, true);

                    withRevokedPermission(() -> {
                                ServiceState ss1 = (ServiceState) performLocationAccessCommand(
                                        CtsLocationAccessService.COMMAND_GET_SERVICE_STATE);
                                assertServiceStateSanitization(ss1, false);
                            },
                            Manifest.permission.ACCESS_COARSE_LOCATION);
                },
                Manifest.permission.ACCESS_FINE_LOCATION);
    }

    @Test
    public void testServiceStateListeningWithoutPermissions() {
            withRevokedPermission(() -> {
                    ServiceState ss = (ServiceState) performLocationAccessCommand(
                            CtsLocationAccessService.COMMAND_GET_SERVICE_STATE_FROM_LISTENER);
                    assertServiceStateSanitization(ss, true);

                    withRevokedPermission(() -> {
                                ServiceState ss1 = (ServiceState) performLocationAccessCommand(
                                        CtsLocationAccessService
                                                .COMMAND_GET_SERVICE_STATE_FROM_LISTENER);
                                assertServiceStateSanitization(ss1, false);
                            },
                            Manifest.permission.ACCESS_COARSE_LOCATION);
                },
                Manifest.permission.ACCESS_FINE_LOCATION);
    }

    @Test
    public void testRegistryPermissionsForCellLocation() {
        withRevokedPermission(() -> {
                    CellLocation cellLocation = (CellLocation) performLocationAccessCommand(
                            CtsLocationAccessService.COMMAND_LISTEN_CELL_LOCATION);
                    assertNull(cellLocation);
                },
                Manifest.permission.ACCESS_FINE_LOCATION);
    }

    @Test
    public void testRegistryPermissionsForCellInfo() {
        withRevokedPermission(() -> {
                    CellLocation cellLocation = (CellLocation) performLocationAccessCommand(
                            CtsLocationAccessService.COMMAND_LISTEN_CELL_INFO);
                    assertNull(cellLocation);
                },
                Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private ICtsLocationAccessControl getLocationAccessAppControl() {
        Intent bindIntent = new Intent(CtsLocationAccessService.CONTROL_ACTION);
        bindIntent.setComponent(new ComponentName(CtsLocationAccessService.class.getPackageName$(),
                CtsLocationAccessService.class.getName()));

        LinkedBlockingQueue<ICtsLocationAccessControl> pipe =
                new LinkedBlockingQueue<>();
        getContext().bindService(bindIntent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                pipe.offer(ICtsLocationAccessControl.Stub.asInterface(service));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {

            }
        }, Context.BIND_AUTO_CREATE);

        try {
            return pipe.poll(TOLERANCE, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail("interrupted");
        }
        fail("Unable to connect to location access test app");
        return null;
    }

    private Object performLocationAccessCommand(String command) {
        ICtsLocationAccessControl control = getLocationAccessAppControl();
        try {
            List ret = control.performCommand(command);
            return ret.get(0);
        } catch (RemoteException e) {
            fail("Remote exception");
        }
        return null;
    }

    private void withRevokedPermission(Runnable r, String permission) {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().revokeRuntimePermission(
                CtsLocationAccessService.class.getPackageName$(), permission);
        try {
            r.run();
        } finally {
            InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().grantRuntimePermission(
                    CtsLocationAccessService.class.getPackageName$(), permission);
        }
    }

    private void assertServiceStateSanitization(ServiceState state, boolean sanitizedForFineOnly) {
        if (state == null) return;

        if (state.getNetworkRegistrationInfoList() != null) {
            for (NetworkRegistrationInfo nrs : state.getNetworkRegistrationInfoList()) {
                assertNull(nrs.getCellIdentity());
            }
        }

        if (sanitizedForFineOnly) return;

        assertTrue(TextUtils.isEmpty(state.getDataOperatorAlphaLong()));
        assertTrue(TextUtils.isEmpty(state.getDataOperatorAlphaShort()));
        assertTrue(TextUtils.isEmpty(state.getDataOperatorNumeric()));
        assertTrue(TextUtils.isEmpty(state.getVoiceOperatorAlphaLong()));
        assertTrue(TextUtils.isEmpty(state.getVoiceOperatorAlphaShort()));
        assertTrue(TextUtils.isEmpty(state.getVoiceOperatorNumeric()));
    }

    @Test
    public void testGetRadioHalVersion() {
        Pair<Integer, Integer> version = mTelephonyManager.getRadioHalVersion();

        // The version must be valid, and the versions start with 1.0
        assertFalse("Invalid Radio HAL Version: " + version,
                version.first < 1 || version.second < 0);
    }

    @Test
    public void testCreateForPhoneAccountHandle() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            Log.d(TAG, "Skipping test that requires FEATURE_TELEPHONY");
            return;
        }
        TelecomManager telecomManager = getContext().getSystemService(TelecomManager.class);
        PhoneAccountHandle handle =
                telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL);
        TelephonyManager telephonyManager = mTelephonyManager.createForPhoneAccountHandle(handle);
        assertEquals(mTelephonyManager.getSubscriberId(), telephonyManager.getSubscriberId());
    }

    @Test
    public void testCreateForPhoneAccountHandle_InvalidHandle(){
        PhoneAccountHandle handle =
                new PhoneAccountHandle(new ComponentName("com.example.foo", "bar"), "baz");
        assertNull(mTelephonyManager.createForPhoneAccountHandle(handle));
    }

    /**
     * Tests that the phone count returned is valid.
     */
    @Test
    public void testGetPhoneCount() {
        int phoneCount = mTelephonyManager.getPhoneCount();
        int phoneType = mTelephonyManager.getPhoneType();
        switch (phoneType) {
            case TelephonyManager.PHONE_TYPE_GSM:
            case TelephonyManager.PHONE_TYPE_CDMA:
                assertTrue("Phone count should be > 0", phoneCount > 0);
                break;
            case TelephonyManager.PHONE_TYPE_NONE:
                assertTrue("Phone count should be 0", phoneCount == 0 || phoneCount == 1);
                break;
            default:
                throw new IllegalArgumentException("Did you add a new phone type? " + phoneType);
        }
    }

    /**
     * Tests that the device properly reports either a valid IMEI, MEID/ESN, or a valid MAC address
     * if only a WiFi device. At least one of them must be valid.
     */
    @Test
    public void testGetDeviceId() {
        verifyDeviceId(mTelephonyManager.getDeviceId());
    }

    /**
     * Tests that the device properly reports either a valid IMEI, MEID/ESN, or a valid MAC address
     * if only a WiFi device. At least one of them must be valid.
     */
    @Test
    public void testGetDeviceIdForSlot() {
        String deviceId = mTelephonyManager.getDeviceId(mTelephonyManager.getSlotIndex());
        verifyDeviceId(deviceId);
        // Also verify that no exception is thrown for any slot index (including invalid ones)
        for (int i = -1; i <= mTelephonyManager.getPhoneCount(); i++) {
            mTelephonyManager.getDeviceId(i);
        }
    }

    private void verifyDeviceId(String deviceId) {
        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            // Either IMEI or MEID need to be valid.
            try {
                assertImei(deviceId);
            } catch (AssertionError e) {
                assertMeidEsn(deviceId);
            }
        } else if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            assertSerialNumber();
            assertMacAddress(getWifiMacAddress());
        } else if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            assertSerialNumber();
            assertMacAddress(getBluetoothMacAddress());
        } else if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_ETHERNET)) {
            assertTrue(mCm.getNetworkInfo(ConnectivityManager.TYPE_ETHERNET) != null);
        }
    }

    private static void assertImei(String id) {
        assertFalse("Imei should not be empty or null", TextUtils.isEmpty(id));
        // IMEI may include the check digit
        String imeiPattern = "[0-9]{14,15}";
        String invalidPattern = "[0]{14,15}";
        assertTrue("IMEI " + id + " does not match pattern " + imeiPattern,
                Pattern.matches(imeiPattern, id));
        assertFalse("IMEI " + id + " must not be a zero sequence" + invalidPattern,
                Pattern.matches(invalidPattern, id));
        if (id.length() == 15) {
            // if the ID is 15 digits, the 15th must be a check digit.
            assertImeiCheckDigit(id);
        }
    }

    private static void assertImeiCheckDigit(String deviceId) {
        int expectedCheckDigit = getLuhnCheckDigit(deviceId.substring(0, 14));
        int actualCheckDigit = Character.digit(deviceId.charAt(14), 10);
        assertEquals("Incorrect check digit for " + deviceId, expectedCheckDigit, actualCheckDigit);
    }

    /**
     * Use decimal value (0-9) to index into array to get sum of its digits
     * needed by Lunh check.
     *
     * Example: DOUBLE_DIGIT_SUM[6] = 3 because 6 * 2 = 12 => 1 + 2 = 3
     */
    private static final int[] DOUBLE_DIGIT_SUM = {0, 2, 4, 6, 8, 1, 3, 5, 7, 9};

    /**
     * Calculate the check digit by starting from the right, doubling every
     * each digit, summing all the digits including the doubled ones, and
     * finding a number to make the sum divisible by 10.
     *
     * @param deviceId not including the check digit
     * @return the check digit
     */
    private static int getLuhnCheckDigit(String deviceId) {
        int sum = 0;
        int dontDoubleModulus = deviceId.length() % 2;
        for (int i = deviceId.length() - 1; i >= 0; --i) {
            int digit = Character.digit(deviceId.charAt(i), 10);
            if (i % 2 == dontDoubleModulus) {
                sum += digit;
            } else {
                sum += DOUBLE_DIGIT_SUM[digit];
            }
        }
        sum %= 10;
        return sum == 0 ? 0 : 10 - sum;
    }

    private static void assertMeidEsn(String id) {
        // CDMA device IDs may either be a 14-hex-digit MEID or an
        // 8-hex-digit ESN.  If it's an ESN, it may not be a
        // pseudo-ESN.
        assertFalse("Meid ESN should not be empty or null", TextUtils.isEmpty(id));
        if (id.length() == 14) {
            assertMeidFormat(id);
        } else if (id.length() == 8) {
            assertHexadecimalEsnFormat(id);
        } else {
            fail("device id on CDMA must be 14-digit hex MEID or 8-digit hex ESN.");
        }
    }

    private static void assertHexadecimalEsnFormat(String deviceId) {
        String esnPattern = "[0-9a-fA-F]{8}";
        String invalidPattern = "[0]{8}";
        assertTrue("ESN hex device id " + deviceId + " does not match pattern " + esnPattern,
                   Pattern.matches(esnPattern, deviceId));
        assertFalse("ESN hex device id " + deviceId + " must not be a pseudo-ESN",
                    "80".equals(deviceId.substring(0, 2)));
        assertFalse("ESN hex device id " + deviceId + "must not be a zero sequence",
                Pattern.matches(invalidPattern, deviceId));
    }

    private static void assertMeidFormat(String deviceId) {
        // MEID must NOT include the check digit.
        String meidPattern = "[0-9a-fA-F]{14}";
        String invalidPattern = "[0]{14}";
        assertTrue("MEID device id " + deviceId + " does not match pattern "
                + meidPattern, Pattern.matches(meidPattern, deviceId));
        assertFalse("MEID device id " + deviceId + "must not be a zero sequence",
                Pattern.matches(invalidPattern, deviceId));
    }

    private void assertSerialNumber() {
        assertNotNull("Non-telephony devices must have a Build.getSerial() number.",
                Build.getSerial());
        assertTrue("Hardware id must be no longer than 20 characters.",
                Build.getSerial().length() <= 20);
        assertTrue("Hardware id must be alphanumeric.",
                Pattern.matches("[0-9A-Za-z]+", Build.getSerial()));
    }

    private void assertMacAddress(String macAddress) {
        String macPattern = "([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}";
        assertTrue("MAC Address " + macAddress + " does not match pattern " + macPattern,
                Pattern.matches(macPattern, macAddress));
    }

    /** @return mac address which requires the WiFi system to be enabled */
    private String getWifiMacAddress() {
        WifiManager wifiManager = (WifiManager) getContext()
                .getSystemService(Context.WIFI_SERVICE);

        boolean enabled = wifiManager.isWifiEnabled();

        try {
            if (!enabled) {
                wifiManager.setWifiEnabled(true);
            }

            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            return wifiInfo.getMacAddress();

        } finally {
            if (!enabled) {
                wifiManager.setWifiEnabled(false);
            }
        }
    }

    private String getBluetoothMacAddress() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return "";
        }

        return adapter.getAddress();
    }

    private static final String ISO_COUNTRY_CODE_PATTERN = "[a-z]{2}";

    @Test
    public void testGetNetworkCountryIso() {
        String countryCode = mTelephonyManager.getNetworkCountryIso();
        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            assertTrue("Country code '" + countryCode + "' did not match "
                    + ISO_COUNTRY_CODE_PATTERN,
                    Pattern.matches(ISO_COUNTRY_CODE_PATTERN, countryCode));
        } else {
            // Non-telephony may still have the property defined if it has a SIM.
        }
    }

    @Test
    public void testGetSimCountryIso() {
        String countryCode = mTelephonyManager.getSimCountryIso();
        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            assertTrue("Country code '" + countryCode + "' did not match "
                    + ISO_COUNTRY_CODE_PATTERN,
                    Pattern.matches(ISO_COUNTRY_CODE_PATTERN, countryCode));
        } else {
            // Non-telephony may still have the property defined if it has a SIM.
        }
    }

    @Test
    public void testGetServiceState() throws InterruptedException {
        if (mCm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE) == null) {
            Log.d(TAG, "Skipping test that requires ConnectivityManager.TYPE_MOBILE");
            return;
        }

        TestThread t = new TestThread(new Runnable() {
            public void run() {
                Looper.prepare();

                mListener = new PhoneStateListener() {
                    @Override
                    public void onServiceStateChanged(ServiceState serviceState) {
                        synchronized (mLock) {
                            mServiceState = serviceState;
                            mLock.notify();
                        }
                    }
                };
                mTelephonyManager.listen(mListener, PhoneStateListener.LISTEN_SERVICE_STATE);
                Looper.loop();
            }
        });

        synchronized (mLock) {
            t.start();
            mLock.wait(TOLERANCE);
        }

        assertEquals(mServiceState, mTelephonyManager.getServiceState());
    }

    /**
     * Tests that a GSM device properly reports either the correct TAC (type allocation code) or
     * null.
     * The TAC should match the first 8 digits of the IMEI.
     */
    @Test
    public void testGetTac() {
        String tac = mTelephonyManager.getTypeAllocationCode();
        String imei = ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
                (tm) -> tm.getImei());

        if (tac == null || imei == null) {
            return;
        }

        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            if (mTelephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
                assertEquals(imei.substring(0, 8), tac);
            }
        }
    }

    /**
     * Tests that a CDMA device properly reports either the correct MC (manufacturer code) or null.
     * The MC should match the first 8 digits of the MEID.
     */
    @Test
    public void testGetMc() {
        String mc = mTelephonyManager.getManufacturerCode();
        String meid = ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
                (tm) -> tm.getMeid());

        if (mc == null || meid == null) {
            return;
        }

        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            if (mTelephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
                assertEquals(meid.substring(0, 8), mc);
            }
        }
    }

    /**
     * Tests that the device properly reports either a valid IMEI or null.
     */
    @Test
    public void testGetImei() {
        String imei = mTelephonyManager.getImei();

        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            if (mTelephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_GSM) {
                assertImei(imei);
            }
        }
    }

    /**
     * Tests that the device properly reports either a valid IMEI or null.
     */
    @Test
    public void testGetImeiForSlot() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }

        for (int i = 0; i < mTelephonyManager.getPhoneCount(); i++) {
            String imei = mTelephonyManager.getImei(i);
            if (!TextUtils.isEmpty(imei)) {
                assertImei(imei);
            }
        }

        // Also verify that no exception is thrown for any slot index (including invalid ones)
        mTelephonyManager.getImei(-1);
        mTelephonyManager.getImei(mTelephonyManager.getPhoneCount());
    }

    /**
     * Tests that the device properly reports either a valid MEID or null.
     */
    @Test
    public void testGetMeid() {
        String meid = mTelephonyManager.getMeid();

        if (mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            if (mTelephonyManager.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
                assertMeidEsn(meid);
            }
        }
    }

    /**
     * Tests that the device properly reports either a valid MEID or null.
     */
    @Test
    public void testGetMeidForSlot() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }

        SubscriptionManager sm = (SubscriptionManager) getContext()
                .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        List<SubscriptionInfo> subInfos = sm.getActiveSubscriptionInfoList();

        if (subInfos != null) {
            for (SubscriptionInfo subInfo : subInfos) {
                int slotIndex = subInfo.getSimSlotIndex();
                int subId = subInfo.getSubscriptionId();
                TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
                if (tm.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA) {
                    String meid = mTelephonyManager.getMeid(slotIndex);
                    if (!TextUtils.isEmpty(meid)) {
                        assertMeidEsn(meid);
                    }
                }
            }
        }

        // Also verify that no exception is thrown for any slot index (including invalid ones)
        mTelephonyManager.getMeid(-1);
        mTelephonyManager.getMeid(mTelephonyManager.getPhoneCount());
    }

    /**
     * Tests sendDialerSpecialCode API.
     * Expects a security exception since the caller does not have carrier privileges or is not the
     * current default dialer app.
     */
    @Test
    public void testSendDialerSpecialCode() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            Log.d(TAG, "Skipping test that requires FEATURE_TELEPHONY");
            return;
        }
        try {
            mTelephonyManager.sendDialerSpecialCode("4636");
            fail("Expected SecurityException. App does not have carrier privileges or is not the "
                    + "default dialer app");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Tests that the device properly reports the contents of EF_FPLMN or null
     */
    @Test
    public void testGetForbiddenPlmns() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }
        String[] plmns = mTelephonyManager.getForbiddenPlmns();

        int phoneType = mTelephonyManager.getPhoneType();
        switch (phoneType) {
            case TelephonyManager.PHONE_TYPE_GSM:
                assertNotNull("Forbidden PLMNs must be valid or an empty list!", plmns);
            case TelephonyManager.PHONE_TYPE_CDMA:
            case TelephonyManager.PHONE_TYPE_NONE:
                if (plmns == null) {
                    return;
                }
        }

        for(String plmn : plmns) {
            assertTrue(
                    "Invalid Length for PLMN-ID, must be 5 or 6! plmn=" + plmn,
                    plmn.length() >= 5 && plmn.length() <= 6);
            assertTrue(
                    "PLMNs must be strings of digits 0-9! plmn=" + plmn,
                    android.text.TextUtils.isDigitsOnly(plmn));
        }
    }

    /**
     * Verify that TelephonyManager.getCardIdForDefaultEuicc returns a positive value or either
     * UNINITIALIZED_CARD_ID or UNSUPPORTED_CARD_ID.
     */
    @Test
    public void testGetCardIdForDefaultEuicc() {
        int cardId = mTelephonyManager.getCardIdForDefaultEuicc();
        assertTrue("Card ID for default EUICC is not a valid value",
                cardId == TelephonyManager.UNSUPPORTED_CARD_ID
                || cardId == TelephonyManager.UNINITIALIZED_CARD_ID
                || cardId >= 0);
    }

    /**
     * Tests that a SecurityException is thrown when trying to access UiccCardsInfo.
     */
    @Test
    public void testGetUiccCardsInfoException() {
        try {
            // Requires READ_PRIVILEGED_PHONE_STATE or carrier privileges
            List<UiccCardInfo> infos = mTelephonyManager.getUiccCardsInfo();
            fail("Expected SecurityException. App does not have carrier privileges");
        } catch (SecurityException e) {
        }
    }

    /**
     * Tests that UiccCardsInfo methods don't crash.
     */
    @Test
    public void testGetUiccCardsInfo() {
        // Requires READ_PRIVILEGED_PHONE_STATE or carrier privileges
        List<UiccCardInfo> infos =
                ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
                (tm) -> tm.getUiccCardsInfo());
        // test that these methods don't crash
        if (infos.size() > 0) {
            UiccCardInfo info = infos.get(0);
            info.getIccId();
            info.getEid();
            info.isRemovable();
            info.isEuicc();
            info.getCardId();
            info.getSlotIndex();
        }
    }

    private static Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    /**
     * Tests that the device properly sets the network selection mode to automatic.
     * Expects a security exception since the caller does not have carrier privileges.
     */
    @Test
    public void testSetNetworkSelectionModeAutomatic() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            Log.d(TAG, "Skipping test that requires FEATURE_TELEPHONY");
            return;
        }
        try {
            mTelephonyManager.setNetworkSelectionModeAutomatic();
            fail("Expected SecurityException. App does not have carrier privileges.");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Tests that the device properly asks the radio to connect to the input network and change
     * selection mode to manual.
     * Expects a security exception since the caller does not have carrier privileges.
     */
    @Test
    public void testSetNetworkSelectionModeManual() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            Log.d(TAG, "Skipping test that requires FEATURE_TELEPHONY");
            return;
        }
        try {
            mTelephonyManager.setNetworkSelectionModeManual(
                    "" /* operatorNumeric */, false /* persistSelection */);
            fail("Expected SecurityException. App does not have carrier privileges.");
        } catch (SecurityException expected) {
        }
    }

    /**
     * Construct a CallAttributes object and test getters.
     */
    @Test
    public void testCallAttributes() {
        CallQuality cq = new CallQuality();
        PreciseCallState pcs = new PreciseCallState();
        CallAttributes ca = new CallAttributes(pcs, TelephonyManager.NETWORK_TYPE_UNKNOWN, cq);
        assertEquals(pcs, ca.getPreciseCallState());
        assertEquals(TelephonyManager.NETWORK_TYPE_UNKNOWN, ca.getNetworkType());
        assertEquals(cq, ca.getCallQuality());
    }

    /**
     * Checks that a zeroed-out default CallQuality object can be created
     */
    @Test
    public void testCallQuality() {
        CallQuality cq = new CallQuality();
        assertEquals(0, cq.getDownlinkCallQualityLevel());
        assertEquals(0, cq.getUplinkCallQualityLevel());
        assertEquals(0, cq.getCallDuration());
        assertEquals(0, cq.getNumRtpPacketsTransmitted());
        assertEquals(0, cq.getNumRtpPacketsReceived());
        assertEquals(0, cq.getNumRtpPacketsTransmittedLost());
        assertEquals(0, cq.getNumRtpPacketsNotReceived());
        assertEquals(0, cq.getAverageRelativeJitter());
        assertEquals(0, cq.getMaxRelativeJitter());
        assertEquals(0, cq.getAverageRoundTripTime());
        assertEquals(0, cq.getCodecType());
    }

    /**
     * Tests TelephonyManager.getEmergencyNumberList.
     */
    @Test
    public void testGetEmergencyNumberList() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }
        Map<Integer, List<EmergencyNumber>> emergencyNumberList
          = mTelephonyManager.getEmergencyNumberList();

        assertFalse(emergencyNumberList == null);

        int defaultSubId = mSubscriptionManager.getDefaultSubscriptionId();
      
        // 112 and 911 should always be available
        // Reference: 3gpp 22.101, Section 10 - Emergency Calls
        assertTrue(checkIfEmergencyNumberListHasSpecificAddress(
            emergencyNumberList.get(defaultSubId), "911"));
        assertTrue(checkIfEmergencyNumberListHasSpecificAddress(
            emergencyNumberList.get(defaultSubId), "112"));

        // 000, 08, 110, 118, 119, and 999 should be always available when sim is absent
        // Reference: 3gpp 22.101, Section 10 - Emergency Calls
        if (mTelephonyManager.getPhoneCount() > 0
                && mSubscriptionManager.getSimStateForSlotIndex(0)
                    == TelephonyManager.SIM_STATE_ABSENT) {
            assertTrue(checkIfEmergencyNumberListHasSpecificAddress(
                emergencyNumberList.get(defaultSubId), "000"));
            assertTrue(checkIfEmergencyNumberListHasSpecificAddress(
                emergencyNumberList.get(defaultSubId), "08"));
            assertTrue(checkIfEmergencyNumberListHasSpecificAddress(
                emergencyNumberList.get(defaultSubId), "110"));
            assertTrue(checkIfEmergencyNumberListHasSpecificAddress(
                emergencyNumberList.get(defaultSubId), "118"));
            assertTrue(checkIfEmergencyNumberListHasSpecificAddress(
                emergencyNumberList.get(defaultSubId), "119"));
            assertTrue(checkIfEmergencyNumberListHasSpecificAddress(
                emergencyNumberList.get(defaultSubId), "999"));
        }
    }

    /**
     * Tests TelephonyManager.isEmergencyNumber.
     */
    @Test
    public void testIsEmergencyNumber() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }
        // 112 and 911 should always be available
        // Reference: 3gpp 22.101, Section 10 - Emergency Calls
        assertTrue(mTelephonyManager.isEmergencyNumber("911"));
        assertTrue(mTelephonyManager.isEmergencyNumber("112"));

        // 000, 08, 110, 118, 119, and 999 should be always available when sim is absent
        // Reference: 3gpp 22.101, Section 10 - Emergency Calls
        if (mTelephonyManager.getPhoneCount() > 0
                && mSubscriptionManager.getSimStateForSlotIndex(0)
                    == TelephonyManager.SIM_STATE_ABSENT) {
            assertTrue(mTelephonyManager.isEmergencyNumber("000"));
            assertTrue(mTelephonyManager.isEmergencyNumber("08"));
            assertTrue(mTelephonyManager.isEmergencyNumber("110"));
            assertTrue(mTelephonyManager.isEmergencyNumber("118"));
            assertTrue(mTelephonyManager.isEmergencyNumber("119"));
            assertTrue(mTelephonyManager.isEmergencyNumber("999"));
        }
    }

    /**
     * Tests TelephonyManager.isPotentialEmergencyNumber.
     */
    @Test
    public void testIsPotentialEmergencyNumber() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }

        String countryIso = mTelephonyManager.getNetworkCountryIso();
        String potentialEmergencyAddress = "91112345";
        if (countryIso.equals("br") || countryIso.equals("cl") || countryIso.equals("ni")) {
            assertTrue(ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
                    (tm) -> tm.isPotentialEmergencyNumber(potentialEmergencyAddress)));
        } else {
            assertFalse(ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
                    (tm) -> tm.isPotentialEmergencyNumber(potentialEmergencyAddress)));
        }
    }

    /**
     * Tests {@link TelephonyManager#getSupportedRadioAccessFamily()}
     */
    @Test
    public void testGetRadioAccessFamily() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }
        long raf = ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
                (tm) -> tm.getSupportedRadioAccessFamily());
        assertThat(raf).isNotEqualTo(TelephonyManager.NETWORK_TYPE_BITMASK_UNKNOWN);
    }

    private static void assertSetOpportunisticSubSuccess(int value) {
        assertThat(value).isEqualTo(TelephonyManager.SET_OPPORTUNISTIC_SUB_SUCCESS);
    }

    private static void assertSetOpportunisticInvalidParameter(int value) {
        assertThat(value).isEqualTo(TelephonyManager.SET_OPPORTUNISTIC_SUB_INACTIVE_SUBSCRIPTION);
    }

    /**
     * Tests {@link TelephonyManager#setPreferredOpportunisticDataSubscription} and
     * {@link TelephonyManager#getPreferredOpportunisticDataSubscription}
     */
    @Test
    public void testPreferredOpportunisticDataSubscription() {
        int randomSubId = 1;
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }
        ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
            (tm) -> tm.setPreferredOpportunisticDataSubscription(
                SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, false,
                null, null));
        // wait for the data change to take effect
        waitForMs(500);
        int subId =
            ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
                (tm) -> tm.getPreferredOpportunisticDataSubscription());
        assertEquals(subId, SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
        List<SubscriptionInfo> subscriptionInfoList =
                    ShellIdentityUtils.invokeMethodWithShellPermissions(mSubscriptionManager,
                            (tm) -> tm.getOpportunisticSubscriptions());
        Consumer<Integer> callbackSuccess = TelephonyManagerTest::assertSetOpportunisticSubSuccess;
        Consumer<Integer> callbackFailure =
                TelephonyManagerTest::assertSetOpportunisticInvalidParameter;
        if (subscriptionInfoList == null || subscriptionInfoList.size() == 0) {
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                    (tm) -> tm.setPreferredOpportunisticDataSubscription(randomSubId, false,
                            AsyncTask.SERIAL_EXECUTOR, callbackFailure));
            // wait for the data change to take effect
            waitForMs(500);
            subId = ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
                    (tm) -> tm.getPreferredOpportunisticDataSubscription());
            assertEquals(subId, SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);

        } else {
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                    (tm) -> tm.setPreferredOpportunisticDataSubscription(
                            subscriptionInfoList.get(0).getSubscriptionId(), false,
                            AsyncTask.SERIAL_EXECUTOR, callbackSuccess));
            // wait for the data change to take effect
            waitForMs(500);
            subId = ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
                (tm) -> tm.getPreferredOpportunisticDataSubscription());
            assertEquals(subId, subscriptionInfoList.get(0).getSubscriptionId());
        }

        ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                (tm) -> tm.setPreferredOpportunisticDataSubscription(
                        SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, false,
                        AsyncTask.SERIAL_EXECUTOR, callbackSuccess));
        // wait for the data change to take effect
        waitForMs(500);
        subId = ShellIdentityUtils.invokeMethodWithShellPermissions(mTelephonyManager,
            (tm) -> tm.getPreferredOpportunisticDataSubscription());
        assertEquals(subId, SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
    }

    private static void assertUpdateAvailableNetworkSuccess(int value) {
        assertThat(value).isEqualTo(TelephonyManager.UPDATE_AVAILABLE_NETWORKS_SUCCESS);
    }

    private static void assertUpdateAvailableNetworkInvalidArguments(int value) {
        assertThat(value).isEqualTo(TelephonyManager.UPDATE_AVAILABLE_NETWORKS_INVALID_ARGUMENTS);
    }

    private static boolean checkIfEmergencyNumberListHasSpecificAddress(
            List<EmergencyNumber> emergencyNumberList, String address) {
        for (EmergencyNumber emergencyNumber : emergencyNumberList) {
            if (address.equals(emergencyNumber.getNumber())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tests {@link TelephonyManager#updateAvailableNetworks}
     */
    @Test
    public void testUpdateAvailableNetworks() {
        int randomSubId = 1;
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }

        List<SubscriptionInfo> subscriptionInfoList =
            ShellIdentityUtils.invokeMethodWithShellPermissions(mSubscriptionManager,
                (tm) -> tm.getOpportunisticSubscriptions());
        List<String> mccMncs = new ArrayList<String>();
        List<Integer> bands = new ArrayList<Integer>();
        List<AvailableNetworkInfo> availableNetworkInfos = new ArrayList<AvailableNetworkInfo>();
        Consumer<Integer> callbackSuccess =
                TelephonyManagerTest::assertUpdateAvailableNetworkSuccess;
        Consumer<Integer> callbackFailure =
                TelephonyManagerTest::assertUpdateAvailableNetworkInvalidArguments;
        if (subscriptionInfoList == null || subscriptionInfoList.size() == 0
                || !mSubscriptionManager.isActiveSubscriptionId(
                        subscriptionInfoList.get(0).getSubscriptionId())) {
            AvailableNetworkInfo availableNetworkInfo = new AvailableNetworkInfo(randomSubId,
                    AvailableNetworkInfo.PRIORITY_HIGH, mccMncs, bands);
            availableNetworkInfos.add(availableNetworkInfo);
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                    (tm) -> tm.updateAvailableNetworks(availableNetworkInfos,
                            AsyncTask.SERIAL_EXECUTOR, callbackFailure));
        } else {
            AvailableNetworkInfo availableNetworkInfo = new AvailableNetworkInfo(
                    subscriptionInfoList.get(0).getSubscriptionId(),
                    AvailableNetworkInfo.PRIORITY_HIGH, mccMncs, bands);
            availableNetworkInfos.add(availableNetworkInfo);
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                    (tm) -> tm.updateAvailableNetworks(availableNetworkInfos,
                            AsyncTask.SERIAL_EXECUTOR, callbackSuccess));
            availableNetworkInfos.clear();
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mTelephonyManager,
                    (tm) -> tm.updateAvailableNetworks(availableNetworkInfos,
                            AsyncTask.SERIAL_EXECUTOR, callbackFailure));
        }
    }

    @Test
    public void testIccOpenLogicalChannelBySlot() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }
        // just verify no crash
        try {
            ShellIdentityUtils.invokeMethodWithShellPermissions(
                    mTelephonyManager, (tm) -> tm.iccOpenLogicalChannelBySlot(0, null, 0));
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is okay, just not SecurityException
        }
    }

    @Test
    public void testIccCloseLogicalChannelBySlot() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }
        // just verify no crash
        try {
            ShellIdentityUtils.invokeMethodWithShellPermissions(
                    mTelephonyManager, (tm) -> tm.iccCloseLogicalChannelBySlot(0, 0));
        } catch (IllegalArgumentException e) {
            // IllegalArgumentException is okay, just not SecurityException
        }
    }

    @Test
    public void testIccTransmitApduLogicalChannelBySlot() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }
        // just verify no crash
        try {
            ShellIdentityUtils.invokeMethodWithShellPermissions(
                    mTelephonyManager, (tm) -> tm.iccTransmitApduLogicalChannelBySlot(
                            0 /* slotIndex */,
                            0 /* channel */,
                            0 /* cla */,
                            0 /* instruction */,
                            0 /* p1 */,
                            0 /* p2 */,
                            0 /* p3 */,
                            null /* data */));
        } catch (IllegalArgumentException e ) {
            // IllegalArgumentException is okay, just not SecurityException
        }
    }

    @Test
    public void testIccTransmitApduBasicChannelBySlot() {
        if (!mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return;
        }
        // just verify no crash
        try {
            ShellIdentityUtils.invokeMethodWithShellPermissions(
                    mTelephonyManager, (tm) -> tm.iccTransmitApduBasicChannelBySlot(
                            0 /* slotIndex */,
                            0 /* cla */,
                            0 /* instruction */,
                            0 /* p1 */,
                            0 /* p2 */,
                            0 /* p3 */,
                            null /* data */));
        } catch (IllegalArgumentException e ) {
            // IllegalArgumentException is okay, just not SecurityException
        }
    }

    public static void waitForMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Log.d(TAG, "InterruptedException while waiting: " + e);
        }
    }
}
