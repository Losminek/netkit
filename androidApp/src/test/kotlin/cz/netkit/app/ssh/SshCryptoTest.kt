package cz.netkit.app.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import cz.netkit.shared.ssh.SshData
import cz.netkit.shared.ssh.SshProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SshCryptoTest {
    @Test fun generatedKeysRoundtripThroughOpenSshAndSignVerify() {
        for (type in listOf(KeyPair.ED25519, KeyPair.ECDSA, KeyPair.RSA)) {
            val (bytes, public) = SshCrypto.generate(type)
            try {
                assertTrue(public.startsWith(when (type) { KeyPair.ED25519 -> "ssh-ed25519 "; KeyPair.ECDSA -> "ecdsa-sha2-nistp256 "; else -> "ssh-rsa " }))
                val loaded = KeyPair.load(JSch(), bytes, null)
                try {
                    SshCrypto.validate(loaded)
                    assertEquals(type, loaded.keyType)
                    val message = "NetKit local test".toByteArray()
                    val algorithm = if (type == KeyPair.RSA) "rsa-sha2-512" else public.substringBefore(' ')
                    val signature = loaded.getSignature(message, algorithm)
                    val verifier = loaded.getVerifier(algorithm); verifier.update(message)
                    assertTrue(verifier.verify(signature))
                } finally { loaded.dispose() }
            } finally { bytes.fill(0) }
        }
    }
    @Test fun encryptedKeyImportRequiresCorrectPassphrase() {
        SshCrypto.configure()
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519, 256)
        try {
            val output = ByteArrayOutputStream(); val phrase = "local-generated-fixture".toByteArray()
            pair.writeOpenSSHv1PrivateKey(output, phrase)
            assertFails { SshCrypto.import(output.toByteArray(), "wrong".toByteArray()) }
            val (secret, public) = SshCrypto.import(output.toByteArray(), phrase)
            assertTrue(public.startsWith("ssh-ed25519 ")); secret.fill(0); phrase.fill(0)
        } finally { pair.dispose() }
    }
    @Test fun pemImportSupportsEncryptedRsaAndEcdsa() {
        SshCrypto.configure()
        for (type in listOf(KeyPair.RSA, KeyPair.ECDSA)) {
            val pair = KeyPair.genKeyPair(JSch(), type, if (type == KeyPair.RSA) 3072 else 256)
            try {
                val output = ByteArrayOutputStream(); val phrase = "local-pem-fixture".toByteArray()
                pair.writePrivateKey(output, phrase)
                assertFails { SshCrypto.import(output.toByteArray(), "wrong".toByteArray()) }
                val (secret, public) = SshCrypto.import(output.toByteArray(), phrase)
                assertEquals(SshCrypto.publicText(pair), public); secret.fill(0); phrase.fill(0)
            } finally { pair.dispose() }
        }
    }
    @Test fun rsaBelow3072AndDsaAreRejected() {
        for (type in listOf(KeyPair.RSA, KeyPair.DSA)) {
            val pair = KeyPair.genKeyPair(JSch(), type, 1024)
            try { assertFailsWith<IllegalArgumentException> { SshCrypto.validate(pair) } } finally { pair.dispose() }
        }
    }
    @Test fun jsonSerializerRoundtripDoesNotContainCredentials() = runTest {
        val original = SshData(profiles = listOf(SshProfile("id-1", "local", "localhost", username = "test", secretRef = "reference-1")))
        val output = ByteArrayOutputStream(); SshDataSerializer.writeTo(original, output)
        assertEquals(original, SshDataSerializer.readFrom(ByteArrayInputStream(output.toByteArray())))
        assertFalse(output.toString("UTF-8").contains("PRIVATE KEY"))
        assertFails { SshDataSerializer.readFrom(ByteArrayInputStream("{\"schema\":2}".toByteArray())) }
        assertFails { SshDataSerializer.readFrom(ByteArrayInputStream("not json".toByteArray())) }
    }
}
