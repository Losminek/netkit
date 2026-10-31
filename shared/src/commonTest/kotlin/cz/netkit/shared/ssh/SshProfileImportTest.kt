package cz.netkit.shared.ssh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class SshProfileImportTest {
    private fun row(alias: String = "proxmox", host: String = "192.168.0.10", port: Int = 22, username: String = "root") =
        """{"alias":"$alias","group":"Homelab","host":"$host","port":$port,"username":"$username"}"""
    private fun document(rows: String = row(), skipped: String = "") =
        """{"format":"netkit-ssh-profiles","schema":1,"source":"build-host","profiles":[$rows],"skipped":[$skipped]}"""
    private fun decode(text: String = document()) = decodeSshProfileImport(text.encodeToByteArray())
    private fun failure(reason: SshProfileImportFailure, block: () -> Unit) {
        assertEquals(reason, assertFailsWith<SshProfileImportException>(block = block).reason)
    }
    private fun plan(current: SshData = SshData(), incoming: SshProfileImport = decode(), key: String? = null): SshImportPlan {
        var id = 0
        return planSshProfileImport(current, incoming, key) { "new-${++id}" }
    }

    @Test fun validDocumentAndBom() {
        val incoming = decode()
        assertEquals("build-host", incoming.source)
        assertEquals(SshImportProfile("proxmox", "192.168.0.10", 22, "root", "Homelab"), incoming.profiles.single())
        assertEquals(incoming, decode("\uFEFF" + document()))
        assertEquals(null, decode(document().replace("\"source\":\"build-host\",", "")).source)
        assertEquals(null, decode(document().replace("\"group\":\"Homelab\",", "")).profiles.single().group)
    }

    @Test fun sizeUtf8FormatAndSchemaAreTyped() {
        failure(SshProfileImportFailure.SIZE) { decodeSshProfileImport(ByteArray(SSH_PROFILE_IMPORT_LIMIT + 1)) }
        failure(SshProfileImportFailure.UTF8) { decodeSshProfileImport(byteArrayOf(0xc3.toByte(), 0x28)) }
        for (text in listOf("[]", "{", document().replace("netkit-ssh-profiles", "other"),
            document().replace("\"username\":\"root\"", "\"username\":42"), document().replace("\"profiles\":[${row()}]", "\"profiles\":{}"))) {
            failure(SshProfileImportFailure.FORMAT) { decode(text) }
        }
        for (schema in listOf("2", "\"1\"", "null", "{}")) {
            failure(SshProfileImportFailure.SCHEMA) { decode(document().replace("\"schema\":1", "\"schema\":$schema")) }
        }
        val exact = document().padEnd(SSH_PROFILE_IMPORT_LIMIT, ' ')
        assertEquals(decode(), decode(exact))
    }

    @Test fun unknownFieldsIncludingCredentialsRejectWholeDocument() {
        failure(SshProfileImportFailure.UNKNOWN_FIELD) { decode(document().replaceFirst("{", "{\"extra\":true,")) }
        for (field in listOf("password", "privateKey", "passphrase", "identityFile", "keyRef", "extra")) {
            failure(SshProfileImportFailure.UNKNOWN_FIELD) { decode(document(row().replaceFirst("{", "{\"$field\":\"forbidden\","))) }
        }
        failure(SshProfileImportFailure.UNKNOWN_FIELD) {
            decode(document(skipped = """{"alias":"jump","reason":"PROXY_JUMP","extra":0}"""))
        }
    }

    @Test fun duplicateAliasesRejectWholeFileIncludingSkipped() {
        failure(SshProfileImportFailure.DUPLICATE_ALIAS) { decode(document("${row()},${row()}")) }
        failure(SshProfileImportFailure.DUPLICATE_ALIAS) {
            decode(document(skipped = """{"alias":"proxmox","reason":"PROXY_JUMP"}"""))
        }
        failure(SshProfileImportFailure.DUPLICATE_ALIAS) {
            val skipped = """{"alias":"jump","reason":"PROXY_JUMP"}"""
            decode(document(skipped = "$skipped,$skipped"))
        }
    }

    @Test fun invalidProfilesNeverReachPreview() {
        for (host in listOf("host name", "user@host", "host/path", "", "h".repeat(254))) {
            failure(SshProfileImportFailure.INVALID_PROFILE) { decode(document(row(host = host))) }
        }
        for (port in listOf(0, 65536)) failure(SshProfileImportFailure.INVALID_PROFILE) { decode(document(row(port = port))) }
        for (username in listOf("", "two words", "u".repeat(129))) {
            failure(SshProfileImportFailure.INVALID_PROFILE) { decode(document(row(username = username))) }
        }
        for (alias in listOf("", " ", "a".repeat(101), "bad\\n", "bad\\u007f", "bad\\u0085")) {
            failure(SshProfileImportFailure.INVALID_PROFILE) { decode(document(row(alias = alias))) }
        }
        failure(SshProfileImportFailure.INVALID_PROFILE) { decode(document().replace("Homelab", "g".repeat(61))) }
        failure(SshProfileImportFailure.FORMAT) { decode(document().replace("build-host", "s".repeat(65))) }
    }

    @Test fun rowLimitsAndAllSkipReasons() {
        failure(SshProfileImportFailure.LIMIT) { decode(document((1..201).joinToString(",") { row(alias = "host-$it") })) }
        failure(SshProfileImportFailure.LIMIT) {
            decode(document(skipped = (1..201).joinToString(",") { """{"alias":"skip-$it","reason":"MATCH"}""" }))
        }
        assertEquals(200, decode(document((1..200).joinToString(",") { row(alias = "host-$it") })).profiles.size)
        val skipped = SshImportSkipReason.entries.joinToString(",") { """{"alias":"$it","reason":"$it"}""" }
        val incoming = decode(document(skipped = skipped))
        val planned = plan(incoming = incoming)
        assertEquals(incoming.skipped, planned.skipped)
        assertEquals(1, applySshImportPlan(SshData(), planned).profiles.size)
        failure(SshProfileImportFailure.FORMAT) { decode(document(skipped = """{"alias":"jump","reason":"OTHER"}""")) }
    }

    @Test fun updatesPreserveOtherLocalFieldsAndOnlyMatchExactName() {
        val existing = SshProfile("saved", "proxmox", "old.example", 2222, "old-user",
            SshAuthentication.INTERACTIVE, "secret-ref", "old-key", "show version", 120)
        val caseDifferent = existing.copy(id = "other", name = "Proxmox")
        val current = SshData(profiles = listOf(existing, caseDifferent))
        val planned = plan(current, key = "new-key")
        assertEquals(1, planned.updatedProfiles.size)
        assertTrue(planned.newProfiles.isEmpty())
        val result = applySshImportPlan(current, planned)
        assertEquals(existing.copy(host = "192.168.0.10", port = 22, username = "root", secretRef = null), result.profiles.first())
        assertEquals(caseDifferent, result.profiles.last())
        val unchanged = plan(result)
        assertEquals(1, unchanged.unchangedProfiles.size)
        assertTrue(unchanged.updatedProfiles.isEmpty())
        assertEquals(result, applySshImportPlan(result, unchanged))
    }

    @Test fun eachTargetChangeDetachesSavedPassword() {
        val existing = SshProfile("saved", "proxmox", "192.168.0.10", username = "root",
            authentication = SshAuthentication.PASSWORD, secretRef = "secret-ref", keyRef = "local-key",
            startupCommand = "show version", keepaliveSeconds = 120)
        for (changed in listOf(existing.copy(host = "new.example"), existing.copy(port = 2222), existing.copy(username = "other"))) {
            val incoming = decode(document(row(host = changed.host, port = changed.port, username = changed.username)))
            val current = SshData(profiles = listOf(existing))
            val planned = plan(current, incoming)
            val entry = planned.updatedProfiles.single()
            assertEquals(changed.copy(secretRef = null), entry.after)
            assertEquals("secret-ref", entry.detachedSecretRef)
            assertEquals(listOf("secret-ref"), planned.detachedSecretRefs)
            assertEquals(entry.after, applySshImportPlan(current, planned).profiles.single())
        }
    }

    @Test fun unchangedTargetKeepsPasswordAndOtherRowsDetachNothing() {
        val existing = SshProfile("saved", "proxmox", "192.168.0.10", username = "root", secretRef = "secret-ref")
        val unchanged = plan(SshData(profiles = listOf(existing)))
        assertEquals(existing, unchanged.unchangedProfiles.single().after)
        assertNull(unchanged.unchangedProfiles.single().detachedSecretRef)
        assertTrue(unchanged.detachedSecretRefs.isEmpty())
        val noPassword = plan(SshData(profiles = listOf(existing.copy(host = "old.example", secretRef = null))))
        assertNull(noPassword.updatedProfiles.single().after.secretRef)
        assertNull(noPassword.updatedProfiles.single().detachedSecretRef)
        assertTrue(noPassword.detachedSecretRefs.isEmpty())
        val added = plan()
        assertNull(added.newProfiles.single().detachedSecretRef)
        assertTrue(added.detachedSecretRefs.isEmpty())
    }

    @Test fun newProfilesUseSelectedKeyOrPasswordWithoutSecret() {
        for (key in listOf(null, "phone-key")) {
            val profile = applySshImportPlan(SshData(), plan(key = key)).profiles.single()
            assertEquals("new-1", profile.id)
            assertTrue(validReference(profile.id))
            assertEquals(if (key == null) SshAuthentication.PASSWORD else SshAuthentication.KEY, profile.authentication)
            assertEquals(key, profile.keyRef)
            assertNull(profile.secretRef)
        }
    }

    @Test fun invalidIdsAndAmbiguousLocalAliasesFailClosed() {
        failure(SshProfileImportFailure.INVALID_REFERENCE) { planSshProfileImport(SshData(), decode(), null) { "../id" } }
        failure(SshProfileImportFailure.INVALID_REFERENCE) { plan(key = "../key") }
        failure(SshProfileImportFailure.DUPLICATE_ID) {
            planSshProfileImport(SshData(), decode(document("${row()},${row(alias = "another")}")), null) { "same" }
        }
        val existing = SshProfile("id", "proxmox", "host", username = "root")
        failure(SshProfileImportFailure.AMBIGUOUS_ALIAS) { plan(SshData(profiles = listOf(existing, existing.copy(id = "id-2")))) }
        failure(SshProfileImportFailure.DUPLICATE_ID) {
            planSshProfileImport(SshData(profiles = listOf(existing.copy(name = "other"))), decode(), null) { "id" }
        }
    }

    @Test fun overCapacityAndStalePlansDoNotMutateCurrentData() {
        val profiles = (1..200).map { SshProfile("id-$it", "host-$it", "localhost", username = "root") }
        val current = SshData(profiles = profiles)
        val planned = plan(current)
        failure(SshProfileImportFailure.LIMIT) { applySshImportPlan(current, planned) }
        assertEquals(profiles, current.profiles)
        val changed = SshData(profiles = listOf(profiles.first()))
        failure(SshProfileImportFailure.STALE_PLAN) { applySshImportPlan(changed, plan()) }
        assertEquals(listOf(profiles.first()), changed.profiles)
    }

    @Test fun trustMetadataSurvivesAndSerializedSchemaHasNoImportFields() {
        val host = TrustedHostKey("[host]:22", "ssh-ed25519", "public", "SHA256:fingerprint")
        val current = SshData(keys = listOf(SshKeyInfo("key", "Phone", "public")), hostKeys = listOf(host), rejectedHostKeys = listOf(host))
        val result = applySshImportPlan(current, plan(current, key = "key"))
        assertEquals(current.keys, result.keys)
        assertEquals(current.hostKeys, result.hostKeys)
        assertEquals(current.rejectedHostKeys, result.rejectedHostKeys)
        assertEquals(1, result.schema)
        val json = Json { encodeDefaults = true }
        val root = json.parseToJsonElement(json.encodeToString(SshData.serializer(), result)).jsonObject
        assertEquals(setOf("schema", "profiles", "hostKeys", "keys", "rejectedHostKeys"), root.keys)
        assertEquals(setOf("id", "name", "host", "port", "username", "authentication", "secretRef", "keyRef", "startupCommand", "keepaliveSeconds"),
            root.getValue("profiles").jsonArray.single().jsonObject.keys)
        assertEquals(result, json.decodeFromString(SshData.serializer(), root.toString()))
    }
}
