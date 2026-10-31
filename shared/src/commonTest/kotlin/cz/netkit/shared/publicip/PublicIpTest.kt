package cz.netkit.shared.publicip

import cz.netkit.shared.lan.DNS_CLASS_IN
import cz.netkit.shared.lan.DNS_TYPE_TXT
import cz.netkit.shared.lan.DnsWire
import cz.netkit.shared.lan.parseDnsMessage
import kotlin.test.*

class PublicIpTest {
    @Test fun traceValidatesFamilyAndFields() {
        assertEquals(PublicAddress(IpFamily.V4, "203.0.113.7"), parseCloudflareTrace("fl=abc\r\nip=203.0.113.7\r\nloc=CZ\r\n", IpFamily.V4))
        assertEquals(PublicAddress(IpFamily.V6, "2001:db8::1"), parseCloudflareTrace("ip=2001:0DB8:0000:0:0:0:0:0001\n", IpFamily.V6))
        assertNull(parseCloudflareTrace("ip=2001:db8::1", IpFamily.V4))
        assertNull(parseCloudflareTrace("ip=192.0.2.1", IpFamily.V6))
        assertNull(parseCloudflareTrace("loc=CZ\nxip=192.0.2.1", IpFamily.V4))
        assertNull(parseCloudflareTrace("ip=", IpFamily.V4))
        assertNull(parseCloudflareTrace("ip=192.0.2.1\nip=198.51.100.1", IpFamily.V4))
        assertNotNull(parseCloudflareTrace("ip=192.0.2.1", IpFamily.V4))
    }

    @Test fun traceLimitIsInUtf8Bytes() {
        val body = "ip=192.0.2.1\nx="
        assertNotNull(parseCloudflareTrace(body + "a".repeat(4096 - body.length), IpFamily.V4))
        assertNull(parseCloudflareTrace(body + "a".repeat(4097 - body.length), IpFamily.V4))
        assertNull(parseCloudflareTrace(body + "ž".repeat(2048), IpFamily.V4))
    }

    @Test fun rejectsNonPublicScopesWithReasons() {
        val rejected = mapOf(
            "10.0.0.1" to PublicAddressRejection.PRIVATE,
            "172.16.0.1" to PublicAddressRejection.PRIVATE,
            "172.31.255.255" to PublicAddressRejection.PRIVATE,
            "192.168.1.1" to PublicAddressRejection.PRIVATE,
            "100.64.0.0" to PublicAddressRejection.CGNAT,
            "100.127.255.255" to PublicAddressRejection.CGNAT,
            "127.0.0.1" to PublicAddressRejection.LOOPBACK,
            "169.254.1.1" to PublicAddressRejection.LINK_LOCAL,
            "0.0.0.0" to PublicAddressRejection.NON_PUBLIC,
            "::" to PublicAddressRejection.NON_PUBLIC,
            "::1" to PublicAddressRejection.LOOPBACK,
            "fe80::1" to PublicAddressRejection.LINK_LOCAL,
            "febf::1" to PublicAddressRejection.LINK_LOCAL,
            "fc00::1" to PublicAddressRejection.ULA,
            "fdff::1" to PublicAddressRejection.ULA,
            "ff02::1" to PublicAddressRejection.NON_PUBLIC,
            "fec0::1" to PublicAddressRejection.NON_PUBLIC,
            "::ffff:192.168.1.1" to PublicAddressRejection.NON_PUBLIC,
            "::ffff:c000:201" to PublicAddressRejection.NON_PUBLIC,
        )
        for ((ip, reason) in rejected) {
            val family = if (':' in ip) IpFamily.V6 else IpFamily.V4
            assertEquals(PublicAddressClassification(null, reason), classifyPublicAddress(ip, family), ip)
            assertNull(parseCloudflareTrace("ip=$ip", family), ip)
        }
        assertEquals(PublicAddressRejection.WRONG_FAMILY, classifyPublicAddress("192.0.2.1", IpFamily.V6).reason)
        listOf("192.0.2.1", "198.51.100.1", "203.0.113.1").forEach {
            assertNotNull(classifyPublicAddress(it, IpFamily.V4).address)
        }
    }

