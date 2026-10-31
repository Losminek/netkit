package cz.netkit.shared.net

import cz.netkit.shared.net.ToolRouteDecision.*
import kotlin.test.*

class ToolRouteTest {
    @Test fun publicTargetUsesTheActiveNetwork() {
        assertEquals(ACTIVE, chooseToolRoute("192.0.2.1", wifiAvailable = true, activeKind = NetKind.WIFI, viaVpn = false))
        assertEquals(ACTIVE, chooseToolRoute("198.51.100.8", wifiAvailable = false, activeKind = NetKind.CELLULAR, viaVpn = false))
        assertEquals(ACTIVE, chooseToolRoute("203.0.113.5", wifiAvailable = true, activeKind = NetKind.VPN, viaVpn = true))
        assertEquals(ACTIVE, chooseToolRoute("2001:db8::1", wifiAvailable = false, activeKind = NetKind.ETHERNET, viaVpn = false))
        assertEquals(WIFI, chooseToolRoute("198.51.100.1", wifiAvailable = true, activeKind = null, viaVpn = false))
        assertEquals(NO_NETWORK, chooseToolRoute("198.51.100.1", wifiAvailable = false, activeKind = null, viaVpn = false))
    }

    @Test fun privateTargetPrefersWifi() {
        assertEquals(WIFI, chooseToolRoute("192.168.0.10", true, NetKind.WIFI, false, targetInWifiSubnet = true))
        assertEquals(WIFI, chooseToolRoute("10.0.0.1", true, NetKind.WIFI, false))
        // Wi-Fi without internet while mobile data is the default: the LAN still goes over Wi-Fi.
        assertEquals(WIFI, chooseToolRoute("192.168.0.10", true, NetKind.CELLULAR, false, targetInWifiSubnet = true))
        assertEquals(WIFI, chooseToolRoute("192.168.0.10", true, NetKind.VPN, true, targetInWifiSubnet = true))
    }

    @Test fun privateTargetOutsideWifiSubnetGoesThroughVpn() {
        assertEquals(ACTIVE, chooseToolRoute("10.20.0.5", true, NetKind.VPN, true, targetInWifiSubnet = false))
        assertEquals(ACTIVE, chooseToolRoute("100.100.1.2", false, NetKind.VPN, true))
        assertEquals(ACTIVE, chooseToolRoute("fd00::5", false, NetKind.VPN, true))
    }

    @Test fun privateTargetOnMobileDataNeedsWifiOrVpn() {
        assertEquals(LOCAL_NEEDS_WIFI_OR_VPN, chooseToolRoute("192.168.0.10", false, NetKind.CELLULAR, false))
        assertEquals(LOCAL_NEEDS_WIFI_OR_VPN, chooseToolRoute("100.64.0.1", false, NetKind.OTHER, false))
        assertEquals(ACTIVE, chooseToolRoute("192.168.0.10", false, NetKind.ETHERNET, false))
        assertEquals(NO_NETWORK, chooseToolRoute("192.168.0.10", false, null, false))
    }

    @Test fun loopbackIsNeverRefused() {
        assertEquals(WIFI, chooseToolRoute("127.0.0.1", true, NetKind.WIFI, false))
        assertEquals(ACTIVE, chooseToolRoute("127.0.0.1", false, NetKind.CELLULAR, false))
        assertEquals(NO_NETWORK, chooseToolRoute("::1", false, null, false))
    }

    @Test fun sshHintOnlyForLocalTargetOverMobileWithoutVpn() {
        assertTrue(localTargetNeedsVpn("192.168.0.5", NetKind.CELLULAR, viaVpn = false))
        assertTrue(localTargetNeedsVpn("fd00::5", NetKind.OTHER, viaVpn = false))
        assertFalse(localTargetNeedsVpn("192.168.0.5", NetKind.CELLULAR, viaVpn = true))
        assertFalse(localTargetNeedsVpn("192.168.0.5", NetKind.VPN, viaVpn = true))
        assertFalse(localTargetNeedsVpn("192.168.0.5", NetKind.WIFI, viaVpn = false))
        assertFalse(localTargetNeedsVpn("192.168.0.5", NetKind.ETHERNET, viaVpn = false))
        assertFalse(localTargetNeedsVpn("192.168.0.5", null, viaVpn = false))
        assertFalse(localTargetNeedsVpn("127.0.0.1", NetKind.CELLULAR, viaVpn = false))
        assertFalse(localTargetNeedsVpn("nas.lan", NetKind.CELLULAR, viaVpn = false))
        assertFalse(localTargetNeedsVpn("203.0.113.9", NetKind.CELLULAR, viaVpn = false))
    }
}
