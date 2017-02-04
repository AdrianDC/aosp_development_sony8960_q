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
package com.android.server.wifi;

import android.hardware.wifi.supplicant.V1_0.ISupplicantStaNetwork;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaNetworkCallback;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatus;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatusCode;
import android.os.RemoteException;
import android.util.Log;
import android.util.MutableBoolean;

import java.util.ArrayList;
/**
 * Wrapper class for ISupplicantStaNetwork HAL calls. Gets and sets supplicant sta network variables
 * and interacts with networks.
 * Public fields should be treated as invalid until their 'get' method is called, which will set the
 * value if it returns true
 */
public class SupplicantStaNetworkHal {
    private static final String TAG = "SupplicantStaNetworkHal";
    private static final boolean DBG = false;

    private final Object mLock = new Object();
    private ISupplicantStaNetwork mISupplicantStaNetwork = null;
    private int mId;
    private String mName;
    private int mType;
    private ArrayList<Byte> mApSsid;
    private byte[/* 6 */] mApBssid;
    private boolean mScanSsid;
    private int mKeyMgmtMask;
    private int mProtoMask;
    private int mAuthAlgMask;
    private int mGroupCipherMask;
    private int mPairwiseCipherMask;
    private String mPskPassphrase;
    private ArrayList<Byte> mWepKey;
    private int mWepTxKeyIdx;
    private boolean mRequirePmf;
    private int mEapMethod;
    private int mEapPhase2Method;
    private ArrayList<Byte> mEapIdentity;
    private ArrayList<Byte> mEapAnonymousIdentity;
    private ArrayList<Byte> mEapPassword;
    private String mEapCACert;
    private String mEapCAPath;
    private String mEapClientCert;
    private String mEapPrivateKey;
    private String mEapSubjectMatch;
    private String mEapAltSubjectMatch;
    private boolean mEapEngine;
    private String mEapEngineID;
    private String mEapDomainSuffixMatch;
    private String mIdStr;

    SupplicantStaNetworkHal(ISupplicantStaNetwork iSupplicantStaNetwork) {
        mISupplicantStaNetwork = iSupplicantStaNetwork;
    }

