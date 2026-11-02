package cz.netkit.app.wifi

import cz.netkit.shared.wifi.OuiDatabase
import java.io.File
import kotlin.test.*

/**
 * The committed IEEE OUI asset as the app reads it, plus the CS/EN texts of the vendor line.
 * Unit tests run with the module directory as the working directory. No prefix or vendor is
 * hard-coded: lookups are derived from the asset's own lines.
 */
class OuiAssetTest {
    private val module = File(".").canonicalFile
    private val asset = File(module, "src/main/assets/$OUI_ASSET")

    @Test fun assetParsesCompletelyAndQuickly() {
        val text = asset.readText()
        val started = System.nanoTime()
        val database = OuiDatabase.parse(text)
        val parseMs = (System.nanoTime() - started) / 1_000_000
        val lines = text.lines().filter { it.isNotEmpty() }
        val header = lines.first()
        assertTrue(header.startsWith("# IEEE OUI Public Listing"), header)
        val total = assertNotNull(Regex("total=(\\d+)").find(header)).groupValues[1].toInt()
        assertEquals(lines.size - 1, total, "header count matches the data lines")
        assertEquals(total, database.size, "every line of the asset is a valid entry")
        assertTrue(total > 40_000, "MA-L + MA-M + MA-S present: $total")
        for (bits in listOf("24", "28", "36")) assertTrue(lines.any { it.split('\t').getOrNull(1) == bits }, "$bits-bit entries")
        assertFalse(lines.any { it.endsWith("\tPrivate") }, "Private entries are left out")
        // Orientation for the report (JVM on the build host, not the phone).
        println("OUI asset: ${asset.length()} B, $total entries, parse $parseMs ms")
    }

    @Test fun everyLineOfTheAssetIsFoundByItsOwnPrefix() {
        val database = OuiDatabase.parse(asset.readText())
        val rows = asset.readLines().filter { it.isNotEmpty() && !it.startsWith('#') }.map { it.split('\t') }
        val byPrefix = rows.associate { it[0] to it[2] }
        var checked = 0
        for (row in rows) {
            val hex = row[0] + "0".repeat(12 - row[0].length)
            val mac = hex.chunked(2).joinToString(":")
            // Reference: the longest listed prefix of this address (36, 28, then 24 bits).
            val expected = listOf(9, 7, 6).firstNotNullOfOrNull { byPrefix[hex.take(it)] }
                .takeIf { hex.take(2).toInt(16) and 0x03 == 0 }
            assertEquals(expected, database.vendorFor(mac), mac)
            checked++
        }
        assertEquals(rows.size, checked)
    }

    @Test fun vendorTextsExistInCzechAndEnglish() {
        val expected = mapOf(
            "values" to mapOf("wifi_vendor" to "Výrobce: %1\$s", "wifi_vendor_unknown" to "Výrobce neznámý"),
            "values-en" to mapOf("wifi_vendor" to "Vendor: %1\$s", "wifi_vendor_unknown" to "Vendor unknown"),
        )
        for ((folder, strings) in expected) {
            val xml = File(module, "src/main/res/$folder/strings_wifi.xml").readText()
            for ((name, value) in strings) assertTrue("<string name=\"$name\">$value</string>" in xml, "$folder/$name")
            assertTrue("<string name=\"wifi_vendor_local\">" in xml, "$folder/wifi_vendor_local kept")
            assertFalse("wifi_vendor_unavailable" in xml, "$folder: the old 'no database' text is gone")
        }
    }

    @Test fun loaderReadsOnceAndKeepsTheResult() {
        val loader = OuiLoader()
        var opens = 0
        val open = { opens++; java.io.StringReader("F40000\t24\tExample Vendor Synthetic\n") }
        assertEquals("Example Vendor Synthetic", loader.get(open).vendorFor("F4:00:00:00:00:01"))
        assertEquals(1, loader.get(open).size)
        assertEquals(1, opens, "parsed once per loader")
    }

    @Test fun missingAssetAndUnexpectedFailureBecomePermanentEmpty() {
        val failures: List<() -> java.io.Reader> = listOf(
            { throw java.io.FileNotFoundException(OUI_ASSET) },
            { throw IllegalStateException("synthetic parse failure") },
            { object : java.io.Reader() {
                override fun read(buffer: CharArray, offset: Int, length: Int): Int = throw java.io.IOException("synthetic read failure")
                override fun close() = Unit
            } },
        )
        for (failure in failures) {
            val loader = OuiLoader()
            var opens = 0
            val open = { opens++; failure() }
            assertSame(OuiDatabase.EMPTY, loader.get(open))
            assertSame(OuiDatabase.EMPTY, loader.get(open), "failure is stored, not retried")
            assertEquals(1, opens)
            assertNull(loader.get(open).vendorFor("F4:00:00:00:00:01"))
        }
    }

    @Test fun cancellationIsNotStored() {
        val loader = OuiLoader()
        var opens = 0
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            loader.get { opens++; throw kotlinx.coroutines.CancellationException("synthetic") }
        }
        assertEquals(1, loader.get { opens++; java.io.StringReader("F40000\t24\tExample\n") }.size)
        assertEquals(2, opens)
    }
}
