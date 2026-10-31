package cz.netkit.shared.update

import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class) // currentTime of the virtual clock
class UpdateFetchTest {
    private val web = "https://www.example.com/netkit"
    private val source = "https://github.com/Losminek/netkit"
    private val webJson = "$web/latest.json"
    private val ghJson = "$source/releases/latest/download/latest.json"
    private val ghTag = "$source/releases/download/v1.0.1/latest.json"
    private val ghAsset = "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sp=r&sv=2018-11-09&se=2026-10-29T12%3A00%3A00Z&sig=x%2By%3D"

    private fun manifest(versionName: String = "1.0.1", versionCode: Int = 10001, pageUrl: String = web) =
        """{"schema":1,"versionName":"$versionName","versionCode":$versionCode,"minSdk":29,"pageUrl":"$pageUrl",""" +
            """"apkUrl":"$source/releases/download/v$versionName/netkit-$versionName.apk"}"""

    private fun ok(body: String) = HttpResult.Response(200, body = body.encodeToByteArray())
    private fun redirect(location: String?, status: Int = 302) = HttpResult.Response(status, location = location)
    private val notFound = HttpResult.Response(404)
    private val down = HttpResult.Failure(HttpResult.Failure.Kind.NETWORK)

    /** Answers from [routes]; an unknown URL is a test failure, every request is recorded. */
    private class FakeHttp(private val routes: Map<String, HttpResult>, private val delays: Map<String, Long> = emptyMap()) : UpdateHttp {
        val requested = mutableListOf<String>()
        override suspend fun get(url: String): HttpResult {
            requested += url
            delays[url]?.let { delay(it) }
            return routes[url] ?: fail("unexpected request $url")
        }
    }

    private val githubChain = mapOf(ghJson to redirect(ghTag), ghTag to redirect(ghAsset), ghAsset to ok(manifest()))

    // --- Orchestration ---------------------------------------------------------------------------

    @Test fun webAnswersFirst() = runTest {
        val http = FakeHttp(mapOf(webJson to ok(manifest())))
        val outcome = assertIs<UpdateCheckOutcome.Found>(checkForUpdate(UpdateUrlPolicy(web, source), http))
        assertEquals(UpdateSource.WEB, outcome.source)
        assertEquals("1.0.1", outcome.info.versionName)
        assertEquals(manifest(), outcome.body)
        assertEquals(listOf(webJson), http.requested, "GitHub is not asked when the web answers")
    }

    @Test fun web404FallsBackToGithubThroughTwoRedirects() = runTest {
        val http = FakeHttp(mapOf(webJson to notFound) + githubChain)
        val outcome = assertIs<UpdateCheckOutcome.Found>(checkForUpdate(UpdateUrlPolicy(web, source), http))
        assertEquals(UpdateSource.GITHUB, outcome.source)
        assertEquals(listOf(webJson, ghJson, ghTag, ghAsset), http.requested)
        assertEquals(web, outcome.info.openUrl, "the link policy does not depend on the source")
    }

    @Test fun invalidWebDocumentFallsBackToGithub() = runTest {
        for (bad in listOf(ok("<html>maintenance</html>"), ok(manifest(pageUrl = "https://evil.example.org/x")),
            HttpResult.Response(200, body = byteArrayOf(0x7b, 0xff.toByte(), 0x7d)),
            HttpResult.Failure(HttpResult.Failure.Kind.TOO_LARGE), HttpResult.Response(200, body = ByteArray(LATEST_JSON_MAX_BYTES + 1)),
            HttpResult.Response(500), HttpResult.Response(304), down)) {
            val http = FakeHttp(mapOf(webJson to bad) + githubChain)
            val outcome = assertIs<UpdateCheckOutcome.Found>(checkForUpdate(UpdateUrlPolicy(web, source), http))
            assertEquals(UpdateSource.GITHUB, outcome.source)
        }
    }

    @Test fun webMayRedirectOnItsOwnHostOnly() = runTest {
        val moved = "https://www.example.com/new/latest.json"
        val sameHost = FakeHttp(mapOf(webJson to redirect("/new/latest.json", 301), moved to ok(manifest())))
        assertEquals(UpdateSource.WEB, assertIs<UpdateCheckOutcome.Found>(checkForUpdate(UpdateUrlPolicy(web, source), sameHost)).source)
        assertEquals(listOf(webJson, moved), sameHost.requested)

        // A redirect from the web to GitHub (or anywhere else) is not followed; GitHub is then asked directly.
        val foreign = FakeHttp(mapOf(webJson to redirect(ghJson)) + githubChain)
        val outcome = assertIs<UpdateCheckOutcome.Found>(checkForUpdate(UpdateUrlPolicy(web, source), foreign))
        assertEquals(UpdateSource.GITHUB, outcome.source)
        assertEquals(listOf(webJson, ghJson, ghTag, ghAsset), foreign.requested)
    }

