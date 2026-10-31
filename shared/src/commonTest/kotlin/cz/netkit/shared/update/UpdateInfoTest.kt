package cz.netkit.shared.update

import kotlin.test.*

class UpdateInfoTest {
    private val policy = UpdateUrlPolicy("https://www.example.com/netkit", "https://github.com/Losminek/netkit")
    private val sha = "ab".repeat(32)

    private fun manifest(
        versionName: String = "1.0.1", versionCode: String = "10001", minSdk: String = "29",
        pageUrl: String? = "\"https://www.example.com/netkit\"",
        apkUrl: String? = "\"https://github.com/Losminek/netkit/releases/download/v1.0.1/netkit-1.0.1.apk\"",
        extra: String = "",
    ) = buildString {
        append("""{"schema":1,"versionName":"$versionName","versionCode":$versionCode,"minSdk":$minSdk""")
        pageUrl?.let { append(""","pageUrl":$it""") }
        apkUrl?.let { append(""","apkUrl":$it""") }
        append(extra)
        append("}")
    }

    private fun ok(body: String): UpdateInfo = when (val result = parseLatestJson(body, policy)) {
        is UpdateParseResult.Ok -> result.info
        is UpdateParseResult.Rejected -> fail("rejected: $result")
    }

    private fun rejected(body: String): UpdateParseResult.Rejected =
        parseLatestJson(body, policy) as? UpdateParseResult.Rejected ?: fail("accepted: $body")

    @Test fun parsesFullManifestOfThePlan() {
        val info = ok(manifest(extra = ""","released":"2026-11-02","sha256":"$sha","certSha256":"${"AB:".repeat(31)}AB",""" +
            """"notes":{"cs":"Krátké shrnutí","en":"Short summary"},"futureField":{"x":1}"""))
        assertEquals("1.0.1", info.versionName)
        assertEquals(10001, info.versionCode)
        assertEquals(29, info.minSdk)
        assertEquals("https://www.example.com/netkit", info.openUrl)
        assertEquals("2026-11-02", info.released)
        assertEquals(sha, info.sha256)
        assertEquals(sha, info.certSha256, "colon form is normalized to lowercase hex")
        assertEquals("Krátké shrnutí", info.notesFor("cs"))
        assertEquals("Short summary", info.notesFor("de"), "falls back to English")
    }

    @Test fun apkUrlAloneIsEnoughAndBecomesTheLink() {
        val info = ok(manifest(pageUrl = null))
        assertEquals(info.apkUrl, info.openUrl)
    }

    @Test fun rejectsInvalidJson() {
        for (body in listOf("", "not json", "{", "[]", "\"text\"", "{\"schema\":1,}", "null")) {
            assertEquals(UpdateParseError.INVALID_JSON, rejected(body).error, body)
        }
    }

    @Test fun rejectsMissingFields() {
        assertEquals("schema", rejected("""{"versionName":"1.0.0","versionCode":10000,"minSdk":29,"pageUrl":"https://www.example.com/netkit"}""").field)
        assertEquals(UpdateParseError.MISSING_FIELD, rejected("""{"schema":1,"versionCode":10000,"minSdk":29}""").error)
        assertEquals("versionCode", rejected("""{"schema":1,"versionName":"1.0.0","minSdk":29}""").field)
        assertEquals("minSdk", rejected("""{"schema":1,"versionName":"1.0.0","versionCode":10000}""").field)
        val noLink = rejected(manifest(pageUrl = null, apkUrl = null))
        assertEquals(UpdateParseError.MISSING_FIELD, noLink.error)
        assertEquals(UpdateParseError.MISSING_FIELD, rejected(manifest(pageUrl = "null", apkUrl = "null")).error)
    }

