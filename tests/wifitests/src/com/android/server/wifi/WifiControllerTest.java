
package com.android.server.wifi;


import android.content.ContentResolver;
import android.content.Context;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import com.android.internal.util.IState;
import com.android.internal.util.StateMachine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;

import static com.android.server.wifi.WifiController.CMD_EMERGENCY_CALL_STATE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_EMERGENCY_MODE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_WIFI_TOGGLED;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SmallTest
public class WifiControllerTest {

    private static final String TAG = "WifiControllerTest";

    private void dumpState() {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(stream);
        mWifiController.dump(null, writer, null);
        writer.flush();
        Log.d(TAG, "WifiStateMachine state -" + stream.toString());
    }

    private IState getCurrentState() throws Exception {
        Method method = StateMachine.class.getDeclaredMethod("getCurrentState");
        method.setAccessible(true);
        return (IState) method.invoke(mWifiController);
    }

    private void initializeSettingsStore() throws Exception {
        when(mSettingsStore.isAirplaneModeOn()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        when(mSettingsStore.isScanAlwaysAvailable()).thenReturn(true);
    }

    MockLooper mLooper;
    @Mock Context mContext;
    @Mock WifiServiceImpl mService;
    @Mock FrameworkFacade mFacade;
    @Mock WifiSettingsStore mSettingsStore;
    @Mock WifiStateMachine mWifiStateMachine;
    @Mock WifiServiceImpl.LockList mLockList;

    WifiController mWifiController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mLooper = new MockLooper();

        initializeSettingsStore();

        when(mContext.getContentResolver()).thenReturn(mock(ContentResolver.class));

        mWifiController = new WifiController(mContext, mWifiStateMachine,
                mSettingsStore, mLockList, mLooper.getLooper(), mFacade);

        mWifiController.start();
        mLooper.dispatchAll();
    }

    @After
    public void cleanUp() {
        mLooper.dispatchAll();
    }

    @Test
    public void enableWifi() throws Exception {
        assertEquals("StaDisabledWithScanState", getCurrentState().getName());

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        mWifiController.sendMessage(CMD_WIFI_TOGGLED);
        mLooper.dispatchAll();
        assertEquals("DeviceActiveState", getCurrentState().getName());

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiController.sendMessage(CMD_WIFI_TOGGLED);
        mLooper.dispatchAll();
        assertEquals("StaDisabledWithScanState", getCurrentState().getName());

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        mWifiController.sendMessage(CMD_WIFI_TOGGLED);
        mLooper.dispatchAll();
        assertEquals("DeviceActiveState", getCurrentState().getName());
    }

    @Test
    public void testEcmOn() throws Exception {
        enableWifi();

        // Test with WifiDisableInECBM turned on:
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(true);
        doTestEcm(true);
    }

    @Test
    public void testEcmOff() throws Exception {
        enableWifi();

        // Test with WifiDisableInECBM turned off
        when(mFacade.getConfigWiFiDisableInECBM(mContext)).thenReturn(false);
        doTestEcm(false);
    }

    private void assertInEcm(boolean ecmEnabled) throws Exception {
        if (ecmEnabled) {
            assertEquals("EcmState", getCurrentState().getName());
        } else {
            assertEquals("DeviceActiveState", getCurrentState().getName());
        }
    }


    private void doTestEcm(boolean ecmEnabled) throws Exception {

        // test ecm changed
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();
        assertEquals("DeviceActiveState", getCurrentState().getName());

        // test call state changed
        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 1);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 0);
        mLooper.dispatchAll();
        assertEquals("DeviceActiveState", getCurrentState().getName());


        // test both changed (variation 1 - the good case)
        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 1);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 0);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();
        assertEquals("DeviceActiveState", getCurrentState().getName());

        // test both changed (variation 2 - emergency call in ecm)
        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 1);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 0);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();
        assertEquals("DeviceActiveState", getCurrentState().getName());

        // test both changed (variation 3 - not so good order of events)
        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 1);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 1);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, 0);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 0);
        mLooper.dispatchAll();
        assertEquals("DeviceActiveState", getCurrentState().getName());

        // test that Wifi toggle doesn't exit Ecm
        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 1);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        mWifiController.sendMessage(CMD_WIFI_TOGGLED);
        mLooper.dispatchAll();
        assertInEcm(ecmEnabled);

        mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, 0);
        mLooper.dispatchAll();
        assertEquals("DeviceActiveState", getCurrentState().getName());
    }

}























































