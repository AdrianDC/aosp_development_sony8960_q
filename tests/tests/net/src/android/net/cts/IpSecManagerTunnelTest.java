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

package android.net.cts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.Network;

import com.android.compatibility.common.util.SystemUtil;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;

public class IpSecManagerTunnelTest extends IpSecBaseTest {

    private static final String TAG = IpSecManagerTunnelTest.class.getSimpleName();
    private static final int IP4_PREFIX_LEN = 24;
    private static final int IP6_PREFIX_LEN = 48;
    private static final InetAddress OUTER_ADDR4 = InetAddress.parseNumericAddress("192.0.2.0");
    private static final InetAddress OUTER_ADDR6 =
            InetAddress.parseNumericAddress("2001:db8:f00d::1");
    private static final InetAddress INNER_ADDR4 = InetAddress.parseNumericAddress("10.0.0.1");
    private static final InetAddress INNER_ADDR6 =
            InetAddress.parseNumericAddress("2001:db8:d00d::1");

    private Network mUnderlyingNetwork;
    private Network mIpSecNetwork;

    protected void setUp() throws Exception {
        super.setUp();
    }

    protected void tearDown() {
        setAppop(false);
    }

    private void setAppop(boolean allow) {
        // Under normal circumstances, the MANAGE_IPSEC_TUNNELS appop would be auto-granted by the
        // telephony framework, and the only permission that is sufficient is NETWORK_STACK. So we
        // shell out the appop manager, to give us the right appop permissions.
        String cmd =
                "appops set "
                        + mContext.getPackageName()
                        + " MANAGE_IPSEC_TUNNELS "
                + (allow ? "allow" : "deny");
        SystemUtil.runShellCommand(cmd);
    }

    public void testSecurityExceptionsCreateTunnelInterface() throws Exception {
        // Ensure we don't have the appop. Permission is not requested in the Manifest
        setAppop(false);

        // Security exceptions are thrown regardless of IPv4/IPv6. Just test one
        try {
            mISM.createIpSecTunnelInterface(OUTER_ADDR6, OUTER_ADDR6, mUnderlyingNetwork);
            fail("Did not throw SecurityException for Tunnel creation without appop");
        } catch (SecurityException expected) {
        }
    }

    public void testSecurityExceptionsBuildTunnelTransform() throws Exception {
        // Ensure we don't have the appop. Permission is not requested in the Manifest
        setAppop(false);

        // Security exceptions are thrown regardless of IPv4/IPv6. Just test one
        try (IpSecManager.SecurityParameterIndex spi =
                mISM.allocateSecurityParameterIndex(OUTER_ADDR4);
                IpSecTransform transform =
                        new IpSecTransform.Builder(mContext)
                                .buildTunnelModeTransform(OUTER_ADDR4, spi)) {
            fail("Did not throw SecurityException for Transform creation without appop");
        } catch (SecurityException expected) {
        }
    }

    private void checkTunnel(InetAddress inner, InetAddress outer, boolean useEncap)
            throws Exception {
        setAppop(true);
        int innerPrefixLen = inner instanceof Inet6Address ? IP6_PREFIX_LEN : IP4_PREFIX_LEN;

        try (IpSecManager.SecurityParameterIndex spi = mISM.allocateSecurityParameterIndex(outer);
                IpSecManager.IpSecTunnelInterface tunnelIntf =
                        mISM.createIpSecTunnelInterface(outer, outer, mCM.getActiveNetwork());
                IpSecManager.UdpEncapsulationSocket encapSocket =
                        mISM.openUdpEncapsulationSocket()) {

            IpSecTransform.Builder transformBuilder = new IpSecTransform.Builder(mContext);
            transformBuilder.setEncryption(
                    new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, CRYPT_KEY));
            transformBuilder.setAuthentication(
                    new IpSecAlgorithm(
                            IpSecAlgorithm.AUTH_HMAC_SHA256, AUTH_KEY, AUTH_KEY.length * 4));

            if (useEncap) {
                transformBuilder.setIpv4Encapsulation(encapSocket, encapSocket.getPort());
            }

            // Check transform application
            try (IpSecTransform transform = transformBuilder.buildTunnelModeTransform(outer, spi)) {
                mISM.applyTunnelModeTransform(tunnelIntf, IpSecManager.DIRECTION_IN, transform);
                mISM.applyTunnelModeTransform(tunnelIntf, IpSecManager.DIRECTION_OUT, transform);

                // TODO: Test to ensure that send/receive works with these transforms.
            }

            // Check interface was created
            NetworkInterface netIntf = NetworkInterface.getByName(tunnelIntf.getInterfaceName());
            assertNotNull(netIntf);

            // Add addresses and check
            tunnelIntf.addAddress(inner, innerPrefixLen);
            for (InterfaceAddress intfAddr : netIntf.getInterfaceAddresses()) {
                assertEquals(intfAddr.getAddress(), inner);
                assertEquals(intfAddr.getNetworkPrefixLength(), innerPrefixLen);
            }

            // Remove addresses and check
            tunnelIntf.removeAddress(inner, innerPrefixLen);
            assertTrue(netIntf.getInterfaceAddresses().isEmpty());

            // Check interface was cleaned up
            tunnelIntf.close();
            netIntf = NetworkInterface.getByName(tunnelIntf.getInterfaceName());
            assertNull(netIntf);
        }
    }

    /*
     * Create, add and remove addresses, then teardown tunnel
     */
    public void testTunnelV4InV4() throws Exception {
        checkTunnel(INNER_ADDR4, OUTER_ADDR4, false);
    }

    public void testTunnelV4InV4UdpEncap() throws Exception {
        checkTunnel(INNER_ADDR4, OUTER_ADDR4, true);
    }

    public void testTunnelV4InV6() throws Exception {
        checkTunnel(INNER_ADDR4, OUTER_ADDR6, false);
    }

    public void testTunnelV6InV4() throws Exception {
        checkTunnel(INNER_ADDR6, OUTER_ADDR4, false);
    }

    public void testTunnelV6InV4UdpEncap() throws Exception {
        checkTunnel(INNER_ADDR6, OUTER_ADDR4, true);
    }

    public void testTunnelV6InV6() throws Exception {
        checkTunnel(INNER_ADDR6, OUTER_ADDR6, false);
    }
}
