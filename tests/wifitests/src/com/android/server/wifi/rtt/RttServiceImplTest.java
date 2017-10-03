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


package com.android.server.wifi.rtt;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.rtt.IRttCallback;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.os.IBinder;
import android.os.test.TestLooper;

import com.android.server.wifi.util.WifiPermissionsUtil;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit test harness for the RttServiceImpl class.
 */
public class RttServiceImplTest {
    private RttServiceImplSpy mDut;
    private TestLooper mMockLooper;

    private final String mPackageName = "some.package.name.for.rtt.app";
    private int mDefaultUid = 1500;

    private ArgumentCaptor<Integer> mIntCaptor = ArgumentCaptor.forClass(Integer.class);
    private ArgumentCaptor<IBinder.DeathRecipient> mDeathRecipientCaptor = ArgumentCaptor
            .forClass(IBinder.DeathRecipient.class);

    @Mock
    public Context mockContext;

    @Mock
    public RttNative mockNative;

    @Mock
    public WifiPermissionsUtil mockPermissionUtil;

    @Mock
    public IBinder mockIbinder;

    @Mock
    public IRttCallback mockCallback;

    /**
     * Using instead of spy to avoid native crash failures - possibly due to
     * spy's copying of state.
     */
    private class RttServiceImplSpy extends RttServiceImpl {
        public int fakeUid;

        RttServiceImplSpy(Context context) {
            super(context);
        }

        /**
         * Return the fake UID instead of the real one: pseudo-spy
         * implementation.
         */
        @Override
        public int getMockableCallingUid() {
            return fakeUid;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mDut = new RttServiceImplSpy(mockContext);
        mDut.fakeUid = mDefaultUid;
        mMockLooper = new TestLooper();

        when(mockPermissionUtil.checkCallersLocationPermission(eq(mPackageName),
                anyInt())).thenReturn(true);
        when(mockNative.rangeRequest(anyInt(), any(RangingRequest.class))).thenReturn(true);

        mDut.start(mMockLooper.getLooper(), mockNative, mockPermissionUtil);
    }

    /**
     * Validate successful ranging flow.
     */
    @Test
    public void testRangingFlow() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest();
        List<RangingResult> results = RttTestUtils.getDummyRangingResults(request);

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, request, mockCallback);
        mMockLooper.dispatchAll();

        // (2) verify that request issued to native
        verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(request));

        // (3) native calls back with result
        mDut.onRangingResults(mIntCaptor.getValue(), results);
        mMockLooper.dispatchAll();

        // (4) verify that results dispatched
        verify(mockCallback).onRangingResults(RangingResultCallback.STATUS_SUCCESS, results);

        // (5) replicate results - shouldn't dispatch another callback
        mDut.onRangingResults(mIntCaptor.getValue(), results);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockNative, mockCallback);
    }

    /**
     * Validate failed ranging flow (native failure).
     */
    @Test
    public void testRangingFlowNativeFailure() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest();
        List<RangingResult> results = RttTestUtils.getDummyRangingResults(request);

        when(mockNative.rangeRequest(anyInt(), any(RangingRequest.class))).thenReturn(false);

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, request, mockCallback);
        mMockLooper.dispatchAll();

        // (2) verify that request issued to native
        verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(request));

        // (3) verify that failure callback dispatched
        verify(mockCallback).onRangingResults(RangingResultCallback.STATUS_FAIL, null);

        // (4) even if native calls back with result we shouldn't dispatch callback
        mDut.onRangingResults(mIntCaptor.getValue(), results);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockNative, mockCallback);
    }

    /**
     * Validate a ranging flow for an app whose LOCATION runtime permission is revoked.
     */
    @Test
    public void testRangingRequestWithoutRuntimePermission() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest();
        List<RangingResult> results = RttTestUtils.getDummyRangingResults(request);

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, request, mockCallback);
        mMockLooper.dispatchAll();

        // (2) verify that request issued to native
        verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(request));

        // (3) native calls back with result - should get a FAILED callback
        when(mockPermissionUtil.checkCallersLocationPermission(eq(mPackageName),
                anyInt())).thenReturn(false);

        mDut.onRangingResults(mIntCaptor.getValue(), results);
        mMockLooper.dispatchAll();

        verify(mockCallback).onRangingResults(eq(RangingResultCallback.STATUS_FAIL), any());

        verifyNoMoreInteractions(mockNative, mockCallback);
    }

    /**
     * Validate that the ranging app's binder death clears record of request - no callbacks are
     * attempted.
     */
    @Test
    public void testBinderDeathOfRangingApp() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest();
        List<RangingResult> results = RttTestUtils.getDummyRangingResults(request);

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, request, mockCallback);
        mMockLooper.dispatchAll();

        // (2) verify that request issued to native & capture death listener
        verify(mockIbinder).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());
        verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(request));

        // (3) trigger death recipient
        mDeathRecipientCaptor.getValue().binderDied();
        mMockLooper.dispatchAll();

        // (4) native calls back with result - shouldn't dispatch a callback
        mDut.onRangingResults(mIntCaptor.getValue(), results);
        mMockLooper.dispatchAll();

        verifyNoMoreInteractions(mockNative, mockCallback);
    }

    /**
     * Validate that when an unexpected result is provided by the Native it is not propagated to
     * caller (unexpected = different command ID).
     */
    @Test
    public void testUnexpectedResult() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest();
        List<RangingResult> results = RttTestUtils.getDummyRangingResults(request);

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, request, mockCallback);
        mMockLooper.dispatchAll();

        // (2) verify that request issued to native
        verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(request));

        // (3) native calls back with result - but wrong ID
        mDut.onRangingResults(mIntCaptor.getValue() + 1, RttTestUtils.getDummyRangingResults(null));
        mMockLooper.dispatchAll();

        // (4) now send results with correct ID (different set of results to differentiate)
        mDut.onRangingResults(mIntCaptor.getValue(), results);
        mMockLooper.dispatchAll();

        // (5) verify that results dispatched
        verify(mockCallback).onRangingResults(RangingResultCallback.STATUS_SUCCESS, results);

        verifyNoMoreInteractions(mockNative, mockCallback);
    }

    /**
     * Validate that the HAL returns results with "missing" entries (i.e. some requests don't get
     * results) they are filled-in with FAILED results.
     */
    @Test
    public void testMissingResults() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest();
        List<RangingResult> results = RttTestUtils.getDummyRangingResults(request);
        List<RangingResult> resultsMissing = new ArrayList<>(results);
        resultsMissing.remove(0);
        List<RangingResult> resultsExpected = new ArrayList<>(resultsMissing);
        resultsExpected.add(
                new RangingResult(RangingResultCallback.STATUS_FAIL, results.get(0).getMacAddress(),
                        0, 0, 0, 0));

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, request, mockCallback);
        mMockLooper.dispatchAll();

        // (2) verify that request issued to native
        verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(request));

        // (3) return results with missing entries
        mDut.onRangingResults(mIntCaptor.getValue(), resultsMissing);
        mMockLooper.dispatchAll();

        // (5) verify that (full) results dispatched
        verify(mockCallback).onRangingResults(RangingResultCallback.STATUS_SUCCESS,
                resultsExpected);

        verifyNoMoreInteractions(mockNative, mockCallback);
    }
}
