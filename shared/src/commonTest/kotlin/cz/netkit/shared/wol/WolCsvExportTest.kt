package cz.netkit.shared.wol

import kotlin.test.*

class WolCsvExportTest {
    private val header = "\"machine_id\",\"machine_name\",\"machine_broadcastip\",\"machine_mac\",\"machine_port\",\"machine_colour\",\"machine_sort\",\"group_id\",\"group_name\",\"group_sort\",\"machine_ip\",\"secure_on\",\"machine_ping_port\",\"machine_ping_ssid\""
    private val now = 1234L
    private val groupOf: (WolDevice) -> String? = { wolGroupFromNote(it.note, "Skupina:") }
    private fun device(id: String = "local", note: String? = "Skupina: Chata") = WolDevice(
        id = id, name = "NAS", mac = "02:00:5E:10:00:03", targetMode = TargetMode.CUSTOM,
        customTarget = "192.168.10.255", port = 7, probeEnabled = true,
        probeHost = "192.168.10.4", probePort = 22, note = note, createdAt = now, updatedAt = now)
    private fun encode(devices: List<WolDevice>) = encodeWolCsvExport(devices, groupOf, "Výchozí")
    private fun decode(bytes: ByteArray) = decodeWolCsvImport(bytes, now) { "Skupina: $it" }.also {
        assertTrue(it.skipped.isEmpty())
        assertEquals(0, it.secureOnDropped)
    }.devices

    @Test fun roundTripPreservesSupportedFieldsAndOriginalOrderAcrossGroups() {
        val devices = listOf(device("webb-8"), device("webb-2", "Skupina: Doma"),
            device("webb-5").copy(name = "PC", probeEnabled = false, probeHost = null, probePort = null))
        assertEquals(devices, decode(encode(devices)))
    }

    @Test fun originalImportSampleRoundTrips() {
        // Same synthetic export as WolCsvImportTest: short group rows, non-list ID order,
        // a dashed MAC, and disabled probes encoded as both -1 and 0.
        val sample = """
            "machine_id","machine_name","machine_broadcastip","machine_mac","machine_port","machine_colour","machine_sort","group_id","group_name","group_sort","machine_ip","secure_on","machine_ping_port","machine_ping_ssid"
            "","","","","","","","0","Chata","-1","",""
            "2","NAS chata","192.168.10.255","02:00:5E:10:00:01","9","#99CC00","2","0","","","192.168.10.4","","-1",""
            "3","Pokoj PC","192.168.10.255","02:00:5E:10:00:02","9","#FFBB33","3","0","","","192.168.10.25","","0",""
            "","","","","","","","1","doma ","0","",""
            "1","NAS","192.168.10.255","02-00-5E-10-00-03","9","#AA66CC","1","1","","","192.168.10.4","","-1",""
        """.trimIndent()
        val devices = decode(sample.encodeToByteArray())
        assertEquals(3, devices.size)
        assertEquals(devices, decode(encode(devices)))
    }

    @Test fun exactWireFormatHasShortGroupRowsAndFourteenQuotedDeviceFields() {
        val bytes = encode(listOf(device("webb-3").copy(mac = "02-00-5e-10-00-03")))
        val expected = header + "\n" +
            "\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"0\",\"Chata\",\"0\",\"\",\"\"\n" +
            "\"3\",\"NAS\",\"192.168.10.255\",\"02:00:5E:10:00:03\",\"7\",\"#33B5E5\",\"0\",\"0\",\"\",\"\",\"192.168.10.4\",\"\",\"22\",\"\"\n"
        assertContentEquals(expected.encodeToByteArray(), bytes)
        assertEquals(12, bytes.decodeToString().lines()[1].split(',').size)
        assertEquals(14, bytes.decodeToString().lines()[2].split(',').size)
        assertFalse(bytes.decodeToString().startsWith('\uFEFF'))
        assertFalse(bytes.decodeToString().contains('\r'))
    }

    @Test fun nameWithCommaQuoteAndNewlineIsEscapedWithoutChangingExportedValue() {
        val bytes = encode(listOf(device().copy(name = "Síť, \"chata\"\npatro")))
        assertTrue(bytes.decodeToString().contains("\"Síť, \"\"chata\"\"\npatro\""))
        // Import deliberately sanitizes names to one line.
        assertEquals("Síť, \"chata\" patro", decode(bytes).single().name)
    }

    @Test fun groupsFollowFirstOccurrenceAndDevicesFollowTheirGroup() {
        val text = encode(listOf(device("webb-8", "Skupina: Doma"), device("webb-2"),
            device("webb-5", "Skupina: Doma"))).decodeToString()
        val lines = text.trimEnd('\n').lines()
        assertEquals(6, lines.size)
        assertEquals("\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"0\",\"Doma\",\"0\",\"\",\"\"", lines[1])
        assertTrue(lines[2].startsWith("\"8\","))
        assertTrue(lines[3].startsWith("\"5\","))
        assertTrue(lines[3].contains("\"#33B5E5\",\"2\",\"0\""))
        assertEquals("\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"1\",\"Chata\",\"1\",\"\",\"\"", lines[4])
        assertTrue(lines[5].startsWith("\"2\","))
    }

