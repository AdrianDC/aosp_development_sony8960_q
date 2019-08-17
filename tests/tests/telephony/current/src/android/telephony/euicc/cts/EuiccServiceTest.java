/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telephony.euicc.cts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.service.euicc.DownloadSubscriptionResult;
import android.service.euicc.EuiccService;
import android.service.euicc.GetDefaultDownloadableSubscriptionListResult;
import android.service.euicc.IDownloadSubscriptionCallback;
import android.service.euicc.IEuiccService;
import android.service.euicc.IGetDefaultDownloadableSubscriptionListCallback;
import android.service.euicc.IGetEidCallback;
import android.service.euicc.IGetOtaStatusCallback;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.telephony.euicc.cts.MockEuiccService.IMockEuiccServiceCallback;

import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
@RunWith(AndroidJUnit4.class)
public class EuiccServiceTest {
    private static final int CALLBACK_TIMEOUT_MILLIS = 2000 /* 2 sec */;

    private static final int MOCK_SLOT_ID = 1;

    private IEuiccService mEuiccServiceBinder;
    private IMockEuiccServiceCallback mCallback;

    private CountDownLatch mCountDownLatch;

    @Rule public ServiceTestRule mServiceTestRule = new ServiceTestRule();

    @Before
    public void setUp() throws Exception {
        mCallback = new MockEuiccServiceCallback();
        MockEuiccService.setCallback(mCallback);

        Intent mockServiceIntent = new Intent(getContext(), MockEuiccService.class);
        IBinder binder = mServiceTestRule.bindService(mockServiceIntent);
        mEuiccServiceBinder = IEuiccService.Stub.asInterface(binder);
    }

    @After
    public void tearDown() throws Exception {
        mServiceTestRule.unbindService();
        mCallback.reset();
    }

    static class MockEuiccServiceCallback implements IMockEuiccServiceCallback {
        private boolean mMethodCalled;

        @Override
        public void setMethodCalled() {
            mMethodCalled = true;
        }

        @Override
        public boolean isMethodCalled() {
            return mMethodCalled;
        }

        @Override
        public void reset() {
            mMethodCalled = false;
        }
    }

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Test
    public void testOnGetEid() throws Exception {
        mCountDownLatch = new CountDownLatch(1);

        mEuiccServiceBinder.getEid(
                MOCK_SLOT_ID,
                new IGetEidCallback.Stub() {
                    @Override
                    public void onSuccess(String eid) {
                        assertEquals(MockEuiccService.MOCK_EID, eid);
                        mCountDownLatch.countDown();
                    }
                });

        try {
            mCountDownLatch.await(CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail(e.toString());
        }

        assertTrue(mCallback.isMethodCalled());
    }

    @Test
    public void testOnGetOtaStatus() throws Exception {
        mCountDownLatch = new CountDownLatch(1);

        mEuiccServiceBinder.getOtaStatus(
                MOCK_SLOT_ID,
                new IGetOtaStatusCallback.Stub() {
                    @Override
                    public void onSuccess(int status) {
                        assertEquals(EuiccManager.EUICC_OTA_SUCCEEDED, status);
                        mCountDownLatch.countDown();
                    }
                });

        try {
            mCountDownLatch.await(CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail(e.toString());
        }

        assertTrue(mCallback.isMethodCalled());
    }

    @Test
    public void testOnGetDefaultDownloadableSubscriptionList() throws Exception {
        mCountDownLatch = new CountDownLatch(1);

        mEuiccServiceBinder.getDefaultDownloadableSubscriptionList(
                MOCK_SLOT_ID,
                true /*forceDeactivateSim*/,
                new IGetDefaultDownloadableSubscriptionListCallback.Stub() {
                    @Override
                    public void onComplete(GetDefaultDownloadableSubscriptionListResult result) {
                        assertNotNull(result);
                        assertEquals(EuiccService.RESULT_RESOLVABLE_ERRORS, result.getResult());
                        mCountDownLatch.countDown();
                    }
                });
    }

    @Test
    public void testOnDownloadSubscription() throws Exception {
        String activationCode = "1$SMDP.GSMA.COM$04386-A1B2C-A74Y8-3F815";
        DownloadableSubscription subscription =
                DownloadableSubscription.forActivationCode(activationCode);

        mCountDownLatch = new CountDownLatch(1);

        mEuiccServiceBinder.downloadSubscription(
                MOCK_SLOT_ID,
                subscription,
                true /*switchAfterDownload*/,
                true /*forceDeactivateSim*/,
                null /*resolvedBundle*/,
                new IDownloadSubscriptionCallback.Stub() {
                    @Override
                    public void onComplete(DownloadSubscriptionResult result) {
                        assertNotNull(result);
                        assertEquals(EuiccService.RESULT_OK, result.getResult());
                        mCountDownLatch.countDown();
                    }
                });

        try {
            mCountDownLatch.await(CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            fail(e.toString());
        }

        assertTrue(mCallback.isMethodCalled());
    }
}
