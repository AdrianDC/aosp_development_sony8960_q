/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.telephony.TelephonyManager.CALL_STATE_IDLE;
import static android.telephony.TelephonyManager.CALL_STATE_OFFHOOK;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SystemSensorManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.test.TestLooper;
import android.support.test.filters.SmallTest;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import com.android.internal.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * unit tests for {@link com.android.server.wifi.SarManager}.
 */
@SmallTest
public class SarManagerTest {
    private static final String TAG = "WifiSarManagerTest";
    private static final String OP_PACKAGE_NAME = "com.xxx";
    private static final String SAR_SENSOR_NAME = "com.google.sensor.sar";

    private static final int SAR_SENSOR_EVENT_FREE_SPACE = 1;
    private static final int SAR_SENSOR_EVENT_HAND       = 2;
    private static final int SAR_SENSOR_EVENT_HEAD       = 3;
    private static final int SAR_SENSOR_EVENT_BODY       = 4;

    private void enableDebugLogs() {
        mSarMgr.enableVerboseLogging(1);
    }

    private MockResources getMockResources() {
        MockResources resources = new MockResources();
        return resources;
    }

    private SarManager mSarMgr;
    private TestLooper mLooper;
    private MockResources mResources;
    private PhoneStateListener mPhoneStateListener;
    private List<Sensor> mSensorList;
    private Sensor mSensor;
    private SarInfo mSarInfo;

    @Mock private Context mContext;
    @Mock SensorEventListener mSensorEventListener;
    @Mock SystemSensorManager mSensorManager;
    @Mock TelephonyManager mTelephonyManager;
    @Mock private ApplicationInfo mMockApplInfo;
    @Mock WifiNative mWifiNative;

    @Before
    public void setUp() throws Exception {
        /* Ensure Looper exists */
        mLooper = new TestLooper();

        MockitoAnnotations.initMocks(this);

        /* Default behavior is to return with success */
        when(mWifiNative.selectTxPowerScenario(any(SarInfo.class))).thenReturn(true);

        mResources = getMockResources();

        when(mContext.getResources()).thenReturn(mResources);
        mMockApplInfo.targetSdkVersion = Build.VERSION_CODES.P;
        when(mContext.getApplicationInfo()).thenReturn(mMockApplInfo);
        when(mContext.getOpPackageName()).thenReturn(OP_PACKAGE_NAME);
    }

    @After
    public void cleanUp() throws Exception {
        mSarMgr = null;
        mLooper = null;
        mContext = null;
        mResources = null;
    }

    /**
     * Helper function to capture SarInfo object
     */
    private void captureSarInfo(WifiNative wifiNative) {
        /* Capture the SensorEventListener */
        ArgumentCaptor<SarInfo> sarInfoCaptor = ArgumentCaptor.forClass(SarInfo.class);
        verify(wifiNative).selectTxPowerScenario(sarInfoCaptor.capture());
        mSarInfo = sarInfoCaptor.getValue();
        assertNotNull(mSarInfo);
    }

    /**
     * Helper function to create and prepare sensor info
     */
    private void prepareSensorInfo(boolean registerReturn) {
        /* Create a sensor object (note, this can not be mocked since it is a final class) */
        Constructor<Sensor> constructor =
                (Constructor<Sensor>) Sensor.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);

        try {
            mSensor = constructor.newInstance();
        } catch (Exception e) {
            fail("Failed to create a sensor object");
        }

        /* Now set the mStringType field with the proper field */
        Field declaredField = null;
        try {
            declaredField = Sensor.class.getDeclaredField("mStringType");
            declaredField.setAccessible(true);
            declaredField.set(mSensor, SAR_SENSOR_NAME);
        } catch (Exception e) {
            fail("Could not set sensor string type");
        }

