package cz.netkit.shared.lan

import kotlin.test.*

class UpnpIdentityTest {
    private val range = Ipv4Subnet.parse("192.168.1.0/24")!!

    @Test fun mSearchIsRootDeviceDiscovery() {
        val text = ssdpMSearch().decodeToString()
        assertTrue(text.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue("HOST: 239.255.255.250:1900\r\n" in text)
        assertTrue("MAN: \"ssdp:discover\"\r\n" in text)
        assertTrue("MX: 2\r\n" in text)
        assertTrue("ST: upnp:rootdevice\r\n" in text)
        assertTrue(text.endsWith("\r\n\r\n"))
        assertFailsWith<IllegalArgumentException> { ssdpMSearch("a\r\nX: y") }
        assertFailsWith<IllegalArgumentException> { ssdpMSearch(mx = 0) }
    }

    @Test fun parsesSsdpResponseHeadersCaseInsensitively() {
        val reply = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nlocation: http://192.168.1.20:49152/desc.xml\r\n" +
            "Server: Linux/5.4 UPnP/1.0 MiniUPnPd/2.2\r\nST: upnp:rootdevice\r\nUSN: uuid:abc::upnp:rootdevice\r\n\r\n").encodeToByteArray()
        val parsed = assertNotNull(parseSsdpResponse(reply))
        assertEquals("http://192.168.1.20:49152/desc.xml", parsed.location)
        assertEquals("Linux/5.4 UPnP/1.0 MiniUPnPd/2.2", parsed.server)
        assertEquals("upnp:rootdevice", parsed.searchTarget)
        assertEquals("uuid:abc::upnp:rootdevice", parsed.usn)
    }

    @Test fun rejectsNonResponsesAndOversizedOrBinarySsdp() {
        assertNull(parseSsdpResponse("NOTIFY * HTTP/1.1\r\nLOCATION: http://192.168.1.2/\r\n\r\n".encodeToByteArray()))
        assertNull(parseSsdpResponse("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n".encodeToByteArray()))
        assertNull(parseSsdpResponse("HTTP/1.1 404 Not Found\r\n\r\n".encodeToByteArray()))
        assertNull(parseSsdpResponse(("HTTP/1.1 200 OK\r\nSERVER: " + "x".repeat(SSDP_MAX_RESPONSE_BYTES) + "\r\n\r\n").encodeToByteArray()))
        assertNull(parseSsdpResponse("HTTP/1.1 200 OK\r\nSERVER: a\u0000b\r\n\r\n".encodeToByteArray()))
        assertNull(parseSsdpResponse(ByteArray(4)))
    }

    @Test fun ssdpValuesAreSanitizedAndLocationMustBePrintable() {
        val reply = "HTTP/1.1 200 OK\r\nSERVER: evil\u001b[31m\r\nLOCATION: http://192.168.1.2/a b\r\nUSN: u\u202Eu\r\n\r\n".encodeToByteArray()
        val parsed = assertNotNull(parseSsdpResponse(reply))
        assertNull(parsed.location, "space in URL")
        assertEquals("evil [31m", parsed.server)
        assertEquals("u u", parsed.usn)
    }

    @Test fun descriptionTargetOnlyForHttpLiteralIpv4OfTheResponderInRange() {
        assertEquals(HttpTarget("192.168.1.20", 49152, "/desc.xml"),
            upnpDescriptionTarget("http://192.168.1.20:49152/desc.xml", "192.168.1.20", range))
        assertEquals(HttpTarget("192.168.1.20", 80, "/"), upnpDescriptionTarget("HTTP://192.168.1.20", "192.168.1.20", range))
        assertEquals("/d.xml", upnpDescriptionTarget("http://192.168.1.20/d.xml#frag", "192.168.1.20", range)?.path)
        assertNull(upnpDescriptionTarget("https://192.168.1.20/desc.xml", "192.168.1.20", range), "https")
        assertNull(upnpDescriptionTarget("ftp://192.168.1.20/desc.xml", "192.168.1.20", range))
        assertNull(upnpDescriptionTarget("http://192.168.1.21/desc.xml", "192.168.1.20", range), "other host than responder")
        assertNull(upnpDescriptionTarget("http://10.0.0.1/desc.xml", "10.0.0.1", range), "outside own subnet")
        assertNull(upnpDescriptionTarget("http://router.local/desc.xml", "192.168.1.20", range), "host name")
        assertNull(upnpDescriptionTarget("http://user@192.168.1.20/desc.xml", "192.168.1.20", range), "user info")
        assertNull(upnpDescriptionTarget("http://192.168.1.20:0/", "192.168.1.20", range))
        assertNull(upnpDescriptionTarget("http://192.168.1.20:65536/", "192.168.1.20", range))
        assertNull(upnpDescriptionTarget("http://192.168.001.20/", "192.168.1.20", range), "non-canonical IPv4")
        assertNull(upnpDescriptionTarget("http://[fe80::1]/", "192.168.1.20", range))
        assertNull(upnpDescriptionTarget("http://192.168.1.20/" + "a".repeat(600), "192.168.1.20", range))
    }

