package cz.netkit.app.wol

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netkit.app.BuildConfig
import cz.netkit.app.R
import cz.netkit.app.ui.EmptyState
import cz.netkit.app.ui.Eyebrow
import cz.netkit.app.ui.InfoNote
import cz.netkit.app.ui.NetKitIcons
import cz.netkit.app.ui.NeutralButton
import cz.netkit.app.ui.Panel
import cz.netkit.app.ui.PrimaryButton
import cz.netkit.app.ui.ScreenHeader
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.StatusBanner
import cz.netkit.app.ui.StatusKind
import cz.netkit.app.ui.Tone
import cz.netkit.app.ui.motion.MotionToken
import cz.netkit.app.ui.motion.SPIN_MS
import cz.netkit.app.ui.motion.rememberLoopingAllowed
import cz.netkit.app.ui.motion.rememberNetKitTween
import cz.netkit.app.ui.theme.NetKitFonts
import cz.netkit.app.ui.theme.NetKitShape
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.app.ui.tone
import cz.netkit.shared.wol.DeviceField
import cz.netkit.shared.wol.SendFailure
import cz.netkit.shared.wol.StoreFailure
import cz.netkit.shared.wol.TargetMode
import cz.netkit.shared.wol.WakeProbeStatus
import cz.netkit.shared.wol.WolController
import cz.netkit.shared.wol.WolDevice
import cz.netkit.shared.wol.WolDeviceDraft
import cz.netkit.shared.wol.WolMessage
import cz.netkit.shared.wol.WolSendOutcome

/**
 * JSON export of NetKit plus the CSV export of the Wake On Lan app. Providers label CSV
 * inconsistently (some as octet-stream), so any file can be picked; the content decides the format.
 */
