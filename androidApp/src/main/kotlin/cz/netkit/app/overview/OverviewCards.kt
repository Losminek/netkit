package cz.netkit.app.overview

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.netkit.app.BuildConfig
import cz.netkit.app.R
import cz.netkit.app.ui.Eyebrow
import cz.netkit.app.ui.GaugeRange
import cz.netkit.app.ui.InfoNote
import cz.netkit.app.ui.NetKitIcons
import cz.netkit.app.ui.Panel
import cz.netkit.app.ui.SectionCard
import cz.netkit.app.ui.SignalGauge
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.StatTile
import cz.netkit.app.ui.StatTiles
import cz.netkit.app.ui.Tag
import cz.netkit.app.ui.TonalButton
import cz.netkit.app.ui.Tone
import cz.netkit.app.ui.motion.MINUS_SIGN
import cz.netkit.app.ui.theme.NetKitShape
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.app.ui.tone
import cz.netkit.app.wifi.live.WifiLiveState
import cz.netkit.shared.cellular.CellQuality
import cz.netkit.shared.cellular.CellRat
import cz.netkit.shared.cellular.CellularSim
import cz.netkit.shared.cellular.cellQuality
import cz.netkit.shared.cellular.primaryDbm
import cz.netkit.shared.net.NetKind
import cz.netkit.shared.overview.OverviewMode
import cz.netkit.shared.overview.ProviderNodeState
import cz.netkit.shared.overview.isBehindCarrierNat
import cz.netkit.shared.overview.primarySim
import cz.netkit.shared.overview.providerNodeState
import cz.netkit.shared.overview.simNumber
import cz.netkit.shared.overview.sortedSims

/** The mobile gauge and its info sit side by side only from this content width (as the Wi-Fi hero). */
private val CELL_SIDE_BY_SIDE_MIN = 320.dp

// --- Path card -------------------------------------------------------------------------------------

/**
 * "Připojení": provider (public IP, AS) → access point (Wi-Fi only) → this phone (local IP), with
 * the lookup menu (refresh, on/off switch). [apDetail] is the band and channel line of the AP node.
 */
@Composable
internal fun ConnectionPathCard(state: WifiLiveState, mode: OverviewMode, apDetail: String?, onRefresh: () -> Unit,
    onLookupChange: (Boolean) -> Unit) {
    val network = state.network
    val publicIp = state.publicIp
    val info = publicIp.info
    val carrierNat = isBehindCarrierNat(network?.kind, network?.localIpv4, info?.v4?.address)
    val provider = providerNodeState(
        enabled = publicIp.enabled, hasNetwork = network != null || !state.networkKnown,
        loading = publicIp.loading || !publicIp.settingLoaded || (!state.networkKnown && network == null), info = info,
        carrierNat = carrierNat,
    )
    val providerLines = when (provider) {
        ProviderNodeState.READY, ProviderNodeState.CARRIER_NAT -> listOfNotNull(
            info?.v4?.let { PathLine(it.address, mono = true) },
            info?.v6?.let { PathLine(it.address, mono = true) },
            info?.asn?.let { PathLine(stringResource(R.string.overview_asn, it), mono = false, dim = true) },
        )
        ProviderNodeState.LOADING -> listOf(PathLine(stringResource(R.string.overview_provider_loading), mono = false, dim = true))
        ProviderNodeState.UNAVAILABLE -> listOf(PathLine(stringResource(R.string.overview_provider_unavailable), mono = false, dim = true))
        ProviderNodeState.DISABLED -> listOf(PathLine(stringResource(R.string.overview_provider_disabled), mono = false, dim = true))
        ProviderNodeState.NO_NETWORK -> listOf(PathLine(stringResource(R.string.net_kind_none), mono = false, dim = true))
    }
    val nodes = buildList {
        add(PathNode(
            NetKitIcons.Globe,
            info?.provider?.takeIf { provider == ProviderNodeState.READY || provider == ProviderNodeState.CARRIER_NAT }
                ?: stringResource(R.string.overview_provider),
            providerLines,
            tag = if (provider == ProviderNodeState.CARRIER_NAT) stringResource(R.string.overview_carrier_nat) to Tone.INFO else null,
            testTag = "overview_node_provider",
        ))
        if (mode == OverviewMode.WIFI) add(PathNode(
            NetKitIcons.Router, stringResource(R.string.wifi_signal_access_point),
            listOfNotNull(apDetail?.let { PathLine(it, mono = false) }), testTag = "overview_node_ap",
        ))
        val local = network?.localIpv4 ?: network?.localIpv6
        add(PathNode(
            NetKitIcons.Phone, stringResource(R.string.overview_this_phone),
            listOf(if (local != null) PathLine(local, mono = true) else PathLine(stringResource(R.string.overview_no_address), mono = false, dim = true)),
            tag = if (network?.kind == NetKind.VPN) stringResource(R.string.net_kind_vpn) to Tone.NEUTRAL else null,
            testTag = "overview_node_phone",
        ))
    }
    var menu by remember { mutableStateOf(false) }
    SectionCard(stringResource(R.string.overview_path_title), trailing = {
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp).testTag("overview_path_menu")) {
                Icon(NetKitIcons.More, stringResource(R.string.overview_menu), tint = NetKitTheme.colors.muted)
            }
            PathMenu(expanded = menu, onDismiss = { menu = false }, enabled = publicIp.enabled,
                canRefresh = publicIp.enabled && network != null,
                onRefresh = { menu = false; onRefresh() }, onLookupChange = { menu = false; onLookupChange(it) })
        }
    }) {
        Box(Modifier.testTag("overview_path")) {
            PathNodes(nodes, live = state.running && network != null,
                firstLinkBroken = provider == ProviderNodeState.DISABLED || provider == ProviderNodeState.UNAVAILABLE ||
                    provider == ProviderNodeState.NO_NETWORK)
        }
    }
}

