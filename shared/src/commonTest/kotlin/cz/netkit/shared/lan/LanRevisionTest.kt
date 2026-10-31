package cz.netkit.shared.lan

import kotlinx.serialization.json.*
import kotlin.test.*

class LanRevisionTest {
    @Test fun confirmationBoundariesAndReasons() {
        val own=Ipv4Subnet.parse("10.0.0.0/16")!!
        val small=Ipv4Subnet.parse("10.0.0.0/24")!!
        assertFalse(requiresTargetConfirmation(small,own,254))
        assertEquals(setOf(TargetConfirmationReason.LARGE_PORT_SET),targetConfirmationReasons(small,own,255))
        val large=Ipv4Subnet.parse("10.0.0.0/20")!!
        assertEquals(4094,large.hosts().size)
        assertTrue(requiresTargetConfirmation(large,own))
        assertFailsWith<IllegalArgumentException>{Ipv4Subnet.parse("10.0.0.0/19")!!.hosts()}
        listOf("127.0.0.1/32","0.0.0.0/32","224.0.0.1/32").forEach { value ->
            val range=Ipv4Subnet.parse(value)!!
            assertTrue(requiresTargetConfirmation(range,range))
            assertTrue(TargetConfirmationReason.NON_PRIVATE_ADDRESS in targetConfirmationReasons(range,range))
        }
        assertTrue(TargetConfirmationReason.OUTSIDE_WIFI in targetConfirmationReasons(Ipv4Subnet.parse("10.1.0.1/32")!!,own))
    }
    @Test fun specialAddressesNeverReachPtrResolver() {
        listOf("127.0.0.1","127.255.255.254","0.0.0.0","0.1.2.3","224.0.0.1","239.255.255.255","255.255.255.255","bad").forEach {
            assertFalse(shouldQueryLanPtr(it))
        }
        assertTrue(shouldQueryLanPtr("192.168.1.2"))
        assertTrue(shouldQueryLanPtr("10.0.2.2"))
    }
    @Test fun numericAndPortSyntaxCannotUseOctalOrSigns() {
        assertNull(ipv4Number("010.0.0.1"))
        assertFalse(validLanHost("010.0.0.1"))
        assertFalse(validLanHost("1.2.3.999"))
        assertFalse(validLanHost("router\n.local"))
        assertTrue(validLanHost("nas.local"))
        assertTrue(validLanHost("127.0.0.1"))
        assertEquals(listOf(22),parsePorts("22-22"))
        assertNull(parsePorts("+22"));assertNull(parsePorts("22-+23"))
    }
    @Test fun metadataCannotInjectRowsOrBidiFormatting() {
        assertEquals("a b c",sanitizeLanText("a\nb\u202ec"))
        assertEquals("abc",sanitizeLanText("abcdef",3))
        assertEquals("nas.local",safePtrName("nas.local."))
        listOf("a..local","-a.local","a-.local","a\n.local","a\u200d.local","x".repeat(64)+".local").forEach {assertNull(safePtrName(it))}
    }
    @Test fun metadataSanitizationHandlesSupplementaryCodePointsAndMalformedSurrogates() {
        // U+E0001 LANGUAGE TAG and U+E0061 TAG LATIN SMALL LETTER A are supplementary Cf.
        assertEquals("a b c",sanitizeLanText("a\uDB40\uDC01b\uDB40\uDC61c"))
        assertEquals("a😀b",sanitizeLanText("a😀b"))
        assertEquals("a\uFFFDb\uFFFDc",sanitizeLanText("a\uD800b\uDC00c"))
        assertEquals("",sanitizeLanText("😀",1))
        assertEquals("😀",sanitizeLanText("😀x",2))
        assertEquals("a",sanitizeLanText("a😀b",2))
        assertEquals("a😀",sanitizeLanText("a😀b",3))
        assertEquals("a ",sanitizeLanText("a\uDB40\uDC01b",2))
        assertEquals("",sanitizeLanText("x",0))
        assertFailsWith<IllegalArgumentException> { sanitizeLanText("x",-1) }
    }
    @Test fun pingSequenceAndTimeSurviveWindowEviction() {
        var rows=emptyList<LanResult>()
        repeat(605){i->rows=appendPingResult(rows,"127.0.0.1",i.toLong()+1,i*1000L,if(i%2==0)1.0 else null)}
        assertEquals(600,rows.size)
        assertEquals(6L,rows.first().sequence);assertEquals(605L,rows.last().sequence)
        assertEquals(604000L,rows.last().elapsedMs)
        assertFailsWith<IllegalArgumentException>{appendPingResult(rows,"127.0.0.1",605,605000,1.0)}
        assertFailsWith<IllegalArgumentException>{appendPingResult(rows,"127.0.0.1",606,603000,1.0)}
    }
    @Test fun jsonIncludesDefaultsAndRoundedRttAndRouteNote() {
        val row=LanResult("127.0.0.1",milliseconds=1.23456,routeMayBeOutsideWifi=true)
        val json=Json.parseToJsonElement(exportLan(listOf(row),LanExport.JSON)).jsonArray.single().jsonObject
        assertEquals(1.235,json.getValue("milliseconds").jsonPrimitive.double)
        assertEquals("",json.getValue("name").jsonPrimitive.content)
        assertEquals(JsonNull,json.getValue("port"))
        assertTrue(json.getValue("routeMayBeOutsideWifi").jsonPrimitive.boolean)
        assertTrue(exportLan(listOf(row),LanExport.TEXT).contains("routeMayBeOutsideWifi=true"))
        assertEquals(1,exportLan(listOf(LanResult("127.0.0.1",name="forged\nrow")),LanExport.TEXT).lines().size)
    }
}