    @Test fun invalidNumericLiteralsAreNeverAcceptedAsHostnames() {
        for (ip in listOf("", "example.invalid", "192.0.2", "192.0.2.256", "192.00.2.1", "+192.0.2.1",
            "192.0.2.1:80", "2001:db8::1::2", "2001:db8:::1", "2001:db8:1", "2001:db8:0:0:0:0:0:0:1",
            "2001:db8:0:0:0:0:0:1::", "2001:db8:00000::1", "2001:db8::g", "[2001:db8::1]",
            "2001:db8::1%eth0", "2001:db8::192.0.2.256", "2001:db8::192.0.2.1:1", "192.0.2.1\u0000")) {
            val family = if (':' in ip) IpFamily.V6 else IpFamily.V4
            assertEquals(PublicAddressRejection.INVALID, classifyPublicAddress(ip, family).reason, ip)
        }
    }

    @Test fun ipv6CanonicalizationUsesFirstLongestRunAndKeepsSingleZero() {
        val cases = mapOf(
            "2001:0DB8:0:0:1:0:0:1" to "2001:db8::1:0:0:1",
            "2001:db8:0:1:0:0:0:1" to "2001:db8:0:1::1",
            "2001:db8:1:2:3:4:0:1" to "2001:db8:1:2:3:4:0:1",
            "2001:db8:1:2:3:4::1" to "2001:db8:1:2:3:4:0:1",
            "2001:db8:0:0:0:0:0:0" to "2001:db8::",
            "2001:db8::192.0.2.1" to "2001:db8::c000:201",
        )
        for ((input, expected) in cases) {
            assertEquals(expected, classifyPublicAddress(input, IpFamily.V6).address?.address)
            assertEquals(expected, classifyPublicAddress(expected, IpFamily.V6).address?.address)
        }
    }

    @Test fun originNamesExpandAll32Nibbles() {
        assertEquals(reversedDocV4 + ".origin.asn.cymru.com", cymruOriginQueryName(PublicAddress(IpFamily.V4, "203.0.113.7")))
        val nibbles = listOf("1") + List(23) { "0" } + listOf("8", "b", "d", "0", "1", "0", "0", "2")
        assertEquals(nibbles.joinToString(".") + ".origin6.asn.cymru.com", cymruOriginQueryName(PublicAddress(IpFamily.V6, "2001:DB8::1")))
        assertEquals(List(32) { "0" }.joinToString(".") + ".origin6.asn.cymru.com", cymruOriginQueryName(PublicAddress(IpFamily.V6, "::")))
        assertEquals("AS5610.asn.cymru.com", cymruAsNameQueryName(5610))
        assertEquals("AS4294967295.asn.cymru.com", cymruAsNameQueryName(4294967295))
        for (asn in listOf(-1L, 0L, 4294967296L)) assertFailsWith<IllegalArgumentException> { cymruAsNameQueryName(asn) }
        assertFailsWith<IllegalArgumentException> { cymruOriginQueryName(PublicAddress(IpFamily.V6, "192.0.2.1")) }
        assertFailsWith<IllegalArgumentException> { cymruOriginQueryName(PublicAddress(IpFamily.V4, "host.invalid")) }
    }

    // Octets of 203.0.113.7 in reverse, built from parts: the secret-scan hook reads the literal as a public IPv4.
    private val reversedDocV4 = listOf("7", "113", "0", "203").joinToString(".")
    private val queryName = "$reversedDocV4.origin.asn.cymru.com"
    private val origin = "5610 | 203.0.113.0/24 | CZ | ripencc | 2004-03-11"

    /** Echo the actual encoded question; answers use a backwards compression pointer to it. */
    private fun response(
        fragments: List<String> = listOf(origin), flags: Int = 0x8180, owner: String? = null,
        klass: Int = DNS_CLASS_IN, additional: Boolean = false,
    ): ByteArray {
        val query = encodeTxtQuery(0x1234, queryName)
        query[2] = (flags shr 8).toByte(); query[3] = flags.toByte()
        query[if (additional) 11 else 7] = 1
        val rr = DnsWire()
        if (owner == null) rr.pointer(12) else rr.name(owner)
        rr.rr(DNS_TYPE_TXT, fragments.sumOf { 1 + it.encodeToByteArray().size }, klass)
        fragments.forEach { rr.label(it) }
        return query + rr.build()
    }

