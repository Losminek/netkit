package cz.netkit.app.lan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netkit.app.R
import cz.netkit.app.net.netKindLabel
import cz.netkit.app.ui.EmptyState
import cz.netkit.app.ui.Eyebrow
import cz.netkit.app.ui.InfoNote
import cz.netkit.app.ui.NetKitIcons
import cz.netkit.app.ui.NeutralButton
import cz.netkit.app.ui.Panel
import cz.netkit.app.ui.PrimaryButton
import cz.netkit.app.ui.SectionCard
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.StatTile
import cz.netkit.app.ui.StatTiles
import cz.netkit.app.ui.StatusBanner
import cz.netkit.app.ui.StatusKind
import cz.netkit.app.ui.Tag
import cz.netkit.app.ui.Tone
import cz.netkit.app.ui.ValueBar
import cz.netkit.app.ui.motion.MotionToken
import cz.netkit.app.ui.motion.rememberNetKitTween
import cz.netkit.app.ui.theme.NetKitShape
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.app.ui.tone
import cz.netkit.shared.chart.niceMax
import cz.netkit.shared.lan.LanExport
import cz.netkit.shared.lan.LanResult
import cz.netkit.shared.lan.LanRunStatus
import cz.netkit.shared.lan.TargetConfirmationReason
import cz.netkit.shared.lan.exportLan
import cz.netkit.shared.lan.ipv4Number
import cz.netkit.shared.lan.lanTextFitsIpc
import cz.netkit.shared.lan.pingStatistics
import cz.netkit.shared.net.NetKind
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Method of a trace hop whose RTT was measured by a direct ICMP echo to that hop (not by the TTL probe). */
private const val TRACE_ECHO_METHOD = "TTL+ICMP"
private val EXPORT_ERRORS = setOf(R.string.lan_export_failed, R.string.lan_export_ipc_failed)
/** Ping rows and graph show the newest samples only; statistics and export keep up to 600. */
private const val PING_VISIBLE = 120

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanScreen(viewModel: LanViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = NetKitTheme.colors
    // Register at screen scope: closing the export sheet must not dispose its SAF result callback.
    val saveText=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"),viewModel::saveExport)
    val saveCsv=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"),viewModel::saveExport)
    val saveJson=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"),viewModel::saveExport)
    val launchSave: () -> Unit = {
        viewModel.prepareExport()
        try { when(state.exportFormat) {
            LanExport.TEXT -> saveText.launch("netkit-lan.txt")
            LanExport.CSV -> saveCsv.launch("netkit-lan.csv")
            LanExport.JSON -> saveJson.launch("netkit-lan.json")
        } } catch(_: Exception) { viewModel.cancelExport(); viewModel.exportFailed() }
    }
    val labels = listOf(R.string.lan_discovery,R.string.lan_ping,R.string.lan_trace,R.string.lan_ports,R.string.lan_dns,R.string.lan_mdns)
    var menuOpen by remember { mutableStateOf(false) }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    val hasResults = state.results.isNotEmpty()
    // Export exists only for a non-empty snapshot; a tool switch or a new run closes the sheet.
    LaunchedEffect(hasResults) { if (!hasResults) exportOpen = false }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val closeExport: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { exportOpen = false } }

    if (state.confirmation) ConfirmationDialog(state, viewModel)
    if (exportOpen && hasResults) ModalBottomSheet(onDismissRequest={ exportOpen = false },sheetState=sheetState,
        containerColor=colors.surface,contentColor=colors.text,dragHandle={ BottomSheetDefaults.DragHandle(color=colors.surface3) }) {
        LanExportSheet(state.results,state.exportFormat,state.error,viewModel) { launchSave(); closeExport() }
    }

    LazyColumn(modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(Spacing.normal),
        verticalArrangement=Arrangement.spacedBy(Spacing.medium)) {
        item(key="header") {
            LanHeader(stringResource(R.string.lan_header),wifiSubtitle(state.wifiAddress)) {
                Box {
                    HeaderIconButton(NetKitIcons.More,stringResource(R.string.lan_more),{ menuOpen = true })
                    DropdownMenu(expanded=menuOpen,onDismissRequest={ menuOpen = false },containerColor=colors.surface2) {
                        DropdownMenuItem(text={ Text(stringResource(R.string.lan_export_open),style=NetKitType.body) },enabled=hasResults,
                            leadingIcon={ Icon(NetKitIcons.Download,null) },onClick={ menuOpen = false; exportOpen = true })
                        DropdownMenuItem(text={ Text(stringResource(R.string.lan_refresh),style=NetKitType.body) },enabled=!state.busy,
                            leadingIcon={ Icon(NetKitIcons.Refresh,null) },onClick={ menuOpen = false; viewModel.refreshNetwork() })
                    }
                }
            }
        }
        // Legal scope remains visible on first use; it is never hidden inside the technical note.
        item(key="safety") { SafetyLine() }
        item(key="tools") {
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(Spacing.small)) {
                LanTool.entries.forEach { tool -> ChoiceChip(stringResource(labels[tool.ordinal]),state.tool==tool) { viewModel.tool(tool) } }
            }
        }
        item(key="target") { TargetPanel(state,viewModel) }
        if (state.runStatus!=LanRunStatus.IDLE || state.busy) item(key="status") { RunStatus(state) }
        state.error?.let { resource -> item(key="error") { StatusBanner(stringResource(resource),StatusKind.ERROR) } }
        state.notice?.let { resource -> item(key="notice") { StatusBanner(stringResource(resource)) } }
        if (state.traceRouteWarning) item(key="route") { StatusBanner(stringResource(R.string.lan_trace_outside_wifi),StatusKind.WARNING) }
        if (state.tool==LanTool.PING && state.samples.isNotEmpty()) item(key="ping") { PingSummary(state) }
        if (hasResults) {
            if (state.tool==LanTool.TRACE) item(key="trace") { TracePanel(state.results) }
            else item(key="results") { ResultsPanel(state.results,state.tool,viewModel) }
        } else if(!state.busy && state.error==null) item(key="empty") {
            EmptyState(stringResource(R.string.lan_empty),if(state.tool==LanTool.TRACE) NetKitIcons.Route else NetKitIcons.Devices)
        }
        item(key="info") {
            InfoNote(stringResource(R.string.lan_network_note),
                stringResource(R.string.lan_transport)+"\n\n"+stringResource(R.string.lan_ping_window))
        }
    }
}

