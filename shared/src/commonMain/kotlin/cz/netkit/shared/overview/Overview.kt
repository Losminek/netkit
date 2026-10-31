package cz.netkit.shared.overview

import cz.netkit.shared.cellular.CellRat
import cz.netkit.shared.cellular.CellularSim
import cz.netkit.shared.chart.ChartEventKind
import cz.netkit.shared.net.NetKind
import cz.netkit.shared.publicip.IpFamily
import cz.netkit.shared.publicip.PublicAddressRejection
import cz.netkit.shared.publicip.PublicIpInfo
import cz.netkit.shared.publicip.classifyPublicAddress
import cz.netkit.shared.wifi.LIVE_EVENT_LIMIT
import cz.netkit.shared.wifi.LiveEvent
import cz.netkit.shared.wifi.WifiBand
import cz.netkit.shared.wifi.bandForFrequency
import cz.netkit.shared.wifi.channelForFrequency

/*
 * Pure logic of the Overview screen ("Přehled", M4 A3): which layout, the public IP cache key,
 * the provider node of the path card, the DNS row note, SIM order and mobile chart events.
 * No `android.*`; the Android layer passes plain values.
 */

// --- Layout --------------------------------------------------------------------------------------

/**
 * Main card of the Overview.
 * - [WIFI]: a Wi-Fi network is connected (also under a VPN). Wi-Fi gauge, compact mobile card.
 * - [CELLULAR]: no Wi-Fi and the device has telephony. Mobile gauge (RSRP) is the main card.
 * - [ETHERNET]: wired default network without Wi-Fi; only the path card and a compact mobile card.
 * - [NO_TELEPHONY]: neither Wi-Fi nor telephony (tablet): the Wi-Fi card with "not connected".
 */
enum class OverviewMode { WIFI, CELLULAR, ETHERNET, NO_TELEPHONY }

fun overviewMode(activeKind: NetKind?, wifiConnected: Boolean, hasTelephony: Boolean): OverviewMode = when {
    wifiConnected -> OverviewMode.WIFI
    activeKind == NetKind.ETHERNET -> OverviewMode.ETHERNET
    hasTelephony -> OverviewMode.CELLULAR
    else -> OverviewMode.NO_TELEPHONY
}

// --- Public IP cache key -------------------------------------------------------------------------

/**
 * Cache key of the public IP for one network: transport + SSID on Wi-Fi, transport +
 * subscription id on mobile data. Without that identity (SSID redacted, VPN, ethernet, data SIM
 * not known yet) the network handle stands in, so the key still changes with the network.
 * `null` without any network (then [cz.netkit.shared.publicip.isPublicIpCacheFresh] always misses).
 */
fun publicIpNetworkKey(kind: NetKind?, ssid: String?, subscriptionId: Int?, networkHandle: Long?): String? {
    kind ?: return null
    val identity = when (kind) {
        NetKind.WIFI -> ssid?.takeIf { it.isNotBlank() }?.let { "ssid:$it" }
        NetKind.CELLULAR -> subscriptionId?.takeIf { it >= 0 }?.let { "sub:$it" }
        NetKind.ETHERNET, NetKind.VPN, NetKind.OTHER -> null
    } ?: networkHandle?.let { "net:$it" } ?: return null
    return "${kind.name}|$identity"
}

// --- Path card: provider node ----------------------------------------------------------------------

/** State of the provider node (first node of the path card). */
enum class ProviderNodeState {
    /** The user switched public IP lookup off: nothing is sent. */
    DISABLED,
    /** No active network. */
    NO_NETWORK,
    /** Lookup running and nothing to show yet. */
    LOADING,
    /** Public address known. */
    READY,
    /** Public address known and the phone sits behind the operator's NAT (CGNAT). */
    CARRIER_NAT,
    /** Lookup finished without a public address (service down, blocked, timeout). */
    UNAVAILABLE,
}

