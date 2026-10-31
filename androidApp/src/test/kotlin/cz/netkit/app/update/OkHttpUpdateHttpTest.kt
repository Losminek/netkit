package cz.netkit.app.update

import cz.netkit.app.speed.LoopbackServer
import cz.netkit.app.speed.TestRequest
import cz.netkit.shared.update.HttpResult
import cz.netkit.shared.update.LATEST_JSON_MAX_BYTES
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.runBlocking

/** The transport alone, against a loopback HTTP server (the https-only policy is tested in `shared`). */
class OkHttpUpdateHttpTest {
    private val http = OkHttpUpdateHttp("NetKit/9.9.9")
    private val requests = CopyOnWriteArrayList<TestRequest>()

    /** TestNG reuses one instance for all methods: each server starts a fresh request log. */
    private fun server(respond: (TestRequest) -> String): LoopbackServer {
        requests.clear()
        return LoopbackServer { request, _, output, _ ->
            requests += request
            output.write(respond(request).toByteArray(Charsets.ISO_8859_1))
            output.flush()
            false
        }
    }

    private fun response(status: String, headers: List<String> = emptyList(), body: String = "") =
        (listOf("HTTP/1.1 $status", "Content-Length: ${body.toByteArray().size}", "Connection: close") + headers)
            .joinToString("\r\n") + "\r\n\r\n" + body

    @Test fun redirectIsReturnedNotFollowed() = runBlocking {
        server { response("302 Found", listOf("Location: /elsewhere", "Set-Cookie: track=1")) }.use { server ->
            val result = assertIs<HttpResult.Response>(http.get(server.base + "latest.json"))
            assertEquals(302, result.status)
            assertEquals("/elsewhere", result.location)
            assertNull(result.body)
            assertEquals(listOf("/latest.json"), requests.map { it.path }, "OkHttp did not follow the redirect")
        }
    }

    @Test fun bodyAndHeadersWithoutCookies() = runBlocking {
        server { response("200 OK", listOf("Set-Cookie: track=1", "Content-Type: application/json"), "{\"schema\":1}") }.use { server ->
            repeat(2) {
                val result = assertIs<HttpResult.Response>(http.get(server.base + "latest.json"))
                assertEquals(200, result.status)
                assertEquals("{\"schema\":1}", result.body?.decodeToString())
            }
            for (request in requests) {
                assertEquals("GET", request.method)
                assertEquals("NetKit/9.9.9", request.header("User-Agent"))
                assertEquals("application/json", request.header("Accept"))
                assertNull(request.header("Cookie"), "no cookie jar")
            }
        }
    }

    @Test fun otherStatusHasNoBody() = runBlocking {
        server { response("404 Not Found", body = "missing") }.use { server ->
            val result = assertIs<HttpResult.Response>(http.get(server.base + "latest.json"))
            assertEquals(404, result.status)
            assertNull(result.body)
        }
    }

    @Test fun bodyOverTheLimitIsTooLarge() = runBlocking {
        val big = "x".repeat(LATEST_JSON_MAX_BYTES + 1)
        server { response("200 OK", body = big) }.use { server ->
            assertEquals(HttpResult.Failure(HttpResult.Failure.Kind.TOO_LARGE), http.get(server.base + "a"))
        }
        // Without Content-Length (close-delimited) the reader stops at the limit as well.
        server { "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n$big" }.use { server ->
            assertEquals(HttpResult.Failure(HttpResult.Failure.Kind.TOO_LARGE), http.get(server.base + "b"))
        }
        val exact = "y".repeat(LATEST_JSON_MAX_BYTES)
        server { response("200 OK", body = exact) }.use { server ->
            assertEquals(LATEST_JSON_MAX_BYTES, assertIs<HttpResult.Response>(http.get(server.base + "c")).body?.size)
        }
    }

    @Test fun unreachableIsNetwork() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(HttpResult.Failure(HttpResult.Failure.Kind.NETWORK), http.get("http://127.0.0.1:$port/latest.json"))
        assertEquals(HttpResult.Failure(HttpResult.Failure.Kind.NETWORK), http.get("not a url"))
    }
}
