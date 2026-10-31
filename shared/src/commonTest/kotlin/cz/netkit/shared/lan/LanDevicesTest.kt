package cz.netkit.shared.lan

import kotlin.test.*

class LanDevicesTest {
    private val gateway = "192.168.1.1"
    private val self = "192.168.1.50"
    private fun seen(address: String, method: LanAliveMethod = LanAliveMethod.ICMP, rtt: Double? = 1.0) = LanDeviceObservation(address, method, rtt)

    @Test fun upsertKeepsFirstSeenNamesAndPortsAndSorts() {
        var devices = upsertLanDevice(emptyList(), seen("192.168.1.20"), 1_000, gateway, self)
        devices = updateLanDeviceNames(devices, "192.168.1.20", listOfNotNull(lanDeviceName(LanNameSource.PTR, "nas.lan.")))
        devices = withOpenPorts(devices, "192.168.1.20", listOf(OpenPort(445), OpenPort(22), OpenPort(22)), 1_500)
        devices = upsertLanDevice(devices, seen("192.168.1.20", LanAliveMethod.TCP, null), 5_000, gateway, self)
        val nas = devices.single()
        assertEquals(1_000, nas.firstSeenElapsedMs)
        assertEquals(5_000, nas.lastSeenElapsedMs)
        assertEquals(1.0, nas.lastRttMs, "missing RTT keeps the previous value")
        assertEquals(LanAliveMethod.TCP, nas.method)
        assertEquals(listOf(LanDeviceName(LanNameSource.PTR, "nas.lan")), nas.names)
        assertEquals(listOf(22, 445), nas.openPorts.map { it.port })
        assertEquals("SSH", nas.openPorts.first().service)
        assertEquals(1_500, nas.portsScannedElapsedMs)

        devices = upsertLanDevice(devices, seen("192.168.1.100"), 6_000, gateway, self)
        devices = upsertLanDevice(devices, seen("192.168.1.9"), 6_000, gateway, self)
        devices = upsertLanDevice(devices, seen(gateway), 6_000, gateway, self)
        devices = upsertLanDevice(devices, seen(self, LanAliveMethod.SELF, null), 6_000, gateway, self)
        assertEquals(listOf(gateway, "192.168.1.9", "192.168.1.20", self, "192.168.1.100"), devices.map { it.address })
        assertTrue(devices.first().isGateway)
        assertTrue(devices.single { it.address == self }.isSelf)
        assertEquals(devices, sortLanDevices(devices.reversed()))
    }

    @Test fun invalidAddressAndCapAreIgnored() {
        assertTrue(upsertLanDevice(emptyList(), seen("nas.local"), 1, gateway, self).isEmpty())
        val full = List(LAN_DEVICE_MAX) { LanDevice(ipv4Text(0x0A000000L + it), firstSeenElapsedMs = 0, lastSeenElapsedMs = 0, method = LanAliveMethod.ICMP) }
        assertEquals(LAN_DEVICE_MAX, upsertLanDevice(full, seen("192.168.1.2"), 1, gateway, self).size)
        // An existing device is still refreshed at the cap.
        assertEquals(9, upsertLanDevice(full, seen("10.0.0.5"), 9, gateway, self).single { it.address == "10.0.0.5" }.lastSeenElapsedMs)
    }

    @Test fun completedScanMarksMissingStaleAndExpiresOld() {
        var devices = upsertLanDevice(emptyList(), seen("192.168.1.2"), 1_000, gateway, self)
        devices = upsertLanDevice(devices, seen("192.168.1.3"), 1_000, gateway, self)
        devices = upsertLanDevice(devices, seen(self, LanAliveMethod.SELF), 1_000, gateway, self)
        // Second scan starts at 10 000 and sees only .2.
        devices = upsertLanDevice(devices, seen("192.168.1.2"), 10_500, gateway, self)
        devices = finishLanDiscovery(devices, scanStartedElapsedMs = 10_000, nowElapsedMs = 11_000)
        assertFalse(devices.single { it.address == "192.168.1.2" }.stale)
        assertTrue(devices.single { it.address == "192.168.1.3" }.stale)
        assertFalse(devices.single { it.address == self }.stale, "the phone itself never becomes stale")
        // Re-seen stale device is fresh again.
        assertFalse(upsertLanDevice(devices, seen("192.168.1.3"), 12_000, gateway, self).single { it.address == "192.168.1.3" }.stale)
        // After retention the stale device is removed.
        val later = finishLanDiscovery(devices, 1_000_000, 1_000 + LAN_DEVICE_STALE_RETENTION_MS + 1)
        assertNull(later.firstOrNull { it.address == "192.168.1.3" })
        assertNotNull(later.firstOrNull { it.address == self })
    }