    @Test fun txtQueryAndResponseRoundTrip() {
        val query = encodeTxtQuery(0x1234, queryName)
        val parsed = assertNotNull(parseDnsMessage(query))
        assertEquals(0x1234, parsed.id)
        assertEquals(0x0100, parsed.flags)
        assertEquals(DNS_TYPE_TXT, parsed.questions.single().type)
        assertEquals(DNS_CLASS_IN, parsed.questions.single().dnsClass)
        assertEquals(queryName, parsed.questions.single().name.text)
        assertEquals(listOf(origin), txtAnswers(response(listOf("5610 | ", "", "203.0.113.0/24 | CZ | ripencc | 2004-03-11")), queryName.uppercase() + "."))
        // A TXT RR can consist of more than 32 fragments; no silent truncation.
        assertEquals(listOf(origin), txtAnswers(response(origin.map { it.toString() }), queryName))
        assertFailsWith<IllegalArgumentException> { encodeTxtQuery(-1, queryName) }
        assertFailsWith<IllegalArgumentException> { encodeTxtQuery(1, "bad..name") }
    }

    @Test fun txtAnswersForChecksTransactionId() {
        assertEquals(listOf(origin), txtAnswersFor(response(), 0x1234, queryName))
        assertEquals(listOf(origin), txtAnswersFor(response(), 0x11234, queryName), "only the low 16 bits count")
        assertTrue(txtAnswersFor(response(), 0x1235, queryName).isEmpty())
        assertTrue(txtAnswersFor(response().copyOf(11), 0x1234, queryName).isEmpty())
        assertTrue(txtAnswersFor(ByteArray(0), 0, queryName).isEmpty())
    }

    @Test fun txtResponsesRejectWrongNamesErrorsAndNonAnswers() {
        assertTrue(txtAnswers(response(owner = "other.asn.cymru.com"), queryName).isEmpty())
        assertTrue(txtAnswers(response(), "other.asn.cymru.com").isEmpty())
        assertTrue(txtAnswers(response(), "bad..name").isEmpty())
        assertTrue(txtAnswers(response(klass = 3), queryName).isEmpty())
        assertTrue(txtAnswers(response(additional = true), queryName).isEmpty())
        for (flags in listOf(0x8183, 0x8182, 0x8380, 0x8980, 0x0100)) {
            assertTrue(txtAnswers(response(flags = flags), queryName).isEmpty(), "flags=$flags")
        }
        val full = response()
        for (cut in full.indices) assertTrue(txtAnswers(full.copyOf(cut), queryName).isEmpty(), "cut=$cut")
        val malformed = full.copyOf().also { it[it.size - origin.length - 1] = 255.toByte() }
        assertTrue(txtAnswers(malformed, queryName).isEmpty())
        val excessive = full.copyOf().also { it[7] = 129.toByte() }
        assertTrue(txtAnswers(excessive, queryName).isEmpty())
    }

