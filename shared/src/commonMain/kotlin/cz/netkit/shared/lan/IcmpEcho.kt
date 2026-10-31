package cz.netkit.shared.lan

/**
 * Match an IPv4 ICMP datagram reply without an IP header. Linux ping sockets own
 * identifier/checksum; sequence and the entire nonce payload must match our request.
 */
fun matchesIcmpEchoReply(request: ByteArray, reply: ByteArray, receivedBytes: Int): Boolean {
    // Require data beyond the 8-byte header so matching never loses the nonce.
    if (request.size <= 8 || request[0] != 8.toByte() || request[1] != 0.toByte()) return false
    if (receivedBytes != request.size || receivedBytes > reply.size) return false
    if (reply[0] != 0.toByte() || reply[1] != 0.toByte()) return false
    return (6 until request.size).all { reply[it] == request[it] }
}
