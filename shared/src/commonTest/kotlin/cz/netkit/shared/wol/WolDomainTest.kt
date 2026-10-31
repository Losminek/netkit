package cz.netkit.shared.wol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WolDomainTest {
    @Test fun macAcceptsFourFormats() {
        listOf("AA:BB:CC:DD:EE:FF", "aa-bb-cc-dd-ee-ff", "aabb.ccdd.eeff", "aabbccddeeff").forEach {
            assertEquals("AA:BB:CC:DD:EE:FF", assertNotNull(parseMac(it)).toString())
        }
    }
    @Test fun macRejectsMalformedAndGroupAddresses() {
        listOf("", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:GG", "AA:BB-CC:DD:EE:FF", "AA::BB:CC:DD:EE:FF", "FF:FF:FF:FF:FF:FF", "01:00:5e:00:00:01").forEach { assertNull(parseMac(it), it) }
    }
    @Test fun magicPacketHasAll102ExpectedBytes() {
        val packet = magicPacket(assertNotNull(parseMac("AA:BB:CC:DD:EE:FF")))
        assertEquals(102, packet.size)
        packet.take(6).forEach { assertEquals(255, it.toInt() and 255) }
        val expected = listOf(170, 187, 204, 221, 238, 255)
        for (i in 6 until 102) assertEquals(expected[(i - 6) % 6], packet[i].toInt() and 255)
    }
    @Test fun broadcastMatchesSubnetBoundaries() {
        listOf(Triple("192.168.1.10", 24, "192.168.1.255"), Triple("10.1.2.3", 16, "10.1.255.255"), Triple("172.16.5.130", 25, "172.16.5.255"), Triple("192.168.0.1", 30, "192.168.0.3"), Triple("192.0.2.1", 0, "255.255.255.255")).forEach { (ip, prefix, expected) -> assertEquals(expected, directedBroadcast(assertNotNull(parseIpv4(ip)), prefix).toString()) }
        listOf(-1, 31, 32, 33).forEach { assertNull(directedBroadcast(assertNotNull(parseIpv4("192.168.0.1")), it)) }
    }
    @Test fun ipv4AndHostValidationRejectsAmbiguity() {
        listOf("256.1.1.1", "1.2.3", "192.00.2.1", "192.0.2.1.5", "-192.0.2.1", "1.2.3.a", "1..2.3", " 192.0.2.1").forEach { assertNull(parseIpv4(it), it) }
        listOf("example.org", "nas", "nas-1.home", "10.0.2.2").forEach { assertTrue(isValidHost(it), it) }
        listOf("", "https://nas", "nas:9", "nas home", "::1", "-nas", "nas-", "a..b", "256.1.1.1", "192.00.2.1", "český.cz", "a".repeat(64)).forEach { assertFalse(isValidHost(it), it) }
    }
    @Test fun draftValidatesNumericRangesAndOptionalFields() {
        val valid = WolDeviceDraft(name = "NAS", mac = "AABBCCDDEEFF")
        listOf("1", "9", "65535").forEach { assertTrue(validateDevice(valid.copy(port = it)).isValid) }
        listOf("", "0", "65536", "999999999999999999999", "-1", "+9", "9.0", " 9").forEach { assertTrue(DeviceField.PORT in validateDevice(valid.copy(port = it)).errors, it) }
        assertTrue(DeviceField.CUSTOM_TARGET in validateDevice(valid.copy(targetMode = TargetMode.CUSTOM)).errors)
        assertTrue(DeviceField.PROBE_PORT in validateDevice(valid.copy(probePort = "80")).errors)
        assertTrue(validateDevice(valid.copy(probeHost = "nas.home", probePort = "80")).isValid)
    }
    @Test fun newEditsHaveFieldSpecificLimitsAndLegacyValuesRemainReadable() {
        val draft = WolDeviceDraft(name = "n".repeat(101), mac = "AABBCCDDEEFF", note = "x".repeat(1001))
        assertEquals(setOf(DeviceField.NAME, DeviceField.NOTE), validateDevice(draft).errors)
        val legacy = WolDevice("old", draft.name, "AA:BB:CC:DD:EE:FF", TargetMode.AUTO_BROADCAST,
            note = draft.note, createdAt = 1, updatedAt = 2)
        assertTrue(validateStoredDevices(listOf(legacy)))
        assertTrue(validateDevice(legacy.toDraft().copy(port = "7"), legacy).isValid)
        assertTrue(DeviceField.NAME in validateDevice(legacy.toDraft().copy(name = "y".repeat(101)), legacy).errors)
        assertTrue(validateDevice(draft.copy(name = "n".repeat(100), note = "x".repeat(1000))).isValid)
        assertFalse(isValidHost("a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62)))
    }

}
