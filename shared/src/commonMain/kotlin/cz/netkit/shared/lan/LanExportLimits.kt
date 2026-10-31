package cz.netkit.shared.lan

const val LAN_IPC_TEXT_LIMIT = 64 * 1024
const val LAN_EXPORT_FILE_LIMIT = 8 * 1024 * 1024
fun lanTextFitsIpc(text: String): Boolean = text.length <= LAN_IPC_TEXT_LIMIT
/** Full export or an error; never truncate data to make it fit an IPC/file limit. */
fun lanExportFileBytes(text: String): ByteArray {
    require(text.length <= LAN_EXPORT_FILE_LIMIT)
    return text.encodeToByteArray().also { require(it.size <= LAN_EXPORT_FILE_LIMIT) }
}
