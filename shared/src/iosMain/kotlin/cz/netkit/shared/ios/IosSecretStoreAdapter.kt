package cz.netkit.shared.ios

import cz.netkit.shared.ssh.SecretStore
import cz.netkit.shared.ssh.SshFailure
import cz.netkit.shared.ssh.validReference

class IosSecretReadResult(val bytes: ByteArray?, val failure: SshFailure?)

/** Swift Keychain implementation catches OSStatus/NSError and returns categorized failures only. */
interface IosSecretBackend {
    fun store(id: String, bytes: ByteArray): SshFailure?
    fun read(id: String): IosSecretReadResult
    fun remove(id: String): SshFailure?
}

/** The synchronous Swift consumer and shared suspend SecretStore use the same checked backend. */
class IosSecretStoreAdapter(private val backend: IosSecretBackend) {
    fun putSecret(id: String, secret: ByteArray): SshFailure? {
        if (!validReference(id) || secret.size > 256 * 1024) return SshFailure.SECRET_UNAVAILABLE
        return backend.store(id, secret)
    }
    fun getSecret(id: String): IosSecretReadResult {
        if (!validReference(id)) return IosSecretReadResult(null, SshFailure.SECRET_UNAVAILABLE)
        val result = backend.read(id)
        if (result.failure != null) return IosSecretReadResult(null, result.failure)
        if ((result.bytes?.size ?: 0) > 256 * 1024) return IosSecretReadResult(null, SshFailure.SECRET_UNAVAILABLE)
        return result
    }
    fun deleteSecret(id: String): SshFailure? = if (!validReference(id)) SshFailure.SECRET_UNAVAILABLE else backend.remove(id)

    internal val sharedStore: SecretStore = object : SecretStore {
        override suspend fun put(id: String, secret: ByteArray) {
            putSecret(id, secret)?.let { throw IosSecretStoreException(it) }
        }
        override suspend fun get(id: String): ByteArray? {
            val result = getSecret(id)
            result.failure?.let { throw IosSecretStoreException(it) }
            return result.bytes
        }
        override suspend fun delete(id: String) {
            deleteSecret(id)?.let { throw IosSecretStoreException(it) }
        }
    }
}

/** Remains inside Kotlin suspend callers; Swift uses explicit result-returning methods above. */
internal class IosSecretStoreException(val failure: SshFailure) : Exception(failure.name)
