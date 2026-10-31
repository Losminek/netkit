package cz.netkit.shared.lan

/*
 * RFC 1002 NetBIOS node status (NBSTAT) — request builder and strictly bounds-checked
 * response parser. Section references: 4.2.1 header, 4.2.17 NODE STATUS REQUEST,
 * 4.2.18 NODE STATUS RESPONSE, 14 first-level name encoding (RFC 1001).
 */

const val NETBIOS_NAME_SERVICE_PORT = 137
/** Larger datagrams are rejected; 1024 B leaves room for ~50 names plus statistics. */
const val NETBIOS_MAX_RESPONSE_BYTES = 1024

private const val NBSTAT_TYPE = 0x0021
private const val IN_CLASS = 0x0001
private const val NAME_ENTRY_BYTES = 18

/** One NODE_NAME entry. [suffix] is the 16th byte (0x00 workstation, 0x20 server, ...). */
data class NetBiosName(val name: String, val suffix: Int, val group: Boolean)

/** [name] = unique workstation (0x00) or server (0x20) name; [group] = workgroup/domain (group 0x00). */
data class NetBiosInfo(val name: String?, val group: String?, val names: List<NetBiosName>)

/** 50-byte NBSTAT query for the wildcard name `*` (unicast, no recursion). */
fun netBiosNodeStatusRequest(transactionId: Int): ByteArray {
    require(transactionId in 0..65535)
    val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
    // First-level encoding: each nibble + 'A' → 32 bytes.
    val encoded = ByteArray(32) { index ->
        val byte = raw[index / 2].toInt() and 255
        val nibble = if (index % 2 == 0) byte shr 4 else byte and 15
        ('A'.code + nibble).toByte()
    }
    val header = byteArrayOf((transactionId shr 8).toByte(), transactionId.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)
    return header + byteArrayOf(32) + encoded + byteArrayOf(0, 0, NBSTAT_TYPE.toByte(), 0, IN_CLASS.toByte())
}

/**
 * Returns null for anything that is not a well-formed positive NBSTAT response to
 * [transactionId]: wrong ID, missing response bit, non-zero opcode/RCODE, unexpected
 * counts, truncated or oversized packet, name count that does not fit RDLENGTH, wrong
 * type/class, or no usable name. Name bytes outside printable ASCII become U+FFFD
 * (the OEM code page is unknown), then [sanitizeLanText].
 */
fun parseNetBiosNodeStatus(packet: ByteArray, transactionId: Int, length: Int = packet.size): NetBiosInfo? {
    if (length < 12 || length > packet.size || length > NETBIOS_MAX_RESPONSE_BYTES) return null
    fun u8(index: Int): Int = packet[index].toInt() and 255
    fun u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

    if (u16(0) != transactionId) return null
    val flags = u16(2)
    val response = flags and 0x8000 != 0
    val opcode = (flags shr 11) and 0xF
    val rcode = flags and 0xF
    if (!response || opcode != 0 || rcode != 0) return null
    if (u16(4) != 0 || u16(6) != 1) return null // QDCOUNT 0, ANCOUNT 1

    // RR_NAME: labels or a compression pointer, bounded.
    var cursor = 12
    var labels = 0
    while (true) {
        if (cursor >= length) return null
        val size = u8(cursor)
        if (size == 0) { cursor += 1; break }
        if (size and 0xC0 == 0xC0) { if (cursor + 1 >= length) return null; cursor += 2; break }
        if (size and 0xC0 != 0 || size > 63) return null
        cursor += 1 + size
        if (++labels > 16 || cursor > length) return null
    }
    if (cursor + 10 > length) return null
    if (u16(cursor) != NBSTAT_TYPE || u16(cursor + 2) != IN_CLASS) return null
    val rdLength = u16(cursor + 8)
    cursor += 10
    if (rdLength < 1 || cursor + rdLength > length) return null
    val count = u8(cursor)
    if (count == 0 || 1 + count * NAME_ENTRY_BYTES > rdLength) return null
    cursor += 1

    val names = (0 until count).mapNotNull { entry ->
        val base = cursor + entry * NAME_ENTRY_BYTES
        val nameFlags = u16(base + 16)
        val deregistering = nameFlags and 0x1000 != 0
        val conflict = nameFlags and 0x0800 != 0
        if (deregistering || conflict) return@mapNotNull null
        // Padding is spaces (RFC) or NULs (some stacks); trim it before mapping bytes.
        var end = 15
        while (end > 0 && u8(base + end - 1).let { it == 0x20 || it == 0x00 }) end--
        val text = buildString {
            for (offset in 0 until end) {
                val byte = u8(base + offset)
                append(if (byte in 0x20..0x7E) byte.toChar() else '\uFFFD')
            }
        }.let { sanitizeLanText(it, 15).trim() }
        if (text.isEmpty()) null else NetBiosName(text, u8(base + 15), nameFlags and 0x8000 != 0)
    }
    val workstation = names.firstOrNull { !it.group && it.suffix == 0x00 } ?: names.firstOrNull { !it.group && it.suffix == 0x20 }
    val group = names.firstOrNull { it.group && it.suffix == 0x00 }
    if (workstation == null && group == null) return null
    return NetBiosInfo(workstation?.name, group?.name, names)
}