private val WOL_IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "text/csv", "text/comma-separated-values",
    "application/csv", "application/vnd.ms-excel", "application/octet-stream", "*/*")

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun WolScreen(viewModel: WolViewModel, modifier: Modifier = Modifier) {
    val controller = viewModel.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val transferBusy by viewModel.transferBusy.collectAsStateWithLifecycle()
    val transferMessage by viewModel.transferMessage.collectAsStateWithLifecycle()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var exportConfirm by rememberSaveable { mutableStateOf<WolExportFormat?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importFrom) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportTo(it, WolExportFormat.JSON) } }
    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { viewModel.exportTo(it, WolExportFormat.WEBB_CSV) } }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = state.draft
    val message = state.message
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val imeVisible = WindowInsets.isImeVisible
    val colors = NetKitTheme.colors
    val requestDiscard: () -> Unit = {
        if (!state.saving) {
            if (viewModel.hasDraftChanges()) confirmDiscard = true else controller.cancelDraft()
        }
    }
    BackHandler(enabled = draft != null) {
        if (imeVisible) { keyboard?.hide(); focus.clearFocus() } else requestDiscard()
    }
    if (draft != null) {
        WolForm(draft, state.errors, state.saving, message, controller, requestDiscard, modifier)
    } else {
        // Same condition as the former add button and "Manage devices" menu button.
        val manageEnabled = !state.saving && !transferBusy && state.sendingDeviceId == null
        val showManage = !state.loading && !state.loadFailed
        // The header stays outside the list: `wol_add` is always visible, so the instrumentation
        // recognises the list screen at any scroll position.
        Column(modifier) {
            val addLabel = stringResource(R.string.add_device)
            Box(Modifier.padding(start = Spacing.normal, end = Spacing.normal, top = Spacing.normal)) {
                ScreenHeader(stringResource(R.string.wol_title), stringResource(R.string.wol_authorized), trailing = if (!showManage) null else ({
                    NeutralButton(stringResource(R.string.wol_add_compact), onClick = viewModel::add, enabled = manageEnabled, small = true,
                        icon = NetKitIcons.Plus, modifier = Modifier.testTag("wol_add").semantics { contentDescription = addLabel })
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = manageEnabled) {
                            Icon(NetKitIcons.More, stringResource(R.string.wol_actions), tint = colors.muted)
                        }
                        WolMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                            DropdownMenuItem(text = { MenuText(stringResource(R.string.wol_sort_name)) }, onClick = { menuOpen = false; controller.sortByName() })
                            DropdownMenuItem(text = { MenuText(stringResource(R.string.wol_import)) }, onClick = {
                                menuOpen = false; viewModel.clearTransferMessage()
                                try { importLauncher.launch(WOL_IMPORT_MIME_TYPES) }
                                catch (_: ActivityNotFoundException) { viewModel.documentUnavailable() }
                            })
                            DropdownMenuItem(text = { MenuText(stringResource(R.string.wol_export)) }, onClick = {
                                menuOpen = false; viewModel.clearTransferMessage(); exportConfirm = WolExportFormat.JSON
                            })
                            DropdownMenuItem(text = { MenuText(stringResource(R.string.wol_export_csv)) }, onClick = {
                                menuOpen = false; viewModel.clearTransferMessage(); exportConfirm = WolExportFormat.WEBB_CSV
                            })
                        }
                    }
                }))
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(Spacing.normal), verticalArrangement = Arrangement.spacedBy(Spacing.normal)) {
                transferMessage?.let { transfer -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        StatusBanner(stringResource(when (transfer) {
                            WolTransferMessage.IMPORT_FAILED -> R.string.wol_import_failed
                            WolTransferMessage.IMPORT_SIZE -> R.string.wol_import_size
                            WolTransferMessage.IMPORT_UTF8 -> R.string.wol_import_utf8
                            WolTransferMessage.IMPORT_FORMAT -> R.string.wol_import_format
                            WolTransferMessage.IMPORT_SCHEMA -> R.string.wol_import_schema
                            WolTransferMessage.IMPORT_UNKNOWN_FIELD -> R.string.wol_import_unknown_field
                            WolTransferMessage.IMPORT_DUPLICATE_ID -> R.string.wol_import_duplicate_id
                            WolTransferMessage.IMPORT_INVALID_DEVICE -> R.string.wol_import_invalid_device
                            WolTransferMessage.EXPORT_FAILED -> R.string.wol_export_failed
                            WolTransferMessage.EXPORTED -> R.string.wol_export_done
                            WolTransferMessage.IMPORT_BLOCKED -> R.string.wol_import_blocked
                            WolTransferMessage.CANCELLED -> R.string.wol_transfer_cancelled
                            WolTransferMessage.DOCUMENT_UNAVAILABLE -> R.string.wol_document_unavailable
                        }), if (transfer in listOf(WolTransferMessage.EXPORTED, WolTransferMessage.CANCELLED)) StatusKind.INFO else StatusKind.ERROR)
                        NeutralButton(stringResource(R.string.wol_dismiss_message), onClick = viewModel::clearTransferMessage, small = true)
                    }
                } }
                if (transferBusy) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, trackColor = colors.surface3, strokeWidth = 2.dp)
                        NeutralButton(stringResource(R.string.wol_cancel_transfer), onClick = viewModel::cancelTransfer, small = true)
                    }
                }
                if (state.loading) item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                }
                if (message == WolMessage.SAVE_FAILED) item { StatusMessage(message) }
                state.archivePath?.let { path -> item {
                    Text(stringResource(R.string.recovery_archive, path), modifier = Modifier.testTag("recovery_archive"),
                        style = NetKitType.label, color = colors.muted)
                } }
                if (state.loadFailed) {
                    item {
                        Box(Modifier.testTag("wol_load_error")) {
                            StatusBanner(stringResource(when (state.loadFailure) {
                                StoreFailure.CORRUPT -> R.string.load_corrupt
                                StoreFailure.UNSUPPORTED_SCHEMA -> R.string.load_unsupported
                                else -> R.string.load_io
                            }), StatusKind.ERROR)
                        }
                    }
                    if (state.recovering) item { CircularProgressIndicator(color = colors.accent) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            PrimaryButton(stringResource(R.string.retry), onClick = controller::retryLoad, enabled = !state.recovering,
                                small = true, modifier = Modifier.testTag("wol_retry"))
                            if (state.loadFailure == StoreFailure.CORRUPT || state.loadFailure == StoreFailure.UNSUPPORTED_SCHEMA)
                                NeutralButton(stringResource(R.string.recover_action), onClick = controller::requestRecovery,
                                    enabled = !state.recovering, small = true, modifier = Modifier.testTag("wol_recover"))
                        }
                    }
                }
                if (!state.loading && !state.loadFailed) {
                    if (state.devices.isEmpty()) item {
                        Panel {
                            EmptyState(stringResource(R.string.no_devices), NetKitIcons.Power) {
                                PrimaryButton(addLabel, onClick = viewModel::add, enabled = manageEnabled, icon = NetKitIcons.Plus)
                            }
                        }
                    }
                    items(state.devices, key = { it.id }) { device ->
                        DeviceCard(device, state.devices, sending = state.sendingDeviceId == device.id, probing = state.probingDeviceId == device.id,
                            probe = state.probes[device.id], outcome = state.outcomes[device.id], manageEnabled = manageEnabled,
                            wakeEnabled = state.sendingDeviceId == null && state.probingDeviceId != device.id && !state.saving && !transferBusy,
                            onWake = { controller.wake(device.id) }, onCancel = controller::cancelWake,
                            onEdit = { viewModel.edit(device.id) }, onDelete = { deletingId = device.id },
                            onMove = { controller.move(device.id, it) })
                    }
                    item { InfoNote(stringResource(R.string.wol_network_note), stringResource(R.string.wol_vpn_notice)) }
                }
            }
        }
    }
    exportConfirm?.let { format ->
        val csv = format == WolExportFormat.WEBB_CSV
        WolAlert(onDismiss = { exportConfirm = null },
            title = stringResource(if (csv) R.string.wol_export_csv else R.string.wol_export),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(stringResource(R.string.wol_export_warning), style = NetKitType.body)
                    if (csv) Text(stringResource(R.string.wol_export_csv_note), style = NetKitType.body, color = colors.muted)
                }
            },
            confirm = { PrimaryButton(stringResource(R.string.wol_export_confirm), small = true, onClick = {
                exportConfirm = null
                try { if (csv) exportCsvLauncher.launch("wol-netkit.csv") else exportLauncher.launch("netkit-wol.json") }
                catch (_: ActivityNotFoundException) { viewModel.documentUnavailable() }
            }) },
            dismiss = { NeutralButton(stringResource(R.string.cancel), small = true, onClick = { exportConfirm = null }) })
    }
    state.pendingImport?.let { incoming ->
        val existing = state.devices.map { it.id }.toSet()
        val overwritten = incoming.count { it.id in existing }
        WolAlert(onDismiss = controller::cancelImport, title = stringResource(R.string.wol_import_title),
            text = { LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        Text(stringResource(R.string.wol_import_preview, incoming.size - overwritten, overwritten),
                            style = NetKitType.body, color = colors.text)
                        val duplicates = cz.netkit.shared.wol.duplicateMacCount(state.devices.filterNot { old -> incoming.any { it.id == old.id } } + incoming)
                        if (duplicates > 0) StatusBanner(pluralStringResource(R.plurals.wol_import_duplicate_mac, duplicates, duplicates), StatusKind.WARNING)
                        // CSV from Wake On Lan (Mike Webb): rejected rows and dropped SecureOn passwords are disclosed, never silent.
                        if (state.skippedCount > 0) StatusBanner(stringResource(R.string.wol_import_skipped, state.skippedCount), StatusKind.WARNING)
                        if (state.secureOnDropped > 0) StatusBanner(stringResource(R.string.wol_import_secure_on_dropped, state.secureOnDropped), StatusKind.WARNING)
                    }
                }
                items(incoming, key = { it.id }) { device ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
                        HorizontalDivider(Modifier.padding(bottom = Spacing.small), thickness = 1.dp, color = colors.divider)
                        Text(device.name, style = NetKitType.panelTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val old = state.devices.find { it.id == device.id }
                        Text(stringResource(if (old == null) R.string.wol_import_add_item else R.string.wol_import_replace_item, old?.name ?: device.name),
                            style = NetKitType.label, color = colors.muted)
                        Text(deviceAddressLine(device), style = NetKitType.label, color = colors.muted)
                        val customTarget = device.customTarget?.takeIf { device.targetMode == TargetMode.CUSTOM }
                        customTarget?.let { target ->
                            if (!cz.netkit.shared.wol.isPrivateWolImportTarget(target)) StatusBanner(stringResource(R.string.wol_import_target_warning), StatusKind.WARNING)
                        }
                        if (device.probeEnabled) device.probeHost?.let { host ->
                            Text(stringResource(R.string.saved_probe, host, device.probePort?.toString().orEmpty()),
                                style = NetKitType.addressFor(NetKitType.label), color = colors.muted)
                            if (!cz.netkit.shared.wol.isPrivateWolImportTarget(host)) StatusBanner(stringResource(R.string.wol_import_target_warning), StatusKind.WARNING)
                        }
                    }
                }
            } },
            confirm = { PrimaryButton(stringResource(R.string.wol_import_confirm), small = true, onClick = controller::confirmImport,
                enabled = !state.saving && !transferBusy && state.sendingDeviceId == null) },
            dismiss = { NeutralButton(stringResource(R.string.cancel), small = true, onClick = controller::cancelImport) })
    }
    if (state.recoveryConfirmationPending) {
        WolAlert(
            onDismiss = controller::cancelRecovery,
            title = stringResource(R.string.recover_title),
            text = { Text(stringResource(R.string.recover_question), style = NetKitType.body) },
            confirm = { PrimaryButton(stringResource(R.string.recover_action), small = true, onClick = controller::confirmRecovery,
                modifier = Modifier.testTag("recovery_confirm")) },
            dismiss = { NeutralButton(stringResource(R.string.cancel), small = true, onClick = controller::cancelRecovery,
                modifier = Modifier.testTag("recovery_cancel")) },
        )
    }
    if (confirmDiscard && draft != null) {
        WolAlert(
            onDismiss = { confirmDiscard = false },
            title = stringResource(R.string.discard_title),
            text = { Text(stringResource(R.string.discard_question), style = NetKitType.body) },
            confirm = { PrimaryButton(stringResource(R.string.discard), small = true, onClick = { confirmDiscard = false; controller.cancelDraft() },
                modifier = Modifier.testTag("draft_discard_confirm")) },
            dismiss = { NeutralButton(stringResource(R.string.keep_editing), small = true, onClick = { confirmDiscard = false },
                modifier = Modifier.testTag("draft_discard_cancel")) },
        )
    }
    deletingId?.let { id ->
        WolAlert(
            onDismiss = { deletingId = null },
            title = stringResource(R.string.delete_title),
            text = { Text(stringResource(R.string.delete_question), style = NetKitType.body) },
            confirm = { PrimaryButton(stringResource(R.string.delete), small = true, onClick = { controller.delete(id); deletingId = null },
                modifier = Modifier.testTag("device_delete_confirm")) },
            dismiss = { NeutralButton(stringResource(R.string.cancel), small = true, onClick = { deletingId = null },
                modifier = Modifier.testTag("device_delete_cancel")) },
        )
    }
}

