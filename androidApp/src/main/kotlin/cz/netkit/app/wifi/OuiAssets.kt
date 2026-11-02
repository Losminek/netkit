package cz.netkit.app.wifi

import android.content.Context
import cz.netkit.shared.wifi.OuiDatabase
import java.io.Reader
import kotlin.coroutines.cancellation.CancellationException

/** Asset written by `tools/update_oui.py` (IEEE OUI Public Listing, prefixes and names only). */
internal const val OUI_ASSET = "oui/ieee-oui.tsv"

/**
 * The bundled OUI list, read and parsed once per process and then kept in memory (~54 k prefixes).
 * [loadedOnIo] blocks on the first call, so it is called only from code that already runs on
 * Dispatchers.IO ([WifiViewModel] warm-up and scan mapping), never on the main thread.
 */
internal object OuiAssets {
    private val loader = OuiLoader()

    fun loadedOnIo(context: Context): OuiDatabase {
        val app = context.applicationContext
        return loader.get { app.assets.open(OUI_ASSET).bufferedReader(Charsets.UTF_8) }
    }
}

/**
 * Once-only load with a permanent result: a missing asset, an I/O error or any unexpected failure
 * of reading/parsing becomes [OuiDatabase.EMPTY] ("vendor unknown") and is kept, so a damaged file
 * never fails a Wi-Fi scan and is not retried on every scan. Only cancellation is passed on
 * (nothing is stored then; the next caller loads again).
 */
internal class OuiLoader {
    @Volatile private var cached: OuiDatabase? = null

    fun get(open: () -> Reader): OuiDatabase = cached ?: synchronized(this) {
        cached ?: load(open).also { cached = it }
    }

    private fun load(open: () -> Reader): OuiDatabase = try {
        open().use { OuiDatabase.parse(it.readText()) }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        OuiDatabase.EMPTY
    }
}
