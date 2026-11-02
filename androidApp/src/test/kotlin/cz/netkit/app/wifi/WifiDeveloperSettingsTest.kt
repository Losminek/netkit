package cz.netkit.app.wifi

import android.provider.Settings
import kotlin.test.*

class WifiDeveloperSettingsTest {
    @Test fun disabledOrMissingToggleGoesStraightToAboutPhone() {
        assertEquals(listOf(Settings.ACTION_DEVICE_INFO_SETTINGS), wifiDeveloperSettingsActions(0))
    }

    @Test fun enabledToggleTriesDeveloperOptionsWithAboutPhoneFallback() {
        assertEquals(listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS),
            wifiDeveloperSettingsActions(1))
    }
}