/**
 * Device card (prototype `vWol`): name, MAC and target in mono, state tag and the round wake
 * button. Edit, delete and reorder sit in a compact row under a divider, each one tap away,
 * because the instrumentation clicks `wol_edit_<id>` and `device_delete_<id>` directly.
 */
@Composable
private fun DeviceCard(
    device: WolDevice, devices: List<WolDevice>, sending: Boolean, probing: Boolean, probe: WakeProbeStatus?, outcome: WolSendOutcome?,
    manageEnabled: Boolean, wakeEnabled: Boolean, onWake: () -> Unit, onCancel: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val colors = NetKitTheme.colors
    val state = wolState(sending, probing, probe, outcome, device.probePort)
    Panel(Modifier.testTag("wol_device_${device.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.name, style = DeviceName, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(deviceAddressLine(device), style = NetKitType.label, color = colors.muted)
                device.note?.let { Text(it, style = NetKitType.label, color = colors.dim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                StateTag(state, Modifier.padding(top = Spacing.small),
                    textModifier = if (outcome != null) Modifier.testTag("wol_status_${device.id}") else Modifier)
            }
            WakeButton(busy = sending || probing, done = state.done, enabled = wakeEnabled, onClick = onWake,
                label = stringResource(R.string.wake) + " " + device.name, modifier = Modifier.testTag("wol_send_${device.id}"))
        }
        state.detail?.let { Text(stringResource(it), style = NetKitType.label, color = if (state.tone == Tone.ERR) colors.err else colors.muted) }
        if (sending || probing) NeutralButton(stringResource(if (probing) R.string.wol_cancel_probe else R.string.cancel_sending), onClick = onCancel,
            small = true, modifier = Modifier.testTag("wol_cancel_${device.id}"))
        HorizontalDivider(thickness = 1.dp, color = colors.divider)
        Row(Modifier.padding(top = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            CardAction(stringResource(R.string.edit), colors.text, manageEnabled, onEdit, Modifier.testTag("wol_edit_${device.id}"))
            CardAction(stringResource(R.string.delete), colors.err, manageEnabled, onDelete, Modifier.testTag("device_delete_${device.id}"))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onMove(-1) }, enabled = manageEnabled && devices.first().id != device.id) {
                Icon(NetKitIcons.ChevronRight, stringResource(R.string.wol_move_up), Modifier.size(20.dp).rotate(-90f),
                    tint = if (manageEnabled && devices.first().id != device.id) colors.muted else colors.dim)
            }
            IconButton(onClick = { onMove(1) }, enabled = manageEnabled && devices.last().id != device.id) {
                Icon(NetKitIcons.ChevronRight, stringResource(R.string.wol_move_down), Modifier.size(20.dp).rotate(90f),
                    tint = if (manageEnabled && devices.last().id != device.id) colors.muted else colors.dim)
            }
        }
    }
}

