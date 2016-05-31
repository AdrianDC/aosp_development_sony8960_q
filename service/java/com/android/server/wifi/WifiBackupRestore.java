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

package com.android.server.wifi;

import android.net.wifi.WifiConfiguration;
import android.util.Log;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Class used to backup/restore data using the SettingsBackupAgent.
 * There are 2 symmetric API's exposed here:
 * 1. retrieveBackupDataFromConfigurations: Retrieve the configuration data to be backed up.
 * 2. retrieveConfigurationsFromBackupData: Restore the configuration using the provided data.
 * The byte stream to be backed up is XML encoded and versioned to migrate the data easily across
 * revisions.
 */
public class WifiBackupRestore {
    private static final String TAG = "WifiBackupRestore";

    /**
     * Current backup data version. This will be incremented for any additions.
     */
    private static final int CURRENT_BACKUP_DATA_VERSION = 1;

    /** This list of older versions will be used to restore data from older backups. */
    /**
     * First version of the backup data format.
     */
    private static final int INITIAL_BACKUP_DATA_VERSION = 1;

    /**
     * List of XML tags in the backed up data
     */
    private static final String XML_TAG_DOCUMENT_HEADER = "WifiBackupData";
    private static final String XML_TAG_SECTION_HEADER_CONFIGURATION_LIST = "ConfigurationList";
    private static final String XML_TAG_SECTION_HEADER_CONFIGURATION = "Configuration";
    private static final String XML_TAG_VERSION = "Version";
    private static final String XML_TAG_CONFIGURATION_SSID = "SSID";
    private static final String XML_TAG_CONFIGURATION_BSSID = "BSSID";
    private static final String XML_TAG_CONFIGURATION_CONFIG_KEY = "ConfigKey";
    private static final String XML_TAG_CONFIGURATION_PRE_SHARED_KEY = "PreSharedKey";
    private static final String XML_TAG_CONFIGURATION_WEP_KEYS = "WEPKeys";
    private static final String XML_TAG_CONFIGURATION_WEP_TX_KEY_INDEX = "WEPTxKeyIndex";
    private static final String XML_TAG_CONFIGURATION_HIDDEN_SSID = "HiddenSSID";
    private static final String XML_TAG_CONFIGURATION_ALLOWED_KEY_MGMT = "AllowedKeyMgmt";
    private static final String XML_TAG_CONFIGURATION_ALLOWED_PROTOCOLS = "AllowedProtocols";
    private static final String XML_TAG_CONFIGURATION_ALLOWED_AUTH_ALGOS = "AllowedAuthAlgos";
    private static final String XML_TAG_CONFIGURATION_SHARED = "Shared";
    private static final String XML_TAG_CONFIGURATION_CREATOR_UID = "CreatorUid";

    private boolean mVerboseLoggingEnabled = true;

    /**
     * Retrieve an XML byte stream representing the data that needs to be backed up from the
     * provided configurations.
     *
     * @param configurations list of currently saved networks that needs to be backed up.
     * @return Raw byte stream of XML that needs to be backed up.
     */
    public byte[] retrieveBackupDataFromConfigurations(List<WifiConfiguration> configurations) {
        try {
            final XmlSerializer out = new FastXmlSerializer();
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            out.setOutput(outputStream, StandardCharsets.UTF_8.name());

            // Start writing the XML stream.
            XmlUtil.writeDocumentStart(out, XML_TAG_DOCUMENT_HEADER);

            XmlUtil.writeNextValue(out, XML_TAG_VERSION, CURRENT_BACKUP_DATA_VERSION);

            writeConfigurationsToXml(out, configurations);

            XmlUtil.writeDocumentEnd(out, XML_TAG_DOCUMENT_HEADER);

            byte[] data = outputStream.toByteArray();
            if (mVerboseLoggingEnabled) logBackupData(data, "retrieveBackupData");

            return data;
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Error retrieving the backup data: " + e);
        } catch (IOException e) {
            Log.e(TAG, "Error retrieving the backup data: " + e);
        }
        return null;
    }