/** "Wi-Fi · 192.168.0.30" (address in mono); the run binds to this network. */
@Composable
private fun wifiSubtitle(address: String?): AnnotatedString = buildAnnotatedString {
    append(stringResource(R.string.lan_wifi_label))
    append(SEPARATOR)
    if (address==null) append(stringResource(R.string.lan_unavailable))
    else withStyle(NetKitType.addressFor(NetKitType.subtitle).toSpanStyle()) { append(address) }
}

/** Compact warning line: the legal scope, one sentence on `warnSoft`. */
@Composable
private fun SafetyLine() {
    val colors = NetKitTheme.colors
    val tone = colors.tone(Tone.WARN)
    Row(Modifier.fillMaxWidth().clip(NetKitShape.nested).background(tone.fill).padding(horizontal=Spacing.medium,vertical=Spacing.small),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Spacing.small)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tone.foreground))
        Text(stringResource(R.string.lan_safety),Modifier.weight(1f),style=NetKitType.label,color=colors.text)
    }
}

@Composable
private fun ConfirmationDialog(state: LanUiState, viewModel: LanViewModel) {
    val colors = NetKitTheme.colors
    AlertDialog(
        onDismissRequest=viewModel::dismissConfirmation,containerColor=colors.surface,shape=NetKitShape.card,
        titleContentColor=colors.text,textContentColor=colors.text,
        title={ Text(stringResource(R.string.lan_confirm_title),style=NetKitType.panelTitle.copy(fontSize=18.sp,lineHeight=24.sp)) },
        text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Spacing.medium)) {
            Text(state.confirmationTarget,style=NetKitType.addressFor(NetKitType.value),color=colors.text)
            state.confirmationReasons.forEach { reason -> Text(stringResource(when(reason) {
                TargetConfirmationReason.OUTSIDE_WIFI -> R.string.lan_reason_outside
                TargetConfirmationReason.NON_PRIVATE_ADDRESS -> R.string.lan_reason_non_private
                TargetConfirmationReason.LARGE_HOST_SET -> R.string.lan_reason_hosts
                TargetConfirmationReason.LARGE_PORT_SET -> R.string.lan_reason_ports
            }),style=NetKitType.body) }
            if (state.traceRouteWarning) StatusBanner(stringResource(R.string.lan_trace_confirmation),StatusKind.WARNING)
            Text(stringResource(R.string.lan_confirm_authorized),style=NetKitType.label,color=colors.muted)
        } },
        confirmButton={ PrimaryButton(stringResource(R.string.lan_confirm),{ viewModel.start(true) },small=true) },
        dismissButton={ NeutralButton(stringResource(R.string.lan_cancel),viewModel::dismissConfirmation,small=true) },
    )
}

