package cz.netkit.app.speed

import cz.netkit.shared.speed.SPEED_STORE_MAX_BYTES
import cz.netkit.shared.speed.SpeedStoreCodec
import cz.netkit.shared.speed.SpeedStoreCorrupt
import cz.netkit.shared.speed.SpeedStoreData
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Result of reading the store; [corrupt] = the file was unreadable and defaults are used. */
internal data class SpeedStoreRead(val data: SpeedStoreData, val corrupt: Boolean)

/**
 * Speed-test history, NDT7 consent and custom server in one small JSON file. Lives in
 * `noBackupFilesDir` (never part of backup/transfer; the app also has `allowBackup=false`).
 * Writes go to a temp file and are atomically moved over the old one.
 */
internal class SpeedStore(private val file: File) {
    private val mutex = Mutex()

    suspend fun read(): SpeedStoreRead = withContext(Dispatchers.IO) { mutex.withLock { readLocked() } }

    /** Applies [transform] to the current data and persists it; returns the stored data. */
    suspend fun update(transform: (SpeedStoreData) -> SpeedStoreData): SpeedStoreData = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = transform(readLocked().data)
            writeLocked(next)
            next
        }
    }

    private fun readLocked(): SpeedStoreRead {
        if (!file.exists()) return SpeedStoreRead(SpeedStoreData(), corrupt = false)
        return try {
            if (file.length() > SPEED_STORE_MAX_BYTES) throw SpeedStoreCorrupt("size")
            SpeedStoreRead(SpeedStoreCodec.decode(file.readText(Charsets.UTF_8)), corrupt = false)
        } catch (_: SpeedStoreCorrupt) {
            SpeedStoreRead(SpeedStoreData(), corrupt = true)
        } catch (_: IOException) {
            SpeedStoreRead(SpeedStoreData(), corrupt = true)
        }
    }

    private fun writeLocked(data: SpeedStoreData) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(SpeedStoreCodec.encode(data), Charsets.UTF_8)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
