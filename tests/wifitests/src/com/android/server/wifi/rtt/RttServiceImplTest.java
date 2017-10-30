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

import static com.android.server.wifi.rtt.RttTestUtils.compareListContentsNoOrdering;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.test.MockAnswerUtil;
import android.app.test.TestAlarmManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.wifi.V1_0.RttResult;
import android.net.wifi.aware.IWifiAwareMacAddressProvider;
import android.net.wifi.aware.IWifiAwareManager;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.rtt.IRttCallback;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.util.Pair;

import com.android.server.wifi.util.WifiPermissionsUtil;

import libcore.util.HexEncoding;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit test harness for the RttServiceImpl class.
 */
public class RttServiceImplTest {
    private RttServiceImplSpy mDut;
    private TestLooper mMockLooper;
    private TestAlarmManager mAlarmManager;

    private final String mPackageName = "some.package.name.for.rtt.app";
    private int mDefaultUid = 1500;

    private ArgumentCaptor<Integer> mIntCaptor = ArgumentCaptor.forClass(Integer.class);
    private ArgumentCaptor<IBinder.DeathRecipient> mDeathRecipientCaptor = ArgumentCaptor
            .forClass(IBinder.DeathRecipient.class);
    private ArgumentCaptor<RangingRequest> mRequestCaptor = ArgumentCaptor.forClass(
            RangingRequest.class);
    private ArgumentCaptor<List> mListCaptor = ArgumentCaptor.forClass(List.class);

    private InOrder mInOrder;

    @Mock
    public Context mockContext;

    @Mock
    public RttNative mockNative;

    @Mock
    public IWifiAwareManager mockAwareManagerBinder;

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

        mAlarmManager = new TestAlarmManager();
        when(mockContext.getSystemService(Context.ALARM_SERVICE))
                .thenReturn(mAlarmManager.getAlarmManager());
        mInOrder = inOrder(mAlarmManager.getAlarmManager(), mockContext);

        when(mockPermissionUtil.checkCallersLocationPermission(eq(mPackageName),
                anyInt())).thenReturn(true);
        when(mockNative.isReady()).thenReturn(true);
        when(mockNative.rangeRequest(anyInt(), any(RangingRequest.class))).thenReturn(true);

