package cz.netkit.shared.speed

import kotlin.math.log10

val SPEED_DIAL_LABELS = listOf(0, 10, 50, 100, 250, 500, 1000)

/** Logarithmic dial position in 0..1. Invalid input/range gives zero; overflow saturates. */
fun speedDialFraction(mbps: Double, maxMbps: Double = 1000.0): Double {
    if (mbps.isNaN() || mbps <= 0.0 || !maxMbps.isFinite() || maxMbps <= 0.0) return 0.0
    if (mbps >= maxMbps) return 1.0
    val denominator = log10(1.0 + maxMbps)
    if (denominator <= 0.0) return 0.0
    return (log10(1.0 + mbps) / denominator).coerceIn(0.0, 1.0)
}
