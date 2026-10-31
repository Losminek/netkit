package cz.netkit.shared.update

/**
 * Privacy policy shown from About: the web page `<webBaseUrl>/privacy` when the build has a web
 * (`/netkit/privacy`, no trailing slash as on the website), otherwise the Markdown source in the
 * repository, Czech for a Czech locale and English for any other.
 */
fun privacyPolicyUrl(webBaseUrl: String?, sourceUrl: String, language: String): String {
    webBaseUrl?.let { return it.trimEnd('/') + "/privacy" }
    val suffix = if (language.lowercase() == "cs") "cs" else "en"
    return "${sourceUrl.trimEnd('/')}/blob/main/docs/public/privacy-$suffix.md"
}
