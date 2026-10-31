package cz.netkit.shared.cellular

/*
 * Mobile signal model for the Overview card (M4 A2). Pure logic, no `android.*`.
 *
 * Sources (developer.android.com and AOSP android15-release, verified 2026-10-27):
 * - CellSignalStrengthLte: RSRP -140..-43 dBm, RSRQ -34..3 dB (AOSP range check; the getter
 *   docs give no range), RSSNR -20..30 dB, RSSI -113..-51 dBm.
 * - CellSignalStrengthNr: SS/CSI-RSRP -156..-31 dBm, SS-RSRQ -43..20 dB, CSI-RSRQ -20..-3 dB,
 *   SS-SINR -23..40 dB, CSI-SINR -23..23 dB.
 * - CellSignalStrengthWcdma.getDbm: RSCP -120..-24 dBm (falls back to RSSI on old radio HALs);
 *   CellSignalStrengthTdscdma.getRscp: -120..-24 dBm; CellSignalStrengthGsm.getRssi: -113..-51 dBm.
 * - CellSignalStrength.getLevel: 0 (NONE_OR_UNKNOWN) .. 4 (GREAT).
 * - CellInfo.UNAVAILABLE = Int.MAX_VALUE; it and every value outside the range map to null.
 */

/** Mirror of `android.telephony.CellInfo.UNAVAILABLE` (Integer.MAX_VALUE). */
const val CELL_UNAVAILABLE: Int = Int.MAX_VALUE

/** Mirror of `android.telephony.TelephonyManager.NETWORK_TYPE_*` (only the values used here). */
object CellNetworkType {
    const val UNKNOWN = 0 // NETWORK_TYPE_UNKNOWN
    const val GPRS = 1 // NETWORK_TYPE_GPRS
    const val EDGE = 2 // NETWORK_TYPE_EDGE
    const val UMTS = 3 // NETWORK_TYPE_UMTS
    const val CDMA = 4 // NETWORK_TYPE_CDMA
    const val EVDO_0 = 5 // NETWORK_TYPE_EVDO_0
    const val EVDO_A = 6 // NETWORK_TYPE_EVDO_A
    const val RTT_1X = 7 // NETWORK_TYPE_1xRTT
    const val HSDPA = 8 // NETWORK_TYPE_HSDPA
    const val HSUPA = 9 // NETWORK_TYPE_HSUPA
    const val HSPA = 10 // NETWORK_TYPE_HSPA
    const val IDEN = 11 // NETWORK_TYPE_IDEN
    const val EVDO_B = 12 // NETWORK_TYPE_EVDO_B
    const val LTE = 13 // NETWORK_TYPE_LTE
    const val EHRPD = 14 // NETWORK_TYPE_EHRPD
    const val HSPAP = 15 // NETWORK_TYPE_HSPAP
    const val GSM = 16 // NETWORK_TYPE_GSM
    const val TD_SCDMA = 17 // NETWORK_TYPE_TD_SCDMA
    const val IWLAN = 18 // NETWORK_TYPE_IWLAN (Wi-Fi calling, not a radio)
    const val LTE_CA = 19 // hidden NETWORK_TYPE_LTE_CA, some vendors still report it
    const val NR = 20 // NETWORK_TYPE_NR (5G standalone)
}

/** Mirror of `android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_*`. */
object CellOverrideType {
    const val NONE = 0
    const val LTE_CA = 1
    const val LTE_ADVANCED_PRO = 2
    const val NR_NSA = 3
    const val NR_NSA_MMWAVE = 4 // deprecated in favour of NR_ADVANCED
    const val NR_ADVANCED = 5
}

/** Radio access technology shown as LTE / 5G NSA / 5G SA / 3G / 2G. */
enum class CellRat { GSM, UMTS, LTE, NR_NSA, NR_SA, UNKNOWN }

enum class CellQuality { EXCELLENT, GOOD, FAIR, POOR, NONE }