private val DeviceName = NetKitType.panelTitle.copy(fontSize = 16.sp, lineHeight = 22.sp)

/** Compact text action of the card row; not outlined, small-button radius, 48 dp touch height. */
@Composable
private fun CardAction(text: String, color: Color, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = NetKitShape.buttonSmall,
        contentPadding = PaddingValues(horizontal = Spacing.medium),
        colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = NetKitTheme.colors.dim)) {
        Text(text, style = NetKitType.body.copy(fontWeight = FontWeight.Bold))
    }
}

/** "B4:2E:99:…:7C · broadcast Wi-Fi · UDP 9" with MAC, address targets and the port in mono. */
@Composable
private fun deviceAddressLine(device: WolDevice) = buildAnnotatedString {
    val mono = SpanStyle(fontFamily = NetKitFonts.mono, fontSize = 0.92.em, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).em)
    withStyle(mono) { append(device.mac) }
    append(" · ")
    when (device.targetMode) {
        TargetMode.CUSTOM -> withStyle(mono) { append(device.customTarget.orEmpty()) }
        TargetMode.LIMITED_BROADCAST -> withStyle(mono) { append(stringResource(R.string.wol_target_short_limited)) }
        TargetMode.AUTO_BROADCAST -> append(stringResource(R.string.wol_target_short_auto))
    }
    append(" · ")
    append(stringResource(R.string.wol_port_short, device.port))
}

