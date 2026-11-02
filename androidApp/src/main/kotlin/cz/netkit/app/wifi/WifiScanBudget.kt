package cz.netkit.app.wifi

internal data class WifiScanBudget(val remaining: Int, val waitSeconds: Long)

/** Own accepted startScan calls only. Advisory: the system's budget is not observable. */
internal class WifiScanWindow(private val nowMs: () -> Long) {
    private val starts = ArrayDeque<Long>()

    @Synchronized fun accepted() {
        prune(nowMs())
        starts.addLast(nowMs())
        // When throttling is disabled we still need only the last four starts.
        while (starts.size > 4) starts.removeFirst()
    }

    @Synchronized fun budget(): WifiScanBudget {
        val now = nowMs()
        prune(now)
        val wait = if (starts.size < 4) 0 else ((120_000 - (now - starts.first()) + 999) / 1000).coerceAtLeast(0)
        return WifiScanBudget((4 - starts.size).coerceAtLeast(0), wait)
    }

    private fun prune(now: Long) {
        while (starts.isNotEmpty() && now - starts.first() >= 120_000) starts.removeFirst()
    }
}