/** Tool or format choice: pill on `surface2`, the selected one in accent tint; 48 dp touch target. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = NetKitTheme.colors
    val fill by animateColorAsState(if(selected) colors.accentSoft else colors.surface2,rememberNetKitTween(MotionToken.FAST),label="chip-fill")
    val ink by animateColorAsState(if(selected) colors.accent else colors.muted,rememberNetKitTween(MotionToken.FAST),label="chip-ink")
    Box(Modifier.minimumInteractiveComponentSize().heightIn(min=36.dp).clip(NetKitShape.pill).background(fill)
        .selectable(selected=selected,role=Role.Tab,onClick=onClick).padding(horizontal=Spacing.medium),contentAlignment=Alignment.Center) {
        Text(label,style=NetKitType.tag.copy(fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.Bold),color=ink,maxLines=1,softWrap=false)
    }
}

@Composable
private fun TargetPanel(state: LanUiState, viewModel: LanViewModel) {
    val colors = NetKitTheme.colors
    Panel {
        RouteLine(state)
        // The primary target and the run action share one row; secondary parameters follow.
        Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(Spacing.small)) {
            val field=Modifier.weight(1f)
            when(state.tool) {
                LanTool.DISCOVERY -> Field(state.cidr,viewModel::cidr,R.string.lan_cidr,!state.busy,field,mono=true)
                LanTool.MDNS -> Field(state.serviceType,viewModel::serviceType,R.string.lan_service_type,!state.busy,field,mono=true)
                LanTool.PING,LanTool.TRACE,LanTool.PORTS,LanTool.DNS -> Field(state.host,viewModel::host,R.string.lan_host,!state.busy,field,mono=true)
            }
            // Same height as the outlined field next to it.
            val action=Modifier.heightIn(min=56.dp)
            if (state.busy) NeutralButton(stringResource(R.string.lan_stop),viewModel::cancelActive,action)
            else PrimaryButton(stringResource(R.string.lan_start),{ viewModel.start() },action)
        }
        if (state.tool==LanTool.PORTS) {
            Field(state.ports,viewModel::ports,R.string.lan_port_list,!state.busy,mono=true)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Spacing.small)) {
                Row(Modifier.weight(1f).heightIn(min=48.dp).clip(NetKitShape.nested).toggleable(value=state.readBanner,enabled=!state.busy,
                    role=Role.Checkbox,onValueChange=viewModel::banner),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Spacing.small)) {
                    Checkbox(checked=state.readBanner,onCheckedChange=null,enabled=!state.busy,
                        colors=CheckboxDefaults.colors(checkedColor=colors.accent,uncheckedColor=colors.muted,checkmarkColor=colors.accentInk))
                    Text(stringResource(R.string.lan_banner),style=NetKitType.body,color=colors.text)
                }
                NeutralButton(stringResource(R.string.lan_preset),viewModel::preset,enabled=!state.busy,small=true)
            }
        }
        if (state.tool !in listOf(LanTool.DNS,LanTool.MDNS)) Field(state.timeout,viewModel::timeout,R.string.lan_timeout,!state.busy,number=true)
        if (state.tool in listOf(LanTool.DISCOVERY,LanTool.PORTS)) Field(state.concurrency,viewModel::concurrency,R.string.lan_concurrency,!state.busy,number=true)
    }
}

/** "Měří se přes [Wi-Fi]": the network of the next or current run; warning tone when it is missing. */
@Composable
private fun RouteLine(state: LanUiState) {
    val kind = state.routeKind
    val wifiOnly = state.tool==LanTool.DISCOVERY || state.tool==LanTool.MDNS
    val missing = kind==null || (wifiOnly && state.wifiAddress==null)
    Row(Modifier.fillMaxWidth().testTag("lan_route"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Spacing.small)) {
        Text(stringResource(R.string.lan_route_label),style=NetKitType.label,color=NetKitTheme.colors.muted)
        Tag(stringResource(kind?.let(::netKindLabel) ?: R.string.net_kind_none),
            when { missing -> Tone.WARN; kind==NetKind.WIFI -> Tone.ACCENT; else -> Tone.INFO },dot=true)
    }
}

