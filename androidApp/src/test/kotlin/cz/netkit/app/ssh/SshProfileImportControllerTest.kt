package cz.netkit.app.ssh

import cz.netkit.app.wol.BoundedStreamIo
import cz.netkit.shared.ssh.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SshProfileImportControllerTest {
    private val bytes = """{"format":"netkit-ssh-profiles","schema":1,"source":"build-host",
        "profiles":[{"alias":"server","group":"Homelab","host":"192.168.0.10","port":22,"username":"root"}],
        "skipped":[{"alias":"jump","reason":"PROXY_JUMP"}]}""".encodeToByteArray()

    private class Fixture(scope: TestScope, initial: SshData = SshData()) {
        var data = initial
        var blocked = false
        var updates = 0
        var writes = 0
        var ids = 0
        var beforeUpdate: () -> Unit = {}
        var beforeCommit: () -> Unit = {}
        var beforeDelete: () -> Unit = {}
        val deleted = mutableListOf<String>()
        val mutex = Mutex()
        val controller = SshProfileImportController(scope, mutex, { data }, { transform ->
            updates++
            beforeUpdate()
            val next = transform(data)
            assertTrue(next.valid())
            beforeCommit()
            data = next
            writes++
            next
        }, { blocked }, { "import-${++ids}" }, { ref ->
            assertEquals(1, writes)
            assertTrue(data.profiles.none { it.secretRef == ref })
            deleted += ref
            beforeDelete()
        }, StandardTestDispatcher(scope.testScheduler))
    }

    private fun savedProfile() = SshProfile("saved", "server", "old.example", username = "root", secretRef = "secret-ref")

    @Test fun detachedSecretIsDeletedOnlyAfterCommit() = runTest {
        val fixture = Fixture(this, SshData(profiles = listOf(savedProfile())))
        fixture.controller.preview { bytes }; advanceUntilIdle()
        assertTrue(fixture.deleted.isEmpty())
        fixture.beforeCommit = { assertTrue(fixture.deleted.isEmpty()) }
        fixture.controller.confirm(null); advanceUntilIdle()
        assertEquals(1, fixture.writes)
        assertEquals(listOf("secret-ref"), fixture.deleted)
        assertNull(fixture.data.profiles.single().secretRef)
        assertEquals(SshAuthentication.PASSWORD, fixture.data.profiles.single().authentication)
        assertEquals(SshProfileImportState(), fixture.controller.state.value)
    }

    @Test fun stalePlanAndFailedCommitNeverDeleteSecrets() = runTest {
        for (stale in listOf(true, false)) {
            val original = SshData(profiles = listOf(savedProfile()))
            val fixture = Fixture(this, original)
            fixture.controller.preview { bytes }; advanceUntilIdle()
            val changed = original.copy(profiles = listOf(savedProfile().copy(port = 2222)))
            if (stale) fixture.beforeUpdate = { fixture.data = changed }
            else fixture.beforeCommit = { throw IOException("write failed") }
            fixture.controller.confirm(null); advanceUntilIdle()
            assertEquals(0, fixture.writes)
            assertTrue(fixture.deleted.isEmpty())
            assertEquals(if (stale) changed else original, fixture.data)
            assertEquals(if (stale) SshProfileImportError.Document(SshProfileImportFailure.STALE_PLAN)
                else SshProfileImportError.Operation.IO, fixture.controller.state.value.failure)
        }
    }

    @Test fun failedSecretCleanupDoesNotFailCommittedImport() = runTest {
        val fixture = Fixture(this, SshData(profiles = listOf(savedProfile())))
        fixture.beforeDelete = { throw IOException("delete failed") }
        fixture.controller.preview { bytes }; advanceUntilIdle()
        fixture.controller.confirm(null); advanceUntilIdle()
        assertEquals(1, fixture.writes)
        assertEquals(listOf("secret-ref"), fixture.deleted)
        assertNull(fixture.data.profiles.single().secretRef)
        assertEquals(SshProfileImportState(), fixture.controller.state.value)
    }

    @Test fun unchangedTargetDoesNotDeleteSavedSecret() = runTest {
        val original = savedProfile().copy(host = "192.168.0.10")
        val fixture = Fixture(this, SshData(profiles = listOf(original)))
        fixture.controller.preview { bytes }; advanceUntilIdle()
        fixture.controller.confirm(null); advanceUntilIdle()
        assertEquals(original, fixture.data.profiles.single())
        assertTrue(fixture.deleted.isEmpty())
        assertEquals(SshProfileImportState(), fixture.controller.state.value)
    }

    @Test fun previewDoesNotWriteAndConfirmUsesOneTransactionAndSelectedKey() = runTest {
        val fixture = Fixture(this, SshData(keys = listOf(SshKeyInfo("phone-key", "Phone", "public"))))
        fixture.controller.preview { bytes }
        advanceUntilIdle()
        val pending = assertNotNull(fixture.controller.state.value.pendingProfileImport)
        assertEquals("Homelab", pending.plan.newProfiles.single().incoming.group)
        assertEquals(1, pending.plan.skipped.size)
        assertEquals(0, fixture.updates)
        fixture.controller.confirm("phone-key")
        fixture.controller.confirm(null) // Repeated taps cannot enqueue a second transaction.
        advanceUntilIdle()
        assertEquals(1, fixture.updates)
        assertEquals(1, fixture.writes)
        val imported = fixture.data.profiles.single()
        assertEquals(pending.plan.newProfiles.single().after.id, imported.id)
        assertEquals(SshAuthentication.KEY, imported.authentication)
        assertEquals("phone-key", imported.keyRef)
        assertNull(imported.secretRef)
        assertEquals(SshProfileImportState(), fixture.controller.state.value)
    }

    @Test fun confirmWithoutKeyAndCancellationBeforeConfirmation() = runTest {
        val fixture = Fixture(this)
        fixture.controller.preview { bytes }; advanceUntilIdle()
        fixture.controller.cancel()
        fixture.controller.confirm(null); advanceUntilIdle()
        assertEquals(0, fixture.updates)
        assertFalse(fixture.controller.active)
        fixture.controller.preview { bytes }; advanceUntilIdle()
        fixture.controller.confirm(null); advanceUntilIdle()
        assertEquals(SshAuthentication.PASSWORD, fixture.data.profiles.single().authentication)
        assertNull(fixture.data.profiles.single().secretRef)
    }

    @Test fun cancellationWhileReadingNeverPublishesOldPreview() = runTest {
        val fixture = Fixture(this)
        val waiting = CompletableDeferred<ByteArray>()
        fixture.controller.preview { waiting.await() }
        runCurrent()
        fixture.controller.cancel()
        waiting.complete(bytes)
        advanceUntilIdle()
        assertEquals(SshProfileImportState(), fixture.controller.state.value)
        assertEquals(0, fixture.writes)
        fixture.controller.preview { bytes }; advanceUntilIdle()
        assertNotNull(fixture.controller.state.value.pendingProfileImport)
    }

    @Test fun immediateCancellationAndQueuedCancellationReleaseBusy() = runTest {
        val fixture = Fixture(this)
        fixture.controller.preview { bytes }
        fixture.controller.cancel()
        advanceUntilIdle()
        assertFalse(fixture.controller.active)
        fixture.mutex.lock()
        fixture.controller.preview { bytes }
        fixture.controller.cancel()
        fixture.mutex.unlock()
        advanceUntilIdle()
        assertEquals(SshProfileImportState(), fixture.controller.state.value)
        assertEquals(0, fixture.updates)
    }

    @Test fun pendingPreviewCannotBeReplacedAndConfirmedTransactionCannotBeCancelled() = runTest {
        val fixture = Fixture(this)
        fixture.controller.preview { bytes }; advanceUntilIdle()
        val pending = fixture.controller.state.value.pendingProfileImport
        fixture.controller.preview { error("Must not replace an open preview") }
        assertSame(pending, fixture.controller.state.value.pendingProfileImport)
        fixture.controller.confirm(null)
        assertTrue(fixture.controller.state.value.busy)
        fixture.controller.cancel()
        assertTrue(fixture.controller.state.value.busy)
        advanceUntilIdle()
        assertEquals(1, fixture.writes)
        assertEquals(SshProfileImportState(), fixture.controller.state.value)
    }

    @Test fun errorsAreTypedAndNeverContainProviderText() = runTest {
        val fixture = Fixture(this)
        fixture.controller.preview { throw IOException("sensitive provider path and data") }
        advanceUntilIdle()
        assertEquals(SshProfileImportError.Operation.IO, fixture.controller.state.value.failure)
        fixture.controller.preview { "{}".encodeToByteArray() }; advanceUntilIdle()
        assertEquals(SshProfileImportError.Document(SshProfileImportFailure.FORMAT), fixture.controller.state.value.failure)
        fixture.controller.preview { ByteArray(SSH_PROFILE_IMPORT_LIMIT + 1) }; advanceUntilIdle()
        assertEquals(SshProfileImportError.Document(SshProfileImportFailure.SIZE), fixture.controller.state.value.failure)
        fixture.controller.preview { withTimeout(1) { delay(10); bytes } }; advanceUntilIdle()
        assertEquals(SshProfileImportError.Operation.TIMEOUT, fixture.controller.state.value.failure)
        assertEquals(0, fixture.updates)
    }

    @Test fun boundedReaderMapsOverflowAndClosesStream() = runBlocking {
        val closed = CompletableDeferred<Unit>()
        val stream = object : java.io.ByteArrayInputStream(ByteArray(SSH_PROFILE_IMPORT_LIMIT + 1)) {
            override fun close() { super.close(); closed.complete(Unit) }
        }
        val controller = SshProfileImportController(this, Mutex(), { SshData() },
            { error("Must not write") }, { false }, { "id" }, { error("Must not delete") })
        controller.preview {
            BoundedStreamIo.read({ stream }, {}, SSH_PROFILE_IMPORT_LIMIT)
        }
        withTimeout(5000) {
            controller.state.first { !it.busy }
            closed.await()
        }
        assertEquals(SshProfileImportError.Document(SshProfileImportFailure.SIZE), controller.state.value.failure)
    }

    @Test fun blockingIsCheckedAtStartAfterReadAndInsideTransaction() = runTest {
        val fixture = Fixture(this)
        fixture.blocked = true
        fixture.controller.preview { error("Must not read") }
        assertEquals(SshProfileImportError.Operation.BLOCKED, fixture.controller.state.value.failure)
        fixture.blocked = false
        fixture.controller.preview { fixture.blocked = true; bytes }; advanceUntilIdle()
        assertNull(fixture.controller.state.value.pendingProfileImport)
        assertEquals(SshProfileImportError.Operation.BLOCKED, fixture.controller.state.value.failure)
        fixture.blocked = false
        fixture.controller.preview { bytes }; advanceUntilIdle()
        fixture.beforeUpdate = { fixture.blocked = true }
        fixture.controller.confirm(null); advanceUntilIdle()
        assertEquals(0, fixture.writes)
        assertEquals(SshProfileImportError.Operation.BLOCKED, fixture.controller.state.value.failure)
    }

    @Test fun stalePreviewAndDeletedKeyDoNotWrite() = runTest {
        val fixture = Fixture(this)
        fixture.controller.preview { bytes }; advanceUntilIdle()
        val changed = SshData(profiles = listOf(SshProfile("racing", "server", "new.example", username = "changed")))
        fixture.beforeUpdate = { fixture.data = changed }
        fixture.controller.confirm(null); advanceUntilIdle()
        assertEquals(changed, fixture.data)
        assertEquals(0, fixture.writes)
        assertEquals(SshProfileImportError.Document(SshProfileImportFailure.STALE_PLAN), fixture.controller.state.value.failure)
        fixture.beforeUpdate = {}
        fixture.controller.preview { bytes }; advanceUntilIdle()
        fixture.controller.confirm("deleted-key"); advanceUntilIdle()
        assertEquals(0, fixture.writes)
        assertEquals(SshProfileImportError.Operation.KEY_UNAVAILABLE, fixture.controller.state.value.failure)
    }

    @Test fun overCapacityIsAtomicAndUnrelatedMetadataIsPreserved() = runTest {
        val full = SshData(profiles = (1..200).map { SshProfile("id-$it", "host-$it", "host", username = "root") })
        val fixture = Fixture(this, full)
        fixture.controller.preview { bytes }; advanceUntilIdle()
        fixture.controller.confirm(null); advanceUntilIdle()
        assertEquals(0, fixture.writes)
        assertEquals(full, fixture.data)
        assertEquals(SshProfileImportError.Document(SshProfileImportFailure.LIMIT), fixture.controller.state.value.failure)
        fixture.data = SshData()
        fixture.controller.preview { bytes }; advanceUntilIdle()
        val key = SshKeyInfo("new-key", "Phone", "public")
        fixture.beforeUpdate = { fixture.data = fixture.data.copy(keys = listOf(key)) }
        fixture.controller.confirm("new-key"); advanceUntilIdle()
        assertEquals(listOf(key), fixture.data.keys)
        assertEquals(1, fixture.writes)
    }

    @Test fun viewModelPolicyBlocksDialogsAndConnectingOrActiveSessions() {
        val ready = SshUiState(loaded = true)
        assertFalse(ready.blocksProfileImport())
        for (state in listOf(ready.copy(loaded = false), ready.copy(storeFailed = true), ready.copy(busy = true),
            ready.copy(profileImportBlockedByDialog = true),
            ready.copy(keySelectionRequired = SshProfile("fixture", "Fixture", "192.0.2.10", username = "tester", authentication = SshAuthentication.KEY)),
            ready.copy(authChallenge = AuthChallenge("request", "session", "identity", "", "", emptyList(), emptyList())),
            ready.copy(hostChallenge = HostChallenge("request", "session", TrustedHostKey("endpoint", "algorithm", "public", "SHA256:x"), null)))) {
            assertTrue(state.blocksProfileImport())
        }
        val session = SshSessionState("session", "Host", TerminalBuffer().snapshot())
        assertTrue(ready.copy(sessions = listOf(session)).blocksProfileImport())
        assertTrue(ready.copy(sessions = listOf(session.copy(connected = true))).blocksProfileImport())
        assertFalse(ready.copy(sessions = listOf(session.copy(ended = true))).blocksProfileImport())
    }

    @Test fun realStoreRoundTripKeepsSchemaAndRejectedTransactionLeavesFileUnchanged() = runTest {
        val directory = Files.createTempDirectory("netkit-profile-import").toFile()
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = File(directory, "ssh.json")
            val store = SshStore(file, storeScope)
            val incoming = decodeSshProfileImport(bytes)
            val original = SshProfile("saved", "server", "old.example", username = "old", secretRef = "secret-ref", startupCommand = "show version")
            store.update { it.copy(profiles = listOf(original)) }
            val plan = planSshProfileImport(store.read(), incoming, null) { "new-id" }
            val imported = store.update { applySshImportPlan(it, plan) }
            assertEquals(original.copy(host = "192.168.0.10", username = "root", secretRef = null), imported.profiles.single())
            assertEquals(imported, store.read())
            val output = ByteArrayOutputStream()
            SshDataSerializer.writeTo(imported, output)
            val root = SshDataSerializer.json.parseToJsonElement(output.toString(Charsets.UTF_8.name())).jsonObject
            assertEquals(setOf("schema", "profiles", "keys", "hostKeys", "rejectedHostKeys"), root.keys)
            assertFalse(root.getValue("profiles").jsonArray.single().jsonObject.containsKey("group"))
            val full = SshData(profiles = (1..200).map { SshProfile("id-$it", "host-$it", "host", username = "root") })
            store.update { full }
            val before = file.readBytes()
            val over = planSshProfileImport(full, incoming, null) { "new-id" }
            assertEquals(SshProfileImportFailure.LIMIT,
                assertFailsWith<SshProfileImportException> { store.update { applySshImportPlan(it, over) } }.reason)
            assertEquals(full, store.read())
            assertContentEquals(before, file.readBytes())
        } finally { storeScope.coroutineContext[Job]!!.cancelAndJoin(); directory.deleteRecursively() }
    }
    @Test fun authenticationChangeDeletesDetachedPasswordAfterCommit() = runTest {
        for (auth in listOf(SshAuthentication.KEY, SshAuthentication.INTERACTIVE)) {
            val original = SshProfile("saved", "Fixture", "192.0.2.10", username = "tester", secretRef = "saved-secret")
            val fixture = Fixture(this, SshData(profiles = listOf(original)))
            val input = encodeSshProfileExport(SshData(profiles = listOf(original.copy(authentication = auth))))
            fixture.controller.preview { input }; advanceUntilIdle()
            assertEquals(listOf("saved-secret"), fixture.controller.state.value.pendingProfileImport!!.plan.detachedSecretRefs)
            assertTrue(fixture.deleted.isEmpty())
            fixture.beforeDelete = { assertNull(fixture.data.profiles.single().secretRef) }
            fixture.controller.confirm(null); advanceUntilIdle()
            assertEquals(listOf("saved-secret"), fixture.deleted)
            assertEquals(auth, fixture.data.profiles.single().authentication)
            assertEquals(1, fixture.writes)
        }
    }

}
