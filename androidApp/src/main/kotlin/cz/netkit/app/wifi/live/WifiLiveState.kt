package cz.netkit.app.wifi.live

import cz.netkit.shared.cellular.CellularState
import cz.netkit.shared.chart.ChartEvent
import cz.netkit.shared.net.NetKind
import cz.netkit.shared.overview.DnsResolverNote
import cz.netkit.shared.publicip.PublicIpInfo
import cz.netkit.shared.wifi.LIVE_DEFAULT_DNS_NAME
import cz.netkit.shared.wifi.LIVE_DEFAULT_INTERNET_TARGET
import cz.netkit.shared.wifi.LIVE_SAMPLE_INTERVAL_MS
import cz.netkit.shared.wifi.LiveSeries
import cz.netkit.shared.wifi.LiveTimeline
import cz.netkit.shared.wifi.RoamingEvent
import cz.netkit.shared.wifi.SignalQuality
import cz.netkit.shared.wifi.WifiBand
import cz.netkit.shared.wifi.WifiSecurity

/** Ring buffer capacity of the DNS series: 90 samples = 15 minutes at one query per 10 s. */
const val LIVE_DNS_SERIES_CAPACITY = 90
/** DNS probe period. */
const val LIVE_DNS_INTERVAL_MS = 10_000L
/** ICMP echo timeout for gateway and internet target (≤ sampling period, so ≤ 1 echo in flight). */
const val LIVE_ECHO_TIMEOUT_MS = 1_000
/** DNS query timeout. */
const val LIVE_DNS_TIMEOUT_MS = 2_000L

/**
 * Complete state of the Overview screen ("Přehled", live Wi-Fi and mobile telemetry), published by
 * [WifiLiveViewModel.state]. The 1 Hz series keep 15 minutes (900 samples) in memory only.
 *
 * All timestamps are `SystemClock.elapsedRealtime()` milliseconds (monotonic, not wall clock).
 * Series keep their data after [running] becomes `false`, so a paused screen still shows the
 * last graphs; a timestamp gap > ~1.5 × [sampleIntervalMs] means "not sampled", whereas a
 * `null` sample value means "sampled but lost" (and counts into [cz.netkit.shared.wifi.LiveStats.lossPercent]).
 *
 * @property running sampling loops are active (between `setActive(true)` and `setActive(false)`).
 * @property link newest reading of the connected Wi-Fi link; `null` before the first reading or
 *   while no Wi-Fi network is connected (then [issues] contains [WifiLiveIssue.NO_WIFI]).
 * @property quality classification of the newest valid RSSI ([cz.netkit.shared.wifi.signalQuality]).
 * @property signalDbm RSSI in dBm, 1 Hz. `null` sample = no Wi-Fi or invalid RSSI.
 * @property rxLinkMbps negotiated RX link rate (PHY rate reported by the driver), Mb/s, 1 Hz.
 * @property txLinkMbps negotiated TX link rate, Mb/s, 1 Hz.
 * @property deviceRxMbps measured receive throughput of the WHOLE DEVICE (all interfaces incl.
 *   mobile data/VPN/tethering, `TrafficStats.getTotalRxBytes`), Mb/s averaged over each ~1 s
 *   interval. A `null` sample marks a counter reset. Not Wi-Fi-only.
 * @property deviceTxMbps the same for transmit (`TrafficStats.getTotalTxBytes`).
 * @property gatewayLatency ICMP echo RTT to the Wi-Fi default IPv4 gateway, 1 Hz.
 * @property internetLatency ICMP echo RTT to [LiveLatency.target] (default `1.1.1.1`), 1 Hz,
 *   bound to the active network (Wi-Fi, mobile data, VPN).
 * @property dns DNS A-record lookup latency on the active network, every 10 s.
 * @property roamingEvents detected BSSID changes, oldest → newest, at most 50.
 * @property timeline common x axis of the charts, built from [signalDbm] (one slot per link
 *   reading, plus one empty slot per interval missed during a pause, so indices stay uniform
 *   in time). Chart lines are `timeline.alignedValues(series.samples)`.
 * @property chartEvents roaming, channel changes and packet-loss runs placed on [timeline] slots;
 *   indices move left and events disappear as the oldest samples drop out of the history.
 * @property issues typed reasons why some data is missing; empty when everything is available.
 *   Several can apply at once. Localized texts: [wifiLiveIssueText].
 * @property sampleIntervalMs sampling period of the 1 Hz series.
 * @property network the active (default) network; `null` without one or before [networkKnown].
 * @property cellular mobile signal of the visible SIMs (`null` before the first report).
 * @property hasTelephony the device has telephony (a SIM slot); false on tablets without one.
 * @property cellularPermissionNeeded offer the READ_PHONE_STATE dialog ("Zobrazit obě SIM").
 * @property cellularSignalDbm RSRP (3G/2G: RSCP/RSSI) of the mobile gauge's SIM on [timeline], 1 Hz
 *   sampling of the latest modem report; `null` = no SIM or no value.
 * @property cellularChartEvents standard changes and data SIM switches placed on [timeline].
 * @property publicIp public IP lookup (path card).
 */
