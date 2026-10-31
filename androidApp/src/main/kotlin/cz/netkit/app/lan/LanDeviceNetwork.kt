package cz.netkit.app.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import cz.netkit.shared.lan.DiscoveryConsent
import cz.netkit.shared.lan.LanNetworkIdentity
import cz.netkit.shared.lan.lanNetworkConsentKey
import cz.netkit.shared.lan.normalizeWifiSsid
import java.io.IOException
import java.net.Inet4Address
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal data class LanDeviceNetworkFacts(val gateway: String?, val ssid: String?)

/**
 * Reads the default IPv4 gateway (must lie in the Wi-Fi subnet) and the SSID of the selected
 * Wi-Fi. SSID requires the location permission already declared for the Wi-Fi analyzer; when it
 * is not granted Android redacts it and this returns null (no new permission, no prompt here).
 */
internal class LanDeviceNetworkReader(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    suspend fun read(wifi: LanWifi): LanDeviceNetworkFacts = LanDeviceNetworkFacts(gateway(wifi), ssid(wifi))

    private fun gateway(wifi: LanWifi): String? = connectivity.getLinkProperties(wifi.network)?.routes.orEmpty()
        .filter { it.isDefaultRoute }
        .mapNotNull { (it.gateway as? Inet4Address)?.hostAddress }
        .firstOrNull { wifi.subnet.contains(it) && it != wifi.address }

    private suspend fun ssid(wifi: LanWifi): String? = try {
        if (Build.VERSION.SDK_INT >= 31) ssidFromCallback(wifi.network)
        else @Suppress("DEPRECATION") normalizeWifiSsid(wifiManager?.connectionInfo?.ssid)
    } catch (error: CancellationException) { throw error
    } catch (_: Exception) { null }

    /** Synchronous capabilities are location-redacted on API 31+; a one-shot callback with the flag is not. */
    @RequiresApi(31)
    private suspend fun ssidFromCallback(network: Network): String? = withTimeoutOrNull(1_500) {
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(changed: Network, capabilities: NetworkCapabilities) {
                    if (changed != network || !done.compareAndSet(false, true)) return
                    val ssid = normalizeWifiSsid((capabilities.transportInfo as? WifiInfo)?.ssid)
                    runCatching { connectivity.unregisterNetworkCallback(this) }
                    if (cont.isActive) cont.resume(ssid)
                }
            }
            try {
                connectivity.registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            } catch (_: RuntimeException) {
                if (done.compareAndSet(false, true) && cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }
            // Installed after registration: an earlier cancellation runs this handler immediately.
            cont.invokeOnCancellation { if (done.compareAndSet(false, true)) runCatching { connectivity.unregisterNetworkCallback(callback) } }
        }
    }
}

/**
 * Per-network consent for automatic discovery and the time of the last completed discovery.
 *
 * Keys are HMAC-SHA256 over [cz.netkit.shared.lan.lanNetworkIdentityBytes] with a random
 * per-install key; neither SSID nor gateway is stored or logged in plain text. Storage is the
 * app-private SharedPreferences file `lan-devices` (`allowBackup=false`, backup rules exclude
 * all data). ALLOWED without a known SSID is kept only in memory and only for the same Wi-Fi
 * [android.net.Network]: gateway + subnet alone match many foreign networks (review M2, P1).
 * All methods do disk IO: call them off the main thread.
 */
internal class LanDeviceConsentStore(context: Context) {
    private val preferences = context.getSharedPreferences("lan-devices", Context.MODE_PRIVATE)
    /** Session-only consent, bound to the network handle it was given on. */
    private val session = ConcurrentHashMap<String, Pair<DiscoveryConsent, Long>>()

    @Synchronized
    fun key(identity: LanNetworkIdentity): String {
        val installKey = preferences.getString(INSTALL_KEY, null)?.let(::fromHex)?.takeIf { it.size == 32 }
            ?: ByteArray(32).also { fresh ->
                SecureRandom().nextBytes(fresh)
                if (!preferences.edit().putString(INSTALL_KEY, fresh.joinToString("") { "%02x".format(it) }).commit()) throw IOException("STORAGE")
            }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(installKey, "HmacSHA256")) }
        return lanNetworkConsentKey(identity) { mac.doFinal(it) }
    }

    fun consent(key: String, networkHandle: Long): DiscoveryConsent = session[key]?.takeIf { it.second == networkHandle }?.first
        ?: preferences.getString(CONSENT + key, null)?.let { stored -> DiscoveryConsent.entries.firstOrNull { it.name == stored } }
        ?: DiscoveryConsent.UNKNOWN

    /** Returns false when the disk write failed; the in-memory value is still applied. */
    fun setConsent(key: String, consent: DiscoveryConsent, persistable: Boolean, networkHandle: Long): Boolean {
        val editor = preferences.edit()
        session.remove(key)
        when {
            consent == DiscoveryConsent.UNKNOWN -> editor.remove(CONSENT + key)
            persistable -> editor.putString(CONSENT + key, consent.name)
            else -> { editor.remove(CONSENT + key); session[key] = consent to networkHandle }
        }
        val written = editor.commit()
        if (!written && consent != DiscoveryConsent.UNKNOWN) session[key] = consent to networkHandle
        return written
    }

    fun lastDiscovery(key: String): Long? = preferences.getLong(LAST + key, -1L).takeIf { it >= 0 }

    fun markDiscovery(key: String, elapsedMs: Long) { preferences.edit().putLong(LAST + key, elapsedMs).apply() }

    private fun fromHex(text: String): ByteArray? =
        if (text.length % 2 != 0) null else runCatching { ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()

    private companion object {
        const val INSTALL_KEY = "key.v1"
        const val CONSENT = "consent."
        const val LAST = "last."
    }
}
