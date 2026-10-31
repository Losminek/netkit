package cz.netkit.app.speed

import cz.netkit.shared.speed.SpeedEngine
import cz.netkit.shared.speed.SpeedRecord
import cz.netkit.shared.speed.withRecord
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SpeedStoreTest {
    private fun tempFile(): File = Files.createTempDirectory("speedstore").toFile().resolve("speedtest.json")

    @Test fun missingFileIsEmptyAndUpdatesPersistAtomically() = runBlocking {
        val file = tempFile()
        val store = SpeedStore(file)
        val empty = store.read()
        assertFalse(empty.corrupt)
        assertTrue(empty.data.records.isEmpty())
        store.update { it.copy(ndt7ConsentMs = 42).withRecord(SpeedRecord(1, SpeedEngine.NDT7, "mlab", downloadMbps = 90.0)) }
        val again = SpeedStore(file).read()
        assertEquals(42L, again.data.ndt7ConsentMs)
        assertEquals("mlab", again.data.records.single().serverName)
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
        assertFalse(file.readText().contains("access_token"))
    }

    @Test fun corruptFileFallsBackToDefaultsAndIsReplacedOnWrite() = runBlocking {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeText("{not json")
        val store = SpeedStore(file)
        assertTrue(store.read().corrupt)
        store.update { it.copy(customUrl = "https://x.example/") }
        val fixed = store.read()
        assertFalse(fixed.corrupt)
        assertEquals("https://x.example/", fixed.data.customUrl)
    }
}
