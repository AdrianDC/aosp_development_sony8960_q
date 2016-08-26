package com.android.server.wifi.hotspot2;

import android.util.Base64;
import android.util.Log;

import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.ANQPFactory;
import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.anqp.eap.AuthParam;
import com.android.server.wifi.anqp.eap.EAP;
import com.android.server.wifi.anqp.eap.EAPMethod;
import com.android.server.wifi.hotspot2.pps.Credential;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SupplicantBridge {
    private final WifiNative mSupplicantHook;
    private final SupplicantBridgeCallbacks mCallbacks;
    private final Map<Long, ScanDetail> mRequestMap = new HashMap<>();

    private static final int IconChunkSize = 1400;  // 2K*3/4 - overhead
    private static final Map<String, Constants.ANQPElementType> sWpsNames = new HashMap<>();

    static {
        sWpsNames.put("anqp_venue_name", Constants.ANQPElementType.ANQPVenueName);
        sWpsNames.put("anqp_roaming_consortium", Constants.ANQPElementType.ANQPRoamingConsortium);
        sWpsNames.put("anqp_ip_addr_type_availability",
                Constants.ANQPElementType.ANQPIPAddrAvailability);
        sWpsNames.put("anqp_nai_realm", Constants.ANQPElementType.ANQPNAIRealm);
        sWpsNames.put("anqp_3gpp", Constants.ANQPElementType.ANQP3GPPNetwork);
        sWpsNames.put("anqp_domain_name", Constants.ANQPElementType.ANQPDomName);
        sWpsNames.put("hs20_operator_friendly_name", Constants.ANQPElementType.HSFriendlyName);
        sWpsNames.put("hs20_wan_metrics", Constants.ANQPElementType.HSWANMetrics);
        sWpsNames.put("hs20_connection_capability", Constants.ANQPElementType.HSConnCapability);
        sWpsNames.put("hs20_osu_providers_list", Constants.ANQPElementType.HSOSUProviders);
    }

    /**
     * Interface to be implemented by the client to receive callbacks from SupplicantBridge.
     */
    public interface SupplicantBridgeCallbacks {
        /**
         * Response from supplicant bridge for the initiated request.
         * @param scanDetail
         * @param anqpElements
         */
        void notifyANQPResponse(
                ScanDetail scanDetail,
                Map<Constants.ANQPElementType, ANQPElement> anqpElements);

        /**
         * Notify failure.
         * @param bssid
         */
        void notifyIconFailed(long bssid);
    }

    public static boolean isAnqpAttribute(String line) {
        int split = line.indexOf('=');
        return split >= 0 && sWpsNames.containsKey(line.substring(0, split));
    }

    public SupplicantBridge(WifiNative supplicantHook, SupplicantBridgeCallbacks callbacks) {
        mSupplicantHook = supplicantHook;
        mCallbacks = callbacks;
    }

    public static Map<Constants.ANQPElementType, ANQPElement> parseANQPLines(List<String> lines) {
        if (lines == null) {
            return null;
        }
        Map<Constants.ANQPElementType, ANQPElement> elements = new HashMap<>(lines.size());
        for (String line : lines) {
            try {
                ANQPElement element = buildElement(line);
                if (element != null) {
                    elements.put(element.getID(), element);
                }
            }
            catch (ProtocolException pe) {
                Log.e(Utils.hs2LogTag(SupplicantBridge.class), "Failed to parse ANQP: " + pe);
            }
        }
        return elements;
    }

    public boolean startANQP(ScanDetail scanDetail, List<Constants.ANQPElementType> elements) {
        String anqpGet = buildWPSQueryRequest(scanDetail.getNetworkDetail(), elements);
        if (anqpGet == null) {
            return false;
        }
        synchronized (mRequestMap) {
            mRequestMap.put(scanDetail.getNetworkDetail().getBSSID(), scanDetail);
        }
        String result = mSupplicantHook.doCustomSupplicantCommand(anqpGet);
        if (result != null && result.startsWith("OK")) {
            Log.d(Utils.hs2LogTag(getClass()), "ANQP initiated on "
                    + scanDetail + " (" + anqpGet + ")");
            return true;
        }
        else {
            Log.d(Utils.hs2LogTag(getClass()), "ANQP failed on " +
                    scanDetail + ": " + result);
            return false;
        }
    }

    public boolean doIconQuery(long bssid, String fileName) {
        String result = mSupplicantHook.doCustomSupplicantCommand("REQ_HS20_ICON " +
                Utils.macToString(bssid) + " " + fileName);
        return result != null && result.startsWith("OK");
    }

    public byte[] retrieveIcon(IconEvent iconEvent) throws IOException {
        byte[] iconData = new byte[iconEvent.getSize()];
        try {
            int offset = 0;
            while (offset < iconEvent.getSize()) {
                int size = Math.min(iconEvent.getSize() - offset, IconChunkSize);

                String command = String.format("GET_HS20_ICON %s %s %d %d",
                        Utils.macToString(iconEvent.getBSSID()), iconEvent.getFileName(),
                        offset, size);
                Log.d(Utils.hs2LogTag(getClass()), "Issuing '" + command + "'");
                String response = mSupplicantHook.doCustomSupplicantCommand(command);
                if (response == null) {
                    throw new IOException("No icon data returned");
                }

                try {
                    byte[] fragment = Base64.decode(response, Base64.DEFAULT);
                    if (fragment.length == 0) {
                        throw new IOException("Null data for '" + command + "': " + response);
                    }
                    if (fragment.length + offset > iconData.length) {
                        throw new IOException("Icon chunk exceeds image size");
                    }
                    System.arraycopy(fragment, 0, iconData, offset, fragment.length);
                    offset += fragment.length;
                } catch (IllegalArgumentException iae) {
                    throw new IOException("Failed to parse response to '" + command
                            + "': " + response);
                }
            }
            if (offset != iconEvent.getSize()) {
                Log.w(Utils.hs2LogTag(getClass()), "Partial icon data: " + offset +
                        ", expected " + iconEvent.getSize());
            }
        }
        finally {
            Log.d(Utils.hs2LogTag(getClass()), "Deleting icon for " + iconEvent);
            String result = mSupplicantHook.doCustomSupplicantCommand("DEL_HS20_ICON " +
                    Utils.macToString(iconEvent.getBSSID()) + " " + iconEvent.getFileName());
        }

        return iconData;
    }

    public void notifyANQPDone(Long bssid, boolean success) {
        ScanDetail scanDetail;
        synchronized (mRequestMap) {
            scanDetail = mRequestMap.remove(bssid);
        }

        if (scanDetail == null) {
            // Icon queries are not held on the request map, so a null scanDetail is very likely
            // the result of an Icon query. Notify the OSU app if the query was unsuccessful,
            // else bail out.
            if (!success) {
                mCallbacks.notifyIconFailed(bssid);
            }
            return;
        } else if (!success) {
            // If there is an associated ScanDetail, notify of a failed regular ANQP query.
            mCallbacks.notifyANQPResponse(scanDetail, null);
            return;
        }

        String bssData = mSupplicantHook.scanResult(scanDetail.getBSSIDString());
        Map<Constants.ANQPElementType, ANQPElement> elements = null;
        try {
            elements = parseWPSData(bssData);
            Log.d(Utils.hs2LogTag(getClass()),
                    String.format("Successful ANQP response for %012x: %s", bssid, elements));
        }
        catch (IOException ioe) {
            Log.e(Utils.hs2LogTag(getClass()), "Failed to parse ANQP: " +
                    ioe.toString() + ": " + bssData);
        }
        catch (RuntimeException rte) {
            Log.e(Utils.hs2LogTag(getClass()), "Failed to parse ANQP: " +
                    rte.toString() + ": " + bssData, rte);
        }
        mCallbacks.notifyANQPResponse(scanDetail, elements);
    }

    private static String escapeSSID(NetworkDetail networkDetail) {
        return escapeString(networkDetail.getSSID(), networkDetail.isSSID_UTF8());
    }

    private static String escapeString(String s, boolean utf8) {
        boolean asciiOnly = true;
        for (int n = 0; n < s.length(); n++) {
            char ch = s.charAt(n);
            if (ch > 127) {
                asciiOnly = false;
                break;
            }
        }

        if (asciiOnly) {
            return '"' + s + '"';
        }
        else {
            byte[] octets = s.getBytes(utf8 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);

            StringBuilder sb = new StringBuilder();
            for (byte octet : octets) {
                sb.append(String.format("%02x", octet & Constants.BYTE_MASK));
            }
            return sb.toString();
        }
    }

    /**
     * Build a wpa_supplicant ANQP query command
     * @param networkDetail The network to query.
     * @param querySet elements to query
     * @return A command string.
     */
    private static String buildWPSQueryRequest(NetworkDetail networkDetail,
                                               List<Constants.ANQPElementType> querySet) {

        boolean baseANQPElements = Constants.hasBaseANQPElements(querySet);
        StringBuilder sb = new StringBuilder();
        if (baseANQPElements) {
            sb.append("ANQP_GET ");
        }
        else {
            sb.append("HS20_ANQP_GET ");     // ANQP_GET does not work for a sole hs20:8 (OSU) query
        }
        sb.append(networkDetail.getBSSIDString()).append(' ');

        boolean first = true;
        for (Constants.ANQPElementType elementType : querySet) {
            if (first) {
                first = false;
            }
            else {
                sb.append(',');
            }

            Integer id = Constants.getANQPElementID(elementType);
            if (id != null) {
                sb.append(id);
            }
            else {
                id = Constants.getHS20ElementID(elementType);
                if (baseANQPElements) {
                    sb.append("hs20:");
                }
                sb.append(id);
            }
        }

        return sb.toString();
    }

    private static List<String> getWPSNetCommands(String netID, NetworkDetail networkDetail,
                                                 Credential credential) {

        List<String> commands = new ArrayList<String>();

        EAPMethod eapMethod = credential.getEAPMethod();
        commands.add(String.format("SET_NETWORK %s key_mgmt WPA-EAP", netID));
        commands.add(String.format("SET_NETWORK %s ssid %s", netID, escapeSSID(networkDetail)));
        commands.add(String.format("SET_NETWORK %s bssid %s",
                netID, networkDetail.getBSSIDString()));
        commands.add(String.format("SET_NETWORK %s eap %s",
                netID, mapEAPMethodName(eapMethod.getEAPMethodID())));

        AuthParam authParam = credential.getEAPMethod().getAuthParam();
        if (authParam == null) {
            return null;            // TLS or SIM/AKA
        }
        switch (authParam.getAuthInfoID()) {
            case NonEAPInnerAuthType:
            case InnerAuthEAPMethodType:
                commands.add(String.format("SET_NETWORK %s identity %s",
                        netID, escapeString(credential.getUserName(), true)));
                commands.add(String.format("SET_NETWORK %s password %s",
                        netID, escapeString(credential.getPassword(), true)));
                commands.add(String.format("SET_NETWORK %s anonymous_identity \"anonymous\"",
                        netID));
                break;
            default:                // !!! Needs work.
                return null;
        }
        commands.add(String.format("SET_NETWORK %s priority 0", netID));
        commands.add(String.format("ENABLE_NETWORK %s", netID));
        commands.add(String.format("SAVE_CONFIG"));
        return commands;
    }

    private static Map<Constants.ANQPElementType, ANQPElement> parseWPSData(String bssInfo)
            throws IOException {
        Map<Constants.ANQPElementType, ANQPElement> elements = new HashMap<>();
        if (bssInfo == null) {
            return elements;
        }
        BufferedReader lineReader = new BufferedReader(new StringReader(bssInfo));
        String line;
        while ((line=lineReader.readLine()) != null) {
            ANQPElement element = buildElement(line);
            if (element != null) {
                elements.put(element.getID(), element);
            }
        }
        return elements;
    }

    private static ANQPElement buildElement(String text) throws ProtocolException {
        int separator = text.indexOf('=');
        if (separator < 0 || separator + 1 == text.length()) {
            return null;
        }

        String elementName = text.substring(0, separator);
        Constants.ANQPElementType elementType = sWpsNames.get(elementName);
        if (elementType == null) {
            return null;
        }

        byte[] payload;
        try {
            payload = Utils.hexToBytes(text.substring(separator + 1));
        }
        catch (NumberFormatException nfe) {
            Log.e(Utils.hs2LogTag(SupplicantBridge.class), "Failed to parse hex string");
            return null;
        }
        return Constants.getANQPElementID(elementType) != null ?
                ANQPFactory.buildElement(ByteBuffer.wrap(payload), elementType, payload.length) :
                ANQPFactory.buildHS20Element(elementType,
                        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN));
    }

    private static String mapEAPMethodName(EAP.EAPMethodID eapMethodID) {
        switch (eapMethodID) {
            case EAP_AKA:
                return "AKA";
            case EAP_AKAPrim:
                return "AKA'";  // eap.c:1514
            case EAP_SIM:
                return "SIM";
            case EAP_TLS:
                return "TLS";
            case EAP_TTLS:
                return "TTLS";
            default:
                throw new IllegalArgumentException("No mapping for " + eapMethodID);
        }
    }

}
