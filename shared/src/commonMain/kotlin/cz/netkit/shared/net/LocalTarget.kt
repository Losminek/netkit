package cz.netkit.shared.net

import cz.netkit.shared.lan.ipv4Number

/**
 * `true` for a numeric address that is only reachable inside a local network: RFC 1918
 * (10/8, 172.16/12, 192.168/16), CGNAT shared space (100.64/10), IPv4 link-local (169.254/16),
 * loopback (127/8, ::1), IPv6 link-local (fe80::/10) and ULA (fc00::/7). IPv4-mapped IPv6
 * (`::ffff:a.b.c.d`) is judged by its IPv4 part. Brackets and an IPv6 zone (`%wlan0`) are
 * accepted. A hostname or an invalid literal is `false`: nothing is resolved here.
 */
fun isPrivateOrLocalTarget(address: String): Boolean {
    val text = address.trim()
    ipv4Number(text)?.let { return privateIpv4(it) }
    val bytes = ipv6Bytes(text) ?: return false
    mappedIpv4(bytes)?.let { return privateIpv4(it) }
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    return isIpv6Loopback(bytes) ||
        (first == 0xfe && second and 0xc0 == 0x80) || // fe80::/10
        (first and 0xfe == 0xfc) // fc00::/7
}

/** Loopback literal (127/8, ::1, ::ffff:127.x): the phone itself, reachable on any network. */
fun isLoopbackTarget(address: String): Boolean {
    val text = address.trim()
    ipv4Number(text)?.let { return it shr 24 == 127L }
    val bytes = ipv6Bytes(text) ?: return false
    mappedIpv4(bytes)?.let { return it shr 24 == 127L }
    return isIpv6Loopback(bytes)
}

private fun privateIpv4(value: Long): Boolean = PRIVATE_IPV4.any { (network, prefix) ->
    val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    value and mask == network
}

private val PRIVATE_IPV4: List<Pair<Long, Int>> = listOf(
    "10.0.0.0" to 8, "172.16.0.0" to 12, "192.168.0.0" to 16, "100.64.0.0" to 10,
    "169.254.0.0" to 16, "127.0.0.0" to 8,
).map { (network, prefix) -> ipv4Number(network)!! to prefix }

private fun isIpv6Loopback(bytes: ByteArray): Boolean = (0 until 15).all { bytes[it].toInt() == 0 } && bytes[15].toInt() == 1

private fun mappedIpv4(bytes: ByteArray): Long? {
    if ((0 until 10).any { bytes[it].toInt() != 0 } || bytes[10].toInt() and 0xff != 0xff || bytes[11].toInt() and 0xff != 0xff) return null
    return (12 until 16).fold(0L) { value, index -> (value shl 8) or (bytes[index].toLong() and 0xff) }
}

/** Strict IPv6 literal parser (RFC 4291 text forms, optional brackets and zone); `null` when invalid. */
internal fun ipv6Bytes(input: String): ByteArray? {
    var text = input
    if (text.startsWith('[')) {
        if (!text.endsWith(']')) return null
        text = text.substring(1, text.length - 1)
    }
    val zone = text.indexOf('%')
    if (zone >= 0) {
        if (zone == text.length - 1) return null
        text = text.substring(0, zone)
    }
    if (text.length !in 2..45 || ':' !in text) return null
    val halves = text.split("::")
    if (halves.size > 2) return null
    fun groups(part: String): List<String>? = if (part.isEmpty()) emptyList() else part.split(':').takeIf { it.none(String::isEmpty) }
    val head = groups(halves[0]) ?: return null
    val tail = (if (halves.size == 2) groups(halves[1]) else emptyList()) ?: return null
    val all = head + tail
    // An embedded IPv4 is allowed only as the last element.
    val ipv4 = all.lastOrNull()?.takeIf { '.' in it }?.let { ipv4Number(it) ?: return null }
    if (ipv4 != null && halves.size == 2 && tail.isEmpty()) return null
    val hexGroups = if (ipv4 != null) all.dropLast(1) else all
    if (hexGroups.any { group -> group.length !in 1..4 || group.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' } }) return null
    val words = hexGroups.size + if (ipv4 != null) 2 else 0
    if (halves.size == 1 && words != 8) return null
    if (halves.size == 2 && words > 7) return null
    val values = mutableListOf<Int>()
    fun add(list: List<String>) = list.forEach { values += it.toInt(16) }
    val headHex = if (ipv4 != null && tail.isEmpty()) head.dropLast(1) else head
    val tailHex = if (ipv4 != null && tail.isNotEmpty()) tail.dropLast(1) else tail
    add(headHex)
    if (halves.size == 2) repeat(8 - words) { values += 0 }
    add(tailHex)
    if (ipv4 != null) { values += (ipv4 shr 16).toInt() and 0xffff; values += ipv4.toInt() and 0xffff }
    if (values.size != 8) return null
    return ByteArray(16) { index -> (values[index / 2] shr (if (index % 2 == 0) 8 else 0)).toByte() }
}
