package cz.netkit.app.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build

/** Only subscribes to system results; never requests a scan. Call and close on the main thread. */
internal class WifiScanEvents(private val app: Context, private val wifi: WifiManager) {
    fun subscribe(onResults: (Boolean) -> Unit): () -> Unit {
        if (Build.VERSION.SDK_INT >= 30) {
            val callback = object : WifiManager.ScanResultsCallback() {
                override fun onScanResultsAvailable() { onResults(true) }
            }
            wifi.registerScanResultsCallback(app.mainExecutor, callback)
            return { wifi.unregisterScanResultsCallback(callback) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                onResults(isUpdatedWifiScan(intent?.action,
                    intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false))
            }
        }
        @Suppress("DEPRECATION")
        app.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        return { app.unregisterReceiver(receiver) }
    }
}


/** API 29 broadcast mapping; missing EXTRA_RESULTS_UPDATED is false. */
internal fun isUpdatedWifiScan(action: String?, resultsUpdated: Boolean): Boolean =
    action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION && resultsUpdated