    @Test fun requestIsHttp10WithoutKeepAlive() {
        val text = upnpDescriptionRequest(HttpTarget("192.168.1.20", 49152, "/desc.xml")).decodeToString()
        assertTrue(text.startsWith("GET /desc.xml HTTP/1.0\r\nHost: 192.168.1.20:49152\r\n"))
        assertTrue("Connection: close\r\n" in text)
        assertTrue(text.endsWith("\r\n\r\n"))
        assertTrue("Host: 192.168.1.20\r\n" in upnpDescriptionRequest(HttpTarget("192.168.1.20", 80, "/")).decodeToString())
    }

    private fun http(head: String, body: String = "") = (head + "\r\n\r\n" + body).encodeToByteArray()

    @Test fun httpBodyOnly200WithContentLengthOrChunkedOrClose() {
        assertEquals("<root/>", parseHttpResponseBody(http("HTTP/1.1 200 OK\r\nContent-Type: text/xml", "<root/>"))?.decodeToString())
        assertEquals("<ro", parseHttpResponseBody(http("HTTP/1.0 200 OK\r\nContent-Length: 3", "<root/>"))?.decodeToString())
        assertNull(parseHttpResponseBody(http("HTTP/1.0 200 OK\r\nContent-Length: 30", "<root/>")), "truncated body")
        assertNull(parseHttpResponseBody(http("HTTP/1.0 200 OK\r\nContent-Length: 3\r\nContent-Length: 4", "<root/>")), "conflicting lengths")
        assertNull(parseHttpResponseBody(http("HTTP/1.0 200 OK\r\nContent-Length: -1", "x")))
        assertEquals("hello world", parseHttpResponseBody(http("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked",
            "5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n"))?.decodeToString())
        assertNull(parseHttpResponseBody(http("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked", "5\r\nhel")), "truncated chunk")
        assertNull(parseHttpResponseBody(http("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked", "zz\r\nhello\r\n0\r\n\r\n")))
        assertNull(parseHttpResponseBody(http("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked", "5\r\nhelloXX0\r\n\r\n")), "missing CRLF")
        assertNull(parseHttpResponseBody(http("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip", "x")))
        assertNull(parseHttpResponseBody(http("HTTP/1.1 301 Moved\r\nLocation: http://example.com/", "")), "no redirects")
        assertNull(parseHttpResponseBody(http("HTTP/1.1 404 Not Found", "x")))
        assertNull(parseHttpResponseBody(http("ICY 200 OK", "x")))
        assertNull(parseHttpResponseBody("HTTP/1.1 200 OK\r\nno end of headers".encodeToByteArray()))
        assertNull(parseHttpResponseBody(ByteArray(UPNP_MAX_RESPONSE_BYTES + 1)))
        assertNull(parseHttpResponseBody(http("HTTP/1.1 200 OK\r\nX: " + "y".repeat(9000), "x")), "header block too large")
    }

    @Test fun responseCompletenessAllowsStoppingBeforeClose() {
        val full = http("HTTP/1.1 200 OK\r\nContent-Length: 7", "<root/>")
        assertTrue(httpResponseComplete(full))
        assertFalse(httpResponseComplete(full, full.size - 1), "one body byte missing")
        assertFalse(httpResponseComplete(full, 10), "headers incomplete")
        val chunked = http("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked", "5\r\nhello\r\n0\r\n\r\n")
        assertTrue(httpResponseComplete(chunked))
        assertFalse(httpResponseComplete(chunked, chunked.size - 5), "final chunk missing")
        assertTrue(httpResponseComplete(http("HTTP/1.1 404 Not Found")), "non-200: nothing to wait for")
        assertFalse(httpResponseComplete(http("HTTP/1.0 200 OK", "<root/>")), "no framing: only EOF completes")
        assertFalse(httpResponseComplete("garbage\r\n\r\n".encodeToByteArray()))
    }

    private val sample = """<?xml version="1.0" encoding="utf-8"?>
        <!-- a comment with <device> inside -->
        <root xmlns="urn:schemas-upnp-org:device-1-0" configId="1">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Obývák &amp; TV &#x1F4FA;</friendlyName>
            <manufacturer><![CDATA[ACME <Corp>]]></manufacturer>
            <modelName>Model X</modelName>
            <modelNumber>X-100</modelNumber>
            <presentationURL>/index.html</presentationURL>
            <deviceList>
              <device><friendlyName>Inner</friendlyName><modelName>Sub</modelName></device>
            </deviceList>
          </device>
        </root>"""

    @Test fun parsesRootDeviceFieldsOnly() {
        val parsed = assertNotNull(parseUpnpDescription(sample))
        assertEquals("Obývák & TV 📺", parsed.friendlyName)
        assertEquals("ACME <Corp>", parsed.manufacturer)
        assertEquals("Model X", parsed.modelName)
        assertEquals("X-100", parsed.modelNumber)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", parsed.deviceType)
        assertEquals("/index.html", parsed.presentationUrl)
    }

