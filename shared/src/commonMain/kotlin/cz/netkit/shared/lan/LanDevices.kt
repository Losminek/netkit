package cz.netkit.shared.lan

/*
 * Device list for the "Devices" screen (M2): pure model, merge, sorting, display names
 * and the per-network consent / rate-limit policy for automatic discovery.
 * No platform code here; Android supplies clocks, sockets and the keyed hash.
 */

/** Where a device name came from. Declaration order is display priority (highest first). */
enum class LanNameSource { NETBIOS, MDNS, PTR }

/** A sanitized name; build through [lanDeviceName], never from raw network bytes. */
data class LanDeviceName(val source: LanNameSource, val value: String)

data class OpenPort(val port: Int, val service: String = portService(port), val banner: String? = null) {
    init { require(port in 1..65535) }
}

/** How liveness was proven. SELF is the phone itself, never probed. */
enum class LanAliveMethod { ICMP, TCP, TCP_RST, SELF }

/**
 * One IPv4 host on the current Wi-Fi.
 *
 * Times are monotonic (`elapsedRealtime`) milliseconds, never wall clock. [stale] means the
 * device was not seen in the latest *completed* discovery; it is removed after
 * [LAN_DEVICE_STALE_RETENTION_MS]. [openPorts] is sorted by port; [portsScannedElapsedMs]
 * is set only after a port scan completed (a cancelled scan leaves partial ports and null).
 */
data class LanDevice(
    val address: String,
    val names: List<LanDeviceName> = emptyList(),
    val netBiosGroup: String? = null,
    val isGateway: Boolean = false,
    val isSelf: Boolean = false,
    val firstSeenElapsedMs: Long,
    val lastSeenElapsedMs: Long,
    val lastRttMs: Double? = null,
    val method: LanAliveMethod,
    val stale: Boolean = false,
    val openPorts: List<OpenPort> = emptyList(),
    val portsScannedElapsedMs: Long? = null,
    /** DNS-SD service types announced by this device over mDNS (e.g. `_ipp._tcp`), sorted. */
    val mdnsServices: List<String> = emptyList(),
    /** SSDP reply and, if fetched, the UPnP device description; null = no SSDP reply seen. */
    val upnp: LanUpnpInfo? = null,
) {
    init { require(ipv4Number(address) != null) }
    /** Display text and its source; source null means the IPv4 address itself. */
    val displayName: LanDisplayName get() = lanDeviceDisplayName(this)
}

data class LanDisplayName(val text: String, val source: LanNameSource?)

/** A positive probe result; names usually arrive later via [updateLanDeviceNames]. */
data class LanDeviceObservation(val address: String, val method: LanAliveMethod, val rttMs: Double? = null)

const val LAN_DEVICE_NAME_MAX = 64
const val LAN_DEVICE_STALE_RETENTION_MS = 30 * 60_000L
const val LAN_DEVICE_MAX = 4096

/** PTR must be a valid DNS name; NetBIOS/mDNS are sanitized display text. Empty result → null. */
fun lanDeviceName(source: LanNameSource, raw: String?): LanDeviceName? {
    if (raw == null) return null
    val value = when (source) {
        LanNameSource.PTR -> safePtrName(raw.trim())?.take(LAN_DEVICE_NAME_MAX)
        else -> sanitizeLanText(raw, LAN_DEVICE_NAME_MAX).trim()
    }
    return value?.takeIf { it.isNotEmpty() }?.let { LanDeviceName(source, it) }
}

/** One name per source: a new value replaces the old one, a missing source keeps the old one. */
fun mergeLanDeviceNames(old: List<LanDeviceName>, new: List<LanDeviceName>): List<LanDeviceName> =
    LanNameSource.entries.mapNotNull { source -> new.lastOrNull { it.source == source } ?: old.firstOrNull { it.source == source } }

/** Priority NetBIOS > mDNS > PTR > IPv4 address. */
fun lanDeviceDisplayName(device: LanDevice): LanDisplayName =
    LanNameSource.entries.firstNotNullOfOrNull { source -> device.names.firstOrNull { it.source == source } }
        ?.let { LanDisplayName(it.value, it.source) } ?: LanDisplayName(device.address, null)

/** Gateway first, then numeric IPv4 (not lexicographic). */
fun sortLanDevices(devices: List<LanDevice>): List<LanDevice> =
    devices.sortedWith(compareByDescending<LanDevice> { it.isGateway }.thenBy { ipv4Number(it.address) ?: Long.MAX_VALUE })

/**
 * Insert or refresh one device. Keeps names, ports and firstSeen; clears stale.
 * A new device beyond [LAN_DEVICE_MAX] is dropped (bounded memory).
 */
