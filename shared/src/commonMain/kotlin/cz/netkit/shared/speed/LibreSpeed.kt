package cz.netkit.shared.speed

import cz.netkit.shared.json.JSON_MAX_DEPTH
import cz.netkit.shared.json.jsonNestingDepth
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * LibreSpeed HTTP protocol, implemented from its public server API (no LibreSpeed code is used;
 * LibreSpeed itself is LGPL). A server is a base URL whose `backend/` directory serves:
 * - `garbage.php?ckSize=N` — N MiB of random bytes (download),
 * - `empty.php` — discards a POST body (upload) and answers GET with an empty body (latency),
 * - `getIP.php` — JSON `{processedString, rawIspInfo}` with the client's public IP (ISP only with `isp=true`, not used).
 */
object LibreSpeedProtocol {
    /** MiB per download request; LibreSpeed servers cap `ckSize` at 1024. */
    const val DOWNLOAD_CHUNK_MIB = 25
    /** Largest upload body; halved down to [UPLOAD_MIN_BYTES] when the server answers 413. */
    const val UPLOAD_MAX_BYTES = 8 * 1024 * 1024
    const val UPLOAD_MIN_BYTES = 256 * 1024
    /** Upper bound of a `getIP.php` answer that is still parsed. */
    const val IP_INFO_MAX_CHARS = 16 * 1024

    fun garbageUrl(base: String, nonce: Long, chunkMib: Int = DOWNLOAD_CHUNK_MIB): String =
        "${base}backend/garbage.php?ckSize=${chunkMib.coerceIn(1, 1024)}&r=${nonce.toULong()}"

    fun emptyUrl(base: String, nonce: Long): String = "${base}backend/empty.php?r=${nonce.toULong()}"

    /**
     * Without `isp=true`: the server answers with the client IP only and does not forward the
     * client IP to an ISP-info service (LibreSpeed uses ipinfo.io for `isp=true`).
     */
    fun ipInfoUrl(base: String): String = "${base}backend/getIP.php"

    /** A LibreSpeed `empty.php` answer is empty; more than this is a different web app. */
    const val EMPTY_MAX_BYTES = 512
    /** One `garbage.php` response ends early only when cut by the deadline; a shorter complete body is not LibreSpeed. */
    const val GARBAGE_MIN_BYTES = 1L shl 20
}

/** A selectable LibreSpeed server. [baseUrl] is normalized by [checkLibreSpeedUrl] (https, ends with `/`). */
data class LibreSpeedServer(val name: String, val baseUrl: String, val host: String)

/** A colleague's public LibreSpeed (Proxmox); offered as a preset, never started automatically. */
val LIBRESPEED_PRESETS: List<LibreSpeedServer> = listOf(
    LibreSpeedServer("rychlost.zelina.eu", "https://rychlost.zelina.eu/", "rychlost.zelina.eu"),
)

enum class LibreSpeedUrlError { EMPTY, TOO_LONG, NOT_HTTPS, CREDENTIALS, QUERY_OR_FRAGMENT, BAD_HOST, BAD_PORT, BAD_PATH }

sealed interface LibreSpeedUrlCheck {
    data class Valid(val server: LibreSpeedServer) : LibreSpeedUrlCheck
    data class Invalid(val error: LibreSpeedUrlError) : LibreSpeedUrlCheck
}

private const val URL_MAX_CHARS = 512

/**
 * Validates and normalizes a user-entered LibreSpeed base URL.
 *
 * - Only `https://` (a missing scheme means https; `http://` and every other scheme is rejected,
 *   cleartext traffic is forbidden in the app).
 * - No credentials (`user@`), query or fragment; DNS name, IPv4 or bracketed IPv6 host; port 1–65535.
 * - Path of unreserved characters without `.`/`..` segments; a trailing `backend/` is removed
 *   (users often paste the backend directory) and the result always ends with `/`.
 */
