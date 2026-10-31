@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cz.netkit.app.ssh

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.text.Editable
import android.text.TextWatcher
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.view.WindowManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.IntSize
import com.jcraft.jsch.KeyPair
import cz.netkit.app.R
import cz.netkit.app.BuildConfig
import cz.netkit.app.ui.*
import cz.netkit.app.ui.theme.DarkNetKitColors
import cz.netkit.app.ui.theme.NetKitShape
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import cz.netkit.shared.ssh.*
import cz.netkit.shared.net.localTargetNeedsVpn
import java.util.UUID

/** The terminal is dark in both themes, so its chrome (pills, input strip) always uses the dark tokens. */
private val TerminalChrome = DarkNetKitColors

@Composable
fun SshScreen(viewModel: SshViewModel, modifier: Modifier = Modifier, prefillHost: String? = null, onPrefillConsumed: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editorJson by rememberSaveable { mutableStateOf<String?>(null) }
    val editor = remember(editorJson) { editorJson?.let { Json.decodeFromString<SshProfile>(it) } }
    var keys by rememberSaveable { mutableStateOf(false) }
    var hostKeys by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SshProfile?>(null) }
    var connecting by remember { mutableStateOf<SshProfile?>(null) }
    var reconnecting by remember { mutableStateOf<Pair<String, SshProfile>?>(null) }
    var closing by remember { mutableStateOf<String?>(null) }
    var closingAll by remember { mutableStateOf(false) }
    var recovering by remember { mutableStateOf(false) }
    var sessionMenu by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val colors = NetKitTheme.colors
    var secureScreen by rememberSaveable {
        mutableStateOf(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
    }
    DisposableEffect(context, secureScreen) {
        val activity = context as? Activity
        if (secureScreen) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 33) activity?.setRecentsScreenshotEnabled(!secureScreen)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (android.os.Build.VERSION.SDK_INT >= 33) activity?.setRecentsScreenshotEnabled(true)
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(viewModel::export) }
    val importProfiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::previewProfileImport) }
    val terminalStateHolder = rememberSaveableStateHolder()
    val active = state.sessions.find { it.id == state.selectedSession }
    val inList = editor == null && !keys && !hostKeys
    // The import transaction must not race a profile/key edit or a connection confirmation.
    val dialogOpen = editor != null || keys || hostKeys || connecting != null || deleting != null
    LaunchedEffect(dialogOpen) { viewModel.setProfileImportBlockedByDialog(dialogOpen) }
    // Device detail "Otevřít SSH": open the new-profile form with the host filled in, once.
    LaunchedEffect(prefillHost, state.loaded) {
        val host = prefillHost ?: return@LaunchedEffect
        if (!state.loaded) return@LaunchedEffect
        if (editorJson == null) editorJson = Json.encodeToString(SshProfile(UUID.randomUUID().toString(), host, host, username = ""))
        onPrefillConsumed()
    }
    state.pendingProfileImport?.let { pending ->
        ProfileImportDialog(pending, state, onConfirm = viewModel::confirmProfileImport, onCancel = viewModel::cancelProfileImport)
    }
    BackHandler(enabled = active != null && editor == null && !keys && !hostKeys) { closing = active?.id }
    Column(modifier.imePadding().padding(Spacing.normal), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        if (active == null && inList) ScreenHeader(stringResource(R.string.ssh_title),
            subtitle = if (state.loaded) sshSubtitle(state.data) else null) {
            PrimaryButton(stringResource(R.string.ssh_add), small = true, icon = NetKitIcons.Plus, enabled = state.loaded && !state.busy,
                onClick = { editorJson = Json.encodeToString(SshProfile(UUID.randomUUID().toString(), "", "", username = "")) },
                modifier = Modifier.testTag("ssh_add"))
            Box {
                IconButton(onClick = { actionsMenu = true }, modifier = Modifier.testTag("ssh_more")) {
                    Icon(NetKitIcons.More, contentDescription = stringResource(R.string.ssh_more_actions), tint = colors.muted)
                }
                NetKitMenu(expanded = actionsMenu, onDismiss = { actionsMenu = false }) {
                    DropdownMenuItem(text = { MenuText(stringResource(R.string.ssh_host_keys)) }, enabled = state.loaded,
                        leadingIcon = { Icon(NetKitIcons.Router, null, Modifier.size(20.dp)) },
                        onClick = { actionsMenu = false; hostKeys = true }, modifier = Modifier.testTag("ssh_host_keys"))
                    DropdownMenuItem(text = { MenuText(stringResource(R.string.ssh_export)) }, enabled = state.loaded && !state.busy,
                        leadingIcon = { Icon(NetKitIcons.Download, null, Modifier.size(20.dp)) },
                        onClick = { actionsMenu = false; export.launch("netkit-ssh.json") }, modifier = Modifier.testTag("ssh_export"))
                    DropdownMenuItem(text = { MenuText(stringResource(R.string.ssh_import_profiles)) },
                        enabled = state.loaded && !state.busy && !state.profileImportBusy,
                        leadingIcon = { Icon(NetKitIcons.Download, null, Modifier.size(20.dp)) },
                        onClick = {
                            actionsMenu = false
                            try { importProfiles.launch(arrayOf("application/json", "text/plain")) }
                            catch (_: android.content.ActivityNotFoundException) {
                                android.widget.Toast.makeText(context, R.string.ssh_share_unavailable, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }, modifier = Modifier.testTag("ssh_import_profiles"))
                }
            }
        }
        if (state.busy) NetKitProgress()
        if (state.error) BannerWithAction(stringResource(sshFailureResource(state.failure)), StatusKind.ERROR,
            stringResource(R.string.ssh_ok), viewModel::clearError)
        if (state.exported) StatusBanner(stringResource(R.string.ssh_exported))
        if (state.profileImportBusy) NetKitProgress()
        state.profileImportFailure?.let { failure ->
            BannerWithAction(stringResource(profileImportErrorResource(failure)), StatusKind.ERROR,
                stringResource(R.string.ssh_ok), viewModel::cancelProfileImport)
        }
        when {
            editor != null -> ProfileForm(editor!!, state, viewModel, onClose = { editorJson = null })
            keys -> KeyManager(state, viewModel, onClose = { keys = false })
            hostKeys -> HostKeys(state, viewModel, onClose = { hostKeys = false })
            else -> {
                if (state.sessions.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalAlignment = Alignment.CenterVertically) {
                        SessionTab(stringResource(R.string.ssh_profiles), selected = active == null, tone = null) { viewModel.selectSession(null) }
                        state.sessions.forEach { session ->
                            SessionTab(session.name, selected = active?.id == session.id, tone = sessionTone(session)) { viewModel.selectSession(session.id) }
                        }
                    }
                    if (active != null) Box {
                        IconButton(onClick = { sessionMenu = true }, modifier = Modifier.testTag("ssh_options")) {
                            Icon(NetKitIcons.More, contentDescription = stringResource(R.string.ssh_session_options), tint = colors.muted)
                        }
                        NetKitMenu(expanded = sessionMenu, onDismiss = { sessionMenu = false }) {
                            DropdownMenuItem(text = { MenuText(stringResource(R.string.ssh_secure_screen)) },
                                onClick = { secureScreen = !secureScreen; sessionMenu = false },
                                trailingIcon = { Switch(checked = secureScreen, onCheckedChange = null, colors = netKitSwitchColors()) },
                                modifier = Modifier.testTag("ssh_secure_screen"))
                            DropdownMenuItem(text = { MenuText(stringResource(R.string.ssh_close_all)) }, onClick = { closingAll = true; sessionMenu = false })
                        }
                    }
                }
                if (active != null) {
                    SessionStatusRow(active, onReconnect = { viewModel.reconnectProfile(active.id)?.let { reconnecting = active.id to it } },
                        onClose = { closing = active.id })
                    active.failure?.let { StatusBanner(stringResource(sshFailureResource(it)), StatusKind.ERROR) }
                    terminalStateHolder.SaveableStateProvider(active.id) { Terminal(active, viewModel, Modifier.weight(1f)) }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.normal)) {
                        if (state.storeFailed) item {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                StatusBanner(stringResource(R.string.ssh_storage_failed), StatusKind.ERROR)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                                    PrimaryButton(stringResource(R.string.ssh_retry), onClick = viewModel::reload, small = true)
                                    if (state.storeRecoverable) NeutralButton(stringResource(R.string.ssh_recover), onClick = { recovering = true }, small = true)
                                }
                            }
                        }
                        state.archivePath?.let { path -> item { Text(stringResource(R.string.ssh_archive_path, path), style = NetKitType.label, color = colors.muted) } }
                        if (!state.loaded && !state.storeFailed) item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                        }
                        if (state.loaded && state.data.profiles.isEmpty()) item { Panel { EmptyState(stringResource(R.string.ssh_empty), NetKitIcons.Terminal) } }
                        if (state.data.profiles.isNotEmpty()) item(key = "profiles") {
                            SectionCard(title = stringResource(R.string.ssh_profiles)) {
                                Column {
                                    val canConnect = !state.busy && state.sessions.count { !it.ended } < 4
                                    val profiles = state.data.profiles
                                    profiles.forEachIndexed { index, profile ->
                                        key(profile.id) {
                                            ProfileRow(profile,
                                                live = state.sessions.any { it.connected && !it.ended && viewModel.reconnectProfile(it.id)?.id == profile.id },
                                                canConnect = canConnect, busy = state.busy, first = index == 0, last = index == profiles.lastIndex,
                                                onConnect = { connecting = profile },
                                                onEdit = { editorJson = Json.encodeToString(profile) },
                                                onDelete = { deleting = profile })
                                        }
                                    }
                                }
                            }
                        }
                        if (state.loaded) item(key = "keys") {
                            // The whole card is the tap target; ListRow only lays out icon, texts and chevron.
                            Panel(Modifier.testTag("ssh_keys"), padding = 0.dp, onClick = { keys = true }) {
                                ListRow(stringResource(R.string.ssh_keys), keyCardSubtitle(state.data.keys), NetKitIcons.Key,
                                    modifier = Modifier.padding(horizontal = Spacing.normal, vertical = Spacing.tiny)) {
                                    Icon(NetKitIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.dim)
                                }
                            }
                        }
                        item(key = "privacy") {
                            Panel(padding = Spacing.small) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(NetKitShape.nested).testTag("ssh_secure_screen")
                                    .toggleable(secureScreen, role = Role.Switch, onValueChange = { secureScreen = it })
                                    .padding(horizontal = Spacing.small), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                                    Text(stringResource(R.string.ssh_secure_screen), Modifier.weight(1f), style = NetKitType.body, color = colors.text)
                                    Switch(checked = secureScreen, onCheckedChange = null, colors = netKitSwitchColors())
                                }
                                if (state.sessions.isNotEmpty()) TextButton(onClick = { closingAll = true }, shape = NetKitShape.buttonSmall,
                                    colors = ButtonDefaults.textButtonColors(contentColor = colors.err)) {
                                    Text(stringResource(R.string.ssh_close_all), style = NetKitType.body.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                        }
                        item(key = "info") {
                            InfoNote(stringResource(R.string.ssh_connection_summary), listOf(stringResource(R.string.ssh_network_notice),
                                stringResource(R.string.ssh_ime_privacy), stringResource(R.string.ssh_terminal_limits)).joinToString("\n\n"))
                        }
                    }
                }
            }
        }
    }
    if (recovering) ConfirmDialog(stringResource(R.string.ssh_recover_confirm), onCancel = { recovering = false }) {
        viewModel.recoverStore(); recovering = false
    }
    if (closingAll) ConfirmDialog(stringResource(R.string.ssh_close_all_confirm), onCancel = { closingAll = false }) {
        viewModel.closeAllSessions(); closingAll = false
    }
    deleting?.let { profile -> ConfirmDialog(stringResource(R.string.ssh_delete_profile, profile.name), onCancel = { deleting = null }) { viewModel.deleteProfile(profile.id); deleting = null } }
    closing?.let { id -> ConfirmDialog(stringResource(R.string.ssh_close_confirm), onCancel = { closing = null }) { viewModel.closeSession(id); closing = null } }
    // Only a hint: a VPN outside the Android API (split tunnel) may still reach the address, so connecting stays allowed.
    @Composable fun localWarning(profile: SshProfile): String? =
        if (localTargetNeedsVpn(profile.host, state.activeKind, state.activeViaVpn)) stringResource(R.string.ssh_local_target_warning) else null
    connecting?.let { profile -> ConfirmDialog(stringResource(R.string.ssh_connect_confirm, profile.endpoint, profile.startupCommand.ifBlank { stringResource(R.string.ssh_none) }),
        onCancel = { connecting = null }, warning = localWarning(profile)) { viewModel.connect(profile); connecting = null } }
    reconnecting?.let { (id, profile) -> ConfirmDialog(
        stringResource(R.string.ssh_connect_confirm, profile.endpoint, profile.startupCommand.ifBlank { stringResource(R.string.ssh_none) }),
        onCancel = { reconnecting = null }, warning = localWarning(profile),
    ) { viewModel.reconnect(id); reconnecting = null } }
    state.hostChallenge?.let { challenge -> HostDialog(challenge, viewModel) }
    state.authChallenge?.let { challenge -> key(challenge) { AuthenticationDialog(challenge, viewModel) } }
}

