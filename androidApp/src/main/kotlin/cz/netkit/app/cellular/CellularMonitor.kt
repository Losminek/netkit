package cz.netkit.app.cellular

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import cz.netkit.shared.cellular.CellNetworkType
import cz.netkit.shared.cellular.CellStrengthReading
import cz.netkit.shared.cellular.CellularSim
import cz.netkit.shared.cellular.CellularState
import cz.netkit.shared.cellular.cellOperatorName
import cz.netkit.shared.cellular.hasNrSignal
import cz.netkit.shared.cellular.selectCellSignal
import cz.netkit.shared.cellular.simRat
import java.util.concurrent.Executor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * True when the mobile card should offer the READ_PHONE_STATE dialog: not granted, the device
 * has telephony, and the permission adds something — a second SIM (dual standby), or API < 31
 * where neither the network type nor `TelephonyDisplayInfo` is readable without it.
 * The request itself (launcher) belongs to the UI.
 */
internal fun cellularPermissionNeeded(context: Context): Boolean {
    if (context.granted(Manifest.permission.READ_PHONE_STATE)) return false
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
    val telephony = context.getSystemService(TelephonyManager::class.java) ?: return false
    val modems = guarded { modemCount(telephony) } ?: 1
    return modems >= 2 || Build.VERSION.SDK_INT < 31
}

/**
 * Raw getter values of one `CellSignalStrength`; validation and selection are the pure
 * functions in `shared` (`selectCellSignal`). CDMA is not mapped (no CDMA network in scope).
 * GSM uses `getDbm()`, which is the RSSI (`getRssi()` exists only from API 30).
 */
internal fun CellSignalStrength.toReading(): CellStrengthReading? = when (this) {
    is CellSignalStrengthNr -> CellStrengthReading.Nr(ssRsrp, ssRsrq, ssSinr, csiRsrp, csiRsrq, csiSinr, level)
    is CellSignalStrengthLte -> CellStrengthReading.Lte(rsrp, rsrq, rssnr, rssi, level)
    is CellSignalStrengthWcdma -> CellStrengthReading.Wcdma(dbm, level)
    is CellSignalStrengthTdscdma -> CellStrengthReading.Tdscdma(rscp, level)
    is CellSignalStrengthGsm -> CellStrengthReading.Gsm(dbm, level)
    else -> null
}

/**
 * Live mobile signal of every visible SIM.
 *
 * - With READ_PHONE_STATE: every SIM from `getActiveSubscriptionInfoList()`; without it only
 *   the data SIM (`getActiveDataSubscriptionId()`, API 29 `getDefaultDataSubscriptionId()`).
 * - Per SIM a `TelephonyManager.createForSubscriptionId` with its own `TelephonyCallback`
 *   (signal, display info, service state; API 31+) or `PhoneStateListener` (API 29–30).
 * - Signal comes only from `SignalStrength` reports (modem: change >= 2 dB, at most every 3 s,
 *   none with the screen off); `requestCellInfoUpdate()` is not used.
 * - Airplane mode (broadcast), subscription changes (`OnSubscriptionsChangedListener`) and the
 *   active data SIM (`ActiveDataSubscriptionIdListener` with READ_PHONE_STATE, otherwise
 *   re-read on every event) trigger a resync. A permission granted later is picked up on the
 *   next event; the UI may also re-collect the flow after the dialog.
 *
 * All Telephony calls and callbacks run on one private `HandlerThread`, never on the main
 * thread. Cancelling the collector unregisters every listener and receiver and stops the thread.
 */
internal class CellularMonitor(context: Context) {
    private val app = context.applicationContext

    fun observe(): Flow<CellularState> = callbackFlow {
        val telephony = app.getSystemService(TelephonyManager::class.java)
        val subscriptions = app.getSystemService(SubscriptionManager::class.java)
        if (telephony == null || subscriptions == null ||
            !app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            trySend(CellularState(emptyList(), phoneStatePermission = false,
                locationPermission = app.granted(Manifest.permission.ACCESS_FINE_LOCATION),
                airplaneMode = airplaneMode(app), noSim = true))
            awaitClose { }
            return@callbackFlow
        }
        val thread = HandlerThread("NetKit-cellular").apply { start() }
        val handler = Handler(thread.looper)
        val session = Session(app, telephony, subscriptions, handler) { trySend(it) }
        handler.post { session.start() }
        awaitClose {
            handler.post { session.stop() }
            thread.quitSafely()
        }
    }.conflate()
}

