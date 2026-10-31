package cz.netkit.shared.lan

/*
 * Minimal RFC 1035 / RFC 6762 DNS message codec for mDNS identification (M2).
 * Pure Kotlin over ByteArray; every read is bounds-checked and a malformed message
 * yields null instead of an exception. Name compression is accepted only as strictly
 * backward pointers (RFC 1035 4.1.4 "prior occurrence"), which together with a hop limit
 * makes decompression loops impossible.
 */

const val DNS_TYPE_A = 1
const val DNS_TYPE_PTR = 12
const val DNS_TYPE_TXT = 16
const val DNS_TYPE_AAAA = 28
const val DNS_TYPE_SRV = 33
const val DNS_CLASS_IN = 1

/** RFC 6762 17: an mDNS datagram may be up to 9000 B (jumbo frames); larger ones are rejected. */
const val DNS_MAX_DATAGRAM_BYTES = 9000
/** RFC 1035 2.3.4: 63 B per label, 255 B per wire name including length bytes and the root. */
const val DNS_MAX_LABEL_BYTES = 63
const val DNS_MAX_NAME_BYTES = 255
const val DNS_MAX_POINTER_HOPS = 32
/** Records (all sections) decoded from one message; later ones are ignored, not rejected. */
const val DNS_MAX_RECORDS = 128
const val DNS_MAX_QUESTIONS = 64
/** A count sum above this cannot be a legitimate datagram of [DNS_MAX_DATAGRAM_BYTES]. */
private const val DNS_MAX_DECLARED_ENTRIES = 1024

/** A decoded or to-be-encoded domain name; labels are UTF-8 text without dots semantics. */
data class DnsName(val labels: List<String>) {
    val text: String get() = labels.joinToString(".")
    /** ASCII case-insensitive comparison (RFC 4343); non-ASCII must match exactly. */
    fun matches(other: List<String>): Boolean =
        labels.size == other.size && labels.indices.all { labels[it].equals(other[it], ignoreCase = true) }
    fun endsWith(suffix: List<String>): Boolean =
        labels.size >= suffix.size && DnsName(labels.takeLast(suffix.size)).matches(suffix)
    override fun toString(): String = text
}

/** [unicastResponse] is the mDNS QU bit (top bit of QCLASS, RFC 6762 5.4). */
data class DnsQuestion(val name: DnsName, val type: Int, val unicastResponse: Boolean = false, val dnsClass: Int = DNS_CLASS_IN)

sealed interface DnsRecordData {
    data class A(val address: String) : DnsRecordData
    data class Ptr(val target: DnsName) : DnsRecordData
    data class Srv(val priority: Int, val weight: Int, val port: Int, val target: DnsName) : DnsRecordData
    data class Txt(val entries: List<String>) : DnsRecordData
    /** Any other type (AAAA, NSEC, ...): only the length is kept. */
    data class Other(val length: Int) : DnsRecordData
}

/** [cacheFlush] is the mDNS top bit of RRCLASS (RFC 6762 10.2); [dnsClass] has it removed. */
data class DnsRecord(val name: DnsName, val type: Int, val dnsClass: Int, val cacheFlush: Boolean, val ttl: Long, val data: DnsRecordData)

/** [records] holds answer, authority and additional records in wire order (at most [DNS_MAX_RECORDS]). */
data class DnsMessage(val id: Int, val flags: Int, val questions: List<DnsQuestion>, val records: List<DnsRecord>) {
    val isResponse: Boolean get() = flags and 0x8000 != 0
    val opcode: Int get() = (flags shr 11) and 0xF
    val rcode: Int get() = flags and 0xF
}

/** Name from dotted text; null if any label is empty or too long or the name exceeds 255 B. */
fun dnsName(text: String): DnsName? {
    val labels = text.removeSuffix(".").split('.')
    val name = DnsName(labels)
    return name.takeIf { dnsNameWireLength(it) != null }
}

private fun dnsNameWireLength(name: DnsName): Int? {
    if (name.labels.isEmpty()) return 1
    var total = 1
    for (label in name.labels) {
        val size = label.encodeToByteArray().size
        if (size !in 1..DNS_MAX_LABEL_BYTES) return null
        total += 1 + size
    }
    return total.takeIf { it <= DNS_MAX_NAME_BYTES }
}

/**
 * Standard query (flags 0, no recursion) with suffix compression between questions.
 * Throws [IllegalArgumentException] for an invalid ID, question count or name.
 */
fun encodeDnsQuery(id: Int, questions: List<DnsQuestion>): ByteArray {
    require(id in 0..65535)
    require(questions.size in 1..DNS_MAX_QUESTIONS)
    val out = ByteBuilder()
    out.u16(id); out.u16(0); out.u16(questions.size); out.u16(0); out.u16(0); out.u16(0)
    val offsets = HashMap<String, Int>()
    for (question in questions) {
        require(dnsNameWireLength(question.name) != null) { "DNS_NAME" }
        require(question.type in 0..65535 && question.dnsClass in 0..0x7FFF)
        val labels = question.name.labels
        var pointer = -1
        for (index in labels.indices) {
            val key = labels.subList(index, labels.size).joinToString("\u0000") { it.lowercase() }
            val known = offsets[key]
            if (known != null) { pointer = known; break }
            if (out.size < 0x3FFF) offsets[key] = out.size
            val bytes = labels[index].encodeToByteArray()
            out.u8(bytes.size); out.bytes(bytes)
        }
        if (pointer >= 0) out.u16(0xC000 or pointer) else out.u8(0)
        out.u16(question.type)
        out.u16(question.dnsClass or if (question.unicastResponse) 0x8000 else 0)
    }
    return out.toByteArray()
}