        /* Prepare the sensor list */
        mSensorList = new ArrayList<Sensor>();
        mSensorList.add(mSensor);
        when(mSensorManager.getSensorList(Sensor.TYPE_ALL)).thenReturn(mSensorList);
        when(mSensorManager.registerListener(any(SensorEventListener.class), any(Sensor.class),
                  anyInt())).thenReturn(registerReturn);
    }

    /**
     * Helper function to set configuration for SAR and create the SAR Manager
     *
     */
    private void createSarManager(boolean isSarEnabled, boolean isSarSensorEnabled) {
        mResources.setBoolean(
                R.bool.config_wifi_framework_enable_sar_tx_power_limit, isSarEnabled);
        mResources.setBoolean(
                R.bool.config_wifi_framework_enable_body_proximity_sar_tx_power_limit,
                isSarSensorEnabled);
        mResources.setString(R.string.config_wifi_sar_sensor_type, SAR_SENSOR_NAME);

        /* Set the event id configs */
        mResources.setInteger(R.integer.config_wifi_framework_sar_free_space_event_id,
                SAR_SENSOR_EVENT_FREE_SPACE);
        mResources.setInteger(R.integer.config_wifi_framework_sar_near_hand_event_id,
                SAR_SENSOR_EVENT_HAND);
        mResources.setInteger(R.integer.config_wifi_framework_sar_near_head_event_id,
                SAR_SENSOR_EVENT_HEAD);
        mResources.setInteger(R.integer.config_wifi_framework_sar_near_body_event_id,
                SAR_SENSOR_EVENT_BODY);

        /* Prepare sensor info only if SarSensorEnabled */
        if (isSarSensorEnabled) {
            prepareSensorInfo(true);
        }

        mSarMgr = new SarManager(mContext, mTelephonyManager, mLooper.getLooper(),
                mWifiNative, mSensorManager);

        if (isSarEnabled) {
            /* Capture the PhoneStateListener */
            ArgumentCaptor<PhoneStateListener> phoneStateListenerCaptor =
                    ArgumentCaptor.forClass(PhoneStateListener.class);
            verify(mTelephonyManager).listen(phoneStateListenerCaptor.capture(),
                    eq(PhoneStateListener.LISTEN_CALL_STATE));
            mPhoneStateListener = phoneStateListenerCaptor.getValue();
            assertNotNull(mPhoneStateListener);
        }

        if (isSarSensorEnabled) {
            /* Capture the SensorEventListener */
            ArgumentCaptor<SensorEventListener> sensorEventListenerCaptor =
                    ArgumentCaptor.forClass(SensorEventListener.class);
            verify(mSensorManager).registerListener(sensorEventListenerCaptor.capture(),
                    any(Sensor.class), anyInt());
            mSensorEventListener = sensorEventListenerCaptor.getValue();
            assertNotNull(mSensorEventListener);
        }

        /* Enable logs from SarManager */
        enableDebugLogs();
    }

    /**
     * Helper function to create SarManager with some error cases for sensor handling
     */
    private void createSarManagerSensorNegTest(String configSensorName, boolean addToConfigs,
            boolean sensorRegisterReturn) {
        mResources.setBoolean(
                R.bool.config_wifi_framework_enable_sar_tx_power_limit, true);
        mResources.setBoolean(
                R.bool.config_wifi_framework_enable_body_proximity_sar_tx_power_limit, true);
        if (addToConfigs) {
            mResources.setString(R.string.config_wifi_sar_sensor_type, configSensorName);
        }

        /* Set the event id configs */
        mResources.setInteger(R.integer.config_wifi_framework_sar_free_space_event_id,
                SAR_SENSOR_EVENT_FREE_SPACE);
        mResources.setInteger(R.integer.config_wifi_framework_sar_near_hand_event_id,
                SAR_SENSOR_EVENT_HAND);
        mResources.setInteger(R.integer.config_wifi_framework_sar_near_head_event_id,
                SAR_SENSOR_EVENT_HEAD);
        mResources.setInteger(R.integer.config_wifi_framework_sar_near_body_event_id,
                SAR_SENSOR_EVENT_BODY);

        prepareSensorInfo(sensorRegisterReturn);

        mSarMgr = new SarManager(mContext, mTelephonyManager, mLooper.getLooper(),
                mWifiNative, mSensorManager);

        /* Capture the PhoneStateListener */
        ArgumentCaptor<PhoneStateListener> phoneStateListenerCaptor =
                ArgumentCaptor.forClass(PhoneStateListener.class);
        verify(mTelephonyManager).listen(phoneStateListenerCaptor.capture(),
                eq(PhoneStateListener.LISTEN_CALL_STATE));
        mPhoneStateListener = phoneStateListenerCaptor.getValue();
        assertNotNull(mPhoneStateListener);

        /* Enable logs from SarManager */
        enableDebugLogs();
    }

    /**
     * Helper function to create and pass a sensor event
     */
    private void sendSensorEvent(int eventId) {
        SensorEvent event;
        Constructor<SensorEvent> constructor =
                (Constructor<SensorEvent>) SensorEvent.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);

        try {
            event = constructor.newInstance(1);
            event.values[0] = (float) eventId;
            mSensorEventListener.onSensorChanged(event);
        } catch (Exception e) {
            fail("Failed to create a Sensor Event");
        }
    }

    /**
     * Test that we do register the telephony call state listener on devices which do support
     * setting/resetting Tx power limit.
     */
    @Test
    public void testSarMgr_enabledTxPowerScenario_registerPhone() throws Exception {
        createSarManager(true, false);
        verify(mTelephonyManager).listen(any(), eq(PhoneStateListener.LISTEN_CALL_STATE));
    }

    /**
     * Test that we do not register the telephony call state listener on devices which
     * do not support setting/resetting Tx power limit.
     */
    @Test
    public void testSarMgr_disabledTxPowerScenario_registerPhone() throws Exception {
        createSarManager(false, false);
        verify(mTelephonyManager, never()).listen(any(), anyInt());
    }

    /**
     * Test that for devices that support setting/resetting Tx Power limits, device sets the proper
     * Tx power scenario upon receiving {@link TelephonyManager#CALL_STATE_OFFHOOK} when WiFi STA
     * is enabled
     * In this case Wifi is enabled first, then off-hook is detected
     */
    @Test
    public void testSarMgr_enabledTxPowerScenario_wifiOn_offHook() throws Exception {
        createSarManager(true, false);

        InOrder inOrder = inOrder(mWifiNative);

        /* Enable WiFi State */
        mSarMgr.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
        captureSarInfo(mWifiNative);

        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertFalse(mSarInfo.mIsVoiceCall);

        /* Set phone state to OFFHOOK */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_OFFHOOK, "");
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertTrue(mSarInfo.mIsVoiceCall);
    }

    /**
     * Test that for devices that support setting/resetting Tx Power limits, device sets the proper
     * Tx power scenario upon receiving {@link TelephonyManager#CALL_STATE_OFFHOOK} when WiFi STA
     * is enabled
     * In this case off-hook event is detected first, then wifi is turned on
     */
    @Test
    public void testSarMgr_enabledTxPowerScenario_offHook_wifiOn() throws Exception {
        createSarManager(true, false);

        InOrder inOrder = inOrder(mWifiNative);

        /* Set phone state to OFFHOOK */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_OFFHOOK, "");

        /* Enable WiFi State */
        mSarMgr.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
        captureSarInfo(mWifiNative);

        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertTrue(mSarInfo.mIsVoiceCall);
    }

    /**
     * Test that for devices that support setting/resetting Tx Power limits, device sets the proper
     * Tx power scenarios upon receiving {@link TelephonyManager#CALL_STATE_OFFHOOK} and
     * {@link TelephonyManager#CALL_STATE_IDLE} when WiFi STA is enabled
     */
    @Test
    public void testSarMgr_enabledTxPowerScenario_wifiOn_offHook_onHook() throws Exception {
        createSarManager(true, false);

        InOrder inOrder = inOrder(mWifiNative);

        /* Enable WiFi State */
        mSarMgr.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
        captureSarInfo(mWifiNative);

        /* Now device should set tx power scenario to NORMAL */
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertFalse(mSarInfo.mIsVoiceCall);

        /* Set phone state to OFFHOOK */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_OFFHOOK, "");

        /* Device should set tx power scenario to Voice call */
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertTrue(mSarInfo.mIsVoiceCall);

        /* Set state back to ONHOOK */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_IDLE, "");

        /* Device should set tx power scenario to NORMAL again */
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertFalse(mSarInfo.mIsVoiceCall);
    }

    /**
     * Test that for devices that support setting/resetting Tx Power limits, device does not
     * sets the Tx power scenarios upon receiving {@link TelephonyManager#CALL_STATE_OFFHOOK} and
     * {@link TelephonyManager#CALL_STATE_IDLE} when WiFi STA is disabled
     */
    @Test
    public void testSarMgr_enabledTxPowerScenario_wifiOff_offHook_onHook() throws Exception {
        createSarManager(true, false);

        InOrder inOrder = inOrder(mWifiNative);

        /* Set phone state to OFFHOOK */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_OFFHOOK, "");

        /* Set state back to ONHOOK */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_IDLE, "");

        /* Device should not set tx power scenario at all */
        inOrder.verify(mWifiNative, never()).selectTxPowerScenario(any(SarInfo.class));
    }

    /**
     * Test that for a device that has SAR enabled, with sar sensor enabled,
     * wifi enabled, Then Tx power scenarios follow events from sensor for body/hand/head/none
     */
    @Test
    public void testSarMgr_sarSensorOn_WifiOn_sensorEventsTriggered() throws Exception {
        createSarManager(true, true);

        InOrder inOrder = inOrder(mWifiNative);

        /* Enable Wifi Client */
        mSarMgr.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
        captureSarInfo(mWifiNative);

        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_FREE_SPACE, mSarInfo.mSensorState);

        /* Sensor event */
        sendSensorEvent(SAR_SENSOR_EVENT_BODY);
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_BODY, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);

        /* Sensor event */
        sendSensorEvent(SAR_SENSOR_EVENT_HEAD);
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);

        /* Sensor event */
        sendSensorEvent(SAR_SENSOR_EVENT_HAND);
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HAND, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);

        /* Sensor event */
        sendSensorEvent(SAR_SENSOR_EVENT_FREE_SPACE);
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_FREE_SPACE, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);
    }

    /**
     * Test that for a device that has SAR enabled, with sar sensor enabled,
     * wifi enabled, cellOn,
     * then Tx power scenarios follow events from sensor for body/hand/head/none
     */
    @Test
    public void testSarMgr_sarSensorOn_wifiOn_cellOn_sensorEventsTriggered() throws Exception {
        createSarManager(true, true);

        InOrder inOrder = inOrder(mWifiNative);

        /* Enable Wifi Client */
        mSarMgr.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
        captureSarInfo(mWifiNative);

        /* Should get the an event with no calls */
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_FREE_SPACE, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);

        /* Start a Cell call */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_OFFHOOK, "");
        inOrder.verify(mWifiNative).selectTxPowerScenario(any(SarInfo.class));
        assertEquals(SarInfo.SAR_SENSOR_FREE_SPACE, mSarInfo.mSensorState);
        assertTrue(mSarInfo.mIsVoiceCall);

        /* Sensor event */
        sendSensorEvent(SAR_SENSOR_EVENT_BODY);
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_BODY, mSarInfo.mSensorState);
        assertTrue(mSarInfo.mIsVoiceCall);

        /* Sensor event */
        sendSensorEvent(SAR_SENSOR_EVENT_HEAD);
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertTrue(mSarInfo.mIsVoiceCall);

        /* Sensor event */
        sendSensorEvent(SAR_SENSOR_EVENT_HAND);
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HAND, mSarInfo.mSensorState);
        assertTrue(mSarInfo.mIsVoiceCall);

        /* Sensor event */
        sendSensorEvent(SAR_SENSOR_EVENT_FREE_SPACE);
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_FREE_SPACE, mSarInfo.mSensorState);
        assertTrue(mSarInfo.mIsVoiceCall);
    }

    /**
     * Test that for a device that has SAR enabled, with sar sensor enabled,
     * wifi enabled, device next to user head, a call has started and stopped,
     * then Tx power scenarios should adjust properly
     */
    @Test
    public void testSarMgr_sarSensorOn_wifiOn_onHead_cellOnOff() throws Exception {
        createSarManager(true, true);

        InOrder inOrder = inOrder(mWifiNative);

        /* Enable Wifi Client */
        mSarMgr.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
        captureSarInfo(mWifiNative);

        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_FREE_SPACE, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);

        /* Sensor event */
        sendSensorEvent(SAR_SENSOR_EVENT_HEAD);
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);

        /* Start a Cell call */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_OFFHOOK, "");
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertTrue(mSarInfo.mIsVoiceCall);

        /* End a Cell call */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_IDLE, "");
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);
    }

    /**
     * Test that for a device that has SAR enabled, with sar sensor enabled,
     * all wifi states disabled, when a sensor event is triggered no setting of Tx power scenario
     * is initiated.
     * Then when Wifi is enabled, Tx power setting will be initiated to reflect the sensor event.
     */
    @Test
    public void testSarMgr_sarSensorOn_WifiOffOn_sensorEventTriggered() throws Exception {
        createSarManager(true, true);

        InOrder inOrder = inOrder(mWifiNative);

        /* Sensor event */
        sendSensorEvent(SAR_SENSOR_EVENT_BODY);
        inOrder.verify(mWifiNative, never()).selectTxPowerScenario(any(SarInfo.class));

        /* Enable Wifi Client */
        mSarMgr.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
        captureSarInfo(mWifiNative);

        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_BODY, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);
    }

    /**
     * Test the error case when SAR sensor name does not exist in configuration.
     * In this case, SarManager should assume operation near head all the time.
     */
    @Test
    public void testSarMgr_error_sar_name_does_not_exist() throws Exception {
        createSarManagerSensorNegTest(SAR_SENSOR_NAME, false, true);

        InOrder inOrder = inOrder(mWifiNative);

        verify(mSensorManager, never()).registerListener(any(SensorEventListener.class),
                any(Sensor.class), anyInt());

        /* Enable WiFi Client */
        mSarMgr.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
        captureSarInfo(mWifiNative);

        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);

        /* Start a Cell Call */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_OFFHOOK, "");
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertTrue(mSarInfo.mIsVoiceCall);

        /* End the call */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_IDLE, "");
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);
    }

    /**
     * Test the error case when SarManager uses the wrong sensor name in configuration.
     * In this case, SarManager should assume operation near head all the time.
     */
    @Test
    public void testSarMgr_error_sar_name_mismatch() throws Exception {
        createSarManagerSensorNegTest("wrong.sensor.name", true, true);

        InOrder inOrder = inOrder(mWifiNative);

        verify(mSensorManager, never()).registerListener(any(SensorEventListener.class),
                any(Sensor.class), anyInt());

        /* Enable WiFi Client */
        mSarMgr.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
        captureSarInfo(mWifiNative);

        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);

        /* Start a Cell Call */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_OFFHOOK, "");
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertTrue(mSarInfo.mIsVoiceCall);

        /* End the call */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_IDLE, "");
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);
    }

    /**
     * Test the error case when SarManager fails to register as a SensorEventListener.
     * In this case, SarManager should assume operation near head all the time.
     */
    @Test
    public void testSarMgr_error_sar_register_failure() throws Exception {
        createSarManagerSensorNegTest(SAR_SENSOR_NAME, true, false);

        verify(mSensorManager).registerListener(any(SensorEventListener.class),
                any(Sensor.class), anyInt());

        InOrder inOrder = inOrder(mWifiNative);

        /* Enable WiFi Client */
        mSarMgr.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
        captureSarInfo(mWifiNative);

        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);

        /* Start a Cell Call */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_OFFHOOK, "");
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertTrue(mSarInfo.mIsVoiceCall);

        /* End the call */
        mPhoneStateListener.onCallStateChanged(CALL_STATE_IDLE, "");
        inOrder.verify(mWifiNative).selectTxPowerScenario(eq(mSarInfo));
        assertEquals(SarInfo.SAR_SENSOR_NEAR_HEAD, mSarInfo.mSensorState);
        assertFalse(mSarInfo.mIsVoiceCall);
    }
}
