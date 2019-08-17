/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * limitations under the License
 */

package android.provider.cts.contacts;

import static android.provider.ContactsContract.DataUsageFeedback;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Data;
import android.provider.cts.contacts.DataUtil;
import android.provider.cts.contacts.DatabaseAsserts;
import android.provider.cts.contacts.RawContactUtil;
import android.test.AndroidTestCase;
import android.text.TextUtils;

public class ContactsContract_DataUsageTest extends AndroidTestCase {

    private ContentResolver mResolver;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mResolver = getContext().getContentResolver();
    }

    public void testSingleDataUsageFeedback_incrementsCorrectDataItems() {
        DatabaseAsserts.ContactIdPair ids = DatabaseAsserts.assertAndCreateContact(mResolver);

        long[] dataIds = setupRawContactDataItems(ids.mRawContactId);

        // Update just 1 data item at a time.
        updateDataUsageAndAssert(dataIds[1], 1);
        updateDataUsageAndAssert(dataIds[1], 2);
        updateDataUsageAndAssert(dataIds[1], 3);
        updateDataUsageAndAssert(dataIds[1], 4);
        updateDataUsageAndAssert(dataIds[1], 5);
        updateDataUsageAndAssert(dataIds[1], 6);
        updateDataUsageAndAssert(dataIds[1], 7);
        updateDataUsageAndAssert(dataIds[1], 8);
        updateDataUsageAndAssert(dataIds[1], 9);
        updateDataUsageAndAssert(dataIds[1], 10);
        updateDataUsageAndAssert(dataIds[1], 10);
        updateDataUsageAndAssert(dataIds[1], 10);
        updateDataUsageAndAssert(dataIds[1], 10);
        updateDataUsageAndAssert(dataIds[1], 10);
        updateDataUsageAndAssert(dataIds[1], 10);
        updateDataUsageAndAssert(dataIds[1], 10);
        updateDataUsageAndAssert(dataIds[1], 10);
        updateDataUsageAndAssert(dataIds[1], 10);

        updateDataUsageAndAssert(dataIds[2], 1);
        updateDataUsageAndAssert(dataIds[2], 2);
        updateDataUsageAndAssert(dataIds[2], 3);

        // Go back and update the previous data item again.
        updateDataUsageAndAssert(dataIds[1], 10);
        updateDataUsageAndAssert(dataIds[1], 20);

        updateDataUsageAndAssert(dataIds[2], 4);
        updateDataUsageAndAssert(dataIds[2], 5);
        updateDataUsageAndAssert(dataIds[2], 6);
        updateDataUsageAndAssert(dataIds[2], 7);
        updateDataUsageAndAssert(dataIds[2], 8);
        updateDataUsageAndAssert(dataIds[2], 9);
        updateDataUsageAndAssert(dataIds[2], 10);

        updateDataUsageAndAssert(dataIds[1], 20);
        updateDataUsageAndAssert(dataIds[1], 20);
        updateDataUsageAndAssert(dataIds[1], 20);

        deleteDataUsage();
        RawContactUtil.delete(mResolver, ids.mRawContactId, true);
    }

    public void testMultiIdDataUsageFeedback_incrementsCorrectDataItems() {
        DatabaseAsserts.ContactIdPair ids = DatabaseAsserts.assertAndCreateContact(mResolver);

        long[] dataIds = setupRawContactDataItems(ids.mRawContactId);

        updateMultipleAndAssertUpdateSuccess(new long[] {dataIds[1], dataIds[2]});

        updateMultipleAndAssertUpdateSuccess(new long[]{dataIds[1], dataIds[2]});

        for (int i = 3; i <= 10; i++) {
            updateMultipleAndAssertUpdateSuccess(new long[]{dataIds[1]});
        }

        updateMultipleAndAssertUpdateSuccess(new long[]{dataIds[0], dataIds[1]});

        for (int i = 12; i <= 19; i++) {
            updateMultipleAndAssertUpdateSuccess(new long[]{dataIds[1]});
        }
        updateMultipleAndAssertUpdateSuccess(new long[]{dataIds[1]});

        deleteDataUsage();
        RawContactUtil.delete(mResolver, ids.mRawContactId, true);
    }

    private long[] setupRawContactDataItems(long rawContactId) {
        // Create 4 data items.
        long[] dataIds = new long[4];
        dataIds[0] = DataUtil.insertPhoneNumber(mResolver, rawContactId, "555-5555");
        dataIds[1] = DataUtil.insertPhoneNumber(mResolver, rawContactId, "555-5554");
        dataIds[2] = DataUtil.insertEmail(mResolver, rawContactId, "test@thisisfake.com");
        dataIds[3] = DataUtil.insertPhoneNumber(mResolver, rawContactId, "555-5556");
        return dataIds;
    }

    /**
     * Updates multiple data ids at once.  And asserts the update returned success.
     */
    private void updateMultipleAndAssertUpdateSuccess(long[] dataIds) {
        String[] ids = new String[dataIds.length];
        for (int i = 0; i < dataIds.length; i++) {
            ids[i] = String.valueOf(dataIds[i]);
        }
        Uri uri = DataUsageFeedback.FEEDBACK_URI.buildUpon().appendPath(TextUtils.join(",", ids))
                .appendQueryParameter(DataUsageFeedback.USAGE_TYPE,
                        DataUsageFeedback.USAGE_TYPE_CALL).build();
        int result = mResolver.update(uri, new ContentValues(), null, null);
    }

    /**
     * Updates a single data item usage.  Asserts the update was successful.  Asserts the usage
     * number is equal to expected value.
     */
    private void updateDataUsageAndAssert(long dataId, int assertValue) {
        Uri uri = DataUsageFeedback.FEEDBACK_URI.buildUpon().appendPath(String.valueOf(dataId))
                .appendQueryParameter(DataUsageFeedback.USAGE_TYPE,
                        DataUsageFeedback.USAGE_TYPE_CALL).build();
        int result = mResolver.update(uri, new ContentValues(), null, null);
    }

    private void deleteDataUsage() {
        mResolver.delete(DataUsageFeedback.DELETE_USAGE_URI, null, null);
    }
}
