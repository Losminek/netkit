package cz.netkit.shared.ios

import cz.netkit.shared.lan.*

/** Small Objective-C export surface; all wire parsing and validation stay in commonMain. */
class IosNetworkDomain {
    fun ports(input: String): List<Int>? = parsePorts(input, maximum = 4096)

    fun confirmationReasons(address: String, ownCidr: String?, portCount: Int): List<String> {
        val range = Ipv4Subnet.parse("$address/32") ?: return listOf("INVALID_TARGET")
        if (portCount !in 0..4096) return listOf("INVALID_PORT_COUNT")
        return targetConfirmationReasons(range, ownCidr?.let(Ipv4Subnet::parse), portCount).map { it.name }
    }

    fun dnsQuery(host: String, type: Int, id: Int): ByteArray? {
        if (type !in listOf(DNS_TYPE_A, DNS_TYPE_PTR, DNS_TYPE_TXT, DNS_TYPE_SRV) || id !in 0..65535) return null
        val name = dnsName(host) ?: return null
        // encodeDnsQuery is also used by mDNS; unicast DNS requests recursion explicitly.
        return encodeDnsQuery(id, listOf(DnsQuestion(name, type))).also { it[2] = 0x01 }
    }

    /** Null means a malformed, mismatched, truncated or unsuccessful response, never an empty success. */
    fun dnsResponse(packet: ByteArray, id: Int, host: String, type: Int): List<String>? {
        if (packet.size > 65_535 || id !in 0..65535) return null
        val expected = dnsName(host) ?: return null
        val message = parseDnsMessage(packet) ?: return null
        if (!message.isResponse || message.opcode != 0 || message.rcode != 0 || message.flags and 0x0200 != 0 || message.id != id) return null
        val question = message.questions.singleOrNull() ?: return null
        if (!question.name.matches(expected.labels) || question.type != type || question.dnsClass != DNS_CLASS_IN) return null
        return message.records.filter { it.name.matches(expected.labels) && it.type == type && it.dnsClass == DNS_CLASS_IN }.mapNotNull { record ->
            when (val data = record.data) {
                is DnsRecordData.A -> data.address
                is DnsRecordData.Ptr -> safePtrName(data.target.text)
                is DnsRecordData.Txt -> data.entries.joinToString(" ") { sanitizeLanText(it) }
                is DnsRecordData.Srv -> "${data.priority} ${data.weight} ${data.port} ${sanitizeLanText(data.target.text)}"
                else -> null
            }
        }
    }

    fun icmpReplyMatches(request: ByteArray, reply: ByteArray): Boolean =
        matchesIcmpEchoReply(request, reply, reply.size)

    /** NWBrowser supplies a DNS-SD instance label; reuse the shared sanitization and name model. */
    fun mdnsName(raw: String): LanDeviceName? = lanDeviceName(LanNameSource.MDNS, raw)
}