@Composable
private fun PathMenu(expanded: Boolean, onDismiss: () -> Unit, enabled: Boolean, canRefresh: Boolean, onRefresh: () -> Unit,
    onLookupChange: (Boolean) -> Unit) {
    val colors = NetKitTheme.colors
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = NetKitShape.nested,
        containerColor = colors.surface2, tonalElevation = 0.dp,
        modifier = Modifier.semantics { testTagsAsResourceId = BuildConfig.DEBUG }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.overview_refresh), style = NetKitType.body, color = if (canRefresh) colors.text else colors.dim) },
            onClick = onRefresh, enabled = canRefresh,
            leadingIcon = { Icon(NetKitIcons.Refresh, null, Modifier.size(18.dp), tint = if (canRefresh) colors.muted else colors.dim) },
            modifier = Modifier.testTag("overview_public_ip_refresh"),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.overview_lookup_toggle), style = NetKitType.body, color = colors.text) },
            onClick = { onLookupChange(!enabled) },
            trailingIcon = {
                Checkbox(checked = enabled, onCheckedChange = null, colors = CheckboxDefaults.colors(
                    checkedColor = colors.accent, checkmarkColor = colors.accentInk, uncheckedColor = colors.muted))
            },
            modifier = Modifier.testTag("overview_public_ip_toggle"),
        )
    }
}

// --- Mobile network ---------------------------------------------------------------------------------

/**
 * Main mobile card (no Wi-Fi): RSRP gauge on the −140…−44 dBm scale, SIM, operator and tags
 * beside it, RSRQ / SINR / IPv4 tiles, other SIMs as rows and the "both SIMs" permission block.
 */
