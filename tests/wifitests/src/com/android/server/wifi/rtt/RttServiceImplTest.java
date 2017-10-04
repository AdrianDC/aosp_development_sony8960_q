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
import static org.mockito.Mockito.times;
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
        int numIter = 10;
        RangingRequest[] requests = new RangingRequest[numIter];
        List<List<RangingResult>> results = new ArrayList<>();

        for (int i = 0; i < numIter; ++i) {
            requests[i] = RttTestUtils.getDummyRangingRequest((byte) i);
            results.add(RttTestUtils.getDummyRangingResults(requests[i]));
        }

        // (1) request 10 ranging operations
        for (int i = 0; i < numIter; ++i) {
            mDut.startRanging(mockIbinder, mPackageName, requests[i], mockCallback);
        }
        mMockLooper.dispatchAll();

        for (int i = 0; i < numIter; ++i) {
            // (2) verify that request issued to native
            verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(requests[i]));

            // (3) native calls back with result
            mDut.onRangingResults(mIntCaptor.getValue(), results.get(i));
            mMockLooper.dispatchAll();

            // (4) verify that results dispatched
            verify(mockCallback).onRangingResults(RangingResultCallback.STATUS_SUCCESS,
                    results.get(i));

            // (5) replicate results - shouldn't dispatch another callback
            mDut.onRangingResults(mIntCaptor.getValue(), results.get(i));
            mMockLooper.dispatchAll();
        }

        verifyNoMoreInteractions(mockNative, mockCallback);
    }

    /**
     * Validate failed ranging flow (native failure).
     */
    @Test
    public void testRangingFlowNativeFailure() throws Exception {
        int numIter = 10;
        RangingRequest[] requests = new RangingRequest[numIter];
        List<List<RangingResult>> results = new ArrayList<>();

        for (int i = 0; i < numIter; ++i) {
            requests[i] = RttTestUtils.getDummyRangingRequest((byte) i);
            results.add(RttTestUtils.getDummyRangingResults(requests[i]));
        }


        // (1) request 10 ranging operations: fail the first one
        when(mockNative.rangeRequest(anyInt(), any(RangingRequest.class))).thenReturn(false);
        mDut.startRanging(mockIbinder, mPackageName, requests[0], mockCallback);
        mMockLooper.dispatchAll();

        when(mockNative.rangeRequest(anyInt(), any(RangingRequest.class))).thenReturn(true);
        for (int i = 1; i < numIter; ++i) {
            mDut.startRanging(mockIbinder, mPackageName, requests[i], mockCallback);
        }
        mMockLooper.dispatchAll();

        for (int i = 0; i < numIter; ++i) {
            // (2) verify that request issued to native
            verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(requests[i]));

            // (3) verify that failure callback dispatched (for the HAL failure)
            if (i == 0) {
                verify(mockCallback).onRangingResults(RangingResultCallback.STATUS_FAIL, null);
            }

            // (4) on failed HAL: even if native calls back with result we shouldn't dispatch
            // callback, otherwise expect result
            mDut.onRangingResults(mIntCaptor.getValue(), results.get(i));
            mMockLooper.dispatchAll();

            if (i != 0) {
                verify(mockCallback).onRangingResults(RangingResultCallback.STATUS_SUCCESS,
                        results.get(i));
            }
        }

        verifyNoMoreInteractions(mockNative, mockCallback);
    }

    /**
     * Validate a ranging flow for an app whose LOCATION runtime permission is revoked.
     */
    @Test
    public void testRangingRequestWithoutRuntimePermission() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
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
        int numIter = 10;
        RangingRequest[] requests = new RangingRequest[numIter];
        List<List<RangingResult>> results = new ArrayList<>();

        for (int i = 0; i < numIter; ++i) {
            requests[i] = RttTestUtils.getDummyRangingRequest((byte) i);
            results.add(RttTestUtils.getDummyRangingResults(requests[i]));
        }

        // (1) request 10 ranging operations: even/odd with different UIDs
        for (int i = 0; i < numIter; ++i) {
            mDut.fakeUid = mDefaultUid + i % 2;
            mDut.startRanging(mockIbinder, mPackageName, requests[i], mockCallback);
        }
        mMockLooper.dispatchAll();

        // (2) capture death listeners
        verify(mockIbinder, times(numIter)).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());

        for (int i = 0; i < numIter; ++i) {
            // (3) verify first request and all odd requests issued to HAL
            if (i == 0 || i % 2 == 1) {
                verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(requests[i]));
            }

            // (4) trigger first death recipient (which will map to the even UID)
            if (i == 0) {
                mDeathRecipientCaptor.getAllValues().get(0).binderDied();
                mMockLooper.dispatchAll();
            }

            // (5) native calls back with results - should get requests for the odd attempts and
            // should only get callbacks for the odd attempts (the non-dead UID)
            if (i == 0 || i % 2 == 1) {
                mDut.onRangingResults(mIntCaptor.getValue(), results.get(i));
                mMockLooper.dispatchAll();

                // note that we are getting a callback for the first operation - it was dispatched
                // before the binder death. The callback is called from the service - the app is
                // dead so in reality this will throw a RemoteException which the service will
                // handle correctly.
                verify(mockCallback).onRangingResults(RangingResultCallback.STATUS_SUCCESS,
                        results.get(i));
            }
        }

        verifyNoMoreInteractions(mockNative, mockCallback);
    }

    /**
     * Validate that when an unexpected result is provided by the Native it is not propagated to
     * caller (unexpected = different command ID).
     */
    @Test
    public void testUnexpectedResult() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
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
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
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