/**
 * One validated signal reading. [rsrpDbm] is LTE RSRP or NR SS-RSRP (CSI fallback); for 3G and
 * 2G it is null and [rssiDbm] carries the signal (3G: RSCP from `getDbm`, 2G: RSSI).
 * [rsrqDb] is LTE RSRQ or NR SS-RSRQ (CSI fallback), [sinrDb] LTE RSSNR or NR SS-SINR (CSI
 * fallback). [level] is the Android 0..4 bar level. [source] is the RAT of the measurement the
 * values come from (NR with an LTE anchor in the same report = [CellRat.NR_NSA]).
 */
data class CellSignal(
    val rsrpDbm: Int?,
    val rsrqDb: Int?,
    val sinrDb: Int?,
    val rssiDbm: Int?,
    val level: Int?,
    val source: CellRat,
)

/**
 * One active subscription. [elapsedMs] is `SystemClock.elapsedRealtime()` of the last signal
 * report (or of the registration when no report has arrived yet).
 * [roaming] is null when the SIM is not in service or service state is unknown.
 */
data class CellularSim(
    val slotIndex: Int,
    val subscriptionId: Int,
    val operatorName: String?,
    val isDataSim: Boolean,
    val rat: CellRat,
    val signal: CellSignal?,
    val roaming: Boolean?,
    val elapsedMs: Long,
)

/**
 * Snapshot for the mobile card. Without [phoneStatePermission] only the data SIM is listed.
 * [locationPermission] is ACCESS_FINE_LOCATION (without location Android strips the operator
 * name from `ServiceState`). [noSim] is true when no SIM is active and no slot is ready.
 */
data class CellularState(
    val sims: List<CellularSim>,
    val phoneStatePermission: Boolean,
    val locationPermission: Boolean,
    val airplaneMode: Boolean,
    val noSim: Boolean,
)

// --- Raw readings (mirror of CellSignalStrength* getters, built by the Android layer) ----------

/** Raw getter values of one `CellSignalStrength*` object, before validation. */
sealed interface CellStrengthReading {
    val level: Int

    data class Lte(val rsrp: Int, val rsrq: Int, val rssnr: Int, val rssi: Int, override val level: Int) : CellStrengthReading

    data class Nr(
        val ssRsrp: Int,
        val ssRsrq: Int,
        val ssSinr: Int,
        val csiRsrp: Int,
        val csiRsrq: Int,
        val csiSinr: Int,
        override val level: Int,
    ) : CellStrengthReading

    /** WCDMA `getDbm()` (RSCP, RSSI fallback on old HALs). */
    data class Wcdma(val dbm: Int, override val level: Int) : CellStrengthReading

    /** TD-SCDMA `getRscp()`. */
    data class Tdscdma(val rscp: Int, override val level: Int) : CellStrengthReading

    data class Gsm(val rssi: Int, override val level: Int) : CellStrengthReading
}

// Documented ranges (see file header).
private val LTE_RSRP = -140..-43
private val LTE_RSRQ = -34..3
private val LTE_RSSNR = -20..30
private val RSSI_2G_LTE = -113..-51
private val NR_RSRP = -156..-31
private val NR_SS_RSRQ = -43..20
private val NR_CSI_RSRQ = -20..-3
private val NR_SS_SINR = -23..40
private val NR_CSI_SINR = -23..23
private val RSCP_3G = -120..-24
private val LEVEL = 0..4

/** Value inside [range], otherwise null (covers [CELL_UNAVAILABLE]). */
fun cellValue(raw: Int, range: IntRange): Int? = raw.takeIf { it != CELL_UNAVAILABLE && it in range }

