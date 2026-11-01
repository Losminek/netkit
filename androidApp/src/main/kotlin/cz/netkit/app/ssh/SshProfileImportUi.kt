package cz.netkit.app.ssh

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.netkit.app.R
import cz.netkit.app.ui.Eyebrow
import cz.netkit.app.ui.NeutralButton
import cz.netkit.app.ui.PrimaryButton
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.StatusBanner
import cz.netkit.app.ui.StatusKind
import cz.netkit.app.ui.Tag
import cz.netkit.app.ui.Tone
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.shared.ssh.needsKeySelection
import cz.netkit.shared.ssh.SshAuthentication
import cz.netkit.shared.ssh.SshImportEntry
import cz.netkit.shared.ssh.SshImportSkipReason
import cz.netkit.shared.ssh.SshProfileImportFailure

private fun endpoint(username: String, host: String, port: Int): String {
    val shownHost = if (':' in host) "[$host]" else host
    return if (port == 22) "$username@$shownHost" else "$username@$shownHost:$port"
}

/**
 * Preview of v1, v2 or legacy export. Nothing is stored before confirmation.
 * A chosen key applies only to new v1 profiles or new v2/legacy KEY profiles.
 */
@Composable
internal fun ProfileImportDialog(pending: PendingSshProfileImport, state: SshUiState, onConfirm: (String?) -> Unit, onCancel: () -> Unit) {
    val colors = NetKitTheme.colors
    val plan = pending.plan
    var keyRef by rememberSaveable(plan) { mutableStateOf(state.data.keys.firstOrNull()?.id) }
    NetKitAlert(
        onDismiss = { if (!state.profileImportBusy) onCancel() },
        title = stringResource(R.string.ssh_import_title),
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                item {
                    Text(stringResource(R.string.ssh_import_summary, plan.newProfiles.size, plan.updatedProfiles.size,
                        plan.unchangedProfiles.size, plan.skipped.size), style = NetKitType.body, color = colors.text)
                    plan.source?.let { Text(stringResource(R.string.ssh_import_source, it), style = NetKitType.label, color = colors.muted) }
                }
                if (plan.newProfiles.isNotEmpty()) {
                    item { Eyebrow(stringResource(R.string.ssh_import_new), Modifier.padding(top = Spacing.small)) }
                    items(plan.newProfiles, key = { "n" + it.incoming.alias }) { ImportRow(it, Tone.OK, keyRef, state) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
                            Eyebrow(stringResource(R.string.ssh_import_key), Modifier.padding(top = Spacing.small))
                            if (state.data.keys.isEmpty()) StatusBanner(stringResource(R.string.ssh_import_no_key), StatusKind.WARNING)
                            (state.data.keys.map { it.id to it.name } + (null to stringResource(R.string.ssh_import_password))).forEach { (id, name) ->
                                ChoiceRow(keyRef == id, name, onClick = { keyRef = id })
                            }
                        }
                    }
                }
                if (plan.updatedProfiles.isNotEmpty()) {
                    item { Eyebrow(stringResource(R.string.ssh_import_updated), Modifier.padding(top = Spacing.small)) }
                    items(plan.updatedProfiles, key = { "u" + it.incoming.alias }) { ImportRow(it, Tone.WARN, keyRef, state) }
                }
                if (plan.skipped.isNotEmpty()) {
                    item { Eyebrow(stringResource(R.string.ssh_import_skipped), Modifier.padding(top = Spacing.small)) }
                    items(plan.skipped, key = { "s" + it.alias }) { skipped ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            Text(skipped.alias, Modifier.weight(1f), style = NetKitType.body, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Tag(stringResource(skipReasonResource(skipped.reason)))
                        }
                    }
                }
                item { Text(stringResource(R.string.ssh_import_note), Modifier.padding(top = Spacing.small), style = NetKitType.label, color = colors.muted) }
            }
        },
        confirm = {
            PrimaryButton(stringResource(R.string.ssh_import_confirm), small = true,
                enabled = !state.profileImportBusy && (plan.newProfiles.isNotEmpty() || plan.updatedProfiles.isNotEmpty()),
                onClick = { onConfirm(if (plan.newProfiles.isEmpty()) null else keyRef) }, modifier = Modifier.testTag("ssh_import_confirm"))
        },
        dismiss = { NeutralButton(stringResource(R.string.ssh_cancel_import), small = true, enabled = !state.profileImportBusy, onClick = onCancel) },
    )
}