    @Test fun rejectsWrongTypesAndFormats() {
        assertEquals(UpdateParseError.INVALID_FIELD, rejected(manifest(versionCode = "\"10001\"")).error)
        assertEquals(UpdateParseError.INVALID_FIELD, rejected(manifest(versionCode = "10001.5")).error)
        assertEquals(UpdateParseError.INVALID_FIELD, rejected(manifest(minSdk = "0")).error)
        assertEquals(UpdateParseError.INVALID_FIELD, rejected(manifest(versionName = "1.0", versionCode = "100")).error)
        assertEquals(UpdateParseError.INVALID_FIELD, rejected(manifest(versionName = "1.0.1-rc1")).error)
        assertEquals(UpdateParseError.INVALID_FIELD, rejected(manifest(versionName = "1.100.0", versionCode = "20000")).error)
        assertEquals(UpdateParseError.INVALID_FIELD, rejected(manifest(pageUrl = "42")).error)
        assertEquals("sha256", rejected(manifest(extra = ""","sha256":"abc"""")).field)
        assertEquals("released", rejected(manifest(extra = ""","released":"2.11.2026"""")).field)
        assertEquals("notes", rejected(manifest(extra = ""","notes":"text"""")).field)
        assertEquals("notes", rejected(manifest(extra = ""","notes":{"cs":1}""")).field)
        assertEquals("notes", rejected(manifest(extra = ""","notes":{"<script>":"x"}""")).field)
    }

    @Test fun unsupportedSchemaIsNotGuessed() {
        assertEquals(UpdateParseError.UNSUPPORTED_SCHEMA, rejected(manifest().replace("\"schema\":1", "\"schema\":2")).error)
    }

    @Test fun versionCodeMustMatchVersionName() {
        assertEquals(UpdateParseError.VERSION_MISMATCH, rejected(manifest(versionCode = "99999")).error)
        assertEquals(10000, versionCodeOf("1.0.0"))
        assertEquals(700, versionCodeOf("0.7.0"))
        assertEquals(12345, versionCodeOf("1.23.45"))
        assertNull(versionCodeOf("01.0.0"))
        assertNull(versionCodeOf("1.0.0-dev"))
        assertNull(versionCodeOf("v1.0.0"))
    }

    @Test fun rejectsForeignAndMalformedHosts() {
        val bad = listOf(
            "http://www.example.com/netkit",
            "https://evil.example.org/netkit",
            "https://github.com.evil.example/x",
            "https://evilgithub.com/x",
            "https://objects.githubusercontent.com/x",
            "https://example.com/netkit",
            "https://www.example.com@evil.example/x",
            "https://user@github.com/x",
            "https://github.com:8443/x",
            "https://GITHUB.com/x",
            "https://github.com/x#frag",
            "https://github.com/a b",
            "https://github.com/á",
            "javascript:alert(1)",
            "intent://github.com/#Intent;end",
            "file:///sdcard/x.apk",
            "https://github.com/" + "a".repeat(UPDATE_URL_MAX_CHARS),
        )
        for (url in bad) {
            assertFalse(policy.isAllowed(url), url)
            val result = rejected(manifest(pageUrl = "\"${url.replace("\\", "\\\\")}\""))
            assertEquals(UpdateParseError.URL_NOT_ALLOWED, result.error, url)
            assertEquals("pageUrl", result.field)
        }
        assertEquals(UpdateParseError.URL_NOT_ALLOWED, rejected(manifest(apkUrl = "\"https://evil.example.org/a.apk\"")).error)
    }

    @Test fun allowsWebHostAndGithubOnly() {
        assertTrue(policy.isAllowed("https://www.example.com/netkit"))
        assertTrue(policy.isAllowed("https://www.example.com:443/netkit/?from=app"))
        assertTrue(policy.isAllowed("https://github.com/Losminek/netkit/releases/tag/v1.0.1"))
        assertEquals("www.example.com", policy.webHost)
        assertEquals("https://www.example.com/netkit/latest.json", policy.latestJsonUrl)
        assertFailsWith<IllegalArgumentException> { UpdateUrlPolicy("http://www.example.com/netkit") }
    }

    @Test fun pageUrlWithoutTrailingSlashIsTheWebPage() {
        // The website uses Astro `trailingSlash: 'never'`: latest.json carries https://www.<domain>/netkit.
        val info = ok(manifest(pageUrl = "\"https://www.example.com/netkit\""))
        assertEquals("https://www.example.com/netkit", info.pageUrl)
        assertEquals("https://www.example.com/netkit/privacy", ok(manifest(pageUrl = "\"https://www.example.com/netkit/privacy\"")).pageUrl)
    }

    @Test fun githubLinksStayInsideTheExactConfiguredRepository() {
        val root = "https://github.com/Losminek/netkit"
        for (url in listOf("$root/", "$root/releases/tag/v1.0.1",
            "$root/releases/download/v1.0.1/netkit-1.0.1.apk?download=1",
            "https://github.com:443/Losminek/netkit/releases/tag/v1.0.1")) {
            assertTrue(policy.isAllowed(url), url)
        }
        val foreign = listOf(
            "https://github.com/other/netkit/releases/tag/v1.0.1",
            "https://github.com/Losminek/other/releases/tag/v1.0.1",
            "https://github.com/Losminek/netkit-evil/releases/tag/v1.0.1",
            "https://github.com/Losminek-evil/netkit/releases/tag/v1.0.1",
            "https://github.com/losminek/netkit/releases/tag/v1.0.1",
            "https://github.com/Losminek/NetKit/releases/tag/v1.0.1",
            "$root/../x", "$root/releases/../../other", "$root/./releases",
            "$root/%2e%2E/x", "$root/.%2e/x", "$root/%2E./x",
            "$root/releases%2F..%2f..%2Fother", "$root/%5C..%5cother",
            "$root/%252e%252e/x", "$root/..\\other",
            root, "https://github.com?path=/Losminek/netkit/", "$root?path=/releases/",
        )
        for (web in listOf("https://www.example.com/netkit", null)) {
            val scoped = UpdateUrlPolicy(web, root)
            for (url in foreign) {
                assertFalse(scoped.isAllowed(url), url)
                val quoted = kotlinx.serialization.json.JsonPrimitive(url).toString()
                for (body in listOf(manifest(pageUrl = quoted), manifest(apkUrl = quoted))) {
                    assertEquals(UpdateParseError.URL_NOT_ALLOWED,
                        assertIs<UpdateParseResult.Rejected>(parseLatestJson(body, scoped), url).error)
                }
            }
        }
        val info = ok(manifest())
        assertEquals("$root/releases/tag/v1.0.1", info.releasePageUrl)
        assertTrue(policy.isAllowed(info.releasePageUrl!!))
        // SOURCE_URL, not a hard-coded repository, defines the allowed prefix.
        val alternate = UpdateUrlPolicy(null, "https://github.com/owner/repo")
        assertTrue(alternate.isAllowed("https://github.com/owner/repo/releases/"))
        assertFalse(alternate.isAllowed("$root/releases/"))
    }

    @Test fun missingOrInvalidSourceNeverAllowsGithubLinks() {
        for (source in listOf(null, "", "https://gitlab.com/a/b", "https://github.com/a/b/", "https://github.com/a/..")) {
            for (web in listOf(null, "https://www.example.com/netkit", "https://github.com")) {
                val withoutRepo = UpdateUrlPolicy(web, source)
                assertNull(withoutRepo.github)
                assertFalse(withoutRepo.isAllowed("https://github.com/Losminek/netkit/releases/"))
                assertIs<UpdateParseResult.Rejected>(parseLatestJson(manifest(), withoutRepo))
            }
        }
        val webOnly = UpdateUrlPolicy("https://www.example.com/netkit")
        assertTrue(webOnly.isAllowed("https://www.example.com/other/path?x=1"))
        assertIs<UpdateParseResult.Ok>(parseLatestJson(manifest(apkUrl = null), webOnly))
    }

    @Test fun withoutWebTheForeignPageIsDroppedAndGithubKept() {
        val noWeb = UpdateUrlPolicy(null, "https://github.com/Losminek/netkit")
        assertNull(noWeb.webHost)
        assertNull(noWeb.latestJsonUrl)
        val info = assertIs<UpdateParseResult.Ok>(parseLatestJson(manifest(), noWeb)).info
        assertNull(info.pageUrl, "www.example.com is unknown to this build")
        assertEquals(info.apkUrl, info.openUrl)
        assertFalse(noWeb.isAllowed("https://www.example.com/netkit"))

        // Only an unknown page left: the GitHub release page of the version is opened instead.
        val pageOnly = assertIs<UpdateParseResult.Ok>(parseLatestJson(manifest(apkUrl = null), noWeb)).info
        assertEquals("https://github.com/Losminek/netkit/releases/tag/v1.0.1", pageOnly.openUrl)

        // Without a GitHub repo nothing usable remains.
        val nothing = UpdateUrlPolicy(null, "https://gitlab.com/x/y")
        assertEquals(UpdateParseError.URL_NOT_ALLOWED,
            assertIs<UpdateParseResult.Rejected>(parseLatestJson(manifest(apkUrl = null), nothing)).error)
    }

    @Test fun withoutWebMalformedLinksStillRejectTheDocument() {
        val noWeb = UpdateUrlPolicy(null, "https://github.com/Losminek/netkit")
        for (url in listOf("http://www.example.com/netkit", "https://user@github.com/x", "https://github.com:8443/x",
            "https://github.com/x#frag", "javascript:alert(1)", "https://GITHUB.com/x")) {
            val result = assertIs<UpdateParseResult.Rejected>(parseLatestJson(manifest(pageUrl = "\"$url\""), noWeb), url)
            assertEquals(UpdateParseError.URL_NOT_ALLOWED, result.error, url)
        }
        assertEquals(UpdateParseError.URL_NOT_ALLOWED, assertIs<UpdateParseResult.Rejected>(
            parseLatestJson(manifest(apkUrl = "\"http://github.com/a.apk\""), noWeb)).error)
    }

    @Test fun configuredWebStillRejectsForeignHosts() {
        val withRepo = UpdateUrlPolicy("https://www.example.com/netkit", "https://github.com/Losminek/netkit")
        assertEquals(UpdateParseError.URL_NOT_ALLOWED,
            assertIs<UpdateParseResult.Rejected>(parseLatestJson(manifest(pageUrl = "\"https://evil.example.org/\""), withRepo)).error)
        val info = assertIs<UpdateParseResult.Ok>(parseLatestJson(manifest(), withRepo)).info
        assertEquals("https://www.example.com/netkit", info.openUrl, "page first")
        assertEquals("https://github.com/Losminek/netkit/releases/tag/v1.0.1", info.releasePageUrl)
    }

    @Test fun endpointsInOrder() {
        val both = UpdateUrlPolicy("https://www.example.com/netkit", "https://github.com/Losminek/netkit").endpoints
        assertEquals(listOf(UpdateSource.WEB, UpdateSource.GITHUB), both.map { it.source })
        assertEquals("https://www.example.com/netkit/latest.json", both[0].url)
        assertEquals(setOf("www.example.com"), both[0].allowedHosts)
        assertEquals("https://github.com/Losminek/netkit/releases/latest/download/latest.json", both[1].url)
        assertEquals(UPDATE_GITHUB_FETCH_HOSTS, both[1].allowedHosts)
        assertEquals(listOf(UpdateSource.GITHUB), UpdateUrlPolicy(null, "https://github.com/Losminek/netkit").endpoints.map { it.source })
        assertEquals(listOf(UpdateSource.WEB), UpdateUrlPolicy("https://www.example.com/netkit", "https://gitlab.com/a/b").endpoints.map { it.source })
        assertTrue(UpdateUrlPolicy(null, null).endpoints.isEmpty())
    }

    @Test fun githubRepoIsParsedStrictly() {
        assertEquals("https://github.com/Losminek/netkit", GithubRepo.parse("https://github.com/Losminek/netkit")?.url)
        assertEquals("https://github.com/a-b/c.d_e", GithubRepo.parse("https://github.com/a-b/c.d_e")?.url)
        for (url in listOf("https://github.com/Losminek/netkit/", "https://github.com/Losminek", "https://github.com/Losminek/netkit/tree/main",
            "http://github.com/Losminek/netkit", "https://github.com.evil/Losminek/netkit", "https://www.github.com/a/b",
            "https://github.com/a/b.git", "https://github.com/-a/b", "https://github.com/a/..", "https://github.com/a/b?x=1",
            "https://github.com/a/b#x", "https://github.com/a b/c", "https://gitlab.com/a/b", "")) {
            assertNull(GithubRepo.parse(url), url)
        }
    }

    @Test fun sizeLimitIsInUtf8Bytes() {
        fun padded(pad: String) = manifest(extra = ""","pad":"$pad"""")
        val room = LATEST_JSON_MAX_BYTES - padded("").length
        val fits = padded("a".repeat(room))
        assertEquals(LATEST_JSON_MAX_BYTES, fits.encodeToByteArray().size)
        assertIs<UpdateParseResult.Ok>(parseLatestJson(fits, policy))
        assertEquals(UpdateParseError.TOO_LARGE, rejected(padded("a".repeat(room + 1))).error)
        assertEquals(UpdateParseError.TOO_LARGE, rejected(padded("ž".repeat(room / 2 + 1))).error)
    }

    @Test fun deepNestingIsRejectedWithoutCrash() {
        val depth = LATEST_JSON_MAX_BYTES / 2 - 1
        assertEquals(UpdateParseError.INVALID_JSON, rejected("[".repeat(depth) + "]".repeat(depth)).error)
        assertEquals(UpdateParseError.INVALID_JSON, rejected("[".repeat(LATEST_JSON_MAX_BYTES)).error)
        assertEquals(UpdateParseError.INVALID_JSON, rejected(manifest(extra = ""","x":${"[".repeat(20)}${"]".repeat(20)}""")).error)
        // Brackets inside strings (also after an escaped quote) do not count.
        ok(manifest(extra = ""","x":"\"${"[".repeat(40)}","y":{"z":[1]}"""))
    }

    @Test fun notesAreSanitizedAndBounded() {
        val info = ok(manifest(extra = ""","notes":{"cs":"  řádek\n\u202edruhý\t\u0000konec  ","en":"${"x".repeat(900)}","de":"  "}"""))
        assertEquals("řádek druhý konec", info.notes["cs"])
        assertEquals(UPDATE_NOTES_MAX_CHARS, info.notes.getValue("en").length)
        assertTrue(info.notes.getValue("en").endsWith("…"))
        assertFalse("de" in info.notes, "blank notes are dropped")
    }

    @Test fun statusComparesVersionCodes() {
        val info = ok(manifest())
        assertEquals(UpdateStatus.AVAILABLE, updateStatus(info, currentVersionCode = 10000, deviceSdk = 35, dismissedVersionCode = null))
        assertEquals(UpdateStatus.UP_TO_DATE, updateStatus(info, currentVersionCode = 10001, deviceSdk = 35, dismissedVersionCode = null))
        assertEquals(UpdateStatus.UP_TO_DATE, updateStatus(info, currentVersionCode = 10100, deviceSdk = 35, dismissedVersionCode = null))
        assertEquals(UpdateStatus.AVAILABLE, updateStatus(info, currentVersionCode = 700, deviceSdk = 29, dismissedVersionCode = null))
    }

    @Test fun statusRespectsMinSdkAndDismissal() {
        val info = ok(manifest(minSdk = "36"))
        assertEquals(UpdateStatus.REQUIRES_NEWER_ANDROID, updateStatus(info, 10000, deviceSdk = 35, dismissedVersionCode = null))
        assertEquals(UpdateStatus.AVAILABLE, updateStatus(info, 10000, deviceSdk = 36, dismissedVersionCode = null))
        assertEquals(UpdateStatus.DISMISSED, updateStatus(info, 10000, deviceSdk = 36, dismissedVersionCode = 10001))
        assertEquals(UpdateStatus.AVAILABLE, updateStatus(info, 10000, deviceSdk = 36, dismissedVersionCode = 10000),
            "a closed card comes back for the next version")
    }

    @Test fun checkIsDueOncePerDay() {
        val day = UPDATE_CHECK_INTERVAL_MS
        assertTrue(isUpdateCheckDue(null, 1_000))
        assertFalse(isUpdateCheckDue(10 * day, 10 * day + day - 1))
        assertTrue(isUpdateCheckDue(10 * day, 11 * day))
        assertTrue(isUpdateCheckDue(10 * day, 9 * day), "clock moved backwards")
    }
}
