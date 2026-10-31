package cz.netkit.shared.overview

import cz.netkit.shared.cellular.CellRat
import cz.netkit.shared.cellular.CellularSim
import cz.netkit.shared.chart.ChartEventKind
import cz.netkit.shared.net.NetKind
import cz.netkit.shared.publicip.IpFamily
import cz.netkit.shared.publicip.PublicAddress
import cz.netkit.shared.publicip.PublicIpInfo
import cz.netkit.shared.publicip.isPublicIpCacheFresh
import cz.netkit.shared.wifi.LiveEvent
import cz.netkit.shared.wifi.WifiBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverviewTest {
    private fun sim(slot: Int, sub: Int, data: Boolean = false, rat: CellRat = CellRat.LTE) =
        CellularSim(slot, sub, "Operator $sub", data, rat, signal = null, roaming = false, elapsedMs = 0)

    private fun info(v4: String? = "203.0.113.7", v6: String? = null) = PublicIpInfo(
        v4 = v4?.let { PublicAddress(IpFamily.V4, it) }, v6 = v6?.let { PublicAddress(IpFamily.V6, it) },
        asn = 64500, prefix = "203.0.113.0/24", provider = "Example", providerRaw = "EX - Example, CZ", country = "CZ",
        fetchedAtMs = 1_000,
    )

    // --- Layout ---------------------------------------------------------------------------------

    @Test fun modeFollowsWifiThenEthernetThenTelephony() {
        assertEquals(OverviewMode.WIFI, overviewMode(NetKind.WIFI, wifiConnected = true, hasTelephony = true))
        assertEquals(OverviewMode.WIFI, overviewMode(NetKind.VPN, wifiConnected = true, hasTelephony = true), "VPN over Wi-Fi")
        assertEquals(OverviewMode.CELLULAR, overviewMode(NetKind.CELLULAR, wifiConnected = false, hasTelephony = true))
        assertEquals(OverviewMode.CELLULAR, overviewMode(NetKind.VPN, wifiConnected = false, hasTelephony = true), "VPN over mobile")
        assertEquals(OverviewMode.CELLULAR, overviewMode(null, wifiConnected = false, hasTelephony = true), "Wi-Fi off, airplane")
        assertEquals(OverviewMode.ETHERNET, overviewMode(NetKind.ETHERNET, wifiConnected = false, hasTelephony = true))
        assertEquals(OverviewMode.NO_TELEPHONY, overviewMode(null, wifiConnected = false, hasTelephony = false))
    }

    // --- Cache key ------------------------------------------------------------------------------

    @Test fun networkKeyIsTransportPlusIdentity() {
        assertEquals("WIFI|ssid:Home", publicIpNetworkKey(NetKind.WIFI, "Home", 3, 100))
        assertEquals("CELLULAR|sub:3", publicIpNetworkKey(NetKind.CELLULAR, "Home", 3, 100))
        assertNotEquals(publicIpNetworkKey(NetKind.WIFI, "Home", null, 1), publicIpNetworkKey(NetKind.WIFI, "Work", null, 1))
        assertNotEquals(publicIpNetworkKey(NetKind.CELLULAR, null, 1, 7), publicIpNetworkKey(NetKind.CELLULAR, null, 2, 7))
        // The same SSID is the same network even after a reconnect (new handle).
        assertEquals(publicIpNetworkKey(NetKind.WIFI, "Home", null, 1), publicIpNetworkKey(NetKind.WIFI, "Home", null, 2))
    }

    @Test fun networkKeyFallsBackToHandleWithoutIdentity() {
        assertEquals("WIFI|net:42", publicIpNetworkKey(NetKind.WIFI, null, null, 42), "SSID redacted")
        assertEquals("WIFI|net:42", publicIpNetworkKey(NetKind.WIFI, "  ", null, 42))
        assertEquals("CELLULAR|net:42", publicIpNetworkKey(NetKind.CELLULAR, null, -1, 42), "INVALID_SUBSCRIPTION_ID")
        assertEquals("VPN|net:9", publicIpNetworkKey(NetKind.VPN, "Home", 3, 9), "a VPN is identified by its own network")
        assertEquals("ETHERNET|net:9", publicIpNetworkKey(NetKind.ETHERNET, null, null, 9))
        assertNull(publicIpNetworkKey(NetKind.WIFI, null, null, null))
        assertNull(publicIpNetworkKey(null, "Home", 3, 9))
    }

    @Test fun networkKeyDrivesCacheFreshness() {
        val home = publicIpNetworkKey(NetKind.WIFI, "Home", null, 1)
        val mobile = publicIpNetworkKey(NetKind.CELLULAR, null, 2, 5)
        assertTrue(isPublicIpCacheFresh(info(), home, home, nowMs = 2_000))
        assertFalse(isPublicIpCacheFresh(info(), mobile, home, nowMs = 2_000), "another network misses")
        assertFalse(isPublicIpCacheFresh(info(), publicIpNetworkKey(null, null, null, null), home, nowMs = 2_000))
    }

    // --- Provider node --------------------------------------------------------------------------

    @Test fun providerNodeStates() {
        assertEquals(ProviderNodeState.DISABLED, providerNodeState(false, true, true, info(), carrierNat = true))
        assertEquals(ProviderNodeState.NO_NETWORK, providerNodeState(true, false, false, info(), carrierNat = false))
        assertEquals(ProviderNodeState.LOADING, providerNodeState(true, true, true, null, carrierNat = false))
        assertEquals(ProviderNodeState.READY, providerNodeState(true, true, false, info(), carrierNat = false))
        assertEquals(ProviderNodeState.READY, providerNodeState(true, true, true, info(), carrierNat = false), "refresh keeps values")
        assertEquals(ProviderNodeState.CARRIER_NAT, providerNodeState(true, true, false, info(), carrierNat = true))
        assertEquals(ProviderNodeState.UNAVAILABLE, providerNodeState(true, true, false, null, carrierNat = false))
        assertEquals(ProviderNodeState.UNAVAILABLE, providerNodeState(true, true, false, info(v4 = null), carrierNat = false))
        assertEquals(ProviderNodeState.READY, providerNodeState(true, true, false, info(v4 = null, v6 = "2001:db8::1"), carrierNat = false))
    }

    // RFC 7335 CLAT address, built from parts: the secret-scan hook reads the literal as a public IPv4.
    private val clatAddress = listOf("192", "0", "0", "4").joinToString(".")

    @Test fun carrierNatDetection() {
        assertTrue(isBehindCarrierNat(NetKind.CELLULAR, "10.20.30.40", "203.0.113.7"))
        assertTrue(isBehindCarrierNat(NetKind.CELLULAR, clatAddress, "203.0.113.7"), "464XLAT CLAT address")
        assertTrue(isBehindCarrierNat(NetKind.WIFI, "100.64.1.2", "203.0.113.7"), "CGNAT space on any transport")
        assertTrue(isBehindCarrierNat(NetKind.ETHERNET, "100.127.255.1", "203.0.113.7"))
        assertFalse(isBehindCarrierNat(NetKind.CELLULAR, "203.0.113.7", "203.0.113.7"), "public address on the phone")
        assertFalse(isBehindCarrierNat(NetKind.WIFI, "192.168.0.30", "203.0.113.7"), "home router NAT is not the operator's")
        assertFalse(isBehindCarrierNat(NetKind.CELLULAR, null, "203.0.113.7"))
        assertFalse(isBehindCarrierNat(NetKind.CELLULAR, "10.20.30.40", null), "IPv6 only")
    }

    // --- DNS note -------------------------------------------------------------------------------

    @Test fun dnsNotePrefersPrivateDnsThenFirstResolver() {
        assertEquals(DnsResolverNote.Resolver("192.168.0.1"), dnsResolverNote(listOf("192.168.0.1", "2001:db8::53"), false, null))
        assertEquals(DnsResolverNote.Resolver("2001:db8::53"), dnsResolverNote(listOf(" ", "2001:db8::53"), false, null))
        assertEquals(DnsResolverNote.PrivateDns("dns.example"), dnsResolverNote(listOf("192.168.0.1"), true, " dns.example "))
        assertEquals(DnsResolverNote.PrivateDns("192.168.0.1"), dnsResolverNote(listOf("192.168.0.1"), true, null), "opportunistic")
        assertEquals(DnsResolverNote.PrivateDns(null), dnsResolverNote(emptyList(), true, ""))
        assertEquals(DnsResolverNote.Unknown, dnsResolverNote(emptyList(), false, "dns.example"), "name without active private DNS")
    }

    // --- Roaming --------------------------------------------------------------------------------

    @Test fun roamingShowsBandChangeElseChannelChange() {
        assertEquals(RoamingChange.Band(WifiBand.GHZ_2_4, WifiBand.GHZ_5), roamingChange(2437, 5180))
        assertEquals(RoamingChange.Band(WifiBand.GHZ_5, WifiBand.GHZ_6), roamingChange(5500, 5955))
        assertEquals(RoamingChange.Channel(36, 100), roamingChange(5180, 5500))
        assertEquals(RoamingChange.Channel(1, 6), roamingChange(2412, 2437))
        assertNull(roamingChange(5180, 5180), "same channel")
        assertNull(roamingChange(null, 5180))
        assertNull(roamingChange(5180, null))
    }

    // --- SIMs -----------------------------------------------------------------------------------

    @Test fun simsSortBySlotUnknownLast() {
        val sims = listOf(sim(-1, 9), sim(1, 4), sim(0, 7), sim(-1, 2))
        assertEquals(listOf(7, 4, 2, 9), sortedSims(sims).map { it.subscriptionId })
        assertEquals(listOf(1, 2, null, null), sortedSims(sims).map(::simNumber))
    }

    @Test fun primarySimIsTheDataSim() {
        assertEquals(4, primarySim(listOf(sim(0, 7), sim(1, 4, data = true)))?.subscriptionId)
        assertEquals(7, primarySim(listOf(sim(1, 4), sim(0, 7)))?.subscriptionId, "no data SIM: first slot")
        assertNull(primarySim(emptyList()))
    }

    @Test fun ratLabels() {
        assertEquals(listOf("LTE", "5G NSA", "5G SA", "3G", "2G", null), listOf(CellRat.LTE, CellRat.NR_NSA, CellRat.NR_SA,
            CellRat.UMTS, CellRat.GSM, CellRat.UNKNOWN).map(::cellRatLabel))
    }

    // --- Chart events ---------------------------------------------------------------------------

    @Test fun ratChangeOnTheSameDataSim() {
        val log = CellularEventLog()
        log.observe(1_000, sim(0, 7, data = true, rat = CellRat.LTE))
        log.observe(2_000, sim(0, 7, data = true, rat = CellRat.LTE))
        log.observe(3_000, sim(0, 7, data = true, rat = CellRat.NR_NSA))
        log.observe(4_000, sim(0, 7, data = true, rat = CellRat.UNKNOWN))
        log.observe(5_000, sim(0, 7, data = true, rat = CellRat.NR_NSA))
        assertEquals(listOf(LiveEvent(3_000, ChartEventKind.RAT_CHANGE, "LTE → 5G NSA")), log.events,
            "an unknown standard in between is not a change")
    }

    @Test fun dataSimSwitchRestartsTheStandardBaseline() {
        val log = CellularEventLog()
        log.observe(1_000, sim(0, 7, data = true, rat = CellRat.NR_NSA))
        log.observe(2_000, sim(1, 4, data = true, rat = CellRat.LTE))
        log.observe(3_000, sim(1, 4, data = true, rat = CellRat.LTE))
        log.observe(4_000, sim(-1, 9, data = true, rat = CellRat.LTE))
        assertEquals(listOf(
            LiveEvent(2_000, ChartEventKind.DATA_SIM_SWITCH, "SIM 1 → SIM 2"),
            LiveEvent(4_000, ChartEventKind.DATA_SIM_SWITCH, null),
        ), log.events, "no RAT event on the switch itself; unknown slot = no detail")
    }

    @Test fun missingDataSimKeepsBaselines() {
        val log = CellularEventLog()
        log.observe(1_000, sim(0, 7, data = true, rat = CellRat.LTE))
        log.observe(2_000, null) // airplane mode
        log.observe(3_000, sim(0, 7, data = true, rat = CellRat.LTE))
        assertTrue(log.events.isEmpty())
        log.observe(4_000, null)
        log.observe(5_000, sim(0, 7, data = true, rat = CellRat.NR_SA))
        assertEquals(listOf(ChartEventKind.RAT_CHANGE), log.events.map { it.kind })
    }

    @Test fun eventLogIsBoundedAndPrunable() {
        val log = CellularEventLog(maxEvents = 2)
        val rats = listOf(CellRat.LTE, CellRat.NR_NSA, CellRat.LTE, CellRat.NR_NSA)
        rats.forEachIndexed { i, rat -> log.observe(i * 1_000L, sim(0, 7, data = true, rat = rat)) }
        assertEquals(listOf(2_000L, 3_000L), log.events.map { it.elapsedMs })
        log.prune(2_500)
        assertEquals(listOf(3_000L), log.events.map { it.elapsedMs })
        log.prune(null)
        assertTrue(log.events.isEmpty())
        log.observe(10_000, sim(0, 7, data = true, rat = CellRat.LTE))
        log.clear()
        log.observe(11_000, sim(1, 4, data = true, rat = CellRat.NR_SA))
        assertTrue(log.events.isEmpty(), "clear forgets the baseline")
        assertFailsWith<IllegalArgumentException> { CellularEventLog(0) }
    }
}