fun upsertLanDevice(
    devices: List<LanDevice>, observation: LanDeviceObservation, nowElapsedMs: Long,
    gateway: String?, self: String?,
): List<LanDevice> {
    if (ipv4Number(observation.address) == null) return devices
    val existing = devices.firstOrNull { it.address == observation.address }
    val updated = existing?.copy(
        lastSeenElapsedMs = maxOf(existing.lastSeenElapsedMs, nowElapsedMs),
        lastRttMs = observation.rttMs ?: existing.lastRttMs,
        method = observation.method, stale = false,
        isGateway = observation.address == gateway, isSelf = observation.address == self,
    ) ?: if (devices.size >= LAN_DEVICE_MAX) return devices else LanDevice(
        observation.address, isGateway = observation.address == gateway, isSelf = observation.address == self,
        firstSeenElapsedMs = nowElapsedMs, lastSeenElapsedMs = nowElapsedMs,
        lastRttMs = observation.rttMs, method = observation.method,
    )
    return sortLanDevices(devices.filterNot { it.address == observation.address } + updated)
}

/** Merge late-arriving names (PTR, NetBIOS, mDNS). Unknown address: unchanged. */
fun updateLanDeviceNames(devices: List<LanDevice>, address: String, names: List<LanDeviceName>, netBiosGroup: String? = null): List<LanDevice> =
    devices.map { device ->
        if (device.address != address) device
        else device.copy(names = mergeLanDeviceNames(device.names, names),
            netBiosGroup = netBiosGroup?.let { sanitizeLanText(it, LAN_DEVICE_NAME_MAX).trim().ifEmpty { null } } ?: device.netBiosGroup)
    }

/** A reply from live ping in the detail: refresh RTT and lastSeen without a full scan. */
fun recordLanDeviceReply(devices: List<LanDevice>, address: String, rttMs: Double, nowElapsedMs: Long): List<LanDevice> =
    devices.map { if (it.address == address) it.copy(lastRttMs = rttMs, lastSeenElapsedMs = maxOf(it.lastSeenElapsedMs, nowElapsedMs), stale = false) else it }

/**
 * Called only after a *completed* discovery that started at [scanStartedElapsedMs]:
 * devices not seen since then become stale; stale devices older than [retentionMs] are removed.
 * A cancelled/failed scan must not call this (it would mark unprobed hosts stale).
 */
fun finishLanDiscovery(
    devices: List<LanDevice>, scanStartedElapsedMs: Long, nowElapsedMs: Long,
    retentionMs: Long = LAN_DEVICE_STALE_RETENTION_MS,
): List<LanDevice> = devices.mapNotNull { device ->
    val seen = device.lastSeenElapsedMs >= scanStartedElapsedMs || device.isSelf
    when {
        seen -> device.copy(stale = false)
        nowElapsedMs - device.lastSeenElapsedMs > retentionMs -> null
        else -> device.copy(stale = true)
    }
}

/** Replace the open-port list of one device; [scannedElapsedMs] non-null only for a completed scan. */
fun withOpenPorts(devices: List<LanDevice>, address: String, ports: List<OpenPort>, scannedElapsedMs: Long?): List<LanDevice> =
    devices.map { device ->
        if (device.address != address) device
        else device.copy(openPorts = ports.distinctBy { it.port }.sortedBy { it.port }, portsScannedElapsedMs = scannedElapsedMs)
    }

// ---------------------------------------------------------------------------------------
// Per-network consent and rate limit for automatic discovery.

/** UNKNOWN → the UI asks; ALLOWED → automatic discovery; DENIED → manual only. */
enum class DiscoveryConsent { UNKNOWN, ALLOWED, DENIED }

/** [ssid] null = not available (no location permission / redacted). [gateway] numeric IPv4 or null. */
data class LanNetworkIdentity(val ssid: String?, val gateway: String?, val subnet: Ipv4Subnet)

const val AUTO_DISCOVERY_MIN_INTERVAL_MS = 5 * 60_000L

/** Android quotes UTF-8 SSIDs and returns a sentinel when location access is missing. */
fun normalizeWifiSsid(raw: String?): String? {
    val value = raw?.trim() ?: return null
    if (value.isEmpty() || value == "<unknown ssid>") return null
    val unquoted = if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1) else value
    return unquoted.takeIf { it.isNotEmpty() && it.length <= 64 }
}

/**
 * Unambiguous canonical bytes (length-prefixed fields, version tag) fed to a keyed hash.
 * The raw SSID never leaves this function except as hash input.
 */
fun lanNetworkIdentityBytes(identity: LanNetworkIdentity): ByteArray {
    fun field(tag: String, value: String?): ByteArray {
        val bytes = value?.encodeToByteArray()
        val header = if (bytes == null) "$tag:-;" else "$tag:${bytes.size}:"
        return header.encodeToByteArray() + (bytes ?: ByteArray(0))
    }
    val gateway = identity.gateway?.takeIf { ipv4Number(it) != null }
    return "netkit-lan-consent-v1;".encodeToByteArray() + field("ssid", identity.ssid) +
        field("gw", gateway) + field("net", identity.subnet.toString())
}

