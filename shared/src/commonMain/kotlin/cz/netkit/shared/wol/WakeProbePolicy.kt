package cz.netkit.shared.wol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

const val WAKE_PROBE_INTERVAL_MS = 2_000L
const val WAKE_PRECHECK_TIMEOUT_MS = 1_500L

/** Each individual attempt is bounded by the remaining overall deadline. */
suspend fun wakeProbeAttempt(timeoutMillis: Long, probe: suspend () -> WakeProbeStatus): WakeProbeStatus = try {
    withTimeoutOrNull(timeoutMillis) { probe() }?.takeIf {
        it == WakeProbeStatus.RESPONDS || it == WakeProbeStatus.NO_RESPONSE || it == WakeProbeStatus.UNAVAILABLE
    } ?: WakeProbeStatus.UNAVAILABLE
} catch (_: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    WakeProbeStatus.UNAVAILABLE
} catch (error: CancellationException) { throw error
} catch (_: Exception) { WakeProbeStatus.UNAVAILABLE }

/** No response proves only this check failed, not that the machine is switched off. */
suspend fun pollWakeResponse(
    windowMillis: Long,
    monotonicMillis: () -> Long,
    probe: suspend () -> WakeProbeStatus,
): WakeProbeStatus {
    require(windowMillis in 1_000..120_000)
    val deadline = monotonicMillis() + windowMillis
    var receivedNegative = false
    while (true) {
        currentCoroutineContext().ensureActive()
        val remaining = deadline - monotonicMillis()
        if (remaining <= 0) break
        when (wakeProbeAttempt(minOf(5_000, remaining), probe)) {
            WakeProbeStatus.RESPONDS -> return WakeProbeStatus.RESPONDS
            WakeProbeStatus.NO_RESPONSE -> receivedNegative = true
            else -> Unit
        }
        val remainingAfterAttempt = deadline - monotonicMillis()
        if (remainingAfterAttempt <= 0) break
        delay(minOf(WAKE_PROBE_INTERVAL_MS, remainingAfterAttempt))
    }
    return if (receivedNegative) WakeProbeStatus.NO_RESPONSE else WakeProbeStatus.UNAVAILABLE
}

internal fun wakeMonotonicClock(): () -> Long {
    val origin = kotlin.time.TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