/** Card state: text and tone of the tag, whether the wake button is filled, optional detail line. */
private data class WolCardState(val text: String, val tone: Tone, val done: Boolean = false, val detail: Int? = null)

/**
 * Card state from the controller: an active send or check wins, then the last outcome combined
 * with the probe result ("odesláno · odpovídá na 22"). Every post-send text starts with
 * `R.string.sent`, which the instrumentation looks for in `wol_status_<id>`.
 */
@Composable
private fun wolState(sending: Boolean, probing: Boolean, probe: WakeProbeStatus?, outcome: WolSendOutcome?, probePort: Int?): WolCardState {
    val waiting = probing || probe == WakeProbeStatus.WAITING
    if (sending) return WolCardState(stringResource(R.string.wol_chip_sending), Tone.INFO)
    if (outcome == null) return WolCardState(stringResource(if (waiting) R.string.wol_chip_checking else R.string.wol_state_idle),
        if (waiting) Tone.INFO else Tone.NEUTRAL)
    if (!outcome.sent) return when (outcome.reason) {
        SendFailure.CANCELLED -> WolCardState(stringResource(R.string.wol_chip_cancelled), Tone.NEUTRAL, detail = R.string.send_cancelled)
        else -> WolCardState(stringResource(R.string.wol_state_failed), Tone.ERR, detail = sendFailure(outcome.reason))
    }
    val sent = stringResource(R.string.sent)
    val (suffix, tone) = if (waiting) stringResource(R.string.wol_chip_checking) to Tone.INFO else when (probe) {
        null -> stringResource(R.string.wol_state_unverified) to Tone.ACCENT
        WakeProbeStatus.RESPONDS -> (if (probePort != null) stringResource(R.string.wol_state_responds_port, probePort)
            else stringResource(R.string.wol_state_responds_ping)) to Tone.OK
        WakeProbeStatus.ALREADY_RESPONDED -> stringResource(R.string.wol_chip_already) to Tone.INFO
        WakeProbeStatus.NO_RESPONSE -> stringResource(R.string.wol_chip_no_response) to Tone.WARN
        WakeProbeStatus.UNAVAILABLE -> stringResource(R.string.wol_chip_unavailable) to Tone.NEUTRAL
        WakeProbeStatus.CANCELLED -> stringResource(R.string.wol_state_probe_cancelled) to Tone.NEUTRAL
        WakeProbeStatus.WAITING -> stringResource(R.string.wol_chip_checking) to Tone.INFO
    }
    return WolCardState("$sent · $suffix", tone, done = !waiting && (probe == null || probe == WakeProbeStatus.RESPONDS))
}

