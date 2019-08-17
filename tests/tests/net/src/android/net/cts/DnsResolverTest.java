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

package android.net.cts;

import static android.net.DnsResolver.CLASS_IN;
import static android.net.DnsResolver.FLAG_EMPTY;
import static android.net.DnsResolver.FLAG_NO_CACHE_LOOKUP;
import static android.net.DnsResolver.TYPE_A;
import static android.net.DnsResolver.TYPE_AAAA;
import static android.system.OsConstants.EBADF;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DnsPacket;
import android.net.DnsResolver;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.ParseException;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.test.AndroidTestCase;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class DnsResolverTest extends AndroidTestCase {
    private static final String TAG = "DnsResolverTest";
    private static final char[] HEX_CHARS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };
    static final int TIMEOUT_MS = 12_000;
    static final int CANCEL_RETRY_TIMES = 5;

    private ConnectivityManager mCM;
    private Executor mExecutor;
    private DnsResolver mDns;

    protected void setUp() throws Exception {
        super.setUp();
        mCM = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        mDns = DnsResolver.getInstance();
        mExecutor = new Handler(Looper.getMainLooper())::post;
    }

    private static String byteArrayToHexString(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; ++i) {
            int b = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_CHARS[b >>> 4];
            hexChars[i * 2 + 1] = HEX_CHARS[b & 0x0F];
        }
        return new String(hexChars);
    }

    private Network[] getTestableNetworks() {
        final ArrayList<Network> testableNetworks = new ArrayList<Network>();
        for (Network network : mCM.getAllNetworks()) {
            final NetworkCapabilities nc = mCM.getNetworkCapabilities(network);
            if (nc != null
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                testableNetworks.add(network);
            }
        }

        assertTrue(
                "This test requires that at least one network be connected. " +
                        "Please ensure that the device is connected to a network.",
                testableNetworks.size() >= 1);
        return testableNetworks.toArray(new Network[0]);
    }

    public void testQueryWithInetAddressCallback() {
        final String dname = "www.google.com";
        final String msg = "Query with InetAddressAnswerCallback " + dname;
        for (Network network : getTestableNetworks()) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<List<InetAddress>> answers = new AtomicReference<>();
            final DnsResolver.InetAddressAnswerCallback callback =
                    new DnsResolver.InetAddressAnswerCallback() {
                @Override
                public void onAnswer(@NonNull List<InetAddress> answerList) {
                    answers.set(answerList);
                    for (InetAddress addr : answerList) {
                        Log.d(TAG, "Reported addr: " + addr.toString());
                    }
                    latch.countDown();
                }

                @Override
                public void onParseException(@NonNull ParseException e) {
                    fail(msg + e.getMessage());
                }

                @Override
                public void onQueryException(@NonNull ErrnoException e) {
                    fail(msg + e.getMessage());
                }
            };
            mDns.query(network, dname, CLASS_IN, TYPE_A, FLAG_NO_CACHE_LOOKUP,
                    mExecutor, null, callback);
            try {
                assertTrue(msg + " but no valid answer after " + TIMEOUT_MS + "ms.",
                        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
                assertGreaterThan(msg + " returned 0 result", answers.get().size(), 0);
            } catch (InterruptedException e) {
                fail(msg + " Waiting for DNS lookup was interrupted");
            }
        }
    }

    static private void assertGreaterThan(String msg, int first, int second) {
        assertTrue(msg + " Excepted " + first + " to be greater than " + second, first > second);
    }

    static private void assertValidAnswer(String msg, @NonNull DnsAnswer ans) {
        // Check rcode field.(0, No error condition).
        assertTrue(msg + " Response error, rcode: " + ans.getRcode(), ans.getRcode() == 0);
        // Check answer counts.
        assertGreaterThan(msg + " No answer found", ans.getANCount(), 0);
        // Check question counts.
        assertGreaterThan(msg + " No question found", ans.getQDCount(), 0);
    }

    static private void assertValidEmptyAnswer(String msg, @NonNull DnsAnswer ans) {
        // Check rcode field.(0, No error condition).
        assertTrue(msg + " Response error, rcode: " + ans.getRcode(), ans.getRcode() == 0);
        // Check answer counts. Expect 0 answer.
        assertTrue(msg + " Not an empty answer", ans.getANCount() == 0);
        // Check question counts.
        assertGreaterThan(msg + " No question found", ans.getQDCount(), 0);
    }

    private static class DnsAnswer extends DnsPacket {
        DnsAnswer(@NonNull byte[] data) throws ParseException {
            super(data);
            // Check QR field.(query (0), or a response (1)).
            if ((mHeader.flags & (1 << 15)) == 0) {
                throw new ParseException("Not an answer packet");
            }
        }

        int getRcode() {
            return mHeader.rcode;
        }
        int getANCount(){
            return mHeader.getRecordCount(ANSECTION);
        }
        int getQDCount(){
            return mHeader.getRecordCount(QDSECTION);
        }
    }

    class RawAnswerCallbackImpl extends DnsResolver.RawAnswerCallback {
        private final CountDownLatch mLatch = new CountDownLatch(1);
        private final String mMsg;
        private final int mTimeout;

        RawAnswerCallbackImpl(@NonNull String msg, int timeout) {
            this.mMsg = msg;
            this.mTimeout = timeout;
        }

        RawAnswerCallbackImpl(@NonNull String msg) {
            this(msg, TIMEOUT_MS);
        }

        public boolean waitForAnswer() throws InterruptedException {
            return mLatch.await(mTimeout, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onAnswer(@NonNull byte[] answer) {
            try {
                assertValidAnswer(mMsg, new DnsAnswer(answer));
                Log.d(TAG, "Reported blob: " + byteArrayToHexString(answer));
                mLatch.countDown();
            } catch (ParseException e) {
                fail(mMsg + e.getMessage());
            }
        }

        @Override
        public void onParseException(@NonNull ParseException e) {
            fail(mMsg + e.getMessage());
        }

        @Override
        public void onQueryException(@NonNull ErrnoException e) {
            fail(mMsg + e.getMessage());
        }
    }

    public void testQueryWithRawAnswerCallback() {
        final String dname = "www.google.com";
        final String msg = "Query with RawAnswerCallback " + dname;
        for (Network network : getTestableNetworks()) {
            final RawAnswerCallbackImpl callback = new RawAnswerCallbackImpl(msg);
            mDns.query(network, dname, CLASS_IN, TYPE_AAAA, FLAG_NO_CACHE_LOOKUP,
                    mExecutor, null, callback);
            try {
                assertTrue(msg + " but no answer after " + TIMEOUT_MS + "ms.",
                        callback.waitForAnswer());
            } catch (InterruptedException e) {
                fail(msg + " Waiting for DNS lookup was interrupted");
            }
        }
    }

    public void testQueryBlobWithRawAnswerCallback() {
        final byte[] blob = new byte[]{
            /* Header */
            0x55, 0x66, /* Transaction ID */
            0x01, 0x00, /* Flags */
            0x00, 0x01, /* Questions */
            0x00, 0x00, /* Answer RRs */
            0x00, 0x00, /* Authority RRs */
            0x00, 0x00, /* Additional RRs */
            /* Queries */
            0x03, 0x77, 0x77, 0x77, 0x06, 0x67, 0x6F, 0x6F, 0x67, 0x6c, 0x65,
            0x03, 0x63, 0x6f, 0x6d, 0x00, /* Name */
            0x00, 0x01, /* Type */
            0x00, 0x01  /* Class */
        };
        final String msg = "Query with RawAnswerCallback " + byteArrayToHexString(blob);
        for (Network network : getTestableNetworks()) {
            final RawAnswerCallbackImpl callback = new RawAnswerCallbackImpl(msg);
            mDns.query(network, blob, FLAG_NO_CACHE_LOOKUP, mExecutor, null, callback);
            try {
                assertTrue(msg + " but no answer after " + TIMEOUT_MS + "ms.",
                        callback.waitForAnswer());
            } catch (InterruptedException e) {
                fail(msg + " Waiting for DNS lookup was interrupted");
            }
        }
    }

    public void testQueryRoot() {
        final String dname = "";
        final String msg = "Query with RawAnswerCallback empty dname(ROOT) ";
        for (Network network : getTestableNetworks()) {
            final CountDownLatch latch = new CountDownLatch(1);
            final DnsResolver.RawAnswerCallback callback = new DnsResolver.RawAnswerCallback() {
                @Override
                public void onAnswer(@NonNull byte[] answer) {
                    try {
                        // Except no answer record because of querying with empty dname(ROOT)
                        assertValidEmptyAnswer(msg, new DnsAnswer(answer));
                        latch.countDown();
                    } catch (ParseException e) {
                        fail(msg + e.getMessage());
                    }
                }

                @Override
                public void onParseException(@NonNull ParseException e) {
                    fail(msg + e.getMessage());
                }

                @Override
                public void onQueryException(@NonNull ErrnoException e) {
                    fail(msg + e.getMessage());
                }
            };
            mDns.query(network, dname, CLASS_IN, TYPE_AAAA, FLAG_NO_CACHE_LOOKUP,
                    mExecutor, null, callback);
            try {
                assertTrue(msg + "but no answer after " + TIMEOUT_MS + "ms.",
                        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                fail(msg + "Waiting for DNS lookup was interrupted");
            }
        }
    }

    public void testQueryNXDomain() {
        final String dname = "test1-nx.metric.gstatic.com";
        final String msg = "Query with InetAddressAnswerCallback " + dname;
        for (Network network : getTestableNetworks()) {
            final CountDownLatch latch = new CountDownLatch(1);
            final DnsResolver.InetAddressAnswerCallback callback =
                    new DnsResolver.InetAddressAnswerCallback() {
                @Override
                public void onAnswer(@NonNull List<InetAddress> answerList) {
                    if (answerList.size() == 0) {
                        latch.countDown();
                        return;
                    }
                    fail(msg + " but get valid answers");
                }

                @Override
                public void onParseException(@NonNull ParseException e) {
                    fail(msg + e.getMessage());
                }

                @Override
                public void onQueryException(@NonNull ErrnoException e) {
                    fail(msg + e.getMessage());
                }
            };
            mDns.query(network, dname, CLASS_IN, TYPE_AAAA, FLAG_NO_CACHE_LOOKUP,
                    mExecutor, null, callback);
            try {
                assertTrue(msg + " but no answer after " + TIMEOUT_MS + "ms.",
                        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                fail(msg + " Waiting for DNS lookup was interrupted");
            }
        }
    }

    /**
     * A query callback that ensures that the query is cancelled and that onAnswer is never
     * called. If the query succeeds before it is cancelled, needRetry will return true so the
     * test can retry.
     */
    class VerifyCancelCallback extends DnsResolver.RawAnswerCallback {
        private static final int CANCEL_TIMEOUT = 3_000;

        private final CountDownLatch mLatch = new CountDownLatch(1);
        private final String mMsg;
        private final CancellationSignal mCancelSignal;

        VerifyCancelCallback(@NonNull String msg, @NonNull CancellationSignal cancelSignal) {
            this.mMsg = msg;
            this.mCancelSignal = cancelSignal;
        }

        public boolean needRetry() throws InterruptedException {
            return mLatch.await(CANCEL_TIMEOUT, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onAnswer(@NonNull byte[] answer) {
            if (mCancelSignal.isCanceled()) {
                fail(mMsg + " should not have returned any answers");
            }
            mLatch.countDown();
        }

        @Override
        public void onParseException(@NonNull ParseException e) {
            fail(mMsg + e.getMessage());
        }

        @Override
        public void onQueryException(@NonNull ErrnoException e) {
            fail(mMsg + e.getMessage());
        }
    }

    public void testQueryCancel() throws ErrnoException {
        final String dname = "www.google.com";
        final String msg = "Test cancel query " + dname;
        // Start a DNS query and the cancel it immediately. Use VerifyCancelCallback to expect
        // that the query is cancelled before it succeeds. If it is not cancelled before it
        // succeeds, retry the test until it is.
        for (Network network : getTestableNetworks()) {
            boolean retry = false;
            int round = 0;
            do {
                if (++round > CANCEL_RETRY_TIMES) {
                    fail(msg + " cancel failed " + CANCEL_RETRY_TIMES + " times");
                }
                final CountDownLatch latch = new CountDownLatch(1);
                final CancellationSignal cancelSignal = new CancellationSignal();
                final VerifyCancelCallback callback = new VerifyCancelCallback(msg, cancelSignal);
                mDns.query(network, dname, CLASS_IN, TYPE_AAAA, FLAG_EMPTY,
                        mExecutor, cancelSignal, callback);
                mExecutor.execute(() -> {
                    cancelSignal.cancel();
                    latch.countDown();
                });
                try {
                    retry = callback.needRetry();
                    assertTrue(msg + " query was not cancelled",
                        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    fail(msg + "Waiting for DNS lookup was interrupted");
                }
            } while (retry);
        }
    }

    public void testQueryBlobCancel() throws ErrnoException {
        final byte[] blob = new byte[]{
            /* Header */
            0x55, 0x66, /* Transaction ID */
            0x01, 0x00, /* Flags */
            0x00, 0x01, /* Questions */
            0x00, 0x00, /* Answer RRs */
            0x00, 0x00, /* Authority RRs */
            0x00, 0x00, /* Additional RRs */
            /* Queries */
            0x03, 0x77, 0x77, 0x77, 0x06, 0x67, 0x6F, 0x6F, 0x67, 0x6c, 0x65,
            0x03, 0x63, 0x6f, 0x6d, 0x00, /* Name */
            0x00, 0x01, /* Type */
            0x00, 0x01  /* Class */
        };
        final String msg = "Test cancel raw Query " + byteArrayToHexString(blob);
        // Start a DNS query and the cancel it immediately. Use VerifyCancelCallback to expect
        // that the query is cancelled before it succeeds. If it is not cancelled before it
        // succeeds, retry the test until it is.
        for (Network network : getTestableNetworks()) {
            boolean retry = false;
            int round = 0;
            do {
                if (++round > CANCEL_RETRY_TIMES) {
                    fail(msg + " cancel failed " + CANCEL_RETRY_TIMES + " times");
                }
                final CountDownLatch latch = new CountDownLatch(1);
                final CancellationSignal cancelSignal = new CancellationSignal();
                final VerifyCancelCallback callback = new VerifyCancelCallback(msg, cancelSignal);
                mDns.query(network, blob, FLAG_EMPTY, mExecutor, cancelSignal, callback);
                mExecutor.execute(() -> {
                    cancelSignal.cancel();
                    latch.countDown();
                });
                try {
                    retry = callback.needRetry();
                    assertTrue(msg + " cancel is not done",
                        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    fail(msg + " Waiting for DNS lookup was interrupted");
                }
            } while (retry);
        }
    }

    public void testCancelBeforeQuery() throws ErrnoException {
        final String dname = "www.google.com";
        final String msg = "Test cancelled query " + dname;
        for (Network network : getTestableNetworks()) {
            final RawAnswerCallbackImpl callback = new RawAnswerCallbackImpl(msg, 3_000);
            final CancellationSignal cancelSignal = new CancellationSignal();
            cancelSignal.cancel();
            mDns.query(network, dname, CLASS_IN, TYPE_AAAA, FLAG_EMPTY,
                    mExecutor, cancelSignal, callback);
            try {
                assertTrue(msg + " should not return any answers",
                        !callback.waitForAnswer());
            } catch (InterruptedException e) {
                fail(msg + " Waiting for DNS lookup was interrupted");
            }
        }
    }

    /**
     * A query callback for InetAddress that ensures that the query is
     * cancelled and that onAnswer is never called. If the query succeeds
     * before it is cancelled, needRetry will return true so the
     * test can retry.
     */
    class VerifyCancelInetAddressCallback extends DnsResolver.InetAddressAnswerCallback {
        private static final int CANCEL_TIMEOUT = 3_000;

        private final CountDownLatch mLatch = new CountDownLatch(1);
        private final String mMsg;
        private final List<InetAddress> mAnswers;
        private final CancellationSignal mCancelSignal;

        VerifyCancelInetAddressCallback(@NonNull String msg, @Nullable CancellationSignal cancel) {
            this.mMsg = msg;
            this.mCancelSignal = cancel;
            mAnswers = new ArrayList<>();
        }

        public boolean waitForAnswer() throws InterruptedException {
            return mLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        public boolean needRetry() throws InterruptedException {
            return mLatch.await(CANCEL_TIMEOUT, TimeUnit.MILLISECONDS);
        }

        public boolean isAnswerEmpty() {
            return mAnswers.isEmpty();
        }

        @Override
        public void onAnswer(@NonNull List<InetAddress> answerList) {
            if (mCancelSignal != null && mCancelSignal.isCanceled()) {
                fail(mMsg + " should not have returned any answers");
            }
            mAnswers.clear();
            mAnswers.addAll(answerList);
            mLatch.countDown();
        }

        @Override
        public void onParseException(@NonNull ParseException e) {
            fail(mMsg + e.getMessage());
        }

        @Override
        public void onQueryException(@NonNull ErrnoException e) {
            fail(mMsg + e.getMessage());
        }
    }

    public void testQueryForInetAddress() {
        final String dname = "www.google.com";
        final String msg = "Test query for InetAddress " + dname;
        for (Network network : getTestableNetworks()) {
            final VerifyCancelInetAddressCallback callback =
                    new VerifyCancelInetAddressCallback(msg, null);
            mDns.query(network, dname, FLAG_NO_CACHE_LOOKUP,
                    mExecutor, null, callback);
            try {
                assertTrue(msg + " but no answer after " + TIMEOUT_MS + "ms.",
                        callback.waitForAnswer());
                assertTrue(msg + " returned 0 results", !callback.isAnswerEmpty());
            } catch (InterruptedException e) {
                fail(msg + " Waiting for DNS lookup was interrupted");
            }
        }
    }

    public void testQueryCancelForInetAddress() throws ErrnoException {
        final String dname = "www.google.com";
        final String msg = "Test cancel query for InetAddress " + dname;
        // Start a DNS query and the cancel it immediately. Use VerifyCancelCallback to expect
        // that the query is cancelled before it succeeds. If it is not cancelled before it
        // succeeds, retry the test until it is.
        for (Network network : getTestableNetworks()) {
            boolean retry = false;
            int round = 0;
            do {
                if (++round > CANCEL_RETRY_TIMES) {
                    fail(msg + " cancel failed " + CANCEL_RETRY_TIMES + " times");
                }
                final CountDownLatch latch = new CountDownLatch(1);
                final CancellationSignal cancelSignal = new CancellationSignal();
                final VerifyCancelInetAddressCallback callback =
                        new VerifyCancelInetAddressCallback(msg, cancelSignal);
                mDns.query(network, dname, FLAG_EMPTY, mExecutor, cancelSignal, callback);
                mExecutor.execute(() -> {
                    cancelSignal.cancel();
                    latch.countDown();
                });
                try {
                    retry = callback.needRetry();
                    assertTrue(msg + " query was not cancelled",
                        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    fail(msg + "Waiting for DNS lookup was interrupted");
                }
            } while (retry);
        }
    }
}
