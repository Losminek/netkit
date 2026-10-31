package cz.netkit.shared.lan

import kotlin.test.*

class NetBiosParserTest {
    private data class Entry(val name: String, val suffix: Int, val flags: Int)

    private fun nameBytes(name: String): ByteArray = ByteArray(15) { i -> name.getOrNull(i)?.code?.toByte() ?: 0x20 }

    /** Builds an RFC 1002 4.2.18 response with the full encoded RR_NAME and 46 B statistics. */
    private fun response(
        id: Int = 0x1234, flags: Int = 0x8400, qd: Int = 0, an: Int = 1, type: Int = 0x21, klass: Int = 1,
        entries: List<Entry>, rawNames: List<ByteArray>? = null, countOverride: Int? = null, rdLengthOverride: Int? = null,
        pointerName: Boolean = false,
    ): ByteArray {
        val rr = if (pointerName) byteArrayOf(0xC0.toByte(), 0x0C) else netBiosNodeStatusRequest(0).copyOfRange(12, 46)
        val names = entries.mapIndexed { index, entry ->
            (rawNames?.get(index) ?: nameBytes(entry.name)) + byteArrayOf(entry.suffix.toByte(), (entry.flags shr 8).toByte(), entry.flags.toByte())
        }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val rdata = byteArrayOf((countOverride ?: entries.size).toByte()) + names + ByteArray(46)
        val rdLength = rdLengthOverride ?: rdata.size
        val header = byteArrayOf((id shr 8).toByte(), id.toByte(), (flags shr 8).toByte(), flags.toByte(),
            0, qd.toByte(), 0, an.toByte(), 0, 0, 0, 0)
        val fixed = byteArrayOf(0, type.toByte(), 0, klass.toByte(), 0, 0, 0, 0, (rdLength shr 8).toByte(), rdLength.toByte())
        return header + rr + fixed + rdata
    }

    private val typical = listOf(
        Entry("WORKGROUP", 0x00, 0x8400),  // group, active
        Entry("NAS-DOMA", 0x00, 0x0400),   // unique workstation
        Entry("NAS-DOMA", 0x20, 0x0400),   // server service
        Entry("\u0001\u0002__MSBROWSE__\u0002", 0x01, 0x8400),
    )