private fun sendFailure(reason: SendFailure?) = when (reason) {
    null -> R.string.sent
    SendFailure.DNS -> R.string.send_dns
    SendFailure.TIMEOUT -> R.string.send_timeout
    SendFailure.NETWORK_LOST -> R.string.send_network_lost
    SendFailure.IO -> R.string.send_io
    SendFailure.CANCELLED -> R.string.send_cancelled
    SendFailure.NO_WIFI -> R.string.no_wifi
    SendFailure.NO_BROADCAST -> R.string.no_broadcast
}

/**
 * State tag in the look of `Tag` (radius 8, padding 4 × 9, `tag` style, 6 dp dot), but the text
 * may wrap: "odesláno · odpovídal už předtím" must not be cut at 360 dp and font scale 1.3.
 * [textModifier] lands on the text node so its accessibility text is the tag text.
 */
@Composable
private fun StateTag(state: WolCardState, modifier: Modifier = Modifier, textModifier: Modifier = Modifier) {
    val target = NetKitTheme.colors.tone(state.tone)
    val fg by animateColorAsState(target.foreground, rememberNetKitTween(MotionToken.FAST), label = "wol-tag-fg")
    val fill by animateColorAsState(target.fill, rememberNetKitTween(MotionToken.FAST), label = "wol-tag-fill")
    Row(modifier.clip(NetKitShape.tag).background(fill).padding(horizontal = 9.dp, vertical = Spacing.tiny),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.tone != Tone.NEUTRAL) Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Text(state.text, textModifier, style = NetKitType.tag, color = fg)
    }
}

/**
 * Round wake button (design.md §5): 52 dp, `accentSoft`/`accent`; while sending or checking a
 * ring spins around it (`spin` token, static when motion is reduced), after success it is filled
 * `accent`. Cancelling stays a separate button, so a second tap never cancels by accident.
 */
@Composable
private fun WakeButton(busy: Boolean, done: Boolean, enabled: Boolean, onClick: () -> Unit, label: String, modifier: Modifier) {
    val colors = NetKitTheme.colors
    val filled = done && !busy
    val container by animateColorAsState(when { filled -> colors.accent; enabled || busy -> colors.accentSoft; else -> colors.surface2 },
        rememberNetKitTween(MotionToken.FAST), label = "wake-fill")
    val tint by animateColorAsState(when { filled -> colors.accentInk; enabled || busy -> colors.accent; else -> colors.dim },
        rememberNetKitTween(MotionToken.FAST), label = "wake-tint")
    Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
        if (busy) SpinRing(colors.accent)
        Box(modifier.minimumInteractiveComponentSize().size(52.dp).clip(CircleShape).background(container)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
            contentAlignment = Alignment.Center) {
            Icon(NetKitIcons.Power, null, Modifier.size(24.dp), tint = tint)
        }
    }
}

@Composable
private fun SpinRing(color: Color) {
    val angle: State<Float>? = if (rememberLoopingAllowed()) {
        val transition = rememberInfiniteTransition(label = "wake-spin")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart), label = "wake-angle")
    } else null
    // The angle is read in the layer block (draw phase), so the spin does not recompose every frame.
    Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle?.value ?: 0f }) {
        val stroke = 2.dp.toPx()
        drawArc(color, startAngle = -90f, sweepAngle = 90f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun MenuText(text: String) {
    Text(text, style = NetKitType.body, color = NetKitTheme.colors.text)
}

@Composable
private fun WolMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = NetKitShape.nested,
        containerColor = NetKitTheme.colors.surface2, tonalElevation = 0.dp,
        modifier = Modifier.semantics { testTagsAsResourceId = BuildConfig.DEBUG }, content = content)
}

/** Alert on `surface` with the card radius; keeps the tags of its buttons exposed as resource ids. */
@Composable
private fun WolAlert(
    onDismiss: () -> Unit, title: String, confirm: @Composable () -> Unit, dismiss: @Composable () -> Unit, text: @Composable () -> Unit,
) {
    val colors = NetKitTheme.colors
    AlertDialog(onDismissRequest = onDismiss, confirmButton = confirm, dismissButton = dismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = BuildConfig.DEBUG },
        title = { Text(title, style = NetKitType.screenTitle.copy(fontSize = 20.sp, lineHeight = 26.sp), color = colors.text) },
        text = text, shape = NetKitShape.card, containerColor = colors.surface, tonalElevation = 0.dp,
        titleContentColor = colors.text, textContentColor = colors.text)
}

