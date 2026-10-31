package cz.netkit.shared.lan

import kotlin.test.*

/** Wire-format builder for responses; deliberately independent of [encodeDnsQuery]. */
internal class DnsWire {
    private val bytes = ArrayList<Byte>()
    val size: Int get() = bytes.size
    fun u8(value: Int) = apply { bytes += value.toByte() }
    fun u16(value: Int) = apply { u8(value shr 8); u8(value) }
    fun u32(value: Long) = apply { u16((value shr 16).toInt()); u16(value.toInt()) }
    fun raw(vararg values: Int) = apply { values.forEach { u8(it) } }
    fun label(text: String) = apply { val data = text.encodeToByteArray(); u8(data.size); data.forEach { bytes += it } }
    fun name(text: String) = apply { text.split('.').filter { it.isNotEmpty() }.forEach { label(it) }; u8(0) }
    fun pointer(offset: Int) = apply { u16(0xC000 or offset) }
    fun header(id: Int = 0, flags: Int = 0x8400, qd: Int = 0, an: Int = 0, ns: Int = 0, ar: Int = 0) =
        apply { u16(id); u16(flags); u16(qd); u16(an); u16(ns); u16(ar) }
    /** Record header after the owner name; [rdLength] must match what follows. */
    fun rr(type: Int, rdLength: Int, klass: Int = 0x8001, ttl: Long = 120) = apply { u16(type); u16(klass); u32(ttl); u16(rdLength) }
    fun build(): ByteArray = bytes.toByteArray()

    companion object {
        fun nameBytes(text: String): Int = text.split('.').filter { it.isNotEmpty() }.sumOf { it.encodeToByteArray().size + 1 } + 1
    }
}

class DnsMessageTest {
    @Test fun encodesQueryWithQuBitAndSuffixCompression() {
        val first = ipv4ReverseName("192.168.16.10")!!
        val second = ipv4ReverseName("192.168.16.172")!!
        val packet = encodeDnsQuery(0x1234, listOf(DnsQuestion(first, DNS_TYPE_PTR, true), DnsQuestion(second, DNS_TYPE_PTR, true)))
        assertContentEquals(byteArrayOf(0x12, 0x34, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0), packet.copyOfRange(0, 12))
        // First name in full: 10.16.168.192.in-addr.arpa (3+3+4+4+8+5+1 = 28 B) + type + class with QU.
        assertEquals(12 + 28 + 4 + (4 + 2) + 4, packet.size, "second name = one label + pointer to 16.168.192.in-addr.arpa")
        assertContentEquals(byteArrayOf(0, 12, 0x80.toByte(), 1), packet.copyOfRange(12 + 28, 12 + 32))
        val parsed = assertNotNull(parseDnsMessage(packet))
        assertEquals(listOf("10.16.168.192.in-addr.arpa", "172.16.168.192.in-addr.arpa"), parsed.questions.map { it.name.text })
        assertTrue(parsed.questions.all { it.unicastResponse && it.type == DNS_TYPE_PTR && it.dnsClass == DNS_CLASS_IN })
        assertFalse(parsed.isResponse)
    }

    @Test fun compressionIsCaseInsensitive() {
        val packet = encodeDnsQuery(1, listOf(DnsQuestion(dnsName("a.LOCAL")!!, DNS_TYPE_A), DnsQuestion(dnsName("b.local")!!, DNS_TYPE_A)))
        assertEquals(12 + (2 + 6 + 1) + 4 + (2 + 2) + 4, packet.size)
        assertEquals(listOf("a.LOCAL", "b.LOCAL"), parseDnsMessage(packet)!!.questions.map { it.name.text })
    }

    @Test fun rejectsInvalidQueryInput() {
        assertFailsWith<IllegalArgumentException> { encodeDnsQuery(70000, listOf(DnsQuestion(dnsName("a.local")!!, 1))) }
        assertFailsWith<IllegalArgumentException> { encodeDnsQuery(1, emptyList()) }
        assertFailsWith<IllegalArgumentException> { encodeDnsQuery(1, List(DNS_MAX_QUESTIONS + 1) { DnsQuestion(dnsName("a.local")!!, 1) }) }
        assertFailsWith<IllegalArgumentException> { encodeDnsQuery(1, listOf(DnsQuestion(DnsName(listOf("", "local")), 1))) }
        assertNull(dnsName("a".repeat(64) + ".local"), "label over 63 B")
        assertNotNull(dnsName("a".repeat(63) + ".local"))
        assertNull(dnsName(List(5) { "b".repeat(63) }.joinToString(".")), "name over 255 B")
        assertNull(dnsName("a..local"))
        assertNull(dnsName(""))
    }

