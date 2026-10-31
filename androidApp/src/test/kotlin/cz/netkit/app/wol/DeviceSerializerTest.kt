package cz.netkit.app.wol

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import cz.netkit.shared.wol.TargetMode
import cz.netkit.shared.wol.WolDevice
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.testng.annotations.Test

class DeviceSerializerTest {
    private val device = WolDevice("id", "Test", "AA:BB:CC:DD:EE:FF", TargetMode.CUSTOM,
        "10.0.2.2", 9009, "test.local", 22, "Poznámka", 1, 2)

    @Test fun productionStoreMigratesActualSchemaOneFileOnNextSave() = runTest {
        val old = """{"schema":1,"devices":[{"id":"id","name":"Test","mac":"AA:BB:CC:DD:EE:FF","targetMode":"CUSTOM","customTarget":"10.0.2.2","port":9009,"probeHost":"test.local","probePort":22,"note":"Poznámka","createdAt":1,"updatedAt":2}]}"""
        val directory=Files.createTempDirectory("netkit-migration").toFile()
        val file=directory.resolve("devices.json");file.writeText(old)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val store=AndroidDeviceStore(file,scope)
        try {
            val loaded=store.load();assertEquals(listOf(device),loaded)
            assertEquals(3,loaded.single().repeatCount);assertEquals(250,loaded.single().repeatIntervalMs)
            assertEquals(false,loaded.single().probeEnabled);assertEquals(20,loaded.single().probeDelaySeconds)
            store.save(loaded)
            val envelope=file.inputStream().use { DeviceSerializer.readFrom(it) }
            assertEquals(2,envelope.schema);assertEquals(listOf(device),envelope.devices)
            kotlin.test.assertTrue(file.readText().contains("\"schema\":2"))
        } finally { store.close();scope.cancel();directory.deleteRecursively() }
    }

    @Test fun roundTripPreservesAllFields() = runTest {
        val value = DeviceEnvelope(2, listOf(device))
        val output = ByteArrayOutputStream()
        DeviceSerializer.writeTo(value, output)
        assertEquals(value, DeviceSerializer.readFrom(ByteArrayInputStream(output.toByteArray())))
    }

    @Test fun corruptUnknownMissingAndDuplicateDataAreRejected() = runTest {
        val output = ByteArrayOutputStream()
        DeviceSerializer.writeTo(DeviceEnvelope(2, listOf(device)), output)
        val valid = output.toString(Charsets.UTF_8)
        val inputs = listOf("{", "{}",
            valid.replace("9009", "0"), valid.replace("\"devices\":[", "\"devices\":[${valid.substringAfter("\"devices\":[").substringBeforeLast("]")},"))
        for (input in inputs) {
            assertFailsWith<CorruptionException>(input) {
                DeviceSerializer.readFrom(ByteArrayInputStream(input.toByteArray()))
            }
        }
    }

    @Test fun futureSchemaIsRecognizedBeforeDecodingChangedFields() = runTest {
        val input = "{\"schema\":3,\"devices\":{\"different\":true}}"
        val error = assertFailsWith<UnsupportedDeviceSchema> {
            DeviceSerializer.readFrom(ByteArrayInputStream(input.toByteArray()))
        }
        assertEquals(3, error.schema)
    }

