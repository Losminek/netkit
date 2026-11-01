package cz.netkit.app.ssh

import cz.netkit.shared.ssh.*
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.*

class SshProfileExportTest {
    private val profile = SshProfile("fixture", "Fixture", "192.0.2.10", username = "tester")

    @Test fun preparedSnapshotDoesNotReadChangedStore() = runTest {
        val bytes = encodeSshProfileExport(SshData(profiles = listOf(profile)))
        var written: ByteArray? = null
        assertNull(completeSshProfileExport(bytes, { error("Snapshot must not read storage") }) { written = it })
        assertContentEquals(bytes, written)
    }

    @Test fun lostSnapshotReadsAndEncodesCurrentStore() = runTest {
        val current = SshData(profiles = listOf(profile.copy(name = "Current")))
        var reads = 0
        var written: ByteArray? = null
        assertNull(completeSshProfileExport(null, { reads++; current }) { written = it })
        assertEquals(1, reads)
        assertEquals("Current", decodeSshProfileImport(assertNotNull(written)).profiles.single().alias)
    }

    @Test fun duplicateNamesAfterStateLossNeverOpenDestination() = runTest {
        val data = SshData(profiles = listOf(profile, profile.copy(id = "second")))
        assertEquals(SshProfileExportError.DuplicateNames(listOf("Fixture")),
            completeSshProfileExport(null, { data }) { error("Must not open/truncate destination") })
    }

    @Test fun oversizeAfterStateLossNeverOpensDestination() = runTest {
        val data = SshData(profiles = (1..70).map { profile.copy(id = "p$it", name = "p$it", startupCommand = "ž".repeat(4096)) })
        assertEquals(SshProfileExportError.TooLarge,
            completeSshProfileExport(null, { data }) { error("Must not open/truncate destination") })
    }

    @Test fun providerFailureStillPropagatesToOperationHandler() = runTest {
        assertFailsWith<IOException> {
            completeSshProfileExport(null, { SshData(profiles = listOf(profile)) }) { throw IOException() }
        }
    }
}
