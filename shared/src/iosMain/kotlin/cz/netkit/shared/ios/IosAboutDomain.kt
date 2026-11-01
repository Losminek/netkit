package cz.netkit.shared.ios

import cz.netkit.shared.update.privacyPolicyUrl

/**
 * Fixed project links for About; the caller supplies the current language code.
 * The Czech base is passed on purpose: [privacyPolicyUrl] maps it to `/en/…` for other languages.
 */
class IosAboutDomain {
    fun privacyUrl(language: String): String =
        privacyPolicyUrl("https://www.daniel.losak.cz/netkit", "https://github.com/Losminek/netkit", language)
}
