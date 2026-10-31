package cz.netkit.shared.lan

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Strict numeric IPv4 parsing: never triggers DNS. */
fun ipv4Number(input: String): Long? {
    val parts = input.split('.')
    if (parts.size != 4) return null
    return parts.fold(0L) { value, part ->
        if (part.isEmpty() || part.length > 3 || part.length > 1 && part.startsWith('0') || part.any { it !in '0'..'9' }) return null
        val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        (value shl 8) or octet.toLong()
    }
}
fun ipv4Text(value: Long): String = (3 downTo 0).joinToString(".") { ((value shr (it * 8)) and 255).toString() }

data class Ipv4Subnet(val network: Long, val prefix: Int) {
    init { require(prefix in 0..32 && network in 0..0xffffffffL) }
    val size: Long get() = 1L shl (32 - prefix)
    val hostCount: Long get() = if (prefix >= 31) size else size - 2
    fun contains(ip: String): Boolean = ipv4Number(ip)?.let { it >= network && it < network + size } == true
    fun hosts(maximum: Int = 4096): List<String> {
        require(hostCount <= maximum) { "HOST_LIMIT" }
        val first = if (prefix >= 31) network else network + 1
        return List(hostCount.toInt()) { ipv4Text(first + it) }
    }
    override fun toString(): String = "${ipv4Text(network)}/$prefix"
    companion object {
        fun parse(input: String): Ipv4Subnet? {
            val parts = input.trim().split('/')
            if (parts.size != 2) return null
            val ip = ipv4Number(parts[0]) ?: return null
            val prefix = parts[1].toIntOrNull()?.takeIf { it in 0..32 } ?: return null
            val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
            return Ipv4Subnet(ip and mask, prefix)
        }
    }
}
fun isLocalAddress(ip: String): Boolean {
    val n = ipv4Number(ip) ?: return false
    return listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16")
        .any { Ipv4Subnet.parse(it)!!.contains(ipv4Text(n)) }
}
/** Whole range must be within one allowed block, not merely its first address. */
fun requiresScanConfirmation(range: Ipv4Subnet, own: Ipv4Subnet?): Boolean = requiresTargetConfirmation(range, own)

val commonPorts: List<Int> = listOf(7,9,13,21,22,23,25,37,53,79,80,81,88,110,111,113,119,123,135,137,138,139,143,161,179,389,427,443,445,465,500,512,513,514,515,548,554,587,631,636,873,902,989,990,993,995,1025,1080,1194,1433,1434,1521,1701,1723,1883,1900,2049,2082,2083,2086,2087,2181,2222,2375,2376,2483,2484,3000,3128,3268,3306,3389,3690,4369,5000,5001,5060,5222,5432,5672,5900,5985,5986,6379,6443,6667,7001,8000,8008,8080,8081,8088,8090,8443,8883,8888,9000,9090,9100,9200,9418,9999,10000,11211,27017)
fun parsePorts(input: String, maximum: Int = 4096): List<Int>? {
    val result = mutableSetOf<Int>()
    for (piece in input.split(',')) {
        val part = piece.trim().split('-')
        if (part.size !in 1..2 || part.any { it.isEmpty() || it.any { digit -> digit !in '0'..'9' } }) return null
        val start = part[0].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val end = if (part.size == 2) part[1].toIntOrNull()?.takeIf { it in start..65535 } ?: return null else start
        if (end - start + 1 > maximum) return null
        result.addAll(start..end)
        if (result.size > maximum) return null
    }
    return result.sorted()
}
fun portService(port: Int): String = mapOf(21 to "FTP",22 to "SSH",23 to "Telnet",25 to "SMTP",53 to "DNS",80 to "HTTP",110 to "POP3",123 to "NTP",139 to "NetBIOS",143 to "IMAP",161 to "SNMP",389 to "LDAP",443 to "HTTPS",445 to "SMB",465 to "SMTPS",587 to "SMTP",631 to "IPP",636 to "LDAPS",873 to "rsync",993 to "IMAPS",995 to "POP3S",1883 to "MQTT",2049 to "NFS",2375 to "Docker",3306 to "MySQL",3389 to "RDP",5432 to "PostgreSQL",5900 to "VNC",6379 to "Redis",8080 to "HTTP-alt",8443 to "HTTPS-alt",9100 to "Printing")[port].orEmpty()

