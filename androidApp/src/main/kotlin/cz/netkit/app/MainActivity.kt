package cz.netkit.app

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import cz.netkit.app.ui.theme.NetKitType
import cz.netkit.app.ui.motion.rememberNetKitExit
import cz.netkit.app.ui.motion.rememberNetKitEnter
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.netkit.app.ui.Spacing
import cz.netkit.app.ui.StatusBanner
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.unit.dp
import cz.netkit.app.ui.NetKitIcons
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.ViewModelProvider
import cz.netkit.app.wol.WolViewModel
import cz.netkit.app.ui.about.AboutScreen
import cz.netkit.app.ui.about.OssLicensesScreen
import cz.netkit.app.update.UpdateCard
import cz.netkit.app.update.UpdateViewModel
import cz.netkit.app.wol.WolScreen
import cz.netkit.app.lan.LanViewModel
import cz.netkit.app.lan.LanScreen
import cz.netkit.app.wifi.WifiViewModel
import cz.netkit.app.wifi.WifiScreen
import cz.netkit.app.ssh.SshViewModel
import cz.netkit.app.ssh.SshScreen
import cz.netkit.app.speed.SignalHost
import cz.netkit.app.speed.SpeedTestViewModel
import cz.netkit.app.wifi.live.WifiLiveViewModel
import cz.netkit.app.lan.DevicesScreen
import cz.netkit.app.lan.LanDevicesViewModel
import cz.netkit.app.lan.LanTool
import cz.netkit.app.ui.SegmentedTabs
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cz.netkit.app.ui.theme.NetKitTheme

class MainActivity : ComponentActivity() {
    private val wol by lazy { ViewModelProvider(this)[WolViewModel::class.java] }

    private val lan by lazy { ViewModelProvider(this)[LanViewModel::class.java] }
    private val wifi by lazy { ViewModelProvider(this)[WifiViewModel::class.java] }
    private val live by lazy { ViewModelProvider(this)[WifiLiveViewModel::class.java] }
    private val devices by lazy { ViewModelProvider(this)[LanDevicesViewModel::class.java] }
    private val speed by lazy { ViewModelProvider(this)[SpeedTestViewModel::class.java] }

    private val ssh by lazy { ViewModelProvider(this)[SshViewModel::class.java] }
    private val update by lazy { ViewModelProvider(this)[UpdateViewModel::class.java] }

    override fun onStop() {
        if (!isChangingConfigurations) {
            wol.controller.cancelWake()
            lan.cancelActive()
            wifi.cancelActive()
            // Both screens also stop themselves on pause; this covers a stop without composition.
            live.setActive(false)
            devices.setActive(false)
            speed.setActive(false)
            ssh.onBackground()
            update.onBackground()
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        ssh.onForeground()
        // D14: the update check runs only in the foreground, at most once per 24 h.
        update.onForeground()
    }

    override fun onDestroy() {
        if (isFinishing) ssh.cancelActive()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NetKitTheme { NetKitApp(wol, lan, wifi, live, devices, ssh, speed, update) } }
    }
}