    @Test fun parsesPtrARecordsSrvTxtAndOther() {
        val wire = DnsWire().header(id = 7, an = 5)
        wire.name("10.1.168.192.in-addr.arpa").rr(DNS_TYPE_PTR, DnsWire.nameBytes("nas.local")).name("nas.local")
        val nasOffset = 12 + DnsWire.nameBytes("10.1.168.192.in-addr.arpa") + 10
        wire.pointer(nasOffset).rr(DNS_TYPE_A, 4).raw(192, 168, 1, 10)
        wire.name("web._http._tcp.local").rr(DNS_TYPE_SRV, 6 + 2, klass = 1).u16(0).u16(5).u16(8080).pointer(nasOffset)
        wire.name("t.local").rr(DNS_TYPE_TXT, 1 + 5 + 1 + 1 + 3).label("path=").u8(0).label("a=b")
        wire.pointer(nasOffset).rr(DNS_TYPE_AAAA, 16).raw(*IntArray(16))
        val message = assertNotNull(parseDnsMessage(wire.build()))
        assertEquals(7, message.id)
        assertTrue(message.isResponse)
        assertEquals(0, message.rcode)
        assertEquals(5, message.records.size)
        val ptr = message.records[0]
        assertTrue(ptr.cacheFlush)
        assertEquals(DNS_CLASS_IN, ptr.dnsClass)
        assertEquals(120, ptr.ttl)
        assertEquals(DnsRecordData.Ptr(DnsName(listOf("nas", "local"))), ptr.data)
        assertEquals("nas.local", message.records[1].name.text)
        assertEquals(DnsRecordData.A("192.168.1.10"), message.records[1].data)
        assertEquals(DnsRecordData.Srv(0, 5, 8080, DnsName(listOf("nas", "local"))), message.records[2].data)
        assertFalse(message.records[2].cacheFlush)
        assertEquals(DnsRecordData.Txt(listOf("path=", "a=b")), message.records[3].data)
        assertEquals(DnsRecordData.Other(16), message.records[4].data)
    }

    @Test fun nameMatchingIsAsciiCaseInsensitive() {
        val name = DnsName(listOf("NAS", "Local"))
        assertTrue(name.matches(listOf("nas", "local")))
        assertTrue(name.endsWith(listOf("LOCAL")))
        assertFalse(name.matches(listOf("nas")))
        assertFalse(DnsName(listOf("local")).endsWith(listOf("nas", "local")))
    }

    @Test fun rejectsTruncatedPackets() {
        val wire = DnsWire().header(an = 1).name("nas.local").rr(DNS_TYPE_A, 4).raw(192, 168, 1, 10)
        val full = wire.build()
        assertNotNull(parseDnsMessage(full))
        for (cut in 0 until full.size) assertNull(parseDnsMessage(full, cut), "cut at $cut")
        assertNull(parseDnsMessage(full, full.size + 1), "length beyond array")
    }

    @Test fun rejectsOversizedDatagramAndAbsurdCounts() {
        assertNull(parseDnsMessage(ByteArray(DNS_MAX_DATAGRAM_BYTES + 1)))
        assertNull(parseDnsMessage(DnsWire().header(an = 0xFFFF).build()))
        assertNull(parseDnsMessage(DnsWire().header(qd = DNS_MAX_QUESTIONS + 1).build()))
        assertNull(parseDnsMessage(DnsWire().header(an = 2).name("a.local").rr(DNS_TYPE_A, 4).raw(1, 2, 3, 4).build()), "declared 2, has 1")
    }

    @Test fun decodesOnlyTheFirstMaxRecords() {
        val wire = DnsWire().header(an = 3)
        repeat(3) { wire.name("h$it.local").rr(DNS_TYPE_A, 4).raw(10, 0, 0, it + 1) }
        assertEquals(2, parseDnsMessage(wire.build(), maxRecords = 2)!!.records.size)
        // Garbage after the decoded records is not inspected.
        val partial = DnsWire().header(an = 2).name("a.local").rr(DNS_TYPE_A, 4).raw(10, 0, 0, 1).raw(0xFF).build()
        assertEquals(1, parseDnsMessage(partial, maxRecords = 1)!!.records.size)
    }