    @Test fun displayNamePriorityNetBiosMdnsPtrIp() {
        val base = LanDevice("192.168.1.7", firstSeenElapsedMs = 0, lastSeenElapsedMs = 0, method = LanAliveMethod.ICMP)
        assertEquals(LanDisplayName("192.168.1.7", null), base.displayName)
        val ptr = base.copy(names = mergeLanDeviceNames(base.names, listOfNotNull(lanDeviceName(LanNameSource.PTR, "host.lan"))))
        assertEquals(LanNameSource.PTR, ptr.displayName.source)
        val mdns = ptr.copy(names = mergeLanDeviceNames(ptr.names, listOfNotNull(lanDeviceName(LanNameSource.MDNS, "Living Room TV"))))
        assertEquals(LanDisplayName("Living Room TV", LanNameSource.MDNS), mdns.displayName)
        val netbios = mdns.copy(names = mergeLanDeviceNames(mdns.names, listOfNotNull(lanDeviceName(LanNameSource.NETBIOS, "DESKTOP-1"))))
        assertEquals(LanDisplayName("DESKTOP-1", LanNameSource.NETBIOS), netbios.displayName)
        assertEquals(listOf(LanNameSource.NETBIOS, LanNameSource.MDNS, LanNameSource.PTR), netbios.names.map { it.source })
        // A newer value replaces an older one of the same source; an empty update keeps it.
        val renamed = mergeLanDeviceNames(netbios.names, listOfNotNull(lanDeviceName(LanNameSource.PTR, "new.lan")))
        assertEquals("new.lan", renamed.single { it.source == LanNameSource.PTR }.value)
        assertEquals(netbios.names, mergeLanDeviceNames(netbios.names, emptyList()))
    }

    @Test fun namesAreSanitized() {
        assertNull(lanDeviceName(LanNameSource.PTR, "bad name.lan"), "PTR must be an LDH DNS name")
        assertNull(lanDeviceName(LanNameSource.PTR, ""))
        assertNull(lanDeviceName(LanNameSource.MDNS, "  \u0000 "))
        assertNull(lanDeviceName(LanNameSource.NETBIOS, null))
        assertEquals("TV x", lanDeviceName(LanNameSource.MDNS, "TV\u0007x")?.value)
        assertEquals("a b", lanDeviceName(LanNameSource.MDNS, "a\u202Eb")?.value, "bidi override (Cf) removed")
        assertEquals(LAN_DEVICE_NAME_MAX, lanDeviceName(LanNameSource.MDNS, "x".repeat(500))?.value?.length)
        val group = updateLanDeviceNames(listOf(LanDevice("10.0.0.2", firstSeenElapsedMs = 0, lastSeenElapsedMs = 0, method = LanAliveMethod.ICMP)),
            "10.0.0.2", emptyList(), "WORK\u0001GROUP")
        assertEquals("WORK GROUP", group.single().netBiosGroup)
    }

    @Test fun liveReplyRefreshesDevice() {
        val stale = LanDevice("10.0.0.2", firstSeenElapsedMs = 0, lastSeenElapsedMs = 10, method = LanAliveMethod.ICMP, stale = true)
        val updated = recordLanDeviceReply(listOf(stale), "10.0.0.2", 3.5, 20).single()
        assertEquals(3.5, updated.lastRttMs); assertEquals(20, updated.lastSeenElapsedMs); assertFalse(updated.stale)
        assertEquals(listOf(stale), recordLanDeviceReply(listOf(stale), "10.0.0.3", 1.0, 30))
        assertFailsWith<IllegalArgumentException> { OpenPort(0) }
    }
}

class LanDeviceConsentTest {
    private val subnet = Ipv4Subnet.parse("192.168.1.0/24")!!
    private val identity = LanNetworkIdentity("Doma", "192.168.1.1", subnet)
    private val fakeHash: (ByteArray) -> ByteArray = { input -> ByteArray(32) { i -> input.getOrElse(i) { 0 } } }

