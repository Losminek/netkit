package cz.netkit.shared.wifi

/**
 * Offline vendor lookup from the IEEE OUI Public Listing (MA-L 24 bit, MA-M 28 bit, MA-S 36 bit),
 * as written by `tools/update_oui.py`: `#` comment lines and `PREFIX<TAB>BITS<TAB>NAME`.
 *
 * [vendorFor] takes the longest matching prefix (36 → 28 → 24 bits). A locally administered
 * (0x02 bit of the first octet) or multicast (0x01 bit) address has no IEEE owner, so it yields
 * `null`, as does any input that is not a 48-bit MAC with `:`, `-` or no separators.
 * Pure Kotlin; the platform reads the text (Android: asset on Dispatchers.IO, once).
 */
class OuiDatabase private constructor(private val tables: List<OuiTable>) {
    /** Number of prefixes (all three registries). */
    val size: Int get() = tables.sumOf { it.prefixes.size }

    fun vendorFor(mac: String?): String? {
        val value = parseMac48(mac ?: return null) ?: return null
        val firstOctet = (value ushr 40).toInt()
        if (firstOctet and 0x03 != 0) return null
        for (table in tables) table.find(value ushr (48 - table.bits))?.let { return it }
        return null
    }

    companion object {
        val EMPTY = OuiDatabase(emptyList())
        private val BITS = intArrayOf(36, 28, 24)

        /**
         * Parses the asset text. Malformed lines are skipped (the file comes from the build, but a
         * damaged one must not crash the Wi-Fi screen); a repeated prefix keeps its first name.
         */
        fun parse(text: String): OuiDatabase {
            val entries = BITS.associateWith { mutableListOf<Pair<Long, String>>() }
            val names = HashMap<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.removeSuffix("\r")
                if (line.isEmpty() || line.startsWith('#')) continue
                val first = line.indexOf('\t')
                val second = if (first < 0) -1 else line.indexOf('\t', first + 1)
                if (second < 0 || line.indexOf('\t', second + 1) >= 0) continue
                val bits = line.substring(first + 1, second).toIntOrNull() ?: continue
                val list = entries[bits] ?: continue
                val hex = line.substring(0, first)
                if (hex.length != bits / 4) continue
                val prefix = parseHex(hex) ?: continue
                val name = line.substring(second + 1).trim()
                if (name.isEmpty()) continue
                list += prefix to names.getOrPut(name) { name }
            }
            // sortedBy is stable, so for a repeated prefix the first line of the file wins.
            val tables = BITS.map { bits -> OuiTable.of(bits, entries.getValue(bits).sortedBy { it.first }) }
                .filter { it.prefixes.isNotEmpty() }
            return OuiDatabase(tables)
        }
    }
}

/** One registry: sorted prefixes with their names, binary search. */
private class OuiTable(val bits: Int, val prefixes: LongArray, val names: Array<String>) {
    fun find(prefix: Long): String? {
        var low = 0
        var high = prefixes.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val value = prefixes[middle]
            when {
                value < prefix -> low = middle + 1
                value > prefix -> high = middle - 1
                else -> return names[middle]
            }
        }
        return null
    }

    companion object {
        /** [sorted] is ordered by prefix; a repeated prefix keeps its first (stable sort) name. */
        fun of(bits: Int, sorted: List<Pair<Long, String>>): OuiTable {
            val unique = ArrayList<Pair<Long, String>>(sorted.size)
            for (entry in sorted) if (unique.isEmpty() || unique.last().first != entry.first) unique += entry
            return OuiTable(bits, LongArray(unique.size) { unique[it].first }, Array(unique.size) { unique[it].second })
        }
    }
}

/** 48-bit MAC as `AA:BB:CC:DD:EE:FF`, `AA-BB-CC-DD-EE-FF` (one separator kind) or `AABBCCDDEEFF`. */
internal fun parseMac48(text: String): Long? {
    val mac = text.trim()
    val digits = when (mac.length) {
        12 -> mac
        17 -> {
            val separator = mac[2]
            if (separator != ':' && separator != '-') return null
            for (index in 2 until 17 step 3) if (mac[index] != separator) return null
            buildString(12) { for (index in 0 until 17) if (index % 3 != 2) append(mac[index]) }
        }
        else -> return null
    }
    return parseHex(digits)
}

/** ASCII hex only (no Unicode digits, no sign); at most 15 digits so the value fits a Long. */
private fun parseHex(text: String): Long? {
    if (text.isEmpty() || text.length > 15) return null
    var value = 0L
    for (char in text) {
        val digit = when (char) {
            in '0'..'9' -> char - '0'
            in 'a'..'f' -> char - 'a' + 10
            in 'A'..'F' -> char - 'A' + 10
            else -> return null
        }
        value = (value shl 4) or digit.toLong()
    }
    return value
}
