package cz.netkit.shared.lan

import kotlin.test.*

class MdnsIdentityTest {
    private val range = Ipv4Subnet.parse("10.0.0.0/8")!!

    private fun response(id: Int = 0, flags: Int = 0x8400, records: DnsWire.() -> Int): DnsMessage {
        val body = DnsWire()
        val count = body.records()
        val packet = DnsWire().header(id = id, flags = flags, an = count).build() + body.build()
        return assertNotNull(parseDnsMessage(packet))
    }

    private fun DnsWire.ptr(owner: String, target: String) { name(owner).rr(DNS_TYPE_PTR, DnsWire.nameBytes(target)).name(target) }
    private fun DnsWire.a(owner: String, vararg octets: Int) { name(owner).rr(DNS_TYPE_A, 4).raw(*octets) }

    @Test fun reverseNamesRoundTripStrictly() {
        assertEquals("10.1.168.192.in-addr.arpa", ipv4ReverseName("192.168.1.10")!!.text)
        assertEquals("192.168.1.10", ipv4FromReverseName(DnsName(listOf("10", "1", "168", "192", "IN-ADDR", "ARPA"))))
        assertNull(ipv4ReverseName("192.168.1"))
        assertNull(ipv4FromReverseName(DnsName(listOf("010", "1", "168", "192", "in-addr", "arpa"))), "leading zero")
        assertNull(ipv4FromReverseName(DnsName(listOf("1", "168", "192", "in-addr", "arpa"))), "three octets")
        assertNull(ipv4FromReverseName(DnsName(listOf("300", "1", "168", "192", "in-addr", "arpa"))))
        assertNull(ipv4FromReverseName(DnsName(listOf("10", "1", "168", "192", "ip6", "arpa"))))
    }

    @Test fun reverseQueriesAreChunkedDedupedAndBounded() {
        var id = 100
        val hosts = (1..70).map { "192.168.1.$it" } + "192.168.1.5" + "224.0.0.1" + "not-ip"
        val packets = mdnsReverseQueries(hosts) { id++ }
        assertEquals(listOf(32, 32, 6), packets.map { parseDnsMessage(it.second)!!.questions.size })
        assertEquals(listOf(100, 101, 102), packets.map { it.first })
        val questions = packets.flatMap { parseDnsMessage(it.second)!!.questions }
        assertEquals(70, questions.map { it.name.text }.distinct().size)
        assertTrue(questions.all { it.unicastResponse && it.type == DNS_TYPE_PTR })
        assertTrue(packets.all { it.second.size < 1200 }, "small datagrams")
        assertEquals(MDNS_MAX_HOSTS, mdnsReverseQueries((0 until 1000).map { "10.${it / 250}.${it % 250}.1" }) { 1 }
            .sumOf { parseDnsMessage(it.second)!!.questions.size })
        assertTrue(mdnsReverseQueries(emptyList()) { 1 }.isEmpty())
    }

    @Test fun servicesQueryIsDnsSdEnumeration() {
        val question = parseDnsMessage(mdnsServicesQuery(9))!!.questions.single()
        assertEquals("_services._dns-sd._udp.local", question.name.text)
        assertEquals(DNS_TYPE_PTR, question.type)
        assertTrue(question.unicastResponse)
    }

    @Test fun reversePtrNamesTheAddressAndWinsOverARecord() {
        val message = response(id = 5) {
            a("old-name.local", 10, 0, 1, 10)
            ptr("10.1.0.10.in-addr.arpa", "macbook.local")
            ptr("10.2.0.10.in-addr.arpa", "printer.local")
            3
        }
        val found = interpretMdnsResponse(message, "10.0.1.10", range, setOf(5))
        assertEquals(mapOf("10.0.1.10" to "macbook.local", "10.0.2.10" to "printer.local"), found.names.mapValues { it.value.name })
        assertEquals(MdnsHostName("macbook.local", fromSender = true, reverse = true), found.names["10.0.1.10"])
        assertEquals(MdnsHostName("printer.local", fromSender = false, reverse = true), found.names["10.0.2.10"])
        assertTrue(found.services.isEmpty())
    }

    @Test fun ignoresAnswersOutsideTheRangeAndNonLocalNames() {
        val message = response {
            ptr("10.1.168.192.in-addr.arpa", "remote.local")
            ptr("10.3.0.10.in-addr.arpa", "router.example.com")
            ptr("10.4.0.10.in-addr.arpa", "_ipp._tcp.local")
            a("far.local", 192, 168, 1, 5)
            a("local", 10, 0, 5, 10)
            5
        }
        val found = interpretMdnsResponse(message, "10.0.1.10", range, emptySet())
        assertTrue(found.names.isEmpty(), found.names.toString())
    }

    @Test fun rejectsSourceOutsideRangeWrongIdQueriesAndErrors() {
        val build: DnsWire.() -> Int = { ptr("10.1.0.10.in-addr.arpa", "nas.local"); 1 }
        assertTrue(interpretMdnsResponse(response(records = build), "192.168.1.10", range, emptySet()).names.isEmpty())
        assertTrue(interpretMdnsResponse(response(id = 77, records = build), "10.0.1.10", range, setOf(5)).names.isEmpty())
        assertEquals(1, interpretMdnsResponse(response(id = 5, records = build), "10.0.1.10", range, setOf(5)).names.size)
        assertEquals(1, interpretMdnsResponse(response(id = 0, records = build), "10.0.1.10", range, setOf(5)).names.size, "ID 0 accepted")
        assertTrue(interpretMdnsResponse(response(flags = 0x0000, records = build), "10.0.1.10", range, emptySet()).names.isEmpty(), "query")
        assertTrue(interpretMdnsResponse(response(flags = 0x8403, records = build), "10.0.1.10", range, emptySet()).names.isEmpty(), "NXDOMAIN")
        assertTrue(interpretMdnsResponse(response(flags = 0xA400, records = build), "10.0.1.10", range, emptySet()).names.isEmpty(), "opcode 4")
    }

