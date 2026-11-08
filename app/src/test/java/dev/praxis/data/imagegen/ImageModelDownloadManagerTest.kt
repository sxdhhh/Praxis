package dev.praxis.data.imagegen

import dev.praxis.core.imagegen.ImageDownloadState
import dev.praxis.core.imagegen.ImageModel
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 编排层的三件事：单飞、状态流转、取消。
 *
 * 它守的是界面最容易做错的地方 —— 同一时刻只该有一个下载，而「取消」必须真的停下并清理。
 */
class ImageModelDownloadManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun model() = ImageModel(
        id = "anythingv5cpu",
        name = "AnythingV5 (CPU)",
        fileUri = "remote/AnythingV5.zip",
        backendType = "sd15cpu",
        approximateSize = "1MB",
    )

    /** 每块之间睡一下，好让 cancel 有落地的时机。 */
    private fun slowStream(bytes: Int, delayMillis: Long): InputStream = object : InputStream() {
        private var remaining = bytes
        private val chunk = ByteArray(16 * 1024)
        override fun read(): Int {
            val buffer = ByteArray(1)
            val n = read(buffer, 0, 1)
            return if (n <= 0) -1 else buffer[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            Thread.sleep(delayMillis)
            val n = minOf(len, chunk.size, remaining)
            remaining -= n
            return n
        }
    }

    private fun manager(openStream: (String) -> InputStream) = ImageModelDownloadManager(
        downloader = ModelDownloader(baseUrl = "", openStream = openStream),
        modelsRoot = tmp.newFolder(),
        scope = CoroutineScope(Dispatchers.Default),
    )

    @Test
    fun `同一时刻只允许一个下载`() = runBlocking {
        val m = manager { slowStream(200_000, 20) }
        assertTrue(m.start(model()))
        assertFalse("第二个请求必须被拒绝，而不是排队", m.start(model()))
        m.cancel()
        withTimeout(10_000) { m.state.first { it is ImageDownloadState.Cancelled } }
        // JUnit4 要求测试方法返回 void：runBlocking 块的最后一句不能是带值的表达式，
        // 否则整类会以 initializationError 失败（CI 抓到的就是这个）。
        Unit
    }

    @Test
    fun `成功后状态是 Completed`() = runBlocking {
        val m = manager { ByteArrayInputStream(ByteArray(1024)) }
        assertTrue(m.start(model()))
        val done = withTimeout(10_000) {
            m.state.first { it is ImageDownloadState.Completed || it is ImageDownloadState.Failed }
        }
        assertTrue(done is ImageDownloadState.Completed)
        assertEquals("anythingv5cpu", (done as ImageDownloadState.Completed).modelId)
    }

    @Test
    fun `传输失败时状态是 Failed 且带原因`() = runBlocking {
        val m = manager { throw java.io.IOException("连接被重置") }
        assertTrue(m.start(model()))
        val done = withTimeout(10_000) {
            m.state.first { it is ImageDownloadState.Failed || it is ImageDownloadState.Completed }
        }
        assertTrue(done is ImageDownloadState.Failed)
        val failed = done as ImageDownloadState.Failed
        assertEquals(ImageDownloadState.Failed.Reason.Transport, failed.reason)
        assertTrue(failed.detail.contains("连接被重置"))
    }

    @Test
    fun `取消后状态是 Cancelled 而不是 Failed`() = runBlocking {
        val m = manager { slowStream(500_000, 20) }
        assertTrue(m.start(model()))
        // 等第一块真的下来了再取消，避免测成「还没开始就取消」。
        withTimeout(10_000) { m.state.first { it is ImageDownloadState.Running && it.progress != null } }
        m.cancel()
        val done = withTimeout(10_000) { m.state.first { it !is ImageDownloadState.Running } }
        assertTrue("取消不该被当成错误", done is ImageDownloadState.Cancelled)
    }

    @Test
    fun `acknowledge 后回到 Idle`() = runBlocking {
        val m = manager { ByteArrayInputStream(ByteArray(256)) }
        assertTrue(m.start(model()))
        withTimeout(10_000) { m.state.first { it is ImageDownloadState.Completed || it is ImageDownloadState.Failed } }
        m.acknowledge()
        assertEquals(ImageDownloadState.Idle, m.state.value)
        assertFalse(m.isBusy())
    }
}