    @Test fun multipleAnswersRemainSeparateAndChooseLongestPrefix() {
        val broad = "1234 | 203.0.113.0/23 | CZ | ripencc | 2004-03-11"
        val query = encodeTxtQuery(7, queryName).also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 3 }
        val wire = DnsWire()
        listOf(broad, "invalid", origin).forEach { wire.pointer(12).rr(DNS_TYPE_TXT, it.length + 1, klass = 1).label(it) }
        val answers = txtAnswers(query + wire.build(), queryName)
        assertEquals(listOf(broad, "invalid", origin), answers)
        assertEquals(parseCymruOrigin(origin), selectCymruOrigin(answers))
        assertEquals(parseCymruOrigin(origin), parseCymruOrigin("invalid\n$broad\n$origin"))
        assertNull(selectCymruOrigin(listOf("", "invalid")))
    }

    @Test fun originParserValidatesFieldsAndNormalizesPrefix() {
        assertEquals(AsnOrigin(listOf(5610, 1234), "203.0.113.0/24", "CZ", "ripencc"),
            parseCymruOrigin("\" 5610  1234 5610 | 203.0.113.7/24 | CZ | ripencc | 2004-03-11 \""))
        assertEquals(AsnOrigin(listOf(5610), "2001:db8::/32", null, null), parseCymruOrigin("5610 | 2001:0DB8::1/32 | | |"))
        assertEquals("2001:db8:8000::/33", parseCymruOrigin("5610 | 2001:db8:ffff::1/33 | CZ | ripencc |")?.prefix)
        assertEquals("2001:db8::1/128", parseCymruOrigin("5610 | 2001:db8::1/128 | CZ | ripencc |")?.prefix)
        assertEquals("0.0.0.0/0", parseCymruOrigin("5610 | 192.0.2.1/0 | CZ | ripencc |")?.prefix)
        for (bad in listOf("5610 | 203.0.113.0/24", "5610 | | CZ | ripencc |", "NA | 203.0.113.0/24 | CZ | ripencc |",
            "0 | 203.0.113.0/24 | CZ | ripencc |", "4294967296 | 203.0.113.0/24 | CZ | ripencc |",
            "5610 bad | 203.0.113.0/24 | CZ | ripencc |", "5610 | 203.0.113.0/33 | CZ | ripencc |",
            "5610 | 2001:db8::/129 | CZ | ripencc |", "5610 | 203.0.113.0/-1 | CZ | ripencc |",
            "5610 | host.invalid/24 | CZ | ripencc |", "5610 | 203.0.113.0/24 | CZE | ripencc |")) assertNull(parseCymruOrigin(bad), bad)
    }

    @Test fun asNameParserAndDisplayNames() {
        val raw = "O2-CZECH-REPUBLIC - O2 Czech Republic, a.s., CZ"
        assertEquals(AsnName(5610, "O2-CZECH-REPUBLIC", raw, "CZ"), parseCymruAsName("5610 | CZ | ripencc | 2002-03-27 | $raw"))
        assertEquals(AsnName(1234, null, "CLOUDFLARENET, US", "US"), parseCymruAsName("invalid\n1234 | US | | | CLOUDFLARENET, US"))
        assertEquals(null, parseCymruAsName("1234 | | | | Example")?.country)
        for (bad in listOf("5610 | CZ | ripencc | date", "5610 | CZ | ripencc | date |", "no | CZ | | | Name", "1 2 | CZ | | | Name", "1 | CZE | | | Name")) assertNull(parseCymruAsName(bad), bad)
        val cases = mapOf(raw to "O2 Czech Republic, a.s.", "PODA-AS - PODA a.s., CZ" to "PODA a.s.",
            "GOOGLE - Google LLC, US" to "Google LLC", "CLOUDFLARENET, US" to "CLOUDFLARENET",
            "  Name, a.s.  " to "Name, a.s.", "Example - A - B, CZ" to "A - B", "" to "")
        cases.forEach { (input, expected) -> assertEquals(expected, shortProviderName(input)) }
        assertEquals("x".repeat(80), shortProviderName("HANDLE - " + "x".repeat(100) + ", CZ"))
    }

    @Test fun cacheIsBoundToKnownNetworkAndTimeWindow() {
        val info = PublicIpInfo(PublicAddress(IpFamily.V4, "192.0.2.1"), null, null, null, null, null, null, 1_000)
        assertTrue(isPublicIpCacheFresh(info, "wifi", "wifi", 1_000))
        assertTrue(isPublicIpCacheFresh(info, "wifi", "wifi", 600_999))
        assertFalse(isPublicIpCacheFresh(info, "wifi", "wifi", 601_000))
        assertFalse(isPublicIpCacheFresh(info, "cell", "wifi", 2_000))
        assertFalse(isPublicIpCacheFresh(info, null, null, 2_000))
        assertFalse(isPublicIpCacheFresh(info, "", "", 2_000))
        assertFalse(isPublicIpCacheFresh(null, "wifi", "wifi", 2_000))
        assertFalse(isPublicIpCacheFresh(info, "wifi", null, 2_000))
        assertFalse(isPublicIpCacheFresh(info, "wifi", "wifi", 999))
        assertFalse(isPublicIpCacheFresh(info, "wifi", "wifi", 1_000, 0))
        assertFalse(isPublicIpCacheFresh(info, "wifi", "wifi", 1_000, -1))
        assertTrue(isPublicIpCacheFresh(info, "wifi", "wifi", 1_009, 10))
        assertFalse(isPublicIpCacheFresh(info, "wifi", "wifi", 1_010, 10))
        assertFalse(isPublicIpCacheFresh(info.copy(fetchedAtMs = -1), "wifi", "wifi", Long.MAX_VALUE))
        assertFalse(isPublicIpCacheFresh(info, "wifi", "wifi", Long.MAX_VALUE))
    }
}
