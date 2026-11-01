package cz.netkit.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cz.netkit.app.R
import cz.netkit.app.ui.NetKitIcons
import cz.netkit.app.ui.Panel
import cz.netkit.app.ui.PrimaryButton
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.shared.update.UpdateInfo
import cz.netkit.shared.update.localizedWebUrl
import cz.netkit.app.BuildConfig
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

/**
 * Overview card "Dostupná verze X" (D14): version, release date, short notes, "Stáhnout" opens the
 * project page (or the APK asset) in the browser; the close button hides it until a newer version.
 */
@Composable
internal fun UpdateCard(info: UpdateInfo, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = NetKitTheme.colors
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    Panel(modifier.testTag("update_card")) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
                Text(stringResource(R.string.update_available_title, info.versionName), style = NetKitType.panelTitle, color = colors.text)
                info.released?.let { localDate(it) }?.let {
                    Text(stringResource(R.string.update_released, it), style = NetKitType.label, color = colors.muted)
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp).testTag("update_dismiss")) {
                Icon(NetKitIcons.Close, stringResource(R.string.update_dismiss), Modifier.size(18.dp), tint = colors.muted)
            }
        }
        info.notesFor(language)?.let { Text(it, style = NetKitType.body, color = colors.text) }
        Text(stringResource(R.string.update_install_hint), style = NetKitType.label, color = colors.muted)
        PrimaryButton(stringResource(R.string.update_download), { openLink(context, localizedUpdateUrl(info.openUrl, language)) },
            Modifier.testTag("update_open"), icon = NetKitIcons.Download)
    }
}

/** `2026-11-02` in the medium local format ("2. 11. 2026"), or the raw value if it does not parse. */
internal fun localDate(iso: String): String = try {
    LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
} catch (_: DateTimeParseException) {
    iso
}

/**
 * Opens an `https` link in the browser on user request (`ACTION_VIEW`, browsable category). Links
 * from `latest.json` are already limited to the web host and github.com by the shared parser.
 */
internal fun openLink(context: Context, url: String) {
    if (!url.startsWith("https://")) return
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.about_open_failed, Toast.LENGTH_SHORT).show()
    }
}

/** Update page in the UI language: the web page of `latest.json` goes to `/en/netkit` outside Czech. */
internal fun localizedUpdateUrl(url: String, language: String): String =
    localizedWebUrl(url, BuildConfig.UPDATE_BASE_URL.takeIf { BuildConfig.UPDATE_CONFIGURED }, language)
