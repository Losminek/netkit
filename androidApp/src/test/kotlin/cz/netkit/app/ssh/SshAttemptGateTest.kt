package cz.netkit.app.ssh

import kotlin.test.*

class SshAttemptGateTest {
    @Test fun replacementAndClosedTabIgnoreOldCallbacks() {
        val gate = SshAttemptGate(); val original = gate.begin("tab")
        var visible = "old terminal"
        val replacement = gate.begin("tab")
        assertEquals("old terminal", visible)
        assertFalse(gate.publish("tab", original) { visible = "late old EOF" })
        assertEquals("old terminal", visible)
        assertTrue(gate.publish("tab", replacement) { visible = "new connected terminal" })
        assertEquals("new connected terminal", visible)
        gate.invalidate("tab")
        assertFalse(gate.publish("tab", replacement) { visible = "late output" })
    }
}
