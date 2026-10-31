package cz.netkit.app.wol

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import cz.netkit.shared.wol.StoreFailure
import cz.netkit.shared.wol.StoreReadException
import cz.netkit.shared.wol.StoreRecovery
import cz.netkit.shared.wol.WolDevice
import cz.netkit.shared.wol.WolDeviceStore
import cz.netkit.shared.wol.validateStoredDevices
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class DeviceEnvelope(val schema: Int, val devices: List<WolDevice>)
class UnsupportedDeviceSchema(val schema: Int) : java.io.IOException("Unsupported WoL schema $schema")

object DeviceSerializer : Serializer<DeviceEnvelope> {
    override val defaultValue = DeviceEnvelope(2, emptyList())
    private val json = Json { encodeDefaults = true }

    override suspend fun readFrom(input: InputStream): DeviceEnvelope {
        try {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count == -1) break
                if (buffer.size() + count > 1_048_576) throw CorruptionException("WoL data exceeds size limit")
                buffer.write(chunk, 0, count)
            }
            val text = buffer.toByteArray().decodeToString(throwOnInvalidSequence = true)
            // Detect future schema before decoding fields whose structure may have changed.
            val schema = json.parseToJsonElement(text).jsonObject["schema"]?.jsonPrimitive?.intOrNull
                ?: throw CorruptionException("Missing WoL schema")
            if (schema !in 1..2) throw UnsupportedDeviceSchema(schema)
            val snapshot = json.decodeFromString<DeviceEnvelope>(text)
            if (!validateStoredDevices(snapshot.devices)) throw CorruptionException("Invalid WoL data")
            return snapshot // Keep the disk version so the next schema-2 update is not equal and must persist.
        } catch (error: CharacterCodingException) {
            throw CorruptionException("Invalid WoL UTF-8", error)
        } catch (error: SerializationException) {
            throw CorruptionException("Invalid WoL JSON", error)
        } catch (error: IllegalArgumentException) {
            throw CorruptionException("Invalid WoL values", error)
        }
    }

    override suspend fun writeTo(t: DeviceEnvelope, output: OutputStream) {
        require(t.schema in 1..2 && validateStoredDevices(t.devices))
        val bytes = json.encodeToString(t.copy(schema = 2)).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1_048_576) { "WoL data exceeds size limit" }
        output.write(bytes)
    }
}

/** A single owner serializes access and joins its DataStore before archiving the original. */
class AndroidDeviceStore(
    private val file: File,
    private val ownerScope: CoroutineScope,
    private val beforeCreate: () -> Unit = {},
    private val archive: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        Unit
    },
) : WolDeviceStore {
    constructor(context: Context) : this(context, context.filesDir.resolve("datastore/wol-devices.json"))
    constructor(context: Context, file: File) : this(file, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val mutex = Mutex()
    private var job: Job? = null
    private var store: DataStore<DeviceEnvelope>? = null
    @Volatile private var lastArchivePath: String? = null
    override val recoveryArchivePath: String? get() = lastArchivePath
    private var closed = false

    private fun current(): DataStore<DeviceEnvelope> {
        check(!closed) { "Store closed" }
        return store ?: run {
            beforeCreate()
            val child = SupervisorJob(ownerScope.coroutineContext[Job])
            job = child
            DataStoreFactory.create(serializer = DeviceSerializer,
                scope = CoroutineScope(ownerScope.coroutineContext + child + Dispatchers.IO)) { file }
                .also { store = it }
        }
    }
    private fun failure(error: Exception): StoreReadException = when (error) {
        is UnsupportedDeviceSchema -> StoreReadException(StoreFailure.UNSUPPORTED_SCHEMA, error.schema, error, lastArchivePath)
        is CorruptionException -> StoreReadException(StoreFailure.CORRUPT, cause = error, archivePath = lastArchivePath)
        else -> StoreReadException(StoreFailure.IO, cause = error, archivePath = lastArchivePath)
    }
    private suspend fun discoverArchive() = withContext(Dispatchers.IO) {
        // Only inspect direct private archive directories; never follow links or decode the original.
        val parent = file.absoluteFile.parentFile?.toPath() ?: return@withContext
        var newest: java.nio.file.Path? = null
        var newestTime = Long.MIN_VALUE
        try {
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return@withContext
            Files.newDirectoryStream(parent, "wol-archive-*").use { directories ->
                for (directory in directories) {
                    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue
                    val candidate = directory.resolve(file.name)
                    if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue
                    val time = Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS).toMillis()
                    if (time > newestTime || (time == newestTime && candidate.toString() > newest.toString())) {
                        newest = candidate
                        newestTime = time
                    }
                }
            }
            newest?.let { lastArchivePath = it.toAbsolutePath().toString() }
        } catch (_: java.io.IOException) {
            // Receipt discovery must not prevent reading otherwise usable device data.
        }
    }
    override suspend fun load(): List<WolDevice> = mutex.withLock {
        discoverArchive()
        try { current().data.first().devices }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw failure(e) }
    }
    override suspend fun save(devices: List<WolDevice>) = mutex.withLock {
        require(validateStoredDevices(devices))
        current().updateData { DeviceEnvelope(2, devices) }
        Unit
    }
    override suspend fun recover(): StoreRecovery = mutex.withLock {
        discoverArchive()
        try {
            withContext(Dispatchers.IO) {
                // No other DataStore may hold this path when the file is moved.
                withContext(NonCancellable) {
                    job?.cancelAndJoin()
                    job = null
                    store = null
                }
                try {
                    val valid = if (file.exists()) file.inputStream().use { DeviceSerializer.readFrom(it) } else DeviceSerializer.defaultValue
                    // A repaired/replaced valid file must not be reset, even after confirmation.
                    return@withContext StoreRecovery(lastArchivePath, valid.devices)
                } catch (_: CorruptionException) { /* explicit recovery allowed */
                } catch (_: UnsupportedDeviceSchema) { /* explicit recovery allowed */ }
                withContext(NonCancellable) {
                    val parent = requireNotNull(file.absoluteFile.parentFile)
                    val directory = Files.createTempDirectory(parent.toPath(), "wol-archive-").toFile()
                    val target = directory.resolve(file.name)
                    try {
                        archive(file, target)
                    } catch (error: Exception) {
                        // Delete only the empty directory. Never remove a possibly moved original.
                        try { Files.deleteIfExists(directory.toPath()) }
                        catch (cleanup: java.io.IOException) { error.addSuppressed(cleanup) }
                        throw error
                    }
                    lastArchivePath = target.absolutePath
                    // Initialize and read while still protected, retaining archive path on any failure.
                    StoreRecovery(lastArchivePath, current().data.first().devices)
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { throw failure(e)
        }
    }
    suspend fun close() = mutex.withLock {
        closed = true
        job?.cancelAndJoin()
        job = null
        store = null
    }
}
