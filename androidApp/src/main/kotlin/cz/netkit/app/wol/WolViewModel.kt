package cz.netkit.app.wol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.netkit.app.R
import cz.netkit.app.ssh.AndroidSecretStore
import cz.netkit.shared.wol.WolController
import cz.netkit.shared.wol.WolDeviceStore
import cz.netkit.shared.wol.WolDeviceDraft
import java.util.UUID
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import android.os.SystemClock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import cz.netkit.shared.wol.decodeWolImportAny
import cz.netkit.shared.wol.encodeWolExport
import cz.netkit.shared.wol.encodeWolCsvExport
import cz.netkit.shared.wol.wolGroupFromNote
import cz.netkit.shared.wol.WOL_TRANSFER_LIMIT
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

open class NetKitApplication : Application() {
    open val devices: WolDeviceStore by lazy { createDeviceStore() }
    protected open fun createDeviceStore(): WolDeviceStore = AndroidDeviceStore(this)
    open fun createSshSecretStore(): AndroidSecretStore = AndroidSecretStore(this)
}

/**
 * "Skupina: " / "Group: " in the current and every bundled language (cs, en), current first.
 * Only reads strings already in the APK (no app bundle language splits), so the lint hint does not apply.
 */
@android.annotation.SuppressLint("AppBundleLocaleChanges")
private fun groupNotePrefixes(app: Application): List<String> {
    val locales = listOf(java.util.Locale.forLanguageTag("cs"), java.util.Locale.ENGLISH)
    val bundled = locales.map { locale ->
        val config = android.content.res.Configuration(app.resources.configuration).apply { setLocale(locale) }
        app.createConfigurationContext(config).getString(R.string.wol_import_group_note, "")
    }
    return (listOf(app.getString(R.string.wol_import_group_note, "")) + bundled).distinct()
}

/** JSON is NetKit's own lossless format; WEBB_CSV is the file the Wake On Lan app (Mike Webb) imports. */
enum class WolExportFormat { JSON, WEBB_CSV }

enum class WolTransferMessage { IMPORT_FAILED, IMPORT_SIZE, IMPORT_UTF8, IMPORT_FORMAT, IMPORT_SCHEMA, IMPORT_UNKNOWN_FIELD, IMPORT_DUPLICATE_ID, IMPORT_INVALID_DEVICE, EXPORT_FAILED, EXPORTED, IMPORT_BLOCKED, CANCELLED, DOCUMENT_UNAVAILABLE }

