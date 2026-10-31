package cz.netkit.app.ssh

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import cz.netkit.shared.ssh.SecretStore
import cz.netkit.shared.ssh.SshData
import cz.netkit.shared.ssh.TrustedHostKey
import cz.netkit.shared.ssh.validReference
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

object SshDataSerializer : Serializer<SshData> {
    override val defaultValue = SshData()
    val json = Json { encodeDefaults = true }
    override suspend fun readFrom(input: InputStream): SshData {
        try {
            val bytes = input.readBytesBounded(1_048_576)
            return json.decodeFromString<SshData>(bytes.decodeToString(throwOnInvalidSequence = true)).also {
                if (!it.valid()) throw CorruptionException("Invalid SSH data or unsupported schema")
            }
        } catch (e: SerializationException) { throw CorruptionException("Invalid SSH JSON", e) }
        catch (e: IllegalArgumentException) { throw CorruptionException("Invalid SSH data", e) }
    }
    override suspend fun writeTo(t: SshData, output: OutputStream) {
        require(t.valid())
        val bytes = json.encodeToString(t).encodeToByteArray()
        require(bytes.size <= 1_048_576)
        output.write(bytes)
    }
}

fun InputStream.readBytesBounded(limit: Int): ByteArray {
    val buffer = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
    while (true) { val n = read(chunk); if (n < 0) break; require(buffer.size() + n <= limit); buffer.write(chunk, 0, n) }
    return buffer.toByteArray()
}

