package cz.netkit.shared.publicip

import cz.netkit.shared.lan.DNS_CLASS_IN
import cz.netkit.shared.lan.DNS_MAX_RECORDS
import cz.netkit.shared.lan.DNS_TYPE_TXT
import cz.netkit.shared.lan.DnsQuestion
import cz.netkit.shared.lan.DnsRecordData
import cz.netkit.shared.lan.dnsName
import cz.netkit.shared.lan.encodeDnsQuery
import cz.netkit.shared.lan.ipv4Number
import cz.netkit.shared.lan.ipv4Text
import cz.netkit.shared.lan.parseDnsMessage

enum class IpFamily { V4, V6 }
data class PublicAddress(val family: IpFamily, val address: String)
data class AsnOrigin(val asns: List<Long>, val prefix: String, val country: String?, val registry: String?)
data class AsnName(val asn: Long, val handle: String?, val name: String, val country: String?)
data class PublicIpInfo(
    val v4: PublicAddress?, val v6: PublicAddress?, val asn: Long?, val prefix: String?,
    val provider: String?, val providerRaw: String?, val country: String?, val fetchedAtMs: Long,
)

enum class PublicAddressRejection { INVALID, WRONG_FAMILY, PRIVATE, CGNAT, LOOPBACK, LINK_LOCAL, ULA, NON_PUBLIC }
/** Exactly one of [address] / [reason] is non-null for results returned by [classifyPublicAddress]. */
data class PublicAddressClassification(val address: PublicAddress?, val reason: PublicAddressRejection?)

/**
 * Numeric parsing only, never DNS. Documentation ranges are deliberately treated as public
 * for offline fixtures (TEST-NET-1/2/3 and 2001:db8::/32); this is not a reachability check.
 * IPv4-mapped/compatible IPv6 and other non-global IPv6 scopes are not public IPv6 results.
 */
fun classifyPublicAddress(value: String, expected: IpFamily): PublicAddressClassification {
    val ip = parseIp(value) ?: return PublicAddressClassification(null, PublicAddressRejection.INVALID)
    val reason = when {
        ip.family != expected -> PublicAddressRejection.WRONG_FAMILY
        ip.family == IpFamily.V4 -> {
            val a = ip.words[0] shr 8
            val b = ip.words[0] and 255
            when {
                a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168 -> PublicAddressRejection.PRIVATE
                a == 100 && b in 64..127 -> PublicAddressRejection.CGNAT
                a == 127 -> PublicAddressRejection.LOOPBACK
                a == 169 && b == 254 -> PublicAddressRejection.LINK_LOCAL
                a == 0 || a >= 224 || a == 198 && b in 18..19 -> PublicAddressRejection.NON_PUBLIC
                else -> null
            }
        }
        ip.words.take(7).all { it == 0 } && ip.words[7] == 1 -> PublicAddressRejection.LOOPBACK
        ip.words[0] and 0xfe00 == 0xfc00 -> PublicAddressRejection.ULA
        ip.words[0] and 0xffc0 == 0xfe80 -> PublicAddressRejection.LINK_LOCAL
        ip.words[0] and 0xe000 != 0x2000 -> PublicAddressRejection.NON_PUBLIC
        else -> null
    }
    return PublicAddressClassification(if (reason == null) PublicAddress(ip.family, ip.text()) else null, reason)
}

/** At most 4096 UTF-8 bytes; ambiguous duplicate ip fields fail closed. */
fun parseCloudflareTrace(body: String, expected: IpFamily): PublicAddress? {
    if (body.length > 4096 || body.encodeToByteArray().size > 4096) return null
    val values = body.lineSequence().map { it.trim() }.filter { it.startsWith("ip=") }.toList()
    if (values.size != 1) return null
    return classifyPublicAddress(values.single().substring(3).trim(), expected).address
}

/** Invalid literals or a mismatched family throw; the builder itself does not perform scope checks. */
fun cymruOriginQueryName(address: PublicAddress): String {
    val ip = requireNotNull(parseIp(address.address)) { "IP_ADDRESS" }
    require(ip.family == address.family) { "IP_FAMILY" }
    return when (ip.family) {
        IpFamily.V4 -> ip.text().split('.').reversed().joinToString(".") + ".origin.asn.cymru.com"
        IpFamily.V6 -> ip.words.joinToString("") { it.toString(16).padStart(4, '0') }
            .reversed().toList().joinToString(".") + ".origin6.asn.cymru.com"
    }
}

