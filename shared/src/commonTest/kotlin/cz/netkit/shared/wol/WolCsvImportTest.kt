package cz.netkit.shared.wol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WolCsvImportTest {
    private val columns = listOf("machine_id", "machine_name", "machine_broadcastip", "machine_mac",
        "machine_port", "machine_colour", "machine_sort", "group_id", "group_name", "group_sort",
        "machine_ip", "secure_on", "machine_ping_port", "machine_ping_ssid")
    private val now = 1234L
    private val note: (String) -> String = { "Skupina: $it" }
    private fun record(values: List<String>) = values.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
    private fun row(vararg changes: Pair<String, String>): List<String> {
        val values = mapOf("machine_id" to "1", "machine_name" to "NAS", "machine_broadcastip" to "192.168.10.255",
            "machine_mac" to "02:00:5E:10:00:03", "machine_port" to "9", "machine_sort" to "1",
            "machine_ip" to "192.168.10.4", "machine_ping_port" to "-1") + changes.toMap()
        return columns.map { values[it].orEmpty() }
    }
    private fun csv(vararg rows: List<String>) = (listOf(record(columns)) + rows.map(::record)).joinToString("\n")
    private fun decode(text: String) = decodeWolCsvImport(text.encodeToByteArray(), now, note)
    private fun failure(reason: WolImportFailure, text: String) =
        assertEquals(reason, assertFailsWith<WolImportException> { decode(text) }.reason)
    private val sample = """
        "machine_id","machine_name","machine_broadcastip","machine_mac","machine_port","machine_colour","machine_sort","group_id","group_name","group_sort","machine_ip","secure_on","machine_ping_port","machine_ping_ssid"
        "","","","","","","","0","Chata","-1","",""
        "2","NAS chata","192.168.10.255","02:00:5E:10:00:01","9","#99CC00","2","0","","","192.168.10.4","","-1",""
        "3","Pokoj PC","192.168.10.255","02:00:5E:10:00:02","9","#FFBB33","3","0","","","192.168.10.25","","0",""
        "","","","","","","","1","doma ","0","",""
        "1","NAS","192.168.10.255","02-00-5E-10-00-03","9","#AA66CC","1","1","","","192.168.10.4","","-1",""
    """.trimIndent()

    @Test fun sampleMapsGroupsShortRowsMacAndOrder() {
        val result = decode(sample)
        assertEquals(listOf("webb-1", "webb-2", "webb-3"), result.devices.map { it.id })
        assertEquals(listOf("NAS", "NAS chata", "Pokoj PC"), result.devices.map { it.name })
        assertEquals(listOf("Skupina: doma", "Skupina: Chata", "Skupina: Chata"), result.devices.map { it.note })
        assertEquals(listOf("02:00:5E:10:00:03", "02:00:5E:10:00:01", "02:00:5E:10:00:02"), result.devices.map { it.mac })
        result.devices.forEach {
            assertEquals(TargetMode.CUSTOM, it.targetMode)
            assertEquals("192.168.10.255", it.customTarget)
            assertEquals(9, it.port)
            assertEquals(now, it.createdAt)
            assertEquals(now, it.updatedAt)
            assertFalse(it.probeEnabled)
            assertEquals(true, it.probeHost?.startsWith("192.168.10."))
            assertNull(it.probePort)
        }
        assertTrue(validateImportedDevices(result.devices))
        assertTrue(result.skipped.isEmpty())
        assertEquals(0, result.secureOnDropped)
    }
    @Test fun bomCrLfAndFinalNewline() {
        assertEquals(decode(sample), decode("\uFEFF" + sample.replace("\n", "\r\n") + "\r\n"))
    }
    @Test fun quotedCommaEscapedQuoteAndEmbeddedNewlinesBecomeOneLine() {
        // Quoted fields may span lines (RFC 4180); the name is shown on one line.
        val name = "NAS, \"chata\"\r\npatro\n2"
        assertEquals("NAS, \"chata\" patro 2", decode(csv(row("machine_name" to name))).devices.single().name)
    }
    @Test fun controlCharactersAndLineSeparatorsInNamesBecomeSpaces() {
        val result = decode(csv(row("group_id" to "8", "machine_name" to "\tNAS\u0000\u2028patro\u0085\u2029"),
            row("machine_id" to "", "group_id" to "8", "group_name" to "Cha\u001Bta\r\n")))
        assertEquals("NAS patro", result.devices.single().name)
        assertEquals("Skupina: Cha ta", result.devices.single().note)
        assertEquals("a b", singleLineText("\u2028a\u0007\u0007b\n"))
        assertEquals("", singleLineText("\r\n\t"))
    }
    @Test fun groupNoteFormatComesFromTheCaller() {
        val csvText = csv(row("group_id" to "8"), row("machine_id" to "", "group_id" to "8", "group_name" to "Home"))
        val english = decodeWolCsvImport(csvText.encodeToByteArray(), now) { "Group: $it" }
        assertEquals("Group: Home", english.devices.single().note)
    }
    @Test fun headerDeterminesOrderAndUnknownColumnsAreIgnored() {
        val header = columns.reversed() + "future_column"
        val body = row().reversed() + "ignored"
        assertEquals(decode(csv(row())), decode(record(header) + "\n" + record(body)))
    }
    @Test fun missingRequiredAndDuplicateColumnsFail() {
        failure(WolImportFailure.FORMAT, csv(row()).replace("\"machine_mac\"", "\"unknown\""))
        failure(WolImportFailure.FORMAT, record(columns + "machine_mac") + "\n" + record(row()))
    }
    @Test fun groupsMayFollowDevicesAndWhitespaceIsTrimmed() {
        val result = decode(csv(row("group_id" to "8", "machine_name" to " NAS "),
            row("machine_id" to "", "group_id" to "8", "group_name" to " Chata ")))
        assertEquals("Skupina: Chata", result.devices.single().note)
        assertEquals("NAS", result.devices.single().name)
        assertNull(decode(csv(row("group_id" to "missing"))).devices.single().note)
    }
    @Test fun minusOneAndZeroDisableProbeAndTwentyTwoEnablesTcp() {
        for (port in listOf("-1", "0")) {
            val device = decode(csv(row("machine_ping_port" to port, "machine_ip" to "ignored"))).devices.single()
            assertFalse(device.probeEnabled)
            assertNull(device.probeHost)
            assertNull(device.probePort)
            val kept = decode(csv(row("machine_ping_port" to port, "machine_ip" to "192.168.10.9"))).devices.single()
            assertFalse(kept.probeEnabled)
            assertEquals("192.168.10.9", kept.probeHost)
            assertNull(kept.probePort)
        }
        val device = decode(csv(row("machine_ping_port" to "22"))).devices.single()
        assertTrue(device.probeEnabled)
        assertEquals(22, device.probePort)
        assertEquals("192.168.10.4", device.probeHost)
    }
    @Test fun secureOnOnlyEscapesAsCountIncludingSkippedDevices() {
        val sentinel = "synthetic-secure-on-marker"
        val result = decode(csv(row("secure_on" to sentinel), row("machine_id" to "2",
            "machine_mac" to "invalid", "secure_on" to sentinel)))
        assertEquals(2, result.secureOnDropped)
        assertFalse(result.toString().contains(sentinel))
        assertFalse(encodeWolExport(result.devices).decodeToString().contains(sentinel))
        val error = assertFailsWith<WolImportException> {
            decode(csv(row("machine_mac" to "invalid", "secure_on" to sentinel)))
        }
        assertFalse(error.toString().contains(sentinel))
        assertNull(error.cause)
        val syntaxError = assertFailsWith<WolImportException> { decode(record(columns) + "\n\"$sentinel") }
        assertFalse(syntaxError.toString().contains(sentinel))
        assertNull(syntaxError.cause)
    }
    @Test fun invalidDeviceRowsHaveNamedReasonsAndDoNotDiscardValidRows() {
        val cases = listOf(
            Triple("machine_id", "oops", WolCsvSkipReason.ID),
            Triple("machine_id", "1".repeat(60), WolCsvSkipReason.ID),
            Triple("machine_name", " ", WolCsvSkipReason.NAME),
            Triple("machine_name", "x".repeat(101), WolCsvSkipReason.NAME),
            Triple("machine_mac", "broken", WolCsvSkipReason.MAC),
            Triple("machine_port", "0", WolCsvSkipReason.PORT),
            Triple("machine_port", "65536", WolCsvSkipReason.PORT),
            Triple("machine_broadcastip", "192.168.10.256", WolCsvSkipReason.TARGET_IP),
            Triple("machine_ping_port", "65536", WolCsvSkipReason.PROBE_PORT),
            Triple("machine_ping_port", "-2", WolCsvSkipReason.PROBE_PORT),
            Triple("machine_ping_port", "oops", WolCsvSkipReason.PROBE_PORT),
            Triple("machine_ip", "999.1.1.1", WolCsvSkipReason.PROBE_IP),
        )
        for ((field, value, reason) in cases) {
            val result = decode(csv(row(), row("machine_id" to "2", "machine_name" to "Rejected",
                "machine_ping_port" to "22", field to value)))
            assertEquals(1, result.devices.size, field)
            assertEquals(reason, result.skipped.single().reason, field)
            assertEquals(if (field == "machine_name") value.trim() else "Rejected", result.skipped.single().machineName)
        }
    }
    @Test fun duplicateDeviceIdIsReportedAndFirstValidRowWins() {
        val result = decode(csv(row(), row("machine_name" to "Duplicate")))
        assertEquals("NAS", result.devices.single().name)
        assertEquals(WolCsvSkip("Duplicate", WolCsvSkipReason.DUPLICATE_ID), result.skipped.single())
    }
    @Test fun noValidDeviceFails() {
        failure(WolImportFailure.INVALID_DEVICE, csv(row("machine_mac" to "invalid")))
        failure(WolImportFailure.INVALID_DEVICE, csv(row("machine_id" to "")))
        failure(WolImportFailure.INVALID_DEVICE, csv())
    }
    @Test fun malformedQuotesAndBareCrFailWithoutSourceData() {
        for (body in listOf("\"unclosed", "\"closed\"junk", "un\"quoted", "a\rb")) {
            failure(WolImportFailure.FORMAT, record(columns) + "\n" + body)
        }
    }
    @Test fun byteRecordAndFieldLimits() {
        assertEquals(WolImportFailure.SIZE, assertFailsWith<WolImportException> {
            decodeWolCsvImport(ByteArray(WOL_TRANSFER_LIMIT + 1), now, note)
        }.reason)
        val rows = (1..500).map { row("machine_id" to it.toString()) }
        assertEquals(500, decode(csv(*rows.toTypedArray())).devices.size)
        failure(WolImportFailure.SIZE, csv(*(rows + listOf(row("machine_id" to ""))).toTypedArray()))
        assertEquals(1, decode(csv(row("machine_colour" to "x".repeat(1000)))).devices.size)
        failure(WolImportFailure.SIZE, csv(row("machine_colour" to "x".repeat(1001))))
        failure(WolImportFailure.SIZE, csv(row("secure_on" to "x".repeat(1001))))
        // Field count per record is capped too: 1 MiB of commas fails fast with SIZE.
        failure(WolImportFailure.SIZE, record(columns) + "\n" + ",".repeat(WOL_TRANSFER_LIMIT - columns.size * 20))
        failure(WolImportFailure.SIZE, ",".repeat(WOL_TRANSFER_LIMIT))
        val wide = columns + (columns.size until WOL_CSV_MAX_FIELDS).map { "extra_$it" }
        assertEquals(1, decode(record(wide) + "\n" + record(row() + List(wide.size - columns.size) { "" })).devices.size)
        failure(WolImportFailure.SIZE, record(wide + "one_too_many") + "\n" + record(row()))
    }
    @Test fun invalidUtf8IsRejectedByBothEntryPoints() {
        val bytes = byteArrayOf(0xc3.toByte(), 0x28)
        assertEquals(WolImportFailure.UTF8, assertFailsWith<WolImportException> { decodeWolCsvImport(bytes, now, note) }.reason)
        assertEquals(WolImportFailure.UTF8, assertFailsWith<WolImportException> { decodeWolImportAny(bytes, now, note) }.reason)
    }
    @Test fun sortingUsesNumericValueOrFileIndexAndKeepsTiesStable() {
        val result = decode(csv(row("machine_id" to "1", "machine_sort" to "10"),
            row("machine_id" to "2", "machine_sort" to "oops"),
            row("machine_id" to "3", "machine_sort" to "1"),
            row("machine_id" to "4", "machine_sort" to "-1")))
        assertEquals(listOf("webb-4", "webb-2", "webb-3", "webb-1"), result.devices.map { it.id })
    }
    @Test fun longGroupNoteIsReportedAndInvalidClockFailsValidation() {
        val result = decode(csv(row(), row("machine_id" to "2", "group_id" to "8"),
            row("machine_id" to "", "group_id" to "8", "group_name" to "x".repeat(1000))))
        assertEquals(WolCsvSkipReason.NOTE, result.skipped.single().reason)
        assertEquals(WolImportFailure.INVALID_DEVICE, assertFailsWith<WolImportException> {
            decodeWolCsvImport(sample.encodeToByteArray(), -1, note)
        }.reason)
    }
    @Test fun anyDispatchPreservesJsonValidationAndCsvSummary() {
        val devices = decode(sample).devices
        val json = encodeWolExport(devices)
        assertEquals(WolCsvImport(devices, emptyList(), 0), decodeWolImportAny(json, 9999, note))
        assertEquals(WolCsvImport(devices, emptyList(), 0),
            decodeWolImportAny("\uFEFF \r\n".encodeToByteArray() + json, now, note))
        assertEquals(decode(sample), decodeWolImportAny(sample.encodeToByteArray(), now, note))
        assertEquals(WolImportFailure.SCHEMA, assertFailsWith<WolImportException> {
            decodeWolImportAny(json.decodeToString().replace("\"schema\":2", "\"schema\":99").encodeToByteArray(), now, note)
        }.reason)
    }
    @Test fun repeatedImportUpdatesInsteadOfDuplicating() {
        val first = decode(sample).devices
        val second = decodeWolImportAny(sample.encodeToByteArray(), now + 1, note).devices
        val merged = mergeWolDevices(first, second)
        assertEquals(3, merged.size)
        assertEquals(second, merged)
    }
    @Test fun previewCountsAreReplacedCancelledAndClearedAfterSave() = runTest {
        var saved = emptyList<WolDevice>()
        val controller = WolController(
            store = object : WolDeviceStore {
                override suspend fun load() = saved
                override suspend fun save(devices: List<WolDevice>) { saved = devices }
            },
            networkInfo = object : NetworkInfoProvider { override suspend fun currentWifi(): WifiNetworkInfo? = null },
            sender = object : PacketSender {
                override suspend fun sendUdp(network: WifiNetworkHandle, host: String, port: Int, payload: ByteArray) {}
            }, scope = this, nowMillis = { now }, newId = { "unused" }, monotonicMillis = { 0L })
        runCurrent()
        val devices = decode(sample).devices
        assertTrue(controller.previewImport(devices, 2, 1))
        assertEquals(2, controller.state.value.skippedCount)
        assertEquals(1, controller.state.value.secureOnDropped)
        controller.cancelImport()
        assertNull(controller.state.value.pendingImport)
        assertEquals(0, controller.state.value.skippedCount)
        assertEquals(0, controller.state.value.secureOnDropped)
        controller.previewImport(devices, 3, 2)
        controller.previewImport(devices) // A later JSON import has no CSV warnings.
        assertEquals(0, controller.state.value.skippedCount)
        assertEquals(0, controller.state.value.secureOnDropped)
        controller.previewImport(devices, 3, 2)
        controller.confirmImport()
        runCurrent()
        assertEquals(devices, saved)
        assertNull(controller.state.value.pendingImport)
        assertEquals(0, controller.state.value.skippedCount)
        assertEquals(0, controller.state.value.secureOnDropped)
    }
}