fun checkLibreSpeedUrl(input: String): LibreSpeedUrlCheck {
    val text = input.trim()
    if (text.isEmpty()) return LibreSpeedUrlCheck.Invalid(LibreSpeedUrlError.EMPTY)
    if (text.length > URL_MAX_CHARS) return LibreSpeedUrlCheck.Invalid(LibreSpeedUrlError.TOO_LONG)
    if (text.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7e }) return LibreSpeedUrlCheck.Invalid(LibreSpeedUrlError.BAD_HOST)
    val schemeEnd = text.indexOf("://")
    val rest = when {
        schemeEnd < 0 -> text
        text.substring(0, schemeEnd).equals("https", ignoreCase = true) -> text.substring(schemeEnd + 3)
        else -> return LibreSpeedUrlCheck.Invalid(LibreSpeedUrlError.NOT_HTTPS)
    }
    if ('?' in rest || '#' in rest) return LibreSpeedUrlCheck.Invalid(LibreSpeedUrlError.QUERY_OR_FRAGMENT)
    val slash = rest.indexOf('/')
    val authority = if (slash < 0) rest else rest.substring(0, slash)
    val path = if (slash < 0) "/" else rest.substring(slash)
    if ('@' in authority) return LibreSpeedUrlCheck.Invalid(LibreSpeedUrlError.CREDENTIALS)
    val (host, port) = splitHostPort(authority) ?: return LibreSpeedUrlCheck.Invalid(LibreSpeedUrlError.BAD_HOST)
    if (!validHost(host)) return LibreSpeedUrlCheck.Invalid(LibreSpeedUrlError.BAD_HOST)
    if (port != null) {
        val number = port.toIntOrNull()
        if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' } || number == null || number !in 1..65535)
            return LibreSpeedUrlCheck.Invalid(LibreSpeedUrlError.BAD_PORT)
    }
    val normalizedPath = normalizeBasePath(path) ?: return LibreSpeedUrlCheck.Invalid(LibreSpeedUrlError.BAD_PATH)
    val lowerHost = host.lowercase()
    val portPart = if (port == null || port == "443") "" else ":$port"
    val base = "https://$lowerHost$portPart$normalizedPath"
    val name = lowerHost.removePrefix("[").removeSuffix("]") + portPart + normalizedPath.removeSuffix("/").takeIf { it.isNotEmpty() }.orEmpty()
    return LibreSpeedUrlCheck.Valid(LibreSpeedServer(name, base, lowerHost))
}

private fun splitHostPort(authority: String): Pair<String, String?>? {
    if (authority.isEmpty()) return null
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) return null
        val host = authority.substring(0, close + 1)
        val tail = authority.substring(close + 1)
        return when {
            tail.isEmpty() -> host to null
            tail.startsWith(":") -> host to tail.substring(1)
            else -> null
        }
    }
    val colon = authority.indexOf(':')
    if (colon < 0) return authority to null
    if (authority.indexOf(':', colon + 1) >= 0) return null
    return authority.substring(0, colon) to authority.substring(colon + 1)
}

private fun validHost(host: String): Boolean {
    if (host.startsWith("[")) {
        val inner = host.substring(1, host.length - 1)
        return inner.length in 2..45 && inner.count { it == ':' } >= 2 &&
            inner.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }
    if (host.isEmpty() || host.length > 253) return false
    val labels = host.removeSuffix(".").split('.')
    if (labels.any { it.isEmpty() || it.length > 63 }) return false
    return labels.all { label ->
        label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' } &&
            !label.startsWith('-') && !label.endsWith('-')
    }
}

private fun normalizeBasePath(path: String): String? {
    val allowed = path.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-._~/%" }
    if (!allowed) return null
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.any { it == "." || it == ".." || it.endsWith(".php") || it.endsWith(".html") }) return null
    val kept = if (segments.lastOrNull().equals("backend", ignoreCase = true)) segments.dropLast(1) else segments
    return if (kept.isEmpty()) "/" else "/" + kept.joinToString("/") + "/"
}

/** What `getIP.php` says about the client (public IP). Shown to the user only; never logged or stored. */
data class LibreSpeedIpInfo(val summary: String)

@Serializable
private data class GetIpJson(val processedString: String? = null)

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = false }

/** Parses `getIP.php`; control characters are dropped and the text is cut to 160 characters. */
fun parseLibreSpeedIpInfo(text: String): LibreSpeedIpInfo? {
    if (text.length > LibreSpeedProtocol.IP_INFO_MAX_CHARS) return null
    if (jsonNestingDepth(text) > JSON_MAX_DEPTH) return null
    val processed = try {
        lenientJson.decodeFromString(GetIpJson.serializer(), text).processedString
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: return null
    val clean = processed.filter { it.code >= 0x20 && it.code != 0x7f }.trim().take(160)
    return clean.takeIf { it.isNotEmpty() }?.let(::LibreSpeedIpInfo)
}
