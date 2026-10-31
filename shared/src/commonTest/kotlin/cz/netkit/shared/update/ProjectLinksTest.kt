package cz.netkit.shared.update

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectLinksTest {
    private val source = "https://github.com/Losminek/netkit"

    @Test fun webPageWhenConfigured() {
        assertEquals("https://www.example.com/netkit/privacy", privacyPolicyUrl("https://www.example.com/netkit", source, "cs"))
        assertEquals("https://www.example.com/netkit/privacy", privacyPolicyUrl("https://www.example.com/netkit/", source, "en"))
    }

    @Test fun repositoryMarkdownByLanguageOtherwise() {
        assertEquals("$source/blob/main/docs/public/privacy-cs.md", privacyPolicyUrl(null, source, "cs"))
        assertEquals("$source/blob/main/docs/public/privacy-cs.md", privacyPolicyUrl(null, source, "CS"))
        assertEquals("$source/blob/main/docs/public/privacy-en.md", privacyPolicyUrl(null, source, "en"))
        assertEquals("$source/blob/main/docs/public/privacy-en.md", privacyPolicyUrl(null, source, "sk"))
    }
}
