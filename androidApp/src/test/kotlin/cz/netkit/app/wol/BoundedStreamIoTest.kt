package cz.netkit.app.wol

import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.testng.annotations.Test
import kotlin.test.*

class BoundedStreamIoTest {
    @Test fun cancelledBlockingReadClosesStreamAndReleasesWorker() = runBlocking {
        val entered=CountDownLatch(1); val released=CountDownLatch(1); val exited=CountDownLatch(1)
        var cancelledOpen=false
        val stream=object:InputStream() {
            override fun read():Int {
                entered.countDown()
                try { if(!released.await(3,TimeUnit.SECONDS)) error("Reader not cancelled"); throw IOException("closed") }
                finally { exited.countDown() }
            }
            override fun close() { released.countDown() }
        }
        val job=launch { BoundedStreamIo.read({stream},{cancelledOpen=true},100) }
        withContext(Dispatchers.IO) { assertTrue(entered.await(2,TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        withContext(Dispatchers.IO) { assertTrue(exited.await(2,TimeUnit.SECONDS)) }
        assertTrue(cancelledOpen)
    }
    @Test fun boundedReadRejectsOversizeAndWriteClosesOnSuccess() = runBlocking {
        assertFailsWith<IllegalArgumentException> { BoundedStreamIo.read({ByteArray(11).inputStream()},{},10) }
        var closed=false
        val output=object:java.io.ByteArrayOutputStream() { override fun close(){closed=true;super.close()} }
        BoundedStreamIo.write({output},{},byteArrayOf(1,2,3))
        assertTrue(closed);assertContentEquals(byteArrayOf(1,2,3),output.toByteArray())
    }
}