data class PingReply(val milliseconds: Double?, val hop: String? = null, val ttlExceeded: Boolean = false)
/** Tolerates iputils/toybox text and malformed/unsupported output without claiming a reply. */
fun parsePingOutput(output: String, exitCode: Int): PingReply {
    val time = Regex("time[=<]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*ms", RegexOption.IGNORE_CASE)
        .find(output)?.groupValues?.get(1)?.toDoubleOrNull()
    val exceeded = output.contains("exceeded", true) || output.contains("Time to live", true)
    // Only TTL error lines identify a hop; "PING ... from <local IP>" is a header.
    val hopPattern = Regex("^From\\s+(?:[^\\s(]+\\s+\\()?([0-9]+(?:\\.[0-9]+){3})(?=[\\s):]|$)")
    val hop = output.lineSequence().map(String::trim)
        .filter { it.startsWith("From ") && (it.contains("exceeded", true) || it.contains("Time to live", true)) }
        .mapNotNull { hopPattern.find(it)?.groupValues?.get(1) }
        .firstOrNull { ipv4Number(it) != null }
    return PingReply(if (exitCode == 0) time else null, hop, exceeded)
}
data class PingStatistics(val sent: Int, val received: Int, val lossPercent: Double, val minimum: Double?, val average: Double?, val maximum: Double?, val jitter: Double?)
fun pingStatistics(samples: List<Double?>): PingStatistics {
    val valid = samples.filterNotNull()
    val differences = samples.zipWithNext().mapNotNull { (a,b) -> if (a != null && b != null) kotlin.math.abs(a-b) else null }
    return PingStatistics(samples.size, valid.size, if (samples.isEmpty()) 0.0 else 100.0 * (samples.size-valid.size)/samples.size,
        valid.minOrNull(), valid.takeIf { it.isNotEmpty() }?.average(), valid.maxOrNull(), differences.takeIf { it.isNotEmpty() }?.average())
}
@Serializable
data class LanResult(val address: String, val name: String = "", val method: String = "", val milliseconds: Double? = null, val port: Int? = null, val detail: String = "", val sequence: Long? = null, val elapsedMs: Long? = null, val routeMayBeOutsideWifi: Boolean = false)
enum class LanExport { TEXT, CSV, JSON }
fun exportLan(results: List<LanResult>, format: LanExport): String {
    val rows = results.map { row -> row.copy(milliseconds = row.milliseconds?.let { kotlin.math.round(it * 1000.0) / 1000.0 }) }
    return when(format) {
    LanExport.JSON -> Json { prettyPrint = true; encodeDefaults = true }.encodeToString(rows)
    LanExport.CSV -> "address,name,method,milliseconds,port,detail,sequence,elapsedMs,routeMayBeOutsideWifi\n" + rows.joinToString("\n") { row ->
        listOf(row.address,row.name,row.method,row.milliseconds?.toString().orEmpty(),row.port?.toString().orEmpty(),row.detail,row.sequence?.toString().orEmpty(),row.elapsedMs?.toString().orEmpty(),row.routeMayBeOutsideWifi.toString())
            .joinToString(",") { csvCell(it) }
    }
    LanExport.TEXT -> rows.joinToString("\n") { listOf(it.address,it.name,it.method,it.milliseconds?.let { time -> "$time ms" }.orEmpty(),it.port?.toString().orEmpty(),it.detail,it.sequence?.let { sequence -> "#$sequence" }.orEmpty(),it.elapsedMs?.let { elapsed -> "t=${elapsed}ms" }.orEmpty(),if (it.routeMayBeOutsideWifi) "routeMayBeOutsideWifi=true" else "").filter(String::isNotEmpty).joinToString(" · ") { text -> sanitizeLanText(text, 1024) } }
}

}

private fun csvCell(value: String): String {
    // Names and passive banners are untrusted; do not turn them into spreadsheet formulas.
    val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'" + value else value
    return "\"${safe.replace("\"", "\"\"")}\""
}
