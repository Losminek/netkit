package cz.netkit.shared.update

import cz.netkit.shared.json.JSON_MAX_DEPTH
import cz.netkit.shared.json.jsonNestingDepth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Largest accepted `latest.json` (UTF-8 bytes); the Android reader stops at the same bound. */
const val LATEST_JSON_MAX_BYTES = 16 * 1024
/** The only schema this version understands; a compatible extension keeps 1 and adds fields. */
const val LATEST_JSON_SCHEMA = 1
/** At most one automatic check per this interval (D14); a manual check ignores it. */
const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
/** Release notes per language are cut to this many characters. */
const val UPDATE_NOTES_MAX_CHARS = 500
const val UPDATE_URL_MAX_CHARS = 2048
/** Host of the source repository's release links (D13). Fetch redirects have a separate policy. */
const val UPDATE_GITHUB_HOST = "github.com"

/**
 * Validated content of `latest.json` (schema in `docs/distribuce.md` §4).
 * Every URL has passed [UpdateUrlPolicy]; [sha256] and [certSha256] are 64 lowercase hex digits.
 * [releasePageUrl] is not read from the document: it is the GitHub release page of [versionName]
 * derived from the build's source repository (`BuildConfig.SOURCE_URL`), when that is a GitHub repo.
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val minSdk: Int,
    val pageUrl: String?,
    val apkUrl: String?,
    val released: String?,
    val sha256: String?,
    val certSha256: String?,
    val notes: Map<String, String>,
    val releasePageUrl: String? = null,
) {
    init {
        require(pageUrl != null || apkUrl != null || releasePageUrl != null) { "no link" }
    }

    /** What the "download" button opens: the project page, otherwise the APK asset, otherwise the release page. */
    val openUrl: String get() = pageUrl ?: apkUrl ?: releasePageUrl!!

    /** Notes in [language] (ISO 639-1), otherwise English, otherwise none. */
    fun notesFor(language: String): String? = notes[language.lowercase()] ?: notes["en"]
}

enum class UpdateParseError {
    TOO_LARGE, INVALID_JSON, UNSUPPORTED_SCHEMA, MISSING_FIELD, INVALID_FIELD,
    /** `versionCode` is not `major*10000 + minor*100 + patch` of `versionName`. */
    VERSION_MISMATCH,
    /**
     * A URL is not `https` on the web host or within the configured GitHub repository.
     * In a build without a web (placeholder)
     * a well-formed `https` link on another host is dropped instead (that build cannot know the real
     * web host); the document is rejected only when no usable link remains.
     */
    URL_NOT_ALLOWED,
}

sealed interface UpdateParseResult {
    data class Ok(val info: UpdateInfo) : UpdateParseResult
    /** [field] names the offending key where there is one; it is diagnostic only, never shown raw. */
    data class Rejected(val error: UpdateParseError, val field: String? = null) : UpdateParseResult
}

/**
 * Which links from `latest.json` the app may open and where the manifest is read from.
 *
 * Links: `https` only, the exact host of [webBaseUrl] (from `BuildConfig.UPDATE_BASE_URL`, `null`
 * when the build has only a placeholder) or within [github] on [UPDATE_GITHUB_HOST], default port, no user info, no
 * fragment, printable ASCII only. Lookalikes (`github.com.example`, `evilgithub.com`) fail because
 * the host is compared as a whole.
 *
 * Sources ([endpoints], D22): the web `<base>/latest.json` first, then the asset `latest.json` of
 * the latest GitHub release of [sourceUrl] when that is exactly `https://github.com/<owner>/<repo>`.
 */
class UpdateUrlPolicy(webBaseUrl: String?, sourceUrl: String? = null) {
    /** `null` when the build has no web (placeholder). */
    val webHost: String? = webBaseUrl?.let { requireNotNull(httpsHost(it)) { "WEB_BASE_URL" } }
    /** Where the app reads the manifest from on the web, `null` without a web. */
    val latestJsonUrl: String? = webBaseUrl?.let { it.trimEnd('/') + "/latest.json" }
    /** GitHub repository of the sources, `null` when [sourceUrl] is not a plain GitHub repo URL. */
    val github: GithubRepo? = sourceUrl?.let(GithubRepo::parse)

