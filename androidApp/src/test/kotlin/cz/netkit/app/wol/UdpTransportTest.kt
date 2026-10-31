package cz.netkit.app.wol

import cz.netkit.shared.wol.WifiNetworkHandle
import cz.netkit.shared.wol.PacketSendException
import cz.netkit.shared.wol.SendFailure
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.testng.annotations.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UdpTransportTest {
    private object Wifi : WifiNetworkHandle
    private val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 9))
    private val payload = byteArrayOf(1, 2, 3)

    @Test fun selectionExcludesMobileVpnAndMissingCapabilities() {
        val mobile = NetworkChoice("mobile", false, false)
        val vpn = NetworkChoice("vpn", true, true)
        val wifi = NetworkChoice("wifi", true, false)
        assertEquals("wifi", selectWifi(listOf(mobile, vpn, wifi)))
        assertNull(selectWifi(listOf(mobile, vpn, NetworkChoice("unknown", false, false))))
        assertEquals("preferred", selectWifi(listOf(NetworkChoice("preferred", true, false), wifi)))
    }

    @Test fun ipv4LiteralIsStrictAndDoesNotResolveNames() {
        assertContentEquals(byteArrayOf(192.toByte(), 0, 2, 9), ipv4Literal("192.0.2.9")!!.address)
        for (value in listOf("192.00.2.9", "192.0.2.256", "192.0.2", "192.0.2.9.5", "test.example", "::1", " 192.0.2.9")) {
            assertNull(ipv4Literal(value), value)
        }
    }

    @Test fun dnsAnswersRequireSuccessfulIpv4Response() {
        assertEquals(address, dnsIpv4Answer(listOf(address), 0))
        assertFailsWith<IOException> { dnsIpv4Answer(listOf(address), 3) }
        assertFailsWith<IOException> { dnsIpv4Answer(emptyList(), 0) }
        assertFailsWith<IOException> { dnsIpv4Answer(listOf(InetAddress.getByAddress(ByteArray(16))), 0) }
    }

    @Test fun literalSkipsDnsAndBindsBeforeSendingOnOriginalHandle() = runTest {
        val fake = FakePlatform()
        BoundPacketSender(fake, StandardTestDispatcher(testScheduler)).sendUdp(Wifi, "192.0.2.9", 9009, payload)
        assertEquals(listOf("wifi", "open", "wifi", "broadcast", "bind", "send", "close"), fake.events.distinctUntilDoubleClose())
        assertSame(Wifi, fake.bound)
        assertEquals(0, fake.dnsCount)
        assertContentEquals(payload, fake.sentPayload)
        assertEquals(9009, fake.sentPort)
        assertEquals(address, fake.sentTarget)
    }

    @Test fun hostnameResolvesOnOriginalHandleAndDnsFailureNeverOpensSocket() = runTest {
        val fake = FakePlatform()
        BoundPacketSender(fake, StandardTestDispatcher(testScheduler)).sendUdp(Wifi, "host.example", 9009, payload)
        assertSame(Wifi, fake.resolved)
        assertEquals(1, fake.dnsCount)
        val failing = FakePlatform().apply { dnsFailure = true }
        val error = assertFailsWith<PacketSendException> {
            BoundPacketSender(failing, StandardTestDispatcher(testScheduler)).sendUdp(Wifi, "host.example", 9009, payload)
        }
        assertEquals(SendFailure.DNS, error.reason)
        assertFalse("open" in failing.events)
    }

    @Test fun disconnectBeforeBindClosesSocketWithoutSending() = runTest {
        val fake = FakePlatform().apply { disconnectOnSecondCheck = true }
        val error = assertFailsWith<PacketSendException> {
            BoundPacketSender(fake, StandardTestDispatcher(testScheduler)).sendUdp(Wifi, "192.0.2.9", 9009, payload)
        }
        assertEquals(SendFailure.NETWORK_LOST, error.reason)
        assertTrue("close" in fake.events)
        assertFalse("bind" in fake.events)
        assertFalse("send" in fake.events)
    }

    @Test fun cancellingDnsPropagatesCancellationAndNeverOpensSocket() = runTest {
        val fake = FakePlatform().apply { stallDns = true }
        val job = launch { BoundPacketSender(fake, StandardTestDispatcher(testScheduler)).sendUdp(Wifi, "host.example", 9009, payload) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(fake.dnsCancelled)
        assertFalse("open" in fake.events)
    }

    @Test fun deadlineCancelsDnsAndReportsTimeout() = runTest {
        val fake = FakePlatform().apply { stallDns = true }
        val error = assertFailsWith<PacketSendException> {
            BoundPacketSender(fake, StandardTestDispatcher(testScheduler), timeoutMillis = 50)
                .sendUdp(Wifi, "host.example", 9009, payload)
        }
        assertEquals(SendFailure.TIMEOUT, error.reason)
        assertTrue(fake.dnsCancelled)
        assertFalse("open" in fake.events)
    }

    @Test fun socketIoFailureIsTypedAndAlwaysClosed() = runTest {
        val fake = FakePlatform().apply { socketFailure = true }
        val error = assertFailsWith<PacketSendException> {
            BoundPacketSender(fake, StandardTestDispatcher(testScheduler)).sendUdp(Wifi, "192.0.2.9", 9009, payload)
        }
        assertEquals(SendFailure.IO, error.reason)
        assertTrue("close" in fake.events)
    }

    @Test fun cancellationClosesAnInProgressSocket() = runBlocking {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val wasClosed = AtomicBoolean(false)
        val fake = object : UdpPlatform {
            override fun isWifi(network: WifiNetworkHandle) = true
            override suspend fun resolve(network: WifiNetworkHandle, host: String): InetAddress = error("No DNS expected")
            override fun openSocket(): UdpSocket = object : UdpSocket {
                override fun enableBroadcast() = Unit
                override fun bind(network: WifiNetworkHandle) = Unit
                override fun send(target: InetAddress, port: Int, payload: ByteArray) {
                    started.countDown()
                    check(closed.await(2, TimeUnit.SECONDS)) { "Cancellation did not close socket" }
                }
                override fun close() { wasClosed.set(true); closed.countDown() }
            }
        }
        withTimeout(4_000) {
            val job = launch { BoundPacketSender(fake).sendUdp(Wifi, "192.0.2.9", 9009, payload) }
            assertTrue(withContext(Dispatchers.IO) { started.await(2, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(wasClosed.get())
        }
    }

    private inner class FakePlatform : UdpPlatform {
        val events = mutableListOf<String>()
        var dnsCount = 0
        var dnsFailure = false
        var socketFailure = false
        var stallDns = false
        var dnsCancelled = false
        var disconnectOnSecondCheck = false
        var resolved: WifiNetworkHandle? = null
        var bound: WifiNetworkHandle? = null
        var sentPayload: ByteArray? = null
        var sentTarget: InetAddress? = null
        var sentPort = 0
        private var checks = 0
        override fun isWifi(network: WifiNetworkHandle): Boolean {
            events += "wifi"
            return !disconnectOnSecondCheck || ++checks < 2
        }
        override suspend fun resolve(network: WifiNetworkHandle, host: String): InetAddress {
            resolved = network; dnsCount++
            if (dnsFailure) throw IOException("DNS failed")
            if (stallDns) return suspendCancellableCoroutine { cont -> cont.invokeOnCancellation { dnsCancelled = true } }
            return address
        }
        override fun openSocket(): UdpSocket {
            events += "open"
            return object : UdpSocket {
                override fun enableBroadcast() { events += "broadcast" }
                override fun bind(network: WifiNetworkHandle) { bound = network; events += "bind" }
                override fun send(target: InetAddress, port: Int, payload: ByteArray) {
                    sentTarget = target
                    if (socketFailure) throw IOException("socket failed")
                    sentPayload = payload; sentPort = port; events += "send"
                }
                override fun close() { events += "close" }
            }
        }
    }
    private fun List<String>.distinctUntilDoubleClose() = filterIndexed { index, item -> item != "close" || index == 0 || this[index - 1] != "close" }
}
