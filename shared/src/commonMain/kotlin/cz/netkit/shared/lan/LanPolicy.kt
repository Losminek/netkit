package cz.netkit.shared.lan

/** The reasons are data; localized explanations belong to the UI. */
enum class TargetConfirmationReason { OUTSIDE_WIFI, NON_PRIVATE_ADDRESS, LARGE_HOST_SET, LARGE_PORT_SET }

fun targetConfirmationReasons(range: Ipv4Subnet, own: Ipv4Subnet?, portCount: Int = 0): Set<TargetConfirmationReason> = buildSet {
    val first = ipv4Text(range.network)
    val last = ipv4Text(range.network + range.size - 1)
    if (own == null || !own.contains(first) || !own.contains(last)) add(TargetConfirmationReason.OUTSIDE_WIFI)
    if (!isLocalAddress(first) || !isLocalAddress(last)) add(TargetConfirmationReason.NON_PRIVATE_ADDRESS)
    if (range.hostCount > 254) add(TargetConfirmationReason.LARGE_HOST_SET)
    if (portCount > 254) add(TargetConfirmationReason.LARGE_PORT_SET)
}
fun requiresTargetConfirmation(range: Ipv4Subnet, own: Ipv4Subnet?, portCount: Int = 0): Boolean =
    targetConfirmationReasons(range, own, portCount).isNotEmpty()

fun validLanHost(host: String): Boolean {
    if (host.isEmpty() || host.length > 253) return false
    if (host.all { it in '0'..'9' || it == '.' }) return ipv4Number(host) != null
    return safePtrName(host) != null
}

private val unsafeLanCodePoint = Regex("[\\p{Cc}\\p{Cf}]")

/**
 * Sanitize complete Unicode code points. The limit counts output UTF-16 units;
 * a valid surrogate pair is either kept whole or omitted at the boundary.
 * Lone surrogates are replaced rather than leaking malformed text to exports.
 */
fun sanitizeLanText(value: String, maximum: Int = 256): String {
    require(maximum >= 0)
    return buildString {
        var offset = 0
        while (offset < value.length && length < maximum) {
            val first = value[offset]
            val pair = first in '\uD800'..'\uDBFF' && value.getOrNull(offset + 1)?.let { it in '\uDC00'..'\uDFFF' } == true
            val width = if (pair) 2 else 1
            val codePoint = value.substring(offset, offset + width)
            val replacement = when {
                !pair && first in '\uD800'..'\uDFFF' -> "\uFFFD"
                unsafeLanCodePoint.matches(codePoint) -> " "
                else -> codePoint
            }
            if (length + replacement.length > maximum) break
            append(replacement)
            offset += width
        }
    }
}

/** PTR is a DNS hostname, not arbitrary display text. Reject invalid labels instead of rewriting them. */
fun safePtrName(value: String): String? {
    val name = value.removeSuffix(".")
    if (name.length !in 1..253) return null
    val label = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    return name.takeIf { it.split('.').all(label::matches) }
}

enum class LanRunStatus { IDLE, RUNNING, FINISHED, CANCELLED, FAILED }

/** Keeps sequence and elapsed time independent of the bounded display window. */
fun appendPingResult(previous: List<LanResult>, address: String, sequence: Long, elapsedMs: Long, rtt: Double?): List<LanResult> {
    require(sequence > (previous.lastOrNull()?.sequence ?: 0) && elapsedMs >= (previous.lastOrNull()?.elapsedMs ?: 0))
    return (previous + LanResult(address, method = "ICMP", milliseconds = rtt, sequence = sequence, elapsedMs = elapsedMs)).takeLast(600)
}

/** Loopback, this-network, multicast and reserved addresses have no remote LAN PTR lookup. */
fun shouldQueryLanPtr(address: String): Boolean {
    val numeric = ipv4Number(address) ?: return false
    val first = numeric shr 24
    return first != 0L && first != 127L && first < 224L
}
