package cz.netkit.shared.wol

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WolInteractionRevisionTest {
    private val device = WolDevice("one", "NAS", "AA:BB:CC:DD:EE:FF", TargetMode.CUSTOM,
        customTarget="127.0.0.1",probeHost="127.0.0.1",createdAt=1,updatedAt=2,repeatCount=1,probeEnabled=true)
    private class Store(var devices: List<WolDevice>) : WolDeviceStore {
        override suspend fun load() = devices
        override suspend fun save(devices: List<WolDevice>) { this.devices = devices }
    }
    @Test fun pollingDoesNotBlockEditingAndDeletedDeviceNeverGetsLateResult() = runTest {
        val store=Store(listOf(device))
        val handle=object:WifiNetworkHandle {}
        var attempts=0
        val controller=WolController(store,
            object:NetworkInfoProvider {override suspend fun currentWifi()=WifiNetworkInfo(handle,null,null)},
            object:PacketSender {override suspend fun sendUdp(network:WifiNetworkHandle,host:String,port:Int,payload:ByteArray){assertSame(handle,network)}},
            this,{testScheduler.currentTime},{"new"},prober=object:WakeProber {
                override suspend fun probe(network:WifiNetworkHandle,host:String,port:Int?):WakeProbeStatus {
                    assertSame(handle,network);assertEquals("127.0.0.1",host); attempts++
                    if(attempts==1)return WakeProbeStatus.NO_RESPONSE
                    withContext(NonCancellable){delay(3000)} // a slow provider cannot revive a deleted record
                    return WakeProbeStatus.RESPONDS
                }
            },monotonicMillis={testScheduler.currentTime})
        runCurrent();controller.wake("one");runCurrent()
        assertNull(controller.state.value.sendingDeviceId)
        assertEquals("one",controller.state.value.probingDeviceId)
        controller.edit("one");assertNotNull(controller.state.value.draft)
        controller.cancelEdit();controller.delete("one");runCurrent()
        assertTrue(controller.state.value.devices.isEmpty())
        assertNull(controller.state.value.probingDeviceId)
        advanceUntilIdle()
        assertTrue(controller.state.value.probes.isEmpty())
        assertTrue(controller.state.value.outcomes.isEmpty())
    }
    @Test fun precheckResponseIsDistinctAndRequestedPacketsStillGoToSelectedNetwork() = runTest {
        val first=object:WifiNetworkHandle {};val other=object:WifiNetworkHandle {}
        var lookups=0;var sends=0;var probes=0
        val controller=WolController(Store(listOf(device)),
            object:NetworkInfoProvider {override suspend fun currentWifi()=WifiNetworkInfo(if(++lookups==1)first else other,null,null)},
            object:PacketSender {override suspend fun sendUdp(network:WifiNetworkHandle,host:String,port:Int,payload:ByteArray){assertSame(first,network);sends++}},
            this,{testScheduler.currentTime},{"new"},prober=object:WakeProber {
                override suspend fun probe(network:WifiNetworkHandle,host:String,port:Int?):WakeProbeStatus {
                    assertSame(first,network);probes++;return WakeProbeStatus.RESPONDS
                }
            },monotonicMillis={testScheduler.currentTime})
        runCurrent();controller.wake("one");advanceUntilIdle()
        assertEquals(1,sends);assertEquals(1,probes);assertEquals(1,lookups)
        assertEquals(WakeProbeStatus.ALREADY_RESPONDED,controller.state.value.probes["one"])
        assertNull(controller.state.value.probingDeviceId)
    }
    @Test fun replacingDeviceWhilePollingClearsItsOldOutcomeAndKeepsNewSnapshot() = runTest {
        val handle=object:WifiNetworkHandle {}
        val store=Store(listOf(device))
        val controller=WolController(store,
            object:NetworkInfoProvider {override suspend fun currentWifi()=WifiNetworkInfo(handle,null,null)},
            object:PacketSender {override suspend fun sendUdp(network:WifiNetworkHandle,host:String,port:Int,payload:ByteArray){}},
            this,{testScheduler.currentTime},{"new"},prober=object:WakeProber {
                override suspend fun probe(network:WifiNetworkHandle,host:String,port:Int?)=WakeProbeStatus.NO_RESPONSE
            },monotonicMillis={testScheduler.currentTime})
        runCurrent();controller.wake("one");runCurrent()
        val updated=device.copy(probeHost="127.0.0.2",name="New")
        assertTrue(controller.previewImport(listOf(updated)))
        controller.confirmImport();advanceUntilIdle()
        assertEquals(updated,controller.state.value.devices.single())
        assertNull(controller.state.value.probingDeviceId)
        assertTrue(controller.state.value.probes.isEmpty());assertTrue(controller.state.value.outcomes.isEmpty())
    }
    @Test fun sortUsesInjectedComparatorAndMovePersistsOnlyValidAdjacentChanges() = runTest {
        val store=Store(listOf(device.copy(id="c",name="b"),device.copy(id="b",name="a"),device.copy(id="a",name="a")))
        var comparisons=0
        val controller=WolController(store,
            object:NetworkInfoProvider {override suspend fun currentWifi():WifiNetworkInfo?=null},
            object:PacketSender {override suspend fun sendUdp(network:WifiNetworkHandle,host:String,port:Int,payload:ByteArray){}},
            this,{1},{"new"},nameComparator=Comparator {a,b->comparisons++;a.compareTo(b)})
        runCurrent();controller.sortByName();advanceUntilIdle()
        assertTrue(comparisons>0);assertEquals(listOf("a","b","c"),store.devices.map{it.id})
        controller.move("a",-1);controller.move("missing",1);controller.move("a",2);runCurrent()
        assertEquals(listOf("a","b","c"),store.devices.map{it.id})
        controller.move("c",-1);advanceUntilIdle()
        assertEquals(listOf("a","c","b"),store.devices.map{it.id})
        controller.move("a",1);advanceUntilIdle()
        assertEquals(listOf("c","a","b"),store.devices.map{it.id})
    }
    @Test fun importPreviewMarksNonPrivateOrUnresolvedTargets() {
        for(host in listOf("10.0.0.1","172.16.0.1","172.31.255.255","192.168.1.255"))assertTrue(isPrivateWolImportTarget(host),host)
        val belowPrivate = Ipv4Address(assertNotNull(parseIpv4("172.16.0.0")).value - 1).toString()
        val abovePrivate = Ipv4Address(assertNotNull(parseIpv4("172.31.255.255")).value + 2).toString()
        for(host in listOf(belowPrivate,abovePrivate,"127.0.0.1","192.0.2.1","255.255.255.255","nas.local"))assertFalse(isPrivateWolImportTarget(host),host)
    }
}
