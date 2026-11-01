package cz.netkit.shared.ssh

import kotlinx.serialization.json.*
import kotlin.test.*

class SshProfileExchangeTest {
    private val profiles = SshAuthentication.entries.mapIndexed { index, auth ->
        SshProfile("old-$index", "fixture-$index", "192.0.2.${index + 1}", 2222 + index, "tester",
            auth, "secret-$index", "key-$index", "show version", 60 + index)
    }
    private val source = SshData(profiles = profiles,
        keys = listOf(SshKeyInfo("key-1", "fixture", "public")),
        hostKeys = listOf(TrustedHostKey("[example.test]:22", "ssh-ed25519", "public", "SHA256:fixture")))
    private fun restore(bytes: ByteArray, current: SshData = SshData(), key: String? = null): SshData {
        var id = 0
        return applySshImportPlan(current, planSshProfileImport(current, decodeSshProfileImport(bytes), key) { "new-${id++}" })
    }
    private fun failWith(reason: SshProfileImportFailure, text: String) {
        assertEquals(reason, assertFailsWith<SshProfileImportException> { decodeSshProfileImport(text.encodeToByteArray()) }.reason)
    }
    private fun wire() = encodeSshProfileExport(source).decodeToString()

    @Test fun roundtripPortableSettingsAndSelectedKeyOnlyForKeyAuthentication() {
        val bytes = encodeSshProfileExport(source)
        println("SSH_V2_SYNTHETIC_EXPORT=" + bytes.decodeToString())
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals(setOf("format", "schema", "source", "profiles", "skipped"), root.keys)
        root.getValue("profiles").jsonArray.forEach { row ->
            assertEquals(setOf("alias", "host", "port", "username", "authentication", "startupCommand", "keepaliveSeconds"), row.jsonObject.keys)
        }
        for (key in listOf(null, "selected")) {
            assertEquals(profiles.mapIndexed { index, p ->
                p.copy(id = "new-$index", secretRef = null, keyRef = key.takeIf { p.authentication == SshAuthentication.KEY })
            }, restore(bytes, key = key).profiles)
        }
        assertEquals("secret-0", source.profiles.first().secretRef)
        assertTrue(restore(bytes).profiles[1].needsKeySelection(SshData()))
    }

    @Test fun legacySerializerFixturePreservesSettingsAndDiscardsIds() {
        assertEquals(profiles.mapIndexed { i, p -> p.copy(id = "new-$i", secretRef = null, keyRef = null) },
            restore(LEGACY_SSH_EXPORT.encodeToByteArray()).profiles)
        for (field in listOf("hostKeys", "keys", "rejectedHostKeys")) {
            failWith(SshProfileImportFailure.FORMAT, LEGACY_SSH_EXPORT.replace("\"$field\":[]", "\"$field\":[{}]"))
        }
        for (field in listOf("secretRef", "keyRef")) {
            for (value in listOf("\"local-ref\"", "0", "{}"))
                failWith(SshProfileImportFailure.FORMAT, LEGACY_SSH_EXPORT.replace("\"$field\":null", "\"$field\":$value"))
        }
    }

    @Test fun updatesReplaceAllPortableSettingsAndPreservePosition() {
        val changed = profiles[1].copy(host = "198.51.100.1", port = 2229, username = "other",
            authentication = SshAuthentication.INTERACTIVE, startupCommand = "show status", keepaliveSeconds = 0)
        val incoming = encodeSshProfileExport(SshData(profiles = listOf(changed, profiles[0].copy(id = "new", name = "new"))))
        val result = restore(incoming, source, "selected")
        assertEquals(listOf("old-0", "old-1", "old-2", "new-0"), result.profiles.map { it.id })
        assertEquals(changed.copy(secretRef = null, keyRef = null), result.profiles[1])
        assertEquals(source.keys, result.keys)
        assertEquals(source.hostKeys, result.hostKeys)
        // Settings-only updates do not detach a password; changing endpoint still does (v1 regressions too).
        val sameTarget = profiles[0].copy(startupCommand = "show status", keepaliveSeconds = 0)
        assertEquals(sameTarget, restore(encodeSshProfileExport(SshData(profiles = listOf(sameTarget))), source).profiles[0])
    }