    @Test fun serviceTypesAreAttributedToTheResponderOnly() {
        val message = response {
            ptr("_services._dns-sd._udp.local", "_ipp._tcp.local")
            ptr("_services._dns-sd._udp.local", "_AirPlay._TCP.local")
            ptr("_services._dns-sd._udp.local", "_bad label._tcp.local")
            ptr("_services._dns-sd._udp.local", "_x._sctp.local")
            ptr("_googlecast._tcp.local", "Living Room._googlecast._tcp.local")
            ptr("_services._dns-sd._udp.local", "plain.local")
            6
        }
        val found = interpretMdnsResponse(message, "10.0.1.20", range, emptySet())
        assertEquals(mapOf("10.0.1.20" to setOf("_airplay._tcp", "_googlecast._tcp", "_ipp._tcp")), found.services)
    }

    @Test fun serviceTypesAreCapped() {
        val message = response {
            repeat(40) { ptr("_services._dns-sd._udp.local", "_s$it._tcp.local") }
            40
        }
        assertEquals(MDNS_MAX_SERVICES, interpretMdnsResponse(message, "10.0.1.20", range, emptySet()).services.values.single().size)
    }

    @Test fun findingsMergeKeepsFirstOfEqualRankAndUnitesServices() {
        fun n(name: String) = MdnsHostName(name, fromSender = true, reverse = true)
        val first = MdnsFindings(mapOf("10.0.1.2" to n("a.local")), mapOf("10.0.1.2" to setOf("_ssh._tcp")))
        val second = MdnsFindings(mapOf("10.0.1.2" to n("b.local"), "10.0.1.3" to n("c.local")),
            mapOf("10.0.1.2" to setOf("_http._tcp"), "10.0.1.3" to setOf("_ipp._tcp")))
        val merged = first.plus(second)
        assertEquals(mapOf("10.0.1.2" to "a.local", "10.0.1.3" to "c.local"), merged.names.mapValues { it.value.name })
        assertEquals(setOf("_http._tcp", "_ssh._tcp"), merged.services["10.0.1.2"])
        assertEquals(setOf("_ipp._tcp"), merged.services["10.0.1.3"])
    }

    @Test fun reversePtrWinsOverAnEarlierARecordAcrossDatagrams() {
        val earlier = interpretMdnsResponse(response { a("forward.local", 10, 0, 1, 10); 1 }, "10.0.1.10", range, emptySet())
        val later = interpretMdnsResponse(response { ptr("10.1.0.10.in-addr.arpa", "reverse.local"); 1 }, "10.0.1.10", range, emptySet())
        assertEquals("reverse.local", earlier.plus(later).names["10.0.1.10"]?.name)
        assertEquals("reverse.local", later.plus(earlier).names["10.0.1.10"]?.name)
    }

    @Test fun answersFromTheAddressItselfBeatRelayedOnes() {
        // A proxy (10.0.9.10) answers for 10.0.1.10 with a reverse PTR; the host itself only with an A record.
        val relayed = interpretMdnsResponse(response { ptr("10.1.0.10.in-addr.arpa", "proxy-said.local"); 1 }, "10.0.9.10", range, emptySet())
        val own = interpretMdnsResponse(response { a("self-said.local", 10, 0, 1, 10); 1 }, "10.0.1.10", range, emptySet())
        assertEquals("self-said.local", relayed.plus(own).names["10.0.1.10"]?.name)
        assertEquals("self-said.local", own.plus(relayed).names["10.0.1.10"]?.name)
        // Relayed answers remain a fallback for hosts that do not answer themselves.
        assertEquals("proxy-said.local", relayed.names["10.0.1.10"]?.name)
        // Within one datagram the same ranking applies.
        val mixed = interpretMdnsResponse(response {
            ptr("10.1.0.10.in-addr.arpa", "relayed-ptr.local")
            a("own-a.local", 10, 0, 9, 10)
            ptr("10.9.0.10.in-addr.arpa", "own-ptr.local")
            3
        }, "10.0.9.10", range, emptySet())
        assertEquals("own-ptr.local", mixed.names["10.0.9.10"]?.name)
        assertEquals("relayed-ptr.local", mixed.names["10.0.1.10"]?.name)
    }

    @Test fun rankOrder() {
        val ranks = listOf(MdnsHostName("x", true, true), MdnsHostName("x", true, false), MdnsHostName("x", false, true), MdnsHostName("x", false, false)).map { it.rank }
        assertEquals(ranks.sortedDescending(), ranks)
        assertEquals(4, ranks.distinct().size)
    }

    @Test fun serviceTypeShape() {
        assertEquals("_ipp._tcp", mdnsServiceType(DnsName(listOf("_ipp", "_tcp", "local"))))
        assertEquals("_raop._udp", mdnsServiceType(DnsName(listOf("_RAOP", "_UDP", "LOCAL"))))
        assertNull(mdnsServiceType(DnsName(listOf("_ipp", "_tcp", "example"))))
        assertNull(mdnsServiceType(DnsName(listOf("ipp", "_tcp", "local"))))
        assertNull(mdnsServiceType(DnsName(listOf("_-x", "_tcp", "local"))))
        assertNull(mdnsServiceType(DnsName(listOf("inst", "_ipp", "_tcp", "local"))))
    }
}
