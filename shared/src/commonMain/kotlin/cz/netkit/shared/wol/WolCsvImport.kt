package cz.netkit.shared.wol

data class WolCsvImport(val devices: List<WolDevice>, val skipped: List<WolCsvSkip>, val secureOnDropped: Int)
data class WolCsvSkip(val machineName: String, val reason: WolCsvSkipReason)
enum class WolCsvSkipReason { ID, DUPLICATE_ID, NAME, MAC, PORT, TARGET_IP, PROBE_PORT, PROBE_IP, NOTE }

private val requiredWebbColumns = setOf("machine_id", "machine_name", "machine_broadcastip",
    "machine_mac", "machine_port", "group_id", "group_name", "machine_ip", "machine_ping_port")

private fun importText(bytes: ByteArray): String {
    if (bytes.size > WOL_TRANSFER_LIMIT) throw WolImportException(WolImportFailure.SIZE)
    return try { bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF") }
    catch (_: CharacterCodingException) { throw WolImportException(WolImportFailure.UTF8) }
}

/**
 * JSON keeps its strict whole-document validation; CSV reports rejected device rows.
 * [groupNote] turns a CSV group name into the device note (localized by the caller).
 */
fun decodeWolImportAny(bytes: ByteArray, now: Long, groupNote: (String) -> String): WolCsvImport =
    if (importText(bytes).firstOrNull { !it.isWhitespace() } == '{')
        WolCsvImport(decodeWolImport(bytes), emptyList(), 0)
    else decodeWolCsvImport(bytes, now, groupNote)

/** Most fields one CSV record may have (the Webb export has 14); more is a SIZE failure. */
const val WOL_CSV_MAX_FIELDS = 64

/**
 * [text] with every run of control characters (Unicode Cc, e.g. CR, LF, TAB, NEL) and line or
 * paragraph separators replaced by one space, trimmed. For names shown on one line.
 */
internal fun singleLineText(text: String): String = buildString(text.length) {
    var pendingSpace = false
    text.forEach { c ->
        val breaking = c.category == CharCategory.CONTROL || c.category == CharCategory.LINE_SEPARATOR ||
            c.category == CharCategory.PARAGRAPH_SEPARATOR
        if (breaking) pendingSpace = true
        else {
            if (pendingSpace && isNotEmpty()) append(' ')
            pendingSpace = false
            append(c)
        }
    }
}.trim()

/**
 * RFC 4180 records: at most 500 data records (groups included), plus the header, each with at
 * most [WOL_CSV_MAX_FIELDS] fields of at most 1000 characters.
 */
private fun csvRecords(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var pos = 0
    fun format(): Nothing = throw WolImportException(WolImportFailure.FORMAT)
    while (pos < text.length) {
        if (rows.size == 501) throw WolImportException(WolImportFailure.SIZE)
        val row = mutableListOf<String>()
        while (true) {
            val field = StringBuilder()
            fun append(char: Char) {
                if (field.length == 1000) throw WolImportException(WolImportFailure.SIZE)
                field.append(char)
            }
            if (pos < text.length && text[pos] == '"') {
                pos++
                var closed = false
                while (pos < text.length) {
                    val char = text[pos++]
                    if (char != '"') append(char)
                    else if (pos < text.length && text[pos] == '"') { append('"'); pos++ }
                    else { closed = true; break }
                }
                if (!closed) format()
            } else {
                while (pos < text.length && text[pos] !in ",\r\n") {
                    if (text[pos] == '"') format()
                    append(text[pos++])
                }
            }
            if (row.size == WOL_CSV_MAX_FIELDS) throw WolImportException(WolImportFailure.SIZE)
            row += field.toString()
            if (pos == text.length) break
            when (text[pos++]) {
                ',' -> continue
                '\n' -> break
                '\r' -> {
                    if (pos == text.length || text[pos++] != '\n') format()
                    break
                }
                else -> format()
            }
        }
        rows += row
    }
    return rows
}

/** CSV export of Webb Wake On LAN; [groupNote] turns a group name into the (localized) device note. */
fun decodeWolCsvImport(bytes: ByteArray, now: Long, groupNote: (String) -> String): WolCsvImport {
    val records = csvRecords(importText(bytes))
    val header = records.firstOrNull() ?: throw WolImportException(WolImportFailure.FORMAT)
    if (!header.containsAll(requiredWebbColumns) || header.distinct().size != header.size)
        throw WolImportException(WolImportFailure.FORMAT)
    val columns = header.withIndex().associate { it.value to it.index }
    fun List<String>.field(name: String): String = columns[name]?.let { getOrNull(it) }.orEmpty()
    val rows = records.drop(1)
    // Resolve groups before devices, including exports that put group rows last.
    val groups = rows.filter { it.field("machine_id").isEmpty() }
        .associate { it.field("group_id") to singleLineText(it.field("group_name")) }
    val devices = mutableListOf<Pair<Long, WolDevice>>()
    val skipped = mutableListOf<WolCsvSkip>()
    val ids = mutableSetOf<String>()
    var secureOnDropped = 0
    rows.forEachIndexed { index, row ->
        val machineId = row.field("machine_id")
        if (machineId.isEmpty()) return@forEachIndexed
        // Only the count escapes this decoder; never include source rows in errors.
        if (row.field("secure_on").isNotEmpty()) secureOnDropped++
        val name = singleLineText(row.field("machine_name"))
        fun skip(reason: WolCsvSkipReason) { skipped += WolCsvSkip(name, reason) }
        if (machineId.length > 59 || machineId.any { it !in '0'..'9' }) {
            skip(WolCsvSkipReason.ID); return@forEachIndexed
        }
        if (name.isEmpty() || name.length > 100) { skip(WolCsvSkipReason.NAME); return@forEachIndexed }
        val mac = parseMac(row.field("machine_mac"))
        if (mac == null) { skip(WolCsvSkipReason.MAC); return@forEachIndexed }
        val portText = row.field("machine_port").trim()
        val port = portText.toIntOrNull()
        if (portText.any { it !in '0'..'9' } || port == null || port !in 1..65535) {
            skip(WolCsvSkipReason.PORT); return@forEachIndexed
        }
        val target = parseIpv4(row.field("machine_broadcastip").trim())
        if (target == null) { skip(WolCsvSkipReason.TARGET_IP); return@forEachIndexed }
        val pingText = row.field("machine_ping_port").trim()
        val pingPort = pingText.toIntOrNull()
        if (pingPort == null || pingPort !in -1..65535 ||
            (pingText != "-1" && pingText.any { it !in '0'..'9' })) {
            skip(WolCsvSkipReason.PROBE_PORT); return@forEachIndexed
        }
        val probeEnabled = pingPort > 0
        // The device address is kept even with the check off, so a CSV export can write it back.
        val probeHost = parseIpv4(row.field("machine_ip").trim())
        if (probeEnabled && probeHost == null) { skip(WolCsvSkipReason.PROBE_IP); return@forEachIndexed }
        val note = groups[row.field("group_id")]?.takeIf { it.isNotEmpty() }?.let(groupNote)
        if ((note?.length ?: 0) > 1000) { skip(WolCsvSkipReason.NOTE); return@forEachIndexed }
        val id = "webb-$machineId"
        if (!ids.add(id)) { skip(WolCsvSkipReason.DUPLICATE_ID); return@forEachIndexed }
        val device = WolDevice(id = id, name = name, mac = mac.toString(), targetMode = TargetMode.CUSTOM,
            customTarget = target.toString(), port = port, probeHost = probeHost?.toString(),
            probePort = pingPort.takeIf { probeEnabled }, probeEnabled = probeEnabled, note = note,
            createdAt = now, updatedAt = now)
        devices += (row.field("machine_sort").trim().toLongOrNull() ?: index.toLong()) to device
    }
    val sorted = devices.sortedBy { it.first }.map { it.second }
    if (sorted.isEmpty() || !validateImportedDevices(sorted)) throw WolImportException(WolImportFailure.INVALID_DEVICE)
    return WolCsvImport(sorted, skipped, secureOnDropped)
}
