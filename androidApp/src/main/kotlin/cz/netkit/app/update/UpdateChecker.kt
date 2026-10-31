package cz.netkit.app.update

import android.content.Context
import androidx.core.content.edit
import cz.netkit.app.BuildConfig
import cz.netkit.shared.update.HttpResult
import cz.netkit.shared.update.LATEST_JSON_MAX_BYTES
import cz.netkit.shared.update.UPDATE_SOURCE_TIMEOUT_MS
import cz.netkit.shared.update.UpdateCheckFailure
import cz.netkit.shared.update.UpdateCheckOutcome
import cz.netkit.shared.update.UpdateHttp
import cz.netkit.shared.update.UpdateInfo
import cz.netkit.shared.update.UpdateParseResult
import cz.netkit.shared.update.UpdateSource
import cz.netkit.shared.update.UpdateUrlPolicy
import cz.netkit.shared.update.checkForUpdate
import cz.netkit.shared.update.isUpdateCheckDue
import cz.netkit.shared.update.parseLatestJson
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Bound of one HTTP call (DNS, TLS, body); a source's hops share [UPDATE_SOURCE_TIMEOUT_MS] in `shared`. */
private const val CALL_TIMEOUT_MS = UPDATE_SOURCE_TIMEOUT_MS
private const val PREFERENCES = "update"
private const val KEY_ENABLED = "enabled"
private const val KEY_LAST_CHECK_MS = "last_check_ms"
private const val KEY_LAST_BODY = "last_body"
private const val KEY_DISMISSED_CODE = "dismissed_version_code"
/** Time and source (`web` / `github`) of the last successful check. */
private const val KEY_LAST_SUCCESS_MS = "last_success_ms"
private const val KEY_LAST_SOURCE = "last_source"
/** Debug/dev builds only: app-private file used instead of the network (emulator test of the card). */
internal const val UPDATE_FIXTURE_FILE = "update-fixture.json"

/**
 * Update check against `latest.json` (D14, D17, D22; policy in docs/bezpecnost.md § Kontrola
 * aktualizací). The order, redirects, limits and fallback live in `shared` ([checkForUpdate]); this
 * class adds the HTTP transport, storage and the debug fixture.
 *
 * - Sources: `<UPDATE_BASE_URL>/latest.json`, then `<SOURCE_URL>/releases/latest/download/latest.json`
 *   on GitHub when SOURCE_URL is a GitHub repo. Each `GET` over HTTPS (OkHttp, cleartext stays
 *   forbidden by the network security config) with OkHttp's own redirects off; the shared policy
 *   follows at most 3 redirects per source, `https` only, web: same host, GitHub: github.com and its
 *   asset hosts. 5 s per source including hops, no retries, no cookies, no cache, no identifier;
 *   headers beyond OkHttp's defaults are `User-Agent: NetKit/<version>` and `Accept: application/json`.
 * - Body at most [LATEST_JSON_MAX_BYTES], parsed and validated in `shared` ([parseLatestJson]):
 *   links only on the web host or github.com. The app never downloads or installs an APK.
 * - App-private SharedPreferences `update` (excluded from backups): switch (default on), time of
 *   the last attempt, the last valid body (re-validated on every read), time and source of the last
 *   success, and the dismissed version.
 * - Without any source (placeholder web and no GitHub SOURCE_URL) nothing is sent. Debug builds may
 *   read [UPDATE_FIXTURE_FILE] from `filesDir` instead of the network; release builds compile that
 *   branch out (`BuildConfig.DEBUG`).
 *
 * Every method touches disk or network and belongs off the main thread.
 */
