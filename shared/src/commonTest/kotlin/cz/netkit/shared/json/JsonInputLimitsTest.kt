package cz.netkit.shared.json

import cz.netkit.shared.speed.*
import cz.netkit.shared.ssh.*
import cz.netkit.shared.update.*
import cz.netkit.shared.wol.*
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** Exercise the real entry points, including ignored fields of typed network decoders. */
class JsonInputLimitsTest {
    private val policy = UpdateUrlPolicy("https://www.example.com/netkit")
    private val ssh = """{"format":"netkit-ssh-profiles","schema":1,"profiles":[{"alias":"test","host":"192.0.2.1","port":22,"username":"user"}],"skipped":[]}"""
    private val wol = encodeWolExport(listOf(WolDevice(id = "test", name = "test", mac = "00:11:22:33:44:55",
        targetMode = TargetMode.AUTO_BROADCAST, createdAt = 0, updatedAt = 0))).decodeToString()
    private val ip = """{"processedString":"192.0.2.1","rawIspInfo":{"ip":"192.0.2.1"}}"""
    private val locate = """{"results":[{"machine":"test","location":{"city":"Prague","country":"CZ"},"urls":{"wss:///ndt/v7/download":"wss://test.measurement-lab.org/ndt/v7/download","wss:///ndt/v7/upload":"wss://test.measurement-lab.org/ndt/v7/upload"}}]}"""
    private val measurement = """{"AppInfo":{"ElapsedTime":1000,"NumBytes":10},"TCPInfo":{"RTT":100},"BBRInfo":{"BW":10}}"""
    private val latest = """{"schema":1,"versionName":"1.0.0","versionCode":10000,"minSdk":29,"pageUrl":"https://www.example.com/netkit","notes":{"cs":"text"}}"""

    private fun extra(body: String, value: String) = body.dropLast(1) + ",\"future\":" + value + "}"

    @Test fun currentSchemasNeedAtMostFourLevels() {
        assertEquals(listOf(3, 3, 2, 4, 2, 2), listOf(ssh, wol, ip, locate, measurement, latest).map(::jsonNestingDepth))
        assertEquals(1, decodeSshProfileImport(ssh.encodeToByteArray()).profiles.size)
        assertEquals(1, decodeWolImport(wol.encodeToByteArray()).size)
        assertEquals("192.0.2.1", parseLibreSpeedIpInfo(ip)?.summary)
        assertEquals(1, parseNdt7Locate(locate).size)
        assertNotNull(parseNdt7Measurement(measurement))
        assertIs<UpdateParseResult.Ok>(parseLatestJson(latest, policy))
    }

    @Test fun sshRejectsArraysAndObjectsAboveLimitBeforeUnknownFieldValidation() {
        for (objects in listOf(false, true)) {
            assertEquals(SshProfileImportFailure.FORMAT, sshFailure(extra(ssh, nestedJson(JSON_MAX_DEPTH, objects))))
            val atLimit = extra(ssh, nestedJson(JSON_MAX_DEPTH - 1, objects))
            assertEquals(JSON_MAX_DEPTH, jsonNestingDepth(atLimit))
            // Strict import has no valid field this deep; UNKNOWN_FIELD proves the tree parsed.
            assertEquals(SshProfileImportFailure.UNKNOWN_FIELD, sshFailure(atLimit))
        }
    }

    @Test fun wolRejectsArraysAndObjectsAboveLimitBeforeUnknownFieldValidation() {
        for (objects in listOf(false, true)) {
            assertEquals(WolImportFailure.FORMAT, wolFailure(extra(wol, nestedJson(JSON_MAX_DEPTH, objects))))
            val atLimit = extra(wol, nestedJson(JSON_MAX_DEPTH - 1, objects))
            assertEquals(JSON_MAX_DEPTH, jsonNestingDepth(atLimit))
            assertEquals(WolImportFailure.UNKNOWN_FIELD, wolFailure(atLimit))
        }
    }

