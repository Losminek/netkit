package cz.netkit.app.update

import cz.netkit.shared.update.isUpdateCheckDue

/** Main-thread process memory, retained even when the activity/ViewModel is recreated. */
internal class StartupUpdateSession {
    private var started = false
    var pending: Boolean = false
        private set
    var closed: Boolean = false
        private set

    fun claimStart(): Boolean {
        if (started) return false
        started = true
        pending = true
        return true
    }

    /** Cancellation leaves pending intact; only a completed attempt/cache consumes startup. */
    fun complete() { pending = false }

    fun isAutomaticCheckDue(lastAttemptMs: Long?, nowMs: Long): Boolean =
        if (pending) isStartupCheckDue(lastAttemptMs, nowMs) else isUpdateCheckDue(lastAttemptMs, nowMs)

    fun close() { closed = true }
}

/** Persisted time of the last attempt includes errors and manual checks. */
internal fun isStartupCheckDue(lastAttemptMs: Long?, nowMs: Long): Boolean =
    lastAttemptMs == null || nowMs < lastAttemptMs || nowMs - lastAttemptMs >= 60 * 60 * 1000L
