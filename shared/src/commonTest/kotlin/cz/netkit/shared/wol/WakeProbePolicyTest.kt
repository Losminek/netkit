package cz.netkit.shared.wol

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WakeProbePolicyTest {
    @Test fun pollsUntilResponseAndUsesMonotonicDeadline() = runTest {
        val times = mutableListOf<Long>()
        val result = pollWakeResponse(10_000, {testScheduler.currentTime}) {
            times += testScheduler.currentTime
            if (times.size == 3) WakeProbeStatus.RESPONDS else WakeProbeStatus.NO_RESPONSE
        }
        assertEquals(listOf(0L, 2000L, 4000L), times)
        assertEquals(WakeProbeStatus.RESPONDS, result)
    }
    @Test fun deadlineBoundsFinalDelayAndDistinguishesUnavailableFromNoResponse() = runTest {
        val calls = mutableListOf<Long>()
        assertEquals(WakeProbeStatus.NO_RESPONSE, pollWakeResponse(5000, {testScheduler.currentTime}) {
            calls += testScheduler.currentTime; WakeProbeStatus.NO_RESPONSE
        })
        assertEquals(listOf(0L, 2000L, 4000L), calls)
        assertEquals(5000L, testScheduler.currentTime)
        assertEquals(WakeProbeStatus.UNAVAILABLE, pollWakeResponse(3000, {testScheduler.currentTime}) {
            awaitCancellation()
        })
        assertEquals(8000L, testScheduler.currentTime)
    }
    @Test fun internalTimeoutIsUnavailableButUserCancellationPropagates() = runTest {
        assertEquals(WakeProbeStatus.UNAVAILABLE, wakeProbeAttempt(100) {
            withTimeout(10) { delay(50); WakeProbeStatus.RESPONDS }
        })
        var attempts = 0
        val job = launch { pollWakeResponse(20_000, {testScheduler.currentTime}) {
            attempts++; WakeProbeStatus.NO_RESPONSE
        } }
        runCurrent(); job.cancelAndJoin(); advanceUntilIdle()
        assertEquals(1, attempts)
        assertTrue(job.isCancelled)
    }
}