fun cymruAsNameQueryName(asn: Long): String {
    require(asn in 1..0xffffffffL) { "ASN" }
    return "AS$asn.asn.cymru.com"
}

/** Recursive unicast IN/TXT query. Caller owns transaction-ID and transport validation. */
fun encodeTxtQuery(id: Int, name: String): ByteArray =
    encodeDnsQuery(id, listOf(DnsQuestion(requireNotNull(dnsName(name)) { "DNS_NAME" }, DNS_TYPE_TXT)))
        .also { it[2] = (it[2].toInt() or 1).toByte() } // RD, required by the system recursive resolver.

/** Only the answer section, exact owner (case-insensitive), IN/TXT, complete NOERROR response. */
fun txtAnswers(packet: ByteArray, expectedName: String): List<String> {
    val name = dnsName(expectedName) ?: return emptyList()
    val message = parseDnsMessage(packet) ?: return emptyList()
    if (!message.isResponse || message.opcode != 0 || message.rcode != 0 || message.flags and 0x0200 != 0) return emptyList()
    fun count(offset: Int) = ((packet[offset].toInt() and 255) shl 8) or (packet[offset + 1].toInt() and 255)
    val answers = count(6)
    // The shared mDNS codec intentionally permits partial decoding above its record limit.
    if (answers + count(8) + count(10) > DNS_MAX_RECORDS) return emptyList()
    if (message.questions.any { !it.name.matches(name.labels) || it.type != DNS_TYPE_TXT || it.dnsClass != DNS_CLASS_IN }) return emptyList()
    return message.records.take(answers).mapNotNull {
        if (it.type == DNS_TYPE_TXT && it.dnsClass == DNS_CLASS_IN && !it.cacheFlush && it.name.matches(name.labels))
            (it.data as? DnsRecordData.Txt)?.entries?.joinToString("") else null
    }
}

/**
 * [txtAnswers] of a response to the query with transaction [id]: a packet with another ID (or
 * shorter than the header) is not the answer to this query and yields nothing.
 */
fun txtAnswersFor(packet: ByteArray, id: Int, expectedName: String): List<String> {
    if (packet.size < 12) return emptyList()
    val received = ((packet[0].toInt() and 255) shl 8) or (packet[1].toInt() and 255)
    return if (received != (id and 0xffff)) emptyList() else txtAnswers(packet, expectedName)
}

/** Accepts one or more lines; invalid lines are ignored, the most specific prefix wins. */
fun parseCymruOrigin(txt: String): AsnOrigin? = txt.lineSequence().mapNotNull(::originLine)
    .maxByOrNull { it.prefix.substringAfter('/').toInt() }

/** Pass TXT answers from one origin query (one address/family). First record wins ties. */
fun selectCymruOrigin(records: List<String>): AsnOrigin? = records.mapNotNull(::parseCymruOrigin)
    .maxByOrNull { it.prefix.substringAfter('/').toInt() }

private fun originLine(line: String): AsnOrigin? {
    val fields = cymruFields(line) ?: return null
    val asns = fields[0].split(Regex("\\s+")).map { parseAsn(it) ?: return null }.distinct()
    val prefix = parsePrefix(fields[1]) ?: return null
    val country = country(fields[2])
    if (fields[2].isNotEmpty() && fields[2] != "NA" && country == null) return null
    return AsnOrigin(asns, prefix, country, fields[3].takeIf { it.isNotEmpty() && it != "NA" })
}

/** Name is the full fifth field (providerRaw); use [shortProviderName] for display. */
fun parseCymruAsName(txt: String): AsnName? = txt.lineSequence().mapNotNull { line ->
    val fields = cymruFields(line) ?: return@mapNotNull null
    val asn = parseAsn(fields[0]) ?: return@mapNotNull null
    if (fields[4].isEmpty()) return@mapNotNull null
    val country = country(fields[1])
    if (fields[1].isNotEmpty() && fields[1] != "NA" && country == null) return@mapNotNull null
    val handle = fields[4].substringBefore(" - ", "").trim().takeIf { it.isNotEmpty() }
    AsnName(asn, handle, fields[4], country)
}.firstOrNull()