    @Test fun foreignRedirectFromGithubIsRejected() = runTest {
        val evil = "https://evil.example.org/latest.json"
        val http = FakeHttp(mapOf(webJson to notFound, ghJson to redirect(ghTag), ghTag to redirect(evil)))
        val outcome = assertIs<UpdateCheckOutcome.Failed>(checkForUpdate(UpdateUrlPolicy(web, source), http))
        assertEquals(UpdateCheckFailure.REDIRECT, outcome.failure)
        assertFalse(evil in http.requested)
        assertEquals(listOf(UpdateAttempt(UpdateSource.WEB, UpdateCheckFailure.HTTP_STATUS, 0),
            UpdateAttempt(UpdateSource.GITHUB, UpdateCheckFailure.REDIRECT, 1)), outcome.attempts)
    }

    @Test fun redirectLoopStopsAfterThreeHops() = runTest {
        val a = "$source/releases/download/a"
        val b = "$source/releases/download/b"
        val http = FakeHttp(mapOf(ghJson to redirect(a), a to redirect(b), b to redirect(a)))
        val outcome = assertIs<UpdateCheckOutcome.Failed>(checkForUpdate(UpdateUrlPolicy(null, source), http))
        assertEquals(UpdateCheckFailure.REDIRECT, outcome.failure)
        assertEquals(listOf(ghJson, a, b, a), http.requested, "three redirects followed, the fourth refused")
        assertEquals(3, outcome.attempts.single().hops)
    }

    @Test fun bothDownIsUnreachable() = runTest {
        val http = FakeHttp(mapOf(webJson to down, ghJson to down))
        val outcome = assertIs<UpdateCheckOutcome.Failed>(checkForUpdate(UpdateUrlPolicy(web, source), http))
        assertEquals(UpdateCheckFailure.NETWORK, outcome.failure)
        assertEquals(2, outcome.attempts.size)
    }

    @Test fun answeredFailureWinsOverNetwork() = runTest {
        val http = FakeHttp(mapOf(webJson to ok("{}"), ghJson to down))
        assertEquals(UpdateCheckFailure.INVALID,
            assertIs<UpdateCheckOutcome.Failed>(checkForUpdate(UpdateUrlPolicy(web, source), http)).failure)
        val noRelease = FakeHttp(mapOf(webJson to down, ghJson to notFound))
        assertEquals(UpdateCheckFailure.HTTP_STATUS,
            assertIs<UpdateCheckOutcome.Failed>(checkForUpdate(UpdateUrlPolicy(web, source), noRelease)).failure)
    }

    @Test fun withoutWebOnlyGithubIsAsked() = runTest {
        val http = FakeHttp(githubChain)
        val policy = UpdateUrlPolicy(null, source)
        val outcome = assertIs<UpdateCheckOutcome.Found>(checkForUpdate(policy, http))
        assertEquals(UpdateSource.GITHUB, outcome.source)
        assertEquals(ghJson, http.requested.first())
        assertNull(outcome.info.pageUrl, "the unknown web host of the document is dropped")
        assertEquals("$source/releases/download/v1.0.1/netkit-1.0.1.apk", outcome.info.openUrl)
    }

    @Test fun nothingConfiguredSendsNothing() = runTest {
        val http = FakeHttp(emptyMap())
        val outcome = assertIs<UpdateCheckOutcome.Failed>(checkForUpdate(UpdateUrlPolicy(null, "https://gitlab.com/x/y"), http))
        assertEquals(UpdateCheckFailure.NETWORK, outcome.failure)
        assertTrue(http.requested.isEmpty())
    }

    @Test fun eachSourceHasFiveSecondsIncludingHops() = runTest {
        // The web hangs: after 5 s GitHub is asked; GitHub's hops share its own 5 s.
        val http = FakeHttp(mapOf(webJson to ok(manifest())) + githubChain,
            delays = mapOf(webJson to 60_000, ghJson to 1_500, ghTag to 1_500, ghAsset to 1_500))
        val outcome = assertIs<UpdateCheckOutcome.Found>(checkForUpdate(UpdateUrlPolicy(web, source), http))
        assertEquals(UpdateSource.GITHUB, outcome.source)
        assertEquals(9_500, currentTime)

        val slowHops = FakeHttp(mapOf(webJson to notFound) + githubChain,
            delays = mapOf(ghJson to 2_000, ghTag to 2_000, ghAsset to 2_000))
        val failed = assertIs<UpdateCheckOutcome.Failed>(checkForUpdate(UpdateUrlPolicy(web, source), slowHops))
        assertEquals(UpdateCheckFailure.HTTP_STATUS, failed.failure, "the web answered 404, GitHub timed out")
        assertEquals(UpdateAttempt(UpdateSource.GITHUB, UpdateCheckFailure.NETWORK, 2), failed.attempts.last())
    }

