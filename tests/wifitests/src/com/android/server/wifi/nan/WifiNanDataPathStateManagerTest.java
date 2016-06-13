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

package com.android.server.wifi.nan;

import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Matchers.anyShort;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.test.TestAlarmManager;
import android.content.Context;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * Unit test harness for WifiNanDataPathStateManager class.
 */
@SmallTest
public class WifiNanDataPathStateManagerTest {
    private static final String sNanInterfacePrefix = "nan";

    private TestLooper mMockLooper;
    private WifiNanStateManager mDut;
    @Mock private WifiNanNative mMockNative;
    @Mock private Context mMockContext;
    TestAlarmManager mAlarmManager;

    @Rule
    public ErrorCollector collector = new ErrorCollector();

    /**
     * Initialize mocks.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mAlarmManager = new TestAlarmManager();
        when(mMockContext.getSystemService(Context.ALARM_SERVICE))
                .thenReturn(mAlarmManager.getAlarmManager());

        mMockLooper = new TestLooper();

        mDut = installNewNanStateManagerAndResetState();
        mDut.start(mMockContext, mMockLooper.getLooper());
        mDut.startLate();

        when(mMockNative.getCapabilities(anyShort())).thenReturn(true);
        when(mMockNative.createNanNetworkInterface(anyShort(), anyString())).thenReturn(true);
        when(mMockNative.deleteNanNetworkInterface(anyShort(), anyString())).thenReturn(true);

        installMockWifiNanNative(mMockNative);
    }

    /**
     * Validates that creating and deleting all interfaces works based on capabilities.
     */
    @Test
    public void testCreateDeleteAllInterfaces() throws Exception {
        final int numNdis = 3;
        final int failCreateInterfaceIndex = 1;

        WifiNanNative.Capabilities capabilities = new WifiNanNative.Capabilities();
        capabilities.maxNdiInterfaces = numNdis;

        ArgumentCaptor<Short> transactionId = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<String> interfaceName = ArgumentCaptor.forClass(String.class);
        InOrder inOrder = inOrder(mMockNative);

        // (1) get capabilities
        mDut.getCapabilities();
        mMockLooper.dispatchAll();
        inOrder.verify(mMockNative).getCapabilities(transactionId.capture());
        mDut.onCapabilitiesUpdateResponse(transactionId.getValue(), capabilities);
        mMockLooper.dispatchAll();

        // (2) create all interfaces
        mDut.createAllDataPathInterfaces();
        mMockLooper.dispatchAll();
        for (int i = 0; i < numNdis; ++i) {
            inOrder.verify(mMockNative).createNanNetworkInterface(transactionId.capture(),
                    interfaceName.capture());
            collector.checkThat("interface created -- " + i, sNanInterfacePrefix + i,
                    equalTo(interfaceName.getValue()));
            mDut.onCreateDataPathInterfaceResponse(transactionId.getValue(), true, 0);
            mMockLooper.dispatchAll();
        }

        // (3) delete all interfaces [one unsuccessfully] - note that will not necessarily be
        // done sequentially
        boolean[] done = new boolean[numNdis];
        Arrays.fill(done, false);
        mDut.deleteAllDataPathInterfaces();
        mMockLooper.dispatchAll();
        for (int i = 0; i < numNdis; ++i) {
            inOrder.verify(mMockNative).deleteNanNetworkInterface(transactionId.capture(),
                    interfaceName.capture());
            int interfaceIndex = Integer.valueOf(
                    interfaceName.getValue().substring(sNanInterfacePrefix.length()));
            done[interfaceIndex] = true;
            if (interfaceIndex == failCreateInterfaceIndex) {
                mDut.onDeleteDataPathInterfaceResponse(transactionId.getValue(), false, 0);
            } else {
                mDut.onDeleteDataPathInterfaceResponse(transactionId.getValue(), true, 0);
            }
            mMockLooper.dispatchAll();
        }
        for (int i = 0; i < numNdis; ++i) {
            collector.checkThat("interface deleted -- " + i, done[i], equalTo(true));
        }

        // (4) create all interfaces (should get a delete for the one which couldn't delete earlier)
        mDut.createAllDataPathInterfaces();
        mMockLooper.dispatchAll();
        for (int i = 0; i < numNdis; ++i) {
            if (i == failCreateInterfaceIndex) {
                inOrder.verify(mMockNative).deleteNanNetworkInterface(transactionId.capture(),
                        interfaceName.capture());
                collector.checkThat("interface delete pre-create -- " + i, sNanInterfacePrefix + i,
                        equalTo(interfaceName.getValue()));
                mDut.onDeleteDataPathInterfaceResponse(transactionId.getValue(), true, 0);
                mMockLooper.dispatchAll();
            }
            inOrder.verify(mMockNative).createNanNetworkInterface(transactionId.capture(),
                    interfaceName.capture());
            collector.checkThat("interface created -- " + i, sNanInterfacePrefix + i,
                    equalTo(interfaceName.getValue()));
            mDut.onCreateDataPathInterfaceResponse(transactionId.getValue(), true, 0);
            mMockLooper.dispatchAll();
        }

        verifyNoMoreInteractions(mMockNative);
    }

        /*
     * Utilities
     */

    private static WifiNanStateManager installNewNanStateManagerAndResetState()
            throws Exception {
        Constructor<WifiNanStateManager> ctr = WifiNanStateManager.class.getDeclaredConstructor();
        ctr.setAccessible(true);
        WifiNanStateManager nanStateManager = ctr.newInstance();

        Field field = WifiNanStateManager.class.getDeclaredField("sNanStateManagerSingleton");
        field.setAccessible(true);
        field.set(null, nanStateManager);

        return WifiNanStateManager.getInstance();
    }

    private static void installMockWifiNanNative(WifiNanNative obj) throws Exception {
        Field field = WifiNanNative.class.getDeclaredField("sWifiNanNativeSingleton");
        field.setAccessible(true);
        field.set(null, obj);
    }
}
