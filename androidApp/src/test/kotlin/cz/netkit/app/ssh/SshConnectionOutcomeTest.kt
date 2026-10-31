package cz.netkit.app.ssh

import cz.netkit.shared.ssh.*
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SshConnectionOutcomeTest {
    @Test fun throwingWriterReportsConnectionBeforeTransportCloses() = runTest {
        val events = mutableListOf<String>()
        val outcome = SshConnectionOutcome { events += it.name }
        val pump = TerminalWritePump(this)
        val output = object : OutputStream() { override fun write(value: Int) { throw IOException() } }
        assertTrue(pump.send("fixture"))
        consumeSshWrites(pump, { output.write(it.encodeToByteArray()) }, { _, _ -> }, {
            outcome.record(SshFailure.CONNECTION); events += "closed"; pump.close()
        })
        assertTrue(outcome.failed)
        assertEquals(listOf("CONNECTION", "closed"), events)
    }
    @Test fun cancellationIsNotAWriteFailure() = runTest {
        val pump = TerminalWritePump(this); assertTrue(pump.send("fixture"))
        assertFailsWith<CancellationException> {
            consumeSshWrites(pump, { throw CancellationException() }, { _, _ -> }, { fail("Cancellation is not failure") })
        }
        pump.close()
        assertFalse(SshConnectionOutcome { fail("No failure") }.failed)
    }
    @Test fun hostDecisionSurvivesTimeoutAndFollowupTransportErrors() {
        for (first in listOf(SshFailure.HOST_KEY_CHANGED, SshFailure.HOST_KEY_REJECTED)) {
            val values = mutableListOf<SshFailure>(); val outcome = SshConnectionOutcome(values::add)
            outcome.record(first); outcome.record(SshFailure.TIMEOUT); outcome.record(SshFailure.CONNECTION)
            assertTrue(outcome.failed); assertEquals(listOf(first), values)
        }
    }
}