    /** See ISupplicantNetwork.hal for documentation */
    private boolean getId() {
        synchronized (mLock) {
            final String methodStr = "getId";
            if (DBG) Log.i(TAG, methodStr);
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getId((SupplicantStatus status, int idValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mId = idValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /** See ISupplicantNetwork.hal for documentation */
    private boolean getInterfaceName() {
        synchronized (mLock) {
            final String methodStr = "getInterfaceName";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getInterfaceName((SupplicantStatus status,
                        String nameValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mName = nameValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /** See ISupplicantNetwork.hal for documentation */
    private boolean getType() {
        synchronized (mLock) {
            final String methodStr = "getType";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getType((SupplicantStatus status, int typeValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mType = typeValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean registerCallback(ISupplicantStaNetworkCallback callback) {
        synchronized (mLock) {
            final String methodStr = "registerCallback";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.registerCallback(callback);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setSsid(java.util.ArrayList<Byte> ssid) {
        synchronized (mLock) {
            final String methodStr = "setSsid";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setSsid(ssid);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setBssid(byte[/* 6 */] bssid) {
        synchronized (mLock) {
            final String methodStr = "setBssid";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setBssid(bssid);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setScanSsid(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setScanSsid";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setScanSsid(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setKeyMgmt(int keyMgmtMask) {
        synchronized (mLock) {
            final String methodStr = "setKeyMgmt";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setKeyMgmt(keyMgmtMask);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setProto(int protoMask) {
        synchronized (mLock) {
            final String methodStr = "setProto";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setProto(protoMask);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setAuthAlg(int authAlgMask) {
        synchronized (mLock) {
            final String methodStr = "setAuthAlg";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setAuthAlg(authAlgMask);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setGroupCipher(int groupCipherMask) {
        synchronized (mLock) {
            final String methodStr = "setGroupCipher";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setGroupCipher(groupCipherMask);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setPairwiseCipher(int pairwiseCipherMask) {
        synchronized (mLock) {
            final String methodStr = "setPairwiseCipher";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaNetwork.setPairwiseCipher(pairwiseCipherMask);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setPskPassphrase(String psk) {
        synchronized (mLock) {
            final String methodStr = "setPskPassphrase";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setPskPassphrase(psk);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setWepKey(int keyIdx, java.util.ArrayList<Byte> wepKey) {
        synchronized (mLock) {
            final String methodStr = "setWepKey";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setWepKey(keyIdx, wepKey);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setWepTxKeyIdx(int keyIdx) {
        synchronized (mLock) {
            final String methodStr = "setWepTxKeyIdx";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setWepTxKeyIdx(keyIdx);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setRequirePmf(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setRequirePmf";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setRequirePmf(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapMethod(int method) {
        synchronized (mLock) {
            final String methodStr = "setEapMethod";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapMethod(method);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapPhase2Method(int method) {
        synchronized (mLock) {
            final String methodStr = "setEapPhase2Method";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapPhase2Method(method);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapIdentity(java.util.ArrayList<Byte> identity) {
        synchronized (mLock) {
            final String methodStr = "setEapIdentity";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapIdentity(identity);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapAnonymousIdentity(java.util.ArrayList<Byte> identity) {
        synchronized (mLock) {
            final String methodStr = "setEapAnonymousIdentity";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapAnonymousIdentity(identity);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapPassword(java.util.ArrayList<Byte> password) {
        synchronized (mLock) {
            final String methodStr = "setEapPassword";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapPassword(password);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapCACert(String path) {
        synchronized (mLock) {
            final String methodStr = "setEapCACert";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapCACert(path);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapCAPath(String path) {
        synchronized (mLock) {
            final String methodStr = "setEapCAPath";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapCAPath(path);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapClientCert(String path) {
        synchronized (mLock) {
            final String methodStr = "setEapClientCert";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapClientCert(path);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapPrivateKey(String path) {
        synchronized (mLock) {
            final String methodStr = "setEapPrivateKey";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapPrivateKey(path);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapSubjectMatch(String match) {
        synchronized (mLock) {
            final String methodStr = "setEapSubjectMatch";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapSubjectMatch(match);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapAltSubjectMatch(String match) {
        synchronized (mLock) {
            final String methodStr = "setEapAltSubjectMatch";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapAltSubjectMatch(match);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapEngine(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setEapEngine";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapEngine(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapEngineID(String id) {
        synchronized (mLock) {
            final String methodStr = "setEapEngineID";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapEngineID(id);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setEapDomainSuffixMatch(String match) {
        synchronized (mLock) {
            final String methodStr = "setEapDomainSuffixMatch";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setEapDomainSuffixMatch(match);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean setIdStr(String idString) {
        synchronized (mLock) {
            final String methodStr = "setIdStr";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.setIdStr(idString);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getSsid() {
        synchronized (mLock) {
            final String methodStr = "getSsid";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getSsid((SupplicantStatus status,
                        java.util.ArrayList<Byte> ssidValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mApSsid = ssidValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getBssid() {
        synchronized (mLock) {
            final String methodStr = "getBssid";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getBssid((SupplicantStatus status,
                        byte[/* 6 */] bssidValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mApBssid = bssidValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getScanSsid() {
        synchronized (mLock) {
            final String methodStr = "getScanSsid";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getScanSsid((SupplicantStatus status,
                        boolean enabledValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mScanSsid = enabledValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getKeyMgmt() {
        synchronized (mLock) {
            final String methodStr = "getKeyMgmt";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getKeyMgmt((SupplicantStatus status,
                        int keyMgmtMaskValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mKeyMgmtMask = keyMgmtMaskValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getProto() {
        synchronized (mLock) {
            final String methodStr = "getProto";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getProto((SupplicantStatus status, int protoMaskValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mProtoMask = protoMaskValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getAuthAlg() {
        synchronized (mLock) {
            final String methodStr = "getAuthAlg";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getAuthAlg((SupplicantStatus status,
                        int authAlgMaskValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mAuthAlgMask = authAlgMaskValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getGroupCipher() {
        synchronized (mLock) {
            final String methodStr = "getGroupCipher";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getGroupCipher((SupplicantStatus status,
                        int groupCipherMaskValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mGroupCipherMask = groupCipherMaskValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getPairwiseCipher() {
        synchronized (mLock) {
            final String methodStr = "getPairwiseCipher";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getPairwiseCipher((SupplicantStatus status,
                        int pairwiseCipherMaskValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mPairwiseCipherMask = pairwiseCipherMaskValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getPskPassphrase() {
        synchronized (mLock) {
            final String methodStr = "getPskPassphrase";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getPskPassphrase((SupplicantStatus status,
                        String pskValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mPskPassphrase = pskValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getWepKeyCallback(int keyIdx) {
        synchronized (mLock) {
            final String methodStr = "keyIdx";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getWepKey(keyIdx, (SupplicantStatus status,
                        java.util.ArrayList<Byte> wepKeyValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mWepKey = wepKeyValue;
                    } else {
                        Log.e(TAG, methodStr + ",  failed: " + status.debugMessage);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getWepTxKeyIdx() {
        synchronized (mLock) {
            final String methodStr = "getWepTxKeyIdx";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getWepTxKeyIdx((SupplicantStatus status,
                        int keyIdxValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mWepTxKeyIdx = keyIdxValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getRequirePmf() {
        synchronized (mLock) {
            final String methodStr = "getRequirePmf";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getRequirePmf((SupplicantStatus status,
                        boolean enabledValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mRequirePmf = enabledValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapMethod() {
        synchronized (mLock) {
            final String methodStr = "getEapMethod";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapMethod((SupplicantStatus status,
                        int methodValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapMethod = methodValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapPhase2Method() {
        synchronized (mLock) {
            final String methodStr = "getEapPhase2Method";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapPhase2Method((SupplicantStatus status,
                        int methodValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapPhase2Method = methodValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapIdentity() {
        synchronized (mLock) {
            final String methodStr = "getEapIdentity";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapIdentity((SupplicantStatus status,
                        ArrayList<Byte> identityValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapIdentity = identityValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapAnonymousIdentity() {
        synchronized (mLock) {
            final String methodStr = "getEapAnonymousIdentity";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapAnonymousIdentity((SupplicantStatus status,
                        ArrayList<Byte> identityValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapAnonymousIdentity = identityValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapPassword() {
        synchronized (mLock) {
            final String methodStr = "getEapPassword";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapPassword((SupplicantStatus status,
                        ArrayList<Byte> passwordValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapPassword = passwordValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapCACert() {
        synchronized (mLock) {
            final String methodStr = "getEapCACert";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapCACert((SupplicantStatus status, String pathValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapCACert = pathValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapCAPath() {
        synchronized (mLock) {
            final String methodStr = "getEapCAPath";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapCAPath((SupplicantStatus status, String pathValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapCAPath = pathValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapClientCert() {
        synchronized (mLock) {
            final String methodStr = "getEapClientCert";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapClientCert((SupplicantStatus status,
                        String pathValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapClientCert = pathValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapPrivateKey() {
        synchronized (mLock) {
            final String methodStr = "getEapPrivateKey";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapPrivateKey((SupplicantStatus status,
                        String pathValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapPrivateKey = pathValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapSubjectMatch() {
        synchronized (mLock) {
            final String methodStr = "getEapSubjectMatch";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapSubjectMatch((SupplicantStatus status,
                        String matchValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapSubjectMatch = matchValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapAltSubjectMatch() {
        synchronized (mLock) {
            final String methodStr = "getEapAltSubjectMatch";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapAltSubjectMatch((SupplicantStatus status,
                        String matchValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapAltSubjectMatch = matchValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapEngine() {
        synchronized (mLock) {
            final String methodStr = "getEapEngine";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapEngine((SupplicantStatus status,
                        boolean enabledValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapEngine = enabledValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapEngineID() {
        synchronized (mLock) {
            final String methodStr = "getEapEngineID";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapEngineID((SupplicantStatus status, String idValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapEngineID = idValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getEapDomainSuffixMatch() {
        synchronized (mLock) {
            final String methodStr = "getEapDomainSuffixMatch";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getEapDomainSuffixMatch((SupplicantStatus status,
                        String matchValue) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mEapDomainSuffixMatch = matchValue;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean getIdStr() {
        synchronized (mLock) {
            final String methodStr = "getIdStr";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                MutableBoolean statusOk = new MutableBoolean(false);
                mISupplicantStaNetwork.getIdStr((SupplicantStatus status, String idString) -> {
                    statusOk.value = status.code == SupplicantStatusCode.SUCCESS;
                    if (statusOk.value) {
                        this.mIdStr = idString;
                    } else {
                        logFailureStatus(status, methodStr);
                    }
                });
                return statusOk.value;
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean enable(boolean noConnect) {
        synchronized (mLock) {
            final String methodStr = "enable";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.enable(noConnect);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean disable() {
        synchronized (mLock) {
            final String methodStr = "disable";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.disable();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean select() {
        synchronized (mLock) {
            final String methodStr = "select";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaNetwork.select();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean sendNetworkEapSimGsmAuthResponse(
            ArrayList<ISupplicantStaNetwork.NetworkResponseEapSimGsmAuthParams> params) {
        synchronized (mLock) {
            final String methodStr = "sendNetworkEapSimGsmAuthResponse";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaNetwork.sendNetworkEapSimGsmAuthResponse(params);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean sendNetworkEapSimGsmAuthFailure() {
        synchronized (mLock) {
            final String methodStr = "sendNetworkEapSimGsmAuthFailure";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaNetwork.sendNetworkEapSimGsmAuthFailure();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean sendNetworkEapSimUmtsAuthResponse(
            ISupplicantStaNetwork.NetworkResponseEapSimUmtsAuthParams params) {
        synchronized (mLock) {
            final String methodStr = "sendNetworkEapSimUmtsAuthResponse";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaNetwork.sendNetworkEapSimUmtsAuthResponse(params);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean sendNetworkEapSimUmtsAutsResponse(byte[/* 14 */] auts) {
        synchronized (mLock) {
            final String methodStr = "sendNetworkEapSimUmtsAutsResponse";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaNetwork.sendNetworkEapSimUmtsAutsResponse(auts);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean sendNetworkEapSimUmtsAuthFailure() {
        synchronized (mLock) {
            final String methodStr = "sendNetworkEapSimUmtsAuthFailure";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaNetwork.sendNetworkEapSimUmtsAuthFailure();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }
    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean sendNetworkEapIdentityResponse(ArrayList<Byte> identity) {
        synchronized (mLock) {
            final String methodStr = "sendNetworkEapIdentityResponse";
            if (!checkISupplicantStaNetworkAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaNetwork.sendNetworkEapIdentityResponse(identity);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkStatusAndLogFailure(SupplicantStatus status, final String methodStr) {
        if (DBG) Log.i(TAG, methodStr);
        if (status.code != SupplicantStatusCode.SUCCESS) {
            Log.e(TAG, methodStr + " failed: "
                    + SupplicantStaIfaceHal.supplicantStatusCodeToString(status.code) + ", "
                    + status.debugMessage);
            return false;
        }
        return true;
    }

    /**
     * Returns false if ISupplicantStaNetwork is null, and logs failure of methodStr
     */
    private boolean checkISupplicantStaNetworkAndLogFailure(final String methodStr) {
        if (mISupplicantStaNetwork == null) {
            Log.e(TAG, "Can't call " + methodStr + ", ISupplicantStaNetwork is null");
            return false;
        }
        return true;
    }

    private void handleRemoteException(RemoteException e, String methodStr) {
        mISupplicantStaNetwork = null;
        Log.e(TAG, "ISupplicantStaNetwork." + methodStr + ":exception: " + e);
    }

    private void logFailureStatus(SupplicantStatus status, String methodStr) {
        Log.e(TAG, methodStr + " failed: " + status.debugMessage);
    }
}
