package cz.netkit.shared.lan

/*
 * mDNS identification for the Devices screen (M2): which queries to send and how to read
 * the answers. The Android side sends the queries as RFC 6762 6.7 "legacy unicast"
 * (ephemeral source port), so responders reply by unicast to that port and the app needs
 * neither a multicast group membership nor a WifiManager.MulticastLock.
 */

const val MDNS_PORT = 5353
const val MDNS_IPV4_GROUP = "224.0.0.251"
/** Reverse questions per datagram: ~22 B each with compression keeps a packet under ~800 B. */
const val MDNS_MAX_QUESTIONS_PER_PACKET = 32
/** Hosts per identification run; the Devices range is at most /24. */
const val MDNS_MAX_HOSTS = 254
/** Service types kept per device. */
const val MDNS_MAX_SERVICES = 16

private val localSuffix = listOf("local")
private val reverseSuffix = listOf("in-addr", "arpa")
private val servicesName = listOf("_services", "_dns-sd", "_udp", "local")
private val serviceLabel = Regex("_[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

/** `d.c.b.a.in-addr.arpa` for IPv4 `a.b.c.d`; null for anything that is not strict IPv4. */
fun ipv4ReverseName(address: String): DnsName? {
    val number = ipv4Number(address) ?: return null
    return DnsName(ipv4Text(number).split('.').reversed() + reverseSuffix)
}

/** Inverse of [ipv4ReverseName]: only exactly four decimal octets (no leading zeros) + in-addr.arpa. */
fun ipv4FromReverseName(name: DnsName): String? {
    if (name.labels.size != 6 || !name.endsWith(reverseSuffix)) return null
    val address = name.labels.take(4).reversed().joinToString(".")
    return address.takeIf { ipv4Number(it) != null }
}

/**
 * Reverse PTR questions (QU bit set) for [addresses], split into datagrams of at most
 * [MDNS_MAX_QUESTIONS_PER_PACKET] questions. Invalid or duplicate addresses are skipped;
 * at most [MDNS_MAX_HOSTS]. Each datagram carries its own transaction ID from [nextId].
 */
fun mdnsReverseQueries(addresses: List<String>, nextId: () -> Int): List<Pair<Int, ByteArray>> =
    addresses.asSequence().filter { shouldQueryLanPtr(it) }.distinct().take(MDNS_MAX_HOSTS)
        .mapNotNull(::ipv4ReverseName).chunked(MDNS_MAX_QUESTIONS_PER_PACKET).map { names ->
            val id = nextId()
            id to encodeDnsQuery(id, names.map { DnsQuestion(it, DNS_TYPE_PTR, unicastResponse = true) })
        }.toList()

/** One DNS-SD service type enumeration question (RFC 6763 9) with the QU bit. */
fun mdnsServicesQuery(id: Int): ByteArray =
    encodeDnsQuery(id, listOf(DnsQuestion(DnsName(servicesName), DNS_TYPE_PTR, unicastResponse = true)))

/**
 * A `.local` name for one address and how trustworthy it is: an answer from the address itself
 * ([fromSender]) beats one relayed by another host (e.g. a sleep proxy), and within that a
 * reverse PTR ([reverse]) beats a forward A record.
 */
data class MdnsHostName(val name: String, val fromSender: Boolean, val reverse: Boolean) {
    val rank: Int get() = (if (fromSender) 2 else 0) + (if (reverse) 1 else 0)
}

/** Address → best `.local` host name and address → service types (e.g. `_ipp._tcp`). */
data class MdnsFindings(val names: Map<String, MdnsHostName> = emptyMap(), val services: Map<String, Set<String>> = emptyMap()) {
    /** Higher [MdnsHostName.rank] wins regardless of datagram order; on a tie the earlier answer stays. */
    fun plus(other: MdnsFindings): MdnsFindings = MdnsFindings(
        // maxBy keeps the first of equal ranks, i.e. this (earlier) answer.
        names = (names.keys + other.names.keys).associateWith { key -> listOfNotNull(names[key], other.names[key]).maxBy { it.rank } },
        services = (services.keys + other.services.keys).associateWith { key ->
            (services[key].orEmpty() + other.services[key].orEmpty()).sorted().take(MDNS_MAX_SERVICES).toSet()
        },
    )
}

/** `_ipp._tcp` for `_ipp._tcp.local`; null for anything that is not a service type name. */
fun mdnsServiceType(name: DnsName): String? {
    if (name.labels.size != 3 || !name.endsWith(localSuffix)) return null
    val (service, protocol) = name.labels
    if (!serviceLabel.matches(service) || service.length > 63) return null
    if (!protocol.equals("_tcp", true) && !protocol.equals("_udp", true)) return null
    return "${service.lowercase()}.${protocol.lowercase()}"
}

/** A plain `.local` host name (no service labels); returns it without a trailing dot. */
private fun mdnsHostName(name: DnsName): String? {
    if (name.labels.size < 2 || !name.endsWith(localSuffix)) return null
    if (name.labels.any { it.isEmpty() || it.startsWith('_') }) return null
    return name.text
}

/**
 * Reads one response datagram received from [source] (already checked to come from port
 * 5353). Ignored entirely unless it is a positive standard response from inside [range].
 *
 * - Reverse PTR `d.c.b.a.in-addr.arpa → host.local` and A `host.local → address`: name for
 *   that address if it is in [range], ranked by [MdnsHostName.rank] (sender's own answer
 *   first, reverse PTR before A; answers about other addresses only as a fallback).
 * - PTR from `_services._dns-sd._udp.local` or from a service type (`_ipp._tcp.local → …`):
 *   the service type, attributed only to [source] (the responder itself).
 *
 * [expectedIds]: legacy unicast replies echo the query ID (RFC 6762 6.7); ID 0 is accepted
 * because some responders do not echo it.
 */
fun interpretMdnsResponse(message: DnsMessage, source: String, range: Ipv4Subnet, expectedIds: Set<Int>): MdnsFindings {
    if (!message.isResponse || message.opcode != 0 || message.rcode != 0) return MdnsFindings()
    if (!range.contains(source) || (message.id != 0 && message.id !in expectedIds)) return MdnsFindings()
    var names = MdnsFindings()
    fun name(address: String, host: String, reverse: Boolean) {
        if (range.contains(address)) names = names.plus(MdnsFindings(mapOf(address to MdnsHostName(host, address == source, reverse))))
    }
    val services = LinkedHashSet<String>()
    for (record in message.records) {
        if (record.dnsClass != DNS_CLASS_IN) continue
        when (val data = record.data) {
            is DnsRecordData.Ptr -> {
                val address = ipv4FromReverseName(record.name)
                when {
                    address != null -> mdnsHostName(data.target)?.let { name(address, it, reverse = true) }
                    record.name.matches(servicesName) -> mdnsServiceType(data.target)?.let(services::add)
                    else -> mdnsServiceType(record.name)?.let(services::add)
                }
            }
            is DnsRecordData.A -> mdnsHostName(record.name)?.let { name(data.address, it, reverse = false) }
            else -> Unit
        }
    }
    val typed = services.sorted().take(MDNS_MAX_SERVICES).toSet()
    return MdnsFindings(names.names, if (typed.isEmpty()) emptyMap() else mapOf(source to typed))
}