/** Hex of a keyed hash (Android: HMAC-SHA256 with a per-install key). Output at least 128 bits. */
fun lanNetworkConsentKey(identity: LanNetworkIdentity, keyedHash: (ByteArray) -> ByteArray): String {
    val digest = keyedHash(lanNetworkIdentityBytes(identity))
    require(digest.size >= 16)
    return digest.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

/**
 * ALLOWED is remembered only when the SSID is known: gateway + subnet alone (e.g.
 * 192.168.1.1/24) are shared by many foreign networks. Without SSID, ALLOWED lasts for the
 * current screen session. DENIED is always safe to remember.
 */
fun lanConsentIsPersistable(consent: DiscoveryConsent, identity: LanNetworkIdentity): Boolean = when (consent) {
    DiscoveryConsent.DENIED -> true
    DiscoveryConsent.ALLOWED -> identity.ssid != null
    DiscoveryConsent.UNKNOWN -> false
}

/**
 * Automatic discovery runs only for ALLOWED and at most once per [AUTO_DISCOVERY_MIN_INTERVAL_MS]
 * since the last completed discovery. A last time in the future (reboot reset the monotonic
 * clock) counts as expired. Manual refresh does not consult this policy.
 */
fun shouldAutoDiscover(consent: DiscoveryConsent, lastCompletedElapsedMs: Long?, nowElapsedMs: Long): Boolean {
    if (consent != DiscoveryConsent.ALLOWED) return false
    val last = lastCompletedElapsedMs ?: return true
    return nowElapsedMs < last || nowElapsedMs - last >= AUTO_DISCOVERY_MIN_INTERVAL_MS
}

/**
 * Range for the Devices screen: the phone's own subnet narrowed to at most /24 (same as the
 * LAN tool default). Null if it would need any confirmation (outside Wi-Fi, non-private).
 */
fun lanDevicesRange(address: String, prefix: Int): Ipv4Subnet? {
    if (prefix !in 0..32) return null
    val own = Ipv4Subnet.parse("$address/$prefix") ?: return null
    val range = Ipv4Subnet.parse("$address/${maxOf(prefix, 24)}") ?: return null
    return range.takeIf { targetConfirmationReasons(it, own).isEmpty() }
}

// ---------------------------------------------------------------------------------------
// Identification (mDNS services, SSDP/UPnP) — see MdnsIdentity.kt and UpnpIdentity.kt.

/**
 * What SSDP/UPnP told about a device. [server]/[usn] come from the SSDP reply; the rest from
 * the device description (null when it was not fetched or did not say). [openUrl] is set only
 * for a presentation URL that [upnpPresentationTarget] accepted; the UI opens it only on tap.
 */
data class LanUpnpInfo(
    val server: String? = null, val usn: String? = null,
    val friendlyName: String? = null, val manufacturer: String? = null, val modelName: String? = null,
    val modelNumber: String? = null, val deviceType: String? = null,
    val presentationUrl: String? = null, val openUrl: String? = null,
) {
    val hasDescription: Boolean get() = listOfNotNull(friendlyName, manufacturer, modelName, modelNumber, deviceType, presentationUrl).isNotEmpty()
}

/** Union with the known service types; sorted, valid DNS-SD types only, at most [MDNS_MAX_SERVICES]. */
fun updateLanDeviceServices(devices: List<LanDevice>, address: String, services: Collection<String>): List<LanDevice> {
    val valid = services.filter { type -> dnsName("$type.local")?.let(::mdnsServiceType) == type }
    if (valid.isEmpty()) return devices
    return devices.map { device ->
        if (device.address != address) device
        else device.copy(mdnsServices = (device.mdnsServices + valid).distinct().sorted().take(MDNS_MAX_SERVICES))
    }
}

/** Replaces the UPnP info of one device; a reply without description keeps an earlier description. */
fun updateLanDeviceUpnp(devices: List<LanDevice>, address: String, info: LanUpnpInfo): List<LanDevice> =
    devices.map { device ->
        if (device.address != address) device
        else device.copy(upnp = if (!info.hasDescription && device.upnp?.hasDescription == true)
            device.upnp.copy(server = info.server ?: device.upnp.server, usn = info.usn ?: device.upnp.usn) else info)
    }

/**
 * Builds [LanUpnpInfo] from one SSDP reply and an optional parsed description fetched from
 * [target]. All text is already sanitized by the parsers.
 */
fun lanUpnpInfo(response: SsdpResponse, description: UpnpDescription?, target: HttpTarget?, range: Ipv4Subnet): LanUpnpInfo =
    LanUpnpInfo(
        server = response.server, usn = response.usn,
        friendlyName = description?.friendlyName, manufacturer = description?.manufacturer,
        modelName = description?.modelName, modelNumber = description?.modelNumber, deviceType = description?.deviceType,
        presentationUrl = description?.presentationUrl,
        openUrl = target?.let { upnpPresentationTarget(description?.presentationUrl, it, range) },
    )