    /** Sources in the order they are tried; empty when the build has neither. */
    val endpoints: List<UpdateEndpoint> = listOfNotNull(
        latestJsonUrl?.let { UpdateEndpoint(UpdateSource.WEB, it, setOf(webHost!!)) },
        github?.let { UpdateEndpoint(UpdateSource.GITHUB, it.latestJsonUrl, UPDATE_GITHUB_FETCH_HOSTS) },
    )

    fun isAllowed(url: String): Boolean {
        val host = httpsHost(url) ?: return false
        if (host != UPDATE_GITHUB_HOST) return host == webHost
        val repo = github ?: return false
        // httpsHost already checked the authority, including an optional default port.
        val path = "/" + url.substringBefore('?').removePrefix("https://").substringAfter('/', "")
        if (!path.startsWith("/${repo.owner}/${repo.name}/")) return false
        // Never let browser/server normalization escape the exact, case-sensitive repo prefix.
        // Encoded separators and repeated percent-decoding are rejected conservatively.
        if (ENCODED_PATH_SEPARATOR.containsMatchIn(path)) return false
        return path.split('/').none { segment ->
            val dots = segment.replace("%2e", ".", ignoreCase = true)
            dots == "." || dots == ".."
        }
    }

    /** `true` for a strict `https` URL on a host this build does not know (used without a web). */
    internal fun isWellFormedForeign(url: String): Boolean =
        httpsHost(url)?.let { it != webHost && it != UPDATE_GITHUB_HOST } == true
}

/**
 * A GitHub repository given exactly as `https://github.com/<owner>/<repo>` (no trailing slash,
 * no further path, no `.git`). Anything else yields `null` from [parse], so the fallback is off.
 */
class GithubRepo private constructor(val owner: String, val name: String) {
    val url: String get() = "https://$UPDATE_GITHUB_HOST/$owner/$name"
    /** Release asset of the latest (non-draft, non-prerelease) release; GitHub answers with redirects. */
    val latestJsonUrl: String get() = "$url/releases/latest/download/latest.json"

    /** Release page of tag `v<versionName>` (tags as in `tools/check_release_version.sh`). */
    fun releaseTagUrl(versionName: String): String = "$url/releases/tag/v$versionName"

    override fun equals(other: Any?): Boolean = other is GithubRepo && other.owner == owner && other.name == name
    override fun hashCode(): Int = 31 * owner.hashCode() + name.hashCode()
    override fun toString(): String = url

    companion object {
        private val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
        private val NAME = Regex("[A-Za-z0-9._-]{1,100}")

        fun parse(url: String): GithubRepo? {
            val rest = url.removePrefix("https://$UPDATE_GITHUB_HOST/").takeIf { it != url } ?: return null
            val parts = rest.split('/')
            if (parts.size != 2) return null
            val (owner, name) = parts
            if (!OWNER.matches(owner) || !NAME.matches(name) || name == "." || name == ".." || name.endsWith(".git")) return null
            return GithubRepo(owner, name)
        }
    }
}

/** `major*10000 + minor*100 + patch` for `X.Y.Z` (as in `androidApp/build.gradle.kts`), else `null`. */
fun versionCodeOf(versionName: String): Int? {
    val match = VERSION_NAME.matchEntire(versionName) ?: return null
    val (major, minor, patch) = match.destructured.toList().map(String::toInt)
    if (major > MAX_MAJOR) return null
    return major * 10000 + minor * 100 + patch
}

/**
 * Parses and validates [body]. Fails closed: any malformed or disallowed value rejects the whole
 * document instead of being dropped, so a partly broken manifest never produces a link. Unknown
 * keys are ignored (forward compatibility within schema 1).
 */
fun parseLatestJson(body: String, policy: UpdateUrlPolicy): UpdateParseResult {
    if (body.length > LATEST_JSON_MAX_BYTES || body.encodeToByteArray().size > LATEST_JSON_MAX_BYTES) {
        return UpdateParseResult.Rejected(UpdateParseError.TOO_LARGE)
    }
    // The tree reader recurses per array level (StackOverflowError at a few thousand), so nesting is
    // bounded before parsing. SerializationException is an IllegalArgumentException.
    if (jsonNestingDepth(body) > JSON_MAX_DEPTH) return UpdateParseResult.Rejected(UpdateParseError.INVALID_JSON)
    val element = try {
        Json.parseToJsonElement(body)
    } catch (_: IllegalArgumentException) {
        null
    }
    val root = element as? JsonObject ?: return UpdateParseResult.Rejected(UpdateParseError.INVALID_JSON)
    return try {
        UpdateParseResult.Ok(readInfo(root, policy))
    } catch (rejection: Rejection) {
        UpdateParseResult.Rejected(rejection.error, rejection.field)
    }
}