/**
 * Provider node state. A known [info] wins over [loading] (a refresh keeps the old values
 * visible); an [info] without any address counts as unavailable.
 */
fun providerNodeState(
    enabled: Boolean,
    hasNetwork: Boolean,
    loading: Boolean,
    info: PublicIpInfo?,
    carrierNat: Boolean,
): ProviderNodeState = when {
    !enabled -> ProviderNodeState.DISABLED
    !hasNetwork -> ProviderNodeState.NO_NETWORK
    info != null && (info.v4 != null || info.v6 != null) ->
        if (carrierNat) ProviderNodeState.CARRIER_NAT else ProviderNodeState.READY
    loading -> ProviderNodeState.LOADING
    else -> ProviderNodeState.UNAVAILABLE
}

/**
 * The public IPv4 is not the phone's own address but an operator NAT: the local IPv4 lies in
 * the shared CGNAT space 100.64.0.0/10 (any transport), or on mobile data the local IPv4 differs
 * from the public one (private 10/8, 464XLAT 192.0.0.x …). Wi-Fi behind a home router is always
 * NATed by the router and the router's WAN is unknown, so a private Wi-Fi address is not flagged.
 * Without a public IPv4 (IPv6 only) there is nothing to flag.
 */
fun isBehindCarrierNat(kind: NetKind?, localIpv4: String?, publicIpv4: String?): Boolean {
    if (publicIpv4 == null || localIpv4 == null) return false
    if (classifyPublicAddress(localIpv4, IpFamily.V4).reason == PublicAddressRejection.CGNAT) return true
    return kind == NetKind.CELLULAR && localIpv4 != publicIpv4
}

// --- DNS row --------------------------------------------------------------------------------------

/** Main note of the DNS latency row (decision O-5). */
sealed interface DnsResolverNote {
    /** Plain DNS: the first resolver address of the measured network. */
    data class Resolver(val address: String) : DnsResolverNote

    /**
     * Private DNS (DNS over TLS) is active: [host] is the configured server name (strict mode) or,
     * in opportunistic mode, the resolver address the TLS connection goes to; `null` when neither is known.
     */
    data class PrivateDns(val host: String?) : DnsResolverNote

    /** No network or no resolver address known. */
    data object Unknown : DnsResolverNote
}

fun dnsResolverNote(dnsServers: List<String>, privateDnsActive: Boolean, privateDnsServerName: String?): DnsResolverNote {
    val first = dnsServers.map { it.trim() }.firstOrNull { it.isNotEmpty() }
    if (privateDnsActive) return DnsResolverNote.PrivateDns(privateDnsServerName?.trim()?.takeIf { it.isNotEmpty() } ?: first)
    return first?.let { DnsResolverNote.Resolver(it) } ?: DnsResolverNote.Unknown
}

// --- Roaming row -----------------------------------------------------------------------------------

/** What changed with a roam, shown as one tag: the band ("2,4 → 5 GHz") or, within a band, the channel. */
sealed interface RoamingChange {
    data class Band(val from: WifiBand, val to: WifiBand) : RoamingChange
    data class Channel(val from: Int, val to: Int) : RoamingChange
}

/** `null` when a frequency is unknown or nothing changed (same channel). */
fun roamingChange(fromMhz: Int?, toMhz: Int?): RoamingChange? {
    if (fromMhz == null || toMhz == null || fromMhz == toMhz) return null
    val fromBand = bandForFrequency(fromMhz)
    val toBand = bandForFrequency(toMhz)
    if (fromBand != toBand && fromBand != WifiBand.UNKNOWN && toBand != WifiBand.UNKNOWN) return RoamingChange.Band(fromBand, toBand)
    val fromChannel = channelForFrequency(fromMhz)
    val toChannel = channelForFrequency(toMhz)
    return if (fromChannel != null && toChannel != null && fromChannel != toChannel) RoamingChange.Channel(fromChannel, toChannel) else null
}

// --- SIMs -----------------------------------------------------------------------------------------

