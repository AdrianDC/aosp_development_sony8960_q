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

package com.android.server.wifi.util;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;

/**
 * Utils for manipulating XML data. This is essentially a wrapper over XmlUtils provided by core.
 * The utility provides methods to write/parse section headers and write/parse values.
 *
 * This utility is designed for formatting the XML into the following format:
 * <Document Header>
 *  <Section 1 Header>
 *   <Value 1>
 *   <Value 2>
 *   ...
 *   <Sub Section 1 Header>
 *    <Value 1>
 *    <Value 2>
 *    ...
 *   </Sub Section 1 Header>
 *  </Section 1 Header>
 * </Document Header>
 */
public class XmlUtil {
    private static final String TAG = "WifiXmlUtil";

    /**
     * Move the XML stream to the start of the next tag or the end of document.
     */
    private static void gotoNextElement(XmlPullParser in)
            throws XmlPullParserException, IOException {
        XmlUtils.nextElement(in);
    }

    /**
     * Start processing the XML stream at the document header.
     *
     * @param in         XmlPullParser instance pointing to the XML stream.
     * @param headerName expected name for the start tag.
     */
    public static void gotoDocumentStart(XmlPullParser in, String headerName)
            throws XmlPullParserException, IOException {
        XmlUtils.beginDocument(in, headerName);
    }

    /**
     * Move the XML stream to the next section header. The provided outerdepth is used to find
     * sub sections within the provided depth.
     *
     * @param in         XmlPullParser instance pointing to the XML stream.
     * @param headerName expected name for the start tag.
     * @param outerDepth Find section within this depth.
     * @return {@code true} if a start tag with the provided name is found, {@code false} otherwise
     */
    public static boolean gotoNextSection(XmlPullParser in, String headerName, int outerDepth)
            throws XmlPullParserException, IOException {
        while (XmlUtils.nextElementWithin(in, outerDepth)) {
            if (in.getName().equals(headerName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read the next value in the XML stream using core XmlUtils and ensure that it matches the
     * provided name.
     * Note: Because there could be genuine null values being read from the XML, this method raises
     * an exception to indicate errors.
     *
     * @param in XmlPullParser instance pointing to the XML stream.
     * @return value retrieved from the XML stream.
     * @throws {XmlPullParserException}, if the value read does not match |expectedName|,
     *                                   or if parsing errors occur.
     */
    public static Object readNextValue(XmlPullParser in, String expectedName)
            throws XmlPullParserException, IOException {
        String[] names = new String[1];
        names[0] = new String();
        gotoNextElement(in);
        Object value = XmlUtils.readValueXml(in, names);
        if (names[0].equals(expectedName)) {
            // XmlUtils.readValue does not always move the stream to the end of the tag. So, move
            // it to the end before returning from here.
            while (in.getEventType() != XmlPullParser.END_DOCUMENT
                    && in.getEventType() != XmlPullParser.END_TAG) {
                in.next();
            }
            return value;
        }
        throw new XmlPullParserException(
                "Value not found. Expected: " + expectedName + ", but got: " + names[0]);
    }

    /**
     * Write the XML document start with the provided document header name.
     *
     * @param out        XmlSerializer instance pointing to the XML stream.
     * @param headerName name for the start tag.
     */
    public static void writeDocumentStart(XmlSerializer out, String headerName)
            throws XmlPullParserException, IOException {
        out.startDocument(null, true);
        out.startTag(null, headerName);
    }

    /**
     * Write the XML document end with the provided document header name.
     *
     * @param out        XmlSerializer instance pointing to the XML stream.
     * @param headerName name for the end tag.
     */
    public static void writeDocumentEnd(XmlSerializer out, String headerName)
            throws XmlPullParserException, IOException {
        out.endTag(null, headerName);
        out.endDocument();
    }

    /**
     * Write a section start header tag with the provided section name.
     *
     * @param out        XmlSerializer instance pointing to the XML stream.
     * @param headerName name for the start tag.
     */
    public static void writeNextSectionStart(XmlSerializer out, String headerName)
            throws XmlPullParserException, IOException {
        out.startTag(null, headerName);
    }

    /**
     * Write a section end header tag with the provided section name.
     *
     * @param out        XmlSerializer instance pointing to the XML stream.
     * @param headerName name for the end tag.
     */
    public static void writeNextSectionEnd(XmlSerializer out, String headerName)
            throws XmlPullParserException, IOException {
        out.endTag(null, headerName);
    }

    /**
     * Write the value with the provided name in the XML stream using core XmlUtils.
     *
     * @param out   XmlSerializer instance pointing to the XML stream.
     * @param name  name of the value.
     * @param value value to be written.
     */
    public static void writeNextValue(XmlSerializer out, String name, Object value)
            throws XmlPullParserException, IOException {
        XmlUtils.writeValueXml(value, name, out);
    }
}

