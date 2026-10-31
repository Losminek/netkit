package cz.netkit.shared.speed

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeedProtocolTest {
    private fun assertClose(expected: Double, actual: Double?, eps: Double = 1e-6) {
        assertTrue(actual != null && abs(expected - actual) < eps, "expected $expected, was $actual")
    }

    private fun valid(input: String): LibreSpeedServer = assertIs<LibreSpeedUrlCheck.Valid>(checkLibreSpeedUrl(input)).server
    private fun error(input: String): LibreSpeedUrlError = assertIs<LibreSpeedUrlCheck.Invalid>(checkLibreSpeedUrl(input)).error

    // --- LibreSpeed URL ----------------------------------------------------------------------

    @Test fun libreSpeedUrlIsNormalized() {
        assertEquals("https://rychlost.zelina.eu/", valid("https://rychlost.zelina.eu").baseUrl)
        assertEquals("https://rychlost.zelina.eu/", valid("  rychlost.zelina.eu/ ").baseUrl)
        assertEquals("https://rychlost.zelina.eu/", valid("HTTPS://Rychlost.Zelina.EU/backend/").baseUrl)
        assertEquals("https://speed.example.org:8443/ls/", valid("https://speed.example.org:8443/ls").baseUrl)
        assertEquals("https://speed.example.org/", valid("https://speed.example.org:443/").baseUrl)
        assertEquals("https://[2001:db8::1]/", valid("https://[2001:db8::1]/").baseUrl)
        assertEquals("https://192.0.2.10/", valid("https://192.0.2.10").baseUrl)
        assertEquals("speed.example.org:8443/ls", valid("https://speed.example.org:8443/ls/").name)
        assertEquals("rychlost.zelina.eu", valid("rychlost.zelina.eu").host)
    }

    @Test fun libreSpeedUrlRejectsCleartextAndUnsafeForms() {
        assertEquals(LibreSpeedUrlError.NOT_HTTPS, error("http://rychlost.zelina.eu"))
        assertEquals(LibreSpeedUrlError.NOT_HTTPS, error("ftp://x.example"))
        assertEquals(LibreSpeedUrlError.NOT_HTTPS, error("wss://x.example"))
        assertEquals(LibreSpeedUrlError.EMPTY, error("   "))
        assertEquals(LibreSpeedUrlError.CREDENTIALS, error("https://user:pw@x.example/"))
        assertEquals(LibreSpeedUrlError.QUERY_OR_FRAGMENT, error("https://x.example/?a=1"))
        assertEquals(LibreSpeedUrlError.QUERY_OR_FRAGMENT, error("https://x.example/#top"))
        assertEquals(LibreSpeedUrlError.BAD_PORT, error("https://x.example:0/"))
        assertEquals(LibreSpeedUrlError.BAD_PORT, error("https://x.example:70000/"))
        assertEquals(LibreSpeedUrlError.BAD_PORT, error("https://x.example:/"))
        assertEquals(LibreSpeedUrlError.BAD_HOST, error("https://-bad.example/"))
        assertEquals(LibreSpeedUrlError.BAD_HOST, error("https://bad_host.example/"))
        assertEquals(LibreSpeedUrlError.BAD_HOST, error("https://x example/"))
        assertEquals(LibreSpeedUrlError.BAD_HOST, error("https:///path"))
        assertEquals(LibreSpeedUrlError.BAD_HOST, error("https://[zz::1]/"))
        assertEquals(LibreSpeedUrlError.BAD_PATH, error("https://x.example/../etc/"))
        assertEquals(LibreSpeedUrlError.BAD_PATH, error("https://x.example/backend/garbage.php"))
        assertEquals(LibreSpeedUrlError.BAD_PATH, error("https://x.example/a\"b"))
        assertEquals(LibreSpeedUrlError.TOO_LONG, error("https://x.example/" + "a".repeat(600)))
    }

    @Test fun libreSpeedEndpointsAndPreset() {
        val base = LIBRESPEED_PRESETS.single().baseUrl
        assertEquals("https://rychlost.zelina.eu/", base)
        assertEquals("https://rychlost.zelina.eu/backend/garbage.php?ckSize=25&r=7", LibreSpeedProtocol.garbageUrl(base, 7))
        assertEquals("https://rychlost.zelina.eu/backend/garbage.php?ckSize=1024&r=7", LibreSpeedProtocol.garbageUrl(base, 7, 5_000))
        assertEquals("https://rychlost.zelina.eu/backend/empty.php?r=18446744073709551615", LibreSpeedProtocol.emptyUrl(base, -1))
        assertEquals("https://rychlost.zelina.eu/backend/getIP.php", LibreSpeedProtocol.ipInfoUrl(base), "no isp=true: no ipinfo lookup")
    }

    @Test fun ipInfoKeepsOnlySanitizedSummary() {
        val info = parseLibreSpeedIpInfo("""{"processedString":"192.0.2.1 - Example ISP\u0007, CZ","rawIspInfo":{"ip":"192.0.2.1"}}""")
        assertEquals("192.0.2.1 - Example ISP, CZ", info?.summary)
        assertNull(parseLibreSpeedIpInfo("""{"rawIspInfo":""}"""))
        assertNull(parseLibreSpeedIpInfo("not json"))
        assertNull(parseLibreSpeedIpInfo("""{"processedString":"   "}"""))
        assertNull(parseLibreSpeedIpInfo("x".repeat(LibreSpeedProtocol.IP_INFO_MAX_CHARS + 1)))
        assertEquals(160, parseLibreSpeedIpInfo("""{"processedString":"${"a".repeat(500)}"}""")?.summary?.length)
    }

    // --- NDT7 locate -------------------------------------------------------------------------

    private val locateJson = """
        {"results":[
          {"machine":"mlab2-prg04.mlab-oti.measurement-lab.org","location":{"city":"Prague","country":"CZ"},
           "urls":{"ws:///ndt/v7/download":"ws://ndt-mlab2-prg04.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=T",
                   "wss:///ndt/v7/download":"wss://ndt-mlab2-prg04.mlab-oti.measurement-lab.org/ndt/v7/download?access_token=T&index=0",
                   "wss:///ndt/v7/upload":"wss://ndt-mlab2-prg04.mlab-oti.measurement-lab.org/ndt/v7/upload?access_token=T&index=0"}},
          {"machine":"evil","urls":{"wss:///ndt/v7/download":"wss://evil.example.com/ndt/v7/download?access_token=T",
                   "wss:///ndt/v7/upload":"wss://evil.example.com/ndt/v7/upload?access_token=T"}},
          {"machine":"only-ws","urls":{"ws:///ndt/v7/download":"ws://x.measurement-lab.org/ndt/v7/download"}}
        ], "extra": 1}
    """.trimIndent()

    @Test fun locateKeepsOnlySecureMlabTargets() {
        val targets = parseNdt7Locate(locateJson)
        val t = targets.single()
        assertEquals("mlab2-prg04.mlab-oti.measurement-lab.org", t.machine)
        assertEquals("Prague", t.city)
        assertEquals("CZ", t.country)
        assertTrue(t.downloadUrl.startsWith("wss://ndt-mlab2-prg04.mlab-oti.measurement-lab.org/ndt/v7/download?"))
        assertEquals("ndt-mlab2-prg04.mlab-oti.measurement-lab.org", t.host)
        assertFalse("access_token" in t.toString(), "token must not leak via toString")
        assertTrue(parseNdt7Locate("""{"results":[]}""").isEmpty())
        assertTrue(parseNdt7Locate("garbage").isEmpty())
        assertTrue(parseNdt7Locate("""{"results":"x"}""").isEmpty())
    }

    @Test fun ndt7UrlValidation() {
        assertTrue(validNdt7Url("wss://a.measurement-lab.org/ndt/v7/upload?x=1", Ndt7Protocol.UPLOAD_PATH))
        assertTrue(validNdt7Url("wss://a.measurement-lab.org:443/ndt/v7/upload", Ndt7Protocol.UPLOAD_PATH))
        assertFalse(validNdt7Url("wss://a.measurement-lab.org/ndt/v7/download", Ndt7Protocol.UPLOAD_PATH))
        assertFalse(validNdt7Url("ws://a.measurement-lab.org/ndt/v7/upload", Ndt7Protocol.UPLOAD_PATH))
        assertFalse(validNdt7Url("wss://measurement-lab.org.evil.com/ndt/v7/upload", Ndt7Protocol.UPLOAD_PATH))
        assertFalse(validNdt7Url("wss://u@a.measurement-lab.org/ndt/v7/upload", Ndt7Protocol.UPLOAD_PATH))
        assertFalse(validNdt7Url("wss://a.measurement-lab.org/ndt/v7/upload extra", Ndt7Protocol.UPLOAD_PATH))
        assertNull(ndt7Host("https://a.measurement-lab.org/"))
    }

    @Test fun ndt7UrlPathMustFollowTheAuthority() {
        // A '/' inside the query or fragment is not the path.
        assertFalse(validNdt7Url("wss://x.measurement-lab.org?/ndt/v7/download", Ndt7Protocol.DOWNLOAD_PATH))
        assertFalse(validNdt7Url("wss://x.measurement-lab.org#/ndt/v7/download", Ndt7Protocol.DOWNLOAD_PATH))
        assertFalse(validNdt7Url("wss://x.measurement-lab.org", Ndt7Protocol.DOWNLOAD_PATH))
        assertFalse(validNdt7Url("wss://x.measurement-lab.org/ndt/v7/download/extra", Ndt7Protocol.DOWNLOAD_PATH))
        assertTrue(validNdt7Url("wss://x.measurement-lab.org/ndt/v7/download#f", Ndt7Protocol.DOWNLOAD_PATH))
    }

    @Test fun ndt7HostRejectsMalformedLabelsAndPorts() {
        // These would make OkHttp's URL parser throw IllegalArgumentException.
        assertNull(ndt7Host("wss://a..measurement-lab.org/ndt/v7/download"))
        assertNull(ndt7Host("wss://.measurement-lab.org/ndt/v7/download"))
        assertNull(ndt7Host("wss://a.measurement-lab.org./ndt/v7/download"))
        assertNull(ndt7Host("wss://-a.measurement-lab.org/ndt/v7/download"))
        assertNull(ndt7Host("wss://a-.measurement-lab.org/ndt/v7/download"))
        assertNull(ndt7Host("wss://a.measurement-lab.org:0/ndt/v7/download"))
        assertNull(ndt7Host("wss://a.measurement-lab.org:/ndt/v7/download"))
        assertNull(ndt7Host("wss://a.measurement-lab.org:99999/ndt/v7/download"))
        assertFalse(validNdt7Url("wss://a..measurement-lab.org/ndt/v7/download", Ndt7Protocol.DOWNLOAD_PATH))
        assertEquals("ndt-a.measurement-lab.org", ndt7Host("wss://NDT-a.measurement-lab.org:443/ndt/v7/upload?x"))
    }

    @Test fun clientMetadataIsAppendedOnce() {
        val url = "wss://a.measurement-lab.org/ndt/v7/download?access_token=T"
        val with = withNdt7ClientMetadata(url, "netkit-android", "0.3.0-dev")
        assertEquals("$url&client_name=netkit-android&client_version=0.3.0-dev", with)
        assertEquals(with, withNdt7ClientMetadata(with, "netkit-android", "0.3.0-dev"))
        assertEquals("wss://h/x?client_name=a&client_version=1b", withNdt7ClientMetadata("wss://h/x", "a", "1 b"))
    }

    // --- NDT7 messages -----------------------------------------------------------------------

    @Test fun measurementParsingAndServerView() {
        val text = """{"AppInfo":{"ElapsedTime":10000000,"NumBytes":125000000},
            "ConnectionInfo":{"Client":"192.0.2.1:5555","Server":"[2001:db8::1]:443","UUID":"u"},
            "Origin":"server","Test":"download",
            "TCPInfo":{"BytesAcked":130000000,"BytesSent":131000000,"BytesRetrans":1310000,"ElapsedTime":10000000,
                       "MinRTT":8000,"RTT":25500,"RTTVar":1000,"Unknown":5},
            "BBRInfo":{"BW":15000000,"MinRTT":8100}}"""
        val m = assertNotNull(parseNdt7Measurement(text))
        assertEquals("server", m.origin)
        val view = ndt7ServerView(m, download = true)
        assertClose(100.0, view.appMbps)
        assertClose(104.0, view.tcpMbps)
        assertClose(1.0, view.retransPercent)
        assertClose(8.0, view.minRttMs)
        assertClose(25.5, view.rttMs)
        assertClose(120.0, view.bbrMbps)
        val upload = ndt7ServerView(m, download = false)
        assertNull(upload.retransPercent)
        assertNull(upload.tcpMbps, "BytesReceived missing")
    }

    @Test fun measurementParsingIsRobust() {
        assertNotNull(parseNdt7Measurement("{}"))
        assertNotNull(parseNdt7Measurement("""{"AppInfo":{"ElapsedTime":1.5e6,"NumBytes":12}}"""))
        assertNull(parseNdt7Measurement("[1,2]"))
        assertNull(parseNdt7Measurement("{"))
        assertNull(parseNdt7Measurement("""{"AppInfo":{"NumBytes":"x"}}"""))
        assertNull(parseNdt7Measurement(" ".repeat(Ndt7Protocol.MEASUREMENT_MAX_CHARS + 1)))
        val empty = ndt7ServerView(Ndt7Measurement(appInfo = Ndt7AppInfo(elapsedTimeUs = 0.0, numBytes = 10.0)), download = true)
        assertNull(empty.appMbps)
        val negative = ndt7ServerView(Ndt7Measurement(tcpInfo = Ndt7TcpInfo(minRttUs = -1.0)), download = true)
        assertNull(negative.minRttMs)
    }

    @Test fun messageSizerFollowsSpecAppendix() {
        val sizer = Ndt7MessageSizer(maxBytes = 1 shl 16)
        assertEquals(1 shl 13, sizer.size)
        repeat(15) { sizer.onQueued(sizer.size) }
        assertEquals(1 shl 13, sizer.size, "no doubling while size >= total/16")
        sizer.onQueued(sizer.size) // total = 16 × 8 KiB → size == total/16, still no doubling
        assertEquals(1 shl 13, sizer.size)
        sizer.onQueued(sizer.size)
        assertEquals(1 shl 14, sizer.size)
        repeat(1_000) { sizer.onQueued(sizer.size) }
        assertEquals(1 shl 16, sizer.size, "capped")
    }

    // --- History store -----------------------------------------------------------------------

    private fun record(i: Int) = SpeedRecord(timestampMs = 1_000L + i, engine = SpeedEngine.LIBRESPEED, serverName = "s$i",
        downloadMbps = 100.0 + i, ssid = "home", frequencyMhz = 5_180, rssiDbm = -55)

    @Test fun historyKeepsNewestTwenty() {
        var data = SpeedStoreData()
        repeat(25) { data = data.withRecord(record(it)) }
        assertEquals(SPEED_HISTORY_MAX, data.records.size)
        assertEquals("s24", data.records.first().serverName)
        assertEquals("s5", data.records.last().serverName)
    }

    @Test fun storeRoundTripAndStrictDecode() {
        val data = SpeedStoreData(ndt7ConsentMs = 5, customUrl = "https://x.example/",
            records = listOf(record(1).copy(downloadStreams = 2, plannedDownloadStreams = 6, partial = true)))
        val decoded = SpeedStoreCodec.decode(SpeedStoreCodec.encode(data))
        assertEquals(data, decoded)
        assertFailsWith<SpeedStoreCorrupt> { SpeedStoreCodec.decode("""{"schema":2,"records":[]}""") }
        assertFailsWith<SpeedStoreCorrupt> { SpeedStoreCodec.decode("""{"records":[]}""") }
        assertFailsWith<SpeedStoreCorrupt> { SpeedStoreCodec.decode("nope") }
        assertFailsWith<SpeedStoreCorrupt> {
            SpeedStoreCodec.decode("""{"schema":1,"records":[{"timestampMs":1,"engine":"NDT7","serverName":"a","rssiDbm":5}]}""")
        }
        assertFailsWith<SpeedStoreCorrupt> { SpeedStoreCodec.decode(" ".repeat(SPEED_STORE_MAX_BYTES + 1)) }
        assertFailsWith<SpeedStoreCorrupt> {
            SpeedStoreCodec.decode("""{"schema":1,"records":[{"timestampMs":1,"engine":"NDT7","serverName":"a","downloadStreams":-1}]}""")
        }
        // Records written before the stream fields existed still decode.
        assertNull(SpeedStoreCodec.decode("""{"schema":1,"records":[{"timestampMs":1,"engine":"NDT7","serverName":"a"}]}""")
            .records.single().downloadStreams)
        // A stored cleartext URL (edited by hand) is dropped, not used.
        assertNull(SpeedStoreCodec.decode("""{"schema":1,"customUrl":"http://x.example/"}""").customUrl)
        // Encoding drops invalid records instead of persisting them.
        val bad = SpeedStoreData(records = listOf(record(1).copy(downloadMbps = Double.NaN), record(2)))
        assertEquals(listOf("s2"), SpeedStoreCodec.decode(SpeedStoreCodec.encode(bad)).records.map { it.serverName })
    }

    @Test fun historyNetworkKindIsBackwardCompatible() {
        // A record written before M4 (no networkKind) is a Wi-Fi result.
        val old = SpeedStoreCodec.decode("""{"schema":1,"records":[{"timestampMs":1,"engine":"LIBRESPEED","serverName":"a","ssid":"home","downloadMbps":50.0}]}""")
            .records.single()
        assertNull(old.networkKind)
        assertEquals(cz.netkit.shared.net.NetKind.WIFI, old.measuredKind)
        // A new record keeps its kind through a round trip and stays in schema 1.
        val cellular = record(2).copy(networkKind = cz.netkit.shared.net.NetKind.CELLULAR, ssid = null)
        val text = SpeedStoreCodec.encode(SpeedStoreData(records = listOf(cellular)))
        assertTrue(text.contains(""""networkKind":"CELLULAR""""))
        assertTrue(text.contains("\"schema\":1,"))
        assertEquals(cz.netkit.shared.net.NetKind.CELLULAR, SpeedStoreCodec.decode(text).records.single().measuredKind)
        // An unknown kind from a future version is rejected like any other invalid value.
        assertFailsWith<SpeedStoreCorrupt> {
            SpeedStoreCodec.decode("""{"schema":1,"records":[{"timestampMs":1,"engine":"NDT7","serverName":"a","networkKind":"SATELLITE"}]}""")
        }
    }
}
