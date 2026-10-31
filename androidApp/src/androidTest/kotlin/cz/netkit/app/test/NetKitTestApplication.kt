package cz.netkit.app.test

import android.content.Context
import cz.netkit.app.wol.AndroidDeviceStore
import cz.netkit.app.wol.NetKitApplication
import cz.netkit.shared.wol.WolDeviceStore
import java.io.File
import kotlinx.coroutines.runBlocking

/** Application substitute installed only by the JUnit-free instrumentation runner. */
class NetKitTestApplication : NetKitApplication() {
    private var testStore: AndroidDeviceStore? = null

    override fun createDeviceStore(): WolDeviceStore {
        val id = requireRunId()
        val directory = File(filesDir, "$TEST_DATA_DIR/$id")
        check(directory.canonicalFile.parentFile == File(filesDir, TEST_DATA_DIR).canonicalFile) {
            "Test store path escaped its bounded test-data directory"
        }
        check(directory.isDirectory || directory.mkdirs()) { "Could not create isolated test store directory" }
        return AndroidDeviceStore(this, File(directory, STORE_FILE)).also { testStore = it }
    }

    fun closeTestStore() {
        testStore?.let { store -> runBlocking { store.close() } }
        testStore = null
    }

    fun screenshotFile(name: String, runId: String = requireRunId()): File {
        require(name == SCREENSHOT_FILE || name == KEYBOARD_SCREENSHOT_FILE || name.matches(Regex("matrix-[a-f]\\.png"))) { "Unexpected instrumentation screenshot name" }
        return File(File(filesDir, "$TEST_DATA_DIR/$runId"), name)
    }

    private fun requireRunId(): String = currentRunId.also {
        require(it.matches(RUN_ID_PATTERN)) { "Instrumentation runId was not configured before app launch" }
    }

    companion object {
        const val TEST_DATA_DIR = "netkit-test-data"
        const val STORE_FILE = "wol-devices.json"
        const val SCREENSHOT_FILE = "read-phase.png"
        const val KEYBOARD_SCREENSHOT_FILE = "ime-save-visible.png"
        private val RUN_ID_PATTERN = Regex("[a-f0-9]{32}")

        @Volatile
        var currentRunId: String = ""
    }
}