@Composable
private fun WolForm(
    draft: WolDeviceDraft, errors: Set<DeviceField>, saving: Boolean, message: WolMessage?,
    controller: WolController, requestDiscard: () -> Unit, modifier: Modifier,
) {
    val colors = NetKitTheme.colors
    LazyColumn(modifier.imePadding().testTag("device_form"), contentPadding = PaddingValues(Spacing.normal), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        item { FormHeader(stringResource(if (draft.id == null) R.string.add_device else R.string.edit_device), onBack = requestDiscard, enabled = !saving) }
        if (message == WolMessage.SAVE_FAILED) item { StatusMessage(message) }
        item { FormSection(R.string.wol_section_device) }
        item { DeviceTextField(draft.name, { controller.updateDraft(draft.copy(name = it)) }, R.string.device_name, "device_name", DeviceField.NAME in errors, saving) }
        item { DeviceTextField(draft.mac, { controller.updateDraft(draft.copy(mac = it)) }, R.string.device_mac, "device_mac", DeviceField.MAC in errors, saving) }
        item { FormSection(R.string.target_mode) }
        items(TargetMode.entries) { mode ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(NetKitShape.nested).testTag("target_mode_${modeTag(mode)}")
                    .selectable(selected = draft.targetMode == mode, enabled = !saving,
                        role = Role.RadioButton, onClick = { controller.updateDraft(draft.copy(targetMode = mode)) })
                    .padding(horizontal = Spacing.tiny),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                RadioButton(selected = draft.targetMode == mode, onClick = null, enabled = !saving,
                    colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.muted,
                        disabledSelectedColor = colors.dim, disabledUnselectedColor = colors.dim))
                Text(stringResource(targetLabel(mode)), Modifier.weight(1f), style = NetKitType.body, color = colors.text)
            }
        }
        if (draft.targetMode == TargetMode.CUSTOM) item {
            DeviceTextField(draft.customTarget, { controller.updateDraft(draft.copy(customTarget = it)) }, R.string.custom_target, "custom_target", DeviceField.CUSTOM_TARGET in errors, saving)
        }
        item { DeviceTextField(draft.port, { controller.updateDraft(draft.copy(port = it)) }, R.string.device_port, "device_port", DeviceField.PORT in errors, saving, KeyboardType.Number) }
        item { FormSection(R.string.wol_section_repeat) }
        item { DeviceTextField(draft.repeatCount, { controller.updateDraft(draft.copy(repeatCount = it)) }, R.string.wol_repeat_count, "repeat_count", DeviceField.REPEAT_COUNT in errors, saving, KeyboardType.Number) }
        item { DeviceTextField(draft.repeatIntervalMs, { controller.updateDraft(draft.copy(repeatIntervalMs = it)) }, R.string.wol_repeat_interval, "repeat_interval", DeviceField.REPEAT_INTERVAL in errors, saving, KeyboardType.Number) }
        item { FormSection(R.string.wol_section_probe) }
        item { Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(NetKitShape.nested).toggleable(value = draft.probeEnabled, enabled = !saving, role = Role.Checkbox,
            onValueChange = { controller.updateDraft(draft.copy(probeEnabled = it)) }).padding(horizontal = Spacing.tiny),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Checkbox(checked = draft.probeEnabled, onCheckedChange = null, enabled = !saving,
                colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.muted, checkmarkColor = colors.accentInk,
                    disabledCheckedColor = colors.dim, disabledUncheckedColor = colors.dim))
            Text(stringResource(R.string.wol_probe_enabled), Modifier.weight(1f), style = NetKitType.body, color = colors.text)
        } }
        item { DeviceTextField(draft.probeDelaySeconds, { controller.updateDraft(draft.copy(probeDelaySeconds = it)) }, R.string.wol_probe_delay, "probe_delay", DeviceField.PROBE_DELAY in errors, saving, KeyboardType.Number) }
        item { Text(stringResource(R.string.probe_hint), style = NetKitType.label, color = colors.muted) }
        item { DeviceTextField(draft.probeHost, { controller.updateDraft(draft.copy(probeHost = it)) }, R.string.probe_host, "probe_host", DeviceField.PROBE_HOST in errors, saving) }
        item { DeviceTextField(draft.probePort, { controller.updateDraft(draft.copy(probePort = it)) }, R.string.probe_port, "probe_port", DeviceField.PROBE_PORT in errors, saving, KeyboardType.Number) }
        item { FormSection(R.string.wol_section_note) }
        item { DeviceTextField(draft.note, { controller.updateDraft(draft.copy(note = it)) }, R.string.device_note, "device_note", DeviceField.NOTE in errors, saving, singleLine = false) }
        item {
            Row(Modifier.padding(top = Spacing.small), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                PrimaryButton(stringResource(R.string.save), onClick = controller::save, enabled = !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("device_save"))
                NeutralButton(stringResource(R.string.cancel), onClick = requestDiscard, enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("device_cancel"))
            }
        }
    }
}

