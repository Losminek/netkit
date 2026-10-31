package cz.netkit.app.lan

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netkit.app.R
import cz.netkit.app.ui.EmptyState
import cz.netkit.app.ui.Eyebrow
import cz.netkit.app.ui.InfoNote
import cz.netkit.app.ui.KeyValue
import cz.netkit.app.ui.KeyValueRow
import cz.netkit.app.ui.KeyValueRows
import cz.netkit.app.ui.NetKitIcons
import cz.netkit.app.ui.NeutralButton
import cz.netkit.app.ui.Panel
import cz.netkit.app.ui.PrimaryButton
import cz.netkit.app.ui.SectionCard
import cz.netkit.app.ui.AdaptiveHeader
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.StatTile
import cz.netkit.app.ui.StatTiles
import cz.netkit.app.ui.StatusBanner
import cz.netkit.app.ui.StatusKind
import cz.netkit.app.ui.Tag
import cz.netkit.app.ui.TonalButton
import cz.netkit.app.ui.Tone
import cz.netkit.app.ui.ValueBar
import cz.netkit.app.ui.motion.rememberLoopingAllowed
import cz.netkit.app.ui.theme.NetKitShape
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.app.ui.tone
import cz.netkit.shared.lan.DiscoveryConsent
import cz.netkit.shared.lan.LanAliveMethod
import cz.netkit.shared.lan.LanDevice
import cz.netkit.shared.lan.LanNameSource
import cz.netkit.shared.lan.LanRunStatus
import cz.netkit.shared.lan.LanUpnpInfo
import cz.netkit.shared.lan.OpenPort
import cz.netkit.shared.lan.commonPorts
import cz.netkit.shared.lan.lanDeviceDisplayName
import java.util.Locale

/**
 * "Zařízení" (WiFiman Discovery + Device Detail). Stateless over [LanDevicesViewModel]:
 * list mode while no device is selected, detail mode for [LanDevicesState.selectedDevice].
 * The screen is active (automatic discovery allowed, running work kept) only while resumed.
 */
@Composable
fun DevicesScreen(viewModel: LanDevicesViewModel, onTrace: (String) -> Unit, onSsh: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current
    LifecycleResumeEffect(Unit) {
        viewModel.setActive(true)
        // A rotation recreates the screen; user-started port scans and pings must survive it.
        onPauseOrDispose { if (activity?.isChangingConfigurations != true) viewModel.setActive(false) }
    }
    // Hoisted so the list keeps its scroll position across detail visits.
    val listState = rememberLazyListState()
    val device = state.selectedDevice
    // Selected device dropped from the list (pruned as stale): stop its ping and go back.
    LaunchedEffect(state.selectedAddress, device == null) {
        if (state.selectedAddress != null && device == null) viewModel.closeDevice()
    }
    BackHandler(enabled = device != null) { viewModel.closeDevice() }
    if (device != null) DeviceDetail(state, device, viewModel, onTrace, onSsh, modifier)
    else DeviceList(state, viewModel, listState, modifier)
}

// ---------------------------------------------------------------------------------------
// List mode

@Composable
private fun DeviceList(state: LanDevicesState, viewModel: LanDevicesViewModel, listState: LazyListState, modifier: Modifier) {
    val scan = state.scan
    val canRefresh = !state.loadingNetwork && !scan.running && state.network?.range != null
    LazyColumn(modifier.testTag("devices_screen"), state = listState, contentPadding = PaddingValues(Spacing.normal),
        verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        item(key = "header") {
            LanHeader(stringResource(R.string.lan_devices_ui_title), networkSubtitle(state)) {
                HeaderIconButton(NetKitIcons.Refresh, stringResource(R.string.lan_devices_ui_refresh_cd), viewModel::refresh,
                    Modifier.testTag("devices_refresh"), enabled = canRefresh)
            }
        }
        if (state.askConsent) item(key = "consent") { ConsentCard(state, viewModel) }
        item(key = "summary") { Summary(state, viewModel) }
        if (scan.running) item(key = "progress") { LanProgress(progressOf(scan.done, scan.total)) }
        else if (state.identification == LanRunStatus.RUNNING) item(key = "progress") { LanProgress(null) }
        if (state.icmpUnavailable) item(key = "icmp") { StatusBanner(stringResource(R.string.lan_devices_icmp_fallback), StatusKind.WARNING) }
        if (state.error != null) item(key = "error") { ErrorBanner(state, viewModel) }
        if (state.devices.isNotEmpty()) item(key = "devices") { DevicePanel(state.devices, viewModel::openDevice) }
        else if (!scan.running && !state.loadingNetwork) item(key = "empty") {
            EmptyState(stringResource(R.string.lan_devices_empty), NetKitIcons.Devices) {
                PrimaryButton(stringResource(R.string.lan_devices_refresh), viewModel::refresh, Modifier.testTag("devices_empty_refresh"),
                    icon = NetKitIcons.Refresh, enabled = canRefresh)
            }
        }
        item(key = "info") { InfoNote(stringResource(R.string.lan_devices_ui_info_summary), stringResource(R.string.lan_devid_info_detail)) }
    }
}

