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

package android.carrierapi.cts;

import static org.junit.Assert.assertArrayEquals;

import static android.telephony.IccOpenLogicalChannelResponse.INVALID_CHANNEL;
import static android.telephony.IccOpenLogicalChannelResponse.STATUS_NO_ERROR;
import static android.telephony.IccOpenLogicalChannelResponse.STATUS_UNKNOWN_ERROR;
import static android.carrierapi.cts.FcpTemplate.FILE_IDENTIFIER;
import static android.carrierapi.cts.IccUtils.bytesToHexString;
import static android.carrierapi.cts.IccUtils.hexStringToBytes;

import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PersistableBundle;
import android.provider.Telephony;
import android.provider.VoicemailContract;
import android.telephony.CarrierConfigManager;
import android.telephony.IccOpenLogicalChannelResponse;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.AndroidTestCase;
import android.util.Log;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

// TODO(b/130187425): Split CarrierApiTest apart to have separate test classes for functionality
public class CarrierApiTest extends AndroidTestCase {
    private static final String TAG = "CarrierApiTest";
    private TelephonyManager mTelephonyManager;
    private CarrierConfigManager mCarrierConfigManager;
    private PackageManager mPackageManager;
    private SubscriptionManager mSubscriptionManager;
    private ContentProviderClient mVoicemailProvider;
    private ContentProviderClient mStatusProvider;
    private Uri mVoicemailContentUri;
    private Uri mStatusContentUri;
    private boolean hasCellular;
    private String selfPackageName;
    private String selfCertHash;
    private HandlerThread mListenerThread;

    private static final String FiDevCert = "24EB92CBB156B280FA4E1429A6ECEEB6E5C1BFE4";
    // The minimum allocatable logical channel number, per TS 102 221 Section 11.1.17.1
    private static final int MIN_LOGICAL_CHANNEL = 1;
    // The maximum allocatable logical channel number in the standard range, per TS 102 221 Section
    // 11.1.17.1
    private static final int MAX_LOGICAL_CHANNEL = 3;
    // Class bytes. The logical channel used should be included for bits b2b1. TS 102 221 Table 11.5
    private static final int CLA_GET_RESPONSE = 0x00;
    private static final int CLA_MANAGE_CHANNEL = 0x00;
    private static final int CLA_READ_BINARY = 0x00;
    private static final int CLA_SELECT = 0x00;
    private static final int CLA_STATUS = 0x80;
    // APDU Instruction Bytes. TS 102 221 Section 10.1.2
    private static final int COMMAND_GET_RESPONSE = 0xC0;
    private static final int COMMAND_MANAGE_CHANNEL = 0x70;
    private static final int COMMAND_READ_BINARY = 0xB0;
    private static final int COMMAND_SELECT = 0xA4;
    private static final int COMMAND_STATUS = 0xF2;
    // Status words. TS 102 221 Section 10.2.1
    private static final byte[] STATUS_NORMAL = {(byte) 0x90, (byte) 0x00};
    private static final String STATUS_NORMAL_STRING = "9000";
    private static final String STATUS_BYTES_REMAINING = "61";
    private static final String STATUS_WARNING_A = "62";
    private static final String STATUS_WARNING_B = "63";
    private static final String STATUS_FILE_NOT_FOUND = "6a82";
    private static final String STATUS_FUNCTION_NOT_SUPPORTED = "6a81";
    private static final String STATUS_INCORRECT_PARAMETERS = "6a86";
    private static final String STATUS_WRONG_CLASS = "6e00";
    // File ID for the EF ICCID. TS 102 221
    private static final String ICCID_FILE_ID = "2FE2";
    // File ID for the master file. TS 102 221
    private static final String MF_FILE_ID = "3F00";
    // File ID for the MF Access Rule Reference. TS 102 221
    private static final String MF_ARR_FILE_ID = "2F06";
    private static final String ALPHA_TAG_A = "tagA";
    private static final String ALPHA_TAG_B = "tagB";
    private static final String NUMBER_A = "1234567890";
    private static final String NUMBER_B = "0987654321";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTelephonyManager = (TelephonyManager)
                getContext().getSystemService(Context.TELEPHONY_SERVICE);
        mCarrierConfigManager = (CarrierConfigManager)
                getContext().getSystemService(Context.CARRIER_CONFIG_SERVICE);
        mPackageManager = getContext().getPackageManager();
        mSubscriptionManager = (SubscriptionManager)
                getContext().getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        selfPackageName = getContext().getPackageName();
        selfCertHash = getCertHash(selfPackageName);
        mVoicemailContentUri = VoicemailContract.Voicemails.buildSourceUri(selfPackageName);
        mVoicemailProvider = getContext().getContentResolver()
                .acquireContentProviderClient(mVoicemailContentUri);
        mStatusContentUri = VoicemailContract.Status.buildSourceUri(selfPackageName);
        mStatusProvider = getContext().getContentResolver()
                .acquireContentProviderClient(mStatusContentUri);
        mListenerThread = new HandlerThread("CarrierApiTest");
        mListenerThread.start();
        hasCellular = hasCellular();
        if (!hasCellular) {
            Log.e(TAG, "No cellular support, all tests will be skipped.");
        }

