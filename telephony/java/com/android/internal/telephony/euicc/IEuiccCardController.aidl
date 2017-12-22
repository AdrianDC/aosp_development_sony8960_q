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

package com.android.internal.telephony.euicc;

import com.android.internal.telephony.euicc.IGetAllProfilesCallback;
import com.android.internal.telephony.euicc.IAuthenticateServerCallback;
import com.android.internal.telephony.euicc.ICancelSessionCallback;
import com.android.internal.telephony.euicc.IGetEuiccChallengeCallback;
import com.android.internal.telephony.euicc.IGetEuiccInfo1Callback;
import com.android.internal.telephony.euicc.IGetEuiccInfo2Callback;
import com.android.internal.telephony.euicc.IGetRulesAuthTableCallback;
import com.android.internal.telephony.euicc.IListNotificationsCallback;
import com.android.internal.telephony.euicc.ILoadBoundProfilePackageCallback;
import com.android.internal.telephony.euicc.IPrepareDownloadCallback;
import com.android.internal.telephony.euicc.IRemoveNotificationFromListCallback;
import com.android.internal.telephony.euicc.IRetrieveNotificationCallback;
import com.android.internal.telephony.euicc.IRetrieveNotificationListCallback;

/** @hide */
interface IEuiccCardController {
    oneway void getAllProfiles(String callingPackage, in IGetAllProfilesCallback callback);
    oneway void getRulesAuthTable(String callingPackage, in IGetRulesAuthTableCallback callback);
    oneway void getEuiccChallenge(String callingPackage, in IGetEuiccChallengeCallback callback);
    oneway void getEuiccInfo1(String callingPackage, in IGetEuiccInfo1Callback callback);
    oneway void getEuiccInfo2(String callingPackage, in IGetEuiccInfo2Callback callback);
    oneway void authenticateServer(String callingPackage, String matchingId,
        in byte[] serverSigned1, in byte[] serverSignature1, in byte[] euiccCiPkIdToBeUsed,
        in byte[] serverCertificatein, in IAuthenticateServerCallback callback);
    oneway void prepareDownload(String callingPackage, in byte[] hashCc, in byte[] smdpSigned2,
        in byte[] smdpSignature2, in byte[] smdpCertificate, in IPrepareDownloadCallback callback);
    oneway void loadBoundProfilePackage(String callingPackage, in byte[] boundProfilePackage,
        in ILoadBoundProfilePackageCallback callback);
    oneway void cancelSession(String callingPackage, in byte[] transactionId, int reason,
        in ICancelSessionCallback callback);
    oneway void listNotifications(String callingPackage, int events,
        in IListNotificationsCallback callback);
    oneway void retrieveNotificationList(String callingPackage, int events,
        in IRetrieveNotificationListCallback callback);
    oneway void retrieveNotification(String callingPackage, int seqNumber,
        in IRetrieveNotificationCallback callback);
    oneway void removeNotificationFromList(String callingPackage, int seqNumber,
            in IRemoveNotificationFromListCallback callback);
}