data class WifiLiveState(
    val running: Boolean = false,
    val link: LiveLink? = null,
    val quality: SignalQuality = SignalQuality.UNKNOWN,
    val signalDbm: LiveSeries = LiveSeries(),
    val rxLinkMbps: LiveSeries = LiveSeries(),
    val txLinkMbps: LiveSeries = LiveSeries(),
    val deviceRxMbps: LiveSeries = LiveSeries(),
    val deviceTxMbps: LiveSeries = LiveSeries(),
    val gatewayLatency: LiveLatency = LiveLatency(target = null),
    val internetLatency: LiveLatency = LiveLatency(target = LIVE_DEFAULT_INTERNET_TARGET),
    val dns: LiveDns = LiveDns(name = LIVE_DEFAULT_DNS_NAME),
    val roamingEvents: List<RoamingEvent> = emptyList(),
    val timeline: LiveTimeline = LiveTimeline.EMPTY,
    val chartEvents: List<ChartEvent> = emptyList(),
    val issues: Set<WifiLiveIssue> = emptySet(),
    val sampleIntervalMs: Long = LIVE_SAMPLE_INTERVAL_MS,
    val network: OverviewNetwork? = null,
    val networkKnown: Boolean = false,
    val cellular: CellularState? = null,
    val hasTelephony: Boolean = false,
    val cellularPermissionNeeded: Boolean = false,
    val cellularSignalDbm: LiveSeries = LiveSeries(),
    val cellularChartEvents: List<ChartEvent> = emptyList(),
    val publicIp: PublicIpUiState = PublicIpUiState(),
)

/**
 * The active network as the Overview shows it (public projection of the internal
 * `ActiveNetworkInfo`). [networkHandle] identifies the network while it exists.
 */
data class OverviewNetwork(
    val kind: NetKind,
    val metered: Boolean,
    val viaVpn: Boolean,
    val interfaceName: String?,
    val localIpv4: String?,
    val localIpv6: String?,
    val networkHandle: Long,
)

/**
 * Public IP lookup of the path card. [enabled] is the stored setting (default on, valid once
 * [settingLoaded]); [info] belongs to the current network only; [loading] while a lookup runs.
 */
data class PublicIpUiState(
    val enabled: Boolean = true,
    val settingLoaded: Boolean = false,
    val loading: Boolean = false,
    val info: PublicIpInfo? = null,
)

/**
 * One reading of the connected Wi-Fi link.
 *
 * @property ssid SSID without quotes; `null` when hidden or redacted (missing location permission
 *   or location disabled).
 * @property bssid normalized lowercase BSSID; `null` when redacted (`02:00:00:00:00:00`) or unknown.
 * @property rssiDbm RSSI in dBm, `null` when invalid. Refresh rate is set by the framework/driver
 *   (often every ~3 s with the screen on), so consecutive 1 Hz samples can repeat a value.
 * @property rxLinkMbps negotiated RX link rate in Mb/s, `null` when unknown.
 * @property txLinkMbps negotiated TX link rate in Mb/s, `null` when unknown.
 * @property frequencyMhz primary channel frequency, `null` when unknown.
 * @property channel primary channel number derived from [frequencyMhz].
 * @property band band derived from [frequencyMhz].
 * @property ipv4Address the phone's IPv4 address on the Wi-Fi network.
 * @property gatewayIpv4 IPv4 default gateway of the Wi-Fi network (target of [WifiLiveState.gatewayLatency]).
 * @property wifiStandard negotiated Wi-Fi standard (API 30+), `null` when unknown.
 * @property security security of the connection (API 31+), `null` when unknown.
 */
