package cz.netkit.shared.lan

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedProbesTest {
    @Test fun boundedWorkersAndCancellationReleaseEveryActiveProbe() = runTest {
        var active=0; var peak=0; var completed=0
        val job=launch {
            probeBounded((1..4096).toList(),3) {
                active++; peak=maxOf(peak,active)
                try { delay(5000); completed++ } finally { active-- }
            }
        }
        runCurrent()
        assertEquals(3,peak); assertEquals(3,active)
        job.cancelAndJoin()
        assertEquals(0,active); assertEquals(0,completed)
        assertEquals(0L,testScheduler.currentTime)
    }
    @Test fun eachTargetIsProbedOnce() = runTest {
        val found=mutableListOf<Int>()
        probeBounded((1..100).toList(),7) { delay(1); found+=it }
        assertEquals((1..100).toList(),found.sorted())
        assertFailsWith<IllegalArgumentException> { probeBounded(listOf(1),65) {} }
    }
}