    /**
     * Write the list of configurations to the XML stream.
     */
    private void writeConfigurationsToXml(
            XmlSerializer out, List<WifiConfiguration> configurations)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_CONFIGURATION_LIST);
        for (WifiConfiguration configuration : configurations) {
            // We don't want to backup/restore enterprise/passpoint configurations.
            if (configuration.isEnterprise() || configuration.isPasspoint()) {
                Log.d(TAG, "Skipping enterprise config for backup: " + configuration.configKey());
                continue;
            }
            // Write this configuration data now.
            XmlUtil.writeNextSectionStart(out, XML_TAG_SECTION_HEADER_CONFIGURATION);
            writeConfigurationToXml(out, configuration);
            XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_CONFIGURATION);
        }
        XmlUtil.writeNextSectionEnd(out, XML_TAG_SECTION_HEADER_CONFIGURATION_LIST);
    }

    /**
     * Write WepKeys to the XML stream.
     * WepKeys array is intialized in WifiConfiguration constructor, but all of the elements
     * are null. XmlUtils serialization doesn't handle this array of nulls well .
     * So, write null if the keys are not initialized.
     */
    private void writeWepKeys(XmlSerializer out, String[] wepKeys)
            throws XmlPullParserException, IOException {
        if (wepKeys[0] != null) {
            XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_WEP_KEYS, wepKeys);
        } else {
            XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_WEP_KEYS, null);
        }
    }

    /**
     * Write the configuration data elements from the provided Configuration to the XML stream.
     * Uses XmlUtils to write the values of each element.
     */
    private void writeConfigurationToXml(XmlSerializer out, WifiConfiguration configuration)
            throws XmlPullParserException, IOException {
        XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_CONFIG_KEY, configuration.configKey());
        XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_SSID, configuration.SSID);
        XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_BSSID, configuration.BSSID);
        XmlUtil.writeNextValue(
                out, XML_TAG_CONFIGURATION_PRE_SHARED_KEY, configuration.preSharedKey);
        writeWepKeys(out, configuration.wepKeys);
        XmlUtil.writeNextValue(
                out, XML_TAG_CONFIGURATION_WEP_TX_KEY_INDEX, configuration.wepTxKeyIndex);
        XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_HIDDEN_SSID, configuration.hiddenSSID);
        XmlUtil.writeNextValue(
                out, XML_TAG_CONFIGURATION_ALLOWED_KEY_MGMT,
                configuration.allowedKeyManagement.toByteArray());
        XmlUtil.writeNextValue(
                out, XML_TAG_CONFIGURATION_ALLOWED_PROTOCOLS,
                configuration.allowedProtocols.toByteArray());
        XmlUtil.writeNextValue(
                out, XML_TAG_CONFIGURATION_ALLOWED_AUTH_ALGOS,
                configuration.allowedAuthAlgorithms.toByteArray());
        XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_SHARED, configuration.shared);
        XmlUtil.writeNextValue(out, XML_TAG_CONFIGURATION_CREATOR_UID, configuration.creatorUid);
    }

    /**
     * Parse out the configurations from the back up data.
     *
     * @param data raw byte stream representing the XML data.
     * @return list of networks retrieved from the backed up data.
     */
    public List<WifiConfiguration> retrieveConfigurationsFromBackupData(byte[] data) {
        try {
            if (mVerboseLoggingEnabled) logBackupData(data, "restoreBackupData");

            final XmlPullParser in = Xml.newPullParser();
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            in.setInput(inputStream, StandardCharsets.UTF_8.name());

            // Start parsing the XML stream.
            XmlUtil.gotoDocumentStart(in, XML_TAG_DOCUMENT_HEADER);
            int rootDepth = in.getDepth();

            int version = (int) XmlUtil.readNextValue(in, XML_TAG_VERSION);
            if (version < INITIAL_BACKUP_DATA_VERSION || version > CURRENT_BACKUP_DATA_VERSION) {
                Log.e(TAG, "Invalid version of data: " + version);
                return null;
            }

            return parseConfigurationsFromXml(in, rootDepth, version);
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Error parsing the backup data: " + e);
        } catch (IOException e) {
            Log.e(TAG, "Error parsing the backup data: " + e);
        }
        return null;
    }

    /**
     * Parses the list of configurations from the provided XML stream.
     *
     * @param in          XmlPullParser instance pointing to the XML stream.
     * @param outerDepth  depth of the outer tag of the document.
     * @param dataVersion version number parsed from incoming data.
     * @return List<WifiConfiguration> object if parsing is successful, null otherwise.
     */
    private List<WifiConfiguration> parseConfigurationsFromXml(
            XmlPullParser in, int outerDepth, int dataVersion)
            throws XmlPullParserException, IOException {
        // Find the configuration list section.
        if (!XmlUtil.gotoNextSection(
                in, XML_TAG_SECTION_HEADER_CONFIGURATION_LIST, outerDepth)) {
            Log.e(TAG, "Error parsing the backup data. Did not find configuration list");
            // Malformed XML input, bail out.
            return null;
        }
        // Find all the configurations within the configuration list section.
        int confListDepth = outerDepth + 1;
        List<WifiConfiguration> configurations = new ArrayList<>();
        while (XmlUtil.gotoNextSection(in, XML_TAG_SECTION_HEADER_CONFIGURATION, confListDepth)) {
            WifiConfiguration configuration = parseConfigurationFromXml(in, dataVersion);
            if (configuration != null) {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "Parsed Configuration: " + configuration.configKey());
                }
                configurations.add(configuration);
            }
        }
        return configurations;
    }

    /**
     * Parse WepKeys from the XML stream.
     * Populate wepKeys array only if they were present in the backup data.
     */
    private void parseWepKeys(XmlPullParser in, String[] wepKeys)
            throws XmlPullParserException, IOException {
        String[] wepKeysInData =
                (String[]) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_WEP_KEYS);
        if (wepKeysInData != null) {
            for (int i = 0; i < wepKeys.length; i++) {
                wepKeys[i] = wepKeysInData[i];
            }
        }
    }

    /**
     * Parses the configuration data elements from the provided XML stream to a Configuration.
     *
     * @param in          XmlPullParser instance pointing to the XML stream.
     * @param dataVersion version number parsed from incoming data.
     * @return WifiConfiguration object if parsing is successful, null otherwise.
     */
    private WifiConfiguration parseConfigurationFromXml(XmlPullParser in, int dataVersion)
            throws XmlPullParserException, IOException {

        // Any version migration needs to be handled here in future.
        if (dataVersion == INITIAL_BACKUP_DATA_VERSION) {
            WifiConfiguration configuration = new WifiConfiguration();
            String configKeyInData =
                    (String) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_CONFIG_KEY);
            configuration.SSID =
                    (String) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_SSID);
            configuration.BSSID =
                    (String) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_BSSID);
            configuration.preSharedKey =
                    (String) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_PRE_SHARED_KEY);
            parseWepKeys(in, configuration.wepKeys);
            configuration.wepTxKeyIndex =
                    (int) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_WEP_TX_KEY_INDEX);
            configuration.hiddenSSID =
                    (boolean) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_HIDDEN_SSID);
            byte[] allowedKeyMgmt =
                    (byte[]) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_ALLOWED_KEY_MGMT);
            configuration.allowedKeyManagement = BitSet.valueOf(allowedKeyMgmt);
            byte[] allowedProtocols =
                    (byte[]) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_ALLOWED_PROTOCOLS);
            configuration.allowedProtocols = BitSet.valueOf(allowedProtocols);
            byte[] allowedAuthAlgorithms =
                    (byte[]) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_ALLOWED_AUTH_ALGOS);
            configuration.allowedAuthAlgorithms = BitSet.valueOf(allowedAuthAlgorithms);
            configuration.shared =
                    (boolean) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_SHARED);
            configuration.creatorUid =
                    (int) XmlUtil.readNextValue(in, XML_TAG_CONFIGURATION_CREATOR_UID);

            // We should not have all the data to calculate the configKey. Compare it against the
            // configKey stored in the XML data.
            String configKeyCalculated = configuration.configKey();
            if (!configKeyInData.equals(configKeyCalculated)) {
                Log.e(TAG, "Configuration key does not match. InData: " + configKeyInData
                        + "Calculated: " + configKeyCalculated);
                return null;
            }
            return configuration;
        }
        return null;
    }

    /**
     * Log the backup data in XML format with the preShared key masked.
     */
    private void logBackupData(byte[] data, String logString) {
        String xmlString;
        try {
            xmlString = new String(data, StandardCharsets.UTF_8.name());
            // TODO(b/29051876): Mask passwords.
        } catch (UnsupportedEncodingException e) {
            return;
        }
        Log.d(TAG, logString + ": " + xmlString);
    }
}
