package com.android.server.wifi.hotspot2;

import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType;
import com.android.server.wifi.hotspot2.anqp.HSConnectionCapabilityElement;
import com.android.server.wifi.hotspot2.anqp.HSWanMetricsElement;
import com.android.server.wifi.hotspot2.anqp.IPAddressTypeAvailabilityElement;
import com.android.server.wifi.hotspot2.anqp.ProtocolPortTuple;

import java.util.HashMap;
import java.util.Map;

/**
 * TODO(b/32714185): update using the new HomeSP object.
 */
public class PasspointMatchInfo implements Comparable<PasspointMatchInfo> {
    private final PasspointMatch mPasspointMatch;
    private final ScanDetail mScanDetail;
    private final int mScore;

    private static final Map<Integer, Integer> sIP4Scores = new HashMap<>();
    private static final Map<Integer, Integer> sIP6Scores = new HashMap<>();

    private static final Map<Integer, Map<Integer, Integer>> sPortScores = new HashMap<>();

    private static final int IPPROTO_ICMP = 1;
    private static final int IPPROTO_TCP = 6;
    private static final int IPPROTO_UDP = 17;
    private static final int IPPROTO_ESP = 50;
    private static final Map<NetworkDetail.Ant, Integer> sAntScores = new HashMap<>();

    static {
        // These are all arbitrarily chosen scores, subject to tuning.

        sAntScores.put(NetworkDetail.Ant.FreePublic, 4);
        sAntScores.put(NetworkDetail.Ant.ChargeablePublic, 4);
        sAntScores.put(NetworkDetail.Ant.PrivateWithGuest, 4);
        sAntScores.put(NetworkDetail.Ant.Private, 4);
        sAntScores.put(NetworkDetail.Ant.Personal, 2);
        sAntScores.put(NetworkDetail.Ant.EmergencyOnly, 2);
        sAntScores.put(NetworkDetail.Ant.Wildcard, 1);
        sAntScores.put(NetworkDetail.Ant.TestOrExperimental, 0);

        sIP4Scores.put(IPAddressTypeAvailabilityElement.IPV4_NOT_AVAILABLE, 0);
        sIP4Scores.put(IPAddressTypeAvailabilityElement.IPV4_PORT_RESTRICTED, 1);
        sIP4Scores.put(IPAddressTypeAvailabilityElement.IPV4_PORT_RESTRICTED_AND_SINGLE_NAT, 1);
        sIP4Scores.put(IPAddressTypeAvailabilityElement.IPV4_PORT_RESTRICTED_AND_DOUBLE_NAT, 1);
        sIP4Scores.put(IPAddressTypeAvailabilityElement.IPV4_UNKNOWN, 1);
        sIP4Scores.put(IPAddressTypeAvailabilityElement.IPV4_PUBLIC, 2);
        sIP4Scores.put(IPAddressTypeAvailabilityElement.IPV4_SINGLE_NAT, 2);
        sIP4Scores.put(IPAddressTypeAvailabilityElement.IPV4_DOUBLE_NAT, 2);

        sIP6Scores.put(IPAddressTypeAvailabilityElement.IPV6_NOT_AVAILABLE, 0);
        sIP6Scores.put(IPAddressTypeAvailabilityElement.IPV6_UNKNOWN, 1);
        sIP6Scores.put(IPAddressTypeAvailabilityElement.IPV6_AVAILABLE, 2);

        Map<Integer, Integer> tcpMap = new HashMap<>();
        tcpMap.put(20, 1);
        tcpMap.put(21, 1);
        tcpMap.put(22, 3);
        tcpMap.put(23, 2);
        tcpMap.put(25, 8);
        tcpMap.put(26, 8);
        tcpMap.put(53, 3);
        tcpMap.put(80, 10);
        tcpMap.put(110, 6);
        tcpMap.put(143, 6);
        tcpMap.put(443, 10);
        tcpMap.put(993, 6);
        tcpMap.put(1723, 7);

        Map<Integer, Integer> udpMap = new HashMap<>();
        udpMap.put(53, 10);
        udpMap.put(500, 7);
        udpMap.put(5060, 10);
        udpMap.put(4500, 4);

        sPortScores.put(IPPROTO_TCP, tcpMap);
        sPortScores.put(IPPROTO_UDP, udpMap);
    }


