package cz.netkit.app.speed

import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import cz.netkit.app.wifi.live.LiveRadioSource
import cz.netkit.app.wifi.live.WifiLinkSnapshot
import cz.netkit.app.wifi.live.liveLinkRate
import cz.netkit.app.wifi.live.liveSsid
import cz.netkit.app.wifi.live.selectLiveRadioSource
import cz.netkit.shared.wifi.validRssiDbm

/**
 * Wi-Fi link at a moment of the test. [ssid] is `null` when hidden or redacted (no location
 * permission); the speed test itself needs no location permission.
 */
data class SpeedWifiContext(
    val ssid: String?,
    val frequencyMhz: Int?,
    val rssiDbm: Int?,
    val rxLinkMbps: Int?,
    val txLinkMbps: Int?,
)

/** Same source selection as the live Signal screen ([selectLiveRadioSource]). Blocking binder calls. */
@Suppress("DEPRECATION") // connectionInfo: only source below API 31, fresher radio values above.
internal fun readSpeedWifiContext(snapshot: WifiLinkSnapshot, wifiManager: WifiManager): SpeedWifiContext {
    val callbackInfo = snapshot.info
    val legacy: WifiInfo? = try { wifiManager.connectionInfo } catch (_: Exception) { null }
    val radio = when (selectLiveRadioSource(Build.VERSION.SDK_INT, callbackInfo != null, callbackInfo?.bssid,
        legacy != null, legacy?.bssid)) {
        LiveRadioSource.CALLBACK -> callbackInfo
        LiveRadioSource.LEGACY -> legacy
        LiveRadioSource.NONE -> null
    }
    val identity = callbackInfo ?: legacy
    return SpeedWifiContext(
        ssid = liveSsid(identity?.ssid),
        frequencyMhz = radio?.frequency?.takeIf { it > 0 },
        rssiDbm = validRssiDbm(radio?.rssi),
        rxLinkMbps = radio?.let { liveLinkRate(it.rxLinkSpeedMbps) },
        txLinkMbps = radio?.let { liveLinkRate(it.txLinkSpeedMbps) },
    )
}