    @Test fun bothBroadcastModesExportLimitedBroadcastAddress() {
        for (mode in listOf(TargetMode.AUTO_BROADCAST, TargetMode.LIMITED_BROADCAST)) {
            val imported = decode(encode(listOf(device().copy(targetMode = mode, customTarget = null)))).single()
            assertEquals(TargetMode.CUSTOM, imported.targetMode)
            assertEquals("255.255.255.255", imported.customTarget)
        }
    }

    @Test fun disabledOrPortlessProbeExportsMinusOne() {
        for (source in listOf(device().copy(probeEnabled = false), device().copy(probePort = null))) {
            val bytes = encode(listOf(source))
            assertTrue(bytes.decodeToString().lines()[2].endsWith(",\"192.168.10.4\",\"\",\"-1\",\"\""))
            val imported = decode(bytes).single()
            assertFalse(imported.probeEnabled)
            assertEquals("192.168.10.4", imported.probeHost)
            assertNull(imported.probePort)
        }
    }

    @Test fun generatedIdsReserveLaterWebbIdsBeforeAllocation() {
        val devices = (0..3).map { device("local-$it") } + device("webb-3")
        assertEquals(listOf("webb-0", "webb-1", "webb-2", "webb-4", "webb-3"),
            decode(encode(devices)).map { it.id })
        assertContentEquals(encode(devices), encode(devices))
    }

    @Test fun numericIdsDoNotOverflowAndLeadingZeroAliasesDoNotCollide() {
        val longId = "webb-" + "9".repeat(59)
        val devices = listOf(device("local"), device("webb-00"), device("webb-0"),
            device(longId), device("webb-invalid"))
        assertEquals(listOf("webb-1", "webb-00", "webb-2", longId, "webb-3"),
            decode(encode(devices)).map { it.id })
    }

    @Test fun missingGroupUsesDefaultAndNeverExportsUnrelatedNote() {
        val devices = listOf(device("webb-1", null), device("webb-2", "private-note-marker"),
            device("webb-3", "Skupina:  "), device("webb-4", "Skupina: Výchozí"))
        val bytes = encode(devices)
        assertFalse(bytes.decodeToString().contains("private-note-marker"))
        assertEquals(List(4) { "Skupina: Výchozí" }, decode(bytes).map { it.note })
        assertEquals(6, bytes.decodeToString().trimEnd('\n').lines().size)
    }

    @Test fun groupCallbackIsUsedAndGroupNamesAreQuoted() {
        val bytes = encodeWolCsvExport(listOf(device()), { "Dům, \"sever\"" }, "Default")
        assertTrue(bytes.decodeToString().contains("\"Dům, \"\"sever\"\"\""))
        assertEquals("Skupina: Dům, \"sever\"", decode(bytes).single().note)
        val blank = encodeWolCsvExport(listOf(device()), { "  " }, "Default")
        assertEquals("Skupina: Default", decode(blank).single().note)
    }

    @Test fun secureOnIsEmptyInEveryRecordAndRepeatSettingsAreNotTransferred() {
        val source = device("webb-3").copy(repeatCount = 9, repeatIntervalMs = 1000, probeDelaySeconds = 90)
        val bytes = encode(listOf(source, device("webb-4", "Skupina: Doma")))
        bytes.decodeToString().trimEnd('\n').lines().drop(1).forEach { line ->
            assertEquals("\"\"", line.split(',')[11])
        }
        val imported = decode(bytes).first()
        assertEquals(3, imported.repeatCount)
        assertEquals(250, imported.repeatIntervalMs)
        assertEquals(20, imported.probeDelaySeconds)
    }

    @Test fun emptyListProducesOnlyHeader() {
        assertContentEquals((header + "\n").encodeToByteArray(),
            encodeWolCsvExport(emptyList(), { error("No devices to group") }, "Výchozí"))
    }

    @Test fun groupFromNoteRequiresExactLocalizedPrefixAndTrimsGroupWhitespace() {
        assertEquals("Chata", wolGroupFromNote("Skupina: Chata", "Skupina:"))
        assertEquals("Chata", wolGroupFromNote("Skupina:   Chata \t", "Skupina: "))
        assertEquals("Chata", wolGroupFromNote("Skupina:Chata", "Skupina:"))
        assertEquals("Home", wolGroupFromNote("Group: Home", "Group: "))
        for (note in listOf(null, "", "Chata", "Moje Skupina: Chata", " Skupina: Chata",
            "skupina: Chata", "Skupina: \t", "Group: Home")) {
            assertNull(wolGroupFromNote(note, "Skupina:"), note)
        }
        assertNull(wolGroupFromNote("Skupina:Chata", "Skupina: "))
        assertNull(wolGroupFromNote("Chata", ""))
    }
}