/** Mutable per-SIM state; touched only on the monitor thread. */
private class SimHolder(
    val subscriptionId: Int,
    val slotIndex: Int,
    val telephony: TelephonyManager,
    val carrierName: String?,
) {
    var readings: List<CellStrengthReading> = emptyList()
    var overrideNetworkType: Int? = null
    var displayNetworkType: Int? = null
    var dataNetworkType: Int? = null
    var networkOperatorName: String? = null
    var serviceStateAlpha: String? = null
    var simOperatorName: String? = null
    var roaming: Boolean? = null
    var elapsedMs: Long = SystemClock.elapsedRealtime()
    var unregister: (() -> Unit)? = null
}

private data class SubDesc(val subscriptionId: Int, val slotIndex: Int, val carrierName: String?)

private class Session(
    private val context: Context,
    private val telephony: TelephonyManager,
    private val subscriptions: SubscriptionManager,
    private val handler: Handler,
    private val emit: (CellularState) -> Unit,
) {
    private val executor = Executor { handler.post(it) }
    private val holders = linkedMapOf<Int, SimHolder>()
    private var stopped = false
    private var phoneState = false
    private var dataSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var globalUnregister: (() -> Unit)? = null
    private var activeDataUnregister: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        // Delivered on the monitor handler (registered with it).
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    fun start() {
        // Protected system broadcast: no RECEIVER_* flag needed (Context.registerReceiver docs).
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), null, handler)
        globalUnregister = registerSubscriptionsListener()
        refresh()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        runCatching { context.unregisterReceiver(receiver) }
        globalUnregister?.invoke()
        activeDataUnregister?.invoke()
        holders.values.forEach { it.unregister?.invoke() }
        holders.clear()
    }

    /** Re-reads permissions and the SIM set, then publishes. */
    fun refresh() {
        if (stopped) return
        resync()
        publish()
    }

    private fun resync() {
        phoneState = context.granted(Manifest.permission.READ_PHONE_STATE)
        dataSubId = activeDataSubscriptionId()
        val desired = desiredSubscriptions()
        val wanted = desired.map { it.subscriptionId }.toSet()
        holders.keys.filter { it !in wanted }.forEach { id -> holders.remove(id)?.unregister?.invoke() }
        for (desc in desired) {
            if (desc.subscriptionId in holders) continue
            val tm = guarded { telephony.createForSubscriptionId(desc.subscriptionId) } ?: continue
            val holder = SimHolder(desc.subscriptionId, desc.slotIndex, tm, desc.carrierName)
            refreshSlowFields(holder)
            holder.unregister = registerSim(holder)
            holders[desc.subscriptionId] = holder
        }
        updateActiveDataListener()
    }

    @SuppressLint("MissingPermission") // guarded by phoneState + SecurityException
    private fun desiredSubscriptions(): List<SubDesc> {
        if (phoneState) {
            val infos = guarded { subscriptions.activeSubscriptionInfoList }
            if (infos != null) return infos.map { SubDesc(it.subscriptionId, it.simSlotIndex, it.carrierName?.toString()) }
        }
        val id = dataSubId.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID } ?: return emptyList()
        return listOf(SubDesc(id, SubscriptionManager.getSlotIndex(id), null))
    }

    private fun activeDataSubscriptionId(): Int = if (Build.VERSION.SDK_INT >= 30) {
        SubscriptionManager.getActiveDataSubscriptionId()
    } else {
        SubscriptionManager.getDefaultDataSubscriptionId()
    }

    /** Operator names and network type: read at registration and on service / display changes. */
    @SuppressLint("MissingPermission") // getDataNetworkType/getVoiceNetworkType only after the permission check
    private fun refreshSlowFields(holder: SimHolder) {
        val tm = holder.telephony
        holder.networkOperatorName = guarded { tm.networkOperatorName }
        holder.simOperatorName = guarded { tm.simOperatorName }?.takeUnless { it.isBlank() } ?: holder.carrierName
        val basic = phoneState || (Build.VERSION.SDK_INT >= 33 && context.granted(Manifest.permission.READ_BASIC_PHONE_STATE))
        holder.dataNetworkType = if (basic) {
            guarded { tm.dataNetworkType }.known()
                // A SIM without data (second SIM) still has a voice/IMS registration.
                ?: guarded { tm.voiceNetworkType }.known()
        } else null
    }

    private fun onSignal(holder: SimHolder, strength: SignalStrength) {
        if (stopped) return
        holder.readings = strength.cellSignalStrengths.mapNotNull { it.toReading() }
        holder.elapsedMs = if (Build.VERSION.SDK_INT >= 30) strength.timestampMillis else SystemClock.elapsedRealtime()
        publish()
    }

    // Operator alpha needs a location permission; without it Android returns null, no exception.
    @SuppressLint("MissingPermission")
    private fun onServiceState(holder: SimHolder, state: ServiceState) {
        if (stopped) return
        // Alpha is null without a location permission (ServiceState docs).
        holder.serviceStateAlpha = state.operatorAlphaLong ?: state.operatorAlphaShort
        holder.roaming = if (state.state == ServiceState.STATE_IN_SERVICE) state.roaming else null
        refreshSlowFields(holder)
        publish()
    }

    @RequiresApi(30)
    private fun onDisplayInfo(holder: SimHolder, info: TelephonyDisplayInfo) {
        if (stopped) return
        holder.overrideNetworkType = info.overrideNetworkType
        holder.displayNetworkType = info.networkType
        refreshSlowFields(holder)
        publish()
    }

    private fun publish() {
        if (stopped) return
        val permission = context.granted(Manifest.permission.READ_PHONE_STATE)
        val currentData = activeDataSubscriptionId()
        // Without the permission the visible SIM follows the data SIM; a granted permission adds SIMs.
        if (permission != phoneState || (!permission && currentData != dataSubId)) {
            resync()
        } else {
            dataSubId = currentData
        }
        val sims = holders.values.map { h ->
            val signal = selectCellSignal(h.readings)
            val networkType = h.dataNetworkType ?: h.displayNetworkType.known()
            CellularSim(
                slotIndex = h.slotIndex,
                subscriptionId = h.subscriptionId,
                operatorName = cellOperatorName(h.networkOperatorName, h.serviceStateAlpha, h.simOperatorName),
                isDataSim = h.subscriptionId == dataSubId,
                rat = simRat(networkType, h.overrideNetworkType, signal, hasNrSignal(h.readings)),
                signal = signal,
                roaming = h.roaming,
                elapsedMs = h.elapsedMs,
            )
        }.sortedWith(compareBy({ if (it.slotIndex < 0) Int.MAX_VALUE else it.slotIndex }, { it.subscriptionId }))
        emit(
            CellularState(
                sims = sims,
                phoneStatePermission = phoneState,
                locationPermission = context.granted(Manifest.permission.ACCESS_FINE_LOCATION),
                airplaneMode = airplaneMode(context),
                noSim = sims.isEmpty() && !anySimReady(),
            ),
        )
    }

    private fun anySimReady(): Boolean {
        val count = guarded { modemCount(telephony) } ?: 1
        return (0 until count).any { slot -> guarded { telephony.getSimState(slot) } == TelephonyManager.SIM_STATE_READY }
    }

    // --- Registration ----------------------------------------------------------------------

    private fun registerSim(holder: SimHolder): (() -> Unit)? =
        if (Build.VERSION.SDK_INT >= 31) registerSim31(holder) else registerSimLegacy(holder)

    @RequiresApi(31)
    private fun registerSim31(holder: SimHolder): (() -> Unit)? {
        val callback = SimCallback(
            onSignal = { onSignal(holder, it) },
            onService = { onServiceState(holder, it) },
            onDisplay = { onDisplayInfo(holder, it) },
        )
        // None of the three listeners needs a permission on API 31+ (TelephonyCallback docs).
        guarded { holder.telephony.registerTelephonyCallback(executor, callback) } ?: return null
        return { guarded { holder.telephony.unregisterTelephonyCallback(callback) } }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private fun registerSimLegacy(holder: SimHolder): (() -> Unit)? {
        val listener = object : PhoneStateListener(executor) {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = onSignal(holder, signalStrength)

            override fun onServiceStateChanged(serviceState: ServiceState) = onServiceState(holder, serviceState)

            @RequiresApi(30)
            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) =
                onDisplayInfo(holder, telephonyDisplayInfo)
        }
        val base = PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE
        // API 30 devices check READ_PHONE_STATE for display info (LISTEN_DISPLAY_INFO_CHANGED docs).
        val events = if (Build.VERSION.SDK_INT >= 30 && phoneState) base or PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED else base
        if (guarded { holder.telephony.listen(listener, events) } == null &&
            (events == base || guarded { holder.telephony.listen(listener, base) } == null)) return null
        return { guarded { holder.telephony.listen(listener, PhoneStateListener.LISTEN_NONE) } }
    }

    /** Subscription list changes (SIM added/removed, default data SIM). */
    private fun registerSubscriptionsListener(): (() -> Unit)? {
        // Constructed on the monitor thread: the API 29 constructor binds to Looper.myLooper().
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() = refresh()
        }
        val added = if (Build.VERSION.SDK_INT >= 30) {
            guarded { subscriptions.addOnSubscriptionsChangedListener(executor, listener) }
        } else {
            @Suppress("DEPRECATION")
            guarded { subscriptions.addOnSubscriptionsChangedListener(listener) }
        }
        added ?: return null
        return { guarded { subscriptions.removeOnSubscriptionsChangedListener(listener) } }
    }

    /** Active data SIM changes (temporary data switch); needs READ_PHONE_STATE. */
    private fun updateActiveDataListener() {
        if (!phoneState) {
            activeDataUnregister?.invoke()
            activeDataUnregister = null
            return
        }
        if (activeDataUnregister != null) return
        activeDataUnregister = if (Build.VERSION.SDK_INT >= 31) registerActiveData31() else registerActiveDataLegacy()
    }

    @RequiresApi(31)
    private fun registerActiveData31(): (() -> Unit)? {
        val callback = ActiveDataCallback { refresh() }
        guarded { telephony.registerTelephonyCallback(executor, callback) } ?: return null
        return { guarded { telephony.unregisterTelephonyCallback(callback) } }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private fun registerActiveDataLegacy(): (() -> Unit)? {
        val listener = object : PhoneStateListener(executor) {
            override fun onActiveDataSubscriptionIdChanged(subId: Int) = refresh()
        }
        guarded { telephony.listen(listener, PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE) } ?: return null
        return { guarded { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) } }
    }
}