enum class UpdateStatus {
    /** Newer, installable here and not dismissed: the overview shows the card. */
    AVAILABLE,
    /** Same or older than the running build. */
    UP_TO_DATE,
    /** Newer, but its `minSdk` is above this device: no card, About explains it. */
    REQUIRES_NEWER_ANDROID,
    /** Newer, but the user closed its card; a later version shows again. */
    DISMISSED,
}

fun updateStatus(info: UpdateInfo, currentVersionCode: Int, deviceSdk: Int, dismissedVersionCode: Int?): UpdateStatus = when {
    info.versionCode <= currentVersionCode -> UpdateStatus.UP_TO_DATE
    info.minSdk > deviceSdk -> UpdateStatus.REQUIRES_NEWER_ANDROID
    dismissedVersionCode != null && info.versionCode <= dismissedVersionCode -> UpdateStatus.DISMISSED
    else -> UpdateStatus.AVAILABLE
}

/**
 * Whether an automatic check may run at [nowMs] (wall clock, it survives restarts). A clock that
 * moved backwards counts as due, otherwise a wrong date could block checks for a long time.
 */
fun isUpdateCheckDue(lastCheckMs: Long?, nowMs: Long): Boolean =
    lastCheckMs == null || nowMs < lastCheckMs || nowMs - lastCheckMs >= UPDATE_CHECK_INTERVAL_MS

// --- Internals ---------------------------------------------------------------------------------

private val VERSION_NAME = Regex("""(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)""")
private const val MAX_MAJOR = 20_000
private val HEX_64 = Regex("[0-9a-f]{64}")
private val DATE = Regex("""\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])""")
private val LANGUAGE = Regex("[a-z]{2}")
private val HOST_LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
private val ENCODED_PATH_SEPARATOR = Regex("%(2f|5c|25)", RegexOption.IGNORE_CASE)
private const val MAX_NOTES = 8
private const val MAX_SDK = 1000

private class Rejection(val error: UpdateParseError, val field: String?) : Exception()

private fun reject(error: UpdateParseError, field: String? = null): Nothing = throw Rejection(error, field)

private fun readInfo(root: JsonObject, policy: UpdateUrlPolicy): UpdateInfo {
    val schema = int(root, "schema") ?: reject(UpdateParseError.MISSING_FIELD, "schema")
    if (schema != LATEST_JSON_SCHEMA) reject(UpdateParseError.UNSUPPORTED_SCHEMA, "schema")
    val versionName = string(root, "versionName") ?: reject(UpdateParseError.MISSING_FIELD, "versionName")
    val versionCode = int(root, "versionCode") ?: reject(UpdateParseError.MISSING_FIELD, "versionCode")
    val minSdk = int(root, "minSdk") ?: reject(UpdateParseError.MISSING_FIELD, "minSdk")
    val expectedCode = versionCodeOf(versionName) ?: reject(UpdateParseError.INVALID_FIELD, "versionName")
    if (versionCode != expectedCode) reject(UpdateParseError.VERSION_MISMATCH, "versionCode")
    if (minSdk !in 1..MAX_SDK) reject(UpdateParseError.INVALID_FIELD, "minSdk")
    val rawPageUrl = string(root, "pageUrl")
    val rawApkUrl = string(root, "apkUrl")
    if (rawPageUrl == null && rawApkUrl == null) reject(UpdateParseError.MISSING_FIELD, "pageUrl")
    val pageUrl = rawPageUrl?.let { link(it, "pageUrl", policy) }
    val apkUrl = rawApkUrl?.let { link(it, "apkUrl", policy) }
    val releasePageUrl = policy.github?.releaseTagUrl(versionName)
    if (pageUrl == null && apkUrl == null && releasePageUrl == null) reject(UpdateParseError.URL_NOT_ALLOWED, "pageUrl")
    val released = string(root, "released")?.also { if (!DATE.matches(it)) reject(UpdateParseError.INVALID_FIELD, "released") }
    return UpdateInfo(
        versionName = versionName, versionCode = versionCode, minSdk = minSdk, pageUrl = pageUrl, apkUrl = apkUrl,
        released = released, sha256 = digest(root, "sha256"), certSha256 = digest(root, "certSha256"), notes = notes(root),
        releasePageUrl = releasePageUrl,
    )
}

