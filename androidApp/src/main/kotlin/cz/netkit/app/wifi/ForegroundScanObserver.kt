package cz.netkit.app.wifi

/** Main-thread subscription gate. Late queued callbacks cannot publish after stop or re-entry. */
internal class ForegroundScanObserver(
    private val subscribe: ((Boolean) -> Unit) -> (() -> Unit),
    private val onUpdated: () -> Unit,
) {
    private var unsubscribe: (() -> Unit)? = null
    private var generation = 0
    val active: Boolean get() = unsubscribe != null

    fun setActive(active: Boolean) {
        if (active == this.active) return
        generation++
        if (!active) {
            val close = unsubscribe
            unsubscribe = null
            close?.invoke()
        } else {
            val token = generation
            unsubscribe = subscribe { updated ->
                if (updated && token == generation && this.active) onUpdated()
            }
        }
    }
}

/** Scan callback has no EXTRA_RESULTS_UPDATED; a strictly newer measurement proves freshness. */
internal fun newPassiveScanTime(timestampsMs: List<Long>, nowMs: Long, afterMs: Long): Long? =
    timestampsMs.filter { it > afterMs && it <= nowMs }.maxOrNull()