/** "3 profily · klíč Ed25519 v Keystore": profile count and what the phone holds for key login. */
@Composable
private fun sshSubtitle(data: SshData): String {
    val profiles = pluralStringResource(R.plurals.ssh_subtitle_profiles, data.profiles.size, data.profiles.size)
    val keys = when (data.keys.size) {
        0 -> stringResource(R.string.ssh_subtitle_no_key)
        1 -> stringResource(R.string.ssh_subtitle_key, keyAlgorithm(data.keys.single().publicKey))
        else -> pluralStringResource(R.plurals.ssh_subtitle_keys, data.keys.size, data.keys.size)
    }
    return "$profiles · $keys"
}

/** Algorithm label from the OpenSSH public key prefix (`ssh-ed25519 AAAA…`). */
private fun keyAlgorithm(publicKey: String): String = when (publicKey.substringBefore(' ')) {
    "ssh-ed25519" -> "Ed25519"
    "ssh-rsa" -> "RSA"
    "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521" -> "ECDSA"
    else -> publicKey.substringBefore(' ')
}

@Composable
private fun keyCardSubtitle(keys: List<SshKeyInfo>): String =
    if (keys.isEmpty()) stringResource(R.string.ssh_key_card_empty)
    else stringResource(R.string.ssh_key_card_subtitle, keys.joinToString(", ") { it.name })