    @Test fun identityBytesAreUnambiguous() {
        val variants = listOf(
            identity, identity.copy(ssid = null), identity.copy(ssid = ""), identity.copy(gateway = null),
            identity.copy(ssid = "Doma;gw:11:192.168.1.1"), identity.copy(subnet = Ipv4Subnet.parse("192.168.1.0/25")!!),
            identity.copy(ssid = "Dom", gateway = "a192.168.1.1"),
        )
        val encoded = variants.map { lanNetworkIdentityBytes(it).decodeToString() }
        assertEquals(encoded.size, encoded.toSet().size, encoded.joinToString("\n"))
        assertEquals(lanNetworkIdentityBytes(identity.copy(gateway = "not-ip")).decodeToString(),
            lanNetworkIdentityBytes(identity.copy(gateway = null)).decodeToString(), "non-numeric gateway is ignored")
        assertContentEquals(lanNetworkIdentityBytes(identity), lanNetworkIdentityBytes(identity.copy()))
    }

    @Test fun consentKeyIsHexOfKeyedHashAndNeverRawSsid() {
        var hashed: ByteArray? = null
        val key = lanNetworkConsentKey(identity) { input -> hashed = input; ByteArray(32) { 0xAB.toByte() } }
        assertEquals("ab".repeat(32), key)
        assertContentEquals(lanNetworkIdentityBytes(identity), hashed)
        assertFalse(lanNetworkConsentKey(identity, fakeHash).contains("Doma"))
        assertFailsWith<IllegalArgumentException> { lanNetworkConsentKey(identity) { ByteArray(8) } }
    }

    @Test fun ssidNormalization() {
        assertEquals("Doma", normalizeWifiSsid("\"Doma\""))
        assertEquals("3a4b", normalizeWifiSsid("3a4b"), "hex SSID stays as Android reports it")
        assertNull(normalizeWifiSsid("<unknown ssid>"))
        assertNull(normalizeWifiSsid("\"\""))
        assertNull(normalizeWifiSsid(null))
        assertNull(normalizeWifiSsid("  "))
    }

    @Test fun allowedIsRememberedOnlyWithSsid() {
        assertTrue(lanConsentIsPersistable(DiscoveryConsent.ALLOWED, identity))
        assertFalse(lanConsentIsPersistable(DiscoveryConsent.ALLOWED, identity.copy(ssid = null)))
        assertTrue(lanConsentIsPersistable(DiscoveryConsent.DENIED, identity.copy(ssid = null)))
        assertFalse(lanConsentIsPersistable(DiscoveryConsent.UNKNOWN, identity))
    }

    @Test fun autoDiscoveryRateLimit() {
        val now = 10_000_000L
        assertFalse(shouldAutoDiscover(DiscoveryConsent.UNKNOWN, null, now))
        assertFalse(shouldAutoDiscover(DiscoveryConsent.DENIED, null, now))
        assertTrue(shouldAutoDiscover(DiscoveryConsent.ALLOWED, null, now))
        assertFalse(shouldAutoDiscover(DiscoveryConsent.ALLOWED, now - AUTO_DISCOVERY_MIN_INTERVAL_MS + 1, now))
        assertTrue(shouldAutoDiscover(DiscoveryConsent.ALLOWED, now - AUTO_DISCOVERY_MIN_INTERVAL_MS, now))
        assertTrue(shouldAutoDiscover(DiscoveryConsent.ALLOWED, now + 5, now), "clock reset after reboot")
        assertFalse(shouldAutoDiscover(DiscoveryConsent.ALLOWED, now, now))
    }

    @Test fun devicesRangeStaysInsideOwnSubnet() {
        assertEquals("192.168.1.0/24", lanDevicesRange("192.168.1.50", 24).toString())
        assertEquals("10.0.5.0/24", lanDevicesRange("10.0.5.9", 16).toString(), "wide Wi-Fi narrowed to /24")
        assertEquals("192.168.1.48/28", lanDevicesRange("192.168.1.50", 28).toString())
        assertEquals(14L, lanDevicesRange("192.168.1.50", 28)!!.hostCount)
        assertNull(lanDevicesRange("198.51.100.8", 24), "public address needs explicit confirmation in the LAN tool")
        assertNull(lanDevicesRange("192.168.1.50", 33))
        assertNull(lanDevicesRange("not-ip", 24))
        assertTrue(lanDevicesRange("172.16.4.4", 12)!!.hostCount <= 254)
    }
}