@Composable
private fun RunStatus(state: LanUiState) {
    val colors = NetKitTheme.colors
    val count = state.results.size
    Column(verticalArrangement=Arrangement.spacedBy(Spacing.small)) {
        when(state.runStatus) {
            LanRunStatus.RUNNING -> Tag(when {
                state.total>0 -> stringResource(R.string.lan_chip_running_total,state.progress,state.total)
                state.progress>0 -> stringResource(R.string.lan_chip_running,state.progress)
                else -> stringResource(R.string.lan_chip_running_plain)
            },Tone.ACCENT,dot=true)
            LanRunStatus.FINISHED -> Tag(pluralStringResource(R.plurals.lan_chip_finished,count,count),Tone.OK,dot=true)
            LanRunStatus.CANCELLED -> Tag(pluralStringResource(R.plurals.lan_chip_cancelled,count,count),Tone.WARN,dot=true)
            LanRunStatus.FAILED -> Tag(stringResource(R.string.lan_chip_failed),Tone.ERR,dot=true)
            LanRunStatus.IDLE -> Unit
        }
        if (state.tool in listOf(LanTool.DISCOVERY,LanTool.PORTS) && state.runStatus!=LanRunStatus.IDLE)
            Text(stringResource(R.string.lan_probe_counts,state.probeCounts.open,state.probeCounts.refused,state.probeCounts.timeout,state.probeCounts.error),
                style=NetKitType.label,color=colors.dim)
        if (state.busy) LanProgress(if(state.total>0) (state.progress.toFloat()/state.total).coerceIn(0f,1f) else null)
    }
}

@Composable
private fun PingSummary(state: LanUiState) {
    val colors = NetKitTheme.colors
    val stats = remember(state.samples) { pingStatistics(state.samples) }
    val ms = stringResource(R.string.lan_unit_ms)
    val missing = stringResource(R.string.lan_missing_value)
    val loss = if (state.sent>0) 100.0*(state.sent-state.received)/state.sent else 0.0
    val tiles = listOf(R.string.lan_metric_min to stats.minimum,R.string.lan_metric_avg to stats.average,R.string.lan_metric_max to stats.maximum)
        .map { (label,value) -> StatTile(stringResource(label),value?.let(::compact) ?: missing,if(value!=null) ms else null) } +
        StatTile(stringResource(R.string.lan_metric_loss),String.format(Locale.getDefault(),"%.1f",loss),stringResource(R.string.lan_unit_percent),
            tone=when { loss>=50.0 -> Tone.ERR; loss>0.0 -> Tone.WARN; else -> Tone.NEUTRAL })
    val counts = listOfNotNull(stringResource(R.string.lan_ping_counts,state.sent,state.received),
        stats.jitter?.let { stringResource(R.string.lan_ping_jitter,decimal(it)) }).joinToString(SEPARATOR)
    SectionCard(stringResource(R.string.lan_ping_statistics)) {
        StatTiles(tiles)
        Text(counts,style=NetKitType.label,color=colors.muted)
        PingGraph(state.results.takeLast(PING_VISIBLE))
    }
}

@Composable
private fun ResultsPanel(results: List<LanResult>, tool: LanTool, viewModel: LanViewModel) {
    val displayed = if(tool==LanTool.PING) results.takeLast(PING_VISIBLE).asReversed() else results
    SectionCard(stringResource(R.string.lan_results_title),meta=if(tool==LanTool.PING) stringResource(R.string.lan_ping_visible_window) else null) {
        Column {
            displayed.forEachIndexed { index, result ->
                // Keyed so a row's open menu stays with its result while new ping samples arrive on top.
                key(result.sequence ?: index) { ResultRow(result,tool,viewModel,showDivider=index>0) }
            }
        }
    }
}

