package cz.netkit.shared.lan

/*
 * SSDP / UPnP device identification for the Devices screen (M2): M-SEARCH builder,
 * response parser, the rule deciding whether a device description may be fetched, a
 * minimal HTTP/1.0 response reader and a small XML reader for the root <device> element.
 * Everything here is pure and bounded; the Android side only moves bytes.
 */

const val SSDP_PORT = 1900
const val SSDP_IPV4_GROUP = "239.255.255.250"
/** MX in the M-SEARCH: responders spread replies over 0..MX seconds. */
const val SSDP_MX_SECONDS = 2
/** SSDP replies are a few hundred bytes; anything above this is ignored. */
const val SSDP_MAX_RESPONSE_BYTES = 4096
/** Whole HTTP response (headers + body) for one device description. */
const val UPNP_MAX_RESPONSE_BYTES = 65536
private const val HTTP_MAX_HEADER_BYTES = 8192
private const val SSDP_MAX_HEADERS = 32
private const val UPNP_URL_MAX = 512
private const val UPNP_FIELD_MAX = 128
private const val XML_MAX_DEPTH = 32
private const val XML_MAX_TAGS = 4096

/** M-SEARCH for root devices only: one reply per device instead of one per service (`ssdp:all`). */
fun ssdpMSearch(searchTarget: String = "upnp:rootdevice", mx: Int = SSDP_MX_SECONDS): ByteArray {
    require(mx in 1..5 && searchTarget.isNotEmpty() && searchTarget.all { it in '!'..'~' })
    return ("M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $SSDP_IPV4_GROUP:$SSDP_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: $mx\r\n" +
        "ST: $searchTarget\r\n" +
        "\r\n").encodeToByteArray()
}

/** Header values are sanitized display text; [location] is raw-validated later by [upnpDescriptionTarget]. */
data class SsdpResponse(val location: String?, val server: String?, val searchTarget: String?, val usn: String?)

/**
 * Parses a unicast M-SEARCH reply: `HTTP/1.x 200` status line and up to 32 header lines.
 * NOTIFY / M-SEARCH requests, other status codes, oversized or binary datagrams → null.
 */
fun parseSsdpResponse(packet: ByteArray, length: Int = packet.size): SsdpResponse? {
    if (length < 12 || length > packet.size || length > SSDP_MAX_RESPONSE_BYTES) return null
    if ((0 until length).any { packet[it] == 0.toByte() }) return null
    val text = packet.decodeToString(0, length)
    val lines = text.split("\r\n", "\n")
    if (!Regex("HTTP/1\\.[01] 200(?: .*)?").matches(lines.first())) return null
    val headers = HashMap<String, String>()
    for (line in lines.drop(1).take(SSDP_MAX_HEADERS)) {
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val key = line.substring(0, colon).trim().uppercase()
        if (key !in headers) headers[key] = line.substring(colon + 1).trim()
    }
    fun display(key: String) = headers[key]?.let { sanitizeLanText(it, UPNP_FIELD_MAX).trim() }?.ifEmpty { null }
    val location = headers["LOCATION"]?.takeIf { it.length <= UPNP_URL_MAX && it.all { char -> char in '!'..'~' } }
    return SsdpResponse(location, display("SERVER"), display("ST"), display("USN"))
}

/** A literal-IPv4 http endpoint inside the own subnet. [path] starts with `/` and is printable ASCII. */
data class HttpTarget(val host: String, val port: Int, val path: String) {
    val origin: String get() = if (port == 80) "http://$host" else "http://$host:$port"
    val url: String get() = origin + path
}

private val httpUrl = Regex("([A-Za-z][A-Za-z0-9+.-]*)://([0-9.]+)(?::([0-9]{1,5}))?(/[!-~]*)?")

/**
 * The description at [location] may be fetched only if: scheme is `http` (no TLS, no other
 * scheme), the host is a literal IPv4 equal to [responder] (the SSDP reply's source address)
 * and inside [range], no user-info, port 1..65535. Any fragment is dropped.
 */
