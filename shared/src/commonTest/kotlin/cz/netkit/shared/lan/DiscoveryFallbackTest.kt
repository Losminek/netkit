package cz.netkit.shared.lan

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryFallbackTest {
    @Test fun fourFallbacksOverlapInsteadOfAddingTimeouts() = runTest {
        val started=mutableListOf<Int>()
        val result=probeDiscoveryFallback { port -> started+=port;delay(400);TcpProbeResult(ProbeStatus.TIMEOUT) }
        assertEquals(setOf(80,443,22,445),started.toSet())
        assertEquals(400L,testScheduler.currentTime)
        assertEquals(4,result.size)
    }
    @Test fun cancellingHostCancelsEveryActiveFallback() = runTest {
        var active=0
        val job=launch { probeDiscoveryFallback { active++;try { delay(5000);TcpProbeResult(ProbeStatus.TIMEOUT) } finally { active-- } } }
        runCurrent();assertEquals(4,active)
        job.cancelAndJoin();assertEquals(0,active)
    }
}
