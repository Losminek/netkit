package cz.netkit.app.ui.about

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import cz.netkit.app.R
import cz.netkit.app.ui.ListRow
import cz.netkit.app.ui.Panel
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.theme.NetKitTheme
import cz.netkit.app.ui.theme.NetKitType
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Directory in `assets` with the licence texts and [OSS_COMPONENTS_FILE]. */
internal const val OSS_LICENSES_DIR = "licenses"
internal const val OSS_COMPONENTS_FILE = "components.json"

/**
 * One bundled component (`assets/licenses/components.json`). [texts] are file names in
 * [OSS_LICENSES_DIR]; [catalog] and [artifacts] exist for the coverage test only.
 */
@Serializable
internal data class OssComponent(
    val id: String,
    val name: String,
    val version: String? = null,
    val license: String,
    val copyright: String,
    val texts: List<String>,
    val catalog: List<String> = emptyList(),
    val artifacts: List<String> = emptyList(),
)

@Serializable
internal data class OssComponents(val comment: String? = null, val components: List<OssComponent>)

private val componentsJson = Json { ignoreUnknownKeys = false }

/** Parses the component list; a malformed file (a build mistake) yields `null`, never a crash. */
internal fun parseOssComponents(text: String): List<OssComponent>? = try {
    componentsJson.decodeFromString(OssComponents.serializer(), text).components
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Paragraphs for display: blank lines separate paragraphs; inside one, hard-wrapped lines are joined
 * with a space unless a line starts a list item or is a heading underline, so the text reflows on a
 * narrow screen. The words themselves are unchanged.
 */
internal fun licenseParagraphs(text: String): List<String> =
    text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n+")).mapNotNull { block ->
        val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return@mapNotNull null
        buildString {
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                when {
                    index == 0 -> append(trimmed)
                    LIST_ITEM.containsMatchIn(trimmed) || UNDERLINE.matches(trimmed) || UNDERLINE.matches(lines[index - 1].trim()) ->
                        append('\n').append(trimmed)
                    else -> append(' ').append(trimmed)
                }
            }
        }
    }

private val LIST_ITEM = Regex("""^(\d+(\.\d+)*\.|\(?[a-z0-9]\)|[-*•]\s)""")
private val UNDERLINE = Regex("""^[-=*]{3,}$""")

private fun readAsset(context: Context, name: String): String? = try {
    context.assets.open("$OSS_LICENSES_DIR/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
} catch (_: IOException) {
    null
}

/**
 * "Licence open source" (subscreen of About): every bundled third-party component with version,
 * licence and copyright; a tap shows the full licence text from `assets/licenses`. System back and
 * the header arrow go from a text back to the list, from the list back to About.
 */
@Composable
fun OssLicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val components by produceState<List<OssComponent>?>(null, context) {
        value = withContext(Dispatchers.IO) { readAsset(context, OSS_COMPONENTS_FILE)?.let(::parseOssComponents) } ?: emptyList()
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = components?.firstOrNull { it.id == selectedId }
    val back: () -> Unit = {
        if (selectedId != null) {
            selectedId = null
        } else {
            onBack()
        }
    }
    BackHandler(onBack = back)
    if (selected == null) {
        ComponentList(components, onBack = back, onSelect = { selectedId = it.id }, modifier = modifier)
    } else {
        LicenseText(selected, onBack = back, modifier = modifier)
    }
}

@Composable
private fun ComponentList(components: List<OssComponent>?, onBack: () -> Unit, onSelect: (OssComponent) -> Unit, modifier: Modifier) {
    val colors = NetKitTheme.colors
    LazyColumn(
        modifier.fillMaxSize().testTag("oss_licenses_screen"),
        contentPadding = PaddingValues(start = Spacing.normal, end = Spacing.normal, bottom = Spacing.normal),
        verticalArrangement = Arrangement.spacedBy(Spacing.normal),
    ) {
        item(key = "header") { SubscreenHeader(stringResource(R.string.about_oss_licenses), "oss_back", onBack) }
        item(key = "intro") { Text(stringResource(R.string.oss_intro), style = NetKitType.body, color = colors.muted) }
        item(key = "list") {
            Panel {
                when {
                    components == null -> Text(stringResource(R.string.oss_loading), style = NetKitType.body, color = colors.muted)
                    components.isEmpty() -> Text(stringResource(R.string.oss_load_failed), style = NetKitType.body, color = colors.muted)
                    else -> Column {
                        components.forEachIndexed { index, component ->
                            ListRow(component.name, subtitle(component), onClick = { onSelect(component) },
                                modifier = Modifier.testTag("oss_license_${component.id}"), showDivider = index > 0,
                                titleMaxLines = Int.MAX_VALUE, subtitleMaxLines = Int.MAX_VALUE)
                        }
                    }
                }
            }
        }
    }
}

/** "5.3.2 · Apache-2.0" and the copyright line below. */
@Composable
private fun subtitle(component: OssComponent): String {
    val meta = component.version?.let { stringResource(R.string.oss_meta, it, component.license) } ?: component.license
    return meta + "\n" + component.copyright
}

@Composable
private fun LicenseText(component: OssComponent, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val colors = NetKitTheme.colors
    // One list of paragraphs per text file, in the order of `texts`; null while loading.
    val texts by produceState<List<Pair<String, List<String>?>>?>(null, component.id) {
        value = withContext(Dispatchers.IO) { component.texts.map { name -> name to readAsset(context, name)?.let(::licenseParagraphs) } }
    }
    // Manrope's contextual alternates turn "(c)" into "©"; licence text is shown literally.
    val literal = "liga 0, clig 0, calt 0"
    val licenseStyle = NetKitType.body.copy(fontSize = 13.sp, lineHeight = 19.sp, fontFeatureSettings = literal)
    LazyColumn(
        modifier.fillMaxSize().testTag("oss_license_text"),
        contentPadding = PaddingValues(start = Spacing.normal, end = Spacing.normal, bottom = Spacing.normal),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        item(key = "header") { SubscreenHeader(component.name, "oss_back", onBack) }
        item(key = "meta") {
            Panel {
                Text(subtitle(component), style = NetKitType.body.copy(fontFeatureSettings = literal), color = colors.muted)
            }
        }
        val loaded = texts
        if (loaded == null) {
            item(key = "loading") { Text(stringResource(R.string.oss_loading), style = NetKitType.body, color = colors.muted) }
        } else {
            loaded.forEach { (name, paragraphs) ->
                item(key = "file-$name") {
                    Text(name, Modifier.semantics { heading() }, style = NetKitType.panelTitle, color = colors.text)
                }
                if (paragraphs == null) {
                    item(key = "failed-$name") { Text(stringResource(R.string.oss_load_failed), style = NetKitType.body, color = colors.muted) }
                } else {
                    itemsIndexed(paragraphs, key = { index, _ -> "$name-$index" }) { _, paragraph ->
                        SelectionContainer { Text(paragraph, style = licenseStyle, color = colors.text) }
                    }
                }
            }
        }
    }
}