/** "192.168.0.0/24 · SSID": the range in mono; the SSID stays as broadcast (it is case-sensitive). */
@Composable
private fun networkSubtitle(state: LanDevicesState): AnnotatedString? {
    if (state.loadingNetwork) return AnnotatedString(stringResource(R.string.lan_devices_ui_loading))
    val network = state.network ?: return null
    val range = network.range ?: "${network.address}/${network.prefix}"
    return buildAnnotatedString {
        withStyle(NetKitType.addressFor(NetKitType.subtitle).toSpanStyle()) { append(range) }
        network.ssid?.let { append(SEPARATOR); append(it) }
    }
}

@Composable
private fun ConsentCard(state: LanDevicesState, viewModel: LanDevicesViewModel) {
    val colors = NetKitTheme.colors
    val network = state.network ?: return
    val range = network.range ?: return
    val where = network.ssid?.let { stringResource(R.string.lan_devices_ui_network_ssid, it, range) } ?: range
    Panel(Modifier.testTag("devices_consent")) {
        Text(stringResource(R.string.lan_devices_consent_title), style = NetKitType.panelTitle, color = colors.text)
        Text(stringResource(R.string.lan_devices_consent_body, where), style = NetKitType.body, color = colors.text)
        Text(stringResource(R.string.lan_devices_ui_consent_legal), style = NetKitType.label, color = colors.muted)
        if (network.ssid == null) Text(stringResource(R.string.lan_devices_consent_session), style = NetKitType.label, color = colors.muted)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            PrimaryButton(stringResource(R.string.lan_devices_consent_allow), viewModel::allowAutoDiscovery,
                Modifier.fillMaxWidth().testTag("devices_consent_allow"))
            NeutralButton(stringResource(R.string.lan_devices_consent_deny), viewModel::denyAutoDiscovery,
                Modifier.fillMaxWidth().testTag("devices_consent_deny"))
        }
        Text(stringResource(R.string.lan_devices_ui_consent_manual), style = NetKitType.label, color = colors.dim)
    }
}