    @Test fun namespacePrefixesAreIgnored() {
        val xml = "<u:root xmlns:u=\"urn:x\"><u:device><u:friendlyName>NAS</u:friendlyName></u:device></u:root>"
        assertEquals("NAS", parseUpnpDescription(xml)?.friendlyName)
    }

    @Test fun rejectsDtdEntitiesAndMalformedDocuments() {
        assertNull(parseUpnpDescription("<!DOCTYPE root [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><root><device><friendlyName>&x;</friendlyName></device></root>"))
        assertNull(parseUpnpDescription("<!ENTITY x \"y\"><root><device><friendlyName>a</friendlyName></device></root>"))
        assertNull(parseUpnpDescription("<root><device><friendlyName>a</modelName></device></root>"), "mismatched")
        assertNull(parseUpnpDescription("<root><device><friendlyName>a</friendlyName></device>"), "unclosed")
        assertNull(parseUpnpDescription("<root><device><friendlyName>a"), "truncated")
        assertNull(parseUpnpDescription("<root><device><!-- never closed"), "comment")
        assertNull(parseUpnpDescription("<root <device>"), "broken tag")
        assertNull(parseUpnpDescription("<root><1device/></root>"), "invalid name")
        assertNull(parseUpnpDescription("<root></root>"), "nothing useful")
        assertNull(parseUpnpDescription("plain text"))
        assertNull(parseUpnpDescription(""))
        val deep = "<a>".repeat(40) + "</a>".repeat(40)
        assertNull(parseUpnpDescription(deep), "depth")
        assertNull(parseUpnpDescription("<root>" + "<x/>".repeat(5000) + "<device><friendlyName>a</friendlyName></device></root>"), "tag count")
    }

    @Test fun unknownEntitiesStayLiteralAndControlsAreSanitized() {
        val xml = "<root><device><friendlyName>A &foo; B &#0; C\u0007 &amp</friendlyName><modelName>${"m".repeat(300)}</modelName></device></root>"
        val parsed = assertNotNull(parseUpnpDescription(xml))
        assertEquals("A &foo; B &#0; C  &amp", parsed.friendlyName)
        assertEquals(128, parsed.modelName?.length)
    }

    @Test fun presentationUrlIsNeverTruncated() {
        val longPath = "/" + "p".repeat(300)
        val ok = parseUpnpDescription("<root><device><presentationURL>$longPath</presentationURL></device></root>")
        assertEquals(longPath, ok?.presentationUrl, "longer than the 128-char display cap, kept whole")
        val base = HttpTarget("192.168.1.20", 80, "/d.xml")
        assertEquals("http://192.168.1.20$longPath", upnpPresentationTarget(ok?.presentationUrl, base, range))
        val tooLong = "/" + "q".repeat(600)
        val dropped = parseUpnpDescription("<root><device><friendlyName>TV</friendlyName><presentationURL>$tooLong</presentationURL></device></root>")
        assertEquals("TV", dropped?.friendlyName)
        assertNull(dropped?.presentationUrl, "over 512 chars: dropped, not cut")
    }

    @Test fun attributesWithGreaterThanInsideQuotes() {
        val xml = "<root a=\"x>y\"><device b='1>2'><friendlyName>ok</friendlyName></device></root>"
        assertEquals("ok", parseUpnpDescription(xml)?.friendlyName)
    }

    @Test fun firstOccurrenceWinsAndChildTextIsNotMerged() {
        val xml = "<root><device><friendlyName>one<b>bold</b>tail</friendlyName><friendlyName>two</friendlyName></device></root>"
        assertEquals("one", parseUpnpDescription(xml)?.friendlyName)
    }

    @Test fun presentationTargetIsOwnSubnetOnly() {
        val base = HttpTarget("192.168.1.20", 8080, "/desc.xml")
        assertEquals("http://192.168.1.20:8080/index.html", upnpPresentationTarget("/index.html", base, range))
        assertEquals("http://192.168.1.20/", upnpPresentationTarget("/", HttpTarget("192.168.1.20", 80, "/d"), range))
        assertEquals("http://192.168.1.30/ui", upnpPresentationTarget("http://192.168.1.30/ui", base, range))
        assertEquals("https://192.168.1.20:8443/", upnpPresentationTarget(" https://192.168.1.20:8443/ ", base, range))
        assertNull(upnpPresentationTarget("http://10.0.0.1/", base, range))
        assertNull(upnpPresentationTarget("http://evil.example.com/", base, range))
        assertNull(upnpPresentationTarget("//evil.example.com/", base, range))
        assertNull(upnpPresentationTarget("javascript:alert(1)", base, range))
        assertNull(upnpPresentationTarget("index.html", base, range))
        assertNull(upnpPresentationTarget("/a b", base, range))
        assertNull(upnpPresentationTarget(null, base, range))
        assertNull(upnpPresentationTarget("http://192.168.1.20:99999/", base, range))
    }
}
