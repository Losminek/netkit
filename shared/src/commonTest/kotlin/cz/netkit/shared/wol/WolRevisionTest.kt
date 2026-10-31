package cz.netkit.shared.wol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WolRevisionTest {
    private val device = WolDevice("one", "NAS", "AA:BB:CC:DD:EE:FF", TargetMode.CUSTOM,
        customTarget="127.0.0.1", probeHost="127.0.0.1", createdAt=1, updatedAt=2)
    @Test fun schemaOneMigrationPreservesOriginalDataAndAddsDefaults() {
        val old = """{"schema":1,"devices":[{"id":"one","name":"NAS","mac":"AA:BB:CC:DD:EE:FF","targetMode":"CUSTOM","customTarget":"127.0.0.1","port":9,"probeHost":"127.0.0.1","probePort":null,"note":null,"createdAt":1,"updatedAt":2}]}"""
        assertEquals(device, decodeWolImport(old.encodeToByteArray()).single())
        assertEquals(3, decodeWolImport(old.encodeToByteArray()).single().repeatCount)
        assertTrue(encodeWolExport(listOf(device)).decodeToString().contains("\"schema\":2"))
    }
    @Test fun importIsAtomicStrictAndBounded() {
        val bytes=encodeWolExport(listOf(device))
        assertEquals(listOf(device),decodeWolImport(bytes))
        assertFailsWith<IllegalArgumentException> { decodeWolImport(ByteArray(WOL_TRANSFER_LIMIT+1)) }
        assertFailsWith<IllegalArgumentException> { encodeWolExport(listOf(device,device)) }
        assertFailsWith<IllegalArgumentException> { decodeWolImport(bytes.decodeToString().replace("\"repeatCount\":3","\"repeatCount\":0").encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { decodeWolImport(bytes.decodeToString().replace("\"schema\":2","\"schema\":3").encodeToByteArray()) }
    }
    @Test fun mergeOverwritesByIdentityAndPreservesOrder() {
        val other=device.copy(id="two",name="PC")
        assertEquals(listOf(device.copy(name="new"),other),mergeWolDevices(listOf(device),listOf(device.copy(name="new"),other)))
    }
    @Test fun repeatsAndProbeUseSameNetworkAndCancellationStopsWaiting() = runTest {
        val handle=object:WifiNetworkHandle {}
        val sends=mutableListOf<Long>()
        var probes=0
        val store=object:WolDeviceStore {
            override suspend fun load()=listOf(device.copy(probeEnabled=true))
            override suspend fun save(devices:List<WolDevice>) {}
        }
        val sender=object:PacketSender { override suspend fun sendUdp(network:WifiNetworkHandle,host:String,port:Int,payload:ByteArray) {
            assertSame(handle,network);sends+=testScheduler.currentTime
        } }
        val provider=object:NetworkInfoProvider { override suspend fun currentWifi()=WifiNetworkInfo(handle,null,null) }
        val probe=object:WakeProber { override suspend fun probe(network:WifiNetworkHandle,host:String,port:Int?):WakeProbeStatus {
            assertSame(handle,network);probes++
            return when (probes) {
                1, 3 -> WakeProbeStatus.NO_RESPONSE // one pre-check per wake
                2 -> kotlinx.coroutines.awaitCancellation() // first wake is cancelled while probing
                4 -> {
                    kotlinx.coroutines.delay(1_000) // response inside the 5 s per-attempt deadline
                    WakeProbeStatus.RESPONDS
                }
                else -> error("Unexpected extra probe: $probes")
            }
        } }
        val controller=WolController(store,provider,sender,this,{testScheduler.currentTime},{"id"},prober=probe,monotonicMillis={testScheduler.currentTime})
        runCurrent();controller.wake("one");advanceTimeBy(501);runCurrent()
        assertEquals(listOf(0L,250L,500L),sends)
        assertEquals(WakeProbeStatus.WAITING,controller.state.value.probes["one"])
        controller.cancelWake();runCurrent();assertEquals(2,probes)
        assertEquals(WakeProbeStatus.CANCELLED,controller.state.value.probes["one"])
        assertTrue(controller.state.value.outcomes["one"]!!.sent)
        controller.wake("one");advanceUntilIdle()
        assertEquals(4,probes);assertEquals(WakeProbeStatus.RESPONDS,controller.state.value.probes["one"])
        assertEquals(listOf(0L, 250L, 500L, 501L, 751L, 1001L), sends)
        assertEquals(2001L, testScheduler.currentTime)
    }
    @Test fun wakeBlocksImportAndChangedRecordLosesOldStatus() = runTest {
        val handle=object:WifiNetworkHandle {}
        var saved=listOf(device.copy(probeEnabled=true))
        val store=object:WolDeviceStore { override suspend fun load()=saved; override suspend fun save(devices:List<WolDevice>){saved=devices} }
        val provider=object:NetworkInfoProvider { override suspend fun currentWifi()=WifiNetworkInfo(handle,null,null) }
        val sender=object:PacketSender { override suspend fun sendUdp(network:WifiNetworkHandle,host:String,port:Int,payload:ByteArray){} }
        var probeCalls=0
        val prober=object:WakeProber { override suspend fun probe(network:WifiNetworkHandle,host:String,port:Int?) =
            if (++probeCalls == 1) WakeProbeStatus.NO_RESPONSE else WakeProbeStatus.RESPONDS }
        val controller=WolController(store,provider,sender,this,{testScheduler.currentTime},{"id"},prober=prober,monotonicMillis={testScheduler.currentTime})
        runCurrent(); controller.wake("one");runCurrent() // still sending repeated packets: import stays blocked
        val changed=device.copy(name="Other",probeHost="127.0.0.2")
        controller.previewImport(listOf(changed));controller.confirmImport();controller.delete("one");runCurrent()
        assertEquals("NAS",controller.state.value.devices.single().name)
        assertNull(controller.state.value.pendingImport)
        advanceUntilIdle();assertEquals(WakeProbeStatus.RESPONDS,controller.state.value.probes["one"])
        controller.previewImport(listOf(changed));controller.confirmImport();advanceUntilIdle()
        assertEquals(changed,controller.state.value.devices.single())
        assertTrue(controller.state.value.probes.isEmpty());assertTrue(controller.state.value.outcomes.isEmpty())
    }
    @Test fun internalProbeTimeoutIsUnavailableRatherThanUserCancellation() = runTest {
        val handle=object:WifiNetworkHandle {}
        val store=object:WolDeviceStore { override suspend fun load()=listOf(device.copy(probeEnabled=true));override suspend fun save(devices:List<WolDevice>){} }
        val provider=object:NetworkInfoProvider { override suspend fun currentWifi()=WifiNetworkInfo(handle,null,null) }
        val sender=object:PacketSender { override suspend fun sendUdp(network:WifiNetworkHandle,host:String,port:Int,payload:ByteArray){} }
        val prober=object:WakeProber { override suspend fun probe(network:WifiNetworkHandle,host:String,port:Int?):WakeProbeStatus = kotlinx.coroutines.withTimeout(10) { kotlinx.coroutines.delay(100);WakeProbeStatus.RESPONDS } }
        val controller=WolController(store,provider,sender,this,{testScheduler.currentTime},{"id"},prober=prober,monotonicMillis={testScheduler.currentTime})
        runCurrent();controller.wake("one");advanceUntilIdle()
        assertTrue(controller.state.value.outcomes["one"]!!.sent)
        assertEquals(WakeProbeStatus.UNAVAILABLE,controller.state.value.probes["one"])
    }

    @Test fun pendingSaveCannotStartWakeWithStaleDeviceSnapshot() = runTest {
        val gate=kotlinx.coroutines.CompletableDeferred<Unit>();var sends=0
        val store=object:WolDeviceStore { override suspend fun load()=listOf(device);override suspend fun save(devices:List<WolDevice>){gate.await()} }
        val provider=object:NetworkInfoProvider { override suspend fun currentWifi()=WifiNetworkInfo(object:WifiNetworkHandle{},null,null) }
        val sender=object:PacketSender { override suspend fun sendUdp(network:WifiNetworkHandle,host:String,port:Int,payload:ByteArray){sends++} }
        val controller=WolController(store,provider,sender,this,{testScheduler.currentTime},{"id"})
        runCurrent();controller.previewImport(listOf(device.copy(name="Other")));controller.confirmImport();runCurrent()
        assertTrue(controller.state.value.saving)
        controller.wake("one");runCurrent();assertEquals(0,sends);assertNull(controller.state.value.sendingDeviceId)
        gate.complete(Unit);advanceUntilIdle();assertEquals("Other",controller.state.value.devices.single().name)
    }

    @Test fun arrivingImportDoesNotDiscardOpenDraft() = runTest {
        val store=object:WolDeviceStore { override suspend fun load()=listOf(device);override suspend fun save(devices:List<WolDevice>){} }
        val provider=object:NetworkInfoProvider { override suspend fun currentWifi():WifiNetworkInfo?=null }
        val sender=object:PacketSender { override suspend fun sendUdp(network:WifiNetworkHandle,host:String,port:Int,payload:ByteArray){} }
        val controller=WolController(store,provider,sender,this,{0},{"id"})
        runCurrent();controller.add();val draft=controller.state.value.draft!!
        controller.updateDraft(draft.copy(name="Unsaved"))
        assertFalse(controller.previewImport(listOf(device)))
        assertNull(controller.state.value.pendingImport)
        assertEquals("Unsaved",controller.state.value.draft!!.name)
    }

}
