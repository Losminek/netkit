package cz.netkit.shared.ssh

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PromptReplyGateTest {
    @Test fun staleTofuAcceptanceCannotTrustNextHost() = runTest {
        val gate = PromptReplyGate<Boolean>()
        val hostA = gate.open("session-A-prompt-1")
        gate.close("session-A-prompt-1") // Timeout closes A while its old button is still visible.
        val hostB = gate.open("session-B-prompt-1")
        assertTrue(hostA.isCancelled)
        assertFalse(gate.answer("session-A-prompt-1", true))
        assertFalse(hostB.isCompleted)
        assertTrue(gate.answer("session-B-prompt-1", true))
        assertTrue(hostB.await())
    }

    @Test fun stalePasswordCannotReachAnotherSession() = runTest {
        val gate = PromptReplyGate<List<String>?>()
        gate.open("session-A-prompt-1")
        gate.close("session-A-prompt-1")
        val hostB = gate.open("session-B-prompt-1")
        assertFalse(gate.answer("session-A-prompt-1", listOf("credential-A")))
        assertFalse(hostB.isCompleted)
        assertTrue(gate.answer("session-B-prompt-1", listOf("credential-B")))
        assertEquals(listOf("credential-B"), hostB.await())
    }

    @Test fun repeatedPromptsWithinSameSessionStillRequireUniqueRequest() = runTest {
        val gate = PromptReplyGate<List<String>?>()
        val first = gate.open("same-session-round-1")
        assertTrue(gate.answer("same-session-round-1", listOf("first-answer")))
        assertEquals(listOf("first-answer"), first.await())
        gate.close("same-session-round-1")
        val second = gate.open("same-session-round-2")
        assertFalse(gate.answer("same-session-round-1", listOf("stale-double-tap")))
        gate.close("same-session-round-1") // A late cancellation cannot close round 2 either.
        assertFalse(second.isCompleted)
        assertTrue(gate.answer("same-session-round-2", null))
        assertNull(second.await())
    }

    @Test fun hostRekeyInSameSessionCannotReusePreviousConsent() = runTest {
        val gate = PromptReplyGate<Boolean>()
        gate.open("same-session-host-1")
        gate.close("same-session-host-1")
        val second = gate.open("same-session-host-2")
        assertFalse(gate.answer("same-session-host-1", true))
        assertFalse(gate.answer("same-session-host-1", false))
        assertFalse(second.isCompleted)
        assertTrue(gate.answer("same-session-host-2", false))
        assertFalse(second.await())
    }
}
