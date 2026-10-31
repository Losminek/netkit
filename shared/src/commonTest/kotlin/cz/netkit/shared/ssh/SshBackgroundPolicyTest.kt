package cz.netkit.shared.ssh

import kotlin.test.*

class SshBackgroundPolicyTest {
    @Test fun foregroundBeforeDeadlineKeepsSessionAndResetsNextGracePeriod() {
        var now = 1000L
        val policy = SshBackgroundPolicy { now }
        assertFalse(policy.expired())
        policy.background()
        now += 299999
        assertFalse(policy.foreground())
        assertNull(policy.remainingMillis())
        policy.background()
        assertEquals(300000L, policy.remainingMillis())
    }
    @Test fun deadlineIncludesSuspendAndCannotBeExtendedByRepeatedStops() {
        var now = 0L
        val policy = SshBackgroundPolicy { now }
        policy.background()
        now = 200000
        policy.background()
        assertEquals(100000L, policy.remainingMillis())
        now = 300000
        assertTrue(policy.expired())
        assertTrue(policy.foreground())
        assertFalse(policy.expired())
    }
}
