package cz.netkit.shared.wol

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.*

class WolImportLimitsTest {
    private val device = WolDevice("safe-id_1.2", "NAS", "AA:BB:CC:DD:EE:FF", TargetMode.CUSTOM,
        customTarget = "127.0.0.1", createdAt = 0, updatedAt = 1)
    private fun raw(devices: List<WolDevice>): ByteArray =
        ("{\"schema\":2,\"devices\":" + Json.encodeToString(ListSerializer(WolDevice.serializer()), devices) + "}").encodeToByteArray()
    private fun rejected(expected: WolImportFailure, bytes: ByteArray) {
        assertEquals(expected, assertFailsWith<WolImportException> { decodeWolImport(bytes) }.reason)
    }
    @Test fun lengthsAreHardLimitsEvenForInactiveFieldsAndWhitespace() {
        val invalid = listOf(device.copy(name = "N".repeat(101)), device.copy(name = " ".repeat(101) + "N"),
            device.copy(note = "n".repeat(1001)), device.copy(id = "i".repeat(65)), device.copy(id = "../bad/id"),
            device.copy(id = ""), device.copy(customTarget = "h".repeat(254), targetMode = TargetMode.AUTO_BROADCAST),
            device.copy(probeHost = "h".repeat(254)), device.copy(createdAt = -1), device.copy(updatedAt = -1))
        invalid.forEach { rejected(WolImportFailure.INVALID_DEVICE, raw(listOf(it))) }
        val boundary = device.copy(name = "n".repeat(100), note = "n".repeat(1000), id = "i".repeat(64))
        assertEquals(listOf(boundary), decodeWolImport(raw(listOf(boundary))))
    }
    @Test fun ownLegacyStorageRemainsReadableButCannotBeImportedAsNewData() {
        val legacy = device.copy(name = "n".repeat(900_000), note = "n".repeat(1001))
        assertTrue(validateStoredDevices(listOf(legacy)))
        rejected(WolImportFailure.INVALID_DEVICE, raw(listOf(legacy)))
        assertFailsWith<IllegalArgumentException> { mergeWolDevices(emptyList(), listOf(legacy)) }
    }
    @Test fun bomAcceptedButInvalidEncodingSchemaAndUnknownFieldsExplainFailure() {
        val source = raw(listOf(device))
        assertEquals(listOf(device), decodeWolImport(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + source))
        rejected(WolImportFailure.UTF8, byteArrayOf(0xff.toByte()))
        rejected(WolImportFailure.SIZE, ByteArray(WOL_TRANSFER_LIMIT + 1))
        rejected(WolImportFailure.SCHEMA, source.decodeToString().replace("\"schema\":2", "\"schema\":7").encodeToByteArray())
        rejected(WolImportFailure.UNKNOWN_FIELD, source.decodeToString().replace("\"schema\":2", "\"extra\":0,\"schema\":2").encodeToByteArray())
        rejected(WolImportFailure.UNKNOWN_FIELD, source.decodeToString().replace("\"name\":", "\"extra\":0,\"name\":").encodeToByteArray())
        rejected(WolImportFailure.FORMAT, "not json".encodeToByteArray())
    }
    @Test fun duplicateIdsFailWhileEquivalentMacFormatsAreOnlyWarnings() {
        rejected(WolImportFailure.DUPLICATE_ID, raw(listOf(device, device.copy(name = "Other"))))
        val devices = listOf(device, device.copy(id = "other", mac = "aabb.ccdd.eeff"))
        assertEquals(devices, decodeWolImport(raw(devices)))
        assertEquals(1, duplicateMacCount(devices))
        assertEquals(0, duplicateMacCount(listOf(device)))
    }
}