/**
 * Tags for the scan state, the device count and the consent answer, plus the identification
 * status; the actions that fit the state (cancel, change the consent) wrap with them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(state: LanDevicesState, viewModel: LanDevicesViewModel) {
    val colors = NetKitTheme.colors
    val scan = state.scan
    val identifying = !scan.running && state.identification == LanRunStatus.RUNNING
    val consent = if (state.network?.range == null || state.loadingNetwork) null else when (state.consent) {
        DiscoveryConsent.ALLOWED -> stringResource(R.string.lan_devices_auto_on)
        DiscoveryConsent.DENIED -> stringResource(R.string.lan_devices_auto_off)
        DiscoveryConsent.UNKNOWN -> null
    }
    val count = state.devices.size
    val showCount = !scan.running && (count > 0 || scan.status == LanRunStatus.FINISHED)
    val cancelled = !scan.running && scan.status == LanRunStatus.CANCELLED
    if (!scan.running && !showCount && !cancelled && consent == null && !identifying) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            val middle = Modifier.align(Alignment.CenterVertically)
            if (scan.running) Tag(stringResource(R.string.lan_devices_ui_scan_running, scan.done, scan.total), Tone.ACCENT, dot = true,
                modifier = middle.testTag("devices_scan_running"))
            else if (showCount) Tag(pluralStringResource(R.plurals.lan_devices_ui_scan_found, count, count), Tone.ACCENT, dot = true, modifier = middle)
            if (cancelled) Tag(stringResource(R.string.lan_devices_ui_scan_cancelled), Tone.WARN, modifier = middle)
            consent?.let { Tag(it, modifier = middle) }
            if (identifying) Text(stringResource(R.string.lan_devid_identifying), middle.testTag("devices_identifying"),
                style = NetKitType.label, color = colors.muted)
            val cancel = stringResource(R.string.lan_cancel)
            if (scan.running) NeutralButton(cancel, viewModel::cancel, middle.testTag("devices_cancel"), small = true)
            else if (identifying) NeutralButton(cancel, viewModel::cancelIdentification, middle.testTag("devices_identify_cancel"), small = true)
            if (consent != null) NeutralButton(stringResource(R.string.lan_devices_ui_consent_change), viewModel::resetAutoDiscoveryConsent,
                middle.testTag("devices_consent_change"), small = true)
        }
        if (state.consent == DiscoveryConsent.ALLOWED && !state.consentRemembered && state.network?.ssid == null)
            Text(stringResource(R.string.lan_devices_consent_session), style = NetKitType.label, color = colors.dim)
    }
}

@Composable
private fun ErrorBanner(state: LanDevicesState, viewModel: LanDevicesViewModel) {
    val error = state.error ?: return
    Column(Modifier.fillMaxWidth().testTag("devices_error"), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        StatusBanner(stringResource(error.message), StatusKind.ERROR)
        NeutralButton(stringResource(R.string.lan_devices_ui_dismiss), viewModel::dismissError, Modifier.align(Alignment.End), small = true)
    }
}

@Composable
private fun DevicePanel(devices: List<LanDevice>, onOpen: (String) -> Unit) {
    // A /24 caps the list at 254 hosts, so one non-lazy panel stays cheap; rows skip when unchanged.
    Panel(padding = Spacing.normal) {
        Column {
            devices.forEachIndexed { index, device -> DeviceRow(device, showDivider = index > 0, onOpen = onOpen) }
        }
    }
}

@Composable
private fun DeviceRow(device: LanDevice, showDivider: Boolean, onOpen: (String) -> Unit) {
    val name = lanDeviceDisplayName(device)
    val parts = listOfNotNull(
        name.source?.let { stringResource(nameSourceChip(it)) },
        stringResource(R.string.lan_devices_ui_chip_gateway).takeIf { device.isGateway },
        stringResource(R.string.lan_devices_ui_chip_self).takeIf { device.isSelf },
        stringResource(R.string.lan_devices_ui_chip_stale).takeIf { device.stale },
    )
    // Unnamed devices already show the IP as the title; it is not repeated below.
    val subtitle = if (name.source == null && parts.isEmpty()) null else buildAnnotatedString {
        if (name.source != null) withStyle(NetKitType.addressFor(NetKitType.label).toSpanStyle()) { append(device.address) }
        parts.forEachIndexed { index, part -> if (index > 0 || name.source != null) append(SEPARATOR); append(part) }
    }
    val rtt = device.lastRttMs?.takeUnless { device.isSelf }?.let { stringResource(R.string.lan_rtt, formatRtt(it)) }
    LanRow(AnnotatedString(name.text), subtitle, Modifier.testTag("devices_row_${device.address}"),
        titleStyle = if (name.source == null) NetKitType.addressFor(LanRowTitle) else LanRowTitle,
        icon = deviceIcon(device), iconTone = if (device.isGateway) Tone.ACCENT else Tone.NEUTRAL,
        subtitleModifier = Modifier.testTag("device_source_${device.address}"),
        showDivider = showDivider, onClick = { onOpen(device.address) }) {
        rtt?.let { Text(it, style = LanRowValue, color = NetKitTheme.colors.text, maxLines = 1, softWrap = false) }
    }
}

// ---------------------------------------------------------------------------------------
// Detail mode

@Composable
private fun DeviceDetail(
    state: LanDevicesState, device: LanDevice, viewModel: LanDevicesViewModel,
    onTrace: (String) -> Unit, onSsh: (String) -> Unit, modifier: Modifier,
) {
    val address = device.address
    LazyColumn(modifier.testTag("device_detail"), contentPadding = PaddingValues(Spacing.normal),
        verticalArrangement = Arrangement.spacedBy(Spacing.normal)) {
        item(key = "hero") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                BackRow(viewModel::closeDevice)
                DeviceHero(device)
            }
        }
        if (!device.isSelf) item(key = "ping") { PingStats(state, device, viewModel) }
        if (state.error != null) item(key = "error") { ErrorBanner(state, viewModel) }
        if (!device.isSelf || device.names.isNotEmpty()) item(key = "identity") { IdentityPanel(state, device) }
        item(key = "ports") { PortsPanel(state, device, viewModel) }
        if (!device.isSelf) item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                PrimaryButton(stringResource(R.string.lan_trace), { onTrace(address) }, Modifier.weight(1f).testTag("device_trace"),
                    icon = NetKitIcons.Route)
                NeutralButton(stringResource(R.string.lan_devices_ui_open_ssh), { onSsh(address) }, Modifier.weight(1f).testTag("device_ssh"),
                    icon = NetKitIcons.Terminal)
            }
        }
    }
}

/** "‹ Zařízení v síti": a text back link (the system back gesture does the same). */
@Composable
private fun BackRow(onBack: () -> Unit) {
    val colors = NetKitTheme.colors
    Row(Modifier.testTag("device_back").heightIn(min = 48.dp).clip(NetKitShape.buttonSmall).clickable(role = Role.Button, onClick = onBack)
        .padding(end = Spacing.small), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
        Icon(NetKitIcons.ChevronLeft, null, Modifier.size(18.dp), tint = colors.muted)
        Text(stringResource(R.string.lan_devices_title), style = NetKitType.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
            color = colors.muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceHero(device: LanDevice) {
    val colors = NetKitTheme.colors
    val name = lanDeviceDisplayName(device)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
        Box(Modifier.size(56.dp).clip(NetKitShape.card).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(deviceIcon(device), null, Modifier.size(28.dp), tint = colors.accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
            // A name is data: it wraps instead of being cut; without a name the title is the IP.
            Text(name.text, style = if (name.source == null) NetKitType.addressFor(HeroTitle) else HeroTitle, color = colors.text)
            if (name.source != null) Text(device.address, style = NetKitType.address, color = colors.muted)
            if (device.isGateway || device.isSelf || device.stale) FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.tiny),
                verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
                if (device.isGateway) Tag(stringResource(R.string.lan_devices_ui_chip_gateway), Tone.INFO)
                if (device.isSelf) Tag(stringResource(R.string.lan_devices_ui_chip_self))
                if (device.stale) Tag(stringResource(R.string.lan_devices_ui_chip_stale))
            }
        }
    }
}

/** Ping (10×) / loss / answering method, then min–max and "Měřit znovu". */
@Composable
private fun PingStats(state: LanDevicesState, device: LanDevice, viewModel: LanDevicesViewModel) {
    val colors = NetKitTheme.colors
    val ping = state.ping?.takeIf { it.address == device.address }
    val samples = ping?.samples.orEmpty()
    val stats = ping?.statistics
    val running = ping?.status == LanRunStatus.RUNNING
    // ICMP refused by the OS/VPN: say so instead of showing an empty measurement.
    val icmpFailed = state.icmpUnavailable && (ping == null || ping.status == LanRunStatus.FAILED)
    val target = ping?.target ?: DEVICE_PING_SAMPLES
    val ms = stringResource(R.string.lan_unit_ms)
    val missing = stringResource(R.string.lan_missing_value)
    val label = if (running) stringResource(R.string.lan_devices_ping_progress, samples.size, target)
        else stringResource(R.string.lan_devices_ui_ping_tile, target)
    val average = stats?.average
    val pingTile = if (icmpFailed && samples.isEmpty()) StatTile(label, stringResource(R.string.lan_devices_ui_ping_unavailable), tone = Tone.WARN)
        else StatTile(label, average?.let(::formatRtt) ?: missing, if (average != null) ms else null,
            tone = rttTone(average).takeUnless { it == Tone.OK } ?: Tone.NEUTRAL)
    val lossTile = when {
        stats == null || samples.isEmpty() -> StatTile(stringResource(R.string.lan_devices_ui_loss), missing)
        else -> StatTile(stringResource(R.string.lan_devices_ui_loss), String.format(Locale.getDefault(), "%.0f", stats.lossPercent),
            stringResource(R.string.lan_unit_percent), tone = when {
                stats.received == stats.sent -> Tone.OK
                stats.received == 0 -> Tone.ERR
                else -> Tone.WARN
            })
    }
    val methodTile = StatTile(stringResource(R.string.lan_devices_ui_method), stringResource(methodLabel(device.method)), weight = 1.2f)
    val minimum = stats?.minimum
    val maximum = stats?.maximum
    val detail = listOfNotNull(
        if (minimum != null && maximum != null) stringResource(R.string.lan_devices_ui_ping_range, formatRtt(minimum), formatRtt(maximum)) else null,
        stringResource(R.string.lan_devices_ui_ping_unavailable).takeIf { icmpFailed && samples.isNotEmpty() },
    ).joinToString(SEPARATOR)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        StatTiles(listOf(pingTile, lossTile, methodTile))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(detail, Modifier.weight(1f), style = NetKitType.label, color = if (icmpFailed) colors.warn else colors.muted)
            NeutralButton(stringResource(R.string.lan_devices_ping_again), { viewModel.openDevice(device.address) },
                Modifier.testTag("device_ping_again"), icon = NetKitIcons.Refresh, enabled = !running, small = true)
        }
    }
}

