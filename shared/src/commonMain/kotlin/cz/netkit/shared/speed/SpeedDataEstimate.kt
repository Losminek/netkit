package cz.netkit.shared.speed

import cz.netkit.shared.net.NetKind
import kotlin.math.ceil

/** Estimate when no earlier result on the same network type exists (O-3, conservative). */
const val SPEED_DATA_FALLBACK_MB = 200
/** Protocol overhead, warm-up variance and the latency phase on top of the payload. */
private const val SPEED_DATA_MARGIN = 1.2
private const val SPEED_DATA_STEP_MB = 10

/** Data estimate shown before a full test on a metered network; [fromHistory] = based on a past result. */
data class SpeedDataEstimate(val megabytes: Int, val fromHistory: Boolean)

/**
 * Data a full test of [engine] will transfer, from the newest stored result on [kind]
 * (history is newest first). Rate × phase length per direction: LibreSpeed 10 s each way, NDT7
 * download up to its 15 s hard cap and 10 s upload. A missing upload rate counts as the download
 * rate (upper bound). Rounded up to 10 MB; [SPEED_DATA_FALLBACK_MB] without a usable result.
 */
fun estimateSpeedTestData(engine: SpeedEngine, history: List<SpeedRecord>, kind: NetKind): SpeedDataEstimate {
    val last = history.firstOrNull { it.measuredKind == kind && it.downloadMbps?.let { v -> v.isFinite() && v > 0.0 } == true }
        ?: return SpeedDataEstimate(SPEED_DATA_FALLBACK_MB, fromHistory = false)
    val down = last.downloadMbps!!
    val up = last.uploadMbps?.takeIf { it.isFinite() && it >= 0.0 } ?: down
    val (downMs, upMs) = when (engine) {
        SpeedEngine.LIBRESPEED -> SPEED_PHASE_MS to SPEED_PHASE_MS
        SpeedEngine.NDT7 -> Ndt7Protocol.HARD_CAP_MS to Ndt7Protocol.UPLOAD_SEND_MS
    }
    // Mbit/s × s / 8 = MB (decimal megabytes). The epsilon keeps an exact 180.0000001 at 180.
    val megabytes = (down * downMs + up * upMs) / 1000.0 / 8.0 * SPEED_DATA_MARGIN
    val rounded = (ceil(megabytes / SPEED_DATA_STEP_MB - 1e-9) * SPEED_DATA_STEP_MB).coerceIn(SPEED_DATA_STEP_MB.toDouble(), 100_000.0)
    return SpeedDataEstimate(rounded.toInt(), fromHistory = true)
}
