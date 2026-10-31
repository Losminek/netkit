package cz.netkit.shared.wol

private val webbExportColumns = listOf("machine_id", "machine_name", "machine_broadcastip",
    "machine_mac", "machine_port", "machine_colour", "machine_sort", "group_id", "group_name",
    "group_sort", "machine_ip", "secure_on", "machine_ping_port", "machine_ping_ssid")

/**
 * UTF-8 CSV for Webb Wake On Lan, with every field quoted and no BOM.
 * [groupOf] returns the group name of a device (e.g. parsed from its note), or null.
 * Missing or blank groups use [defaultGroup]. Generated IDs use the lowest free number from zero.
 * CSV cannot preserve automatic targets, ICMP probes, notes or repeat/delay settings.
 * Addresses are copied without DNS lookup; the existing importer accepts only IPv4 and applies
 * its record/field/byte limits. An empty list produces only the header.
 */
fun encodeWolCsvExport(
    devices: List<WolDevice>,
    groupOf: (WolDevice) -> String?,
    defaultGroup: String,
): ByteArray {
    // Keep numeric suffixes as strings: imported IDs can have up to 59 digits.
    val originalIds = devices.map { device ->
        device.id.takeIf { it.startsWith("webb-") }?.removePrefix("webb-")
            ?.takeIf { it.length in 1..59 && it.all { char -> char in '0'..'9' } }
    }
    fun numericKey(id: String) = id.trimStart('0').ifEmpty { "0" }
    val reserved = originalIds.filterNotNull().map(::numericKey).toMutableSet()
    val used = mutableSetOf<String>()
    var nextId = 0
    val ids = originalIds.map { original ->
        if (original != null && used.add(numericKey(original))) original
        else {
            while (nextId.toString() in reserved) nextId++
            (nextId++).toString().also { reserved.add(it); used.add(it) }
        }
    }
    val groups = devices.withIndex().groupBy { (_, device) ->
        groupOf(device)?.takeIf { it.isNotBlank() } ?: defaultGroup
    }
    return buildString {
        fun record(fields: List<String>) {
            append(fields.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" })
            append('\n')
        }
        record(webbExportColumns)
        groups.entries.forEachIndexed { groupIndex, (name, members) ->
            val groupId = groupIndex.toString()
            record(List(7) { "" } + listOf(groupId, name, groupId, "", ""))
            members.forEach { (index, device) ->
                val target = when (device.targetMode) {
                    TargetMode.CUSTOM -> device.customTarget.orEmpty()
                    TargetMode.AUTO_BROADCAST, TargetMode.LIMITED_BROADCAST -> "255.255.255.255"
                }
                record(listOf(ids[index], device.name, target,
                    requireNotNull(parseMac(device.mac)).toString(), device.port.toString(),
                    "#33B5E5", index.toString(), groupId, "", "", device.probeHost.orEmpty(), "",
                    if (device.probeEnabled) device.probePort?.toString() ?: "-1" else "-1", ""))
            }
        }
    }.encodeToByteArray()
}

/**
 * Inverse of the import's note: "Skupina: Chata" -> "Chata" for the given localized [prefix].
 * Matches the prefix exactly (including case and any spaces); trims only the group name.
 */
fun wolGroupFromNote(note: String?, prefix: String): String? =
    if (prefix.isNotEmpty() && note != null && note.startsWith(prefix))
        note.removePrefix(prefix).trim().takeIf { it.isNotEmpty() }
    else null
