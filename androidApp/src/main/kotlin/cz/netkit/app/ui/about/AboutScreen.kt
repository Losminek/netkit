package cz.netkit.app.ui.about

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netkit.app.BuildConfig
import cz.netkit.app.R
import cz.netkit.app.ssh.netKitSwitchColors
import cz.netkit.app.ui.InfoNote
import cz.netkit.app.ui.ListRow
import cz.netkit.app.ui.NetKitIcons
import cz.netkit.app.ui.NeutralButton
import cz.netkit.app.ui.Panel
import cz.netkit.app.ui.PrimaryButton
import cz.netkit.app.ui.SectionCard
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.StatusBanner
import cz.netkit.app.ui.StatusKind
import cz.netkit.app.ui.theme.NetKitShape
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.app.update.UpdateUiState
import cz.netkit.app.update.UpdateViewModel
import cz.netkit.app.update.localizedUpdateUrl
import cz.netkit.app.update.openLink
import cz.netkit.shared.update.UpdateCheckFailure
import cz.netkit.shared.update.UpdateSource
import cz.netkit.shared.update.UpdateStatus
import cz.netkit.shared.update.localizedWebUrl
import cz.netkit.shared.update.privacyPolicyUrl

/**
 * About (zadání 21, D14, D22): name and version from [PackageManager], update switch and
 * "Zkontrolovat teď", project links (web, sources, privacy policy, open-source licences, app
 * licence), the scanning notice and what the check sends. A subscreen of Přehled opened from its
 * header menu; the number of tabs does not change. [onOpenLicenses] opens [OssLicensesScreen].
 */
@Composable
fun AboutScreen(update: UpdateViewModel, onBack: () -> Unit, onOpenLicenses: () -> Unit, modifier: Modifier = Modifier) {
    val state by update.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) { packageVersion(context) }
    val note = updatesNote(state)
    BackHandler(onBack = onBack)
    LazyColumn(
        modifier.fillMaxSize().testTag("about_screen"),
        contentPadding = PaddingValues(start = Spacing.normal, end = Spacing.normal, bottom = Spacing.normal),
        verticalArrangement = Arrangement.spacedBy(Spacing.normal),
    ) {
        item(key = "header") { SubscreenHeader(stringResource(R.string.about_title), "about_back", onBack) }
        item(key = "app") {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.tiny)) {
                    Text(stringResource(R.string.app_name), style = NetKitType.value, color = NetKitTheme.colors.text)
                    Text(stringResource(R.string.about_version, version.first, version.second), Modifier.testTag("about_version"),
                        style = NetKitType.body, color = NetKitTheme.colors.muted)
                }
                Text(stringResource(R.string.about_tagline), style = NetKitType.body, color = NetKitTheme.colors.text)
            }
        }
        item(key = "updates") {
            UpdatesCard(state, onToggle = update::setEnabled, onCheck = update::checkNow,
                onOpen = { url -> openLink(context, url) })
        }
        item(key = "project") { ProjectCard(onOpen = { url -> openLink(context, url) }, onOpenLicenses = onOpenLicenses) }
        item(key = "safety") { StatusBanner(stringResource(R.string.lan_safety), StatusKind.WARNING) }
        note?.let { detail ->
            item(key = "note") { InfoNote(stringResource(R.string.about_updates_note_summary), detail) }
        }
    }
}

/** Back (ChevronLeft on a 40 dp `surface2` tile) + title, the subscreen header of WoL and SSH. */
@Composable
internal fun SubscreenHeader(title: String, backTag: String, onBack: () -> Unit) {
    val colors = NetKitTheme.colors
    val back = stringResource(R.string.about_back)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
        Box(Modifier.minimumInteractiveComponentSize().size(40.dp).clip(NetKitShape.nested).background(colors.surface2)
            .clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = back }.testTag(backTag),
            contentAlignment = Alignment.Center) {
            Icon(NetKitIcons.ChevronLeft, null, Modifier.size(20.dp), tint = colors.text)
        }
        Text(title, Modifier.weight(1f), style = NetKitType.screenTitle, color = colors.text)
    }
}

@Composable
private fun UpdatesCard(state: UpdateUiState, onToggle: (Boolean) -> Unit, onCheck: () -> Unit, onOpen: (String) -> Unit) {
    val colors = NetKitTheme.colors
    val language = LocalConfiguration.current.locales[0].language
    SectionCard(stringResource(R.string.about_updates_title)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = state.enabled, enabled = state.available, role = Role.Switch, onValueChange = onToggle)
                .testTag("about_update_toggle"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.about_updates_toggle), style = NetKitType.body,
                    color = if (state.available) colors.text else colors.dim)
                Text(stringResource(R.string.about_updates_toggle_detail), style = NetKitType.label, color = colors.muted)
            }
            Switch(checked = state.enabled && state.available, onCheckedChange = null, enabled = state.available, colors = netKitSwitchColors())
        }
        Column(Modifier.testTag("about_update_status"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(statusText(state), style = NetKitType.body, color = colors.text)
            if (state.available) Text(lastCheckText(state.lastCheckMs, state.lastSource), style = NetKitType.label, color = colors.muted)
        }
        val info = state.info
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            if (info != null && state.available && (state.status == UpdateStatus.AVAILABLE || state.status == UpdateStatus.DISMISSED)) {
                PrimaryButton(stringResource(R.string.update_download), { onOpen(localizedUpdateUrl(info.openUrl, language)) },
                    Modifier.testTag("about_update_open"),
                    icon = NetKitIcons.Download, small = true)
            }
            NeutralButton(stringResource(if (state.checking) R.string.about_checking else R.string.about_check_now), onCheck,
                Modifier.testTag("about_check_now"), icon = NetKitIcons.Refresh, enabled = state.available && state.loaded && !state.checking,
                small = true)
        }
    }
}

