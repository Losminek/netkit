package cz.netkit.app.wifi.live

import cz.netkit.shared.wifi.usableBssid

/** Which `WifiInfo` supplies the per-tick radio values (RSSI, link rates, frequency). */
internal enum class LiveRadioSource { CALLBACK, LEGACY, NONE }

/**
 * Pure choice of the radio source for one tick.
 *
 * - API < 31: `NetworkCapabilities.transportInfo` carries no `WifiInfo`; the legacy
 *   `WifiManager.connectionInfo` is the only source (same as `WifiViewModel.readCurrent`).
 * - API ≥ 31: the identity (SSID/BSSID) comes from the long-lived `NetworkCallback` with
 *   `FLAG_INCLUDE_LOCATION_INFO`. The framework publishes new capabilities only on some
 *   changes, so the callback copy of RSSI/link rate can be stale. The legacy reading is used for
 *   the radio values only when it describes the very same AP (equal usable BSSID); otherwise
 *   (roam in progress, redaction) the callback copy wins. Without a callback `WifiInfo` the
 *   legacy reading is a fallback.
 */
internal fun selectLiveRadioSource(
    sdkInt: Int,
    hasCallbackInfo: Boolean,
    callbackBssid: String?,
    hasLegacyInfo: Boolean,
    legacyBssid: String?,
): LiveRadioSource {
    if (sdkInt < 31 || !hasCallbackInfo) return if (hasLegacyInfo) LiveRadioSource.LEGACY else LiveRadioSource.NONE
    val callback = usableBssid(callbackBssid)
    val legacy = usableBssid(legacyBssid)
    return if (hasLegacyInfo && legacy != null && legacy == callback) LiveRadioSource.LEGACY else LiveRadioSource.CALLBACK
}

/** SSID as shown to the user: quotes removed; blank and Android's `<unknown ssid>` are `null`. */
internal fun liveSsid(raw: String?): String? =
    raw?.trim()?.removeSurrounding("\"")?.takeUnless { it.isBlank() || it == "<unknown ssid>" }

/** Android reports unknown link rates as `-1` (`WifiInfo.LINK_SPEED_UNKNOWN`). */
internal fun liveLinkRate(mbps: Int): Int? = mbps.takeIf { it > 0 }