@Composable
private fun ImportRow(entry: SshImportEntry, tone: Tone, keyRef: String?, state: SshUiState) {
    val colors = NetKitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(entry.incoming.alias, Modifier.weight(1f), style = NetKitType.panelTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.incoming.group?.let { Tag(it, tone) }
        }
        val after = entry.after
        val before = entry.before
        if (before == null) {
            Text(endpoint(after.username, after.host, after.port), style = NetKitType.addressFor(NetKitType.label), color = colors.muted)
            val authentication = entry.incoming.authentication ?: if (keyRef != null) SshAuthentication.KEY else SshAuthentication.PASSWORD
            Text(stringResource(authenticationResource(authentication)), Modifier.testTag("ssh_import_auth_${entry.incoming.alias}"), style = NetKitType.label, color = colors.muted)
            if (after.startupCommand.isNotEmpty()) StatusBanner(stringResource(R.string.ssh_import_startup_warning) + "\n" + after.startupCommand, StatusKind.WARNING)
        } else {
            ImportChange(R.string.ssh_host, before.host, after.host)
            ImportChange(R.string.ssh_port, before.port.toString(), after.port.toString())
            ImportChange(R.string.ssh_username, before.username, after.username)
            ImportChange(R.string.ssh_authentication_label, stringResource(authenticationResource(before.authentication)), stringResource(authenticationResource(after.authentication)))
            ImportChange(R.string.ssh_startup_command, before.startupCommand, after.startupCommand, sensitive = true)
            ImportChange(R.string.ssh_keepalive, before.keepaliveSeconds.toString(), after.keepaliveSeconds.toString())
            if (after.needsKeySelection(state.data)) StatusBanner(stringResource(R.string.ssh_select_profile_key), StatusKind.WARNING)
        }
        if (entry.detachedSecretRef != null) StatusBanner(stringResource(R.string.ssh_import_secret_moves), StatusKind.WARNING)
    }
}

@Composable
private fun ImportChange(@StringRes label: Int, before: String, after: String, sensitive: Boolean = false) {
    if (before == after) return
    val text = stringResource(R.string.ssh_import_change, stringResource(label),
        before.ifEmpty { stringResource(R.string.ssh_none) }, after.ifEmpty { stringResource(R.string.ssh_none) })
    if (sensitive) StatusBanner(text + "\n" + stringResource(R.string.ssh_import_startup_warning), StatusKind.WARNING)
    else Text(text, style = NetKitType.label, color = NetKitTheme.colors.muted)
}

@StringRes
private fun authenticationResource(value: SshAuthentication) = when (value) {
    SshAuthentication.PASSWORD -> R.string.ssh_password
    SshAuthentication.KEY -> R.string.ssh_key
    SshAuthentication.INTERACTIVE -> R.string.ssh_interactive
}

@StringRes
private fun skipReasonResource(reason: SshImportSkipReason) = when (reason) {
    SshImportSkipReason.PROXY_JUMP -> R.string.ssh_skip_proxy_jump
    SshImportSkipReason.PROXY_COMMAND -> R.string.ssh_skip_proxy_command
    SshImportSkipReason.MATCH -> R.string.ssh_skip_match
    SshImportSkipReason.WILDCARD -> R.string.ssh_skip_wildcard
    SshImportSkipReason.MISSING_HOST -> R.string.ssh_skip_missing_host
    SshImportSkipReason.UNSUPPORTED_OPTION -> R.string.ssh_skip_unsupported
}

@StringRes
internal fun profileImportErrorResource(error: SshProfileImportError): Int = when (error) {
    SshProfileImportError.Operation.BLOCKED -> R.string.ssh_import_blocked
    SshProfileImportError.Operation.IO -> R.string.ssh_import_io
    SshProfileImportError.Operation.TIMEOUT -> R.string.ssh_import_timeout
    SshProfileImportError.Operation.KEY_UNAVAILABLE -> R.string.ssh_import_key_unavailable
    is SshProfileImportError.Document -> when (error.reason) {
        SshProfileImportFailure.SIZE -> R.string.ssh_import_size
        SshProfileImportFailure.UTF8 -> R.string.ssh_import_utf8
        SshProfileImportFailure.SCHEMA -> R.string.ssh_import_schema
        SshProfileImportFailure.UNKNOWN_FIELD -> R.string.ssh_import_unknown_field
        SshProfileImportFailure.DUPLICATE_ALIAS, SshProfileImportFailure.AMBIGUOUS_ALIAS -> R.string.ssh_import_duplicate
        SshProfileImportFailure.INVALID_PROFILE -> R.string.ssh_import_invalid_profile
        SshProfileImportFailure.LIMIT -> R.string.ssh_import_limit
        SshProfileImportFailure.STALE_PLAN -> R.string.ssh_import_stale
        SshProfileImportFailure.FORMAT, SshProfileImportFailure.INVALID_REFERENCE, SshProfileImportFailure.DUPLICATE_ID,
        SshProfileImportFailure.INVALID_DATA -> R.string.ssh_import_format
    }
}