@Composable
private fun ResultRow(result: LanResult, tool: LanTool, viewModel: LanViewModel, showDivider: Boolean) {
    val context = LocalContext.current
    val colors = NetKitTheme.colors
    val mono = NetKitType.addressFor(NetKitType.label).toSpanStyle()
    val address = result.address.ifEmpty { stringResource(R.string.lan_unknown_hop) }+result.port?.let { ":$it" }.orEmpty()
    val actions = ipv4Number(result.address)!=null
    fun line(first: String?, rest: List<String>): AnnotatedString? {
        val tail = rest.filter(String::isNotEmpty)
        if (first==null && tail.isEmpty()) return null
        return buildAnnotatedString {
            first?.let { withStyle(mono) { append(it) } }
            tail.forEachIndexed { index, part -> if (index>0 || first!=null) append(SEPARATOR); append(part) }
        }
    }
    // The address is the title wherever there is no better one; it is mono and never cut.
    val (title,titleMono,subtitle) = when(tool) {
        LanTool.PING -> result.sequence?.let { Triple(stringResource(R.string.lan_ping_sample,it,result.elapsedMs ?: 0),false,line(address,listOf(result.method))) }
            ?: Triple(address,true,line(null,listOf(result.method)))
        LanTool.MDNS -> if (result.name.isNotEmpty()) Triple(result.name,false,line(address,listOf(result.detail,result.method)))
            else Triple(address,true,line(null,listOf(result.detail,result.method)))
        else -> Triple(address,true,line(null,listOf(result.name,result.method,if(tool==LanTool.PORTS) "" else result.detail)))
    }
    Column {
        LanRow(AnnotatedString(title),subtitle,titleStyle=if(titleMono) NetKitType.addressFor(LanRowTitle) else LanRowTitle,showDivider=showDivider) {
            val rtt = result.milliseconds
            if (rtt!=null) Text(stringResource(R.string.lan_rtt,decimal(rtt)),style=LanRowValue,color=colors.text,maxLines=1,softWrap=false)
            else if (tool==LanTool.PING) Text(stringResource(R.string.lan_missing_value),style=LanRowValue,color=colors.err)
            if (actions) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick={ open = true }) { Icon(NetKitIcons.More,stringResource(R.string.lan_row_actions,result.address),tint=colors.muted) }
                    DropdownMenu(expanded=open,onDismissRequest={ open = false },containerColor=colors.surface2) {
                        DropdownMenuItem(text={ Text(stringResource(R.string.lan_copy_ip),style=NetKitType.body) },onClick={
                            open = false
                            if(!copy(context,result.address)) viewModel.exportIpcFailed()
                        })
                        if(tool!=LanTool.PING) DropdownMenuItem(text={ Text(stringResource(R.string.lan_ping),style=NetKitType.body) },onClick={
                            open = false
                            viewModel.selectHost(result.address)
                        })
                    }
                }
            }
        }
        // A passively read TCP banner can be long; it gets its own lines instead of the subtitle.
        if (tool==LanTool.PORTS && result.detail.isNotEmpty()) Text(result.detail,Modifier.padding(bottom=Spacing.medium),
            style=NetKitType.addressFor(NetKitType.label),color=colors.muted,maxLines=4,overflow=TextOverflow.Ellipsis)
    }
}

private sealed interface TraceItem {
    data class Hop(val result: LanResult, val destination: Boolean) : TraceItem
    data class Silent(val firstHop: String, val lastHop: String, val count: Int) : TraceItem
}

/** Consecutive hops without an address collapse into one row; the reached target is the last TTL hop with an RTT. */
private fun traceItems(results: List<LanResult>): List<TraceItem> {
    val items = mutableListOf<TraceItem>()
    var index = 0
    while (index < results.size) {
        val result = results[index]
        if (result.address.isEmpty()) {
            var end = index
            while (end+1 < results.size && results[end+1].address.isEmpty()) end++
            if (end > index) { items += TraceItem.Silent(result.detail,results[end].detail,end-index+1); index = end+1; continue }
        }
        items += TraceItem.Hop(result,index==results.lastIndex && result.method=="TTL" && result.milliseconds!=null)
        index++
    }
    return items
}

/** Timeline geometry on the 4 dp grid: hop badge 28 placed 8 below the row top; the rail meets its centre. */
private val HopColumn = 28.dp
private val HopTop = 8.dp

