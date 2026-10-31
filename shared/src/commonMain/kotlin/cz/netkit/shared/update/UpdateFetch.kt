package cz.netkit.shared.update

import kotlinx.coroutines.withTimeoutOrNull

/** Redirects followed per source at most (GitHub needs 2: tag resolution, signed asset URL). */
const val UPDATE_MAX_REDIRECTS = 3
/** Whole budget of one source including its redirect hops (D14, D22); two sources ≤ 10 s. */
const val UPDATE_SOURCE_TIMEOUT_MS = 5_000L
/** Signed asset URLs of GitHub are ~900 characters; links in `latest.json` keep [UPDATE_URL_MAX_CHARS]. */
const val UPDATE_REDIRECT_URL_MAX_CHARS = 4096

/**
 * Hosts the GitHub source may be redirected through (verified 2026-10-29): `github.com` resolves
 * `latest` to a tag, then the asset is served from a signed `release-assets.githubusercontent.com`
 * URL; `objects.githubusercontent.com` is the older asset host.
 */
val UPDATE_GITHUB_FETCH_HOSTS: Set<String> =
    setOf(UPDATE_GITHUB_HOST, "release-assets.githubusercontent.com", "objects.githubusercontent.com")

enum class UpdateSource { WEB, GITHUB }

/** One place to read `latest.json` from; redirects stay within [allowedHosts]. */
data class UpdateEndpoint(val source: UpdateSource, val url: String, val allowedHosts: Set<String>)

enum class RedirectRejection {
    MISSING_LOCATION,
    TOO_MANY_HOPS,
    /** Relative, protocol-relative, malformed, user info, other port, fragment or too long. */
    MALFORMED,
    /** Another scheme than `https` (e.g. a downgrade to `http`). */
    NOT_HTTPS,
    HOST_NOT_ALLOWED,
}

sealed interface RedirectDecision {
    data class Follow(val url: String) : RedirectDecision
    data class Reject(val reason: RedirectRejection) : RedirectDecision
}

/**
 * Whether a redirect from [current] to [location] may be followed after [hop] redirects already
 * followed. [location] may be an absolute `https` URL or an absolute path (`/…`, same origin as
 * [current]); anything else (relative paths, `//host`, other schemes) is rejected rather than
 * resolved. The target must pass the same strict URL check as links ([httpsOrigin]) and its host
 * must be one of [allowedHosts], compared as a whole.
 */
fun nextRedirect(current: String, location: String?, allowedHosts: Set<String>, hop: Int): RedirectDecision {
    if (hop >= UPDATE_MAX_REDIRECTS) return RedirectDecision.Reject(RedirectRejection.TOO_MANY_HOPS)
    if (location.isNullOrEmpty()) return RedirectDecision.Reject(RedirectRejection.MISSING_LOCATION)
    val target = when {
        location.startsWith("//") -> return RedirectDecision.Reject(RedirectRejection.MALFORMED)
        location.startsWith("/") -> httpsOrigin(current, UPDATE_REDIRECT_URL_MAX_CHARS)?.first?.plus(location)
            ?: return RedirectDecision.Reject(RedirectRejection.MALFORMED)
        location.startsWith("https://") -> location
        SCHEME.containsMatchIn(location) -> return RedirectDecision.Reject(RedirectRejection.NOT_HTTPS)
        else -> return RedirectDecision.Reject(RedirectRejection.MALFORMED)
    }
    val host = httpsOrigin(target, UPDATE_REDIRECT_URL_MAX_CHARS)?.second
        ?: return RedirectDecision.Reject(RedirectRejection.MALFORMED)
    if (host !in allowedHosts) return RedirectDecision.Reject(RedirectRejection.HOST_NOT_ALLOWED)
    return RedirectDecision.Follow(target)
}

/** Result of one HTTP GET without redirect following. Implementations never throw (except cancellation). */
sealed interface HttpResult {
    /**
     * Any response. [location] is the `Location` header of a redirect; [body] is the complete body
     * of a `200` (at most [LATEST_JSON_MAX_BYTES], otherwise the implementation answers [Failure]).
     */
    class Response(val status: Int, val location: String? = null, val body: ByteArray? = null) : HttpResult

    data class Failure(val kind: Kind) : HttpResult {
        enum class Kind {
            /** DNS, connect, TLS, timeout, broken body. */
            NETWORK,
            /** Body (or its declared length) above [LATEST_JSON_MAX_BYTES]. */
            TOO_LARGE,
        }
    }
}

/** A single `GET` with no redirects, cookies, cache or retries; the caller enforces the time budget. */
fun interface UpdateHttp {
    suspend fun get(url: String): HttpResult
}

