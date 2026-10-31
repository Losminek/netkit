package cz.netkit.shared.net

import cz.netkit.shared.lan.ipv4Number
import cz.netkit.shared.lan.ipv4Text
import kotlin.test.*

private fun neighbour(address: String, delta: Long): String = ipv4Text(ipv4Number(address)!! + delta)

class LocalTargetTest {
    @Test fun privateIpv4BlocksAndTheirEdges() {
        listOf("10.0.0.0", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.30", "192.168.255.255",
            "100.64.0.1", "100.127.255.254", "169.254.1.1", "127.0.0.1", "127.255.255.255").forEach {
            assertTrue(isPrivateOrLocalTarget(it), it)
        }
        // Neighbours just outside each block are computed, so the source holds no public literals.
        val outside = listOf("10.0.0.0" to "10.255.255.255", "172.16.0.0" to "172.31.255.255", "192.168.0.0" to "192.168.255.255",
            "100.64.0.0" to "100.127.255.255", "169.254.0.0" to "169.254.255.255")
            .flatMap { (first, last) -> listOf(neighbour(first, -1), neighbour(last, 1)) }
        (outside + listOf("192.0.2.1", "198.51.100.8", "192.0.2.10", "198.51.100.7", "203.0.113.1")).forEach {
            assertFalse(isPrivateOrLocalTarget(it), it)
        }
    }

    @Test fun ipv6LoopbackLinkLocalUlaAndMapped() {
        listOf("::1", "[::1]", "fe80::1", "FE80::1%wlan0", "[fe80::aa:bb%25]", "febf:ffff::1", "fc00::1", "fd12:3456:789a::1",
            "::ffff:192.168.0.1", "::ffff:10.1.2.3", "0:0:0:0:0:ffff:100.64.0.1").forEach {
            assertTrue(isPrivateOrLocalTarget(it), it)
        }
        listOf("2001:db8::1", "[2001:db8::10]", "fec0::1", "fe7f::1", "fe00::1", "::", "::ffff:198.51.100.8", "2001:db8:0:0:0:0:0:1",
            "ff02::1").forEach {
            assertFalse(isPrivateOrLocalTarget(it), it)
        }
    }

    @Test fun hostnamesAndInvalidLiteralsAreNotLocal() {
        listOf("", "nas.lan", "localhost", "router.local", "192.168.0", "192.168.0.300", "010.0.0.1", "fe80:::1",
            "1:2:3:4:5:6:7:8:9", "fc00::1::2", "[fc00::1", "fc00::1%", "12345::1", "192.0.2.4::", "::192.0.2").forEach {
            assertFalse(isPrivateOrLocalTarget(it), it)
        }
    }

    @Test fun loopbackIsRecognisedInEveryForm() {
        assertTrue(isLoopbackTarget("127.0.0.1"))
        assertTrue(isLoopbackTarget("127.1.2.3"))
        assertTrue(isLoopbackTarget("::1"))
        assertTrue(isLoopbackTarget("::ffff:127.0.0.1"))
        assertFalse(isLoopbackTarget("192.168.0.1"))
        assertFalse(isLoopbackTarget("::2"))
    }

    @Test fun ipv6ParserExpandsCompression() {
        assertContentEquals(ByteArray(16) { if (it == 15) 1 else 0 }, ipv6Bytes("::1"))
        assertContentEquals(ipv6Bytes("2001:db8:0:0:0:0:0:1"), ipv6Bytes("2001:db8::1"))
        assertContentEquals(ipv6Bytes("1:2:3:4:5:6:7:0"), ipv6Bytes("1:2:3:4:5:6:7::"))
        assertNull(ipv6Bytes("1:2:3:4:5:6:7:8::"))
        assertNull(ipv6Bytes("1:2:3:4:5:6:7"))
    }
}