/**
 * Session state (dot + text) with reconnect/close on the right. A flow row: at 360 dp and large
 * font the buttons drop under the state instead of squeezing the text into a narrow column.
 */
@Composable
private fun SessionStatusRow(session: SshSessionState, onReconnect: () -> Unit, onClose: () -> Unit) {
    val colors = NetKitTheme.colors
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(Spacing.small),
        itemVerticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.heightIn(min = 36.dp).padding(end = Spacing.small), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.tone(sessionTone(session)).foreground))
            Text(stringResource(when {
                session.networkLost && !session.ended -> R.string.ssh_network_lost
                session.failed -> R.string.ssh_failed
                session.ended -> R.string.ssh_ended
                session.connected -> R.string.ssh_connected
                else -> R.string.ssh_connecting
            }), Modifier.testTag("ssh_status"), style = NetKitType.body.copy(fontWeight = FontWeight.SemiBold), color = colors.text)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
            if (session.ended || session.networkLost) TonalButton(stringResource(R.string.ssh_reconnect), onClick = onReconnect, small = true)
            NeutralButton(stringResource(R.string.ssh_close), onClick = onClose, small = true, modifier = Modifier.testTag("ssh_close"))
        }
    }
}

/** State dot of a session: live OK, lost network WARN, ended or failed ERR, handshake in progress INFO. */
private fun sessionTone(session: SshSessionState): Tone = when {
    session.networkLost && !session.ended -> Tone.WARN
    session.failed || session.ended -> Tone.ERR
    session.connected -> Tone.OK
    else -> Tone.INFO
}

// --- Token-styled building blocks shared by the SSH screen and its dialogs -----------------------

/** Indeterminate progress bar on the `surface3` track. */
@Composable
internal fun NetKitProgress() {
    LinearProgressIndicator(Modifier.fillMaxWidth(), color = NetKitTheme.colors.accent, trackColor = NetKitTheme.colors.surface3)
}

/** Banner with one small neutral action under it (dismiss, retry). */
@Composable
private fun BannerWithAction(text: String, kind: StatusKind, action: String, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        StatusBanner(text, kind)
        NeutralButton(action, onClick = onAction, small = true)
    }
}

/** Popup menu on `surface2` with the card radius; tags of its items are exposed as resource ids. */
@Composable
internal fun NetKitMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = NetKitShape.nested,
        containerColor = NetKitTheme.colors.surface2, tonalElevation = 0.dp,
        modifier = Modifier.semantics { testTagsAsResourceId = BuildConfig.DEBUG }, content = content)
}

@Composable
internal fun MenuText(text: String) {
    Text(text, style = NetKitType.body, color = NetKitTheme.colors.text)
}

@Composable
internal fun netKitSwitchColors() = NetKitTheme.colors.let { colors ->
    SwitchDefaults.colors(checkedThumbColor = colors.accentInk, checkedTrackColor = colors.accent, checkedBorderColor = colors.accent,
        uncheckedThumbColor = colors.muted, uncheckedTrackColor = colors.surface3, uncheckedBorderColor = colors.border)
}

@Composable
internal fun netKitRadioColors() = NetKitTheme.colors.let { colors ->
    RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.muted, disabledSelectedColor = colors.dim,
        disabledUnselectedColor = colors.dim)
}

@Composable
internal fun netKitCheckboxColors() = NetKitTheme.colors.let { colors ->
    CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.muted, checkmarkColor = colors.accentInk,
        disabledCheckedColor = colors.dim, disabledUncheckedColor = colors.dim)
}

/** Outlined text field colours: hairline `border`, accent focus, `err` for validation. */
@Composable
internal fun netKitFieldColors() = NetKitTheme.colors.let { colors ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.text, unfocusedTextColor = colors.text, disabledTextColor = colors.dim, errorTextColor = colors.text,
        cursorColor = colors.accent, errorCursorColor = colors.err,
        focusedBorderColor = colors.accent, unfocusedBorderColor = colors.border, disabledBorderColor = colors.divider, errorBorderColor = colors.err,
        focusedLabelColor = colors.accent, unfocusedLabelColor = colors.muted, disabledLabelColor = colors.dim, errorLabelColor = colors.err,
        focusedSupportingTextColor = colors.muted, unfocusedSupportingTextColor = colors.muted, errorSupportingTextColor = colors.err,
        focusedPlaceholderColor = colors.dim, unfocusedPlaceholderColor = colors.dim,
    )
}

/** Field text: body 15 for words, mono 0.92× for hosts, ports and commands. */
private val FieldText = NetKitType.body.copy(fontSize = 15.sp, lineHeight = 22.sp)
private val FieldMono = NetKitType.addressFor(FieldText)

/** Selectable radio row (48 dp, radius 12) used by forms and the import dialog. */
@Composable
internal fun ChoiceRow(selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(NetKitShape.nested)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(horizontal = Spacing.tiny),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, colors = netKitRadioColors())
        Text(label, Modifier.weight(1f), style = NetKitType.body, color = if (enabled) NetKitTheme.colors.text else NetKitTheme.colors.dim)
    }
}

@Composable
private fun CheckRow(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(NetKitShape.nested)
        .toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(horizontal = Spacing.tiny),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Checkbox(checked, onCheckedChange = null, colors = netKitCheckboxColors())
        Text(label, Modifier.weight(1f), style = NetKitType.body, color = NetKitTheme.colors.text)
    }
}

/**
 * Alert on `surface` with the card radius, no tonal tint. Every dialog of the SSH screen goes
 * through it, so tags inside stay exposed as resource ids for the instrumentation.
 */
@Composable
internal fun NetKitAlert(
    onDismiss: () -> Unit, confirm: @Composable () -> Unit, dismiss: (@Composable () -> Unit)? = null,
    title: String? = null, titleColor: Color = NetKitTheme.colors.text, text: @Composable () -> Unit,
) {
    val colors = NetKitTheme.colors
    AlertDialog(onDismissRequest = onDismiss, confirmButton = confirm, dismissButton = dismiss,
        modifier = Modifier.semantics { testTagsAsResourceId = BuildConfig.DEBUG },
        title = title?.let { { Text(it, style = DialogTitle, color = titleColor) } },
        text = text, shape = NetKitShape.card, containerColor = colors.surface, tonalElevation = 0.dp,
        titleContentColor = colors.text, textContentColor = colors.text)
}

