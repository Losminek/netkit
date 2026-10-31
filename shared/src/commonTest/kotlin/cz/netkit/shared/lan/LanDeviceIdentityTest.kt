package cz.netkit.shared.lan

import kotlin.test.*

class LanDeviceIdentityTest {
    private val range = Ipv4Subnet.parse("192.168.1.0/24")!!
    private fun devices() = upsertLanDevice(emptyList(), LanDeviceObservation("192.168.1.20", LanAliveMethod.ICMP, 1.0), 1_000, "192.168.1.1", "192.168.1.50")

    @Test fun servicesAreUnitedSortedValidatedAndCapped() {
        var list = updateLanDeviceServices(devices(), "192.168.1.20", listOf("_ipp._tcp", "_airplay._tcp", "bogus", "_x._sctp"))
        assertEquals(listOf("_airplay._tcp", "_ipp._tcp"), list.single().mdnsServices)
        list = updateLanDeviceServices(list, "192.168.1.20", listOf("_ipp._tcp", "_ssh._tcp"))
        assertEquals(listOf("_airplay._tcp", "_ipp._tcp", "_ssh._tcp"), list.single().mdnsServices)
        list = updateLanDeviceServices(list, "192.168.1.20", (0 until 40).map { "_s$it._tcp" })
        assertEquals(MDNS_MAX_SERVICES, list.single().mdnsServices.size)
        assertSame(list, updateLanDeviceServices(list, "192.168.1.20", listOf("bad")), "nothing valid: unchanged")
        assertEquals(list, updateLanDeviceServices(list, "192.168.1.99", listOf("_ipp._tcp")), "unknown address")
    }

    @Test fun mdnsNamesUseTheExistingNameMergeAndKeepNetBiosPriority() {
        var list = updateLanDeviceNames(devices(), "192.168.1.20", listOfNotNull(lanDeviceName(LanNameSource.MDNS, "nas.local")))
        assertEquals(LanDisplayName("nas.local", LanNameSource.MDNS), list.single().displayName)
        list = updateLanDeviceNames(list, "192.168.1.20", listOfNotNull(lanDeviceName(LanNameSource.NETBIOS, "NAS-DEMO")))
        assertEquals(LanNameSource.NETBIOS, list.single().displayName.source)
        assertEquals(listOf(LanNameSource.NETBIOS, LanNameSource.MDNS), list.single().names.map { it.source })
    }

    @Test fun upnpInfoReplacesButABareReplyKeepsTheDescription() {
        val full = LanUpnpInfo(server = "A", friendlyName = "TV", modelName = "X")
        var list = updateLanDeviceUpnp(devices(), "192.168.1.20", full)
        assertEquals(full, list.single().upnp)
        list = updateLanDeviceUpnp(list, "192.168.1.20", LanUpnpInfo(server = "B", usn = "uuid:1"))
        assertEquals(LanUpnpInfo(server = "B", usn = "uuid:1", friendlyName = "TV", modelName = "X"), list.single().upnp)
        list = updateLanDeviceUpnp(list, "192.168.1.20", LanUpnpInfo(friendlyName = "New"))
        assertEquals(LanUpnpInfo(friendlyName = "New"), list.single().upnp)
    }

    @Test fun identificationSurvivesRediscoveryAndStaleMarking() {
        var list = updateLanDeviceServices(devices(), "192.168.1.20", listOf("_ipp._tcp"))
        list = updateLanDeviceUpnp(list, "192.168.1.20", LanUpnpInfo(friendlyName = "Printer"))
        list = upsertLanDevice(list, LanDeviceObservation("192.168.1.20", LanAliveMethod.TCP), 9_000, "192.168.1.1", "192.168.1.50")
        list = finishLanDiscovery(list, scanStartedElapsedMs = 10_000, nowElapsedMs = 11_000)
        assertTrue(list.single().stale)
        assertEquals(listOf("_ipp._tcp"), list.single().mdnsServices)
        assertEquals("Printer", list.single().upnp?.friendlyName)
    }

    @Test fun lanUpnpInfoCombinesReplyAndDescription() {
        val response = SsdpResponse("http://192.168.1.20:8080/d.xml", "MiniUPnPd", "upnp:rootdevice", "uuid:1")
        val target = upnpDescriptionTarget(response.location!!, "192.168.1.20", range)
        val description = UpnpDescription(friendlyName = "Router", manufacturer = "MikroTik", presentationUrl = "/")
        val info = lanUpnpInfo(response, description, target, range)
        assertEquals("MiniUPnPd", info.server)
        assertEquals("uuid:1", info.usn)
        assertEquals("Router", info.friendlyName)
        assertEquals("MikroTik", info.manufacturer)
        assertEquals("/", info.presentationUrl)
        assertEquals("http://192.168.1.20:8080/", info.openUrl)
        assertTrue(info.hasDescription)
        val bare = lanUpnpInfo(response, null, null, range)
        assertFalse(bare.hasDescription)
        assertNull(bare.openUrl)
        assertNull(lanUpnpInfo(response, description.copy(presentationUrl = "http://evil.example/"), target, range).openUrl)
    }
}