    @Test fun libreSpeedBoundsUnknownArraysAndObjects() {
        for (objects in listOf(false, true)) {
            assertNull(parseLibreSpeedIpInfo(extra(ip, nestedJson(JSON_MAX_DEPTH, objects))))
            val atLimit = extra(ip, nestedJson(JSON_MAX_DEPTH - 1, objects))
            assertEquals(JSON_MAX_DEPTH, jsonNestingDepth(atLimit))
            assertEquals("192.0.2.1", parseLibreSpeedIpInfo(atLimit)?.summary)
        }
    }

    @Test fun ndt7LocateBoundsUnknownArraysAndObjects() {
        for (objects in listOf(false, true)) {
            assertTrue(parseNdt7Locate(extra(locate, nestedJson(JSON_MAX_DEPTH, objects))).isEmpty())
            val atLimit = extra(locate, nestedJson(JSON_MAX_DEPTH - 1, objects))
            assertEquals(JSON_MAX_DEPTH, jsonNestingDepth(atLimit))
            assertEquals(1, parseNdt7Locate(atLimit).size)
        }
    }

    @Test fun ndt7MeasurementBoundsUnknownArraysAndObjects() {
        for (objects in listOf(false, true)) {
            assertNull(parseNdt7Measurement(extra(measurement, nestedJson(JSON_MAX_DEPTH, objects))))
            val atLimit = extra(measurement, nestedJson(JSON_MAX_DEPTH - 1, objects))
            assertEquals(JSON_MAX_DEPTH, jsonNestingDepth(atLimit))
            assertEquals(10.0, parseNdt7Measurement(atLimit)?.appInfo?.numBytes)
        }
    }

    @Test fun latestJsonKeepsItsExistingBoundary() {
        for (objects in listOf(false, true)) {
            val rejected = parseLatestJson(extra(latest, nestedJson(JSON_MAX_DEPTH, objects)), policy)
            assertEquals(UpdateParseError.INVALID_JSON, assertIs<UpdateParseResult.Rejected>(rejected).error)
            val atLimit = extra(latest, nestedJson(JSON_MAX_DEPTH - 1, objects))
            assertEquals(JSON_MAX_DEPTH, jsonNestingDepth(atLimit))
            assertIs<UpdateParseResult.Ok>(parseLatestJson(atLimit, policy))
        }
    }

    @Test fun escapedQuotesAndBracketsInsideStringsDoNotTriggerAnyInputLimit() {
        val text = "\\\"" + "[{".repeat(40) + "\\"
        val quoted = JsonPrimitive(text).toString()
        // Both strict imports must accept the text in a real schema field (not merely reject it).
        assertEquals(text, decodeSshProfileImport(ssh.replace("\"test\"", quoted).encodeToByteArray()).profiles.single().alias)
        assertEquals(text, decodeWolImport(wol.replace("\"note\":null", "\"note\":$quoted").encodeToByteArray()).single().note)
        assertEquals("192.0.2.1", parseLibreSpeedIpInfo(extra(ip, quoted))?.summary)
        assertEquals(1, parseNdt7Locate(extra(locate, quoted)).size)
        assertEquals(10.0, parseNdt7Measurement(extra(measurement, quoted))?.appInfo?.numBytes)
        assertIs<UpdateParseResult.Ok>(parseLatestJson(extra(latest, quoted), policy))
    }

    @Test fun hundredThousandArrayLevelsAreRejectedByImportsWithoutStackOverflow() {
        val text = nestedJson(100_000, objects = false)
        assertTrue(text.length < SSH_PROFILE_IMPORT_LIMIT)
        assertEquals(SshProfileImportFailure.FORMAT, sshFailure(text))
        assertEquals(WolImportFailure.FORMAT, wolFailure(text))
    }

    private fun sshFailure(text: String) = assertFailsWith<SshProfileImportException> {
        decodeSshProfileImport(text.encodeToByteArray())
    }.reason

    private fun wolFailure(text: String) = assertFailsWith<WolImportException> {
        decodeWolImport(text.encodeToByteArray())
    }.reason
}
