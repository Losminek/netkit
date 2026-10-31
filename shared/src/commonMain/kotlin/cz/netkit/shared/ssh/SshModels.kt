package cz.netkit.shared.ssh

import kotlinx.serialization.Serializable

@Serializable
enum class SshAuthentication { PASSWORD, KEY, INTERACTIVE }

/** Only references, never credentials, are serialised or exported. */
@Serializable
data class SshProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authentication: SshAuthentication = SshAuthentication.PASSWORD,
    val secretRef: String? = null,
    val keyRef: String? = null,
    val startupCommand: String = "",
    val keepaliveSeconds: Int = 30,
) {
    fun valid(): Boolean = validReference(id) && name.isNotBlank() && name.length <= 100 &&
        host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it.code < 32 || it.code == 127 } &&
        host.none { it in "/\\@?#[]" } && port in 1..65535 && username.isNotBlank() &&
        username.length <= 128 && username.none { it.isWhitespace() || it.code < 32 } &&
        (secretRef == null || validReference(secretRef)) && (keyRef == null || validReference(keyRef)) &&
        startupCommand.length <= 4096 && startupCommand.none { it.code < 32 || it.code == 127 } &&
        keepaliveSeconds in 0..300
    val endpoint: String get() = "[${host.lowercase()}]:$port"
}

fun validReference(value: String): Boolean = value.length in 1..80 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }

@Serializable
data class TrustedHostKey(val endpoint: String, val algorithm: String, val publicKey: String, val fingerprint: String)

@Serializable
data class SshKeyInfo(val id: String, val name: String, val publicKey: String)

@Serializable
data class SshData(val schema: Int = 1, val profiles: List<SshProfile> = emptyList(),
    val hostKeys: List<TrustedHostKey> = emptyList(), val keys: List<SshKeyInfo> = emptyList(),
    val rejectedHostKeys: List<TrustedHostKey> = emptyList()) {
    fun valid(): Boolean = schema == 1 && profiles.size <= 200 && keys.size <= 100 && hostKeys.size <= 500 &&
        profiles.all { it.valid() } && profiles.map { it.id }.distinct().size == profiles.size &&
        keys.all { validReference(it.id) && it.name.length in 1..100 && it.publicKey.length <= 16384 } &&
        keys.map { it.id }.distinct().size == keys.size &&
        hostKeys.all { it.endpoint.length in 1..300 && it.publicKey.length in 1..16384 && it.fingerprint.startsWith("SHA256:") } &&
        hostKeys.map { it.endpoint }.distinct().size == hostKeys.size &&
        rejectedHostKeys.size <= 500 && rejectedHostKeys.all {
            it.endpoint.length in 1..300 && it.publicKey.length in 1..16384 && it.fingerprint.startsWith("SHA256:")
        } && rejectedHostKeys.map { it.endpoint }.distinct().size == rejectedHostKeys.size
}

/** Export connection profiles only; local credential references and trust/key metadata stay on device. */
fun exportSshProfiles(data: SshData): SshData = SshData(
    profiles = data.profiles.map { it.copy(secretRef = null, keyRef = null) },
)

enum class HostKeyDecision { TRUSTED, UNKNOWN, CHANGED }
fun hostKeyDecision(saved: TrustedHostKey?, offered: TrustedHostKey): HostKeyDecision = when {
    saved == null -> HostKeyDecision.UNKNOWN
    saved.endpoint == offered.endpoint && saved.algorithm == offered.algorithm && saved.publicKey == offered.publicKey -> HostKeyDecision.TRUSTED
    else -> HostKeyDecision.CHANGED
}

/** Closed allowlists: an old server causes failure, never a weak fallback. */
object SshAlgorithms {
    val kex = listOf("curve25519-sha256", "curve25519-sha256@libssh.org", "ecdh-sha2-nistp256", "ecdh-sha2-nistp384", "ecdh-sha2-nistp521", "diffie-hellman-group16-sha512", "diffie-hellman-group14-sha256")
    val hostKeys = listOf("ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "rsa-sha2-512", "rsa-sha2-256")
    fun hostKeyPreference(savedAlgorithm: String?): List<String> {
        // RSA blobs use ssh-rsa, but only RSA/SHA-2 signatures remain permitted.
        val preferred = if (savedAlgorithm == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256")
            else listOfNotNull(savedAlgorithm).filter { it in hostKeys }
        return preferred + hostKeys.filterNot { it in preferred }
    }
    val ciphers = listOf("aes256-gcm@openssh.com", "aes128-gcm@openssh.com", "chacha20-poly1305@openssh.com", "aes256-ctr", "aes128-ctr")
    val macs = listOf("hmac-sha2-512-etm@openssh.com", "hmac-sha2-256-etm@openssh.com", "hmac-sha2-512", "hmac-sha2-256")
}

interface SecretStore {
    suspend fun put(id: String, secret: ByteArray)
    suspend fun get(id: String): ByteArray?
    suspend fun delete(id: String)
}

/** Pasted control bytes cannot become Enter, ESC sequences or terminal shortcuts. */
fun safeTerminalPaste(text: String): String = text.take(16384).map {
    when { it == '\r' || it == '\n' || it == '\t' -> ' '; it.code < 32 || it.code in 127..159 -> ' '; else -> it }
}.joinToString("")
fun pasteNeedsConfirmation(text: String): Boolean = text.any { it.code < 32 || it.code in 127..159 }

/** Platform-neutral lifetime and PTY controls; transport implementations live in Android. */
interface SshSession {
    fun send(text: String): Boolean
    fun resize(columns: Int, rows: Int)
    fun close()
}

/** Categorized locally; raw transport/provider messages never reach UI or logs. */
enum class SshFailure {
    HOST_KEY_CHANGED, HOST_KEY_REJECTED, AUTHENTICATION, ALGORITHMS, DNS, TIMEOUT, CONNECTION,
    IMPORT_RSA_SMALL, IMPORT_DSA, IMPORT_PASSPHRASE, IMPORT_FORMAT, SECRET_UNAVAILABLE, STORAGE, UNKNOWN,
}