/**
 * Only the names and fields that were found, then one dim line for what was not: NetBIOS and
 * reverse DNS come from discovery, mDNS and UPnP count as "not found" only after a finished
 * identification pass that queried this device; before that the line says why they are unknown.
 * A presentation URL is opened only on tap and only if [LanUpnpInfo.openUrl] passed the
 * own-subnet check.
 */
@Composable
private fun IdentityPanel(state: LanDevicesState, device: LanDevice) {
    val colors = NetKitTheme.colors
    val running = !device.isSelf && state.identification == LanRunStatus.RUNNING
    val known = LanNameSource.entries.mapNotNull { source ->
        device.names.firstOrNull { it.source == source }?.let { KeyValue(stringResource(nameSourceLabel(source)), it.value) }
    } + listOfNotNull(device.netBiosGroup?.let { KeyValue(stringResource(R.string.lan_devices_netbios_group), it) })
    val line = if (device.isSelf) null else identityLine(state, device, running)
    Box(Modifier.testTag("device_identity")) {
        SectionCard(stringResource(R.string.lan_devid_section), trailing = if (running) ({
            Tag(stringResource(R.string.lan_devid_running), Tone.ACCENT, dot = true, modifier = Modifier.testTag("device_identity_running"))
        }) else null) {
            if (known.isNotEmpty() || device.mdnsServices.isNotEmpty()) Column {
                KeyValueRows(known)
                if (device.mdnsServices.isNotEmpty()) Box(Modifier.testTag("device_mdns_services")) {
                    KeyValueRow(stringResource(R.string.lan_devid_services), device.mdnsServices.joinToString(", "), showDivider = known.isNotEmpty())
                }
            }
            device.upnp?.let { UpnpRows(it) }
            line?.let { Text(it, Modifier.testTag("device_identity_missing"), style = NetKitType.label, color = colors.dim) }
        }
    }
}