fun upnpDescriptionTarget(location: String, responder: String, range: Ipv4Subnet): HttpTarget? {
    if (location.length > UPNP_URL_MAX) return null
    val match = httpUrl.matchEntire(location) ?: return null
    val (scheme, host, portText, rawPath) = match.destructured
    if (!scheme.equals("http", ignoreCase = true)) return null
    val hostNumber = ipv4Number(host) ?: return null
    if (hostNumber != ipv4Number(responder) || !range.contains(host)) return null
    val port = if (portText.isEmpty()) 80 else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val path = rawPath.substringBefore('#').ifEmpty { "/" }
    return HttpTarget(ipv4Text(hostNumber), port, path)
}

/** HTTP/1.0 GET without keep-alive; the server closes after the response. */
fun upnpDescriptionRequest(target: HttpTarget): ByteArray =
    ("GET ${target.path} HTTP/1.0\r\n" +
        "Host: ${if (target.port == 80) target.host else "${target.host}:${target.port}"}\r\n" +
        "Connection: close\r\n" +
        "Accept: text/xml, application/xml\r\n" +
        "User-Agent: NetKit UPnP/1.0\r\n" +
        "\r\n").encodeToByteArray()

private class HttpHead(val bodyStart: Int, val status: String, val contentLength: Int?, val chunked: Boolean)

/** Status line and framing headers; null if the header block is incomplete, too large or malformed. */
private fun httpHead(bytes: ByteArray, length: Int): HttpHead? {
    if (length > bytes.size || length > UPNP_MAX_RESPONSE_BYTES) return null
    val headerEnd = indexOf(bytes, length, "\r\n\r\n".encodeToByteArray(), HTTP_MAX_HEADER_BYTES) ?: return null
    val lines = bytes.decodeToString(0, headerEnd).split("\r\n")
    val status = Regex("HTTP/1\\.[01] ([0-9]{3})(?: .*)?").matchEntire(lines.first()) ?: return null
    var contentLength: Int? = null
    var chunked = false
    for (line in lines.drop(1)) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val key = line.substring(0, colon).trim().lowercase()
        val value = line.substring(colon + 1).trim()
        when (key) {
            "content-length" -> {
                val parsed = value.takeIf { it.isNotEmpty() && it.length <= 6 && it.all(Char::isDigit) }?.toInt() ?: return null
                if (contentLength != null && contentLength != parsed) return null
                contentLength = parsed
            }
            "transfer-encoding" -> chunked = value.equals("chunked", ignoreCase = true) || return null
        }
    }
    return HttpHead(headerEnd + 4, status.groupValues[1], contentLength, chunked)
}

/**
 * Body of a complete `200` response of at most [UPNP_MAX_RESPONSE_BYTES]. Redirects and
 * every other status → null (no redirects are followed). Honours Content-Length (a shorter
 * body is truncated input → null) and bounded chunked encoding; otherwise the body runs to
 * the end of the connection.
 */
fun parseHttpResponseBody(bytes: ByteArray, length: Int = bytes.size): ByteArray? {
    val head = httpHead(bytes, length) ?: return null
    if (head.status != "200") return null
    val body = bytes.copyOfRange(head.bodyStart, length)
    return when {
        head.chunked -> dechunk(body)
        head.contentLength != null -> if (body.size < head.contentLength) null else body.copyOf(head.contentLength)
        else -> body
    }
}

/**
 * True once the first [length] bytes hold everything the reader needs, so it can stop without
 * waiting for the server to close: a non-200 status line (the answer is "no"), all
 * Content-Length bytes, or the final chunk. A response without either framing is complete
 * only at end of stream, which the caller detects itself.
 */
fun httpResponseComplete(bytes: ByteArray, length: Int = bytes.size): Boolean {
    val head = httpHead(bytes, length) ?: return false
    return when {
        head.status != "200" -> true
        head.chunked -> dechunk(bytes.copyOfRange(head.bodyStart, length)) != null
        head.contentLength != null -> length - head.bodyStart >= head.contentLength
        else -> false
    }
}