/**
 * Parses a DNS message of [length] bytes. Returns null for: length outside 12..9000 or
 * beyond the array, absurd section counts, truncated sections, labels over 63 B, names over
 * 255 B, forward or looping compression pointers, reserved label types (0x40/0x80), or
 * RDATA that does not match its type (A ≠ 4 B, names leaving RDATA, broken TXT strings).
 * At most [maxRecords] records are decoded; the rest is not inspected.
 */
fun parseDnsMessage(packet: ByteArray, length: Int = packet.size, maxRecords: Int = DNS_MAX_RECORDS): DnsMessage? {
    if (length < 12 || length > packet.size || length > DNS_MAX_DATAGRAM_BYTES) return null
    val reader = DnsReader(packet, length)
    return try { reader.message(maxRecords.coerceIn(0, DNS_MAX_RECORDS)) } catch (_: MalformedDns) { null }
}

private class MalformedDns : Exception()

private class DnsReader(private val packet: ByteArray, private val length: Int) {
    private fun fail(): Nothing = throw MalformedDns()
    private fun u8(index: Int): Int { if (index !in 0 until length) fail(); return packet[index].toInt() and 255 }
    private fun u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)
    private fun u32(index: Int): Long = (u16(index).toLong() shl 16) or u16(index + 2).toLong()

    fun message(maxRecords: Int): DnsMessage {
        val id = u16(0)
        val flags = u16(2)
        val qd = u16(4)
        val records = u16(6) + u16(8) + u16(10)
        if (qd > DNS_MAX_QUESTIONS || qd + records > DNS_MAX_DECLARED_ENTRIES) fail()
        var cursor = 12
        val questions = ArrayList<DnsQuestion>(qd)
        repeat(qd) {
            val (name, next) = name(cursor)
            val type = u16(next)
            val klass = u16(next + 2)
            questions += DnsQuestion(name, type, klass and 0x8000 != 0, klass and 0x7FFF)
            cursor = next + 4
        }
        val parsed = ArrayList<DnsRecord>(minOf(records, maxRecords))
        for (index in 0 until minOf(records, maxRecords)) {
            val (name, next) = name(cursor)
            val type = u16(next)
            val klass = u16(next + 2)
            val ttl = u32(next + 4)
            val rdLength = u16(next + 8)
            val start = next + 10
            val end = start + rdLength
            if (end > length) fail()
            parsed += DnsRecord(name, type, klass and 0x7FFF, klass and 0x8000 != 0, ttl, rdata(type, start, end))
            cursor = end
        }
        return DnsMessage(id, flags, questions, parsed)
    }

    private fun rdata(type: Int, start: Int, end: Int): DnsRecordData = when (type) {
        DNS_TYPE_A -> {
            if (end - start != 4) fail()
            DnsRecordData.A((0 until 4).joinToString(".") { u8(start + it).toString() })
        }
        DNS_TYPE_PTR -> {
            val (target, next) = name(start)
            if (next > end) fail()
            DnsRecordData.Ptr(target)
        }
        DNS_TYPE_SRV -> {
            if (end - start < 7) fail()
            val (target, next) = name(start + 6)
            if (next > end) fail()
            DnsRecordData.Srv(u16(start), u16(start + 2), u16(start + 4), target)
        }
        DNS_TYPE_TXT -> {
            val entries = ArrayList<String>()
            var cursor = start
            while (cursor < end) {
                val size = u8(cursor)
                if (cursor + 1 + size > end) fail()
                // Keep every fragment: the datagram byte limit already bounds allocation.
                if (size > 0) entries += packet.decodeToString(cursor + 1, cursor + 1 + size)
                cursor += 1 + size
            }
            DnsRecordData.Txt(entries)
        }
        else -> DnsRecordData.Other(end - start)
    }

    /** Returns the name and the offset just after its in-place encoding. */
    fun name(start: Int): Pair<DnsName, Int> {
        val labels = ArrayList<String>()
        var cursor = start
        var next = -1
        var hops = 0
        var wire = 1
        while (true) {
            val size = u8(cursor)
            when {
                size == 0 -> { if (next < 0) next = cursor + 1; break }
                size and 0xC0 == 0xC0 -> {
                    val target = ((size and 0x3F) shl 8) or u8(cursor + 1)
                    // Strictly backward: every hop moves to a smaller offset, so no cycle can exist.
                    if (target >= cursor || ++hops > DNS_MAX_POINTER_HOPS) fail()
                    if (next < 0) next = cursor + 2
                    cursor = target
                }
                size and 0xC0 != 0 -> fail() // 0x40 / 0x80: extended / reserved label types
                else -> {
                    if (cursor + 1 + size > length) fail()
                    wire += 1 + size
                    if (wire > DNS_MAX_NAME_BYTES) fail()
                    labels += packet.decodeToString(cursor + 1, cursor + 1 + size)
                    cursor += 1 + size
                }
            }
        }
        return DnsName(labels) to next
    }
}

private class ByteBuilder {
    private var buffer = ByteArray(512)
    var size = 0; private set
    private fun ensure(extra: Int) { if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra)) }
    fun u8(value: Int) { ensure(1); buffer[size++] = value.toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    fun bytes(value: ByteArray) { ensure(value.size); value.copyInto(buffer, size); size += value.size }
    fun toByteArray(): ByteArray = buffer.copyOf(size)
}
