package cz.netkit.shared.ssh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlin.test.*

class SshPolicyTest {
    @Test fun profileValidationAndExportHaveNoSecretFields() {
        val profile = SshProfile("id-1", "Router", "router.example", username = "admin", secretRef = "ref-1", keyRef = "key-1")
        assertTrue(profile.valid())
        assertFalse(profile.copy(port = 0).valid())
        assertFalse(profile.copy(host = "host\nother").valid())
        assertFalse(profile.copy(username = "user\u0000").valid())
        assertFalse(profile.copy(startupCommand = "show\rreboot").valid())
        assertFalse(profile.copy(secretRef = "../outside").valid())
        assertFalse(profile.copy(keepaliveSeconds = 301).valid())
        val json = Json { encodeDefaults = true }
        val data = SshData(profiles = listOf(profile))
        assertEquals(data, json.decodeFromString<SshData>(json.encodeToString(data)))
        assertFalse(data.copy(profiles = listOf(profile, profile)).valid())
        assertFalse(data.copy(schema = 2).valid())
    }
    @Test fun legacyExportRemovesCredentialReferencesAndAllTrustAndKeyMetadata() {
        val profile = SshProfile("profile-1", "Fixture", "host.example", username = "user",
            secretRef = "local-ref-1", keyRef = "local-key-1", startupCommand = "status")
        val trusted = TrustedHostKey(profile.endpoint, "ssh-ed25519", "public-data", "SHA256:display")
        val source = SshData(profiles = listOf(profile), hostKeys = listOf(trusted),
            keys = listOf(SshKeyInfo("local-key-1", "Local key metadata", "public-metadata")),
            rejectedHostKeys = listOf(trusted.copy(publicKey = "rejected-data")))
        val exported = exportSshProfiles(source)
        assertEquals(SshData(profiles = listOf(profile.copy(secretRef = null, keyRef = null))), exported)
        // Check the legacy serialized structure; v2 is covered by SshProfileExchangeTest.
        val json = Json { encodeDefaults = true }
        val document = json.parseToJsonElement(json.encodeToString(exported)).jsonObject
        val connection = document.getValue("profiles").jsonArray.single().jsonObject
        assertEquals(JsonNull, connection["secretRef"])
        assertEquals(JsonNull, connection["keyRef"])
        for (metadata in listOf("hostKeys", "keys", "rejectedHostKeys")) {
            assertEquals(JsonArray(emptyList()), document[metadata])
        }
        assertEquals("local-ref-1", source.profiles.single().secretRef)
        assertEquals(listOf(trusted), source.hostKeys) // Export never mutates the stored source.
    }
    @Test fun hostTrustPinsEndpointAndExactPublicKey() {
        val known = TrustedHostKey("[host]:22", "ssh-ed25519", "blob", "SHA256:abc")
        assertEquals(HostKeyDecision.UNKNOWN, hostKeyDecision(null, known))
        assertEquals(HostKeyDecision.TRUSTED, hostKeyDecision(known, known.copy(fingerprint = "SHA256:display")))
        assertEquals(HostKeyDecision.CHANGED, hostKeyDecision(known, known.copy(publicKey = "different")))
        assertEquals(HostKeyDecision.CHANGED, hostKeyDecision(known, known.copy(endpoint = "[host]:2222")))
        assertEquals(HostKeyDecision.CHANGED, hostKeyDecision(known, known.copy(algorithm = "ssh-rsa")))
    }
    @Test fun algorithmsCannotFallBackToSha1DsaCbcOrWeakGroups() {
        val all = SshAlgorithms.kex + SshAlgorithms.hostKeys + SshAlgorithms.ciphers + SshAlgorithms.macs
        for (bad in listOf("ssh-rsa", "ssh-dss", "diffie-hellman-group1-sha1", "diffie-hellman-group14-sha1", "aes128-cbc", "3des-cbc", "arcfour", "hmac-sha1")) assertFalse(bad in all)
        assertTrue(all.none { it.contains("sha1") || it.contains("cbc") })
        assertTrue("rsa-sha2-512" in SshAlgorithms.hostKeys)
        assertTrue("curve25519-sha256" in SshAlgorithms.kex)
    }
    @Test fun pasteCannotExecuteCommandsOrInjectEscapeSequences() {
        val attack = "echo safe\r\nreboot\u001b[200~\t\u0003\u007f\u009b"
        val cleaned = safeTerminalPaste(attack)
        assertTrue(pasteNeedsConfirmation(attack))
        assertTrue(cleaned.none { it.code < 32 || it.code in 127..159 })
        assertEquals("echo hello", safeTerminalPaste("echo hello"))
        assertFalse(pasteNeedsConfirmation("echo hello"))
        assertEquals(16384, safeTerminalPaste("x".repeat(20000)).length)
    }
    @Test fun pinnedAlgorithmGoesFirstWithoutWeakeningAllowlist() {
        assertEquals("ecdsa-sha2-nistp384", SshAlgorithms.hostKeyPreference("ecdsa-sha2-nistp384").first())
        assertEquals(listOf("rsa-sha2-512", "rsa-sha2-256"), SshAlgorithms.hostKeyPreference("ssh-rsa").take(2))
        assertEquals(SshAlgorithms.hostKeys, SshAlgorithms.hostKeyPreference("ssh-dss"))
        assertEquals(SshAlgorithms.hostKeys.toSet(), SshAlgorithms.hostKeyPreference("ssh-rsa").toSet())
        assertEquals(listOf("aes256-gcm@openssh.com", "aes128-gcm@openssh.com"), SshAlgorithms.ciphers.take(2))
    }
}
