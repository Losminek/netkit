package cz.netkit.shared.ios

import cz.netkit.shared.wol.*

/** Reuses the bounded UTF-8/schema validation of the shared WoL transfer codec. */
class IosWolCodec {
    fun decode(bytes: ByteArray): IosWolLoadResult = try {
        IosWolLoadResult(decodeWolImport(bytes), null)
    } catch (error: WolImportException) {
        IosWolLoadResult(emptyList(), if (error.reason == WolImportFailure.SCHEMA) StoreFailure.UNSUPPORTED_SCHEMA else StoreFailure.CORRUPT)
    } catch (_: IllegalArgumentException) {
        IosWolLoadResult(emptyList(), StoreFailure.CORRUPT)
    }

    fun encode(devices: List<WolDevice>): ByteArray? = try {
        if (!validateStoredDevices(devices)) null else encodeWolExport(devices)
    } catch (_: IllegalArgumentException) {
        null
    }
}
