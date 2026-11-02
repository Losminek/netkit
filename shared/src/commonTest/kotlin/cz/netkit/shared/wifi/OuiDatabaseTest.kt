package cz.netkit.shared.wifi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Synthetic prefixes and names only; F4… is globally administered unicast (bits 0x01/0x02 clear). */
class OuiDatabaseTest {
    private val database = OuiDatabase.parse(
        "# synthetic fixture, format of tools/update_oui.py\n" +
            "F40000\t24\tExample Vendor L\n" +
            "F40001\t24\tExample Block Owner\n" +
            "F40001A\t28\tExample Vendor M\n" +
            "F40001ABC\t36\tExample Vendor S\n",
    )

    @Test fun longestPrefixWins() {
        assertEquals(4, database.size)
        assertEquals("Example Vendor S", database.vendorFor("F4:00:01:AB:C1:23"))
        assertEquals("Example Vendor M", database.vendorFor("F4:00:01:AB:D1:23"))
        assertEquals("Example Vendor M", database.vendorFor("F4:00:01:A0:00:00"))
        assertEquals("Example Block Owner", database.vendorFor("F4:00:01:B0:00:00"))
        assertEquals("Example Vendor L", database.vendorFor("F4:00:00:00:00:01"))
        assertNull(database.vendorFor("F4:00:02:00:00:01"))
    }

    @Test fun everyNotationIsAccepted() {
        for (mac in listOf("f4:00:01:ab:c1:23", "F4-00-01-AB-C1-23", "f40001abc123", "F40001ABC123", " F4:00:01:AB:C1:23 ")) {
            assertEquals("Example Vendor S", database.vendorFor(mac), mac)
        }
    }

    @Test fun locallyAdministeredAndMulticastHaveNoVendor() {
        val local = OuiDatabase.parse("F60000\t24\tExample Local\nF50000\t24\tExample Multicast\nF70000\t24\tExample Both")
        assertNull(local.vendorFor("F6:00:00:00:00:01")) // 0x02 locally administered
        assertNull(local.vendorFor("F5:00:00:00:00:01")) // 0x01 multicast
        assertNull(local.vendorFor("F7:00:00:00:00:01"))
        assertNull(database.vendorFor("02:00:00:00:00:00")) // Android's redacted BSSID
        assertNull(database.vendorFor("FF:FF:FF:FF:FF:FF"))
    }

    @Test fun invalidInputHasNoVendor() {
        for (mac in listOf(null, "", "F4:00:01", "F4:00:01:AB:C1", "F4:00:01:AB:C1:23:45", "F4:00-01:AB:C1:23",
            "F4.00.01.AB.C1.23", "G4:00:01:AB:C1:23", "F4:00:01:AB:C1:2", "F40001ABC12", "F40001ABC1234",
            "F4::01:AB:C1:23:4", "+4:00:01:AB:C1:23", "F4:00:01:AB:C1:\uFF12\uFF13")) {
            assertNull(database.vendorFor(mac), mac.toString())
        }
    }

    @Test fun emptyOrDamagedFileNeverMatches() {
        for (text in listOf("", "# header only\n", "F40000 24 no tabs", "F40000\t25\tWrong bits", "F4000\t24\tShort",
            "F4000G\t24\tBad hex", "F40000\t24\t   ", "F40000\t24\tA\tExtra column")) {
            val parsed = OuiDatabase.parse(text)
            assertEquals(0, parsed.size, text)
            assertNull(parsed.vendorFor("F4:00:00:00:00:01"), text)
        }
        assertNull(OuiDatabase.EMPTY.vendorFor("F4:00:00:00:00:01"))
    }

    @Test fun damagedLinesAreSkippedAndFirstDuplicateWins() {
        val parsed = OuiDatabase.parse("F40000\t24\tFirst\r\nbroken\nF40000\t24\tSecond\nF40002\t24\t  Trimmed  \n")
        assertEquals(2, parsed.size)
        assertEquals("First", parsed.vendorFor("F40000000001"))
        assertEquals("Trimmed", parsed.vendorFor("F40002000001"))
    }
}
