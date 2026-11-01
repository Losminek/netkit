package cz.netkit.app.ssh

import cz.netkit.shared.ssh.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SshLegacyExportFixtureTest {
    @Test fun originalSerializerExportDecodesWithPortableSettings() = runTest {
        val data = SshData(profiles = SshAuthentication.entries.mapIndexed { index, auth ->
            SshProfile("old-$index", "fixture-$index", "192.0.2.${index + 1}", 2222 + index, "tester",
                auth, "secret-$index", "key-$index", "show version", 60 + index)
        })
        val output = ByteArrayOutputStream()
        SshDataSerializer.writeTo(exportSshProfiles(data), output)
        val incoming = decodeSshProfileImport(output.toByteArray())
        var id = 0
        val restored = applySshImportPlan(SshData(), planSshProfileImport(SshData(), incoming, null) { "new-${id++}" })
        assertEquals(data.profiles.mapIndexed { index, p -> p.copy(id = "new-$index", secretRef = null, keyRef = null) }, restored.profiles)
        assertEquals(data.profiles.map { it.copy(secretRef = null, keyRef = null) },
            SshDataSerializer.readFrom(output.toByteArray().inputStream()).profiles)
    }
}