internal val DialogTitle = NetKitType.screenTitle.copy(fontSize = 20.sp, lineHeight = 26.sp)

@Composable
private fun SessionTab(label: String, selected: Boolean, tone: Tone?, onClick: () -> Unit) {
    val colors = NetKitTheme.colors
    Box(Modifier.heightIn(min = 48.dp).clip(NetKitShape.pill).selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Row(Modifier.clip(NetKitShape.pill).background(if (selected) colors.accentSoft else colors.surface2)
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tone?.let { Box(Modifier.size(6.dp).clip(CircleShape).background(colors.tone(it).foreground)) }
            Text(label, Modifier.widthIn(max = 160.dp), style = NetKitType.tag.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = if (selected) colors.accent else colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Two or three letter monogram for the leading tile: initials of words, or the first two characters. */
private fun profileMonogram(name: String): String {
    val initials = name.split(' ', '-', '_', '.', '/', ':', '@').mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
    val letters = if (initials.size >= 2) initials.take(3) else name.filter { it.isLetterOrDigit() }.take(2).toList()
    return letters.joinToString("").uppercase().ifEmpty { "?" }
}

/** `user@host` with ` :port` only when it is not 22; IPv6 literals get brackets so the port stays unambiguous. */
private fun profileAddress(profile: SshProfile): String {
    val host = if (profile.host.contains(':')) "[${profile.host}]" else profile.host
    return "${profile.username}@$host" + if (profile.port != 22) " :${profile.port}" else ""
}

/**
 * Profile row: 44 dp monogram tile | name and `user@host :port` | tonal "Připojit". Tapping the
 * tile or the texts opens edit/delete; the connect button keeps the `ssh_connect_<id>` tag.
 */
@Composable
private fun ProfileRow(profile: SshProfile, live: Boolean, canConnect: Boolean, busy: Boolean, first: Boolean, last: Boolean,
    onConnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = NetKitTheme.colors
    val divider = colors.divider
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth()
        .then(if (first) Modifier else Modifier.drawBehind { drawLine(divider, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) })
        .padding(top = if (first) 0.dp else Spacing.medium, bottom = if (last) 0.dp else Spacing.medium),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Box(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().clip(NetKitShape.nested)
                .clickable(onClickLabel = stringResource(R.string.ssh_profile_actions, profile.name), role = Role.Button) { menu = true },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                Box(Modifier.size(44.dp).clip(NetKitShape.nested).background(colors.surface2), contentAlignment = Alignment.Center) {
                    Text(profileMonogram(profile.name), style = NetKitType.panelTitle.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
                        color = colors.accent, maxLines = 1, softWrap = false)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(profile.name, style = NetKitType.panelTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(profileAddress(profile), style = NetKitType.addressFor(NetKitType.label), color = colors.muted)
                    if (live) Tag(stringResource(R.string.ssh_chip_live), Tone.OK, dot = true, modifier = Modifier.padding(top = Spacing.tiny))
                }
            }
            NetKitMenu(expanded = menu, onDismiss = { menu = false }) {
                DropdownMenuItem(text = { MenuText(stringResource(R.string.ssh_edit)) }, enabled = !busy, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text(stringResource(R.string.ssh_delete), style = NetKitType.body, color = colors.err) },
                    enabled = !busy, onClick = { menu = false; onDelete() })
            }
        }
        TonalButton(stringResource(R.string.ssh_connect), onClick = onConnect, enabled = canConnect, small = true,
            modifier = Modifier.testTag("ssh_connect_${profile.id}"))
    }
}

@Composable
private fun ConfirmDialog(message: String, onCancel: () -> Unit, warning: String? = null, onConfirm: () -> Unit) {
    NetKitAlert(onDismiss = onCancel, text = {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            if (warning != null) Box(Modifier.testTag("ssh_local_warning")) { StatusBanner(warning, StatusKind.WARNING) }
            Text(message, style = NetKitType.body)
        }
    },
        confirm = { PrimaryButton(stringResource(R.string.ssh_confirm), onClick = onConfirm, small = true, modifier = Modifier.testTag("ssh_confirm")) },
        dismiss = { NeutralButton(stringResource(R.string.ssh_cancel), onClick = onCancel, small = true) })
}

/** Back (ChevronLeft on a 40 dp `surface2` tile) + title, for the profile form, keys and trusted servers. */
@Composable
private fun SubscreenHeader(title: String, onBack: () -> Unit, enabled: Boolean = true) {
    val colors = NetKitTheme.colors
    val back = stringResource(R.string.ssh_back)
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
private fun Hint(text: String) {
    Text(text, style = NetKitType.label, color = NetKitTheme.colors.muted)
}

@Composable
private fun ProfileForm(original: SshProfile, state: SshUiState, viewModel: SshViewModel, onClose: () -> Unit) {
    var name by rememberSaveable(original.id) { mutableStateOf(original.name) }; var host by rememberSaveable(original.id) { mutableStateOf(original.host) }
    var port by rememberSaveable(original.id) { mutableStateOf(original.port.toString()) }; var user by rememberSaveable(original.id) { mutableStateOf(original.username) }
    var auth by rememberSaveable(original.id) { mutableStateOf(original.authentication) }; var keyRef by rememberSaveable(original.id) { mutableStateOf(original.keyRef) }
    var command by rememberSaveable(original.id) { mutableStateOf(original.startupCommand) }; var keepalive by rememberSaveable(original.id) { mutableStateOf(original.keepaliveSeconds.toString()) }
    // Deliberately not rememberSaveable: credentials must never reach savedInstanceState.
    var password by remember { mutableStateOf("") }; var savePassword by rememberSaveable { mutableStateOf(original.secretRef != null) }
    var discard by remember { mutableStateOf(false) }
    val draft = original.copy(name = name, host = host.trim(), port = port.toIntOrNull() ?: 0, username = user.trim(), authentication = auth, keyRef = if (auth == SshAuthentication.KEY) keyRef else null, startupCommand = command, keepaliveSeconds = keepalive.toIntOrNull() ?: -1)
    BackHandler { discard = true }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        SubscreenHeader(stringResource(R.string.ssh_profile), onBack = { discard = true }, enabled = !state.busy)
        SshField(name, { name = it.take(100) }, R.string.ssh_name)
        SshField(host, { host = it.take(253) }, R.string.ssh_host, mono = true)
        SshField(port, { port = it.take(5) }, R.string.ssh_port, number = true, mono = true)
        SshField(user, { user = it.take(128) }, R.string.ssh_username)
        Column {
            SshAuthentication.entries.forEach { type ->
                ChoiceRow(auth == type, stringResource(when (type) { SshAuthentication.PASSWORD -> R.string.ssh_password; SshAuthentication.KEY -> R.string.ssh_key; SshAuthentication.INTERACTIVE -> R.string.ssh_interactive }),
                    onClick = { auth = type })
            }
        }
        if (auth == SshAuthentication.PASSWORD) {
            OutlinedTextField(password, { password = it.take(4096) }, label = { Text(stringResource(R.string.ssh_password_optional)) }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, textStyle = FieldText,
                shape = NetKitShape.nested, colors = netKitFieldColors(), modifier = Modifier.fillMaxWidth())
            CheckRow(savePassword, stringResource(R.string.ssh_remember_password)) { savePassword = it }
            Hint(stringResource(R.string.ssh_password_hint))
        }
        if (auth == SshAuthentication.KEY) {
            if (state.data.keys.isEmpty()) Hint(stringResource(R.string.ssh_add_key_first))
            Column { state.data.keys.forEach { key -> ChoiceRow(keyRef == key.id, key.name, onClick = { keyRef = key.id }) } }
        }
        SshField(command, { command = it.take(4096) }, R.string.ssh_startup_command, mono = true)
        SshField(keepalive, { keepalive = it.take(3) }, R.string.ssh_keepalive, number = true)
        Hint(stringResource(R.string.ssh_profile_validation))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            PrimaryButton(stringResource(R.string.ssh_save), enabled = draft.valid() && !state.busy && (auth != SshAuthentication.KEY || state.data.keys.any { it.id == keyRef }),
                onClick = { viewModel.saveProfile(draft, password, savePassword) { password = ""; onClose() } })
            NeutralButton(stringResource(R.string.ssh_back), enabled = !state.busy, onClick = { discard = true })
        }
    }
    if (discard) ConfirmDialog(stringResource(R.string.ssh_discard), onCancel = { discard = false }) { password = ""; onClose() }
}

