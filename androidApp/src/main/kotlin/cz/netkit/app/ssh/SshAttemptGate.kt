package cz.netkit.app.ssh

/** Reconnect reuses a tab; obsolete transport callbacks must never publish into its replacement. */
class SshAttemptGate {
    private var next = 0L
    private val active = mutableMapOf<String, Long>()
    @Synchronized fun begin(id: String): Long = (++next).also { active[id] = it }
    @Synchronized fun invalidate(id: String) { active.remove(id) }
    @Synchronized fun isCurrent(id: String, attempt: Long): Boolean = active[id] == attempt
    @Synchronized fun publish(id: String, attempt: Long, update: () -> Unit): Boolean {
        if (active[id] != attempt) return false
        update(); return true
    }
}