@Composable
private fun identityLine(state: LanDevicesState, device: LanDevice, running: Boolean): String? {
    val hasMdns = device.mdnsServices.isNotEmpty() || device.names.any { it.source == LanNameSource.MDNS }
    // mDNS/SSDP absence is known only when a finished pass actually queried this device.
    val measured = device.address in state.identificationHosts && state.identification == LanRunStatus.FINISHED
    val missing = buildList {
        if (device.names.none { it.source == LanNameSource.NETBIOS }) add(stringResource(nameSourceLabel(LanNameSource.NETBIOS)))
        if (measured && !hasMdns) add(stringResource(nameSourceLabel(LanNameSource.MDNS)))
        if (device.names.none { it.source == LanNameSource.PTR }) add(stringResource(nameSourceLabel(LanNameSource.PTR)))
        if (measured && device.upnp == null) add(stringResource(R.string.lan_devid_upnp))
    }
    val hint = when {
        running || measured || hasMdns || device.upnp != null -> null
        device.address !in state.identificationHosts || state.identification == LanRunStatus.IDLE -> stringResource(R.string.lan_devid_pending)
        else -> stringResource(R.string.lan_devid_incomplete)
    }
    val notFound = missing.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.lan_devid_missing, it.joinToString(", ")) }
    return listOfNotNull(notFound, hint).joinToString(" ").ifEmpty { null }
}

