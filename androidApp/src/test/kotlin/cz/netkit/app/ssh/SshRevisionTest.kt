package cz.netkit.app.ssh

import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import cz.netkit.shared.ssh.*
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SshRevisionTest {
    @Test fun existingUnavailableAliasIsNeverReplaced() {
        var creates = 0
        val generated = SecretKeySpec(ByteArray(32), "AES")
        assertFailsWith<SecretUnavailableException> { selectSecretKey(true, { null }) { creates++; generated } }
        assertEquals(0, creates)
        assertSame(generated, selectSecretKey(false, { error("Must not read absent alias") }) { creates++; generated })
        assertEquals(1, creates)
        assertSame(generated, selectSecretKey(true, { generated }) { error("Must not replace existing alias") })
    }
    @Test fun categoriesNeverExposeTransportMessages() {
        assertEquals(SshFailure.DNS, classifySshFailure(UnknownHostException("private server name")))
        assertEquals(SshFailure.TIMEOUT, classifySshFailure(SocketTimeoutException()))
        assertEquals(SshFailure.AUTHENTICATION, classifySshFailure(JSchException("Auth fail for methods 'password'")))
        assertEquals(SshFailure.AUTHENTICATION, classifySshFailure(JSchException("Auth cancel")))
        assertEquals(SshFailure.CONNECTION, classifySshFailure(JSchException("unclassified transport failure")))
        assertEquals(SshFailure.CONNECTION, classifySshFailure(java.io.IOException()))
        assertEquals(SshFailure.SECRET_UNAVAILABLE, classifySshFailure(SecretUnavailableException()))
        for (kind in listOf(SshFailure.IMPORT_RSA_SMALL, SshFailure.IMPORT_DSA, SshFailure.IMPORT_PASSPHRASE, SshFailure.IMPORT_FORMAT))
            assertEquals(kind, classifySshFailure(SshImportException(kind)))
    }
    @Test fun corruptMetadataIsArchivedVerbatimAndValidMetadataIsNotReset() = runTest {
        val directory = Files.createTempDirectory("netkit-ssh-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = File(directory, "ssh.json")
            val original = "broken metadata".encodeToByteArray()
            file.writeBytes(original)
            val store = SshStore(file, scope)
            assertFails { store.read() }
            assertEquals(SshData(), store.recover())
            assertContentEquals(original, File(requireNotNull(store.archivePath)).readBytes())
            val profile = SshProfile("id-1", "Local", "127.0.0.1", username = "test")
            store.update { it.copy(profiles = listOf(profile)) }
            assertEquals(listOf(profile), store.recover().profiles)
            assertContentEquals(original, File(requireNotNull(store.archivePath)).readBytes())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); directory.deleteRecursively() }
    }
    @Test fun forgottenTrustRetainsTheLastRejectedFingerprint() = runTest {
        val directory = Files.createTempDirectory("netkit-ssh-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = SshStore(File(directory, "ssh.json"), scope)
            val known = TrustedHostKey("[127.0.0.1]:22", "ssh-ed25519", "known", "SHA256:first")
            val rejected = known.copy(publicKey = "offered", fingerprint = "SHA256:rejected")
            store.trustNew(known); store.recordRejected(rejected)
            store.update { it.copy(hostKeys = emptyList()) }
            assertEquals(listOf(rejected), store.read().rejectedHostKeys)
            assertTrue(store.read().hostKeys.isEmpty())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); directory.deleteRecursively() }
    }
}
