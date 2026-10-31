package cz.netkit.shared.wol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WolControllerTest {
    private val handle = object : WifiNetworkHandle {}
    private fun device(mode: TargetMode = TargetMode.CUSTOM) = WolDevice("one", "NAS", "AA:BB:CC:DD:EE:FF", mode, "10.0.2.2", 9009, "nas.home", 80, "note", 1, 2)
    private class Store(var data: List<WolDevice>) : WolDeviceStore {
        var failLoad = false
        var failSave = false
        var saves = 0
        override suspend fun load(): List<WolDevice> { check(!failLoad); return data }
        override suspend fun save(devices: List<WolDevice>) { check(!failSave); saves++; data = devices }
    }
    private class Sender : PacketSender {
        var calls = 0
        var network: WifiNetworkHandle? = null
        var host: String? = null
        var port: Int? = null
        var bytes: ByteArray? = null
        var block: (suspend () -> Unit)? = null
        override suspend fun sendUdp(network: WifiNetworkHandle, host: String, port: Int, payload: ByteArray) {
            calls++; this.network = network; this.host = host; this.port = port; bytes = payload
            block?.invoke()
        }
    }
    private fun provider(info: WifiNetworkInfo?) = object : NetworkInfoProvider { override suspend fun currentWifi() = info }
    @Test fun crudPreservesOptionalFieldsAndCreationTime() = runTest {
        val store = Store(emptyList())
        val c = WolController(store, provider(null), Sender(), this, { 100 }, { "new" })
        advanceUntilIdle()
        c.add(); c.updateDraft(WolDeviceDraft(name = " NAS ", mac = "aabb.ccdd.eeff", targetMode = TargetMode.CUSTOM, customTarget = "10.0.2.2", port = "9009", probeHost = "nas.home", probePort = "80", note = "note")); c.save()
        advanceUntilIdle()
        assertEquals("AA:BB:CC:DD:EE:FF", store.data.single().mac)
        assertEquals(100L, store.data.single().createdAt)
        c.edit("new"); c.updateDraft(assertNotNull(c.state.value.draft).copy(name = "Other")); c.save(); advanceUntilIdle()
        assertEquals("Other", store.data.single().name)
        assertEquals("nas.home", store.data.single().probeHost)
        assertEquals(80, store.data.single().probePort)
        assertEquals("note", store.data.single().note)
        assertEquals(100L, store.data.single().createdAt)
        c.delete("new"); advanceUntilIdle(); assertTrue(store.data.isEmpty())
    }
    @Test fun failedLoadCannotOverwriteDataAndRetryWorks() = runTest {
        val store = Store(listOf(device())).also { it.failLoad = true }
        val c = WolController(store, provider(null), Sender(), this, { 1 }, { "new" })
        advanceUntilIdle(); assertTrue(c.state.value.loadFailed)
        c.add(); c.delete("one"); c.save(); advanceUntilIdle()
        assertEquals(0, store.saves); assertEquals(1, store.data.size); assertNull(c.state.value.draft)
        store.failLoad = false; c.retryLoad(); advanceUntilIdle()
        assertEquals(store.data, c.state.value.devices); assertFalse(c.state.value.loadFailed)
    }
    @Test fun failedSaveAndDeleteKeepCommittedSnapshotAndDraft() = runTest {
        val store = Store(listOf(device())).also { it.failSave = true }
        val c = WolController(store, provider(null), Sender(), this, { 3 }, { "new" })
        advanceUntilIdle(); c.edit("one"); c.updateDraft(assertNotNull(c.state.value.draft).copy(name = "changed")); c.save(); advanceUntilIdle()
        assertEquals("NAS", c.state.value.devices.single().name)
        assertEquals("changed", c.state.value.draft?.name); assertEquals(WolMessage.SAVE_FAILED, c.state.value.message)
        c.delete("one"); advanceUntilIdle(); assertEquals(1, c.state.value.devices.size)
    }
    @Test fun invalidStoredSnapshotIsNotSilentlySalvaged() = runTest {
        val store = Store(listOf(device(), device()))
        val c = WolController(store, provider(null), Sender(), this, { 1 }, { "new" })
        advanceUntilIdle(); assertTrue(c.state.value.loadFailed); assertEquals(0, store.saves)
    }
    @Test fun noWifiNeverCallsSender() = runTest {
        val sender = Sender()
        val c = WolController(Store(listOf(device())), provider(null), sender, this, { 1 }, { "new" })
        advanceUntilIdle(); c.wake("one"); advanceUntilIdle()
        assertEquals(0, sender.calls); assertEquals(WolMessage.NO_WIFI, c.state.value.message)
    }
    @Test fun sendsExactTargetAndOriginalWifiHandle() = runTest {
        val sender = Sender()
        var info = WifiNetworkInfo(handle, "192.168.1.10", 24)
        val networkProvider = object : NetworkInfoProvider { override suspend fun currentWifi(): WifiNetworkInfo { val result = info; info = WifiNetworkInfo(object : WifiNetworkHandle {}, null, null); return result } }
        val c = WolController(Store(listOf(device())), networkProvider, sender, this, { 1 }, { "new" })
        advanceUntilIdle(); c.wake("one"); advanceUntilIdle()
        assertSame(handle, sender.network); assertEquals("10.0.2.2", sender.host); assertEquals(9009, sender.port)
        assertEquals(102, sender.bytes?.size); assertEquals(WolMessage.SENT, c.state.value.message)
    }
    @Test fun autoAndLimitedBroadcastHaveExplicitBehaviour() = runTest {
        for ((mode, prefix, expected) in listOf(Triple(TargetMode.AUTO_BROADCAST, 24, "192.168.1.255"), Triple(TargetMode.AUTO_BROADCAST, 31, null), Triple(TargetMode.AUTO_BROADCAST, 32, null), Triple(TargetMode.LIMITED_BROADCAST, 32, "255.255.255.255"))) {
            val sender = Sender()
            val c = WolController(Store(listOf(device(mode))), provider(WifiNetworkInfo(handle, "192.168.1.10", prefix)), sender, this, { 1 }, { "new" })
            advanceUntilIdle(); c.wake("one"); advanceUntilIdle()
            assertEquals(expected, sender.host)
            assertEquals(if (expected == null) WolMessage.NO_BROADCAST else WolMessage.SENT, c.state.value.message)
        }
    }
    @Test fun rapidWakeIsSingleFlightAndCancellationClearsBusy() = runTest {
        val sender = Sender().also { it.block = { awaitCancellation() } }
        val c = WolController(Store(listOf(device())), provider(WifiNetworkInfo(handle, null, null)), sender, this, { 1 }, { "new" })
        advanceUntilIdle(); c.wake("one"); c.wake("one"); runCurrent()
        assertEquals(1, sender.calls); c.cancelWake(); runCurrent()
        assertNull(c.state.value.sendingDeviceId); assertNull(c.state.value.message)
    }
    @Test fun cancellationBeforeLaunchDispatchClearsBusy() = runTest {
        val sender = Sender()
        val c = WolController(Store(listOf(device())), provider(WifiNetworkInfo(handle, null, null)), sender, this, { 1 }, { "new" })
        advanceUntilIdle(); c.wake("one"); c.cancelWake(); runCurrent()
        assertEquals(0, sender.calls); assertNull(c.state.value.sendingDeviceId)
    }
    @Test fun timeoutAndSenderFailureClearBusyAndReportFailure() = runTest {
        for (timeout in listOf(true, false)) {
            val sender = Sender().also { it.block = { if (timeout) awaitCancellation() else error("send failed") } }
            val c = WolController(Store(listOf(device())), provider(WifiNetworkInfo(handle, null, null)), sender, this, { 1 }, { "new" }, 100)
            advanceUntilIdle(); c.wake("one"); advanceUntilIdle()
            assertNull(c.state.value.sendingDeviceId); assertEquals(WolMessage.SEND_FAILED, c.state.value.message)
        }
    }
    @Test fun concurrentCrudIsNotAllowedToOverwritePendingSave() = runTest {
        val gate = CompletableDeferred<Unit>()
        val data = listOf(device())
        var saved = data
        val store = object : WolDeviceStore {
            override suspend fun load() = data
            override suspend fun save(devices: List<WolDevice>) { gate.await(); saved = devices }
        }
        val c = WolController(store, provider(null), Sender(), this, { 1 }, { "new" })
        advanceUntilIdle(); c.delete("one"); c.add(); c.delete("one"); runCurrent()
        assertTrue(c.state.value.saving); gate.complete(Unit); advanceUntilIdle()
        assertTrue(saved.isEmpty()); assertFalse(c.state.value.saving)
    }
    @Test fun typedSendOutcomesRemainOnEachDeviceWithTimestamp() = runTest {
        val sender = Sender()
        val devices = listOf(device(), device().copy(id = "two"))
        val c = WolController(Store(devices), provider(WifiNetworkInfo(handle, null, null)), sender, this, { 42 }, { "new" })
        advanceUntilIdle(); c.wake("one"); advanceUntilIdle()
        for (reason in listOf(SendFailure.DNS, SendFailure.TIMEOUT, SendFailure.NETWORK_LOST, SendFailure.IO)) {
            sender.block = { throw PacketSendException(reason) }
            c.wake("two"); advanceUntilIdle()
            assertEquals(WolSendOutcome(false, reason, 42), c.state.value.outcomes["two"])
            assertEquals(WolSendOutcome(true, null, 42), c.state.value.outcomes["one"])
        }
        sender.block = { awaitCancellation() }; c.wake("two"); runCurrent(); c.cancelWake(); runCurrent()
        assertEquals(SendFailure.CANCELLED, c.state.value.outcomes["two"]?.reason)
        assertEquals(WolSendOutcome(true, null, 42), c.state.value.outcomes["one"])
    }
    @Test fun recoveryRequiresConfirmationAndIoOnlyAllowsRetry() = runTest {
        for (kind in StoreFailure.entries) {
            var recovered = 0
            val store = object : WolDeviceStore {
                override suspend fun load(): List<WolDevice> = throw StoreReadException(kind)
                override suspend fun save(devices: List<WolDevice>) = error("must not save")
                override suspend fun recover(): StoreRecovery { recovered++; return StoreRecovery("archive", emptyList()) }
            }
            val c = WolController(store, provider(null), Sender(), this, { 1 }, { "new" })
            advanceUntilIdle(); assertEquals(kind, c.state.value.loadFailure)
            c.confirmRecovery(); advanceUntilIdle(); assertEquals(0, recovered)
            c.requestRecovery()
            if (kind == StoreFailure.IO) { assertFalse(c.state.value.recoveryConfirmationPending); continue }
            assertTrue(c.state.value.recoveryConfirmationPending)
            c.cancelRecovery(); c.confirmRecovery(); advanceUntilIdle(); assertEquals(0, recovered)
            c.requestRecovery(); c.confirmRecovery(); advanceUntilIdle()
            assertEquals(1, recovered); assertFalse(c.state.value.loadFailed); assertEquals("archive", c.state.value.archivePath)
        }
    }

    @Test fun recoveryIoFailureReplacesCorruptReasonAndRequiresReloadBeforeRecovery() = runTest {
        var recoveries = 0
        val store = object : WolDeviceStore {
            override suspend fun load(): List<WolDevice> = throw StoreReadException(StoreFailure.CORRUPT)
            override suspend fun save(devices: List<WolDevice>) = error("must not save")
            override suspend fun recover(): StoreRecovery {
                recoveries++
                throw StoreReadException(StoreFailure.IO)
            }
        }
        val c = WolController(store, provider(null), Sender(), this, { 1 }, { "new" })
        advanceUntilIdle()
        c.requestRecovery(); c.confirmRecovery(); advanceUntilIdle()
        assertTrue(c.state.value.loadFailed)
        assertEquals(StoreFailure.IO, c.state.value.loadFailure)
        assertFalse(c.state.value.recovering)
        c.requestRecovery(); c.confirmRecovery(); advanceUntilIdle()
        assertEquals(1, recoveries)
        assertFalse(c.state.value.recoveryConfirmationPending)
        c.retryLoad(); advanceUntilIdle()
        assertEquals(StoreFailure.CORRUPT, c.state.value.loadFailure)
    }

}