@Composable
private fun SshField(value: String, change: (String) -> Unit, label: Int, number: Boolean = false, mono: Boolean = false) {
    OutlinedTextField(value, change, label = { Text(stringResource(label)) }, singleLine = true,
        textStyle = if (mono) FieldMono else FieldText, shape = NetKitShape.nested, colors = netKitFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun KeyManager(state: SshUiState, viewModel: SshViewModel, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }; var phrase by remember { mutableStateOf("") }; var type by rememberSaveable { mutableIntStateOf(KeyPair.ED25519) }
    var uri by rememberSaveable { mutableStateOf<Uri?>(null) }; var deleting by remember { mutableStateOf<SshKeyInfo?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri = it }
    val context = LocalContext.current
    val colors = NetKitTheme.colors
    var discard by remember { mutableStateOf(false) }
    val close: () -> Unit = { if (name.isNotEmpty() || uri != null || phrase.isNotEmpty()) discard = true else onClose() }
    BackHandler(onBack = close)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        item { SubscreenHeader(stringResource(R.string.ssh_keys), onBack = close) }
        item {
            val protection = state.protection
            StatusBanner(stringResource(when (protection) { KeyProtection.STRONGBOX -> R.string.ssh_strongbox; KeyProtection.HARDWARE -> R.string.ssh_hardware; KeyProtection.SOFTWARE -> R.string.ssh_software; null -> R.string.ssh_protection_unknown }),
                if (protection == KeyProtection.STRONGBOX || protection == KeyProtection.HARDWARE) StatusKind.INFO else StatusKind.WARNING)
        }
        item { SshField(name, { name = it.take(100) }, R.string.ssh_key_name) }
        item {
            val types = listOf(KeyPair.ED25519 to R.string.ssh_ed25519, KeyPair.RSA to R.string.ssh_rsa, KeyPair.ECDSA to R.string.ssh_ecdsa)
            SegmentedTabs(types.map { stringResource(it.second) }, types.indexOfFirst { it.first == type }, onSelect = { type = types[it].first })
        }
        item { PrimaryButton(stringResource(R.string.ssh_generate), enabled = name.isNotBlank() && !state.busy, onClick = { viewModel.generateKey(name, type) }) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                NeutralButton(stringResource(R.string.ssh_choose_key), enabled = !state.busy, onClick = { picker.launch(arrayOf("*/*")) })
                if (uri != null) Tag(stringResource(R.string.ssh_file_selected), Tone.ACCENT, dot = true)
            }
        }
        item {
            OutlinedTextField(phrase, { phrase = it.take(4096) }, label = { Text(stringResource(R.string.ssh_passphrase)) }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, textStyle = FieldText,
                shape = NetKitShape.nested, colors = netKitFieldColors(), modifier = Modifier.fillMaxWidth())
        }
        item { TonalButton(stringResource(R.string.ssh_import), enabled = uri != null && name.isNotBlank() && !state.busy, onClick = { uri?.let { viewModel.importKey(it, name, phrase) }; phrase = ""; uri = null }) }
        item { Hint(stringResource(R.string.ssh_key_policy)) }
        if (state.data.keys.isEmpty()) item { Panel { EmptyState(stringResource(R.string.ssh_add_key_first), NetKitIcons.Key) } }
        items(state.data.keys, key = { it.id }) { key -> Panel {
            Text(key.name, style = NetKitType.panelTitle, color = colors.text)
            SelectionContainer { Text(key.publicKey, style = NetKitType.addressFor(NetKitType.label), color = colors.muted) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TonalButton(stringResource(R.string.ssh_copy_public), small = true, onClick = { copyText(context, key.publicKey) })
                // Public half only: the PC script `install-pubkey` distributes it to the aliases.
                NeutralButton(stringResource(R.string.ssh_share_public), small = true, onClick = { sharePublicKey(context, key.name, key.publicKey) })
                DangerTextButton(stringResource(R.string.ssh_delete), enabled = !state.busy && state.data.profiles.none { it.keyRef == key.id }) { deleting = key }
            }
        } }
    }
    deleting?.let { key -> ConfirmDialog(stringResource(R.string.ssh_delete_key, key.name), onCancel = { deleting = null }) { viewModel.deleteKey(key.id); deleting = null } }
    if (discard) ConfirmDialog(stringResource(R.string.ssh_discard), onCancel = { discard = false }) { phrase = ""; onClose() }
}

/** Destructive action without a filled container: `err` text, small-button radius. */
@Composable
private fun DangerTextButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, shape = NetKitShape.buttonSmall, modifier = Modifier.heightIn(min = 36.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = NetKitTheme.colors.err, disabledContentColor = NetKitTheme.colors.dim)) {
        Text(text, style = NetKitType.body.copy(fontWeight = FontWeight.Bold))
    }
}

private fun sharePublicKey(context: Context, name: String, publicKey: String) {
    try {
        context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "$name.pub")
            putExtra(android.content.Intent.EXTRA_TEXT, publicKey + "\n")
        }, context.getString(R.string.ssh_share_public)))
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, R.string.ssh_share_unavailable, android.widget.Toast.LENGTH_LONG).show()
    }
}

private fun copyText(context: Context, text: String, sensitive: Boolean = false) {
    // Clipboard is Binder IPC. A full scrollback can exceed its shared transaction buffer.
    if (text.length > 65_536) {
        android.widget.Toast.makeText(context, R.string.ssh_clipboard_too_large, android.widget.Toast.LENGTH_LONG).show()
        return
    }
    try {
        val clip = ClipData.newPlainText(context.getString(R.string.ssh_clipboard), text)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(clip)
    } catch (_: RuntimeException) {
        android.widget.Toast.makeText(context, R.string.ssh_clipboard_failed, android.widget.Toast.LENGTH_LONG).show()
    }
}

