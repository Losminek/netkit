package cz.netkit.shared.lan

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IcmpEchoTest {
    private fun request() = ByteArray(24) { (it * 7).toByte() }.apply { this[0] = 8; this[1] = 0 }
    private fun reply(request: ByteArray) = request.copyOf().apply { this[0] = 0 }

    @Test fun echoReplyMatchesSequenceAndWholeNonceInsideReceiveBuffer() {
        val request = request()
        val response = reply(request).copyOf(512)
        // A ping socket lets the kernel replace identifier and checksum.
        for (index in 2..5) response[index] = (response[index] + 1).toByte()
        assertTrue(matchesIcmpEchoReply(request, response, request.size))
        assertTrue(matchesIcmpEchoReply(request, reply(request), request.size))
    }
    @Test fun wrongTypeCodeSequenceOrAnyNonceByteDoesNotMatch() {
        val request = request()
        for (index in listOf(0, 1) + (6 until request.size)) {
            val response = reply(request)
            response[index] = (response[index] + 1).toByte()
            assertFalse(matchesIcmpEchoReply(request, response, request.size), "index=$index")
        }
        assertFalse(matchesIcmpEchoReply(request, request, request.size)) // outgoing echo
    }
    @Test fun truncatedOversizedAndInvalidLengthsAreRejectedWithoutReadingPastBuffer() {
        val request = request()
        for (length in listOf(-1, 0, 1, 7, 8, 23, 25, Int.MAX_VALUE)) {
            assertFalse(matchesIcmpEchoReply(request, reply(request), length), "length=$length")
        }
        assertFalse(matchesIcmpEchoReply(request, reply(request).copyOf(23), request.size))
        assertFalse(matchesIcmpEchoReply(request, byteArrayOf(), request.size))
    }
    @Test fun malformedOrNonceFreeRequestCannotMatch() {
        val request = request()
        for (length in 0..8) {
            assertFalse(matchesIcmpEchoReply(request.copyOf(length), reply(request), length))
        }
        assertFalse(matchesIcmpEchoReply(request.copyOf().apply { this[0] = 0 }, reply(request), 24))
        assertFalse(matchesIcmpEchoReply(request.copyOf().apply { this[1] = 1 }, reply(request), 24))
    }
}