/** Validates one reading; null when it carries no usable value. */
fun cellSignalOf(reading: CellStrengthReading, nrWithLteAnchor: Boolean = false): CellSignal? {
    val level = cellValue(reading.level, LEVEL)
    val signal = when (reading) {
        is CellStrengthReading.Lte -> CellSignal(
            rsrpDbm = cellValue(reading.rsrp, LTE_RSRP),
            rsrqDb = cellValue(reading.rsrq, LTE_RSRQ),
            sinrDb = cellValue(reading.rssnr, LTE_RSSNR),
            rssiDbm = cellValue(reading.rssi, RSSI_2G_LTE),
            level = level,
            source = CellRat.LTE,
        )
        is CellStrengthReading.Nr -> CellSignal(
            rsrpDbm = cellValue(reading.ssRsrp, NR_RSRP) ?: cellValue(reading.csiRsrp, NR_RSRP),
            rsrqDb = cellValue(reading.ssRsrq, NR_SS_RSRQ) ?: cellValue(reading.csiRsrq, NR_CSI_RSRQ),
            sinrDb = cellValue(reading.ssSinr, NR_SS_SINR) ?: cellValue(reading.csiSinr, NR_CSI_SINR),
            rssiDbm = null,
            level = level,
            source = if (nrWithLteAnchor) CellRat.NR_NSA else CellRat.NR_SA,
        )
        is CellStrengthReading.Wcdma -> CellSignal(null, null, null, cellValue(reading.dbm, RSCP_3G), level, CellRat.UMTS)
        is CellStrengthReading.Tdscdma -> CellSignal(null, null, null, cellValue(reading.rscp, RSCP_3G), level, CellRat.UMTS)
        is CellStrengthReading.Gsm -> CellSignal(null, null, null, cellValue(reading.rssi, RSSI_2G_LTE), level, CellRat.GSM)
    }
    return signal.takeIf { it.rsrpDbm != null || it.rsrqDb != null || it.sinrDb != null || it.rssiDbm != null }
}

/**
 * Picks the reading for the gauge from one `SignalStrength.getCellSignalStrengths()` report:
 * NR with RSRP first (5G leg, as WiFiman), then LTE with RSRP, then 3G, then 2G; when nothing
 * has a primary value, the first reading with any value (quality [CellQuality.NONE]).
 * An NR reading next to an LTE reading in the same report is the NSA secondary leg.
 */
fun selectCellSignal(readings: List<CellStrengthReading>): CellSignal? {
    val hasLte = readings.any { it is CellStrengthReading.Lte }
    val signals = readings.mapNotNull { cellSignalOf(it, nrWithLteAnchor = hasLte) }
    fun best(vararg rats: CellRat) = signals.filter { it.source in rats }.maxByOrNull { primaryDbm(it) ?: Int.MIN_VALUE }
        ?.takeIf { primaryDbm(it) != null }
    return best(CellRat.NR_NSA, CellRat.NR_SA)
        ?: best(CellRat.LTE)
        ?: best(CellRat.UMTS)
        ?: best(CellRat.GSM)
        ?: signals.firstOrNull()
}

/** True when the report carries an NR reading with at least one valid value (NSA hint for LTE). */
fun hasNrSignal(readings: List<CellStrengthReading>): Boolean =
    readings.any { it is CellStrengthReading.Nr && cellSignalOf(it) != null }

/** dBm shown in the gauge: RSRP for LTE/NR, RSCP/RSSI for 3G/2G. */
fun primaryDbm(signal: CellSignal?): Int? = when (signal?.source) {
    null -> null
    CellRat.LTE, CellRat.NR_NSA, CellRat.NR_SA -> signal.rsrpDbm
    CellRat.UMTS, CellRat.GSM -> signal.rssiDbm
    CellRat.UNKNOWN -> signal.rsrpDbm ?: signal.rssiDbm
}

/**
 * Quality thresholds (lower bound inclusive):
 * - LTE/NR by RSRP: >= -80 excellent, >= -90 good, >= -100 fair, below poor (M4 design F3);
 * - 3G by RSCP: >= -85, >= -95, >= -105, below (AOSP WCDMA RSCP defaults GREAT/GOOD/MODERATE);
 * - 2G by RSSI: >= -89, >= -97, >= -103, below (AOSP GSM RSSI defaults GREAT/GOOD/MODERATE).
 * No signal or no primary value = [CellQuality.NONE].
 */