/** Endpoint, algorithm and fingerprint as labelled monospace values; selectable for manual comparison. */
@Composable
private fun HostKeyDetails(endpoint: String, algorithm: String, fingerprint: String, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            LabeledValue(stringResource(R.string.ssh_fp_server), endpoint, monospace = true)
            LabeledValue(stringResource(R.string.ssh_fp_algorithm), algorithm, monospace = true)
            LabeledValue(stringResource(R.string.ssh_fp_fingerprint), fingerprint, monospace = true)
        }
    }
}

@Composable
private fun HostKeys(state: SshUiState, viewModel: SshViewModel, onClose: () -> Unit) {
    var deleting by remember { mutableStateOf<TrustedHostKey?>(null) }
    BackHandler(onBack = onClose)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        item { SubscreenHeader(stringResource(R.string.ssh_host_keys), onBack = onClose) }
        if (state.data.hostKeys.isEmpty()) item { Panel { EmptyState(stringResource(R.string.ssh_no_trusted_hosts), NetKitIcons.Router) } }
        items(state.data.hostKeys, key = { it.endpoint }) { host -> Panel {
            HostKeyDetails(host.endpoint, host.algorithm, host.fingerprint)
            DangerTextButton(stringResource(R.string.ssh_forget_host), enabled = !state.busy) { deleting = host }
        } }
    }
    deleting?.let { host -> ConfirmDialog(stringResource(R.string.ssh_forget_warning, host.endpoint) + state.data.rejectedHostKeys.find { it.endpoint == host.endpoint }?.let { "\n" + stringResource(R.string.ssh_rejected_fingerprint, it.fingerprint) }.orEmpty(), onCancel = { deleting = null }) { viewModel.forgetHost(host.endpoint); deleting = null } }
}

@Composable
private fun HostDialog(challenge: HostChallenge, viewModel: SshViewModel) {
    val context = LocalContext.current
    val colors = NetKitTheme.colors
    val changed = challenge.previous != null
    NetKitAlert(onDismiss = { viewModel.confirmHost(challenge.requestId, false) },
        title = stringResource(if (!changed) R.string.ssh_unknown_host else R.string.ssh_changed_host), titleColor = if (changed) colors.err else colors.text,
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
            if (!changed) Text(stringResource(R.string.ssh_verify_fingerprint), style = NetKitType.body, color = colors.text)
            else StatusBanner(stringResource(R.string.ssh_changed_warning), StatusKind.ERROR)
            HostKeyDetails(challenge.offered.endpoint, challenge.offered.algorithm, challenge.offered.fingerprint,
                Modifier.fillMaxWidth().clip(NetKitShape.nested).background(colors.surface2).padding(Spacing.medium))
            NeutralButton(stringResource(R.string.ssh_copy_fingerprint), small = true, onClick = { copyText(context, challenge.offered.fingerprint) })
            challenge.previous?.let { Text(stringResource(R.string.ssh_previous_fingerprint, it.fingerprint), style = NetKitType.addressFor(NetKitType.label), color = colors.muted) }
            challenge.lastRejected?.let { Text(stringResource(R.string.ssh_rejected_fingerprint, it.fingerprint), style = NetKitType.addressFor(NetKitType.label), color = colors.err) }
        } },
        confirm = { if (!changed) PrimaryButton(stringResource(R.string.ssh_trust), small = true, onClick = { viewModel.confirmHost(challenge.requestId, true) }, modifier = Modifier.testTag("ssh_trust")) },
        dismiss = { NeutralButton(stringResource(R.string.ssh_reject), small = true, onClick = { viewModel.confirmHost(challenge.requestId, false) }) })
}

@Composable
private fun AuthenticationDialog(challenge: AuthChallenge, viewModel: SshViewModel) {
    var answers by remember { mutableStateOf(List(challenge.prompts.size) { "" }) }
    val colors = NetKitTheme.colors
    NetKitAlert(onDismiss = { viewModel.answerAuthentication(challenge.requestId, null) }, title = stringResource(R.string.ssh_authenticate),
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(stringResource(R.string.ssh_auth_identity, challenge.trustedIdentity), style = NetKitType.addressFor(NetKitType.body), color = colors.text)
            Text(stringResource(R.string.ssh_server_prompts), style = NetKitType.label, color = colors.dim)
            Text(challenge.name, style = NetKitType.body, color = colors.text); Text(challenge.instruction, style = NetKitType.body, color = colors.muted)
            challenge.prompts.forEachIndexed { index, prompt -> OutlinedTextField(answers[index], { value -> answers = answers.mapIndexed { i, old -> if (i == index) value.take(4096) else old } },
                label = { Text(if (prompt == "Password") stringResource(R.string.ssh_password) else prompt) }, singleLine = true,
                visualTransformation = if (challenge.echo[index]) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = if (challenge.echo[index]) KeyboardType.Text else KeyboardType.Password),
                textStyle = FieldText, shape = NetKitShape.nested, colors = netKitFieldColors(), modifier = Modifier.fillMaxWidth()) }
        } },
        confirm = { PrimaryButton(stringResource(R.string.ssh_send), small = true, onClick = { viewModel.answerAuthentication(challenge.requestId, answers); answers = List(answers.size) { "" } }) },
        dismiss = { NeutralButton(stringResource(R.string.ssh_cancel), small = true, onClick = { viewModel.answerAuthentication(challenge.requestId, null); answers = List(answers.size) { "" } }) })
}

/**
 * Extra-key pill on the terminal background: 48 dp touch height, 36 dp visual pill (tag radius),
 * equal [width]. [active] != null makes it a toggle (Ctrl/Alt/more keys) on the accent soft fill.
 */
@Composable
private fun KeyPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    active: Boolean? = null, icon: ImageVector? = null, width: Dp = 52.dp) {
    val chrome = TerminalChrome
    val on = active == true
    val shape = NetKitShape.tag
    val interaction = if (active != null) Modifier.toggleable(on, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
        else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    Box(modifier.heightIn(min = 48.dp).clip(shape).then(interaction)
        .then(if (icon != null) Modifier.semantics { contentDescription = label } else Modifier), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(min = width).height(36.dp).clip(shape).background(if (on) chrome.accentSoft else chrome.surface2)
            .padding(horizontal = Spacing.small), contentAlignment = Alignment.Center) {
            val tint = when { !enabled -> chrome.dim; on -> chrome.accent; else -> chrome.text }
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = tint)
            else Text(label, style = NetKitType.tag.copy(fontSize = 13.sp, lineHeight = 18.sp), color = tint, maxLines = 1)
        }
    }
}

/** Terminal font steps (docs/design.md §3: 12.5/20, adjustable 8–24); the line height keeps the 1.6 ratio. */
private const val TERMINAL_FONT_DEFAULT = 12.5f
private const val TERMINAL_LINE_RATIO = 1.6f