@Composable
internal fun CellularHeroPanel(state: WifiLiveState, permissionDenied: Boolean, onRequestPhonePermission: () -> Unit) {
    val dash = stringResource(R.string.wifi_signal_no_value)
    val cellular = state.cellular
    val sims = sortedSims(cellular?.sims.orEmpty())
    val primary = primarySim(sims)
    val signal = primary?.signal
    val dbm = primaryDbm(signal)
    val quality = cellQuality(signal)
    val status = cellularStatus(state, primary)
    Panel(Modifier.testTag("cellular_hero")) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gauge = @Composable {
                SignalGauge(
                    fraction = null, value = dbm?.toString() ?: dash, caption = "", tone = if (status == null) qualityTone(quality) else Tone.NEUTRAL,
                    dbm = dbm?.toDouble(), qualityLabel = status ?: stringResource(cellQualityText(quality)),
                    unit = stringResource(R.string.wifi_signal_unit_dbm), range = GaugeRange.CELLULAR,
                )
            }
            if (maxWidth < CELL_SIDE_BY_SIDE_MIN) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
                    gauge()
                    SimInfo(primary, showDataTag = sims.size > 1, centered = true, Modifier.fillMaxWidth())
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.normal)) {
                    gauge()
                    SimInfo(primary, showDataTag = sims.size > 1, centered = false, Modifier.weight(1f))
                }
            }
        }
        val db = stringResource(R.string.cellular_unit_db)
        val ipv4 = state.network?.takeIf { it.kind == NetKind.CELLULAR }?.localIpv4
        StatTiles(listOf(
            StatTile(stringResource(R.string.cellular_rsrq), signal?.rsrqDb?.let(::signed) ?: dash, unit = db.takeIf { signal?.rsrqDb != null }),
            StatTile(stringResource(R.string.cellular_sinr), signal?.sinrDb?.let(::signed) ?: dash, unit = db.takeIf { signal?.sinrDb != null }),
            StatTile(stringResource(R.string.wifi_signal_ipv4), ipv4 ?: dash, weight = 1.45f, monospace = ipv4 != null),
        ))
        val others = sims.filter { it !== primary }
        if (others.isNotEmpty()) Column(Modifier.fillMaxWidth()) {
            others.forEachIndexed { index, sim -> SimRow(sim, showDivider = index > 0) }
        }
        PermissionBlock(state.cellularPermissionNeeded, permissionDenied, onRequestPhonePermission)
    }
}

/**
 * Compact "Mobilní síť" card under the Wi-Fi card: one row per SIM (SIM n, operator, standard,
 * dBm and quality, tag "Data"), or the airplane / no SIM state, plus the permission block.
 */
@Composable
internal fun CellularCompactCard(state: WifiLiveState, permissionDenied: Boolean, onRequestPhonePermission: () -> Unit) {
    val colors = NetKitTheme.colors
    val cellular = state.cellular
    val sims = sortedSims(cellular?.sims.orEmpty())
    SectionCard(stringResource(R.string.cellular_title)) {
        Column(Modifier.fillMaxWidth().testTag("cellular_compact")) {
            when {
                cellular == null -> Text(stringResource(R.string.wifi_live_probe_idle), style = NetKitType.body, color = colors.muted)
                cellular.airplaneMode -> Text(stringResource(R.string.cellular_airplane), style = NetKitType.body, color = colors.muted)
                sims.isEmpty() -> Text(stringResource(if (cellular.noSim) R.string.cellular_no_sim else R.string.cellular_no_service),
                    style = NetKitType.body, color = colors.muted)
                else -> sims.forEachIndexed { index, sim -> SimRow(sim, showDivider = index > 0, dataTag = true) }
            }
        }
        PermissionBlock(state.cellularPermissionNeeded, permissionDenied, onRequestPhonePermission)
    }
}

/** Airplane mode, no SIM or no service in place of the quality label; `null` when a signal is shown. */
@Composable
private fun cellularStatus(state: WifiLiveState, primary: CellularSim?): String? {
    val cellular = state.cellular ?: return stringResource(R.string.wifi_live_probe_idle)
    return when {
        cellular.airplaneMode -> stringResource(R.string.cellular_airplane)
        primary == null -> stringResource(if (cellular.noSim) R.string.cellular_no_sim else R.string.cellular_no_service)
        primary.signal == null -> stringResource(R.string.cellular_no_service)
        else -> null
    }
}

@Composable
private fun SimInfo(sim: CellularSim?, showDataTag: Boolean, centered: Boolean, modifier: Modifier = Modifier) {
    val colors = NetKitTheme.colors
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(modifier, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
        Eyebrow(sim?.let { simTitle(it) } ?: stringResource(R.string.cellular_title))
        Text(sim?.let { operatorText(it) } ?: stringResource(R.string.wifi_signal_no_value),
            style = NetKitType.panelTitle, color = colors.text, textAlign = align)
        if (sim != null) FlowRow(Modifier.padding(top = Spacing.tiny),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small, if (centered) Alignment.CenterHorizontally else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Tag(stringResource(ratText(sim.rat)), if (sim.rat == CellRat.UNKNOWN) Tone.NEUTRAL else Tone.ACCENT, dot = sim.rat != CellRat.UNKNOWN)
            if (showDataTag && sim.isDataSim) Tag(stringResource(R.string.cellular_data))
            if (sim.roaming == true) Tag(stringResource(R.string.cellular_roaming), Tone.WARN)
        }
    }
}

