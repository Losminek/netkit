package cz.netkit.shared.update

/**
 * Privacy policy shown from About: the web page `<webBaseUrl>/privacy` when the build has a web
 * (`/netkit/privacy`, no trailing slash as on the website; `/en/netkit/privacy` for a non-Czech
 * locale, see [localizedWebUrl]), otherwise the Markdown source in the repository, Czech for a Czech
 * locale and English for any other.
 */
fun privacyPolicyUrl(webBaseUrl: String?, sourceUrl: String, language: String): String {
    webBaseUrl?.let {
        val base = it.trimEnd('/')
        return localizedWebUrl("$base/privacy", base, language)
    }
    val suffix = if (language.lowercase() == "cs") "cs" else "en"
    return "${sourceUrl.trimEnd('/')}/blob/main/docs/public/privacy-$suffix.md"
}

/**
 * English counterpart of a page on the author's web for a non-Czech UI: `<origin>/netkit…` becomes
 * `<origin>/en/netkit…` (the website keeps both paths stable; the Czech page stays binding). Only [url]
 * equal to [webBaseUrl] or below it changes; anything else (GitHub, APK) and every URL for a Czech
 * locale is returned unchanged. `latest.json` keeps the Czech `pageUrl`; this maps it on display.
 */
fun localizedWebUrl(url: String, webBaseUrl: String?, language: String): String {
    if (webBaseUrl == null || language.lowercase() == "cs") return url
    val base = webBaseUrl.trimEnd('/')
    if (url != base && !url.startsWith("$base/")) return url
    val hostStart = base.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return url
    val pathStart = base.indexOf('/', hostStart).takeIf { it >= 0 } ?: return url
    return base.substring(0, pathStart) + "/en" + url.substring(pathStart)
}