@Composable
private fun Terminal(session: SshSessionState, viewModel: SshViewModel, modifier: Modifier) {
    // Terminal input/paste must not survive in savedInstanceState.
    var input by remember(session.id) { mutableStateOf("") }
    var paste by remember(session.id) { mutableStateOf<String?>(null) }
    var fallback by rememberSaveable(session.id) { mutableStateOf(false) }
    var moreKeys by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var fontSize by rememberSaveable { mutableFloatStateOf(TERMINAL_FONT_DEFAULT) }
    var control by remember { mutableStateOf(false) }; var alt by remember { mutableStateOf(false) }
    val context = LocalContext.current; val list = rememberLazyListState(); val measurer = rememberTextMeasurer()
    val style = NetKitType.terminal.copy(fontSize = fontSize.sp, lineHeight = (fontSize * TERMINAL_LINE_RATIO).sp)
    val cell = measurer.measure("M", style).size
    var terminalSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(terminalSize, cell, session.id) {
        delay(100) // Coalesce the IME animation before changing both local grid and remote PTY.
        if (terminalSize.width > 0 && terminalSize.height > 0 && cell.width > 0 && cell.height > 0)
            viewModel.resize(session.id, (terminalSize.width / cell.width).coerceIn(10, 240), (terminalSize.height / cell.height).coerceIn(2, 100))
    }
    val rows = remember(session.terminal) { session.terminal.history + session.terminal.screen }
    var directInput by remember { mutableStateOf<DirectTerminalInput?>(null) }
    val sendTextRaw: (String, Boolean, Boolean) -> Unit = { text, ctrlPressed, altPressed ->
        if (text.isNotEmpty()) viewModel.send(session.id, terminalTextSequence(text, control || ctrlPressed, alt || altPressed))
        control = false; alt = false
    }
    val sendKeyRaw: (TerminalKey, Boolean, Boolean, Boolean) -> Unit = { key, ctrlPressed, altPressed, shiftPressed ->
        viewModel.send(session.id, terminalKeySequence(key, session.terminal.applicationCursorKeys, control || ctrlPressed, alt || altPressed, shiftPressed))
        control = false; alt = false
    }
    val sendText: (String, Boolean, Boolean) -> Unit = { text, ctrlPressed, altPressed ->
        directInput?.flushComposing(); sendTextRaw(text, ctrlPressed, altPressed)
    }
    val sendKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit = { key, ctrlPressed, altPressed, shiftPressed ->
        directInput?.flushComposing(); sendKeyRaw(key, ctrlPressed, altPressed, shiftPressed)
    }
    val sendInput: () -> Unit = { sendText(safeTerminalPaste(input), false, false); input = "" }
    val colors = NetKitTheme.colors
    val chrome = TerminalChrome
    val background = colors.terminalBackground
    val foregroundDefault = colors.terminalText
    val cursorColor = chrome.accent
    val cursorText = chrome.accentInk
    val grid = if (terminalSize.width > 0 && terminalSize.height > 0 && cell.width > 0 && cell.height > 0)
        (terminalSize.width / cell.width).coerceIn(10, 240) to (terminalSize.height / cell.height).coerceIn(2, 100) else null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Column(Modifier.weight(1f).fillMaxWidth().clip(NetKitShape.card).background(background)
            .padding(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.tiny), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(chrome.tone(sessionTone(session)).foreground))
                Text(grid?.let { (columns, height) -> stringResource(R.string.ssh_terminal_header, session.name, columns, height) } ?: session.name,
                    Modifier.weight(1f), style = NetKitType.label, color = chrome.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().background(background).testTag("ssh_terminal_output")) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().onSizeChanged { terminalSize = it }) {
                    items(rows.size) { index -> Text(buildAnnotatedString {
                        rows[index].runs.forEach { run ->
                            withStyle(SpanStyle(color = terminalForeground(run.foreground, foregroundDefault, background),
                                background = terminalBackground(run.background, foregroundDefault, background),
                                fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal)) { append(run.text) }
                        }
                        if (session.connected && session.terminal.cursorVisible && index == session.terminal.history.size + session.terminal.cursorRow) {
                            val column = session.terminal.cursorColumn
                            if (column < length) addStyle(SpanStyle(color = cursorText, background = cursorColor), column, column + 1)
                        }
                    }, style = style, softWrap = false, maxLines = 1) }
                }
            }
            LaunchedEffect(session.terminal) {
                if (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= rows.size - session.terminal.screen.size - 2 } != false)
                    list.scrollToItem((rows.size - 1).coerceAtLeast(0))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
            AndroidView(
                factory = { inputContext -> DirectTerminalInput(inputContext).apply {
                    id = R.id.ssh_direct_input; directInput = this
                    typeface = inputContext.resources.getFont(R.font.manrope); textSize = 13.5f
                } },
                update = { field ->
                    field.isEnabled = session.connected
                    field.text = context.getString(R.string.ssh_direct_input)
                    field.contentDescription = context.getString(R.string.ssh_direct_input)
                    field.setTextColor(chrome.muted.toArgb()); field.setBackgroundColor(chrome.surface2.toArgb())
                    field.sendText = sendTextRaw; field.sendKey = sendKeyRaw
                    field.confirmPaste = { paste = it }
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).clip(NetKitShape.nested).testTag("ssh_direct_input"),
            )
            Box {
                IconButton(onClick = { directInput?.flushComposing(); menu = true }, modifier = Modifier.testTag("ssh_terminal_options")) {
                    Icon(NetKitIcons.More, contentDescription = stringResource(R.string.ssh_terminal_options), tint = chrome.muted)
                }
                NetKitMenu(expanded = menu, onDismiss = { menu = false }) {
                    DropdownMenuItem(text = { MenuText(stringResource(R.string.ssh_font_smaller)) }, onClick = { fontSize = (fontSize - 1).coerceAtLeast(8f) })
                    DropdownMenuItem(text = { MenuText(stringResource(R.string.ssh_font_larger)) }, onClick = { fontSize = (fontSize + 1).coerceAtMost(24f) })
                    DropdownMenuItem(text = { MenuText(stringResource(R.string.ssh_paste)) }, onClick = {
                        menu = false
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { paste = it.take(16384) }
                    })
                    DropdownMenuItem(text = { MenuText(stringResource(R.string.ssh_copy_terminal)) }, onClick = { menu = false; copyText(context, rows.joinToString("\n") { it.text.trimEnd() }, sensitive = true) })
                    DropdownMenuItem(text = { MenuText(stringResource(R.string.ssh_fallback_input)) }, onClick = { menu = false; fallback = !fallback }, modifier = Modifier.testTag("ssh_fallback_toggle"))
                }
            }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
                KeyPill(stringResource(R.string.ssh_ctrl), { directInput?.flushComposing(); control = !control }, active = control)
                KeyPill(stringResource(R.string.ssh_alt), { directInput?.flushComposing(); alt = !alt }, active = alt)
                val keys = listOf(R.string.ssh_esc to TerminalKey.ESCAPE, R.string.ssh_tab to TerminalKey.TAB,
                    R.string.ssh_up to TerminalKey.UP, R.string.ssh_down to TerminalKey.DOWN,
                    R.string.ssh_left to TerminalKey.LEFT, R.string.ssh_right to TerminalKey.RIGHT,
                    R.string.ssh_enter to TerminalKey.ENTER, R.string.ssh_backspace to TerminalKey.BACKSPACE)
                keys.forEach { (label, key) -> KeyPill(stringResource(label), { sendKey(key, false, false, false) }, enabled = session.connected,
                    modifier = if (key == TerminalKey.ENTER) Modifier.testTag("ssh_enter") else Modifier) }
                KeyPill(stringResource(R.string.ssh_more_keys), { directInput?.flushComposing(); moreKeys = !moreKeys }, active = moreKeys, icon = NetKitIcons.More)
            }
            if (moreKeys) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
                val keys = listOf(R.string.ssh_home to TerminalKey.HOME, R.string.ssh_end to TerminalKey.END,
                    R.string.ssh_page_up to TerminalKey.PAGE_UP, R.string.ssh_page_down to TerminalKey.PAGE_DOWN,
                    R.string.ssh_key_insert to TerminalKey.INSERT, R.string.ssh_key_delete to TerminalKey.DELETE)
                keys.forEach { (label, key) -> KeyPill(stringResource(label), { sendKey(key, false, false, false) }, enabled = session.connected) }
                KeyPill(stringResource(R.string.ssh_shift_tab), { sendKey(TerminalKey.TAB, false, false, true) }, enabled = session.connected)
                listOf(R.string.ssh_pipe to "|", R.string.ssh_tilde to "~", R.string.ssh_slash to "/").forEach { (label, value) ->
                    KeyPill(stringResource(label), { sendText(value, false, false) }, enabled = session.connected)
                }
                TerminalKey.entries.filter { it >= TerminalKey.F1 }.forEachIndexed { index, key ->
                    KeyPill(stringResource(R.string.ssh_function_key, index + 1), { sendKey(key, false, false, false) }, enabled = session.connected)
                }
            }
        }
        if (fallback) {
            // The fallback field sits outside the terminal container and follows the app theme.
            AndroidView(
                factory = { inputContext -> PrivateTerminalEditText(inputContext).apply {
                    hint = inputContext.getString(R.string.ssh_terminal_input); contentDescription = hint
                    this.background = null; typeface = inputContext.resources.getFont(R.font.manrope); textSize = 15f
                    val inset = (Spacing.medium.value * inputContext.resources.displayMetrics.density).toInt()
                    setPadding(inset, 0, inset, 0)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            val value = s?.toString().orEmpty()
                            if (pasteNeedsConfirmation(value)) paste = value else input = value.take(16384)
                        }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                } },
                update = { field ->
                    field.isEnabled = session.connected
                    field.setTextColor(colors.text.toArgb()); field.setHintTextColor(colors.muted.toArgb())
                    if (field.text.toString() != input) { field.setText(input); field.setSelection(field.text.length) }
                    field.setOnEditorActionListener { _, _, _ -> sendInput(); true }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(NetKitShape.nested).background(colors.surface2).testTag("ssh_terminal_input"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                PrimaryButton(stringResource(R.string.ssh_send_text), small = true, enabled = session.connected && input.isNotEmpty(), onClick = sendInput, modifier = Modifier.testTag("ssh_send_text"))
                NeutralButton(stringResource(R.string.ssh_enter), small = true, enabled = session.connected, onClick = { sendKey(TerminalKey.ENTER, false, false, false) })
                NeutralButton(stringResource(R.string.ssh_ctrl_c), small = true, enabled = session.connected, onClick = { sendText("c", true, false) })
            }
            Hint(stringResource(R.string.ssh_input_hint))
        }
    }
    paste?.let { value -> NetKitAlert(onDismiss = { paste = null }, title = stringResource(R.string.ssh_paste_confirm),
        text = { Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(stringResource(R.string.ssh_paste_warning), style = NetKitType.body)
            Text(safeTerminalPaste(value).take(1000), Modifier.fillMaxWidth().clip(NetKitShape.nested).background(colors.terminalBackground).padding(Spacing.medium),
                style = NetKitType.terminal, color = colors.terminalText)
        } },
        confirm = { PrimaryButton(stringResource(R.string.ssh_insert), small = true, onClick = { input = safeTerminalPaste(value); fallback = true; paste = null }) },
        dismiss = { NeutralButton(stringResource(R.string.ssh_cancel), small = true, onClick = { paste = null }) }) }
}