    @Test fun rejectsCompressionLoopsAndForwardPointers() {
        // Pointer to itself.
        assertNull(parseDnsMessage(DnsWire().header(an = 1).pointer(12).rr(DNS_TYPE_A, 4).raw(1, 2, 3, 4).build()))
        // Two pointers pointing at each other (the second one is forward from the first).
        assertNull(parseDnsMessage(DnsWire().header(an = 1).pointer(14).pointer(12).rr(DNS_TYPE_A, 4).raw(1, 2, 3, 4).build()))
        // Forward pointer into later data.
        assertNull(parseDnsMessage(DnsWire().header(an = 1).pointer(40).rr(DNS_TYPE_A, 4).raw(1, 2, 3, 4).build()))
        // Pointer into the header region pointing back to itself via the id bytes.
        val selfInHeader = DnsWire().u16(0xC000).u16(0x8400).u16(0).u16(1).u16(0).u16(0).pointer(0).rr(DNS_TYPE_A, 4).raw(1, 2, 3, 4).build()
        assertNull(parseDnsMessage(selfInHeader))
        // Pointer truncated after its first byte.
        assertNull(parseDnsMessage(DnsWire().header(an = 1).raw(0xC0).build()))
    }

    @Test fun rejectsLongChainsOfBackwardPointers() {
        // 40 records each named by a pointer to the previous record's name: hops grow by one each time.
        val wire = DnsWire().header(an = 40)
        var previous = wire.size
        wire.name("a.local").rr(DNS_TYPE_A, 4).raw(10, 0, 0, 1)
        repeat(39) {
            val here = wire.size
            wire.pointer(previous).rr(DNS_TYPE_A, 4).raw(10, 0, 0, 1)
            previous = here
        }
        assertNull(parseDnsMessage(wire.build()), "more than $DNS_MAX_POINTER_HOPS hops")
    }

    @Test fun rejectsOversizedLabelsNamesAndReservedLabelTypes() {
        assertNull(parseDnsMessage(DnsWire().header(an = 1).u8(64).raw(*IntArray(64) { 'a'.code }).u8(0).rr(DNS_TYPE_A, 4).raw(1, 2, 3, 4).build()))
        val long = DnsWire().header(an = 1)
        repeat(5) { long.label("b".repeat(60)) }
        assertNull(parseDnsMessage(long.u8(0).rr(DNS_TYPE_A, 4).raw(1, 2, 3, 4).build()), "name over 255 B")
        assertNull(parseDnsMessage(DnsWire().header(an = 1).raw(0x41, 0).rr(DNS_TYPE_A, 4).raw(1, 2, 3, 4).build()), "0x40 label type")
        assertNull(parseDnsMessage(DnsWire().header(an = 1).raw(0x81, 0).rr(DNS_TYPE_A, 4).raw(1, 2, 3, 4).build()), "0x80 label type")
        // Long name assembled through compression is limited as well.
        val viaPointer = DnsWire().header(an = 2)
        repeat(4) { viaPointer.label("c".repeat(60)) }
        viaPointer.u8(0).rr(DNS_TYPE_A, 4).raw(1, 2, 3, 4)
        viaPointer.label("d".repeat(20)).pointer(12).rr(DNS_TYPE_A, 4).raw(1, 2, 3, 4)
        assertNull(parseDnsMessage(viaPointer.build()))
    }

    @Test fun rejectsRdataThatDoesNotMatchItsType() {
        assertNull(parseDnsMessage(DnsWire().header(an = 1).name("a.local").rr(DNS_TYPE_A, 3).raw(1, 2, 3).build()), "A must be 4 B")
        // PTR target running past RDLENGTH.
        assertNull(parseDnsMessage(DnsWire().header(an = 1).name("x.arpa").rr(DNS_TYPE_PTR, 3).name("nas.local").build()))
        // SRV shorter than its fixed part.
        assertNull(parseDnsMessage(DnsWire().header(an = 1).name("s.local").rr(DNS_TYPE_SRV, 5).raw(0, 0, 0, 0, 0).build()))
        // TXT string longer than RDATA.
        assertNull(parseDnsMessage(DnsWire().header(an = 1).name("t.local").rr(DNS_TYPE_TXT, 3).raw(5, 'a'.code, 'b'.code).build()))
        // RDLENGTH beyond the datagram.
        assertNull(parseDnsMessage(DnsWire().header(an = 1).name("o.local").rr(99, 50).raw(1, 2).build()))
    }

    @Test fun invalidUtf8InLabelsBecomesReplacementCharacters() {
        val packet = DnsWire().header(an = 1).raw(2, 0xC3, 0x28).label("local").u8(0).rr(DNS_TYPE_A, 4).raw(1, 2, 3, 4).build()
        assertTrue('\uFFFD' in parseDnsMessage(packet)!!.records.single().name.labels.first())
    }

    @Test fun randomBytesNeverThrow() {
        val random = kotlin.random.Random(6762)
        repeat(2000) {
            val packet = ByteArray(random.nextInt(0, 300)) { random.nextInt().toByte() }
            if (packet.size >= 8) { packet[4] = 0; packet[5] = random.nextInt(0, 3).toByte(); packet[6] = 0; packet[7] = random.nextInt(0, 4).toByte() }
            parseDnsMessage(packet)
        }
    }
}