@Composable
private fun UpnpRows(info: LanUpnpInfo) {
    val colors = NetKitTheme.colors
    val context = LocalContext.current
    val rows = listOfNotNull(
        info.friendlyName?.let { KeyValue(stringResource(R.string.lan_devid_upnp_name), it) },
        info.manufacturer?.let { KeyValue(stringResource(R.string.lan_devid_upnp_manufacturer), it) },
        listOfNotNull(info.modelName, info.modelNumber).distinct().joinToString(" ").ifEmpty { null }
            ?.let { KeyValue(stringResource(R.string.lan_devid_upnp_model), it) },
        info.deviceType?.let { KeyValue(stringResource(R.string.lan_devid_upnp_type), it.removePrefix("urn:schemas-upnp-org:device:")) },
        info.server?.let { KeyValue(stringResource(R.string.lan_devid_upnp_server), it) },
        info.usn?.let { KeyValue(stringResource(R.string.lan_devid_upnp_usn), it.substringBefore("::")) },
        info.presentationUrl?.let { KeyValue(stringResource(R.string.lan_devid_upnp_presentation), info.openUrl ?: it, monospace = true) },
    )
    Column(Modifier.testTag("device_upnp"), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Eyebrow(stringResource(R.string.lan_devid_upnp))
        if (rows.isNotEmpty()) KeyValueRows(rows)
        if (!info.hasDescription) Text(stringResource(R.string.lan_devid_upnp_no_description), style = NetKitType.label, color = colors.dim)
        info.openUrl?.let { url ->
            TonalButton(stringResource(R.string.lan_devid_upnp_open), { openInBrowser(context, url) }, Modifier.testTag("device_upnp_open"),
                icon = NetKitIcons.Globe, small = true)
        }
    }
}

/** User-initiated only; the browser, not NetKit, makes the (possibly cleartext) request. */
private fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    try { context.startActivity(intent) }
    catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.lan_devid_upnp_open_failed, Toast.LENGTH_SHORT).show() }
}

@Composable
private fun PortsPanel(state: LanDevicesState, device: LanDevice, viewModel: LanDevicesViewModel) {
    val colors = NetKitTheme.colors
    val address = device.address
    val scan = state.portScan?.takeIf { it.address == address }
    val runningScan = scan?.takeIf { it.status == LanRunStatus.RUNNING }
    val running = runningScan != null
    val scanned = device.portsScannedElapsedMs != null
    val total = scan?.total?.takeIf { it > 0 } ?: commonPorts.size
    val meta = if (!running && (scanned || device.openPorts.isNotEmpty()))
        stringResource(R.string.lan_devices_ui_ports_meta, device.openPorts.size, total) else null
    Box(Modifier.testTag("device_ports")) {
        SectionCard(stringResource(R.string.lan_devices_ui_section_ports), meta = meta) {
            when {
                runningScan != null -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        Text(stringResource(R.string.lan_devices_ports_progress, runningScan.done, runningScan.total, device.openPorts.size),
                            Modifier.weight(1f), style = NetKitType.label, color = colors.muted)
                        NeutralButton(stringResource(R.string.lan_cancel), viewModel::cancelPortScan, Modifier.testTag("device_ports_cancel"), small = true)
                    }
                    LanProgress(progressOf(runningScan.done, runningScan.total))
                }
                scan == null && !scanned && device.openPorts.isEmpty() -> {
                    Text(stringResource(R.string.lan_devices_ui_ports_hint, commonPorts.size), style = NetKitType.label, color = colors.muted)
                    TonalButton(stringResource(R.string.lan_devices_scan_ports), { viewModel.scanPorts(address) }, Modifier.testTag("device_scan_ports"))
                }
            }
            if (device.openPorts.isNotEmpty()) PortGrid(device.openPorts)
            else if (!running && scanned) Text(stringResource(R.string.lan_devices_no_open_ports), style = NetKitType.label, color = colors.muted)
            if (!running && (scan != null || scanned || device.openPorts.isNotEmpty())) {
                if (scan?.status == LanRunStatus.CANCELLED || (!scanned && device.openPorts.isNotEmpty()))
                    Text(stringResource(R.string.lan_devices_ui_ports_cancelled), style = NetKitType.label, color = colors.dim)
                TonalButton(stringResource(R.string.lan_devices_ui_ports_again), { viewModel.scanPorts(address) },
                    Modifier.testTag("device_scan_ports_again"), icon = NetKitIcons.Refresh, small = true)
            }
        }
    }
}