@RequiresApi(31)
private class SimCallback(
    private val onSignal: (SignalStrength) -> Unit,
    private val onService: (ServiceState) -> Unit,
    private val onDisplay: (TelephonyDisplayInfo) -> Unit,
) : TelephonyCallback(),
    TelephonyCallback.SignalStrengthsListener,
    TelephonyCallback.ServiceStateListener,
    TelephonyCallback.DisplayInfoListener {
    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = onSignal(signalStrength)
    override fun onServiceStateChanged(serviceState: ServiceState) = onService(serviceState)
    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) = onDisplay(telephonyDisplayInfo)
}

@RequiresApi(31)
private class ActiveDataCallback(private val onChange: () -> Unit) :
    TelephonyCallback(), TelephonyCallback.ActiveDataSubscriptionIdListener {
    override fun onActiveDataSubscriptionIdChanged(subId: Int) = onChange()
}

private fun Context.granted(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun airplaneMode(context: Context): Boolean =
    guarded { Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 } ?: false

private fun modemCount(telephony: TelephonyManager): Int =
    if (Build.VERSION.SDK_INT >= 30) telephony.activeModemCount else @Suppress("DEPRECATION") telephony.phoneCount

/** `NETWORK_TYPE_UNKNOWN` (0) and IWLAN carry no radio information. */
private fun Int?.known(): Int? = this?.takeIf { it != CellNetworkType.UNKNOWN && it != CellNetworkType.IWLAN }

/**
 * Telephony reads are best effort: SecurityException (permission revoked), UnsupportedOperation
 * (missing telephony feature) and binder failures (RuntimeException) all mean "unknown".
 */
private inline fun <T> guarded(block: () -> T): T? = try {
    block()
} catch (_: RuntimeException) {
    null
}