        // We need to close all logical channels in the standard range, [1, 3], before each test.
        // This makes sure each SIM-related test starts with a clean slate.
        for (int i = MIN_LOGICAL_CHANNEL; i <= MAX_LOGICAL_CHANNEL; i++) {
            mTelephonyManager.iccCloseLogicalChannel(i);
        }
    }

    @Override
    public void tearDown() throws Exception {
        // We need to close all logical channels in the standard range, [1, 3], after each test.
        // This makes sure each SIM-related test releases any opened channels.
        for (int i = MIN_LOGICAL_CHANNEL; i <= MAX_LOGICAL_CHANNEL; i++) {
            mTelephonyManager.iccCloseLogicalChannel(i);
        }

        mListenerThread.quit();
        try {
            mStatusProvider.delete(mStatusContentUri, null, null);
            mVoicemailProvider.delete(mVoicemailContentUri, null, null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to clean up voicemail tables in tearDown", e);
        }
        super.tearDown();
    }

    /**
     * Checks whether the cellular stack should be running on this device.
     */
    private boolean hasCellular() {
        return mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
                mTelephonyManager.getPhoneCount() > 0;
    }

    private boolean isSimCardPresent() {
        return mTelephonyManager.getPhoneType() != TelephonyManager.PHONE_TYPE_NONE &&
                mTelephonyManager.getSimState() != TelephonyManager.SIM_STATE_ABSENT;
    }

    private String getCertHash(String pkgName) {
        try {
            PackageInfo pInfo = mPackageManager.getPackageInfo(pkgName,
                    PackageManager.GET_SIGNATURES | PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return bytesToHexString(md.digest(pInfo.signatures[0].toByteArray()));
        } catch (PackageManager.NameNotFoundException ex) {
            Log.e(TAG, pkgName + " not found", ex);
        } catch (NoSuchAlgorithmException ex) {
            Log.e(TAG, "Algorithm SHA1 is not found.");
        }
        return "";
    }

    private void failMessage() {
        if (FiDevCert.equalsIgnoreCase(selfCertHash)) {
            fail("This test requires a Project Fi SIM card.");
        } else {
            fail("This test requires a SIM card with carrier privilege rule on it.\n" +
                 "Cert hash: " + selfCertHash + "\n" +
                 "Visit https://source.android.com/devices/tech/config/uicc.html");
        }
    }

    public void testSimCardPresent() {
        if (!hasCellular) return;
        assertTrue("This test requires SIM card.", isSimCardPresent());
    }

    public void testHasCarrierPrivileges() {
        if (!hasCellular) return;
        if (!mTelephonyManager.hasCarrierPrivileges()) {
            failMessage();
        }
    }

    public void testGetIccAuthentication() {
        // EAP-SIM rand is 16 bytes.
        String base64Challenge = "ECcTqwuo6OfY8ddFRboD9WM=";
        String base64Challenge2 = "EMNxjsFrPCpm+KcgCmQGnwQ=";
        if (!hasCellular) return;
        try {
            assertNull("getIccAuthentication should return null for empty data.",
                    mTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                    TelephonyManager.AUTHTYPE_EAP_AKA, ""));
            String response = mTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                    TelephonyManager.AUTHTYPE_EAP_SIM, base64Challenge);
            assertTrue("Response to EAP-SIM Challenge must not be Null.", response != null);
            // response is base64 encoded. After decoding, the value should be:
            // 1 length byte + SRES(4 bytes) + 1 length byte + Kc(8 bytes)
            byte[] result = android.util.Base64.decode(response, android.util.Base64.DEFAULT);
            assertTrue("Result length must be 14 bytes.", 14 == result.length);
            String response2 = mTelephonyManager.getIccAuthentication(TelephonyManager.APPTYPE_USIM,
                    TelephonyManager.AUTHTYPE_EAP_SIM, base64Challenge2);
            assertTrue("Two responses must be different.", !response.equals(response2));
        } catch (SecurityException e) {
            failMessage();
        }
    }

    public void testSendDialerSpecialCode() {
        if (!hasCellular) return;
        try {
            IntentReceiver intentReceiver = new IntentReceiver();
            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Telephony.Sms.Intents.SECRET_CODE_ACTION);
            intentFilter.addDataScheme("android_secret_code");
            getContext().registerReceiver(intentReceiver, intentFilter);

            mTelephonyManager.sendDialerSpecialCode("4636");
            assertTrue("Did not receive expected Intent: " +
                    Telephony.Sms.Intents.SECRET_CODE_ACTION,
                    intentReceiver.waitForReceive());
        } catch (SecurityException e) {
            failMessage();
        } catch (InterruptedException e) {
            Log.d(TAG, "Broadcast receiver wait was interrupted.");
        }
    }

    public void testSubscriptionInfoListing() {
        if (!hasCellular) return;
        try {
            assertTrue("getActiveSubscriptionInfoCount() should be non-zero",
                    mSubscriptionManager.getActiveSubscriptionInfoCount() > 0);
            List<SubscriptionInfo> subInfoList =
                    mSubscriptionManager.getActiveSubscriptionInfoList();
            assertNotNull("getActiveSubscriptionInfoList() returned null", subInfoList);
            assertFalse("getActiveSubscriptionInfoList() returned an empty list",
                    subInfoList.isEmpty());
            for (SubscriptionInfo info : subInfoList) {
                TelephonyManager tm =
                        mTelephonyManager.createForSubscriptionId(info.getSubscriptionId());
                assertTrue("getActiveSubscriptionInfoList() returned an inaccessible subscription",
                        tm.hasCarrierPrivileges());

                // Check other APIs to make sure they are accessible and return consistent info.
                SubscriptionInfo infoForSlot =
                        mSubscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(
                                info.getSimSlotIndex());
                assertNotNull("getActiveSubscriptionInfoForSimSlotIndex() returned null",
                        infoForSlot);
                assertEquals(
                        "getActiveSubscriptionInfoForSimSlotIndex() returned inconsistent info",
                        info.getSubscriptionId(), infoForSlot.getSubscriptionId());

                SubscriptionInfo infoForSubId =
                        mSubscriptionManager.getActiveSubscriptionInfo(info.getSubscriptionId());
                assertNotNull("getActiveSubscriptionInfo() returned null", infoForSubId);
                assertEquals("getActiveSubscriptionInfo() returned inconsistent info",
                        info.getSubscriptionId(), infoForSubId.getSubscriptionId());
            }
        } catch (SecurityException e) {
            failMessage();
        }
    }

    public void testCarrierConfigIsAccessible() {
        if (!hasCellular) return;
        try {
            PersistableBundle bundle = mCarrierConfigManager.getConfig();
            assertNotNull("CarrierConfigManager#getConfig() returned null", bundle);
            assertFalse("CarrierConfigManager#getConfig() returned empty bundle", bundle.isEmpty());

            int subId = SubscriptionManager.getDefaultSubscriptionId();
            bundle = mCarrierConfigManager.getConfigForSubId(subId);
            assertNotNull("CarrierConfigManager#getConfigForSubId() returned null", bundle);
            assertFalse("CarrierConfigManager#getConfigForSubId() returned empty bundle",
                    bundle.isEmpty());
        } catch (SecurityException e) {
            failMessage();
        }
    }

    public void testTelephonyApisAreAccessible() {
        if (!hasCellular) return;
        // The following methods may return any value depending on the state of the device. Simply
        // call them to make sure they do not throw any exceptions.
        try {
            mTelephonyManager.getDeviceSoftwareVersion();
            mTelephonyManager.getDeviceId();
            mTelephonyManager.getImei();
            mTelephonyManager.getMeid();
            mTelephonyManager.getNai();
            mTelephonyManager.getDataNetworkType();
            mTelephonyManager.getVoiceNetworkType();
            mTelephonyManager.getSimSerialNumber();
            mTelephonyManager.getSubscriberId();
            mTelephonyManager.getGroupIdLevel1();
            mTelephonyManager.getLine1Number();
            mTelephonyManager.getVoiceMailNumber();
            mTelephonyManager.getVisualVoicemailPackageName();
            mTelephonyManager.getVoiceMailAlphaTag();
            mTelephonyManager.getForbiddenPlmns();
            mTelephonyManager.getServiceState();
        } catch (SecurityException e) {
            failMessage();
        }
        // For APIs which take a slot ID, we should be able to call them without getting a
        // SecurityException for at last one valid slot ID.
        // TODO(b/112441100): Simplify this test once slot ID APIs are cleaned up.
        boolean hasReadableSlot = false;
        for (int slotIndex = 0; slotIndex < mTelephonyManager.getPhoneCount(); slotIndex++) {
            try {
                mTelephonyManager.getDeviceId(slotIndex);
                mTelephonyManager.getImei(slotIndex);
                mTelephonyManager.getMeid(slotIndex);
                hasReadableSlot = true;
                break;
            } catch (SecurityException e) {
                // Move on to the next slot.
            }
        }
        assertTrue("Unable to read device identifiers for any slot index", hasReadableSlot);
    }

    public void testVoicemailTableIsAccessible() throws Exception {
        if (!hasCellular) return;
        ContentValues value = new ContentValues();
        value.put(VoicemailContract.Voicemails.NUMBER, "0123456789");
        value.put(VoicemailContract.Voicemails.SOURCE_PACKAGE, selfPackageName);
        try {
            Uri uri = mVoicemailProvider.insert(mVoicemailContentUri, value);
            assertNotNull(uri);
            Cursor cursor = mVoicemailProvider.query(uri,
                    new String[] {
                            VoicemailContract.Voicemails.NUMBER,
                            VoicemailContract.Voicemails.SOURCE_PACKAGE
                    }, null, null, null);
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            assertEquals("0123456789", cursor.getString(0));
            assertEquals(selfPackageName, cursor.getString(1));
            assertFalse(cursor.moveToNext());
        } catch (SecurityException e) {
            failMessage();
        }
    }

    public void testVoicemailStatusTableIsAccessible() throws Exception {
        if (!hasCellular) return;
        ContentValues value = new ContentValues();
        value.put(VoicemailContract.Status.CONFIGURATION_STATE,
                VoicemailContract.Status.CONFIGURATION_STATE_OK);
        value.put(VoicemailContract.Status.SOURCE_PACKAGE, selfPackageName);
        try {
            Uri uri = mStatusProvider.insert(mStatusContentUri, value);
            assertNotNull(uri);
            Cursor cursor = mVoicemailProvider.query(uri,
                    new String[] {
                            VoicemailContract.Status.CONFIGURATION_STATE,
                            VoicemailContract.Status.SOURCE_PACKAGE
                    }, null, null, null);
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            assertEquals(VoicemailContract.Status.CONFIGURATION_STATE_OK, cursor.getInt(0));
            assertEquals(selfPackageName, cursor.getString(1));
            assertFalse(cursor.moveToNext());
        } catch (SecurityException e) {
            failMessage();
        }
    }

    public void testPhoneStateListener() throws Exception {
        if (!hasCellular) return;
        final AtomicReference<SecurityException> error = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(mListenerThread.getLooper()).post(() -> {
            PhoneStateListener listener = new PhoneStateListener() {};
            try {
                mTelephonyManager.listen(
                        listener, PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR);
                mTelephonyManager.listen(
                        listener, PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR);
            } catch (SecurityException e) {
                error.set(e);
            } finally {
                mTelephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE);
                latch.countDown();
            }
        });
        assertTrue("Test timed out", latch.await(30L, TimeUnit.SECONDS));
        if (error.get() != null) {
            failMessage();
        }
    }

    public void testSubscriptionInfoChangeListener() throws Exception {
        if (!hasCellular) return;
        final AtomicReference<SecurityException> error = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(mListenerThread.getLooper()).post(() -> {
            SubscriptionManager.OnSubscriptionsChangedListener listener =
                    new SubscriptionManager.OnSubscriptionsChangedListener();
            try {
                mSubscriptionManager.addOnSubscriptionsChangedListener(listener);
            } catch (SecurityException e) {
                error.set(e);
            } finally {
                mSubscriptionManager.removeOnSubscriptionsChangedListener(listener);
                latch.countDown();
            }
        });
        assertTrue("Test timed out", latch.await(30L, TimeUnit.SECONDS));
        if (error.get() != null) {
            failMessage();
        }

    }

    /**
     * Test that it's possible to open logical channels to the ICC. This mirrors the Manage Channel
     * command described in TS 102 221 Section 11.1.17.
     */
    public void testIccOpenLogicalChannel() {
        if (!hasCellular) return;

        // The AID here doesn't matter - we just need to open a valid connection. In this case, the
        // specified AID ("") opens a channel and selects the MF.
        IccOpenLogicalChannelResponse response = mTelephonyManager.iccOpenLogicalChannel("");
        verifyValidIccOpenLogicalChannelResponse(response);
        mTelephonyManager.iccCloseLogicalChannel(response.getChannel());

        // {@link TelephonyManager#iccOpenLogicalChannel} sends a Manage Channel (open) APDU
        // followed by a Select APDU with the given AID and p2 values. See Open Mobile API
        // Specification v3.2 Section 6.2.7.h and TS 102 221 for details.
        int p2 = 0;
        response = mTelephonyManager.iccOpenLogicalChannel("", p2);
        verifyValidIccOpenLogicalChannelResponse(response);
        mTelephonyManager.iccCloseLogicalChannel(response.getChannel());

        // Only values 0x00, 0x04, 0x08, and 0x0C are allowed for p2. Any p2 values that produce
        // non '9000'/'62xx'/'63xx' status words are treated as an error and the channel is not
        // opened.
        p2 = 0x02;
        response = mTelephonyManager.iccOpenLogicalChannel("", p2);
        assertEquals(INVALID_CHANNEL, response.getChannel());
        assertEquals(STATUS_UNKNOWN_ERROR, response.getStatus());
    }

    /**
     * Test that it's possible to close logical channels to the ICC. This follows the Manage Channel
     * command described in TS 102 221 Section 11.1.17.
     */
    public void testIccCloseLogicalChannel() {
        if (!hasCellular) return;

        // The directory here doesn't matter - we just need to open a valid connection that can
        // later be closed. In this case, the specified AID ("") opens a channel and selects the MF.
        IccOpenLogicalChannelResponse response = mTelephonyManager.iccOpenLogicalChannel("");
        // Check that the select command succeeded. This ensures that the logical channel is indeed
        // open.
        assertArrayEquals(STATUS_NORMAL, response.getSelectResponse());
        assertTrue(mTelephonyManager.iccCloseLogicalChannel(response.getChannel()));

        // Close opened channel twice.
        assertFalse(mTelephonyManager.iccCloseLogicalChannel(response.getChannel()));

        // Close channel that is not open.
        assertFalse(mTelephonyManager.iccCloseLogicalChannel(2));

        // Channel 0 is guaranteed to be always available and cannot be closed, per TS 102 221
        // Section 11.1.17
        assertFalse(mTelephonyManager.iccCloseLogicalChannel(0));
    }

    /**
     * This test ensures that valid APDU instructions can be sent and processed by the ICC. To do
     * so, APDUs are sent to:
     * - get the status of the MF
     * - select the Access Rule Reference (ARR) for the MF
     * - get the FCP template response for the select
     */
    public void testIccTransmitApduLogicalChannel() {
        if (!hasCellular) return;

        // An open LC is required for transmitting APDU commands. This opens an LC to the MF.
        IccOpenLogicalChannelResponse logicalChannel = mTelephonyManager.iccOpenLogicalChannel("");

        // Get the status of the current directory. This should match the MF. TS 102 221 Section
        // 11.1.2
        int channel = logicalChannel.getChannel();
        int cla = CLA_STATUS;
        int p1 = 0; // no indication of application status
        int p2 = 0; // same response parameters as the SELECT in the iccOpenLogicalChannel() above
        int p3 = 0; // length of 'data' payload
        String data = "";
        String response = mTelephonyManager
                .iccTransmitApduLogicalChannel(channel, cla, COMMAND_STATUS, p1, p2, p3, data);
        FcpTemplate fcpTemplate = FcpTemplate.parseFcpTemplate(response);
        // Check that the FCP Template's file ID matches the MF
        assertTrue(containsFileId(fcpTemplate, MF_FILE_ID));
        assertEquals(STATUS_NORMAL_STRING, fcpTemplate.getStatus());

        // Select the Access Rule Reference for the MF. Similar to the MF, this will exist across
        // all SIM cards. TS 102 221 Section 11.1.1
        cla = CLA_SELECT;
        p1 = 0; // select EF by FID
        p2 = 0x04; // requesting FCP template
        p3 = 2; // data (FID to be selected) is 2 bytes
        data = MF_ARR_FILE_ID;
        response = mTelephonyManager
                .iccTransmitApduLogicalChannel(
                        channel, cla, COMMAND_SELECT, p1, p2, p3, data);
        // We requested an FCP template in the response for the select. This will be indicated by a
        // '61xx' response, where 'xx' is the number of bytes remaining.
        assertTrue(response.startsWith(STATUS_BYTES_REMAINING));

        // Read the FCP template from the ICC. TS 102 221 Section 12.1.1
        cla = CLA_GET_RESPONSE;
        p1 = 0;
        p2 = 0;
        p3 = 0;
        data = "";
        response = mTelephonyManager
                .iccTransmitApduLogicalChannel(
                        channel, cla, COMMAND_GET_RESPONSE, p1, p2, p3, data);
        fcpTemplate = FcpTemplate.parseFcpTemplate(response);
        // Check that the FCP Template's file ID matches the selected ARR
        assertTrue(containsFileId(fcpTemplate, MF_ARR_FILE_ID));
        assertEquals(STATUS_NORMAL_STRING, fcpTemplate.getStatus());

        mTelephonyManager.iccCloseLogicalChannel(channel);
    }

    /**
     * Tests several invalid APDU instructions over a logical channel and makes sure appropriate
     * errors are returned from the UICC.
     */
    public void testIccTransmitApduLogicalChannelWithInvalidInputs() {
        if (!hasCellular) return;

        // An open LC is required for transmitting apdu commands. This opens an LC to the MF.
        IccOpenLogicalChannelResponse logicalChannel = mTelephonyManager.iccOpenLogicalChannel("");
        int channel = logicalChannel.getChannel();

        // Make some invalid APDU commands and make sure they fail as expected.
        // Use an invalid p1 value for Status apdu
        int cla = CLA_STATUS | channel;
        int p1 = 0xFF; // only '00', '01', and '02' are allowed
        int p2 = 0; // same response parameters as the SELECT in the iccOpenLogicalChannel() above
        int p3 = 0; // length of 'data' payload
        String data = "";
        String response = mTelephonyManager
                .iccTransmitApduLogicalChannel(channel, cla, COMMAND_STATUS, p1, p2, p3, data);
        assertEquals(STATUS_INCORRECT_PARAMETERS, response);

        // Select a file that doesn't exist
        cla = CLA_SELECT;
        p1 = 0x00; // select by file ID
        p2 = 0x0C; // no data returned
        p3 = 0x02; // length of 'data' payload
        data = "FFFF"; // invalid file ID
        response = mTelephonyManager
                .iccTransmitApduLogicalChannel(channel, cla, COMMAND_SELECT, p1, p2, p3, data);
        assertEquals(STATUS_FILE_NOT_FOUND, response);

        // Manage channel with incorrect p1 parameter
        cla = CLA_MANAGE_CHANNEL | channel;
        p1 = 0x83; // Only '80' or '00' allowed for Manage Channel p1
        p2 = channel; // channel to be closed
        p3 = 0; // length of 'data' payload
        data = "";
        response = mTelephonyManager
            .iccTransmitApduLogicalChannel(channel, cla, COMMAND_MANAGE_CHANNEL, p1, p2, p3, data);
        assertEquals(STATUS_FUNCTION_NOT_SUPPORTED, response);

        // Use an incorrect class byte for Status apdu
        cla = 0xFF;
        p1 = 0; // no indication of application status
        p2 = 0; // same response parameters as the SELECT in the iccOpenLogicalChannel() above
        p3 = 0; // length of 'data' payload
        data = "";
        response = mTelephonyManager
            .iccTransmitApduLogicalChannel(channel, cla, COMMAND_STATUS, p1, p2, p3, data);
        assertEquals(STATUS_WRONG_CLASS, response);

        // Provide a data field that is longer than described for Select apdu
        cla = CLA_SELECT | channel;
        p1 = 0; // select by file ID
        p2 = 0x0C; // no data returned
        p3 = 0x04; // data passed is actually 2 bytes long
        data = "3F00"; // valid ID
        response = mTelephonyManager
            .iccTransmitApduLogicalChannel(channel, cla, COMMAND_SELECT, p1, p2, p3, data);
        assertTrue(isErrorResponse(response));

        // Use an invalid instruction
        cla = 0;
        p1 = 0;
        p2 = 0;
        p3 = 0;
        data = "";
        int invalidInstruction = 0xFF; // see TS 102 221 Table 10.5 for valid instructions
        response = mTelephonyManager
            .iccTransmitApduLogicalChannel(channel, cla, invalidInstruction, p1, p2, p3, data);
        assertTrue(isErrorResponse(response));

        mTelephonyManager.iccCloseLogicalChannel(channel);
    }

    /**
     * This test ensures that files can be read off the UICC. This helps to test the SIM booting
     * process, as it process involves several file-reads. The ICCID is one of the first files read.
     */
    public void testApduFileRead() {
        // Open a logical channel and select the MF.
        IccOpenLogicalChannelResponse logicalChannel = mTelephonyManager.iccOpenLogicalChannel("");
        int channel = logicalChannel.getChannel();

        // Select the ICCID. TS 102 221 Section 13.2
        int p1 = 0; // select by file ID
        int p2 = 0x0C; // no data returned
        int p3 = 2; // length of 'data' payload
        String response = mTelephonyManager.iccTransmitApduLogicalChannel(
                channel, CLA_SELECT, COMMAND_SELECT, p1, p2, p3, ICCID_FILE_ID);
        assertEquals(STATUS_NORMAL_STRING, response);

        // Read the contents of the ICCID.
        p1 = 0; // 0-byte offset
        p2 = 0; // 0-byte offset
        p3 = 0; // length of 'data' payload
        response = mTelephonyManager.iccTransmitApduLogicalChannel(
                channel, CLA_READ_BINARY, COMMAND_READ_BINARY, p1, p2, p3, "");
        assertTrue(response.endsWith(STATUS_NORMAL_STRING));

        mTelephonyManager.iccCloseLogicalChannel(channel);
    }

    /**
     * This test sends several valid APDU commands over the basic channel (channel 0).
     */
    public void testIccTransmitApduBasicChannel() {
        if (!hasCellular) return;

        // select the MF
        int cla = CLA_SELECT;
        int p1 = 0; // select EF by FID
        int p2 = 0x0C; // requesting FCP template
        int p3 = 2; // length of 'data' payload
        String data = MF_FILE_ID;
        String response = mTelephonyManager
            .iccTransmitApduBasicChannel(cla, COMMAND_SELECT, p1, p2, p3, data);
        assertEquals(STATUS_NORMAL_STRING, response);

        // get the Status of the current file/directory
        cla = CLA_STATUS;
        p1 = 0; // no indication of application status
        p2 = 0; // same response parameters as the SELECT in the iccOpenLogicalChannel() above
        p3 = 0; // length of 'data' payload
        data = "";
        response = mTelephonyManager
            .iccTransmitApduBasicChannel(cla, COMMAND_STATUS, p1, p2, p3, data);
        FcpTemplate fcpTemplate = FcpTemplate.parseFcpTemplate(response);
        assertTrue(containsFileId(fcpTemplate, MF_FILE_ID));

        // Manually open a logical channel
        cla = CLA_MANAGE_CHANNEL;
        p1 = 0; // open a logical channel
        p2 = 0; // '00' for open command
        p3 = 0; // length of data payload
        data = "";
        response = mTelephonyManager
            .iccTransmitApduBasicChannel(cla, COMMAND_MANAGE_CHANNEL, p1, p2, p3, data);
        // response is in the format | 1 byte: channel number | 2 bytes: status word |
        String responseStatus = response.substring(2);
        assertEquals(STATUS_NORMAL_STRING, responseStatus);

        // Close the open channel
        byte[] responseBytes = hexStringToBytes(response);
        int channel = responseBytes[0];
        cla = CLA_MANAGE_CHANNEL;
        p1 = 0x80; // close a logical channel
        p2 = channel; // the channel to be closed
        p3 = 0; // length of data payload
        data = "";
        response = mTelephonyManager
            .iccTransmitApduBasicChannel(cla, COMMAND_MANAGE_CHANNEL, p1, p2, p3, data);
        assertEquals(STATUS_NORMAL_STRING, response);
    }

    /**
     * This test verifies that {@link TelephonyManager#setLine1NumberForDisplay(String, String)}
     * correctly sets the Line 1 alpha tag and number when called.
     */
    public void testLine1NumberForDisplay() {
        // Cache original alpha tag and number values.
        String originalAlphaTag = mTelephonyManager.getLine1AlphaTag();
        String originalNumber = mTelephonyManager.getLine1Number();

        try {
            assertTrue(mTelephonyManager.setLine1NumberForDisplay(ALPHA_TAG_A, NUMBER_A));
            assertEquals(ALPHA_TAG_A, mTelephonyManager.getLine1AlphaTag());
            assertEquals(NUMBER_A, mTelephonyManager.getLine1Number());

            assertTrue(mTelephonyManager.setLine1NumberForDisplay(ALPHA_TAG_B, NUMBER_B));
            assertEquals(ALPHA_TAG_B, mTelephonyManager.getLine1AlphaTag());
            assertEquals(NUMBER_B, mTelephonyManager.getLine1Number());

            // null is used to clear the Line 1 alpha tag and number values.
            assertTrue(mTelephonyManager.setLine1NumberForDisplay(null, null));
            assertEquals("", mTelephonyManager.getLine1AlphaTag());
            assertEquals("", mTelephonyManager.getLine1Number());
        } finally {
            // Reset original alpha tag and number values.
            mTelephonyManager.setLine1NumberForDisplay(originalAlphaTag, originalNumber);
        }
    }

    /**
     * This test verifies that {@link TelephonyManager#setVoiceMailNumber(String, String)} correctly
     * sets the VoiceMail alpha tag and number when called.
     */
    public void testVoiceMailNumber() {
        // Cache original alpha tag and number values.
        String originalAlphaTag = mTelephonyManager.getVoiceMailAlphaTag();
        String originalNumber = mTelephonyManager.getVoiceMailNumber();

        try {
            assertTrue(mTelephonyManager.setVoiceMailNumber(ALPHA_TAG_A, NUMBER_A));
            assertEquals(ALPHA_TAG_A, mTelephonyManager.getVoiceMailAlphaTag());
            assertEquals(NUMBER_A, mTelephonyManager.getVoiceMailNumber());

            assertTrue(mTelephonyManager.setVoiceMailNumber(ALPHA_TAG_B, NUMBER_B));
            assertEquals(ALPHA_TAG_B, mTelephonyManager.getVoiceMailAlphaTag());
            assertEquals(NUMBER_B, mTelephonyManager.getVoiceMailNumber());
        } finally {
            // Reset original alpha tag and number values.
            mTelephonyManager.setVoiceMailNumber(originalAlphaTag, originalNumber);
        }
    }

    private void verifyValidIccOpenLogicalChannelResponse(IccOpenLogicalChannelResponse response) {
        // The assigned channel should be between the min and max allowed channel numbers
        int channel = response.getChannel();
        assertTrue(MIN_LOGICAL_CHANNEL <= channel && channel <= MAX_LOGICAL_CHANNEL);
        assertEquals(STATUS_NO_ERROR, response.getStatus());
        assertArrayEquals(STATUS_NORMAL, response.getSelectResponse());
    }

    /**
     * Checks whether the a {@code fcpTemplate} contains the given {@code fileId}.
     *
     * @param fcpTemplate The FCP Template to be checked.
     * @param fileId The file ID that is being searched for
     *
     * @return true iff fcpTemplate contains fileId.
     */
    private boolean containsFileId(FcpTemplate fcpTemplate, String fileId) {
        return fcpTemplate.getTlvs().stream().anyMatch(tlv ->
                tlv.getTag() == FILE_IDENTIFIER && tlv.getValue().equals(fileId));
    }

    /**
     * Returns true iff {@code response} indicates an error with the previous APDU.
     *
     * @param response The APDU response to be checked.
     *
     * @return true iff the given response indicates an error occurred
     */
    private boolean isErrorResponse(@Nonnull String response) {
        return !(STATUS_NORMAL_STRING.equals(response) ||
            response.startsWith(STATUS_WARNING_A) ||
            response.startsWith(STATUS_WARNING_B) ||
            response.startsWith(STATUS_BYTES_REMAINING));
    }

    private static class IntentReceiver extends BroadcastReceiver {
        private final CountDownLatch mReceiveLatch = new CountDownLatch(1);

        @Override
        public void onReceive(Context context, Intent intent) {
            mReceiveLatch.countDown();
        }

        public boolean waitForReceive() throws InterruptedException {
            return mReceiveLatch.await(30, TimeUnit.SECONDS);
        }
    }
}