@Composable
private fun TracePanel(results: List<LanResult>) {
    val items = remember(results) { traceItems(results) }
    val maximum = remember(results) { results.mapNotNull { it.milliseconds }.maxOrNull() }
    Panel {
        Row(horizontalArrangement=Arrangement.spacedBy(Spacing.medium)) {
            Eyebrow(stringResource(R.string.lan_hop),Modifier.width(HopColumn))
            Eyebrow(stringResource(R.string.lan_address),Modifier.weight(1f))
            Eyebrow(stringResource(R.string.lan_latency))
        }
        Column {
            items.forEachIndexed { index, item -> TraceTimelineRow(item,first=index==0,last=index==items.lastIndex,maximum=maximum) }
        }
    }
}

@Composable
private fun TraceTimelineRow(item: TraceItem, first: Boolean, last: Boolean, maximum: Double?) {
    val colors = NetKitTheme.colors
    val rail = colors.surface3
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).semantics(mergeDescendants=true) {},horizontalArrangement=Arrangement.spacedBy(Spacing.medium)) {
        Box(Modifier.width(HopColumn).fillMaxHeight().drawBehind {
            val x = size.width/2; val centre = (HopTop+HopColumn/2).toPx(); val width = 2.dp.toPx()
            if (!first) drawLine(rail,Offset(x,0f),Offset(x,centre),width)
            if (!last) drawLine(rail,Offset(x,centre),Offset(x,size.height),width)
        },contentAlignment=Alignment.TopCenter) {
            when(item) {
                is TraceItem.Hop -> {
                    val responded = item.result.address.isNotEmpty()
                    val fill = when { item.destination -> colors.accent; responded -> colors.surface3; else -> colors.surface2 }
                    Box(Modifier.padding(top=HopTop).size(HopColumn).clip(CircleShape).background(fill),contentAlignment=Alignment.Center) {
                        Text(item.result.detail,style=NetKitType.tag,maxLines=1,softWrap=false,
                            color=when { item.destination -> colors.accentInk; responded -> colors.text; else -> colors.dim })
                    }
                }
                is TraceItem.Silent -> Box(Modifier.padding(top=HopTop+HopColumn/2-Spacing.tiny).size(Spacing.small).clip(CircleShape).background(colors.dim))
            }
        }
        Column(Modifier.weight(1f).padding(top=Spacing.medium,bottom=Spacing.small),verticalArrangement=Arrangement.spacedBy(Spacing.tiny)) {
            when(item) {
                is TraceItem.Hop -> {
                    if (item.result.address.isEmpty()) Text(stringResource(R.string.lan_trace_no_reply),style=NetKitType.body,color=colors.muted)
                    else Text(item.result.address,style=NetKitType.addressFor(NetKitType.value),color=colors.text)
                    if (item.result.name.isNotEmpty()) Text(item.result.name,style=NetKitType.label,color=colors.muted)
                }
                is TraceItem.Silent -> {
                    Text(pluralStringResource(R.plurals.lan_trace_silent,item.count,item.count),style=NetKitType.body,color=colors.muted)
                    Text(stringResource(R.string.lan_trace_silent_range,item.firstHop,item.lastHop),style=NetKitType.label,color=colors.dim)
                }
            }
        }
        if (item is TraceItem.Hop) Column(Modifier.widthIn(min=96.dp).padding(top=Spacing.medium,bottom=Spacing.small),
            horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(Spacing.small)) {
            val rtt = item.result.milliseconds
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Spacing.tiny)) {
                if (rtt!=null && item.result.method==TRACE_ECHO_METHOD) Text(stringResource(R.string.lan_trace_echo),style=NetKitType.tag,color=colors.dim)
                Text(rtt?.let { stringResource(R.string.lan_rtt,compact(it)) } ?: stringResource(R.string.lan_missing_value),
                    style=LanRowValue,color=if(rtt!=null) colors.text else colors.dim,maxLines=1,softWrap=false)
            }
            if (rtt!=null && maximum!=null && maximum>0.0) ValueBar((rtt/maximum).toFloat(),modifier=Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LanExportSheet(results: List<LanResult>, format: LanExport, error: Int?, viewModel: LanViewModel, onSave: () -> Unit) {
    val context=LocalContext.current
    val colors=NetKitTheme.colors
    val content by produceState<String?>(null,results,format) {
        value=null
        try { value=withContext(Dispatchers.IO) { exportLan(results,format) } }
        catch(error: kotlinx.coroutines.CancellationException) { throw error }
        catch(_: Exception) { viewModel.exportFailed() }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=Spacing.card).padding(bottom=Spacing.large),
        verticalArrangement=Arrangement.spacedBy(Spacing.cardGap)) {
        Text(stringResource(R.string.lan_export_title),style=NetKitType.panelTitle,color=colors.text)
        Row(horizontalArrangement=Arrangement.spacedBy(Spacing.small)) {
            LanExport.entries.forEach { entry -> ChoiceChip(stringResource(when(entry) {LanExport.TEXT->R.string.lan_format_text;LanExport.CSV->R.string.lan_format_csv;LanExport.JSON->R.string.lan_format_json}),
                format==entry) { viewModel.exportFormat(entry) } }
        }
        val snapshot=content
        val ipcAllowed=snapshot!=null && lanTextFitsIpc(snapshot)
        if(snapshot!=null && !ipcAllowed) StatusBanner(stringResource(R.string.lan_export_use_file),StatusKind.WARNING)
        // Copy/share failures land in the screen state; repeat them here, the sheet covers the screen banner.
        if(error!=null && error in EXPORT_ERRORS) StatusBanner(stringResource(error),StatusKind.ERROR)
        Row(horizontalArrangement=Arrangement.spacedBy(Spacing.small)) {
            NeutralButton(stringResource(R.string.lan_copy_all),{ if(snapshot!=null && !copy(context,snapshot)) viewModel.exportIpcFailed() },
                Modifier.weight(1f),enabled=ipcAllowed)
            NeutralButton(stringResource(R.string.lan_share),{
                if(snapshot!=null && lanTextFitsIpc(snapshot)) try {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type=when(format) {LanExport.CSV->"text/csv";LanExport.JSON->"application/json";else->"text/plain"}
                        putExtra(Intent.EXTRA_TEXT,snapshot)
                    },context.getString(R.string.lan_share)))
                } catch(_:Exception) {viewModel.exportIpcFailed()}
            },Modifier.weight(1f),enabled=ipcAllowed)
        }
        PrimaryButton(stringResource(R.string.lan_save_export),onSave,Modifier.fillMaxWidth(),icon=NetKitIcons.Download)
    }
}