/** Absent or JSON `null` = `null`; any other non-string value is invalid. */
private fun string(root: JsonObject, key: String): String? {
    val value = root[key] ?: return null
    if (value is JsonPrimitive && value.isString) return value.content
    if (value.isJsonNull()) return null
    reject(UpdateParseError.INVALID_FIELD, key)
}

/** A JSON number that fits an Int; a quoted number or a fraction is invalid. */
private fun int(root: JsonObject, key: String): Int? {
    val value = root[key] ?: return null
    if (value.isJsonNull()) return null
    val primitive = value as? JsonPrimitive
    if (primitive == null || primitive.isString) reject(UpdateParseError.INVALID_FIELD, key)
    return primitive.intOrNull ?: reject(UpdateParseError.INVALID_FIELD, key)
}

/**
 * An allowed link, or `null` for a well-formed `https` link on an unknown host in a build without a
 * web (it is never opened). Everything else rejects the document.
 */
private fun link(value: String, key: String, policy: UpdateUrlPolicy): String? = when {
    policy.isAllowed(value) -> value
    policy.webHost == null && policy.isWellFormedForeign(value) -> null
    else -> reject(UpdateParseError.URL_NOT_ALLOWED, key)
}

/** SHA-256 as 64 hex digits, optionally colon-separated (apksigner / keytool style); stored lowercase. */
private fun digest(root: JsonObject, key: String): String? {
    val value = string(root, key) ?: return null
    val normalized = value.replace(":", "").lowercase()
    if (!HEX_64.matches(normalized)) reject(UpdateParseError.INVALID_FIELD, key)
    return normalized
}

private fun notes(root: JsonObject): Map<String, String> {
    val value = root["notes"] ?: return emptyMap()
    if (value.isJsonNull()) return emptyMap()
    val notes = value as? JsonObject ?: reject(UpdateParseError.INVALID_FIELD, "notes")
    if (notes.size > MAX_NOTES) reject(UpdateParseError.INVALID_FIELD, "notes")
    return notes.entries.associate { (language, text) ->
        if (!LANGUAGE.matches(language)) reject(UpdateParseError.INVALID_FIELD, "notes")
        val primitive = text as? JsonPrimitive
        if (primitive == null || !primitive.isString) reject(UpdateParseError.INVALID_FIELD, "notes")
        language to sanitizeNotes(primitive.content)
    }.filterValues { it.isNotEmpty() }
}

/** Control and format characters become spaces (no bidi tricks or line injection), runs collapse. */
private fun sanitizeNotes(text: String): String {
    val cleaned = buildString(text.length) {
        for (char in text) append(if (char.isISOControl() || char.category == CharCategory.FORMAT) ' ' else char)
    }.replace(Regex("\\s+"), " ").trim()
    return if (cleaned.length <= UPDATE_NOTES_MAX_CHARS) cleaned else cleaned.take(UPDATE_NOTES_MAX_CHARS - 1).trimEnd() + "…"
}

private fun JsonElement.isJsonNull(): Boolean = this === JsonNull

/** Lowercase host of a strict `https://host[:443][/path][?query]` URL, otherwise `null`. */
internal fun httpsHost(url: String, maxChars: Int = UPDATE_URL_MAX_CHARS): String? = httpsOrigin(url, maxChars)?.second

/**
 * `https://host` (port 443 dropped) and the lowercase host of a strict `https://host[:443][/path][?query]`
 * URL of at most [maxChars] printable ASCII characters without user info or fragment, otherwise `null`.
 */
internal fun httpsOrigin(url: String, maxChars: Int = UPDATE_URL_MAX_CHARS): Pair<String, String>? {
    if (url.length > maxChars || !url.startsWith("https://")) return null
    if (url.any { it.code !in 0x21..0x7e || it in "\\\"<>^`{|}#" }) return null
    val rest = url.removePrefix("https://")
    val authority = rest.substring(0, rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it })
    if ('@' in authority) return null
    val host = when {
        authority.endsWith(":443") -> authority.removeSuffix(":443")
        ':' in authority -> return null
        else -> authority
    }
    val labels = host.split('.')
    if (host.length > 253 || labels.size < 2 || labels.any { !HOST_LABEL.matches(it) }) return null
    return "https://$host" to host
}