    @Test fun overallBudgetIsTenSeconds() = runTest {
        val http = FakeHttp(mapOf(webJson to down, ghJson to down), delays = mapOf(webJson to 99_000, ghJson to 99_000))
        val start = currentTime
        assertIs<UpdateCheckOutcome.Failed>(checkForUpdate(UpdateUrlPolicy(web, source), http))
        assertEquals(2 * UPDATE_SOURCE_TIMEOUT_MS, currentTime - start)
    }

    // --- Redirect policy -------------------------------------------------------------------------

    private val gh = UPDATE_GITHUB_FETCH_HOSTS

    private fun follow(location: String?, current: String = ghJson, hosts: Set<String> = gh, hop: Int = 0) =
        assertIs<RedirectDecision.Follow>(nextRedirect(current, location, hosts, hop)).url

    private fun reject(location: String?, current: String = ghJson, hosts: Set<String> = gh, hop: Int = 0) =
        assertIs<RedirectDecision.Reject>(nextRedirect(current, location, hosts, hop), "$location").reason

    @Test fun followsTheGithubChain() {
        assertEquals(ghTag, follow(ghTag))
        assertEquals(ghAsset, follow(ghAsset, current = ghTag, hop = 1))
        assertEquals("https://objects.githubusercontent.com/x?y=1", follow("https://objects.githubusercontent.com/x?y=1", hop = 2))
        assertEquals("https://github.com:443/a", follow("https://github.com:443/a"))
    }

    @Test fun absolutePathKeepsTheOrigin() {
        assertEquals("https://github.com/x/y", follow("/x/y"))
        assertEquals("https://www.example.com/n/latest.json",
            follow("/n/latest.json", current = "https://www.example.com:443/netkit/latest.json", hosts = setOf("www.example.com")))
    }

    @Test fun rejectsLookalikesAndForeignHosts() {
        for (location in listOf("https://github.com.evil/x", "https://evilgithub.com/x", "https://github.com.evil.example/x",
            "https://release-assets.githubusercontent.com.evil.example/x", "https://githubusercontent.com/x",
            "https://api.github.com/x", "https://www.example.com/x")) {
            assertEquals(RedirectRejection.HOST_NOT_ALLOWED, reject(location), location)
        }
        assertEquals(RedirectRejection.HOST_NOT_ALLOWED,
            reject(ghJson, current = webJson, hosts = setOf("www.example.com")), "web source stays on the web host")
    }

    @Test fun rejectsDowngradesAndOtherSchemes() {
        for (location in listOf("http://github.com/x", "HTTP://github.com/x", "ftp://github.com/x", "javascript:alert(1)",
            "intent://github.com/#Intent;end", "file:///sdcard/x")) {
            assertEquals(RedirectRejection.NOT_HTTPS, reject(location), location)
        }
    }

    @Test fun rejectsMalformedTargets() {
        for (location in listOf("https://github.com@evil.com/", "https://user:pw@github.com/x", "https://github.com:8443/x",
            "https://github.com:80/x", "//evil.com/x", "x/y", "../x", "?q=1", "#frag", "https://github.com/x#frag",
            "https://github.com/a b", "https://GITHUB.com/x", "https://github.com/á", "https://", "https:///x",
            "https://github.com/" + "a".repeat(UPDATE_REDIRECT_URL_MAX_CHARS))) {
            assertEquals(RedirectRejection.MALFORMED, reject(location), location)
        }
    }

    @Test fun rejectsMissingLocationAndTooManyHops() {
        assertEquals(RedirectRejection.MISSING_LOCATION, reject(null))
        assertEquals(RedirectRejection.MISSING_LOCATION, reject(""))
        assertEquals(ghTag, follow(ghTag, hop = UPDATE_MAX_REDIRECTS - 1))
        assertEquals(RedirectRejection.TOO_MANY_HOPS, reject(ghTag, hop = UPDATE_MAX_REDIRECTS))
    }
}
