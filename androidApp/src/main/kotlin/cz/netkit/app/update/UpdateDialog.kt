package cz.netkit.app.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import cz.netkit.app.BuildConfig
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import cz.netkit.app.R
import cz.netkit.shared.update.UpdateInfo

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun UpdateDialog(info: UpdateInfo, onClose: () -> Unit, onDismissVersion: () -> Unit) {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    AlertDialog(
        modifier = Modifier.testTag("update_dialog").semantics { testTagsAsResourceId = BuildConfig.DEBUG },
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.update_available_title, info.versionName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                info.notesFor(language)?.let { Text(it) }
                TextButton(onClick = onDismissVersion, modifier = Modifier.testTag("update_dialog_dismiss")) {
                    Text(stringResource(R.string.update_dialog_dismiss_version))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openLink(context, localizedUpdateUrl(info.openUrl, language))
                onClose()
            }, modifier = Modifier.testTag("update_dialog_download")) { Text(stringResource(R.string.update_download)) }
        },
        dismissButton = {
            TextButton(onClick = onClose, modifier = Modifier.testTag("update_dialog_close")) {
                Text(stringResource(R.string.update_dialog_close))
            }
        },
    )
}
