package cz.netkit.shared.lan

import kotlin.test.*

class LanExportLimitsTest {
    @Test fun full4096BannerExportUsesFileInsteadOfOversizedIpc() {
        val rows=List(4096) { LanResult("10.0.0.1",method="TCP",port=it+1,detail="B".repeat(256)) }
        for (format in LanExport.entries) {
            val text=exportLan(rows,format)
            assertFalse(lanTextFitsIpc(text))
            assertEquals(text,lanExportFileBytes(text).decodeToString())
        }
    }
    @Test fun limitsCountUtf16ForIpcAndUtf8ForFiles() {
        assertTrue(lanTextFitsIpc("x".repeat(LAN_IPC_TEXT_LIMIT)))
        assertFalse(lanTextFitsIpc("x".repeat(LAN_IPC_TEXT_LIMIT+1)))
        assertTrue(lanTextFitsIpc("😀".repeat(LAN_IPC_TEXT_LIMIT/2)))
        assertFalse(lanTextFitsIpc("😀".repeat(LAN_IPC_TEXT_LIMIT/2+1)))
        assertFailsWith<IllegalArgumentException> { lanExportFileBytes("ě".repeat(LAN_EXPORT_FILE_LIMIT/2+1)) }
    }
}