/** Three columns of `surface2` cells: port in accent, service muted (docs/design.md §5 "Mřížka portů"). */
@Composable
private fun PortGrid(ports: List<OpenPort>) {
    val colors = NetKitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        ports.chunked(PORT_COLUMNS).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                line.forEach { port ->
                    Column(Modifier.weight(1f).clip(NetKitShape.buttonSmall).background(colors.surface2)
                        .semantics(mergeDescendants = true) {}.padding(horizontal = Spacing.medium, vertical = Spacing.small)) {
                        Text(port.port.toString(), style = PortNumber, color = colors.accent)
                        if (port.service.isNotEmpty()) Text(port.service, style = NetKitType.label.copy(fontSize = 12.sp), color = colors.muted)
                    }
                }
                repeat(PORT_COLUMNS - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Pieces shared with LanScreen (Nástroje)

internal const val SEPARATOR = " · "

/** List-row title (docs/design.md §4: 15, bold) and the end value (13/700, tabular). */
internal val LanRowTitle: TextStyle = NetKitType.panelTitle
internal val LanRowValue: TextStyle = NetKitType.label.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
private val HeroTitle: TextStyle = NetKitType.screenTitle.copy(fontSize = 22.sp, lineHeight = 28.sp)
private val PortNumber: TextStyle = NetKitType.value.copy(fontSize = 15.sp, lineHeight = 20.sp)
private const val PORT_COLUMNS = 3

/**
 * Screen header like `ScreenHeader`, but the subtitle is an [AnnotatedString] so an address in
 * it can be mono. At most one action on the right.
 */
@Composable
internal fun LanHeader(title: String, subtitle: AnnotatedString?, trailing: @Composable RowScope.() -> Unit) {
    val colors = NetKitTheme.colors
    // Same wrapping as ScreenHeader: the action moves below the title rather than splitting a word.
    AdaptiveHeader(title, titleGap = Spacing.tiny, subtitle = subtitle?.let { { Text(it, style = NetKitType.subtitle, color = colors.muted) } },
        trailing = trailing)
}

/** 40 × 40 icon action on `surface2`, radius 12; the touch target stays 48 dp. */
@Composable
internal fun HeaderIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = NetKitTheme.colors
    Box(modifier.minimumInteractiveComponentSize().size(40.dp).clip(NetKitShape.nested).background(colors.surface2)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(20.dp), tint = if (enabled) colors.text else colors.dim)
    }
}

/**
 * List row (docs/design.md §4) for devices and tool results: optional 40 × 40 icon tile, title
 * and subtitle as annotated text (so IPs inside can be mono), trailing content and a chevron when
 * clickable. Title and subtitle wrap instead of being cut: they carry data (names, addresses).
 */
@Composable
internal fun LanRow(
    title: AnnotatedString, subtitle: AnnotatedString?, modifier: Modifier = Modifier,
    titleStyle: TextStyle = LanRowTitle, icon: ImageVector? = null, iconTone: Tone = Tone.NEUTRAL,
    subtitleModifier: Modifier = Modifier, showDivider: Boolean = false, onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = NetKitTheme.colors
    val divider = colors.divider
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (showDivider) Modifier.drawBehind { drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) } else Modifier)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = Spacing.medium), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        icon?.let {
            val tone = colors.tone(iconTone)
            Box(Modifier.size(40.dp).clip(NetKitShape.nested).background(tone.fill), contentAlignment = Alignment.Center) {
                Icon(it, null, Modifier.size(20.dp), tint = tone.foreground)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
            Text(title, style = titleStyle, color = colors.text)
            subtitle?.let { Text(it, subtitleModifier, style = NetKitType.label, color = colors.muted) }
        }
        trailing?.invoke(this)
        if (onClick != null) Icon(NetKitIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.dim)
    }
}