enum class UpdateCheckFailure {
    /** DNS, connect, TLS or timeout, or no source configured. */
    NETWORK,
    /** Anything but `200` or an allowed redirect. */
    HTTP_STATUS,
    /** A redirect outside the source's hosts, not `https`, without `Location` or beyond [UPDATE_MAX_REDIRECTS]. */
    REDIRECT,
    TOO_LARGE,
    /** The body is not a valid `latest.json` (see [UpdateParseError]). */
    INVALID,
}

/** How one source failed; [hops] redirects were followed before. Diagnostic only. */
data class UpdateAttempt(val source: UpdateSource, val failure: UpdateCheckFailure, val hops: Int)

sealed interface UpdateCheckOutcome {
    /** [body] is the accepted document as received, for the cache. */
    data class Found(val info: UpdateInfo, val source: UpdateSource, val body: String) : UpdateCheckOutcome

    /** [failure] is [UpdateCheckFailure.NETWORK] only when no source answered at all. */
    data class Failed(val failure: UpdateCheckFailure, val attempts: List<UpdateAttempt> = emptyList()) : UpdateCheckOutcome
}

/**
 * Update check v2 (D22): tries [UpdateUrlPolicy.endpoints] in order (web, then GitHub) and stops
 * at the first valid `latest.json`. A source fails over to the next on a network error, a status
 * other than `200`, a rejected redirect, a body over [LATEST_JSON_MAX_BYTES] or a document that
 * [parseLatestJson] rejects. Each source has [sourceTimeoutMs] for all its hops; no retries.
 */
suspend fun checkForUpdate(
    policy: UpdateUrlPolicy, http: UpdateHttp, sourceTimeoutMs: Long = UPDATE_SOURCE_TIMEOUT_MS,
): UpdateCheckOutcome {
    val attempts = mutableListOf<UpdateAttempt>()
    for (endpoint in policy.endpoints) {
        var hops = 0
        val result = withTimeoutOrNull(sourceTimeoutMs) {
            fetchSource(endpoint, policy, http) { hops = it }
        } ?: SourceResult.Failed(UpdateCheckFailure.NETWORK)
        when (result) {
            is SourceResult.Found -> return UpdateCheckOutcome.Found(result.info, endpoint.source, result.body)
            is SourceResult.Failed -> attempts += UpdateAttempt(endpoint.source, result.failure, hops)
        }
    }
    val failure = attempts.lastOrNull { it.failure != UpdateCheckFailure.NETWORK }?.failure ?: UpdateCheckFailure.NETWORK
    return UpdateCheckOutcome.Failed(failure, attempts)
}

// --- Internals ---------------------------------------------------------------------------------

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

private sealed interface SourceResult {
    class Found(val info: UpdateInfo, val body: String) : SourceResult
    class Failed(val failure: UpdateCheckFailure) : SourceResult
}

private suspend fun fetchSource(
    endpoint: UpdateEndpoint, policy: UpdateUrlPolicy, http: UpdateHttp, onHop: (Int) -> Unit,
): SourceResult {
    var url = endpoint.url
    var hop = 0
    while (true) {
        val response = when (val result = http.get(url)) {
            is HttpResult.Failure -> return SourceResult.Failed(
                if (result.kind == HttpResult.Failure.Kind.TOO_LARGE) UpdateCheckFailure.TOO_LARGE else UpdateCheckFailure.NETWORK)
            is HttpResult.Response -> result
        }
        when (response.status) {
            200 -> return readBody(response.body, policy)
            in REDIRECT_STATUSES -> when (val decision = nextRedirect(url, response.location, endpoint.allowedHosts, hop)) {
                is RedirectDecision.Follow -> { url = decision.url; hop++; onHop(hop) }
                is RedirectDecision.Reject -> return SourceResult.Failed(UpdateCheckFailure.REDIRECT)
            }
            else -> return SourceResult.Failed(UpdateCheckFailure.HTTP_STATUS)
        }
    }
}

private fun readBody(body: ByteArray?, policy: UpdateUrlPolicy): SourceResult {
    if (body == null) return SourceResult.Failed(UpdateCheckFailure.NETWORK)
    if (body.size > LATEST_JSON_MAX_BYTES) return SourceResult.Failed(UpdateCheckFailure.TOO_LARGE)
    val text = try {
        body.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        return SourceResult.Failed(UpdateCheckFailure.INVALID)
    }
    return when (val parsed = parseLatestJson(text, policy)) {
        is UpdateParseResult.Ok -> SourceResult.Found(parsed.info, text)
        is UpdateParseResult.Rejected -> SourceResult.Failed(UpdateCheckFailure.INVALID)
    }
}