@Composable
private fun statusText(state: UpdateUiState): String {
    val info = state.info
    return when {
        !state.available -> stringResource(R.string.about_updates_not_configured)
        state.checking -> stringResource(R.string.about_checking)
        state.lastFailure == UpdateCheckFailure.NETWORK -> stringResource(R.string.about_check_unreachable)
        state.lastFailure != null -> stringResource(R.string.about_check_invalid)
        info == null -> stringResource(R.string.about_never_checked)
        state.status == UpdateStatus.REQUIRES_NEWER_ANDROID -> stringResource(R.string.about_requires_android, info.versionName)
        state.status == UpdateStatus.UP_TO_DATE -> stringResource(R.string.about_up_to_date)
        else -> stringResource(R.string.update_available_title, info.versionName)
    }
}

/** "Naposledy před 5 min", with ", přes GitHub" when the fallback answered the last check. */
@Composable
private fun lastCheckText(lastCheckMs: Long?, source: UpdateSource?): String {
    if (lastCheckMs == null) return stringResource(R.string.about_never_checked)
    // DateUtils capitalizes the phrase ("Před 4 minutami"); here it continues a sentence.
    val relative = DateUtils.getRelativeTimeSpanString(lastCheckMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        .replaceFirstChar { it.lowercaseChar() }
    return stringResource(if (source == UpdateSource.GITHUB) R.string.about_last_check_github else R.string.about_last_check, relative)
}

/** What the check sends, by the sources this build has; `null` when it has none. */
@Composable
private fun updatesNote(state: UpdateUiState): String? {
    val web = state.webHost
    return when {
        web != null && state.githubSource -> stringResource(R.string.about_updates_note_detail, web)
        web != null -> stringResource(R.string.about_updates_note_detail_web, web)
        state.githubSource -> stringResource(R.string.about_updates_note_detail_github)
        else -> null
    }
}

@Composable
private fun ProjectCard(onOpen: (String) -> Unit, onOpenLicenses: () -> Unit) {
    val language = LocalConfiguration.current.locales[0].language
    val webBase = BuildConfig.UPDATE_BASE_URL.takeIf { BuildConfig.UPDATE_CONFIGURED }
    val privacy = remember(language) { privacyPolicyUrl(webBase, BuildConfig.SOURCE_URL, language) }
    val webPage = remember(language) { localizedWebUrl(BuildConfig.UPDATE_BASE_URL, webBase, language) }
    SectionCard(stringResource(R.string.about_project_title)) {
        Column {
            var divider = false
            if (BuildConfig.UPDATE_CONFIGURED) {
                ListRow(stringResource(R.string.about_web), webPage.removePrefix("https://"), NetKitIcons.Globe,
                    onClick = { onOpen(webPage) }, modifier = Modifier.testTag("about_web"))
                divider = true
            }
            ListRow(stringResource(R.string.about_source), BuildConfig.SOURCE_URL.removePrefix("https://"), NetKitIcons.Terminal,
                onClick = { onOpen(BuildConfig.SOURCE_URL) }, modifier = Modifier.testTag("about_source"), showDivider = divider)
            ListRow(stringResource(R.string.about_privacy), privacy.removePrefix("https://"), NetKitIcons.Shield,
                onClick = { onOpen(privacy) }, modifier = Modifier.testTag("about_privacy"), showDivider = true, titleMaxLines = 2)
            ListRow(stringResource(R.string.about_oss_licenses), stringResource(R.string.about_oss_licenses_detail), NetKitIcons.Document,
                onClick = onOpenLicenses, modifier = Modifier.testTag("about_oss_licenses"), showDivider = true, titleMaxLines = 2)
            ListRow(stringResource(R.string.about_license), stringResource(R.string.about_license_name), NetKitIcons.Info,
                onClick = { onOpen("${BuildConfig.SOURCE_URL}/blob/main/LICENSE") }, modifier = Modifier.testTag("about_license"),
                showDivider = true)
        }
    }
}

/** `versionName` and `versionCode` of the installed package (as Android sees them), else BuildConfig. */
private fun packageVersion(context: Context): Pair<String, Long> = try {
    val manager = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        manager.getPackageInfo(context.packageName, 0)
    }
    (info.versionName ?: BuildConfig.VERSION_NAME) to info.longVersionCode
} catch (_: PackageManager.NameNotFoundException) {
    BuildConfig.VERSION_NAME to BuildConfig.VERSION_CODE.toLong()
}