@Composable
private fun Field(value:String,onChange:(String)->Unit,label:Int,enabled:Boolean,modifier:Modifier=Modifier,number:Boolean=false,mono:Boolean=false) {
    val colors=NetKitTheme.colors
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(stringResource(label),maxLines=1,overflow=TextOverflow.Ellipsis)},enabled=enabled,
        singleLine=true,modifier=modifier.fillMaxWidth(),shape=NetKitShape.nested,
        textStyle=if(mono) NetKitType.addressFor(NetKitType.value) else NetKitType.value,
        colors=OutlinedTextFieldDefaults.colors(
            focusedTextColor=colors.text,unfocusedTextColor=colors.text,disabledTextColor=colors.muted,
            focusedBorderColor=colors.accent,unfocusedBorderColor=colors.border,disabledBorderColor=colors.borderSoft,
            focusedLabelColor=colors.accent,unfocusedLabelColor=colors.muted,disabledLabelColor=colors.dim,cursorColor=colors.accent),
        keyboardOptions=KeyboardOptions(keyboardType=if(number)KeyboardType.Number else KeyboardType.Ascii))
}

/**
 * Ping RTT over time. Samples come every 1 s plus their own RTT or timeout, so they are not
 * time-uniform and the shared `TimeSeriesChart` (fixed sample interval) would misplace them; x is
 * the real time since the run started. Grid, line, fill and timeout markers follow docs/design.md §6.
 */
