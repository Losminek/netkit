package cz.netkit.shared

import cz.netkit.shared.ios.IosAboutDomain
import kotlin.test.Test
import kotlin.test.assertEquals

class IosAboutDomainTest {
    @Test
    fun privacyUsesCzechForCzechLanguage() {
        val domain = IosAboutDomain()
        for (language in listOf("cs", "CS")) {
            assertEquals("https://www.daniel.losak.cz/netkit/privacy", domain.privacyUrl(language))
        }
    }

    @Test
    fun privacyUsesEnglishForEveryOtherLanguage() {
        val domain = IosAboutDomain()
        for (language in listOf("en", "de", "", "unknown")) {
            assertEquals("https://www.daniel.losak.cz/en/netkit/privacy", domain.privacyUrl(language))
        }
    }
}
