package cz.netkit.shared

import cz.netkit.shared.ios.*
import cz.netkit.shared.ssh.*
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class IosSshBridgeTest {
    private val domain = IosSshDomain()
    private fun profile(host: String = "192.0.2.1", secretRef: String? = null) = assertNotNull(domain.profile(
        "profile-a", "Test target", host, 22, "tester", SshAuthentication.PASSWORD, secretRef, null, "", 30))
    private fun key(blob: String) = TrustedHostKey("[192.0.2.1]:22", "ssh-ed25519", blob, "SHA256:$blob")

    @Test fun metadataRoundTripIsBoundedStrictAndVersioned() {
        val data = assertNotNull(domain.upsertProfile(domain.emptyData(), profile()).data)
        val bytes = assertNotNull(domain.encodeData(data))
        assertEquals(data, domain.decodeData(bytes).data)
        assertEquals(IosSshDataFailure.SIZE, domain.decodeData(ByteArray(1_048_577)).failure)
        assertEquals(IosSshDataFailure.UTF8, domain.decodeData(byteArrayOf(0xC3.toByte())).failure)
        assertEquals(IosSshDataFailure.FORMAT, domain.decodeData("[]".encodeToByteArray()).failure)
        assertEquals(IosSshDataFailure.SCHEMA, domain.decodeData("{\"schema\":2}".encodeToByteArray()).failure)
        assertEquals(IosSshDataFailure.SCHEMA, domain.decodeData("{\"schema\":\"1\"}".encodeToByteArray()).failure)
        assertEquals(IosSshDataFailure.FORMAT, domain.decodeData("{\"schema\":1,\"unexpected\":true}".encodeToByteArray()).failure)
        val deep = "{\"schema\":1,\"unexpected\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}"
        assertEquals(IosSshDataFailure.FORMAT, domain.decodeData(deep.encodeToByteArray()).failure)
    }

    @Test fun tofuCannotReplaceTrustedKeyAndResetRetainsRejectedEvidence() {
        val runtimeBlob = Random.nextBytes(32).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        val offered = key(runtimeBlob)
        val changed = key(runtimeBlob + "changed")
        assertEquals(HostKeyDecision.UNKNOWN, domain.hostDecision(domain.emptyData(), offered))
        val trusted = assertNotNull(domain.trustUnknown(domain.emptyData(), offered).data)
        assertEquals(HostKeyDecision.TRUSTED, domain.hostDecision(trusted, offered))
        assertEquals(HostKeyDecision.CHANGED, domain.hostDecision(trusted, changed))
        assertEquals(IosSshDataFailure.CONFLICT, domain.trustUnknown(trusted, changed).failure)
        val rejected = assertNotNull(domain.recordRejected(trusted, changed).data)
        val reset = assertNotNull(domain.forgetHost(rejected, offered.endpoint).data)
        assertTrue(reset.hostKeys.isEmpty())
        assertEquals(listOf(changed), reset.rejectedHostKeys)
        assertNull(domain.previousRejected(reset, changed))
        assertEquals(changed, domain.previousRejected(reset, offered))
    }

    @Test fun profileImportPreviewDetachesCredentialsAndRejectsStalePlan() {
        val before = assertNotNull(domain.upsertProfile(domain.emptyData(), profile(secretRef = "credential-a")).data)
        val document = """{"format":"netkit-ssh-profiles","schema":1,"profiles":[{"alias":"Test target","host":"192.0.2.2","port":22,"username":"tester"}],"skipped":[]}"""
        val incoming = assertNotNull(domain.decodeImport(document.encodeToByteArray()).imported)
        val plan = assertNotNull(domain.planImport(before, incoming, null) { "profile-b" }.plan)
        assertEquals(listOf("credential-a"), plan.detachedSecretRefs)
        val after = assertNotNull(domain.applyImport(before, plan).data)
        assertEquals("192.0.2.2", after.profiles.single().host)
        assertNull(after.profiles.single().secretRef)
        assertEquals(SshProfileImportFailure.STALE_PLAN, domain.applyImport(after, plan).failure)
        assertEquals(SshProfileImportFailure.SIZE, domain.decodeImport(ByteArray(SSH_PROFILE_IMPORT_LIMIT + 1)).failure)
        val runtimeValue = Random.nextBytes(16).joinToString("") { it.toUByte().toString(16) }
        val withCredential = document.replace("\"username\":\"tester\"", "\"username\":\"tester\",\"password\":\"$runtimeValue\"")
        assertEquals(SshProfileImportFailure.UNKNOWN_FIELD, domain.decodeImport(withCredential.encodeToByteArray()).failure)
    }

    @Test fun terminalPreservesStyledOutputAndSharedKeyEncoding() {
        val terminal = IosTerminal(80, 24)
        terminal.feed("\u001b[31mred\u001b[0m\u001b[?1h")
        assertTrue(terminal.snapshot().screen.first().text.startsWith("red"))
        assertEquals(1, terminal.snapshot().screen.first().runs.first().foreground)
        assertEquals("\u001bOA", terminal.keySequence(TerminalKey.UP, false, false, false))
        assertEquals("\u0003", terminal.textSequence("c", true, false))
        terminal.resize(999, 999)
        assertEquals(100, terminal.snapshot().screen.size)
        assertEquals(240, terminal.snapshot().screen.first().text.length)
    }

    @Test fun compositionIsBoundedAndPasteCannotInjectControls() {
        val terminal = IosTerminal(80, 24)
        terminal.composeSet("a😀")
        assertTrue(terminal.composeDelete(1, 0, true))
        terminal.composeFinish()
        assertEquals("a", terminal.drainInput())
        terminal.composeCommit("text")
        terminal.composeCommit("\n")
        assertEquals("text\r", terminal.drainInput())
        terminal.composeCommit("x".repeat(16_384))
        terminal.composeCommit("y")
        assertNull(terminal.drainInput())
        assertEquals("", terminal.drainInput())
        assertTrue(terminal.pasteNeedsConfirmation("a\n\u001bb"))
        assertEquals("a  b", terminal.safePaste("a\n\u001bb"))
    }

    @Test fun lifecycleUsesSharedMonotonicGraceAndPromptLimits() {
        var now = 1_000L
        val lifecycle = IosSshLifecycle { now }
        lifecycle.background()
        assertEquals(300_000L, lifecycle.remainingMillis())
        now += 299_999
        assertFalse(lifecycle.expired())
        now++
        assertTrue(lifecycle.foreground())
        assertNull(lifecycle.remainingMillis())
        assertEquals(180_000L, lifecycle.promptTimeoutMillis())
        assertEquals(600_000L, lifecycle.handshakeTimeoutMillis())
    }

    @Test fun sharedSecretStoreAndSwiftResultsUseSameBackendAndSurfaceFailures() = runTest {
        val backend = MemorySecrets()
        val adapter = IosSecretStoreAdapter(backend)
        val shared: SecretStore = adapter.sharedStore
        val material = Random.nextBytes(32)
        val secondMaterial = Random.nextBytes(32)
        shared.put("credential-a", material)
        assertContentEquals(material, adapter.getSecret("credential-a").bytes)
        assertContentEquals(material, shared.get("credential-a"))
        shared.delete("credential-a")
        assertNull(shared.get("credential-a"))
        backend.failure = SshFailure.SECRET_UNAVAILABLE
        assertEquals(SshFailure.SECRET_UNAVAILABLE, adapter.putSecret("credential-a", secondMaterial))
        assertEquals(SshFailure.SECRET_UNAVAILABLE, assertFailsWith<IosSecretStoreException> { shared.put("credential-a", secondMaterial) }.failure)
        assertFailsWith<IosSecretStoreException> { shared.get("credential-a") }
        assertFailsWith<IosSecretStoreException> { shared.delete("credential-a") }
        assertNull(adapter.getSecret("credential-a").bytes)
    }

    @Test fun profileEndpointChangeDetachesSavedPasswordAndKeyMetadataIsValidated() {
        val before = assertNotNull(domain.upsertProfile(domain.emptyData(), profile(secretRef = "credential-a")).data)
        val changed = assertNotNull(domain.upsertProfile(before, profile("192.0.2.2", "credential-a")).data)
        assertNull(changed.profiles.single().secretRef)
        val same = assertNotNull(domain.upsertProfile(before, profile(secretRef = "credential-a")).data)
        assertEquals("credential-a", same.profiles.single().secretRef)
        val publicValue = Random.nextBytes(32).joinToString("") { it.toUByte().toString(16) }
        val withKey = assertNotNull(domain.upsertKey(before, SshKeyInfo("key-a", "Test key", publicValue)).data)
        assertEquals(1, withKey.keys.size)
        assertEquals(IosSshDataFailure.INVALID_DATA, domain.upsertKey(withKey, SshKeyInfo("invalid/id", "Test", publicValue)).failure)
    }

    @Test fun deletionPreservesReferencedKeysAndRejectsStaleOrInvalidMetadata() {
        val publicValue = Random.nextBytes(32).joinToString("") { it.toUByte().toString(16) }
        val original = SshData(profiles = listOf(profile().copy(keyRef = "key-a")),
            keys = listOf(SshKeyInfo("key-a", "Test key", publicValue)))
        assertEquals(IosSshDataFailure.CONFLICT, domain.deleteKey(original, "key-a").failure)
        assertEquals(IosSshDataFailure.CONFLICT, domain.deleteProfile(original, "missing").failure)
        val withoutProfile = assertNotNull(domain.deleteProfile(original, "profile-a").data)
        assertTrue(withoutProfile.profiles.isEmpty())
        assertEquals(original.keys, withoutProfile.keys)
        val withoutKey = assertNotNull(domain.deleteKey(withoutProfile, "key-a").data)
        assertTrue(withoutKey.keys.isEmpty())
        assertEquals(IosSshDataFailure.CONFLICT, domain.deleteKey(withoutKey, "key-a").failure)
        assertEquals(IosSshDataFailure.INVALID_DATA, domain.deleteProfile(original.copy(schema = 2), "profile-a").failure)
        assertEquals(IosSshDataFailure.INVALID_DATA, domain.deleteKey(original.copy(schema = 2), "key-a").failure)
        assertEquals("key-a", original.profiles.single().keyRef)
    }

    @Test fun importEndpointPreviewIncludesUserAndIpv6AndPreservesKeyReference() {
        val originalProfile = profile(secretRef = "credential-a").copy(host = "2001:db8::1", keyRef = "key-a")
        val before = SshData(profiles = listOf(originalProfile))
        val incoming = SshProfileImport(listOf(SshImportProfile("Test target", "2001:db8::2", 2222, "operator")), emptyList())
        val plan = assertNotNull(domain.planImport(before, incoming, null) { "profile-b" }.plan)
        val change = domain.importChangedEndpoints(plan).single()
        assertEquals("profile-a", change.profileId)
        assertEquals("tester@[2001:db8::1]:22", change.before)
        assertEquals("operator@[2001:db8::2]:2222", change.after)
        assertEquals(listOf("credential-a"), domain.importDetachedRefs(plan))
        val applied = assertNotNull(domain.applyImport(before, plan).data)
        assertNull(applied.profiles.single().secretRef)
        assertEquals("key-a", applied.profiles.single().keyRef)
        val edited = assertNotNull(domain.upsertProfile(before, originalProfile.copy(host = "2001:db8::2")).data)
        assertEquals("key-a", edited.profiles.single().keyRef)
        val unchanged = assertNotNull(domain.planImport(applied, incoming, null) { "profile-b" }.plan)
        assertTrue(domain.importChangedEndpoints(unchanged).isEmpty())
        assertTrue(domain.importDetachedRefs(unchanged).isEmpty())
    }

    @Test fun v2ExportIsPortableOrderedAndUsesTypedFailures() {
        val first = profile(secretRef = "credential-a").copy(keyRef = "key-a", startupCommand = "show version")
        val second = first.copy(id = "profile-b", name = "Second", authentication = SshAuthentication.KEY)
        val data = SshData(profiles = listOf(first, second), keys = listOf(SshKeyInfo("key-a", "Key", "public")),
            hostKeys = listOf(key("runtime-public")))
        val exported = domain.prepareExport(data)
        assertNull(exported.failure)
        val bytes = assertNotNull(exported.bytes)
        val text = bytes.decodeToString()
        assertTrue(text.contains("\"schema\":2"))
        for (field in listOf("id", "secretRef", "keyRef", "keys", "hostKeys", "rejectedHostKeys"))
            assertFalse(text.contains("\"$field\""), field)
        assertEquals(listOf(first.name, second.name), assertNotNull(domain.decodeImport(bytes).imported).profiles.map { it.alias })
        val duplicates = domain.prepareExport(data.copy(profiles = listOf(first, second.copy(name = first.name))))
        assertEquals(IosSshExportFailure.DUPLICATE_NAMES, duplicates.failure)
        assertEquals(listOf(first.name), duplicates.duplicateNames)
        assertNull(duplicates.bytes)
        val large = SshData(profiles = (1..100).map { first.copy(id = "p-$it", name = "Target $it", startupCommand = "x".repeat(4096)) })
        assertEquals(IosSshExportFailure.TOO_LARGE, domain.prepareExport(large).failure)
        assertNull(domain.prepareExport(large).bytes)
        assertEquals(IosSshExportFailure.INVALID_DATA, domain.prepareExport(data.copy(schema = 2)).failure)
    }

    @Test fun profileMovementPreservesMetadataAndRoundTripsInExportOrder() {
        val first = profile(secretRef = "credential-a")
        val second = first.copy(id = "profile-b", name = "Second")
        val original = SshData(profiles = listOf(first, second), hostKeys = listOf(key("public")))
        assertEquals(original, domain.moveProfile(original, first.id, -1).data)
        assertEquals(original, domain.moveProfile(original, second.id, 1).data)
        assertEquals(original, domain.moveProfile(original, "missing", 1).data)
        assertEquals(original, domain.moveProfile(original, first.id, 2).data)
        val moved = assertNotNull(domain.moveProfile(original, second.id, -1).data)
        assertEquals(listOf(second, first), moved.profiles)
        assertEquals(original.hostKeys, moved.hostKeys)
        assertEquals(moved, domain.decodeData(assertNotNull(domain.encodeData(moved))).data)
        val imported = assertNotNull(domain.decodeImport(assertNotNull(domain.prepareExport(moved).bytes)).imported)
        assertEquals(listOf(second.name, first.name), imported.profiles.map { it.alias })
        assertEquals(original, domain.moveProfile(moved, second.id, 1).data)
    }

    @Test fun v2PreviewIncludesEveryChangedFieldAndAuthenticationDetachesPassword() {
        val current = SshData(profiles = listOf(profile(secretRef = "credential-a")))
        val incoming = SshProfileImport(listOf(SshImportProfile("Test target", "192.0.2.2", 2222, "operator",
            authentication = SshAuthentication.KEY, startupCommand = "show version", keepaliveSeconds = 60)), emptyList())
        val plan = assertNotNull(domain.planImport(current, incoming, null) { "unused" }.plan)
        val preview = domain.importProfilePreviews(plan, current).single()
        assertEquals(IosSshImportField.entries, preview.changes.map { it.field })
        assertEquals(listOf("192.0.2.1", "22", "tester", "PASSWORD", "", "30"), preview.changes.map { it.before })
        assertEquals(listOf("192.0.2.2", "2222", "operator", "KEY", "show version", "60"), preview.changes.map { it.after })
        assertTrue(preview.startupWarning)
        assertTrue(preview.needsKeySelection)
        val authOnly = incoming.copy(profiles = listOf(incoming.profiles.single().copy(host = "192.0.2.1", port = 22, username = "tester")))
        val authPlan = assertNotNull(domain.planImport(current, authOnly, null) { "unused" }.plan)
        assertEquals(listOf("credential-a"), domain.importDetachedRefs(authPlan))
        assertNull(assertNotNull(domain.applyImport(current, authPlan).data).profiles.single().secretRef)
        val keyed = SshData(profiles = listOf(profile().copy(authentication = SshAuthentication.KEY,
            keyRef = "key-a", startupCommand = "show version")), keys = listOf(SshKeyInfo("key-a", "Key", "public")))
        val passwordRow = SshImportProfile("Test target", "192.0.2.1", 22, "tester", authentication = SshAuthentication.PASSWORD)
        val passwordPlan = assertNotNull(domain.planImport(keyed, SshProfileImport(listOf(passwordRow), emptyList()), null) { "unused" }.plan)
        val passwordPreview = domain.importProfilePreviews(passwordPlan, keyed).single()
        assertNull(passwordPreview.after.keyRef)
        assertTrue(passwordPreview.startupWarning) // Clearing a command is still a command change.
        assertFalse(passwordPreview.needsKeySelection)
        assertTrue(domain.importDetachedRefs(passwordPlan).isEmpty())
        assertEquals(keyed.keys, assertNotNull(domain.applyImport(keyed, passwordPlan).data).keys)
    }

    @Test fun v1V2AndLegacyRespectSelectedKeyAndRejectBackupMaterial() {
        val row = """{"alias":"Target","host":"192.0.2.1","port":22,"username":"tester"}"""
        val v1 = """{"format":"netkit-ssh-profiles","schema":1,"profiles":[$row],"skipped":[]}"""
        val v2 = v1.replace("\"schema\":1", "\"schema\":2").replace("\"username\":\"tester\"",
            "\"username\":\"tester\",\"authentication\":\"KEY\",\"startupCommand\":\"\",\"keepaliveSeconds\":30")
        val legacy = """{"schema":1,"profiles":[{"id":"old-id","name":"Target","host":"192.0.2.1","port":22,"username":"tester","authentication":"KEY","secretRef":null,"keyRef":null,"startupCommand":"","keepaliveSeconds":30}],"keys":[],"hostKeys":[],"rejectedHostKeys":[]}"""
        val current = SshData(keys = listOf(SshKeyInfo("key-a", "Key", "public")))
        for (document in listOf(v1, v2, legacy)) {
            val incoming = assertNotNull(domain.decodeImport(document.encodeToByteArray()).imported)
            val withKey = assertNotNull(domain.planImport(current, incoming, "key-a") { "local-id" }.plan)
            val preview = domain.importProfilePreviews(withKey, current).single()
            assertNull(preview.before)
            assertEquals("local-id", preview.after.id)
            assertEquals(SshAuthentication.KEY, preview.after.authentication)
            assertFalse(preview.needsKeySelection)
            val withoutKey = assertNotNull(domain.planImport(current, incoming, null) { "local-id" }.plan)
            val after = domain.importProfilePreviews(withoutKey, current).single()
            assertEquals(if (document == v1) SshAuthentication.PASSWORD else SshAuthentication.KEY, after.after.authentication)
            assertEquals(document != v1, after.needsKeySelection)
        }
        for (field in listOf("keys", "hostKeys", "rejectedHostKeys")) {
            val backup = legacy.replace("\"$field\":[]", "\"$field\":[{}]")
            assertNull(domain.decodeImport(backup.encodeToByteArray()).imported)
        }
        assertNull(domain.decodeImport(legacy.replace("\"keyRef\":null", "\"keyRef\":\"key-a\"").encodeToByteArray()).imported)
        val passwordImport = assertNotNull(domain.decodeImport(v2.replace("\"KEY\"", "\"PASSWORD\"").encodeToByteArray()).imported)
        val passwordPlan = assertNotNull(domain.planImport(current, passwordImport, "key-a") { "local-id" }.plan)
        val passwordPreview = domain.importProfilePreviews(passwordPlan, current).single()
        assertEquals(SshAuthentication.PASSWORD, passwordPreview.after.authentication)
        assertNull(passwordPreview.after.keyRef)
    }

    @Test fun keySelectionDependsOnAuthenticationAndLiveKeyMetadata() {
        val keyProfile = profile().copy(authentication = SshAuthentication.KEY)
        val withOtherKey = SshData(keys = listOf(SshKeyInfo("key-b", "Other", "public")))
        assertTrue(domain.needsKeySelection(keyProfile, withOtherKey))
        assertTrue(domain.needsKeySelection(keyProfile.copy(keyRef = "deleted-key"), withOtherKey))
        assertFalse(domain.needsKeySelection(keyProfile.copy(keyRef = "key-b"), withOtherKey))
        assertFalse(domain.needsKeySelection(profile(), withOtherKey))
    }

    private class MemorySecrets : IosSecretBackend {
        private val items = mutableMapOf<String, ByteArray>()
        var failure: SshFailure? = null
        override fun store(id: String, bytes: ByteArray): SshFailure? {
            if (failure == null) items[id] = bytes.copyOf()
            return failure
        }
        override fun read(id: String): IosSecretReadResult = IosSecretReadResult(items[id]?.copyOf(), failure)
        override fun remove(id: String): SshFailure? {
            if (failure == null) items.remove(id)
            return failure
        }
    }
}
