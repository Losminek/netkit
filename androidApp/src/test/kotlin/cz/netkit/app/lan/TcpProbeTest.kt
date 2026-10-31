package cz.netkit.app.lan

import cz.netkit.shared.lan.ProbeStatus
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.*

class TcpProbeTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    @Test fun resetAfterConnectIsStillOpenWithoutBanner() {
        ServerSocket(0, 1, loopback).use { server ->
            // Reset only after connect() returned: a reset racing the client's SO_ERROR check would make
            // connect() itself fail (seen on a fast CI runner), which is not what this test is about.
            val connected = java.util.concurrent.CountDownLatch(1)
            val worker = thread {
                server.accept().use { peer ->
                    connected.await(2, java.util.concurrent.TimeUnit.SECONDS)
                    peer.setSoLinger(true, 0)
                }
            }
            val probe = Socket().use {
                probeTcpSocket(it, loopback, server.localPort, 1000, true, onConnected = { connected.countDown() })
            }
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertEquals(ProbeStatus.OPEN, probe.status)
            val result = assertNotNull(probe.result)
            assertEquals("", result.detail)
        }
    }
    @Test fun boundNonListeningPortIsRefused() {
        // Reserve the port without listen: there is no close/rebind race with another process.
        Socket().use { reserved ->
            reserved.bind(java.net.InetSocketAddress(loopback, 0))
            val probe = Socket().use { socket ->
                probeTcpSocket(socket, loopback, reserved.localPort, 1000, false,
                    classifyFailure = { error ->
                        // OpenJDK exposes no errno for this real, reserved loopback endpoint.
                        // This explicit test adapter supplies Android's native-cause contract;
                        // the unmodified Android reader is tested by the LAN instrumentation.
                        assertIs<ConnectException>(error)
                        assertNull(error.cause)
                        classifyConnectFailure(error, readErrno = { if (it === error) 111 else null },
                            refusedErrno = 111, timeoutErrno = 110)
                    })
            }
            assertEquals(ProbeStatus.REFUSED, probe.status)
            assertNull(probe.result)
        }
    }
    @Test fun boundNonListeningPortWithoutErrnoIsNotAssumedRefused() {
        Socket().use { reserved ->
            reserved.bind(java.net.InetSocketAddress(loopback, 0))
            val probe = Socket().use { socket ->
                probeTcpSocket(socket, loopback, reserved.localPort, 1000, false,
                    classifyFailure = { error ->
                        assertIs<ConnectException>(error)
                        assertNull(error.cause)
                        classifyConnectFailure(error, readErrno = { null },
                            refusedErrno = 111, timeoutErrno = 110)
                    })
            }
            assertEquals(ProbeStatus.ERROR, probe.status)
            assertNull(probe.result)
        }
    }
    @Test fun connectFailureUsesInjectedErrnoIndependentOfLocalizedMessage() {
        // Synthetic values deliberately differ from mock android.jar's zero constants.
        val cases = listOf(
            Triple(111, "Spojení odmítnuto", ProbeStatus.REFUSED),
            Triple(111, "Connection refused", ProbeStatus.REFUSED),
            Triple(111, "Verbindung abgelehnt", ProbeStatus.REFUSED),
            Triple(111, "", ProbeStatus.REFUSED),
            Triple(110, "Vypršel časový limit", ProbeStatus.TIMEOUT),
            Triple(101, "Connection refused", ProbeStatus.ERROR), // ENETUNREACH
            Triple(113, "ECONNREFUSED", ProbeStatus.ERROR), // EHOSTUNREACH
            Triple(13, "Spojení odmítnuto", ProbeStatus.ERROR), // EACCES
            Triple(0, "ECONNREFUSED", ProbeStatus.ERROR),
        )
        for ((errno, message, expected) in cases) {
            val nativeCause = IOException("native diagnostic")
            val error = ConnectException(message).apply { initCause(IOException(nativeCause)) }
            assertEquals(expected, classifyConnectFailure(error,
                readErrno = { if (it === nativeCause) errno else null },
                refusedErrno = 111, timeoutErrno = 110), "errno=$errno; message=$message")
        }
    }
    @Test fun missingErrnoNeverUsesRefusalTextAndTimeoutRetainsItsType() {
        for (message in listOf("Spojení odmítnuto", "Connection refused", "ECONNREFUSED", "")) {
            assertEquals(ProbeStatus.ERROR, classifyConnectFailure(ConnectException(message),
                readErrno = { null }, refusedErrno = 111, timeoutErrno = 110))
        }
        assertEquals(ProbeStatus.TIMEOUT, classifyConnectFailure(IOException(SocketTimeoutException()),
            readErrno = { null }, refusedErrno = 111, timeoutErrno = 110))
        assertEquals(ProbeStatus.ERROR, classifyConnectFailure(IOException(),
            readErrno = { 0 }, refusedErrno = 0, timeoutErrno = 0))
        assertEquals(ProbeStatus.ERROR, classifyConnectFailure(IOException(),
            readErrno = { 111 }, refusedErrno = 0, timeoutErrno = 0))
    }
    @Test fun cyclicCauseChainIsBounded() {
        val first = IOException()
        val second = IOException(first)
        first.initCause(second)
        var reads = 0
        assertEquals(ProbeStatus.ERROR, classifyConnectFailure(first,
            readErrno = { reads++; null }, refusedErrno = 111, timeoutErrno = 110))
        assertEquals(16, reads)
    }
    @Test fun bindFailureIsErrorRatherThanLiveHost() {
        val probe = Socket().use { socket ->
            probeTcpSocket(socket, loopback, 9, 1000, false, bind={ throw java.io.IOException("network lost") })
        }
        assertEquals(ProbeStatus.ERROR, probe.status)
    }
    @Test fun utf8BannerUsesReplacementAndRemovesControls() {
        ServerSocket(0, 1, loopback).use { server ->
            val worker = thread { server.accept().use { it.getOutputStream().write("síť\n\u202e".toByteArray() + byteArrayOf(-1)) } }
            val probe = Socket().use { probeTcpSocket(it, loopback, server.localPort, 1000, true) }
            worker.join(2000)
            assertEquals(ProbeStatus.OPEN, probe.status)
            assertEquals("síť  �", probe.result?.detail)
        }
    }
}
