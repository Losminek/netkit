package cz.netkit.shared.wifi

/** IEEE 802.11 BSS Load element ID (QBSS Load in older drafts). */
const val BSS_LOAD_ELEMENT_ID = 11

/** Exact body length of the BSS Load element, without the 2-byte ID/length header. */
const val BSS_LOAD_BODY_LENGTH = 5

/** Upper bound of any 802.11 element body (length octet is uint8); callers copy at most this. */
const val WIFI_ELEMENT_MAX_BODY_LENGTH = 255

/**
 * Decoded BSS Load element as advertised by the AP itself.
 *
 * @property stationCount stations currently associated, as reported by the AP (uint16).
 * @property channelUtilizationRaw raw busy-time fraction n/255 sensed by the AP on its primary channel.
 * @property availableAdmissionCapacity remaining admission-control time in units of 32 µs/s (uint16).
 */
data class BssLoad(
    val stationCount: Int,
    val channelUtilizationRaw: Int,
    val availableAdmissionCapacity: Int,
) {
    /** round(n * 100 / 255); n*200 is never an odd multiple of 255, so there is no half-way case. */
    val channelUtilizationPercent: Int get() = (channelUtilizationRaw * 100 + 127) / 255
}

/**
 * Parses the BSS Load element **body** (bytes after ID and length).
 *
 * Layout: station count uint16 LE, channel utilization uint8, available admission capacity
 * uint16 LE. Any other length is rejected with `null` (no truncation, no padding), because a
 * malformed or vendor-extended element cannot be interpreted safely.
 */
fun parseBssLoad(body: ByteArray): BssLoad? {
    if (body.size != BSS_LOAD_BODY_LENGTH) return null
    fun u8(index: Int) = body[index].toInt() and 0xFF
    return BssLoad(
        stationCount = u8(0) or (u8(1) shl 8),
        channelUtilizationRaw = u8(2),
        availableAdmissionCapacity = u8(3) or (u8(4) shl 8),
    )
}
