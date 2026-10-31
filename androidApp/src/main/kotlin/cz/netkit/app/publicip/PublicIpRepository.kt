package cz.netkit.app.publicip

import android.content.Context
import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.edit
import cz.netkit.app.BuildConfig
import cz.netkit.app.speed.boundTo
import cz.netkit.app.speed.readBounded
import cz.netkit.shared.publicip.AsnName
import cz.netkit.shared.publicip.AsnOrigin
import cz.netkit.shared.publicip.IpFamily
import cz.netkit.shared.publicip.PublicAddress
import cz.netkit.shared.publicip.PublicIpInfo
import cz.netkit.shared.publicip.cymruAsNameQueryName
import cz.netkit.shared.publicip.cymruOriginQueryName
import cz.netkit.shared.publicip.encodeTxtQuery
import cz.netkit.shared.publicip.isPublicIpCacheFresh
import cz.netkit.shared.publicip.parseCloudflareTrace
import cz.netkit.shared.publicip.parseCymruAsName
import cz.netkit.shared.publicip.selectCymruOrigin
import cz.netkit.shared.publicip.shortProviderName
import cz.netkit.shared.publicip.txtAnswersFor
import java.io.IOException
import java.net.Proxy
import java.security.SecureRandom
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Cloudflare trace over IP literals, so no DNS is involved and each family is reached on its own. */
private const val TRACE_V4_URL = "https://1.1.1.1/cdn-cgi/trace"
private const val TRACE_V6_URL = "https://[2606:4700:4700::1111]/cdn-cgi/trace"
/** Upper bound of one step (trace request, one DNS query). */
private const val STEP_TIMEOUT_MS = 5_000L
/** Trace body limit, as [parseCloudflareTrace] accepts it. */
private const val TRACE_MAX_BYTES = 4_096
/** Networks remembered in the cache (memory only); the oldest is dropped beyond this. */
private const val CACHE_NETWORKS = 8

private const val PREFERENCES = "overview"
private const val KEY_LOOKUP_ENABLED = "public_ip.enabled"

/**
 * Public IP and provider of the active network (M4 F5, decision O-1).
 *
 * - IP: `GET https://1.1.1.1/cdn-cgi/trace` and `https://[2606:4700:4700::1111]/cdn-cgi/trace`
 *   (OkHttp, sockets and DNS bound to the given [Network], no proxy, no redirects, 5 s, body
 *   at most 4 KiB). A failed IPv6 request is not an error; the result needs at least one family.
 * - Provider: Team Cymru DNS TXT through the system resolver of the same network
 *   (`DnsResolver.rawQuery`, `FLAG_NO_CACHE_LOOKUP`): origin of the IPv4 (or IPv6 when there is
 *   no IPv4), then the AS name. A failed lookup keeps the IP without a provider.
 * - Cache: memory only, per network key ([cz.netkit.shared.overview.publicIpNetworkKey]), fresh
 *   for 10 min ([isPublicIpCacheFresh]); the clock is `elapsedRealtime`. Nothing is written to disk.
 * - Setting "look up the public IP" (default on) in the app-private SharedPreferences file
 *   `overview`, excluded from backups like every other app file. Off clears the cache.
 *
 * Disk access ([isLookupEnabled], [setLookupEnabled]) belongs off the main thread.
 */
internal class PublicIpRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val userAgent = "NetKit/${BuildConfig.VERSION_NAME} (Android)"
    private val random = SecureRandom()
    private val direct = Executor { it.run() }
    private val cache = LinkedHashMap<String, PublicIpInfo>()
    private val client by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .callTimeout(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }

    fun isLookupEnabled(): Boolean = preferences.getBoolean(KEY_LOOKUP_ENABLED, true)

    fun setLookupEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_LOOKUP_ENABLED, enabled) }
        if (!enabled) clearCache()
    }

    /** A fresh cached result for [networkKey], otherwise `null`. */
    fun cached(networkKey: String?, nowMs: Long = SystemClock.elapsedRealtime()): PublicIpInfo? = synchronized(cache) {
        val key = networkKey ?: return null
        cache[key]?.takeIf { isPublicIpCacheFresh(it, key, key, nowMs) }
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    /**
     * Looks the public IP and provider up on [network] and caches the result under [networkKey].
     * Returns `null` when neither address family answered. Cancellation stops the HTTP calls and
     * the DNS queries.
     */
    suspend fun fetch(network: Network, networkKey: String?): PublicIpInfo? {
        val bound = client.boundTo(network)
        val (v4, v6) = coroutineScope {
            val v4 = async { trace(bound, TRACE_V4_URL, IpFamily.V4) }
            val v6 = async { trace(bound, TRACE_V6_URL, IpFamily.V6) }
            v4.await() to v6.await()
        }
        if (v4 == null && v6 == null) return null
        val origin = (v4 ?: v6)?.let { origin(network, it) }
        val asn = origin?.asns?.firstOrNull()
        val name = asn?.let { asName(network, it) }
        val info = PublicIpInfo(
            v4 = v4, v6 = v6, asn = asn, prefix = origin?.prefix,
            provider = name?.name?.let(::shortProviderName)?.takeIf { it.isNotBlank() },
            providerRaw = name?.name, country = name?.country ?: origin?.country,
            fetchedAtMs = SystemClock.elapsedRealtime(),
        )
        networkKey?.let { key ->
            synchronized(cache) {
                cache.remove(key)
                cache[key] = info
                while (cache.size > CACHE_NETWORKS) cache.remove(cache.keys.first())
            }
        }
        return info
    }

    private suspend fun trace(client: OkHttpClient, url: String, family: IpFamily): PublicAddress? {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Cache-Control", "no-cache").build()
        val body = execute(client.newCall(request)) ?: return null
        return parseCloudflareTrace(body, family)
    }

    /** Body of a 200 response up to [TRACE_MAX_BYTES], otherwise `null`; never throws. */
    private suspend fun execute(call: Call): String? = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val text = response.use {
                    if (it.code != 200) null else try { readBounded(it.body, TRACE_MAX_BYTES) } catch (_: IOException) { null }
                }
                if (cont.isActive) cont.resume(text)
            }
        })
    }

    private suspend fun origin(network: Network, address: PublicAddress): AsnOrigin? {
        val name = runCatching { cymruOriginQueryName(address) }.getOrNull() ?: return null
        return selectCymruOrigin(txt(network, name))
    }

    private suspend fun asName(network: Network, asn: Long): AsnName? {
        val name = runCatching { cymruAsNameQueryName(asn) }.getOrNull() ?: return null
        return txt(network, name).firstNotNullOfOrNull(::parseCymruAsName)?.takeIf { it.asn == asn }
    }

    /** TXT answers of one recursive query on [network]; empty on any failure or after [STEP_TIMEOUT_MS]. */
    private suspend fun txt(network: Network, name: String): List<String> = withTimeoutOrNull(STEP_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            val id = random.nextInt(65_536)
            val query = runCatching { encodeTxtQuery(id, name) }.getOrNull()
            if (query == null) {
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            try {
                DnsResolver.getInstance().rawQuery(network, query, DnsResolver.FLAG_NO_CACHE_LOOKUP, direct, signal,
                    object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) {
                            if (cont.isActive) cont.resume(if (rcode == 0) txtAnswersFor(answer, id, name) else emptyList())
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    })
            } catch (_: RuntimeException) {
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    } ?: emptyList()
}