/**
 * One SIM: "SIM 1 · O2" with the standard (and "Data", roaming) tags on the left, dBm and the
 * quality on the right. Same rhythm as key/value rows: 9 dp above and below, `divider` on top.
 */
@Composable
private fun SimRow(sim: CellularSim, showDivider: Boolean, dataTag: Boolean = false) {
    val colors = NetKitTheme.colors
    val divider = colors.divider
    val dbm = primaryDbm(sim.signal)
    val quality = cellQuality(sim.signal)
    Row(Modifier.fillMaxWidth().testTag("cellular_sim_${sim.subscriptionId}")
        .then(if (showDivider) Modifier.drawBehind { drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else Modifier)
        .padding(top = if (showDivider) 9.dp else 0.dp, bottom = 9.dp)
        .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
            Text("${simTitle(sim)} · ${operatorText(sim)}", style = NetKitType.body.copy(fontWeight = FontWeight.SemiBold), color = colors.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
                Tag(stringResource(ratText(sim.rat)))
                if (dataTag && sim.isDataSim) Tag(stringResource(R.string.cellular_data), Tone.ACCENT)
                if (sim.roaming == true) Tag(stringResource(R.string.cellular_roaming), Tone.WARN)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (dbm != null) {
                Text(stringResource(R.string.cellular_value_dbm, signed(dbm)), style = NetKitType.body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.text, maxLines = 1, softWrap = false)
                Text(stringResource(cellQualityText(quality)), style = NetKitType.label.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.tone(qualityTone(quality)).foreground.takeIf { quality != CellQuality.NONE } ?: colors.muted)
            } else {
                Text(stringResource(R.string.cellular_no_service), style = NetKitType.label, color = colors.muted)
            }
        }
    }
}

/**
 * O-4: without READ_PHONE_STATE only the data SIM is visible. Before a request the tonal
 * "Zobrazit obě SIM" button starts the system dialog; after a denial a short note replaces it and
 * the app does not ask again on its own.
 */
@Composable
private fun PermissionBlock(needed: Boolean, denied: Boolean, onRequest: () -> Unit) {
    if (!needed) return
    if (denied) InfoNote(stringResource(R.string.cellular_permission_denied), stringResource(R.string.cellular_permission_note))
    else TonalButton(stringResource(R.string.cellular_permission_action), onClick = onRequest, small = true,
        modifier = Modifier.testTag("cellular_permission"))
}

// --- Texts ------------------------------------------------------------------------------------------

@Composable
internal fun simTitle(sim: CellularSim): String =
    simNumber(sim)?.let { stringResource(R.string.cellular_sim, it) } ?: stringResource(R.string.cellular_sim_unknown)

@Composable
internal fun operatorText(sim: CellularSim): String = sim.operatorName ?: stringResource(R.string.cellular_operator_unknown)

@StringRes
internal fun ratText(rat: CellRat): Int = when (rat) {
    CellRat.LTE -> R.string.cellular_rat_lte
    CellRat.NR_NSA -> R.string.cellular_rat_nr_nsa
    CellRat.NR_SA -> R.string.cellular_rat_nr_sa
    CellRat.UMTS -> R.string.cellular_rat_umts
    CellRat.GSM -> R.string.cellular_rat_gsm
    CellRat.UNKNOWN -> R.string.cellular_rat_unknown
}

@StringRes
internal fun cellQualityText(quality: CellQuality): Int = when (quality) {
    CellQuality.EXCELLENT -> R.string.cellular_quality_excellent
    CellQuality.GOOD -> R.string.cellular_quality_good
    CellQuality.FAIR -> R.string.cellular_quality_fair
    CellQuality.POOR -> R.string.cellular_quality_poor
    CellQuality.NONE -> R.string.cellular_quality_none
}

/** Gauge colour per docs/design.md §5: ok / accent / warn / err, neutral without a signal. */
internal fun qualityTone(quality: CellQuality): Tone = when (quality) {
    CellQuality.EXCELLENT -> Tone.OK
    CellQuality.GOOD -> Tone.ACCENT
    CellQuality.FAIR -> Tone.WARN
    CellQuality.POOR -> Tone.ERR
    CellQuality.NONE -> Tone.NEUTRAL
}

/** Integer with a typographic minus ("−97"). */
internal fun signed(value: Int): String = value.toString().replace('-', MINUS_SIGN)