private fun dechunk(body: ByteArray): ByteArray? {
    val out = ByteArray(body.size)
    var written = 0
    var cursor = 0
    while (true) {
        val lineEnd = indexOf(body, body.size, "\r\n".encodeToByteArray(), 32, cursor) ?: return null
        val sizeText = body.decodeToString(cursor, lineEnd).substringBefore(';').trim()
        if (sizeText.isEmpty() || sizeText.length > 6 || !sizeText.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val size = sizeText.toInt(16)
        cursor = lineEnd + 2
        if (size == 0) return out.copyOf(written)
        if (cursor + size + 2 > body.size) return null
        body.copyInto(out, written, cursor, cursor + size)
        written += size
        if (body[cursor + size] != '\r'.code.toByte() || body[cursor + size + 1] != '\n'.code.toByte()) return null
        cursor += size + 2
    }
}

private fun indexOf(bytes: ByteArray, length: Int, needle: ByteArray, limit: Int, from: Int = 0): Int? {
    val last = minOf(length, from + limit) - needle.size
    for (start in from..last) {
        if (needle.indices.all { bytes[start + it] == needle[it] }) return start
    }
    return null
}

/** Fields of the root `<device>`; each sanitized, at most 128 characters (`presentationUrl` up to 512, never truncated). */
data class UpnpDescription(
    val friendlyName: String? = null, val manufacturer: String? = null, val modelName: String? = null,
    val modelNumber: String? = null, val deviceType: String? = null, val presentationUrl: String? = null,
)

private val upnpFields = setOf("friendlyName", "manufacturer", "modelName", "modelNumber", "deviceType", "presentationURL")

/**
 * Reads `root/device/{friendlyName,…}` (namespace prefixes ignored). Deliberately tiny and
 * strict: any DOCTYPE/ENTITY/other `<!` declaration rejects the document (no DTD, no
 * external or custom entities); only the five predefined and numeric character references
 * are decoded, any other `&…` stays literal; mismatched or unclosed tags, depth over 32 or more than 4096 tags → null.
 * Nested `<deviceList>` devices are ignored.
 */
fun parseUpnpDescription(xml: String): UpnpDescription? {
    if (xml.length > UPNP_MAX_RESPONSE_BYTES) return null
    val stack = ArrayList<String>()
    val values = HashMap<String, String>()
    var capture: StringBuilder? = null
    var cursor = 0
    var tags = 0
    fun capturing(): Boolean = stack.size == 3 && stack[0] == "root" && stack[1] == "device" && stack[2] in upnpFields && stack[2] !in values
    while (cursor < xml.length) {
        val open = xml.indexOf('<', cursor)
        val textEnd = if (open < 0) xml.length else open
        if (capture != null && textEnd > cursor) capture.append(decodeXmlText(xml.substring(cursor, textEnd)))
        if (open < 0) break
        if (++tags > XML_MAX_TAGS) return null
        when {
            xml.startsWith("<!--", open) -> cursor = xml.indexOf("-->", open + 4).takeIf { it >= 0 }?.plus(3) ?: return null
            xml.startsWith("<![CDATA[", open) -> {
                val end = xml.indexOf("]]>", open + 9).takeIf { it >= 0 } ?: return null
                capture?.append(xml, open + 9, end)
                cursor = end + 3
            }
            xml.startsWith("<?", open) -> cursor = xml.indexOf("?>", open + 2).takeIf { it >= 0 }?.plus(2) ?: return null
            xml.startsWith("<!", open) -> return null
            xml.startsWith("</", open) -> {
                val end = xml.indexOf('>', open + 2).takeIf { it >= 0 } ?: return null
                val name = localName(xml.substring(open + 2, end).trim()) ?: return null
                if (stack.lastOrNull() != name) return null
                if (capture != null) {
                    values[name] = upnpFieldValue(name, capture.toString())
                    capture = null
                }
                stack.removeAt(stack.size - 1)
                cursor = end + 1
            }
            else -> {
                val end = tagEnd(xml, open + 1) ?: return null
                val body = xml.substring(open + 1, end)
                val selfClosing = body.endsWith('/')
                val name = localName(body.removeSuffix("/").trim().takeWhile { !it.isWhitespace() }) ?: return null
                // Text of a capturing element's children is not part of its value.
                if (capture != null) { values[stack.last()] = upnpFieldValue(stack.last(), capture.toString()); capture = null }
                if (!selfClosing) {
                    stack += name
                    if (stack.size > XML_MAX_DEPTH) return null
                    if (capturing()) capture = StringBuilder()
                }
                cursor = end + 1
            }
        }
    }
    if (stack.isNotEmpty()) return null
    fun field(name: String) = values[name]?.ifEmpty { null }
    val description = UpnpDescription(field("friendlyName"), field("manufacturer"), field("modelName"),
        field("modelNumber"), field("deviceType"), field("presentationURL"))
    return description.takeIf { it != UpnpDescription() }
}

/**
 * Display fields are cut to 128 characters. `presentationURL` may later be opened, so it is
 * never truncated: longer than 512 characters → dropped (empty), otherwise kept whole.
 */
private fun upnpFieldValue(name: String, raw: String): String {
    val value = raw.trim()
    if (name != "presentationURL") return sanitizeLanText(value, UPNP_FIELD_MAX).trim()
    return if (value.length > UPNP_URL_MAX) "" else sanitizeLanText(value, UPNP_URL_MAX).trim()
}

private fun tagEnd(xml: String, from: Int): Int? {
    var quote: Char? = null
    for (index in from until xml.length) {
        val char = xml[index]
        when {
            quote != null -> if (char == quote) quote = null
            char == '"' || char == '\'' -> quote = char
            char == '<' -> return null
            char == '>' -> return index
        }
    }
    return null
}

private val xmlName = Regex("[A-Za-z_][A-Za-z0-9._-]*(?::[A-Za-z_][A-Za-z0-9._-]*)?")

private fun localName(raw: String): String? = raw.takeIf { xmlName.matches(it) }?.substringAfter(':')

/** Predefined entities and numeric references; an unknown or malformed `&…` stays literal text. */
private fun decodeXmlText(text: String): String {
    if ('&' !in text) return text
    val out = StringBuilder(text.length)
    var cursor = 0
    while (cursor < text.length) {
        val amp = text.indexOf('&', cursor)
        if (amp < 0) { out.append(text, cursor, text.length); break }
        out.append(text, cursor, amp)
        val semi = text.indexOf(';', amp + 1).takeIf { it in amp + 2..amp + 10 }
        val entity = semi?.let { text.substring(amp + 1, it) }
        val decoded = when {
            entity == null -> null
            entity == "lt" -> "<"
            entity == "gt" -> ">"
            entity == "amp" -> "&"
            entity == "quot" -> "\""
            entity == "apos" -> "'"
            entity.startsWith("#x") || entity.startsWith("#X") -> codePointText(entity.substring(2).toIntOrNull(16))
            entity.startsWith("#") -> codePointText(entity.substring(1).toIntOrNull())
            else -> null
        }
        if (decoded == null || semi == null) {
            // Unknown or malformed reference: kept literally, never expanded.
            out.append('&')
            cursor = amp + 1
        } else {
            out.append(decoded)
            cursor = semi + 1
        }
    }
    return out.toString()
}

private fun codePointText(codePoint: Int?): String? = when (codePoint) {
    null -> null
    in 0x1..0xD7FF, in 0xE000..0xFFFF -> codePoint.toChar().toString()
    in 0x10000..0x10FFFF -> {
        val value = codePoint - 0x10000
        charArrayOf((0xD800 + (value shr 10)).toChar(), (0xDC00 + (value and 0x3FF)).toChar()).concatToString()
    }
    else -> null
}

/**
 * URL the user may open in a browser: an absolute `http`/`https` URL whose host is a literal
 * IPv4 inside [range], or a path (`/…`) resolved against the description [base]. Anything
 * else (host names, other schemes, relative paths without `/`) → null; the raw text may still
 * be shown. Never opened automatically.
 */
fun upnpPresentationTarget(raw: String?, base: HttpTarget, range: Ipv4Subnet): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= UPNP_URL_MAX && it.all { char -> char in '!'..'~' } } ?: return null
    if (value.startsWith("/") && !value.startsWith("//")) return base.origin + value
    val match = httpUrl.matchEntire(value) ?: return null
    val (scheme, host, portText) = match.destructured
    if (!scheme.equals("http", true) && !scheme.equals("https", true)) return null
    if (ipv4Number(host) == null || !range.contains(host)) return null
    if (portText.isNotEmpty() && portText.toIntOrNull()?.takeIf { it in 1..65535 } == null) return null
    return value
}