/**
 * Default colours (SGR 39/49, foreground 7 on background 0, and their inverse) follow the
 * terminal tokens, so plain output matches the app; explicit ANSI colours keep the palette.
 */
private fun terminalForeground(index: Int, text: Color, background: Color): Color = when (index) {
    7 -> text
    0 -> background
    else -> terminalColor(index)
}

private fun terminalBackground(index: Int, text: Color, background: Color): Color = when (index) {
    0 -> background
    7 -> text
    else -> terminalColor(index)
}

private fun terminalColor(index: Int): Color {
    if (index >= 0x1000000) return Color(0xff000000L or (index.toLong() and 0xffffff))
    val basic = longArrayOf(0xff000000, 0xffcc4444, 0xff44cc44, 0xffcccc44, 0xff6688dd, 0xffcc44cc, 0xff44cccc, 0xffdddddd, 0xff777777, 0xffff7777, 0xff77ff77, 0xffffff77, 0xff99bbff, 0xffff77ff, 0xff77ffff, 0xffffffff)
    if (index < 16) return Color(basic[index.coerceAtLeast(0)])
    if (index >= 232) { val level = (8 + (index.coerceAtMost(255) - 232) * 10); return Color(level, level, level) }
    val n = index - 16; fun component(n: Int) = if (n == 0) 0 else 55 + n * 40
    return Color(component(n / 36), component(n / 6 % 6), component(n % 6))
}

private fun sshFailureResource(failure: SshFailure?): Int = when (failure) {
    SshFailure.HOST_KEY_CHANGED -> R.string.ssh_error_host_changed
    SshFailure.HOST_KEY_REJECTED -> R.string.ssh_error_host_rejected
    SshFailure.AUTHENTICATION -> R.string.ssh_error_authentication
    SshFailure.ALGORITHMS -> R.string.ssh_error_algorithms
    SshFailure.DNS -> R.string.ssh_error_dns
    SshFailure.TIMEOUT -> R.string.ssh_error_timeout
    SshFailure.CONNECTION -> R.string.ssh_error_connection
    SshFailure.IMPORT_RSA_SMALL -> R.string.ssh_error_import_rsa
    SshFailure.IMPORT_DSA -> R.string.ssh_error_import_dsa
    SshFailure.IMPORT_PASSPHRASE -> R.string.ssh_error_import_phrase
    SshFailure.IMPORT_FORMAT -> R.string.ssh_error_import_format
    SshFailure.SECRET_UNAVAILABLE -> R.string.ssh_error_secret
    SshFailure.STORAGE -> R.string.ssh_storage_failed
    else -> R.string.ssh_operation_failed
}