/**
 * Progress bar on the `surface3` track: [fraction] fills it (animated like `ValueBar`); null is
 * the indeterminate search sweep, which is left out when animations are off or the screen paused
 * (the status text next to it already says that work is running).
 */
@Composable
internal fun LanProgress(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction != null) { ValueBar(fraction, Tone.ACCENT, modifier.fillMaxWidth()); return }
    if (!rememberLoopingAllowed()) return
    val colors = NetKitTheme.colors
    val sweep by rememberInfiniteTransition(label = "lan-progress").animateFloat(0f, 1f,
        infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing), RepeatMode.Restart), label = "lan-progress-sweep")
    val track = colors.surface3
    val accent = colors.accent
    Box(modifier.fillMaxWidth().height(Spacing.tiny).clip(NetKitShape.pill).drawBehind {
        drawRect(track)
        val width = size.width * SWEEP_WIDTH
        val x = -width + (size.width + width) * sweep
        drawRect(Brush.horizontalGradient(listOf(accent.copy(alpha = 0f), accent, accent.copy(alpha = 0f)), startX = x, endX = x + width),
            topLeft = Offset(x, 0f), size = Size(width, size.height))
    })
}
private const val SWEEP_MS = 1400
private const val SWEEP_WIDTH = 0.4f

// ---------------------------------------------------------------------------------------
// Helpers

/** Prototype `phone` glyph on the NetKitIcons grid (24 units, 1.8 stroke); the tint colours it. */
private val PhoneIcon: ImageVector by lazy {
    ImageVector.Builder("phone", 24.dp, 24.dp, 24f, 24f).apply {
        listOf("M9.4 2.8h5.2a2.4 2.4 0 0 1 2.4 2.4v13.6a2.4 2.4 0 0 1-2.4 2.4H9.4a2.4 2.4 0 0 1-2.4-2.4V5.2a2.4 2.4 0 0 1 2.4-2.4z", "M11 18h2")
            .forEach { data ->
                addPath(addPathNodes(data), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
    }.build()
}

private fun deviceIcon(device: LanDevice): ImageVector = when {
    device.isGateway -> NetKitIcons.Router
    device.isSelf -> PhoneIcon
    else -> NetKitIcons.Device
}

private fun nameSourceLabel(source: LanNameSource): Int = when (source) {
    LanNameSource.NETBIOS -> R.string.lan_devices_name_netbios
    LanNameSource.MDNS -> R.string.lan_devices_name_mdns
    LanNameSource.PTR -> R.string.lan_devices_name_ptr
}

private fun nameSourceChip(source: LanNameSource): Int = when (source) {
    LanNameSource.NETBIOS -> R.string.lan_devid_chip_netbios
    LanNameSource.MDNS -> R.string.lan_devid_chip_mdns
    LanNameSource.PTR -> R.string.lan_devid_chip_ptr
}

private fun methodLabel(method: LanAliveMethod): Int = when (method) {
    LanAliveMethod.ICMP -> R.string.lan_devices_method_icmp
    LanAliveMethod.TCP -> R.string.lan_devices_method_tcp
    LanAliveMethod.TCP_RST -> R.string.lan_devices_method_tcp_rst
    LanAliveMethod.SELF -> R.string.lan_devices_self
}

/** Green under 30 ms, amber under 100 ms, red above; unknown stays neutral. */
private fun rttTone(ms: Double?): Tone = when {
    ms == null -> Tone.NEUTRAL
    ms < 30.0 -> Tone.OK
    ms < 100.0 -> Tone.WARN
    else -> Tone.ERR
}

private fun progressOf(done: Int, total: Int): Float = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

private fun formatRtt(ms: Double): String =
    String.format(Locale.getDefault(), if (ms < 10.0) "%.1f" else "%.0f", ms)