    @Test fun everyFormatEnforcesSchemaFieldsLimitsAndDepth() {
        val v1 = """{"format":"netkit-ssh-profiles","schema":1,"profiles":[],"skipped":[]}"""
        for (text in listOf(v1, wire(), LEGACY_SSH_EXPORT)) {
            val root = Json.parseToJsonElement(text).jsonObject
            failWith(SshProfileImportFailure.SCHEMA, JsonObject(root + ("schema" to JsonPrimitive(99))).toString())
            failWith(SshProfileImportFailure.UNKNOWN_FIELD, JsonObject(root + ("extra" to JsonPrimitive(true))).toString())
            failWith(SshProfileImportFailure.FORMAT, text.dropLast(1) + ",\"extra\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}")
            failWith(SshProfileImportFailure.SIZE, text.padEnd(SSH_PROFILE_IMPORT_LIMIT + 1, ' '))
        }
        for (text in listOf(wire(), LEGACY_SSH_EXPORT)) {
            val root = Json.parseToJsonElement(text).jsonObject
            val row = root.getValue("profiles").jsonArray.first().jsonObject
            fun altered(field: String, value: JsonElement) = JsonObject(root +
                ("profiles" to JsonArray(listOf(JsonObject(row + (field to value)))))).toString()
            for (field in listOf("password", "privateKey", "extra"))
                failWith(SshProfileImportFailure.UNKNOWN_FIELD, altered(field, JsonPrimitive("forbidden")))
            for (value in listOf(JsonPrimitive("OTHER"), JsonPrimitive(2), JsonNull))
                failWith(SshProfileImportFailure.INVALID_PROFILE, altered("authentication", value))
            for (value in listOf("x".repeat(4097), "show\nstatus", "show\rstatus"))
                failWith(SshProfileImportFailure.INVALID_PROFILE, altered("startupCommand", JsonPrimitive(value)))
            for (value in listOf(-1, 301)) failWith(SshProfileImportFailure.INVALID_PROFILE, altered("keepaliveSeconds", JsonPrimitive(value)))
            failWith(SshProfileImportFailure.LIMIT, JsonObject(root + ("profiles" to JsonArray(List(201) { row }))).toString())
        }
    }

    @Test fun movesPreserveEverythingExceptOrderAndExportFollowsIt() {
        for ((id, delta) in listOf("old-0" to -1, "old-2" to 1, "absent" to 1, "old-1" to 0, "old-1" to 2, "old-1" to Int.MIN_VALUE))
            assertSame(source, moveSshProfile(source, id, delta))
    }

    @Test fun validMovesAndMissingKeySelection() {
        assertEquals(SshData(), moveSshProfile(SshData(), "x", 1))
        val moved = moveSshProfile(source, "old-1", -1)
        assertEquals(source.copy(profiles = listOf(profiles[1], profiles[0], profiles[2])), moved)
        assertEquals(source, moveSshProfile(moved, "old-1", 1))
        assertEquals(moved.profiles.map { it.name }, restore(encodeSshProfileExport(moved)).profiles.map { it.name })
        assertFalse(profiles[0].needsKeySelection(SshData()))
        assertFalse(profiles[1].needsKeySelection(source))
        assertTrue(profiles[1].copy(keyRef = null).needsKeySelection(source))
        assertTrue(profiles[1].copy(keyRef = "absent").needsKeySelection(source))
    }
    @Test fun exportRejectsDuplicateNamesAndUtf8SizeWithSpecificReasons() {
        val duplicate = source.copy(profiles = listOf(profiles[0], profiles[1].copy(name = profiles[0].name)))
        assertTrue(duplicate.valid()) // Saving duplicate names remains allowed.
        assertEquals(SshProfileExportError.DuplicateNames(listOf("fixture-0")),
            assertFailsWith<SshProfileExportException> { encodeSshProfileExport(duplicate) }.reason)
        val large = SshData(profiles = (1..70).map { profiles[0].copy(id = "p$it", name = "p$it", startupCommand = "ž".repeat(4096)) })
        assertTrue(large.valid())
        assertEquals(SshProfileExportError.TooLarge,
            assertFailsWith<SshProfileExportException> { encodeSshProfileExport(large) }.reason)
    }

    @Test fun authenticationTransitionsDetachOnlyReferencesUnusedByNewType() {
        for (old in SshAuthentication.entries) for (new in SshAuthentication.entries) {
            val before = profiles[0].copy(authentication = old)
            val data = SshData(profiles = listOf(before))
            val incoming = decodeSshProfileImport(encodeSshProfileExport(SshData(profiles = listOf(before.copy(authentication = new)))))
            val plan = planSshProfileImport(data, incoming, null) { error("Existing") }
            val after = applySshImportPlan(data, plan).profiles.single()
            val expectedSecretRef = before.secretRef.takeUnless { old != new && new != SshAuthentication.PASSWORD }
            val expectedKeyRef = before.keyRef.takeUnless { old != new && new != SshAuthentication.KEY }
            assertEquals(before.copy(authentication = new, secretRef = expectedSecretRef, keyRef = expectedKeyRef), after)
            assertEquals(if (expectedSecretRef == null) listOf(before.secretRef) else emptyList(), plan.detachedSecretRefs)
        }
        val keyProfile = profiles[1]
        val moved = restore(encodeSshProfileExport(SshData(profiles = listOf(keyProfile.copy(host = "198.51.100.8")))), source)
        assertEquals(keyProfile.keyRef, moved.profiles[1].keyRef) // Endpoint alone never detaches the key.
    }

}