/** Back (ChevronLeft on a 40 dp `surface2` tile) + title, the same subscreen header as SSH. */
@Composable
private fun FormHeader(title: String, onBack: () -> Unit, enabled: Boolean) {
    val colors = NetKitTheme.colors
    val back = stringResource(R.string.cancel)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Box(Modifier.minimumInteractiveComponentSize().size(40.dp).clip(NetKitShape.nested).background(colors.surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onBack).semantics { contentDescription = back },
            contentAlignment = Alignment.Center) {
            Icon(NetKitIcons.ChevronLeft, null, Modifier.size(20.dp), tint = if (enabled) colors.text else colors.dim)
        }
        Text(title, Modifier.weight(1f), style = NetKitType.screenTitle, color = colors.text)
    }
}

@Composable
private fun FormSection(title: Int) {
    Eyebrow(stringResource(title), Modifier.padding(top = Spacing.small))
}

private val FieldText = NetKitType.body.copy(fontSize = 15.sp, lineHeight = 22.sp)
private val FieldMono = NetKitType.addressFor(FieldText)

@Composable
private fun DeviceTextField(
    value: String, change: (String) -> Unit, label: Int, tag: String, error: Boolean,
    saving: Boolean, keyboard: KeyboardType = KeyboardType.Text, singleLine: Boolean = true,
) {
    val colors = NetKitTheme.colors
    OutlinedTextField(
        value = value, onValueChange = change, label = { Text(stringResource(label)) },
        modifier = Modifier.fillMaxWidth().testTag(tag), enabled = !saving,
        singleLine = singleLine, isError = error,
        textStyle = if (tag in MONO_FIELDS) FieldMono else FieldText,
        shape = NetKitShape.nested,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.text, unfocusedTextColor = colors.text, disabledTextColor = colors.dim, errorTextColor = colors.text,
            cursorColor = colors.accent, errorCursorColor = colors.err,
            focusedBorderColor = colors.accent, unfocusedBorderColor = colors.border, disabledBorderColor = colors.divider, errorBorderColor = colors.err,
            focusedLabelColor = colors.accent, unfocusedLabelColor = colors.muted, disabledLabelColor = colors.dim, errorLabelColor = colors.err,
            focusedSupportingTextColor = colors.muted, unfocusedSupportingTextColor = colors.muted, errorSupportingTextColor = colors.err,
        ),
        supportingText = if (error) ({ Text(stringResource(when {
            tag == "device_mac" -> R.string.invalid_mac
            tag == "device_name" && value.length > 100 -> R.string.name_too_long
            tag == "device_note" -> R.string.note_too_long
            (tag == "custom_target" || tag == "probe_host") && value.length > 253 -> R.string.host_too_long
            else -> R.string.invalid_value
        }), style = NetKitType.label) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}

/** Address and port fields use the mono face, like the values they produce on the card. */
private val MONO_FIELDS = setOf("device_mac", "custom_target", "device_port", "probe_host", "probe_port")

@Composable
private fun StatusMessage(message: WolMessage) {
    Box(Modifier.testTag("wol_status")) {
        StatusBanner(stringResource(when (message) {
            WolMessage.LOAD_FAILED -> R.string.load_failed
            WolMessage.SAVE_FAILED -> R.string.save_failed
            WolMessage.SEND_FAILED -> R.string.send_failed
            WolMessage.NO_WIFI -> R.string.no_wifi
            WolMessage.NO_BROADCAST -> R.string.no_broadcast
            WolMessage.SENT -> R.string.sent
        }), if (message == WolMessage.SENT) StatusKind.INFO else StatusKind.ERROR)
    }
}

private fun targetLabel(mode: TargetMode) = when (mode) {
    TargetMode.AUTO_BROADCAST -> R.string.target_auto
    TargetMode.LIMITED_BROADCAST -> R.string.target_limited
    TargetMode.CUSTOM -> R.string.target_custom
}
private fun modeTag(mode: TargetMode) = when (mode) {
    TargetMode.AUTO_BROADCAST -> "auto"
    TargetMode.LIMITED_BROADCAST -> "limited"
    TargetMode.CUSTOM -> "custom"
}
