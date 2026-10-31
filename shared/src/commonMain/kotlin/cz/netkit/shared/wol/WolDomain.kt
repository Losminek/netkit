package cz.netkit.shared.wol

class MacAddress internal constructor(internal val octets: List<Int>) {
    override fun toString(): String = octets.joinToString(":") { it.toString(16).padStart(2, '0').uppercase() }
}

fun parseMac(input: String): MacAddress? {
    val text = input.trim()
    val valid = listOf(
        Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}"),
        Regex("[0-9a-fA-F]{2}(-[0-9a-fA-F]{2}){5}"),
        Regex("[0-9a-fA-F]{4}(\\.[0-9a-fA-F]{4}){2}"),
        Regex("[0-9a-fA-F]{12}"),
    ).any { it.matches(text) }
    if (!valid) return null
    val bytes = text.filter { it != ':' && it != '-' && it != '.' }.chunked(2).map { it.toInt(16) }
    if (bytes[0] and 1 != 0) return null
    return MacAddress(bytes)
}

fun magicPacket(mac: MacAddress): ByteArray = ByteArray(102) { index ->
    if (index < 6) 0xff.toByte() else mac.octets[(index - 6) % 6].toByte()
}

class Ipv4Address internal constructor(internal val value: Long) {
    override fun toString(): String = (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 255).toString() }
}

fun parseIpv4(input: String): Ipv4Address? {
    val parts = input.split('.')
    if (parts.size != 4) return null
    var value = 0L
    for (part in parts) {
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } || (part.length > 1 && part[0] == '0')) return null
        val octet = part.toIntOrNull() ?: return null
        if (octet !in 0..255) return null
        value = (value shl 8) or octet.toLong()
    }
    return Ipv4Address(value)
}

/** /31 and /32 have no directed broadcast; invalid prefixes are rejected too. */
fun directedBroadcast(ip: Ipv4Address, prefix: Int): Ipv4Address? {
    if (prefix !in 0..30) return null
    return Ipv4Address(ip.value or ((1L shl (32 - prefix)) - 1))
}

fun isValidHost(input: String): Boolean {
    if (input.isEmpty() || input.length > 253) return false
    if (parseIpv4(input) != null) return true
    // Numeric-looking addresses must not fall through to DNS hostname parsing.
    if (input.all { it in '0'..'9' || it == '.' }) return false
    return input.split('.').all { label ->
        label.length in 1..63 && label.first().isAsciiLetterOrDigit() && label.last().isAsciiLetterOrDigit() &&
            label.all { it.isAsciiLetterOrDigit() || it == '-' }
    }
}
private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
private fun validPort(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' } && (text.toIntOrNull()?.let { it in 1..65535 } == true)

fun validateDevice(draft: WolDeviceDraft, original: WolDevice? = null): DeviceValidation {
    val errors = mutableSetOf<DeviceField>()
    if (draft.name.trim().isEmpty() || (draft.name.trim().length > 100 && draft.name != original?.name)) errors += DeviceField.NAME
    if (draft.note.length > 1000 && draft.note != original?.note) errors += DeviceField.NOTE
    if (parseMac(draft.mac) == null) errors += DeviceField.MAC
    if (draft.targetMode == TargetMode.CUSTOM && !isValidHost(draft.customTarget.trim())) errors += DeviceField.CUSTOM_TARGET
    if (!validPort(draft.port)) errors += DeviceField.PORT
    if (draft.probeHost.isNotBlank() && !isValidHost(draft.probeHost.trim())) errors += DeviceField.PROBE_HOST
    if (draft.probePort.isNotEmpty() && (!validPort(draft.probePort) || draft.probeHost.isBlank())) errors += DeviceField.PROBE_PORT
    fun inRange(text: String, range: IntRange) = text.isNotEmpty() && text.all { it in '0'..'9' } && text.toIntOrNull() in range
    if (!inRange(draft.repeatCount, 1..10)) errors += DeviceField.REPEAT_COUNT
    if (!inRange(draft.repeatIntervalMs, 50..2000)) errors += DeviceField.REPEAT_INTERVAL
    if (!inRange(draft.probeDelaySeconds, 1..120)) errors += DeviceField.PROBE_DELAY
    if (draft.probeEnabled && draft.probeHost.isBlank()) errors += DeviceField.PROBE_HOST
    return DeviceValidation(errors)
}

internal fun WolDevice.toDraft() = WolDeviceDraft(id, name, mac, targetMode, customTarget.orEmpty(), port.toString(), probeHost.orEmpty(), probePort?.toString().orEmpty(), note.orEmpty(), repeatCount.toString(), repeatIntervalMs.toString(), probeEnabled, probeDelaySeconds.toString())

/** Validate the complete snapshot before accepting persisted data; never salvage by dropping rows. */
fun validateStoredDevices(devices: List<WolDevice>): Boolean =
    devices.all { it.id.isNotBlank() && validateDevice(it.toDraft(), it).isValid } &&
        devices.map { it.id }.distinct().size == devices.size
