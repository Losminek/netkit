package cz.netkit.shared.wol

import cz.netkit.shared.json.JSON_MAX_DEPTH
import cz.netkit.shared.json.jsonNestingDepth
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

const val WOL_TRANSFER_LIMIT = 1_048_576
@Serializable private data class WolTransferEnvelope(val schema: Int, val devices: List<WolDevice>)
private val transferJson = Json { encodeDefaults = true }
enum class WolImportFailure { SIZE, UTF8, FORMAT, SCHEMA, UNKNOWN_FIELD, DUPLICATE_ID, INVALID_DEVICE }
class WolImportException(val reason: WolImportFailure) : IllegalArgumentException(reason.name)
private val importDeviceFields = setOf("id", "name", "mac", "targetMode", "customTarget", "port", "probeHost",
    "probePort", "note", "createdAt", "updatedAt", "repeatCount", "repeatIntervalMs", "probeEnabled", "probeDelaySeconds")

/** Untrusted imports never inherit the persisted-store grandfathering exception. */
fun validateImportedDevices(devices: List<WolDevice>): Boolean = devices.all {
    it.id.matches(Regex("[A-Za-z0-9._-]{1,64}")) && it.name.length <= 100 &&
        (it.note?.length ?: 0) <= 1000 && (it.customTarget?.length ?: 0) <= 253 &&
        (it.probeHost?.length ?: 0) <= 253 && it.createdAt >= 0 && it.updatedAt >= 0 &&
        validateDevice(it.toDraft()).isValid
} && devices.map { it.id }.distinct().size == devices.size

/** Number of repeated MAC identities; accepted, but disclosed before confirmation. */
fun duplicateMacCount(devices: List<WolDevice>): Int = devices.mapNotNull { parseMac(it.mac)?.toString() }
    .groupingBy { it }.eachCount().values.sumOf { (it - 1).coerceAtLeast(0) }

/** Validate the whole document before exposing a preview; no row is silently discarded. */
fun decodeWolImport(bytes: ByteArray): List<WolDevice> {
    if (bytes.size > WOL_TRANSFER_LIMIT) throw WolImportException(WolImportFailure.SIZE)
    val text = try { bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF") }
        catch (_: CharacterCodingException) { throw WolImportException(WolImportFailure.UTF8) }
    // Both decoders below consume this same, immutable, depth-checked text.
    if (jsonNestingDepth(text) > JSON_MAX_DEPTH) throw WolImportException(WolImportFailure.FORMAT)
    try {
        val root = transferJson.parseToJsonElement(text) as? JsonObject ?: throw WolImportException(WolImportFailure.FORMAT)
        if (root.keys.any { it !in setOf("schema", "devices") }) throw WolImportException(WolImportFailure.UNKNOWN_FIELD)
        val schema = root["schema"]?.jsonPrimitive?.intOrNull
        if (schema !in 1..2) throw WolImportException(WolImportFailure.SCHEMA)
        val rows = root["devices"] as? JsonArray ?: throw WolImportException(WolImportFailure.FORMAT)
        if (rows.any { row -> row is JsonObject && row.keys.any { it !in importDeviceFields } })
            throw WolImportException(WolImportFailure.UNKNOWN_FIELD)
        val devices = transferJson.decodeFromString<WolTransferEnvelope>(text).devices
        if (devices.map { it.id }.distinct().size != devices.size) throw WolImportException(WolImportFailure.DUPLICATE_ID)
        if (!validateImportedDevices(devices)) throw WolImportException(WolImportFailure.INVALID_DEVICE)
        return devices
    } catch (error: WolImportException) { throw error }
    catch (_: SerializationException) { throw WolImportException(WolImportFailure.FORMAT) }
    catch (_: IllegalArgumentException) { throw WolImportException(WolImportFailure.FORMAT) }
}
fun encodeWolExport(devices: List<WolDevice>): ByteArray {
    require(validateStoredDevices(devices))
    return transferJson.encodeToString(WolTransferEnvelope(2, devices)).encodeToByteArray().also { require(it.size <= WOL_TRANSFER_LIMIT) }
}
fun mergeWolDevices(current: List<WolDevice>, incoming: List<WolDevice>): List<WolDevice> {
    require(validateStoredDevices(current) && validateImportedDevices(incoming))
    val incomingById = incoming.associateBy { it.id }
    val existingIds = current.map { it.id }.toSet()
    return (current.map { incomingById[it.id] ?: it } + incoming.filterNot { it.id in existingIds }).also {
        encodeWolExport(it) // Enforce the same persisted-size limit before any mutation.
    }
}

/** A hostname is unresolved at preview time; it must not be presented as a private address. */
fun isPrivateWolImportTarget(host: String): Boolean {
    val address = parseIpv4(host)?.value ?: return false
    return address ushr 24 == 10L || address ushr 20 == 0xac1L || address ushr 16 == 0xc0a8L
}
