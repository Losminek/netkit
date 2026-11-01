package cz.netkit.shared

import cz.netkit.shared.ios.*
import cz.netkit.shared.wol.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class IosBridgeTest {
    @Test fun dnsResponseRejectsMismatchedTruncatedOrNonResponsePackets() {
        val domain = IosNetworkDomain()
        val query = assertNotNull(domain.dnsQuery("localhost", 1, 123))
        assertEquals(1, query[2].toInt()) // Unicast DNS requests recursive resolution.
        assertNull(domain.dnsResponse(query, 123, "localhost", 1))
        val response = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }
        assertEquals(emptyList(), domain.dnsResponse(response, 123, "localhost", 1))
        assertNull(domain.dnsResponse(response, 124, "localhost", 1))
        assertNull(domain.dnsResponse(response, 123, "other.local", 1))
        assertNull(domain.dnsResponse(response, 123, "localhost", 12))
        response[2] = 0x83.toByte()
        assertNull(domain.dnsResponse(response, 123, "localhost", 1))
        assertNull(domain.dnsResponse(response.copyOf(8), 123, "localhost", 1))
    }

    @Test fun networkBridgeUsesSharedValidationAndIcmpNonceMatching() {
        val domain = IosNetworkDomain()
        assertEquals(listOf(22, 443), domain.ports("22,443"))
        assertNull(domain.ports("65536"))
        assertEquals("printer label", domain.mdnsName("printer\u202Elabel")?.value)
        val request = ByteArray(16) { it.toByte() }.also { it[0] = 8; it[1] = 0 }
        val reply = request.copyOf().also { it[0] = 0 }
        assertTrue(domain.icmpReplyMatches(request, reply))
        reply[15] = 0
        assertFalse(domain.icmpReplyMatches(request, reply))
    }

    @Test fun speedBridgeKeepsSharedUrlPolicyAndThroughputMath() {
        val domain = IosSpeedDomain()
        assertNull(domain.libreServer("http://localhost/"))
        assertEquals("https://localhost/", domain.libreServer("localhost")?.baseUrl)
        assertEquals(emptyList(), domain.ndtTargets("{}"))
        assertNull(domain.ndtView("not-json", true))
        val trace = domain.createTrace()
        for (time in 250L..10_000L step 250) assertTrue(trace.add(time, time * 1_000))
        assertEquals(8.0, assertNotNull(domain.summarize(trace)).mbps)
    }

    @Test fun wolBridgeRunsExistingControllerAndPassesPinnedNetworkAndMagicPacket() = runTest {
        val platform = FakePlatform()
        val bridge = bridge(platform, MemoryPersistence(), this)
        runCurrent()
        bridge.controller.wake("device")
        advanceUntilIdle()
        val call = assertNotNull(platform.packet)
        assertEquals("wifi-test", call.first)
        assertEquals("192.0.2.255", platform.host)
        assertEquals(102, call.second.size)
        assertTrue(call.second.take(6).all { it == 0xff.toByte() })
        assertTrue(bridge.currentState().outcomes.getValue("device").sent)
    }

    @Test fun wolCancellationCancelsNativeOperationAndIgnoresLateOrDuplicateCompletion() = runTest {
        val platform = FakePlatform(completeImmediately = false)
        val bridge = bridge(platform, MemoryPersistence(), this)
        runCurrent()
        bridge.controller.wake("device")
        runCurrent()
        bridge.controller.cancelWake()
        runCurrent()
        assertTrue(platform.cancelled)
        platform.callback?.invoke(null)
        platform.callback?.invoke(SendFailure.IO)
        runCurrent()
        assertEquals(SendFailure.CANCELLED, bridge.currentState().outcomes.getValue("device").reason)
    }

    @Test fun wolTransportAndPersistenceFailuresAreNotSuccessfulEmptyState() = runTest {
        val platform = FakePlatform(failure = SendFailure.NETWORK_LOST)
        val bridge = bridge(platform, MemoryPersistence(), this)
        runCurrent()
        bridge.controller.wake("device")
        advanceUntilIdle()
        assertEquals(SendFailure.NETWORK_LOST, bridge.currentState().outcomes.getValue("device").reason)
        val failed = bridge(platform, MemoryPersistence(loadFailure = StoreFailure.CORRUPT), this)
        runCurrent()
        assertTrue(failed.currentState().loadFailed)
        assertEquals(StoreFailure.CORRUPT, failed.currentState().loadFailure)
    }

    @Test fun wolCodecRoundTripsAndRejectsCorruptOrUnsupportedDocuments() {
        val codec = IosWolCodec()
        val devices = MemoryPersistence().load().devices
        val encoded = assertNotNull(codec.encode(devices))
        val decoded = codec.decode(encoded)
        assertNull(decoded.failure)
        assertEquals(devices, decoded.devices)
        assertEquals(StoreFailure.CORRUPT, codec.decode("not-json".encodeToByteArray()).failure)
        assertEquals(StoreFailure.CORRUPT, codec.decode(byteArrayOf(0xC3.toByte())).failure)
        assertEquals(StoreFailure.UNSUPPORTED_SCHEMA,
            codec.decode("{\"schema\":999,\"devices\":[]}".encodeToByteArray()).failure)
        assertNull(codec.encode(devices + devices)) // Duplicate IDs must not be persisted.
    }

    @Test fun wolBridgeRunsControllerPrecheckAndPreservesAlreadyRespondedResult() = runTest {
        val platform = FakePlatform()
        val bridge = bridge(platform, MemoryPersistence(probeEnabled = true), this)
        runCurrent()
        bridge.controller.wake("device")
        advanceUntilIdle()
        assertEquals(1, platform.probeCalls)
        assertEquals("wifi-test", platform.observedNetwork)
        assertEquals("192.0.2.42", platform.probeHost)
        assertEquals(22, platform.probePort)
        assertNotNull(platform.packet)
        assertTrue(bridge.currentState().outcomes.getValue("device").sent)
        assertEquals(WakeProbeStatus.ALREADY_RESPONDED, bridge.currentState().probes["device"])
    }

    @Test fun wolPrecheckCancellationCancelsPlatformProbeAndIgnoresLateCompletion() = runTest {
        val platform = FakePlatform(completeProbeImmediately = false)
        val bridge = bridge(platform, MemoryPersistence(probeEnabled = true), this)
        runCurrent()
        bridge.controller.wake("device")
        runCurrent()
        assertEquals(1, platform.probeCalls)
        assertNull(platform.packet)
        bridge.controller.cancelWake()
        runCurrent()
        assertTrue(platform.probeCancelled)
        platform.probeCallback?.invoke(WakeProbeStatus.RESPONDS)
        platform.probeCallback?.invoke(WakeProbeStatus.NO_RESPONSE)
        runCurrent()
        assertNull(platform.packet)
        assertEquals(SendFailure.CANCELLED, bridge.currentState().outcomes.getValue("device").reason)
    }

    private fun bridge(platform: FakePlatform, store: MemoryPersistence, scope: TestScope) =
        IosWolBridge(platform, store, { 1_000L }, { "new-device" }, scope)

    private class MemoryPersistence(private val loadFailure: StoreFailure? = null, probeEnabled: Boolean = false) : IosWolPersistence {
        private var devices = listOf(WolDevice("device", "Test device", "02:00:00:00:00:01", TargetMode.AUTO_BROADCAST,
            createdAt = 1, updatedAt = 1, repeatCount = 1, probeEnabled = probeEnabled,
            probeHost = if (probeEnabled) "192.0.2.42" else null, probePort = if (probeEnabled) 22 else null))
        override fun load() = IosWolLoadResult(devices, loadFailure)
        override fun save(devices: List<WolDevice>): Boolean { this.devices = devices; return true }
    }

    private class FakePlatform(private val completeImmediately: Boolean = true, private val failure: SendFailure? = null,
        private val completeProbeImmediately: Boolean = true) : IosWolPlatform {
        var packet: Pair<String, ByteArray>? = null
        var host: String? = null
        var cancelled = false
        var callback: ((SendFailure?) -> Unit)? = null
        var probeCalls = 0
        var observedNetwork: String? = null
        var probeHost: String? = null
        var probePort: Int? = null
        var probeCancelled = false
        var probeCallback: ((WakeProbeStatus) -> Unit)? = null
        override fun currentWifi() = IosWifiSnapshot("wifi-test", "192.0.2.42", 24)
        override fun sendUdp(networkToken: String, host: String, port: Int, payload: ByteArray, completion: (SendFailure?) -> Unit): IosCancellation {
            packet = networkToken to payload; this.host = host; callback = completion
            if (completeImmediately) completion(failure)
            return object : IosCancellation { override fun cancel() { cancelled = true } }
        }
        override fun probe(networkToken: String, host: String, port: Int?, completion: (WakeProbeStatus) -> Unit): IosCancellation {
            probeCalls++; observedNetwork = networkToken; probeHost = host; probePort = port; probeCallback = completion
            if (completeProbeImmediately) completion(WakeProbeStatus.RESPONDS)
            return object : IosCancellation { override fun cancel() { probeCancelled = true } }
        }
    }
}