@Composable
private fun PingGraph(samples:List<LanResult>) {
    val colors=NetKitTheme.colors
    val description=stringResource(R.string.lan_graph)
    val measurer=rememberTextMeasurer()
    val locale=LocalConfiguration.current.locales[0]
    val format=remember(locale) { NumberFormat.getNumberInstance(locale).apply { isGroupingUsed=false; maximumFractionDigits=2 } }
    val tick=NetKitType.valueSmall.copy(fontSize=11.sp,lineHeight=14.sp,color=colors.dim)
    val top=niceMax(((samples.mapNotNull { it.milliseconds }.maxOrNull() ?: 1.0)*1.15).toFloat())
    val ticks=listOf(0f,top/2f,top)
    val startMs=samples.firstOrNull()?.elapsedMs ?: 0L
    val endMs=samples.lastOrNull()?.elapsedMs ?: 0L
    val timeLabels=listOf(stringResource(R.string.lan_graph_time,startMs/1000),stringResource(R.string.lan_graph_time,endMs/1000))
    val line=colors.accent; val loss=colors.err; val grid=colors.divider; val ground=colors.surface
    Column(verticalArrangement=Arrangement.spacedBy(Spacing.tiny)) {
        Canvas(Modifier.fillMaxWidth().height(136.dp).clearAndSetSemantics { contentDescription=description }) {
            val labels=ticks.map { measurer.measure(format.format(it),tick) }
            val left=(labels.maxOf { it.size.width }+Spacing.small.toPx())
            val axis=Spacing.large.toPx()
            val plotTop=Spacing.small.toPx(); val plotBottom=size.height-axis
            val width=size.width-left
            fun y(v: Double)=(plotBottom-(plotBottom-plotTop)*(v/top).coerceIn(0.0,1.0)).toFloat()
            val duration=(endMs-startMs).coerceAtLeast(1L)
            fun x(sample: LanResult)=left+width*((sample.elapsedMs ?: startMs)-startMs).toFloat()/duration
            ticks.forEachIndexed { index, t ->
                val ty=y(t.toDouble())
                drawLine(grid,Offset(left,ty),Offset(size.width,ty),1.dp.toPx())
                val label=labels[index]
                drawText(label,topLeft=Offset(left-Spacing.small.toPx()-label.size.width,(ty-label.size.height/2f).coerceIn(0f,size.height-label.size.height)))
            }
            val dash=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),3.dp.toPx()))
            samples.forEach { sample -> if (sample.milliseconds==null) drawLine(loss,Offset(x(sample),plotTop),Offset(x(sample),plotBottom),1.dp.toPx(),pathEffect=dash) }
            // One path per run of answered samples; a timeout breaks the line.
            var run = mutableListOf<Offset>()
            fun flush() {
                if (run.isEmpty()) return
                val stroke=Path().apply { moveTo(run[0].x,run[0].y); run.drop(1).forEach { lineTo(it.x,it.y) } }
                val area=Path().apply { addPath(stroke); lineTo(run.last().x,plotBottom); lineTo(run[0].x,plotBottom); close() }
                drawPath(area,Brush.verticalGradient(listOf(line.copy(alpha=.25f),line.copy(alpha=0f)),startY=plotTop,endY=plotBottom))
                if (run.size==1) drawCircle(line,1.6.dp.toPx(),run[0])
                else drawPath(stroke,line,style=Stroke(2.2.dp.toPx(),cap=StrokeCap.Round))
                run = mutableListOf()
            }
            samples.forEach { sample -> val v=sample.milliseconds; if (v==null) flush() else run += Offset(x(sample),y(v)) }
            flush()
            samples.lastOrNull()?.milliseconds?.let { v ->
                val end=Offset(x(samples.last()),y(v))
                drawCircle(line,3.6.dp.toPx(),end); drawCircle(ground,1.6.dp.toPx(),end)
            }
            val first=measurer.measure(timeLabels[0],tick); val lastLabel=measurer.measure(timeLabels[1],tick)
            val labelY=size.height-first.size.height
            drawText(first,topLeft=Offset(left,labelY))
            drawText(lastLabel,topLeft=Offset(size.width-lastLabel.size.width,labelY))
        }
        Text(description,style=NetKitType.label,color=colors.dim)
    }
}
private fun decimal(value:Double):String=String.format(Locale.getDefault(),"%.3f",value)
/** Short RTT for tiles and the hop timeline; full precision stays in rows and exports. */
private fun compact(value:Double):String=String.format(Locale.getDefault(),when { value<10.0 -> "%.2f"; value<100.0 -> "%.1f"; else -> "%.0f" },value)
private fun copy(context:Context,text:String):Boolean {
    if(!lanTextFitsIpc(text))return false
    return try {context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(context.getString(R.string.lan_title),text));true}
    catch(_:Exception){false}
}