internal class UpdateChecker(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val policy = UpdateUrlPolicy(BuildConfig.UPDATE_BASE_URL.takeIf { BuildConfig.UPDATE_CONFIGURED }, BuildConfig.SOURCE_URL)
    private val http by lazy { OkHttpUpdateHttp("NetKit/${BuildConfig.VERSION_NAME}") }

    /** Web host whose links the app accepts besides github.com (shown in About), `null` without a web. */
    val webHost: String? get() = policy.webHost

    /** Whether GitHub releases are asked (first or after the web). */
    val hasGithubSource: Boolean get() = policy.github != null

    /** A source is configured, or (debug only) a local fixture exists. */
    fun isAvailable(): Boolean = policy.endpoints.isNotEmpty() || fixture() != null

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) = preferences.edit { putBoolean(KEY_ENABLED, enabled) }

    fun lastCheckMs(): Long? = preferences.getLong(KEY_LAST_CHECK_MS, -1L).takeIf { it >= 0 }

    /** Source of the last check when that check succeeded, otherwise `null`. */
    fun lastSuccessSource(): UpdateSource? {
        val success = preferences.getLong(KEY_LAST_SUCCESS_MS, -1L).takeIf { it >= 0 } ?: return null
        if (success != lastCheckMs()) return null
        return when (preferences.getString(KEY_LAST_SOURCE, null)) {
            SOURCE_WEB -> UpdateSource.WEB
            SOURCE_GITHUB -> UpdateSource.GITHUB
            else -> null
        }
    }

    fun isDue(nowMs: Long = System.currentTimeMillis()): Boolean = isUpdateCheckDue(lastCheckMs(), nowMs)

    /** The last valid manifest, re-validated against the current policy. */
    fun cached(): UpdateInfo? = preferences.getString(KEY_LAST_BODY, null)?.let { body ->
        (parseLatestJson(body, policy) as? UpdateParseResult.Ok)?.info
    }

    fun dismissedVersionCode(): Int? = preferences.getInt(KEY_DISMISSED_CODE, -1).takeIf { it >= 0 }

    fun dismiss(versionCode: Int) = preferences.edit { putInt(KEY_DISMISSED_CODE, versionCode) }

    /**
     * Fetches and validates the manifest. Any attempt (also a failed one) counts for the 24 h limit,
     * so an unreachable source is not asked on every start; a failure keeps the previous valid result.
     * Cancellation cancels the HTTP call.
     */
    suspend fun check(nowMs: Long = System.currentTimeMillis()): UpdateCheckOutcome {
        preferences.edit { putLong(KEY_LAST_CHECK_MS, nowMs) }
        val outcome = fixture()?.let(::fromFixture) ?: checkForUpdate(policy, http)
        if (outcome is UpdateCheckOutcome.Found) preferences.edit {
            putString(KEY_LAST_BODY, outcome.body)
            putLong(KEY_LAST_SUCCESS_MS, nowMs)
            putString(KEY_LAST_SOURCE, if (outcome.source == UpdateSource.GITHUB) SOURCE_GITHUB else SOURCE_WEB)
        }
        return outcome
    }

    /** The fixture stands for the web answer (no network, no redirects). */
    private fun fromFixture(text: String): UpdateCheckOutcome = when (val parsed = parseLatestJson(text, policy)) {
        is UpdateParseResult.Ok -> UpdateCheckOutcome.Found(parsed.info, UpdateSource.WEB, text)
        is UpdateParseResult.Rejected -> UpdateCheckOutcome.Failed(UpdateCheckFailure.INVALID)
    }

    private fun fixture(): String? {
        if (!BuildConfig.DEBUG) return null
        val file = File(app.filesDir, UPDATE_FIXTURE_FILE)
        return try {
            if (file.isFile && file.length() <= LATEST_JSON_MAX_BYTES) file.readText() else null
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val SOURCE_WEB = "web"
        const val SOURCE_GITHUB = "github"
    }
}

/**
 * One `GET` per call, OkHttp redirects off (the shared policy decides each hop), no retries, no
 * cookies (OkHttp default jar), no cache, no pooled connections kept. Never throws except
 * cancellation, which cancels the call.
 */
internal class OkHttpUpdateHttp(private val userAgent: String) : UpdateHttp {
    private val client = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    override suspend fun get(url: String): HttpResult {
        val request = try {
            Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()
        } catch (_: IllegalArgumentException) {
            return HttpResult.Failure(HttpResult.Failure.Kind.NETWORK)
        }
        return execute(client.newCall(request))
    }

    /** A body over the limit is reported without being read further. */
    private suspend fun execute(call: Call): HttpResult = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(HttpResult.Failure(HttpResult.Failure.Kind.NETWORK))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    when {
                        it.code != 200 -> HttpResult.Response(it.code, location = it.header("Location"))
                        it.body.contentLength() > LATEST_JSON_MAX_BYTES -> HttpResult.Failure(HttpResult.Failure.Kind.TOO_LARGE)
                        else -> try {
                            readBoundedBytes(it, LATEST_JSON_MAX_BYTES)?.let { bytes -> HttpResult.Response(200, body = bytes) }
                                ?: HttpResult.Failure(HttpResult.Failure.Kind.TOO_LARGE)
                        } catch (_: IOException) {
                            HttpResult.Failure(HttpResult.Failure.Kind.NETWORK)
                        }
                    }
                }
                if (cont.isActive) cont.resume(result)
            }
        })
    }

    /** At most [maxBytes] of the body, or `null` when it is longer. */
    private fun readBoundedBytes(response: Response, maxBytes: Int): ByteArray? {
        val source = response.body.source()
        if (source.request(maxBytes.toLong() + 1)) return null
        return source.buffer.readByteArray()
    }
}