/** SIM 1, SIM 2, … by slot; a SIM without a known slot goes last, ties by subscription id. */
fun sortedSims(sims: List<CellularSim>): List<CellularSim> =
    sims.sortedWith(compareBy({ if (it.slotIndex < 0) Int.MAX_VALUE else it.slotIndex }, { it.subscriptionId }))

/** The SIM of the mobile gauge: the data SIM, otherwise the first SIM by slot. */
fun primarySim(sims: List<CellularSim>): CellularSim? = sims.firstOrNull { it.isDataSim } ?: sortedSims(sims).firstOrNull()

/** "SIM n" number (slot + 1); `null` when the slot is unknown. */
fun simNumber(sim: CellularSim): Int? = sim.slotIndex.takeIf { it >= 0 }?.plus(1)

/** Short standard label used in chart events and headers; `null` for an unknown standard. */
fun cellRatLabel(rat: CellRat): String? = when (rat) {
    CellRat.LTE -> "LTE"
    CellRat.NR_NSA -> "5G NSA"
    CellRat.NR_SA -> "5G SA"
    CellRat.UMTS -> "3G"
    CellRat.GSM -> "2G"
    CellRat.UNKNOWN -> null
}

// --- Mobile chart events ------------------------------------------------------------------------------

/**
 * Events of the mobile signal chart, fed once per sample with the data SIM:
 * - [ChartEventKind.DATA_SIM_SWITCH] when the data SIM changes to another subscription
 *   (detail "SIM 1 → SIM 2" when both slots are known); the standard baseline restarts with it;
 * - [ChartEventKind.RAT_CHANGE] when the standard of the same data SIM changes between two known
 *   values (detail "LTE → 5G NSA"). An unknown standard keeps the previous baseline.
 *
 * A `null` data SIM (airplane mode, no SIM, monitor not ready) keeps the baselines, so returning
 * to the same SIM and standard is not an event. Bounded by [maxEvents].
 */
class CellularEventLog(val maxEvents: Int = LIVE_EVENT_LIMIT) {
    init { require(maxEvents > 0) { "maxEvents" } }

    private val retained = ArrayDeque<LiveEvent>()
    private var lastSubscription: Int? = null
    private var lastSlot: Int? = null
    private var lastRat: CellRat? = null

    /** Events oldest → newest. */
    val events: List<LiveEvent> get() = retained.toList()

    fun observe(elapsedMs: Long, dataSim: CellularSim?) {
        dataSim ?: return
        val rat = dataSim.rat.takeIf { it != CellRat.UNKNOWN }
        val previous = lastSubscription
        if (previous != null && previous != dataSim.subscriptionId) {
            val from = lastSlot
            val to = simNumber(dataSim)
            add(LiveEvent(elapsedMs, ChartEventKind.DATA_SIM_SWITCH, if (from != null && to != null) "SIM $from → SIM $to" else null))
            lastRat = rat
        } else {
            val before = lastRat
            if (before != null && rat != null && before != rat) {
                add(LiveEvent(elapsedMs, ChartEventKind.RAT_CHANGE, "${cellRatLabel(before)} → ${cellRatLabel(rat)}"))
            }
            if (rat != null) lastRat = rat
        }
        lastSubscription = dataSim.subscriptionId
        lastSlot = simNumber(dataSim)
    }

    /** Drops events before [oldestElapsedMs] (oldest sample still in the history; `null` = none). */
    fun prune(oldestElapsedMs: Long?) {
        if (oldestElapsedMs == null) { retained.clear(); return }
        while (retained.isNotEmpty() && retained.first().elapsedMs < oldestElapsedMs) retained.removeFirst()
    }

    /** Forgets events and baselines. */
    fun clear() {
        retained.clear()
        lastSubscription = null
        lastSlot = null
        lastRat = null
    }

    private fun add(event: LiveEvent) {
        retained.addLast(event)
        while (retained.size > maxEvents) retained.removeFirst()
    }
}
