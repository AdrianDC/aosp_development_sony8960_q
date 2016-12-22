package com.android.server.wifi.hotspot2.anqp;

import com.android.server.wifi.hotspot2.NetworkDetail;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Factory to build a collection of 802.11u ANQP elements from a byte buffer.
 */
public class ANQPFactory {

    private static final List<Constants.ANQPElementType> BaseANQPSet1 = Arrays.asList(
            Constants.ANQPElementType.ANQPVenueName,
            Constants.ANQPElementType.ANQPRoamingConsortium,
            Constants.ANQPElementType.ANQPIPAddrAvailability,
            Constants.ANQPElementType.ANQPNAIRealm,
            Constants.ANQPElementType.ANQP3GPPNetwork,
            Constants.ANQPElementType.ANQPDomName);

    private static final List<Constants.ANQPElementType> BaseANQPSet2 = Arrays.asList(
            Constants.ANQPElementType.ANQPVenueName,
            Constants.ANQPElementType.ANQPIPAddrAvailability,
            Constants.ANQPElementType.ANQPNAIRealm,
            Constants.ANQPElementType.ANQP3GPPNetwork,
            Constants.ANQPElementType.ANQPDomName);

    private static final List<Constants.ANQPElementType> HS20ANQPSet = Arrays.asList(
            Constants.ANQPElementType.HSFriendlyName,
            Constants.ANQPElementType.HSWANMetrics,
            Constants.ANQPElementType.HSConnCapability);

    private static final List<Constants.ANQPElementType> HS20ANQPSetwOSU = Arrays.asList(
            Constants.ANQPElementType.HSFriendlyName,
            Constants.ANQPElementType.HSWANMetrics,
            Constants.ANQPElementType.HSConnCapability,
            Constants.ANQPElementType.HSOSUProviders);

    public static List<Constants.ANQPElementType> getBaseANQPSet(boolean includeRC) {
        return includeRC ? BaseANQPSet1 : BaseANQPSet2;
    }

    public static List<Constants.ANQPElementType> getHS20ANQPSet(boolean includeOSU) {
        return includeOSU ? HS20ANQPSetwOSU : HS20ANQPSet;
    }

    public static List<Constants.ANQPElementType> buildQueryList(NetworkDetail networkDetail,
                                               boolean matchSet, boolean osu) {
        List<Constants.ANQPElementType> querySet = new ArrayList<>();

        if (matchSet) {
            querySet.addAll(getBaseANQPSet(networkDetail.getAnqpOICount() > 0));
        }

        if (networkDetail.getHSRelease() != null) {
            boolean includeOSU = osu && networkDetail.getHSRelease() == NetworkDetail.HSRelease.R2;
            if (matchSet) {
                querySet.addAll(getHS20ANQPSet(includeOSU));
            }
            else if (includeOSU) {
                querySet.add(Constants.ANQPElementType.HSOSUProviders);
            }
        }

        return querySet;
    }

    /**
     * Build an ANQP element from the pass-in byte buffer.
     *
     * Note: Each Hotspot 2.0 Release 2 element will be wrapped inside a Vendor Specific element
     * in the ANQP response from the AP.  However, the lower layer (e.g. wpa_supplicant) should
     * already take care of parsing those elements out of Vendor Specific elements.  To be safe,
     * we will parse the Vendor Specific elements for non-Hotspot 2.0 Release elements or in
     * the case they're not parsed by the lower layer.
     *
     * @param infoID The ANQP element type
     * @param payload The buffer to read from
     * @return {@link com.android.server.wifi.hotspot2.anqp.ANQPElement}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static ANQPElement buildElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        switch (infoID) {
            case ANQPVenueName:
                return VenueNameElement.parse(payload);
            case ANQPRoamingConsortium:
                return RoamingConsortiumElement.parse(payload);
            case ANQPIPAddrAvailability:
                return IPAddressTypeAvailabilityElement.parse(payload);
            case ANQPNAIRealm:
                return NAIRealmElement.parse(payload);
            case ANQP3GPPNetwork:
                return ThreeGPPNetworkElement.parse(payload);
            case ANQPDomName:
                return DomainNameElement.parse(payload);
            case ANQPVendorSpec:
                if (payload.remaining() > 5) {
                    int oi = payload.getInt();
                    if (oi != Constants.HS20_PREFIX) {
                        return null;
                    }
                    int subType = payload.get() & Constants.BYTE_MASK;
                    Constants.ANQPElementType hs20ID = Constants.mapHS20Element(subType);
                    if (hs20ID == null) {
                        throw new ProtocolException("Bad HS20 info ID: " + subType);
                    }
                    payload.get();   // Skip the reserved octet
                    return buildHS20Element(hs20ID, payload);
                } else {
                    return new GenericBlobElement(infoID, payload);
                }
            default:
                throw new ProtocolException("Unknown element ID: " + infoID);
        }
    }

    /**
     * Build a Hotspot 2.0 Release 2 ANQP element from the pass-in byte buffer.
     *
     * @param infoID The ANQP element ID
     * @param payload The buffer to read from
     * @return {@link com.android.server.wifi.hotspot2.anqp.ANQPElement}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static ANQPElement buildHS20Element(Constants.ANQPElementType infoID,
            ByteBuffer payload) throws ProtocolException {
        switch (infoID) {
            case HSFriendlyName:
                return HSFriendlyNameElement.parse(payload);
            case HSWANMetrics:
                return HSWanMetricsElement.parse(payload);
            case HSConnCapability:
                return HSConnectionCapabilityElement.parse(payload);
            case HSOSUProviders:
                return RawByteElement.parse(infoID, payload);
            default:
                throw new ProtocolException("Unknown element ID: " + infoID);
        }
    }
}