class WolViewModel(application: Application) : AndroidViewModel(application) {
    private val transferState = MutableStateFlow<WolTransferMessage?>(null)
    val transferMessage = transferState.asStateFlow()
    fun clearTransferMessage() { transferState.value = null }
    private var transferJob: Job? = null
    fun cancelTransfer() {
        if (transferBusyState.value) { transferState.value = WolTransferMessage.CANCELLED; transferJob?.cancel() }
    }
    fun documentUnavailable() { transferState.value = WolTransferMessage.DOCUMENT_UNAVAILABLE }
    private val transferBusyState = MutableStateFlow(false)
    val transferBusy = transferBusyState.asStateFlow()
    fun importFrom(uri: Uri) {
        if (transferBusyState.value) return
        val state = controller.state.value
        if (state.draft != null || state.sendingDeviceId != null || state.saving || state.loading || state.loadFailed) {
            transferState.value = WolTransferMessage.IMPORT_BLOCKED
            return
        }
        clearTransferMessage()
        transferBusyState.value = true
        transferJob = viewModelScope.launch {
            try {
                val cancellation = CancellationSignal()
                val bytes = BoundedStreamIo.read(open = {
                    val descriptor = getApplication<Application>().contentResolver.openFileDescriptor(uri,"r",cancellation)
                        ?: error("Import stream unavailable")
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                },cancelOpen=cancellation::cancel,limit=WOL_TRANSFER_LIMIT)
                val app = getApplication<Application>()
                val imported = withContext(Dispatchers.IO) {
                    decodeWolImportAny(bytes, System.currentTimeMillis()) { group -> app.getString(R.string.wol_import_group_note, group) }
                }
                transferState.value = if (controller.previewImport(imported.devices, imported.skipped.size,
                        imported.secureOnDropped)) null else WolTransferMessage.IMPORT_BLOCKED
            } catch (e: cz.netkit.shared.wol.WolImportException) {
                transferState.value = when (e.reason) {
                    cz.netkit.shared.wol.WolImportFailure.SIZE -> WolTransferMessage.IMPORT_SIZE
                    cz.netkit.shared.wol.WolImportFailure.UTF8 -> WolTransferMessage.IMPORT_UTF8
                    cz.netkit.shared.wol.WolImportFailure.FORMAT -> WolTransferMessage.IMPORT_FORMAT
                    cz.netkit.shared.wol.WolImportFailure.SCHEMA -> WolTransferMessage.IMPORT_SCHEMA
                    cz.netkit.shared.wol.WolImportFailure.UNKNOWN_FIELD -> WolTransferMessage.IMPORT_UNKNOWN_FIELD
                    cz.netkit.shared.wol.WolImportFailure.DUPLICATE_ID -> WolTransferMessage.IMPORT_DUPLICATE_ID
                    cz.netkit.shared.wol.WolImportFailure.INVALID_DEVICE -> WolTransferMessage.IMPORT_INVALID_DEVICE
                }
            } catch (_: StreamSizeLimitException) { transferState.value = WolTransferMessage.IMPORT_SIZE
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive(); transferState.value = WolTransferMessage.IMPORT_FAILED
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { transferState.value = WolTransferMessage.IMPORT_FAILED }
            finally { transferBusyState.value = false }
        }
    }
    fun exportTo(uri: Uri, format: WolExportFormat) {
        if (transferBusyState.value) return
        clearTransferMessage()
        transferBusyState.value = true
        val snapshot = controller.state.value.devices.toList()
        transferJob = viewModelScope.launch {
            var saved = false
            try {
                val app = getApplication<Application>()
                val bytes = withContext(Dispatchers.IO) {
                    when (format) {
                        WolExportFormat.JSON -> encodeWolExport(snapshot)
                        WolExportFormat.WEBB_CSV -> {
                            // The note was written in the language active at import time, so accept every bundled one.
                            val prefixes = groupNotePrefixes(app)
                            encodeWolCsvExport(snapshot, { device -> prefixes.firstNotNullOfOrNull { wolGroupFromNote(device.note, it) } },
                                app.getString(R.string.wol_export_default_group))
                        }
                    }
                }
                val cancellation = CancellationSignal()
                BoundedStreamIo.write(open = {
                    val descriptor = getApplication<Application>().contentResolver.openFileDescriptor(uri,"wt",cancellation)
                        ?: error("Export stream unavailable")
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
                },cancelOpen=cancellation::cancel,bytes=bytes)
                saved = true
                transferState.value = WolTransferMessage.EXPORTED
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive(); transferState.value = WolTransferMessage.EXPORT_FAILED
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { transferState.value = WolTransferMessage.EXPORT_FAILED }
            finally {
                if (!saved) withContext(NonCancellable + Dispatchers.IO) {
                    try { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }
                    catch (_: Exception) { /* Provider may refuse deletion; error remains visible. */ }
                }
                transferBusyState.value = false
            }
        }
    }
    private var draftBaseline: WolDeviceDraft? = null
    fun add() { controller.add(); draftBaseline = controller.state.value.draft }
    fun edit(id: String) { controller.edit(id); draftBaseline = controller.state.value.draft }
    fun hasDraftChanges(): Boolean = controller.state.value.draft != draftBaseline

    val controller = WolController(
        store = (application as NetKitApplication).devices,
        networkInfo = AndroidNetworkInfoProvider(application),
        sender = AndroidPacketSender(application),
        scope = viewModelScope,
        nowMillis = System::currentTimeMillis,
        newId = { UUID.randomUUID().toString() },
        prober = AndroidWakeProber(application),
        monotonicMillis = SystemClock::elapsedRealtime,
        nameComparator = czechWolNameComparator(),
    )
}