    @Test fun requestIsRfc1002WildcardNodeStatus() {
        val request = netBiosNodeStatusRequest(0xABCD)
        assertEquals(50, request.size)
        assertContentEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0), request.copyOfRange(0, 12))
        assertEquals(32, request[12].toInt())
        assertEquals("CK" + "A".repeat(30), request.copyOfRange(13, 45).decodeToString())
        assertContentEquals(byteArrayOf(0, 0, 0x21, 0, 1), request.copyOfRange(45, 50))
        assertFailsWith<IllegalArgumentException> { netBiosNodeStatusRequest(65536) }
        assertFailsWith<IllegalArgumentException> { netBiosNodeStatusRequest(-1) }
    }

    @Test fun validResponseWithMultipleNames() {
        val info = assertNotNull(parseNetBiosNodeStatus(response(entries = typical), 0x1234))
        assertEquals("NAS-DOMA", info.name)
        assertEquals("WORKGROUP", info.group)
        assertEquals(4, info.names.size)
        assertEquals(listOf(true, false, false, true), info.names.map { it.group })
        assertEquals(listOf(0x00, 0x00, 0x20, 0x01), info.names.map { it.suffix })
        // Compression pointer in RR_NAME is also accepted.
        assertEquals("NAS-DOMA", parseNetBiosNodeStatus(response(entries = typical, pointerName = true), 0x1234)?.name)
    }

    @Test fun serverNameIsFallbackAndGroupOnlyIsAccepted() {
        val server = parseNetBiosNodeStatus(response(entries = listOf(Entry("PRINTER", 0x20, 0x0400))), 0x1234)
        assertEquals("PRINTER", server?.name); assertNull(server?.group)
        val group = parseNetBiosNodeStatus(response(entries = listOf(Entry("HOME", 0x00, 0x8400))), 0x1234)
        assertNull(group?.name); assertEquals("HOME", group?.group)
        // Deregistering and conflicting names are ignored; nothing usable → null.
        assertNull(parseNetBiosNodeStatus(response(entries = listOf(Entry("OLD", 0x00, 0x1400), Entry("DUP", 0x00, 0x0C00))), 0x1234))
        // Only a non-workstation unique suffix → no usable name.
        assertNull(parseNetBiosNodeStatus(response(entries = listOf(Entry("X", 0x03, 0x0400))), 0x1234))
    }

    @Test fun truncatedResponsesAreRejected() {
        val full = response(entries = typical)
        for (cut in listOf(0, 1, 11, 12, 20, 46, 50, 56, 57, 60, full.size - 47)) {
            assertNull(parseNetBiosNodeStatus(full.copyOf(cut), 0x1234), "cut=$cut")
        }
        // The length argument cannot exceed the buffer.
        assertNull(parseNetBiosNodeStatus(full, 0x1234, full.size + 1))
        // A short receive inside a larger buffer is evaluated only up to `length`.
        val buffer = full.copyOf(NETBIOS_MAX_RESPONSE_BYTES)
        assertNotNull(parseNetBiosNodeStatus(buffer, 0x1234, full.size))
        assertNull(parseNetBiosNodeStatus(buffer, 0x1234, 40))
    }

    @Test fun wrongFlagsIdCountsAndTypeAreRejected() {
        assertNull(parseNetBiosNodeStatus(response(entries = typical), 0x4321), "transaction id")
        assertNull(parseNetBiosNodeStatus(response(flags = 0x0400, entries = typical), 0x1234), "not a response")
        assertNull(parseNetBiosNodeStatus(response(flags = 0x8403, entries = typical), 0x1234), "rcode")
        assertNull(parseNetBiosNodeStatus(response(flags = 0x8400 or (5 shl 11), entries = typical), 0x1234), "opcode")
        assertNull(parseNetBiosNodeStatus(response(qd = 1, entries = typical), 0x1234), "question count")
        assertNull(parseNetBiosNodeStatus(response(an = 0, entries = typical), 0x1234), "answer count")
        assertNull(parseNetBiosNodeStatus(response(type = 0x20, entries = typical), 0x1234), "NB instead of NBSTAT")
        assertNull(parseNetBiosNodeStatus(response(klass = 3, entries = typical), 0x1234), "class")
    }

    @Test fun nameCountOverflowAndBadRdLengthAreRejected() {
        assertNull(parseNetBiosNodeStatus(response(entries = typical, countOverride = 200), 0x1234), "count beyond rdlength")
        assertNull(parseNetBiosNodeStatus(response(entries = typical, countOverride = 0), 0x1234), "zero names")
        assertNull(parseNetBiosNodeStatus(response(entries = typical, rdLengthOverride = 4000), 0x1234), "rdlength beyond packet")
        assertNull(parseNetBiosNodeStatus(response(entries = typical, rdLengthOverride = 0), 0x1234), "empty rdata")
        // 255 names cannot fit the size limit: rejected as oversized before parsing names.
        val many = List(255) { Entry("PC$it", 0x00, 0x0400) }
        val oversized = response(entries = many)
        assertTrue(oversized.size > NETBIOS_MAX_RESPONSE_BYTES)
        assertNull(parseNetBiosNodeStatus(oversized, 0x1234))
    }

    @Test fun malformedRrNameIsRejected() {
        val packet = response(entries = typical)
        packet[12] = 0x80.toByte() // reserved label type
        assertNull(parseNetBiosNodeStatus(packet, 0x1234))
        val endless = ByteArray(200).also { header -> response(entries = typical).copyInto(header, 0, 0, 12) }
        for (i in 12 until 200 step 2) { endless[i] = 1; endless[i + 1] = 'A'.code.toByte() }
        assertNull(parseNetBiosNodeStatus(endless, 0x1234), "unterminated labels")
    }

    @Test fun nonPrintableBytesAreSanitized() {
        val raw = byteArrayOf('P'.code.toByte(), 0x07, 'C'.code.toByte(), 0xC3.toByte(), 0x1B, '1'.code.toByte()) + ByteArray(9)
        val info = assertNotNull(parseNetBiosNodeStatus(response(entries = listOf(Entry("", 0x00, 0x0400)), rawNames = listOf(raw)), 0x1234))
        assertEquals("P\uFFFDC\uFFFD\uFFFD1", info.name)
        assertTrue(info.name!!.none { it.code < 0x20 || it.code == 0x7F })
        // Name consisting only of padding is dropped.
        assertNull(parseNetBiosNodeStatus(response(entries = listOf(Entry("", 0x00, 0x0400)), rawNames = listOf(ByteArray(15))), 0x1234))
        // Trailing padding is trimmed; maximum 15 characters.
        val long = parseNetBiosNodeStatus(response(entries = listOf(Entry("ABCDEFGHIJKLMNOPQ", 0x00, 0x0400))), 0x1234)
        assertEquals("ABCDEFGHIJKLMNO", long?.name)
    }
}