data class LiveLink(
    val elapsedMs: Long,
    val ssid: String?,
    val bssid: String?,
    val rssiDbm: Int?,
    val rxLinkMbps: Int?,
    val txLinkMbps: Int?,
    val frequencyMhz: Int?,
    val channel: Int?,
    val band: WifiBand,
    val ipv4Address: String?,
    val gatewayIpv4: String?,
    val wifiStandard: LiveWifiStandard? = null,
    val security: WifiSecurity? = null,
)

/** Wi-Fi standard of the connected link (`WifiInfo.getWifiStandard`). */
enum class LiveWifiStandard { LEGACY, N, AC, AX, AD, BE }

/**
 * Latency of one ICMP target.
 *
 * @property target numeric IPv4 being probed; `null` when unknown (no gateway).
 * @property series RTT in ms; `null` sample = echo lost (timeout/unreachable) or no Wi-Fi.
 *   [LiveSeries.stats] gives last/min/avg/max/loss/jitter over the buffer (15 min).
 * @property status outcome of the newest probe attempt.
 */
data class LiveLatency(
    val target: String?,
    val series: LiveSeries = LiveSeries(),
    val status: LiveProbeStatus = LiveProbeStatus.IDLE,
)

/** Outcome of the newest probe attempt of a latency target. */
enum class LiveProbeStatus {
    /** No attempt yet (just started or cleared). */
    IDLE,
    /** Reply received; value is in the series. */
    OK,
    /** No reply within the timeout or the target reported unreachable; a `null` sample was added. */
    LOST,
    /** No Wi-Fi network (gateway); a `null` sample was added. */
    NO_WIFI,
    /** No active network at all (internet target); a `null` sample was added. */
    NO_NETWORK,
    /** Target unknown (e.g. no IPv4 default gateway); no sample added. */
    NO_TARGET,
    /** The device does not allow unprivileged ICMP echo sockets; no sample added. */
    ICMP_UNAVAILABLE,
}

/**
 * DNS lookup latency.
 *
 * @property name queried name (A record, resolver cache bypassed).
 * @property lastMs newest successful lookup time in ms; `null` when the newest attempt failed or none yet.
 * @property lastElapsedMs time of the newest attempt.
 * @property lastRcode DNS RCODE of the newest answer when [status] is [LiveDnsStatus.ERROR_RCODE].
 * @property series lookup time in ms, `null` = failed attempt; capacity [LIVE_DNS_SERIES_CAPACITY].
 * @property resolver the resolver the query goes to (first DNS server, or Private DNS), decision O-5.
 */
data class LiveDns(
    val name: String,
    val lastMs: Double? = null,
    val lastElapsedMs: Long? = null,
    val status: LiveDnsStatus = LiveDnsStatus.IDLE,
    val lastRcode: Int? = null,
    val series: LiveSeries = LiveSeries(capacity = LIVE_DNS_SERIES_CAPACITY),
    val resolver: DnsResolverNote = DnsResolverNote.Unknown,
)

enum class LiveDnsStatus {
    IDLE,
    OK,
    /** No answer within [LIVE_DNS_TIMEOUT_MS]. */
    TIMEOUT,
    /** Resolver error (network/parse) without an answer. */
    FAILED,
    /** Answer with non-zero RCODE (e.g. 3 = NXDOMAIN); see [LiveDns.lastRcode]. */
    ERROR_RCODE,
    /** No active network. */
    NO_NETWORK,
}

/** Typed reasons for missing data; never raw exception text. */
enum class WifiLiveIssue {
    /** No connected Wi-Fi network (Wi-Fi off, disconnected, or network lost). */
    NO_WIFI,
    /** `ACCESS_FINE_LOCATION` not granted: SSID/BSSID redacted, roaming cannot be detected. */
    PERMISSION_MISSING,
    /** System location disabled: SSID/BSSID redacted. */
    LOCATION_DISABLED,
    /** Unprivileged ICMP echo socket unavailable: latency cannot be measured. */
    ICMP_UNAVAILABLE,
    /** Wi-Fi network has no IPv4 default gateway: gateway latency cannot be measured. */
    NO_GATEWAY,
    /** `TrafficStats` counters unsupported (`-1`): device throughput unavailable. */
    TRAFFIC_STATS_UNAVAILABLE,
    /** Registering the network callback failed: link data unavailable. */
    LINK_MONITOR_FAILED,
}