fun cellQuality(signal: CellSignal?): CellQuality {
    val dbm = primaryDbm(signal) ?: return CellQuality.NONE
    val (excellent, good, fair) = when (signal!!.source) {
        CellRat.UMTS -> Triple(-85, -95, -105)
        CellRat.GSM -> Triple(-89, -97, -103)
        CellRat.UNKNOWN -> if (signal.rsrpDbm != null) Triple(-80, -90, -100) else Triple(-89, -97, -103)
        CellRat.LTE, CellRat.NR_NSA, CellRat.NR_SA -> Triple(-80, -90, -100)
    }
    return when {
        dbm >= excellent -> CellQuality.EXCELLENT
        dbm >= good -> CellQuality.GOOD
        dbm >= fair -> CellQuality.FAIR
        else -> CellQuality.POOR
    }
}

/** Maps `TelephonyManager.NETWORK_TYPE_*` to a RAT; null, UNKNOWN, IWLAN and unknown codes = UNKNOWN. */
fun cellRatOfNetworkType(networkType: Int?): CellRat = when (networkType) {
    CellNetworkType.GPRS, CellNetworkType.EDGE, CellNetworkType.GSM, CellNetworkType.CDMA,
    CellNetworkType.RTT_1X, CellNetworkType.IDEN -> CellRat.GSM
    CellNetworkType.UMTS, CellNetworkType.HSDPA, CellNetworkType.HSUPA, CellNetworkType.HSPA,
    CellNetworkType.HSPAP, CellNetworkType.TD_SCDMA, CellNetworkType.EVDO_0, CellNetworkType.EVDO_A,
    CellNetworkType.EVDO_B, CellNetworkType.EHRPD -> CellRat.UMTS
    CellNetworkType.LTE, CellNetworkType.LTE_CA -> CellRat.LTE
    CellNetworkType.NR -> CellRat.NR_SA
    else -> CellRat.UNKNOWN
}

/**
 * RAT for the card.
 * - `NR_SA` only when [dataNetworkType] is NR.
 * - `NR_NSA` when the override is NR_NSA (or NR_NSA_MMWAVE / NR_ADVANCED, which on a non-NR
 *   data type also mean an NR secondary leg) and the data type is LTE or unknown, or when the
 *   data type is LTE and the signal report carries an NR reading.
 * - Otherwise the data type mapping; overrides on 3G/2G are ignored as stale.
 */
fun resolveRat(dataNetworkType: Int?, overrideNetworkType: Int?, hasNrSignal: Boolean): CellRat {
    val base = cellRatOfNetworkType(dataNetworkType)
    if (base == CellRat.NR_SA) return CellRat.NR_SA
    val nsaOverride = overrideNetworkType == CellOverrideType.NR_NSA ||
        overrideNetworkType == CellOverrideType.NR_NSA_MMWAVE ||
        overrideNetworkType == CellOverrideType.NR_ADVANCED
    return when (base) {
        CellRat.LTE -> if (nsaOverride || hasNrSignal) CellRat.NR_NSA else CellRat.LTE
        CellRat.UNKNOWN -> if (nsaOverride) CellRat.NR_NSA else CellRat.UNKNOWN
        else -> base
    }
}

/**
 * Final RAT of one SIM: [resolveRat], and when that stays UNKNOWN (API 29–30 without
 * READ_PHONE_STATE, no network type) the source of the signal. An NR-only signal is not trusted
 * as SA (SA requires the NR data type), so it stays UNKNOWN.
 */
fun simRat(dataNetworkType: Int?, overrideNetworkType: Int?, signal: CellSignal?, hasNrSignal: Boolean): CellRat {
    val resolved = resolveRat(dataNetworkType, overrideNetworkType, hasNrSignal)
    if (resolved != CellRat.UNKNOWN) return resolved
    return when (val source = signal?.source) {
        CellRat.LTE, CellRat.NR_NSA, CellRat.UMTS, CellRat.GSM -> source
        else -> CellRat.UNKNOWN
    }
}

/** Operator: network name, then ServiceState alpha, then SIM SPN (eSIM, empty name); blank = null. */
fun cellOperatorName(networkOperatorName: String?, serviceStateAlpha: String?, simOperatorName: String?): String? =
    sequenceOf(networkOperatorName, serviceStateAlpha, simOperatorName)
        .map { it?.trim() }
        .firstOrNull { !it.isNullOrEmpty() }
