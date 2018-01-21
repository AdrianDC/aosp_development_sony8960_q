/*
 * Copyright 2017 The Android Open Source Project
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

import android.util.ArraySet;

import com.android.server.wifi.WifiConfigStore.StoreData;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;

/**
 * Config store data for Wifi Wake.
 */
public class WakeupConfigStoreData implements StoreData {
    private static final String TAG = "WakeupConfigStoreData";

    private static final String XML_TAG_IS_ACTIVE = "IsActive";
    private static final String XML_TAG_NETWORK_SECTION = "Network";
    private static final String XML_TAG_SSID = "SSID";
    private static final String XML_TAG_SECURITY = "Security";

    private final DataSource<Boolean> mIsActiveDataSource;
    private final DataSource<Set<ScanResultMatchInfo>> mNetworkDataSource;

    /**
     * Interface defining a data source for the store data.
     *
     * @param <T> Type of data source
     */
    public interface DataSource<T> {
        /**
         * Returns the data from the data source.
         */
        T getData();

        /**
         * Updates the data in the data source.
         *
         * @param data Data retrieved from the store
         */
        void setData(T data);
    }

    /**
     * Creates the config store data with its data sources.
     *
     * @param isActiveDataSource Data source for isActive
     * @param networkDataSource Data source for the locked network list
     */
    public WakeupConfigStoreData(
            DataSource<Boolean> isActiveDataSource,
            DataSource<Set<ScanResultMatchInfo>> networkDataSource) {
        mIsActiveDataSource = isActiveDataSource;
        mNetworkDataSource = networkDataSource;
    }

    @Override
    public void serializeData(XmlSerializer out, boolean shared)
            throws XmlPullParserException, IOException {
        if (shared) {
            throw new XmlPullParserException("Share data not supported");
        }

        XmlUtil.writeNextValue(out, XML_TAG_IS_ACTIVE, mIsActiveDataSource.getData());

        for (ScanResultMatchInfo scanResultMatchInfo : mNetworkDataSource.getData()) {
            writeNetwork(out, scanResultMatchInfo);
        }
    }

    /**
     * Writes a {@link ScanResultMatchInfo} to an XML output stream.
     *
     * @param out XML output stream
     * @param scanResultMatchInfo The ScanResultMatchInfo to serizialize
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void writeNetwork(XmlSerializer out, ScanResultMatchInfo scanResultMatchInfo)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_NETWORK_SECTION);

        XmlUtil.writeNextValue(out, XML_TAG_SSID, scanResultMatchInfo.networkSsid);
        XmlUtil.writeNextValue(out, XML_TAG_SECURITY, scanResultMatchInfo.networkType);

        XmlUtil.writeNextSectionEnd(out, XML_TAG_NETWORK_SECTION);
    }

    @Override
    public void deserializeData(XmlPullParser in, int outerTagDepth, boolean shared)
            throws XmlPullParserException, IOException {
        // Ignore empty reads.
        if (in == null) {
            return;
        }
        if (shared) {
            throw new XmlPullParserException("Shared data not supported");
        }

        boolean isActive = (Boolean) XmlUtil.readNextValueWithName(in, XML_TAG_IS_ACTIVE);
        mIsActiveDataSource.setData(isActive);

        Set<ScanResultMatchInfo> networks = new ArraySet<>();
        while (XmlUtil.gotoNextSectionWithNameOrEnd(in, XML_TAG_NETWORK_SECTION, outerTagDepth)) {
            networks.add(parseNetwork(in, outerTagDepth + 1));
        }

        mNetworkDataSource.setData(networks);
    }

    /**
     * Parses a {@link ScanResultMatchInfo} from an XML input stream.
     *
     * @param in XML input stream
     * @param outerTagDepth XML tag depth of the containing section
     * @return The {@link ScanResultMatchInfo}
     * @throws IOException
     * @throws XmlPullParserException
     */
    private ScanResultMatchInfo parseNetwork(XmlPullParser in, int outerTagDepth)
            throws IOException, XmlPullParserException {
        ScanResultMatchInfo scanResultMatchInfo = new ScanResultMatchInfo();
        while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
            String[] valueName = new String[1];
            Object value = XmlUtil.readCurrentValue(in, valueName);
            if (valueName[0] == null) {
                throw new XmlPullParserException("Missing value name");
            }
            switch (valueName[0]) {
                case XML_TAG_SSID:
                    scanResultMatchInfo.networkSsid = (String) value;
                    break;
                case XML_TAG_SECURITY:
                    scanResultMatchInfo.networkType = (int) value;
                    break;
                default:
                    throw new XmlPullParserException("Unknown tag under " + TAG + ": "
                            + valueName[0]);
            }
        }

        return scanResultMatchInfo;
    }

    @Override
    public void resetData(boolean shared) {
        if (!shared) {
            mNetworkDataSource.setData(Collections.emptySet());
            mIsActiveDataSource.setData(false);
        }
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public boolean supportShareData() {
        return false;
    }
}
