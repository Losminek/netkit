package cz.netkit.shared.lan

import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

class LanDomainTest {
    @Test fun subnetEdgesAndBounds() {
        assertEquals(listOf("192.168.1.1","192.168.1.2"),Ipv4Subnet.parse("192.168.1.3/30")!!.hosts())
        assertEquals(listOf("10.0.0.0","10.0.0.1"),Ipv4Subnet.parse("10.0.0.1/31")!!.hosts())
        assertEquals(listOf("10.0.0.3"),Ipv4Subnet.parse("10.0.0.3/32")!!.hosts())
        assertEquals(254,Ipv4Subnet.parse("192.168.1.99/24")!!.hosts().size)
        assertEquals(4294967294L,Ipv4Subnet.parse("255.255.255.255/0")!!.hostCount)
        assertFailsWith<IllegalArgumentException> { Ipv4Subnet.parse("10.0.0.0/16")!!.hosts() }
        listOf("1.2.3/24","1.2.3.256/24","a.b.c.d/24","10.2.3.4/33","10.2.3.4/-1").forEach { assertNull(Ipv4Subnet.parse(it)) }
        assertNull(ipv4Number("1.2.3.-1")); assertNull(ipv4Number("10.2.3.4.example"))
    }
    @Test fun confirmationIsNotBypassedByPrivateButForeignRange() {
        val own=Ipv4Subnet.parse("192.168.1.99/24")!!
        assertFalse(requiresScanConfirmation(Ipv4Subnet.parse("192.168.1.0/24")!!,own))
        assertTrue(requiresScanConfirmation(Ipv4Subnet.parse("192.168.2.0/24")!!,own))
        assertTrue(requiresScanConfirmation(Ipv4Subnet.parse("192.168.0.0/23")!!,own))
        assertTrue(requiresScanConfirmation(Ipv4Subnet.parse("192.0.2.8/32")!!,Ipv4Subnet.parse("192.0.2.0/24")))
        assertTrue(requiresScanConfirmation(Ipv4Subnet.parse("192.168.1.0/24")!!,null))
        assertTrue(isLocalAddress("100.64.0.1"))
        // Derive the first address after shared address space; no real public endpoint in fixtures.
        val carrierNat=Ipv4Subnet.parse("100.64.0.0/10")!!
        assertFalse(isLocalAddress(ipv4Text(carrierNat.network+carrierNat.size)))
    }
    @Test fun portsAreUniqueSortedAndBounded() {
        assertEquals(listOf(22,80,81,82,443),parsePorts("443,80-82,22,80"))
        assertNotNull(parsePorts("1-4096")); assertNull(parsePorts("1-4097"))
        listOf("0","65536","80-22","22,,80","22-","abc","1-65535").forEach { assertNull(parsePorts(it)) }
        assertEquals("SSH",portService(22)); assertEquals("",portService(65535))
    }
    @Test fun pingParserDoesNotInventReplies() {
        assertEquals(12.3,parsePingOutput("64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=12.3 ms",0).milliseconds)
        assertEquals(1.0,parsePingOutput("64 bytes from 192.168.1.1: time<1 ms",0).milliseconds)
        assertNull(parsePingOutput("ping: bad address",1).milliseconds)
        assertNull(parsePingOutput("time=4 ms",1).milliseconds)
        val trace=parsePingOutput("From 192.168.1.1 icmp_seq=1 Time to live exceeded",1)
        assertTrue(trace.ttlExceeded); assertEquals("192.168.1.1",trace.hop)
        assertNull(parsePingOutput("",0).milliseconds)
    }
    @Test fun iputilsTraceIgnoresLocalAddressInInterfaceHeader() {
        val output = """
            PING 203.0.113.10 (203.0.113.10) from 192.168.0.30 wlan0: 56(84) bytes of data.
            From 192.168.0.1 icmp_seq=1 Time to live exceeded

            --- 203.0.113.10 ping statistics ---
            1 packets transmitted, 0 received, +1 errors, 100% packet loss, time 0ms
        """.trimIndent()
        assertEquals(PingReply(null,"192.168.0.1",true),parsePingOutput(output,1))
    }
    @Test fun toyboxTraceParsesTtlExceededLine() {
        val output = """
            PING 203.0.113.10 (203.0.113.10): 56 data bytes
            From 192.168.0.1: icmp_seq=0 Time to live exceeded

            --- 203.0.113.10 ping statistics ---
            1 packets transmitted, 0 packets received, 100% packet loss
        """.trimIndent()
        assertEquals(PingReply(null,"192.168.0.1",true),parsePingOutput(output,1))
    }
    @Test fun traceParsesNumericAddressAfterRouterName() {
        val output = """
            PING 203.0.113.10 (203.0.113.10) from 192.168.0.30 wlan0: 56(84) bytes of data.
              From router.lan (192.168.0.1) icmp_seq=1 Time to live exceeded

            --- 203.0.113.10 ping statistics ---
            1 packets transmitted, 0 received, +1 errors, 100% packet loss, time 0ms
        """.trimIndent()
        assertEquals(PingReply(null,"192.168.0.1",true),parsePingOutput(output,1))
    }
    @Test fun traceTimeoutDoesNotUseInterfaceHeader() {
        val output = """
            PING 203.0.113.10 (203.0.113.10) from 192.168.0.30 wlan0: 56(84) bytes of data.

            --- 203.0.113.10 ping statistics ---
            1 packets transmitted, 0 received, 100% packet loss, time 0ms
        """.trimIndent()
        assertEquals(PingReply(null),parsePingOutput(output,1))
    }
    @Test fun traceDestinationReplyHasTimeButNoHop() {
        val output = """
            PING 203.0.113.10 (203.0.113.10) from 192.168.0.30 wlan0: 56(84) bytes of data.
            64 bytes from 203.0.113.10: icmp_seq=1 ttl=56 time=29.6 ms

            --- 203.0.113.10 ping statistics ---
            1 packets transmitted, 1 received, 0% packet loss, time 0ms
            rtt min/avg/max/mdev = 29.600/29.600/29.600/0.000 ms
        """.trimIndent()
        assertEquals(PingReply(29.6),parsePingOutput(output,0))
    }
    @Test fun traceRequiresCaseSensitiveFromAndTtlErrorOnSameLine() {
        listOf(
            "from 192.168.0.1 icmp_seq=1 Time to live exceeded",
            "prefix From 192.168.0.1 icmp_seq=1 Time to live exceeded",
            "From 192.168.0.1 icmp_seq=1 Destination Host Unreachable\nTime to live exceeded",
            "PING 203.0.113.10 from 192.168.0.30 wlan0\nTime to live exceeded",
        ).forEach { assertNull(parsePingOutput(it,1).hop) }
    }
    @Test fun hopEchoUsesMinimumOfThreeSuccessfulSamples() {
        assertEquals(12.0,pingStatistics(listOf(30.0,12.0,21.0)).minimum)
        assertEquals(21.0,pingStatistics(listOf(null,21.0,30.0)).minimum)
        assertNull(pingStatistics(listOf(null,null,null)).minimum)
    }
    @Test fun statisticsDefineLossAndConsecutiveJitter() {
        val stats=pingStatistics(listOf(10.0,20.0,null,40.0,50.0))
        assertEquals(5,stats.sent); assertEquals(4,stats.received); assertEquals(20.0,stats.lossPercent)
        assertEquals(10.0,stats.minimum); assertEquals(30.0,stats.average); assertEquals(50.0,stats.maximum); assertEquals(10.0,stats.jitter)
        assertNull(pingStatistics(listOf(null)).average); assertEquals(0.0,pingStatistics(emptyList()).lossPercent)
    }
    @Test fun exportsEscapeUntrustedNamesAndBanners() {
        val rows=listOf(LanResult("192.168.1.2","a,\"b\n","TCP",1.5,22,"SSH"))
        assertEquals(1,Json.parseToJsonElement(exportLan(rows,LanExport.JSON)).jsonArray.size)
        assertTrue(exportLan(rows,LanExport.CSV).contains("\"a,\"\"b\n\""))
        assertTrue(exportLan(rows,LanExport.TEXT).contains("192.168.1.2"))
        assertTrue(exportLan(listOf(LanResult("10.0.0.1",name="=HYPERLINK(1)")),LanExport.CSV).contains("'=HYPERLINK(1)"))
    }
}
