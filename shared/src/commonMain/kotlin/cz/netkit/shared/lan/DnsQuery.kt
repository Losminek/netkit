package cz.netkit.shared.lan

/** RFC1035 wire-format IN/PTR query. The caller supplies an unpredictable 16-bit transaction ID. */
fun ipv4PtrQuery(address: String, transactionId: Int): ByteArray {
    val ip=ipv4Number(address) ?: throw IllegalArgumentException("IPV4")
    require(transactionId in 0..65535)
    val name=ipv4Text(ip).split('.').reversed()+listOf("in-addr","arpa")
    val question=name.flatMap { label -> listOf(label.length.toByte())+label.encodeToByteArray().toList() }+listOf<Byte>(0,0,12,0,1)
    val header=byteArrayOf((transactionId shr 8).toByte(),transactionId.toByte(),1,0,0,1,0,0,0,0,0,0)
    return header+question.toByteArray()
}