    @Test fun malformedUtf8InsideJsonIsRejectedAndNeverOverwritten() = runTest {
        val output = ByteArrayOutputStream()
        DeviceSerializer.writeTo(DeviceEnvelope(2, listOf(device)), output)
        // Break one byte inside a syntactically valid JSON name string.
        val original = output.toByteArray()
        val nameOffset = output.toString(Charsets.UTF_8).indexOf("Test")
        check(nameOffset >= 0)
        original[nameOffset] = 0xff.toByte()
        assertFailsWith<CorruptionException> {
            DeviceSerializer.readFrom(ByteArrayInputStream(original))
        }
        val directory = Files.createTempDirectory("netkit-utf8-store").toFile()
        val file = directory.resolve("devices.json")
        file.writeBytes(original)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreFactory.create(serializer = DeviceSerializer, scope = scope) { file }
            assertFailsWith<CorruptionException> { store.data.first() }
            assertFailsWith<CorruptionException> { store.updateData { DeviceEnvelope(2, emptyList()) } }
            kotlin.test.assertContentEquals(original, file.readBytes())
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test fun datastoreFailureDoesNotOverwriteCorruptFile() = runTest {
        val directory = Files.createTempDirectory("netkit-store").toFile()
        val file = directory.resolve("devices.json")
        val original = "{malformed"
        file.writeText(original)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreFactory.create(serializer = DeviceSerializer, scope = scope) { file }
            assertFailsWith<CorruptionException> { store.data.first() }
            assertFailsWith<CorruptionException> { store.updateData { DeviceEnvelope(2, emptyList()) } }
            assertEquals(original, file.readText())
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}

class DeviceRecoveryTest {
    private fun fixture() = Files.createTempDirectory("netkit-recovery-").toFile()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test fun confirmedRecoveryArchivesExactBytesAndCreatesUsableEmptyStore() = runTest {
        val dir = fixture(); val file = dir.resolve("devices.json")
        val original = byteArrayOf(0xff.toByte(), 0, 1, 2)
        file.writeBytes(original)
        val owner = scope(); val store = AndroidDeviceStore(file, owner)
        try {
            val error = assertFailsWith<cz.netkit.shared.wol.StoreReadException> { store.load() }
            assertEquals(cz.netkit.shared.wol.StoreFailure.CORRUPT, error.kind)
            val recovery = store.recover()
            kotlin.test.assertTrue(recovery.devices.isEmpty())
            kotlin.test.assertContentEquals(original, java.io.File(kotlin.test.assertNotNull(recovery.archivePath)).readBytes())
            assertEquals(recovery.archivePath, store.recoveryArchivePath)
            store.save(emptyList()); kotlin.test.assertTrue(store.load().isEmpty())
        } finally { store.close(); owner.cancel(); dir.deleteRecursively() }
    }

    @Test fun failedArchiveLeavesOriginalBytesAndCanRetry() = runTest {
        val dir = fixture(); val file = dir.resolve("devices.json"); val original = "{broken".toByteArray()
        file.writeBytes(original)
        var fail = true
        val owner = scope()
        val store = AndroidDeviceStore(file, owner) { source, target ->
            if (fail) throw java.io.IOException("fixture archive failure")
            Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            assertFailsWith<cz.netkit.shared.wol.StoreReadException> { store.load() }
            assertFailsWith<cz.netkit.shared.wol.StoreReadException> { store.recover() }
            kotlin.test.assertContentEquals(original, file.readBytes())
            assertEquals(emptyList(), dir.listFiles()!!.filter { it.name.startsWith("wol-archive-") })
            fail = false
            val result = store.recover()
            kotlin.test.assertContentEquals(original, java.io.File(kotlin.test.assertNotNull(result.archivePath)).readBytes())
        } finally { store.close(); owner.cancel(); dir.deleteRecursively() }
    }

    @Test fun initializationFailureAfterArchiveRetainsReceiptForRetry() = runTest {
        val dir = fixture(); val file = dir.resolve("devices.json"); val original = "{broken".toByteArray(); file.writeBytes(original)
        val owner = scope(); var failCreate = false
        val store = AndroidDeviceStore(file, owner, beforeCreate = { if (failCreate) throw java.io.IOException("fixture create failure") }, archive = { source, target ->
            Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            failCreate = true
        })
        try {
            assertFailsWith<cz.netkit.shared.wol.StoreReadException> { store.load() }
            val error = assertFailsWith<cz.netkit.shared.wol.StoreReadException> { store.recover() }
            val path = kotlin.test.assertNotNull(error.archivePath)
            kotlin.test.assertContentEquals(original, java.io.File(path).readBytes())
            failCreate = false
            kotlin.test.assertTrue(store.load().isEmpty())
            assertEquals(path, store.recoveryArchivePath)
        } finally { store.close(); owner.cancel(); dir.deleteRecursively() }
    }

    @Test fun freshStoreDiscoversNewestArchiveAndIgnoresEmptyAndLinkedEntries() = runTest {
        val dir = fixture(); val file = dir.resolve("devices.json")
        val original = byteArrayOf(0xff.toByte(), 0, 1, 2)
        file.writeBytes(original)
        val firstOwner = scope(); val first = AndroidDeviceStore(file, firstOwner)
        val path = try {
            kotlin.test.assertNotNull(first.recover().archivePath)
        } finally { first.close(); firstOwner.cancel() }
        val older = dir.resolve("wol-archive-older").also { it.mkdir() }
        older.resolve(file.name).writeText("older original")
        check(older.setLastModified(1))
        dir.resolve("wol-archive-empty").mkdir()
        val linked = dir.resolve("wol-archive-linked").also { it.mkdir() }
        Files.createSymbolicLink(linked.resolve(file.name).toPath(), java.io.File(path).toPath())
        Files.createSymbolicLink(dir.resolve("wol-archive-directory-link").toPath(), java.io.File(path).parentFile.toPath())
        val secondOwner = scope(); val second = AndroidDeviceStore(file, secondOwner)
        try {
            kotlin.test.assertTrue(second.load().isEmpty())
            assertEquals(path, second.recoveryArchivePath)
            kotlin.test.assertContentEquals(original, java.io.File(path).readBytes())
        } finally { second.close(); secondOwner.cancel(); dir.deleteRecursively() }
    }

    @Test fun freshStoreFindsArchiveAfterInitializationFailure() = runTest {
        val dir = fixture(); val file = dir.resolve("devices.json"); val original = "{broken".toByteArray()
        file.writeBytes(original)
        val firstOwner = scope()
        val first = AndroidDeviceStore(file, firstOwner, beforeCreate = { throw java.io.IOException("fixture create failure") })
        val path = try {
            kotlin.test.assertNotNull(assertFailsWith<cz.netkit.shared.wol.StoreReadException> { first.recover() }.archivePath)
        } finally { first.close(); firstOwner.cancel() }
        val secondOwner = scope(); val second = AndroidDeviceStore(file, secondOwner)
        try {
            kotlin.test.assertTrue(second.load().isEmpty())
            assertEquals(path, second.recoveryArchivePath)
            kotlin.test.assertContentEquals(original, java.io.File(path).readBytes())
        } finally { second.close(); secondOwner.cancel(); dir.deleteRecursively() }
    }

    @Test fun repairedFileIsRevalidatedAndNeverReset() = runTest {
        val dir = fixture(); val file = dir.resolve("devices.json"); file.writeText("{broken")
        val owner = scope(); val store = AndroidDeviceStore(file, owner)
        val device = WolDevice("id", "NAS", "AA:BB:CC:DD:EE:FF", TargetMode.LIMITED_BROADCAST, createdAt = 1, updatedAt = 2)
        try {
            assertFailsWith<cz.netkit.shared.wol.StoreReadException> { store.load() }
            val bytes = ByteArrayOutputStream().also { DeviceSerializer.writeTo(DeviceEnvelope(2, listOf(device)), it) }.toByteArray()
            file.writeBytes(bytes)
            val result = store.recover()
            assertEquals(listOf(device), result.devices)
            kotlin.test.assertNull(result.archivePath)
            kotlin.test.assertContentEquals(bytes, file.readBytes())
            assertEquals(listOf(device), store.load())
        } finally { store.close(); owner.cancel(); dir.deleteRecursively() }
    }

    @Test fun futureSchemaAndIoFailuresAreDistinct() = runTest {
        val dir = fixture(); val file = dir.resolve("devices.json"); file.writeText("{\"schema\":5,\"unknown\":[]}")
        val owner = scope(); val store = AndroidDeviceStore(file, owner)
        try {
            val error = assertFailsWith<cz.netkit.shared.wol.StoreReadException> { store.load() }
            assertEquals(cz.netkit.shared.wol.StoreFailure.UNSUPPORTED_SCHEMA, error.kind)
            assertEquals(5, error.schema)
        } finally { store.close(); owner.cancel(); dir.deleteRecursively() }
        val ioDir = fixture(); val ioOwner = scope(); val ioStore = AndroidDeviceStore(ioDir, ioOwner)
        try {
            val error = assertFailsWith<cz.netkit.shared.wol.StoreReadException> { ioStore.load() }
            assertEquals(cz.netkit.shared.wol.StoreFailure.IO, error.kind)
        } finally { ioStore.close(); ioOwner.cancel(); ioDir.deleteRecursively() }
    }

    @Test fun cancellationDuringArchiveStillPreservesReceiptAndSerializesConcurrentLoad() = runTest {
        val dir = fixture(); val file = dir.resolve("devices.json"); val original = "{broken".toByteArray(); file.writeBytes(original)
        val moved = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val owner = scope()
        val store = AndroidDeviceStore(file, owner) { source, target ->
            Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            moved.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            assertFailsWith<cz.netkit.shared.wol.StoreReadException> { store.load() }
            val recovery = async(Dispatchers.IO) { store.recover() }
            check(kotlinx.coroutines.withContext(Dispatchers.IO) { moved.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            val load = async(Dispatchers.IO) { store.load() }
            recovery.cancel(); release.countDown(); recovery.join()
            kotlin.test.assertTrue(load.await().isEmpty())
            kotlin.test.assertContentEquals(original, java.io.File(kotlin.test.assertNotNull(store.recoveryArchivePath)).readBytes())
            // Existing archive cannot be overwritten by a second recovery on now-valid data.
            val path = store.recoveryArchivePath
            assertEquals(path, store.recover().archivePath)
        } finally { release.countDown(); store.close(); owner.cancel(); dir.deleteRecursively() }
    }
}