fun shortProviderName(raw: String): String {
    val name = raw.trim().substringAfter(" - ").trim()
    return name.replace(Regex(",\\s*[A-Z]{2}$"), "").trim().take(80)
}

/** Unknown network identity, future timestamps and non-positive TTL always miss. Expiry is exclusive. */
fun isPublicIpCacheFresh(
    info: PublicIpInfo?, networkKey: String?, cachedKey: String?, nowMs: Long, ttlMs: Long = 600_000,
): Boolean {
    if (info == null || networkKey.isNullOrBlank() || networkKey != cachedKey || ttlMs <= 0) return false
    if (info.fetchedAtMs < 0 || nowMs < info.fetchedAtMs) return false
    return nowMs - info.fetchedAtMs < ttlMs
}

private fun parseAsn(value: String): Long? = value.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
    ?.toLongOrNull()?.takeIf { it in 1..0xffffffffL }
private fun country(value: String): String? = value.takeIf { it != "NA" && it.length == 2 && it.all { c -> c in 'A'..'Z' } }
private fun cymruFields(line: String): List<String>? {
    val text = line.trim().removeSurrounding("\"")
    if (text.any { it.code < 32 && it != '\t' || it.code == 127 }) return null
    return text.split('|').map { it.trim() }.takeIf { it.size == 5 }
}

private data class NumericIp(val family: IpFamily, val words: List<Int>) {
    fun text(): String = if (family == IpFamily.V4) ipv4Text((words[0].toLong() shl 16) or words[1].toLong()) else ipv6Text(words)
}

private fun parseIp(text: String): NumericIp? {
    if (text.isEmpty() || text.length > 45) return null
    if (':' !in text) {
        val value = ipv4Number(text) ?: return null
        return NumericIp(IpFamily.V4, listOf((value shr 16).toInt(), (value and 65535).toInt()))
    }
    var hex = text
    if ('.' in hex) {
        val tail = ipv4Number(hex.substringAfterLast(':')) ?: return null
        hex = hex.substringBeforeLast(':') + ":" + (tail shr 16).toString(16) + ":" + (tail and 65535).toString(16)
    }
    val halves = hex.split("::")
    if (halves.size > 2) return null
    fun words(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        return part.split(':').map { word ->
            if (word.length !in 1..4 || word.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
            word.toInt(16)
        }
    }
    val left = words(halves[0]) ?: return null
    val right = if (halves.size == 2) words(halves[1]) ?: return null else emptyList()
    val count = left.size + right.size
    if (halves.size == 1 && count != 8 || halves.size == 2 && count >= 8) return null
    return NumericIp(IpFamily.V6, left + List(8 - count) { 0 } + right)
}

/** RFC 5952 §4: longest zero run, first on ties, never compress a single zero. */
private fun ipv6Text(words: List<Int>): String {
    var bestStart = -1
    var bestLength = 1
    var i = 0
    while (i < words.size) {
        if (words[i] != 0) { i++; continue }
        val start = i
        while (i < words.size && words[i] == 0) i++
        if (i - start > bestLength) { bestStart = start; bestLength = i - start }
    }
    if (bestStart < 0) return words.joinToString(":") { it.toString(16) }
    return words.take(bestStart).joinToString(":") { it.toString(16) } + "::" +
        words.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
}

private fun parsePrefix(text: String): String? {
    val parts = text.split('/')
    if (parts.size != 2 || parts[1].isEmpty() || parts[1].any { it !in '0'..'9' }) return null
    val ip = parseIp(parts[0]) ?: return null
    val bits = parts[1].toIntOrNull()?.takeIf { it in 0..ip.words.size * 16 } ?: return null
    val network = ip.words.mapIndexed { index, word ->
        val keep = (bits - index * 16).coerceIn(0, 16)
        word and ((0xffff shl (16 - keep)) and 0xffff)
    }
    return "${NumericIp(ip.family, network).text()}/$bits"
}
