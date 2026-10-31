package cz.netkit.app.wol

import kotlin.test.Test
import kotlin.test.assertEquals

class WolNameOrderTest {
    @Test fun czechOrderingHandlesAccentsDigraphAndCanonicalEquivalentNames() {
        val order = czechWolNameComparator()
        assertEquals(listOf("cíl", "čaj", "Hana", "chata", "Ivo", "zebra", "žába"),
            listOf("Ivo", "žába", "chata", "čaj", "zebra", "Hana", "cíl").sortedWith(order))
        assertEquals(0, order.compare("ČAJ", "čaj"))
        assertEquals(0, order.compare("čaj", "c\u030caj"))
    }
}
