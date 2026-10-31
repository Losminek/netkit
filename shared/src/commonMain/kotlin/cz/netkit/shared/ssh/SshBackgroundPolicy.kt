package cz.netkit.shared.ssh

/** Monotonic clock, including device sleep. Re-entering the foreground also checks an expired deadline. */
class SshBackgroundPolicy(private val nowMillis: () -> Long) {
    private var deadline: Long? = null
    fun background() { if (deadline == null) deadline = nowMillis() + BACKGROUND_GRACE_MILLIS }
    fun remainingMillis(): Long? = deadline?.let { (it - nowMillis()).coerceAtLeast(0L) }
    fun expired(): Boolean = remainingMillis() == 0L
    fun foreground(): Boolean = expired().also { deadline = null }
    companion object { const val BACKGROUND_GRACE_MILLIS = 5 * 60 * 1000L }
}