/** Test tags keep the M1 names: Spektrum is the former Wi-Fi tab, Zařízení the former LAN tab. */
private enum class Destination(@param:StringRes val label: Int, val tag: String) {
    SIGNAL(R.string.tab_signal, "tab_signal"),
    SPECTRUM(R.string.tab_spectrum, "tab_wifi"),
    DEVICES(R.string.tab_devices, "tab_lan"),
    SSH(R.string.tab_ssh, "tab_ssh"),
    WOL(R.string.tab_wol, "tab_wol"),
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NetKitApp(wol: WolViewModel, lan: LanViewModel, wifi: WifiViewModel, live: WifiLiveViewModel,
    devices: LanDevicesViewModel, ssh: SshViewModel, speed: SpeedTestViewModel, update: UpdateViewModel) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var lanSegment by rememberSaveable { mutableIntStateOf(0) }
    var sshPrefillHost by rememberSaveable { mutableStateOf<String?>(null) }
    // About is a subscreen of Přehled (zadání 21): any tab tap, Přehled included, closes it.
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    // Open-source licences, a subscreen of About; closed together with About.
    var licensesOpen by rememberSaveable { mutableStateOf(false) }
    val updateState by update.state.collectAsStateWithLifecycle()
    val destination = Destination.entries[selectedTab]
    val savedTabs = rememberSaveableStateHolder()
    val sshState by ssh.state.collectAsStateWithLifecycle()
    val divider = NetKitTheme.colors.divider
    fun open(target: Destination) {
        if (selectedTab != target.ordinal) { lan.cancelActive(); wifi.cancelActive() }
        selectedTab = target.ordinal
        aboutOpen = false
        licensesOpen = false
    }
    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = BuildConfig.DEBUG },
        containerColor = NetKitTheme.colors.background,
        topBar = {
            if (destination != Destination.SSH && (sshState.hostChallenge != null || sshState.authChallenge != null)) {
                Column(Modifier.statusBarsPadding().padding(Spacing.small)) {
                    StatusBanner(stringResource(R.string.ssh_prompt_pending))
                    TextButton(onClick = { open(Destination.SSH) }, modifier = Modifier.testTag("ssh_pending_open")) {
                        Text(stringResource(R.string.ssh_open_pending))
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                // A hairline instead of elevation keeps the flat M3 layers (docs/design.md §5).
                Modifier.drawBehind { drawLine(divider, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) },
                containerColor = NetKitTheme.colors.navBackground, tonalElevation = 0.dp,
            ) {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        modifier = Modifier.testTag(item.tag),
                        selected = destination == item,
                        onClick = { open(item) },
                        icon = { Icon(when(item) {
                            Destination.SIGNAL -> NetKitIcons.Signal
                            Destination.SPECTRUM -> NetKitIcons.Spectrum
                            Destination.DEVICES -> NetKitIcons.Devices
                            Destination.SSH -> NetKitIcons.Terminal
                            Destination.WOL -> NetKitIcons.Power
                        }, contentDescription = null) },
                        label = { NavLabel(stringResource(item.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NetKitTheme.colors.accent, selectedTextColor = NetKitTheme.colors.text,
                            indicatorColor = NetKitTheme.colors.accentSoft,
                            unselectedIconColor = NetKitTheme.colors.dim, unselectedTextColor = NetKitTheme.colors.dim,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        val screen = Modifier.padding(padding).consumeWindowInsets(padding)
        val enter = rememberNetKitEnter(); val exit = rememberNetKitExit()
        AnimatedContent(destination, transitionSpec = { enter togetherWith exit }, label = "tab") { shown ->
        savedTabs.SaveableStateProvider(shown.name) {
            when (shown) {
                Destination.SIGNAL -> if (aboutOpen && licensesOpen) OssLicensesScreen(onBack = { licensesOpen = false }, screen)
                    else if (aboutOpen) AboutScreen(update, onBack = { aboutOpen = false }, onOpenLicenses = { licensesOpen = true }, screen)
                    else SignalHost(live, speed, screen,
                        updateCard = updateState.card?.let { info -> { UpdateCard(info, onDismiss = { update.dismiss(info) }) } },
                        onOpenAbout = { aboutOpen = true })
                Destination.SPECTRUM -> WifiScreen(wifi, screen)
                Destination.DEVICES -> Column(screen) {
                    SegmentedTabs(listOf(stringResource(R.string.lan_segment_devices), stringResource(R.string.lan_segment_tools)),
                        // Leaving Tools stops its run, as leaving the tab does, so the two scanners never overlap.
                        lanSegment, { if (it != lanSegment && lanSegment == 1) lan.cancelActive(); lanSegment = it },
                        Modifier.padding(horizontal = Spacing.normal, vertical = Spacing.small),
                        testTags = listOf("lan_segment_devices", "lan_segment_tools"))
                    if (lanSegment == 0) DevicesScreen(devices,
                        onTrace = { address -> lan.host(address); lan.tool(LanTool.TRACE); lanSegment = 1 },
                        onSsh = { address -> sshPrefillHost = address; open(Destination.SSH) },
                        modifier = Modifier.weight(1f))
                    else LanScreen(lan, Modifier.weight(1f))
                }
                Destination.SSH -> SshScreen(ssh, screen, prefillHost = sshPrefillHost, onPrefillConsumed = { sshPrefillHost = null })
                Destination.WOL -> WolScreen(wol, screen)
            }
        }
        }
    }
}

/** Largest font scale the bottom-navigation label follows; beyond it the label stops growing. */
private const val NAV_MAX_FONT_SCALE = 1.15f

/**
 * Bottom-navigation label (`nav` role): one line, never ellipsized. It grows with the system font
 * scale only up to [NAV_MAX_FONT_SCALE] (size × min(fontScale, 1.15)); a label that still does not
 * fit its item is shrunk step by step down to 80 % of that size.
 */
@Composable
private fun NavLabel(text: String) {
    val density = LocalDensity.current
    val color = LocalContentColor.current
    val base = NetKitType.nav
    val scale = density.fontScale.coerceAtMost(NAV_MAX_FONT_SCALE)
    // Converted from dp, so the sp value already contains the capped scale (also with non-linear scaling).
    val max = with(density) { (base.fontSize.value * scale).dp.toSp() }
    val min = with(density) { (base.fontSize.value * scale * 0.8f).dp.toSp() }
    val lineHeight = with(density) { (base.lineHeight.value * scale).dp.toSp() }
    BasicText(
        text, style = base.copy(fontSize = max, lineHeight = lineHeight), color = { color },
        maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = min, maxFontSize = max, stepSize = 0.25.sp),
    )
}
