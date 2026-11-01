package cz.netkit.shared.update

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectLinksTest {
    private val source = "https://github.com/Losminek/netkit"

    @Test fun webPageWhenConfigured() {
        assertEquals("https://www.example.com/netkit/privacy", privacyPolicyUrl("https://www.example.com/netkit", source, "cs"))
        assertEquals("https://www.example.com/en/netkit/privacy", privacyPolicyUrl("https://www.example.com/netkit/", source, "en"))
        assertEquals("https://www.example.com/en/netkit/privacy", privacyPolicyUrl("https://www.example.com/netkit", source, "sk"))
        assertEquals("https://www.example.com/netkit/privacy", privacyPolicyUrl("https://www.example.com/netkit", source, "CS"))
    }

    @Test fun webPagesGetEnglishPathOutsideCzech() {
        val web = "https://www.example.com/netkit"
        assertEquals("https://www.example.com/en/netkit", localizedWebUrl(web, web, "en"))
        assertEquals("https://www.example.com/en/netkit/privacy", localizedWebUrl("$web/privacy", "$web/", "de"))
        assertEquals(web, localizedWebUrl(web, web, "cs"))
        assertEquals(web, localizedWebUrl(web, null, "en"))
    }

    @Test fun otherUrlsStayUnchanged() {
        val web = "https://www.example.com/netkit"
        val apk = "$source/releases/download/v1.0.0/netkit-1.0.0.apk"
        assertEquals(apk, localizedWebUrl(apk, web, "en"))
        assertEquals("https://www.example.com/netkitx", localizedWebUrl("https://www.example.com/netkitx", web, "en"))
        assertEquals("https://www.example.com/other", localizedWebUrl("https://www.example.com/other", web, "en"))
        assertEquals("https://www.example.com", localizedWebUrl("https://www.example.com", "https://www.example.com", "en"))
    }

    @Test fun repositoryMarkdownByLanguageOtherwise() {
        assertEquals("$source/blob/main/docs/public/privacy-cs.md", privacyPolicyUrl(null, source, "cs"))
        assertEquals("$source/blob/main/docs/public/privacy-cs.md", privacyPolicyUrl(null, source, "CS"))
        assertEquals("$source/blob/main/docs/public/privacy-en.md", privacyPolicyUrl(null, source, "en"))
        assertEquals("$source/blob/main/docs/public/privacy-en.md", privacyPolicyUrl(null, source, "sk"))
    }
}
