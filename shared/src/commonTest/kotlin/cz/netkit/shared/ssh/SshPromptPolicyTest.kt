package cz.netkit.shared.ssh

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SshPromptPolicyTest {
    @Test fun promptRemainsPendingAfterOldLimitThenExpiresAtThreeMinutes() = runTest {
        var expired = false
        val pending = launch {
            try { SshPromptPolicy.await { awaitCancellation() } }
            catch (_: TimeoutCancellationException) { expired = true }
        }
        runCurrent(); advanceTimeBy(179_999); runCurrent()
        assertTrue(pending.isActive); assertFalse(expired)
        advanceTimeBy(1); runCurrent(); assertTrue(expired); assertTrue(pending.isCompleted)
        assertEquals(600_000L, SshPromptPolicy.HANDSHAKE_TIMEOUT_MILLIS)
    }
    @Test fun currentOfferedKeyIsNotDisplayedAsPreviousRejection() {
        val offered = TrustedHostKey("[host]:22", "ssh-ed25519", "public", "SHA256:current")
        assertNull(distinctRejectedHostKey(offered, offered.copy(fingerprint = "SHA256:display-only")))
        val older = offered.copy(publicKey = "old", fingerprint = "SHA256:old")
        assertEquals(older, distinctRejectedHostKey(offered, older))
    }
}