        mDut.start(mMockLooper.getLooper(), mockAwareManagerBinder, mockNative, mockPermissionUtil);
    }

    /**
     * Validate successful ranging flow.
     */
    @Test
    public void testRangingFlow() throws Exception {
        int numIter = 10;
        RangingRequest[] requests = new RangingRequest[numIter];
        List<Pair<List<RttResult>, List<RangingResult>>> results = new ArrayList<>();

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
            verifyWakeupSet();

            // (3) native calls back with result
            mDut.onRangingResults(mIntCaptor.getValue(), results.get(i).first);
            mMockLooper.dispatchAll();

            // (4) verify that results dispatched
            verify(mockCallback).onRangingResults(results.get(i).second);
            verifyWakeupCancelled();

            // (5) replicate results - shouldn't dispatch another callback
            mDut.onRangingResults(mIntCaptor.getValue(), results.get(i).first);
            mMockLooper.dispatchAll();
        }

        verify(mockNative, atLeastOnce()).isReady();
        verifyNoMoreInteractions(mockNative, mockCallback, mAlarmManager.getAlarmManager());
    }

    /**
     * Validate a successful ranging flow with PeerHandles (i.e. verify translations)
     */
    @Test
    public void testRangingFlowUsingAwarePeerHandles() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0xA);
        PeerHandle peerHandle = new PeerHandle(1022);
        request.mRttPeers.add(new RangingRequest.RttPeerAware(peerHandle));
        Map<Integer, byte[]> peerHandleToMacMap = new HashMap<>();
        byte[] macAwarePeer = HexEncoding.decode("AABBCCDDEEFF".toCharArray(), false);
        peerHandleToMacMap.put(1022, macAwarePeer);

        AwareTranslatePeerHandlesToMac answer = new AwareTranslatePeerHandlesToMac(mDefaultUid,
                peerHandleToMacMap);
        doAnswer(answer).when(mockAwareManagerBinder).requestMacAddresses(anyInt(), any(), any());

        // issue request
        mDut.startRanging(mockIbinder, mPackageName, request, mockCallback);
        mMockLooper.dispatchAll();

        // verify that requested with MAC address translated from the PeerHandle issued to Native
        verify(mockNative).rangeRequest(mIntCaptor.capture(), mRequestCaptor.capture());
        verifyWakeupSet();

        RangingRequest finalRequest = mRequestCaptor.getValue();
        assertNotEquals("Request to native is not null", null, finalRequest);
        assertEquals("Size of request", request.mRttPeers.size(), finalRequest.mRttPeers.size());
        assertEquals("Aware peer MAC", macAwarePeer,
                ((RangingRequest.RttPeerAware) finalRequest.mRttPeers.get(
                        finalRequest.mRttPeers.size() - 1)).peerMacAddress);

        // issue results
        Pair<List<RttResult>, List<RangingResult>> results =
                RttTestUtils.getDummyRangingResults(mRequestCaptor.getValue());
        mDut.onRangingResults(mIntCaptor.getValue(), results.first);
        mMockLooper.dispatchAll();

        // verify that results with MAC addresses filtered out and replaced by PeerHandles issued
        // to callback
        verify(mockCallback).onRangingResults(mListCaptor.capture());
        verifyWakeupCancelled();

        assertTrue(compareListContentsNoOrdering(results.second, mListCaptor.getValue()));

        verify(mockNative, atLeastOnce()).isReady();
        verifyNoMoreInteractions(mockNative, mockCallback, mAlarmManager.getAlarmManager());
    }

    /**
     * Validate failed ranging flow (native failure).
     */
    @Test
    public void testRangingFlowNativeFailure() throws Exception {
        int numIter = 10;
        RangingRequest[] requests = new RangingRequest[numIter];
        List<Pair<List<RttResult>, List<RangingResult>>> results = new ArrayList<>();

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
                verify(mockCallback).onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
            } else {
                verifyWakeupSet();
            }

            // (4) on failed HAL: even if native calls back with result we shouldn't dispatch
            // callback, otherwise expect result
            mDut.onRangingResults(mIntCaptor.getValue(), results.get(i).first);
            mMockLooper.dispatchAll();

            if (i != 0) {
                verify(mockCallback).onRangingResults(results.get(i).second);
                verifyWakeupCancelled();
            }
        }

        verify(mockNative, atLeastOnce()).isReady();
        verifyNoMoreInteractions(mockNative, mockCallback, mAlarmManager.getAlarmManager());
    }

    /**
     * Validate a ranging flow for an app whose LOCATION runtime permission is revoked.
     */
    @Test
    public void testRangingRequestWithoutRuntimePermission() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
        Pair<List<RttResult>, List<RangingResult>> results = RttTestUtils.getDummyRangingResults(
                request);

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, request, mockCallback);
        mMockLooper.dispatchAll();

        // (2) verify that request issued to native
        verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(request));
        verifyWakeupSet();

        // (3) native calls back with result - should get a FAILED callback
        when(mockPermissionUtil.checkCallersLocationPermission(eq(mPackageName),
                anyInt())).thenReturn(false);

        mDut.onRangingResults(mIntCaptor.getValue(), results.first);
        mMockLooper.dispatchAll();

        verify(mockCallback).onRangingFailure(eq(RangingResultCallback.STATUS_CODE_FAIL));
        verifyWakeupCancelled();

        verify(mockNative, atLeastOnce()).isReady();
        verifyNoMoreInteractions(mockNative, mockCallback, mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that the ranging app's binder death clears record of request - no callbacks are
     * attempted.
     */
    @Test
    public void testBinderDeathOfRangingApp() throws Exception {
        int numIter = 10;
        RangingRequest[] requests = new RangingRequest[numIter];
        List<Pair<List<RttResult>, List<RangingResult>>> results = new ArrayList<>();

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
                verifyWakeupSet();
            }

            // (4) trigger first death recipient (which will map to the even UID)
            if (i == 0) {
                mDeathRecipientCaptor.getAllValues().get(0).binderDied();
                mMockLooper.dispatchAll();

                verify(mockNative).rangeCancel(eq(mIntCaptor.getValue()),
                        (ArrayList) mListCaptor.capture());
                RangingRequest request0 = requests[0];
                assertEquals(request0.mRttPeers.size(), mListCaptor.getValue().size());
                assertArrayEquals(HexEncoding.decode("000102030400".toCharArray(), false),
                        (byte[]) mListCaptor.getValue().get(0));
                assertArrayEquals(HexEncoding.decode("0A0B0C0D0E00".toCharArray(), false),
                        (byte[]) mListCaptor.getValue().get(1));
                assertArrayEquals(HexEncoding.decode("080908070605".toCharArray(), false),
                        (byte[]) mListCaptor.getValue().get(2));
            }

            // (5) native calls back with results - should get requests for the odd attempts and
            // should only get callbacks for the odd attempts (the non-dead UID)
            if (i == 0 || i % 2 == 1) {
                mDut.onRangingResults(mIntCaptor.getValue(), results.get(i).first);
                mMockLooper.dispatchAll();

                // note that we are getting a callback for the first operation - it was dispatched
                // before the binder death. The callback is called from the service - the app is
                // dead so in reality this will throw a RemoteException which the service will
                // handle correctly.
                verify(mockCallback).onRangingResults(results.get(i).second);
                verifyWakeupCancelled();
            }
        }

        verify(mockNative, atLeastOnce()).isReady();
        verifyNoMoreInteractions(mockNative, mockCallback, mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that when an unexpected result is provided by the Native it is not propagated to
     * caller (unexpected = different command ID).
     */
    @Test
    public void testUnexpectedResult() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
        Pair<List<RttResult>, List<RangingResult>> results = RttTestUtils.getDummyRangingResults(
                request);

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, request, mockCallback);
        mMockLooper.dispatchAll();

        // (2) verify that request issued to native
        verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(request));
        verifyWakeupSet();

        // (3) native calls back with result - but wrong ID
        mDut.onRangingResults(mIntCaptor.getValue() + 1,
                RttTestUtils.getDummyRangingResults(null).first);
        mMockLooper.dispatchAll();

        // (4) now send results with correct ID (different set of results to differentiate)
        mDut.onRangingResults(mIntCaptor.getValue(), results.first);
        mMockLooper.dispatchAll();

        // (5) verify that results dispatched
        verify(mockCallback).onRangingResults(results.second);
        verifyWakeupCancelled();

        verify(mockNative, atLeastOnce()).isReady();
        verifyNoMoreInteractions(mockNative, mockCallback, mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that the HAL returns results with "missing" entries (i.e. some requests don't get
     * results) they are filled-in with FAILED results.
     */
    @Test
    public void testMissingResults() throws Exception {
        RangingRequest request = RttTestUtils.getDummyRangingRequest((byte) 0);
        Pair<List<RttResult>, List<RangingResult>> results = RttTestUtils.getDummyRangingResults(
                request);
        results.first.remove(0);
        RangingResult removed = results.second.remove(0);
        results.second.add(
                new RangingResult(RangingResult.STATUS_FAIL, removed.getMacAddress(), 0, 0, 0, 0));

        // (1) request ranging operation
        mDut.startRanging(mockIbinder, mPackageName, request, mockCallback);
        mMockLooper.dispatchAll();

        // (2) verify that request issued to native
        verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(request));
        verifyWakeupSet();

        // (3) return results with missing entries
        mDut.onRangingResults(mIntCaptor.getValue(), results.first);
        mMockLooper.dispatchAll();

        // (5) verify that (full) results dispatched
        verify(mockCallback).onRangingResults(mListCaptor.capture());
        assertTrue(compareListContentsNoOrdering(results.second, mListCaptor.getValue()));
        verifyWakeupCancelled();

        verify(mockNative, atLeastOnce()).isReady();
        verifyNoMoreInteractions(mockNative, mockCallback, mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that when the HAL times out we fail, clean-up the queue and move to the next
     * request.
     */
    @Test
    public void testRangingTimeout() throws Exception {
        RangingRequest request1 = RttTestUtils.getDummyRangingRequest((byte) 1);
        RangingRequest request2 = RttTestUtils.getDummyRangingRequest((byte) 2);
        Pair<List<RttResult>, List<RangingResult>> result1 = RttTestUtils.getDummyRangingResults(
                request1);
        Pair<List<RttResult>, List<RangingResult>> result2 = RttTestUtils.getDummyRangingResults(
                request2);

        // (1) request 2 ranging operation
        mDut.startRanging(mockIbinder, mPackageName, request1, mockCallback);
        mDut.startRanging(mockIbinder, mPackageName, request2, mockCallback);
        mMockLooper.dispatchAll();

        // verify that request 1 issued to native
        verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(request1));
        int cmdId1 = mIntCaptor.getValue();
        verifyWakeupSet();

        // (2) time-out
        mAlarmManager.dispatch(RttServiceImpl.HAL_RANGING_TIMEOUT_TAG);
        mMockLooper.dispatchAll();

        // verify that: failure callback + request 2 issued to native
        verify(mockNative).rangeCancel(eq(cmdId1), any());
        verify(mockCallback).onRangingFailure(RangingResultCallback.STATUS_CODE_FAIL);
        verify(mockNative).rangeRequest(mIntCaptor.capture(), eq(request2));
        verifyWakeupSet();

        // (3) send both result 1 and result 2
        mDut.onRangingResults(cmdId1, result1.first);
        mDut.onRangingResults(mIntCaptor.getValue(), result2.first);
        mMockLooper.dispatchAll();

        // verify that only result 2 is forwarded to client
        verify(mockCallback).onRangingResults(result2.second);
        verifyWakeupCancelled();

        verify(mockNative, atLeastOnce()).isReady();
        verifyNoMoreInteractions(mockNative, mockCallback, mAlarmManager.getAlarmManager());
    }

    /**
     * Validate that when Wi-Fi gets disabled (HAL level) the ranging queue gets cleared.
     */
    @Test
    public void testDisableWifiFlow() throws Exception {
        RangingRequest request1 = RttTestUtils.getDummyRangingRequest((byte) 1);
        RangingRequest request2 = RttTestUtils.getDummyRangingRequest((byte) 2);
        RangingRequest request3 = RttTestUtils.getDummyRangingRequest((byte) 3);

        IRttCallback mockCallback2 = mock(IRttCallback.class);
        IRttCallback mockCallback3 = mock(IRttCallback.class);

        // (1) request 2 ranging operations: request 1 should be sent to HAL
        mDut.startRanging(mockIbinder, mPackageName, request1, mockCallback);
        mDut.startRanging(mockIbinder, mPackageName, request2, mockCallback2);
        mMockLooper.dispatchAll();

        verify(mockNative).rangeRequest(anyInt(), eq(request1));
        verifyWakeupSet();

        // (2) disable Wi-Fi RTT: all requests should "fail"
        when(mockNative.isReady()).thenReturn(false);
        mDut.disable();
        mMockLooper.dispatchAll();

        validateCorrectRttStatusChangeBroadcast(false);
        verify(mockCallback).onRangingFailure(
                RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);
        verify(mockCallback2).onRangingFailure(
                RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);
        verifyWakeupCancelled();

        // (3) issue another request: it should fail
        mDut.startRanging(mockIbinder, mPackageName, request3, mockCallback3);
        mMockLooper.dispatchAll();

        verify(mockCallback3).onRangingFailure(
                RangingResultCallback.STATUS_CODE_FAIL_RTT_NOT_AVAILABLE);

        // (4) enable Wi-Fi: nothing should happen (no requests in queue!)
        when(mockNative.isReady()).thenReturn(true);
        mDut.enable();
        mMockLooper.dispatchAll();

        validateCorrectRttStatusChangeBroadcast(true);
        verify(mockNative, atLeastOnce()).isReady();
        verifyNoMoreInteractions(mockNative, mockCallback, mockCallback2, mockCallback3,
                mAlarmManager.getAlarmManager());
    }

    /*
     * Utilities
     */

    private void verifyWakeupSet() {
        mInOrder.verify(mAlarmManager.getAlarmManager()).setExact(anyInt(), anyLong(),
                eq(RttServiceImpl.HAL_RANGING_TIMEOUT_TAG), any(AlarmManager.OnAlarmListener.class),
                any(Handler.class));
    }

    private void verifyWakeupCancelled() {
        mInOrder.verify(mAlarmManager.getAlarmManager()).cancel(
                any(AlarmManager.OnAlarmListener.class));
    }

    /**
     * Validates that the broadcast sent on RTT status change is correct.
     *
     * @param expectedEnabled The expected change status - i.e. are we expected to announce that
     *                        RTT is enabled (true) or disabled (false).
     */
    private void validateCorrectRttStatusChangeBroadcast(boolean expectedEnabled) {
        ArgumentCaptor<Intent> intent = ArgumentCaptor.forClass(Intent.class);

        mInOrder.verify(mockContext).sendBroadcastAsUser(intent.capture(), eq(UserHandle.ALL));
        assertEquals(intent.getValue().getAction(), WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED);
    }

    private class AwareTranslatePeerHandlesToMac extends MockAnswerUtil.AnswerWithArguments {
        private int mExpectedUid;
        private Map<Integer, byte[]> mPeerIdToMacMap;

        AwareTranslatePeerHandlesToMac(int expectedUid, Map<Integer, byte[]> peerIdToMacMap) {
            mExpectedUid = expectedUid;
            mPeerIdToMacMap = peerIdToMacMap;
        }

        public void answer(int uid, List<Integer> peerIds, IWifiAwareMacAddressProvider callback) {
            assertEquals("Invalid UID", mExpectedUid, uid);

            Map<Integer, byte[]> result = new HashMap<>();
            for (Integer peerId: peerIds) {
                byte[] mac = mPeerIdToMacMap.get(peerId);
                if (mac == null) {
                    continue;
                }

                result.put(peerId, mac);
            }

            try {
                callback.macAddress(result);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}