class SshStore(private val file: File, private val ownerScope: CoroutineScope) {
    constructor(context: Context, scope: CoroutineScope, file: File = context.filesDir.resolve("datastore/ssh.json")) : this(file, scope)
    private val mutex = Mutex()
    private var job: Job? = null
    private var store: androidx.datastore.core.DataStore<SshData>? = null
    var archivePath: String? = null; private set
    @Synchronized private fun current(): androidx.datastore.core.DataStore<SshData> = store ?: run {
        val child = SupervisorJob(ownerScope.coroutineContext[Job]); job = child
        DataStoreFactory.create(serializer = SshDataSerializer,
            scope = CoroutineScope(ownerScope.coroutineContext + child + Dispatchers.IO), produceFile = { file }).also { store = it }
    }
    private fun discoverArchive() {
        val parent = file.absoluteFile.parentFile?.toPath() ?: return
        if (!java.nio.file.Files.isDirectory(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        try {
            java.nio.file.Files.newDirectoryStream(parent, "ssh-archive-*").use { directories ->
                archivePath = directories.filter { java.nio.file.Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                    .map { it.resolve(file.name) }.filter { java.nio.file.Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                    .maxByOrNull { java.nio.file.Files.getLastModifiedTime(it, java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis() }?.toAbsolutePath()?.toString()
            }
        } catch (_: java.io.IOException) { /* Archive receipt lookup must not block valid data. */ }
    }
    val data get() = flow { discoverArchive(); emitAll(current().data) }.flowOn(Dispatchers.IO)
    suspend fun read() = mutex.withLock { withContext(Dispatchers.IO) { discoverArchive(); current().data.first() } }
    suspend fun update(block: (SshData) -> SshData) = mutex.withLock {
        current().updateData { block(it).also { value -> require(value.valid()) } }
    }
    /** Explicit confirmation required by caller. Never reset IO failures or a repaired valid file. */
    suspend fun recover(): SshData = mutex.withLock {
        withContext(Dispatchers.IO) {
            withContext(NonCancellable) { job?.cancelAndJoin(); job = null; store = null }
            try {
                return@withContext if (file.exists()) file.inputStream().use { SshDataSerializer.readFrom(it) } else SshData()
            } catch (_: CorruptionException) { /* Only corrupted/unsupported data can be archived. */ }
            withContext(NonCancellable) {
                val directory = java.nio.file.Files.createTempDirectory(requireNotNull(file.absoluteFile.parentFile).toPath(), "ssh-archive-")
                val target = directory.resolve(file.name)
                try { java.nio.file.Files.move(file.toPath(), target, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
                catch (error: Exception) {
                    try { java.nio.file.Files.deleteIfExists(directory) } catch (_: java.io.IOException) { }
                    throw error
                }
                archivePath = target.toAbsolutePath().toString()
                current().data.first()
            }
        }
    }
    /** Compare-and-insert. A racing confirmation cannot replace an existing host key. */
    suspend fun trustNew(key: TrustedHostKey) {
        update { data ->
            val old = data.hostKeys.find { it.endpoint == key.endpoint }
            check(old == null || old == key)
            if (old == null) data.copy(hostKeys = data.hostKeys + key) else data
        }
    }
    suspend fun recordRejected(key: TrustedHostKey) = update { data ->
        data.copy(rejectedHostKeys = (data.rejectedHostKeys.filterNot { it.endpoint == key.endpoint } + key).takeLast(500))
    }
}

class SecretUnavailableException : java.security.GeneralSecurityException("Stored SSH secret is unavailable")

internal fun selectSecretKey(aliasExists: Boolean, read: () -> SecretKey?, create: () -> SecretKey): SecretKey =
    if (aliasExists) read() ?: throw SecretUnavailableException() else create()

enum class KeyProtection { STRONGBOX, HARDWARE, SOFTWARE }

/** Ciphertexts only; AEAD binds each file to its reference so swaps fail closed. */
class AndroidSecretStore(private val context: Context, namespace: String = "ssh") : SecretStore {
    init { require(validReference(namespace)) }
    // Keystore aliases are process-wide: parallel store instances must not regenerate the same key.
    private val mutex = processMutex
    private val directory = context.noBackupFilesDir.resolve("$namespace-secrets")
    private val alias = "netkit.$namespace.aes.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setIsStrongBoxBacked(strongBox).build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec); generateKey() }
        }
        return selectSecretKey(store.containsAlias(alias), { store.getKey(alias, null) as? SecretKey }) {
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
                try { generate(true) } catch (_: StrongBoxUnavailableException) { generate(false) }
            } else generate(false)
        }
    }
    suspend fun protection(): KeyProtection = withContext(Dispatchers.IO) { mutex.withLock {
        val info = SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(key(), KeyInfo::class.java) as KeyInfo
        if (android.os.Build.VERSION.SDK_INT >= 31 && info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX) KeyProtection.STRONGBOX
        else if (info.isInsideSecureHardware) KeyProtection.HARDWARE else KeyProtection.SOFTWARE
    } }
    private fun file(id: String): File { require(validReference(id)); return directory.resolve("$id.bin") }
    override suspend fun put(id: String, secret: ByteArray) = withContext(Dispatchers.IO) { mutex.withLock {
        require(secret.size <= 262144); val target = file(id)
        check(directory.isDirectory || directory.mkdirs())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(id.encodeToByteArray())
        val encrypted = cipher.doFinal(secret)
        val atomic = android.util.AtomicFile(target)
        val stream = atomic.startWrite()
        try { stream.write(byteArrayOf(1)); stream.write(cipher.iv); stream.write(encrypted); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    } }
    override suspend fun get(id: String): ByteArray? = withContext(Dispatchers.IO) { mutex.withLock {
        val target = file(id); if (!target.exists()) return@withLock null
        try {
            // Reading an existing ciphertext never creates a replacement key.
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(alias)) throw SecretUnavailableException()
            val data = android.util.AtomicFile(target).openRead().use { it.readBytesBounded(262200) }
            require(data.size >= 29 && data[0].toInt() == 1)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, 13)))
            cipher.updateAAD(id.encodeToByteArray()); cipher.doFinal(data, 13, data.size - 13)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw SecretUnavailableException() }
    } }
    override suspend fun delete(id: String) = withContext(Dispatchers.IO) { mutex.withLock { android.util.AtomicFile(file(id)).delete() } }
    private companion object { val processMutex = Mutex() }
}