    public PasspointMatchInfo(PasspointMatch passpointMatch,
                              ScanDetail scanDetail) {
        mPasspointMatch = passpointMatch;
        mScanDetail = scanDetail;

        int score;
        if (passpointMatch == PasspointMatch.HomeProvider) {
            score = 100;
        }
        else if (passpointMatch == PasspointMatch.RoamingProvider) {
            score = 0;
        }
        else {
            score = -1000;  // Don't expect to see anything not home or roaming.
        }

        if (getNetworkDetail().getHSRelease() != null) {
            score += getNetworkDetail().getHSRelease() != NetworkDetail.HSRelease.Unknown ? 50 : 0;
        }

        if (getNetworkDetail().hasInterworking()) {
            score += getNetworkDetail().isInternet() ? 20 : -20;
        }

        score += (Math.max(200-getNetworkDetail().getStationCount(), 0) *
                (255-getNetworkDetail().getChannelUtilization()) *
                getNetworkDetail().getCapacity()) >>> 26;
                // Gives a value of 23 max capped at 200 stations and max cap 31250

        if (getNetworkDetail().hasInterworking()) {
            score += sAntScores.get(getNetworkDetail().getAnt());
        }

        Map<ANQPElementType, ANQPElement> anqp = getNetworkDetail().getANQPElements();

        if (anqp != null) {
            HSWanMetricsElement wm = (HSWanMetricsElement) anqp.get(ANQPElementType.HSWANMetrics);

            if (wm != null) {
                if (wm.getStatus() != HSWanMetricsElement.LINK_STATUS_UP || wm.isCapped()) {
                    score -= 1000;
                } else {
                    long scaledSpeed =
                            wm.getDownlinkSpeed() * (255 - wm.getDownlinkLoad()) * 8
                            + wm.getUplinkSpeed() * (255 - wm.getUplinkLoad()) * 2;
                    score += Math.min(scaledSpeed, 255000000L) >>> 23;
                    // Max value is 30 capped at 100Mb/s
                }
            }

            IPAddressTypeAvailabilityElement ipa =
                    (IPAddressTypeAvailabilityElement) anqp.get(ANQPElementType.ANQPIPAddrAvailability);

            if (ipa != null) {
                Integer as14 = sIP4Scores.get(ipa.getV4Availability());
                Integer as16 = sIP6Scores.get(ipa.getV6Availability());
                as14 = as14 != null ? as14 : 1;
                as16 = as16 != null ? as16 : 1;
                // Is IPv4 twice as important as IPv6???
                score += as14 * 2 + as16;
            }

            HSConnectionCapabilityElement cce =
                    (HSConnectionCapabilityElement) anqp.get(ANQPElementType.HSConnCapability);

            if (cce != null) {
                score = Math.min(Math.max(protoScore(cce) >> 3, -10), 10);
            }
        }

        mScore = score;
    }

    public PasspointMatch getPasspointMatch() {
        return mPasspointMatch;
    }

    public ScanDetail getScanDetail() {
        return mScanDetail;
    }

    public NetworkDetail getNetworkDetail() {
        return mScanDetail.getNetworkDetail();
    }

    public int getScore() {
        return mScore;
    }

    @Override
    public int compareTo(PasspointMatchInfo that) {
        return getScore() - that.getScore();
    }

    private static int protoScore(HSConnectionCapabilityElement cce) {
        int score = 0;
        for (ProtocolPortTuple tuple : cce.getStatusList()) {
            int sign = tuple.getStatus() == ProtocolPortTuple.PROTO_STATUS_OPEN
                    ? 1 : -1;

            int elementScore = 1;
            if (tuple.getProtocol() == IPPROTO_ICMP) {
                elementScore = 1;
            }
            else if (tuple.getProtocol() == IPPROTO_ESP) {
                elementScore = 5;
            }
            else {
                Map<Integer, Integer> protoMap = sPortScores.get(tuple.getProtocol());
                if (protoMap != null) {
                    Integer portScore = protoMap.get(tuple.getPort());
                    elementScore = portScore != null ? portScore : 0;
                }
            }
            score += elementScore * sign;
        }
        return score;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (thatObject == null || getClass() != thatObject.getClass()) {
            return false;
        }

        PasspointMatchInfo that = (PasspointMatchInfo)thatObject;

        return getNetworkDetail().equals(that.getNetworkDetail()) &&
                getPasspointMatch().equals(that.getPasspointMatch());
    }

    @Override
    public int hashCode() {
        int result = mPasspointMatch != null ? mPasspointMatch.hashCode() : 0;
        result = 31 * result + getNetworkDetail().hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "PasspointMatchInfo{" +
                ", mPasspointMatch=" + mPasspointMatch +
                ", mNetworkInfo=" + getNetworkDetail().getSSID() +
                '}';
    }
}
