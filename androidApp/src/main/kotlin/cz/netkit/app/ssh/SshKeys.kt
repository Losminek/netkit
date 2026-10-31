package cz.netkit.app.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import cz.netkit.shared.ssh.SshKeyInfo
import cz.netkit.shared.ssh.SshFailure
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

class SshImportException(val category: SshFailure) : IllegalArgumentException("SSH key import rejected")

object SshCrypto {
    /** Use BC lightweight primitives, without replacing Android's system provider. */
    @Synchronized fun configure() {
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
    }
    fun fingerprint(blob: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    fun publicText(pair: KeyPair): String = ByteArrayOutputStream().also { pair.writePublicKey(it, "NetKit") }.toString("UTF-8").trim()
    fun validate(pair: KeyPair) {
        if (pair.keyType == KeyPair.DSA) throw SshImportException(SshFailure.IMPORT_DSA)
        if (pair.keyType !in listOf(KeyPair.ED25519, KeyPair.ECDSA, KeyPair.RSA)) throw SshImportException(SshFailure.IMPORT_FORMAT)
        if (pair.keyType == KeyPair.RSA && pair.keySize < 3072) throw SshImportException(SshFailure.IMPORT_RSA_SMALL)
    }
    fun generate(type: Int): Pair<ByteArray, String> {
        configure(); require(type in listOf(KeyPair.ED25519, KeyPair.ECDSA, KeyPair.RSA))
        val pair = KeyPair.genKeyPair(JSch(), type, if (type == KeyPair.RSA) 3072 else 256)
        try {
            validate(pair)
            val output = ByteArrayOutputStream(); pair.writeOpenSSHv1PrivateKey(output, null)
            return output.toByteArray() to publicText(pair)
        } finally { pair.dispose() }
    }
    /** Import is decrypted only in memory; the persisted representation is protected by SecretStore. */
    fun import(bytes: ByteArray, passphrase: ByteArray): Pair<ByteArray, String> {
        configure(); require(bytes.size <= 262144)
        val pair = try { KeyPair.load(JSch(), bytes, null) }
            catch (_: Exception) { throw SshImportException(SshFailure.IMPORT_FORMAT) }
        try {
            if (pair.isEncrypted && !pair.decrypt(passphrase)) throw SshImportException(SshFailure.IMPORT_PASSPHRASE)
            validate(pair)
            val output = ByteArrayOutputStream(); pair.writeOpenSSHv1PrivateKey(output, null)
            return output.toByteArray() to publicText(pair)
        } finally { pair.dispose() }
    }
}
