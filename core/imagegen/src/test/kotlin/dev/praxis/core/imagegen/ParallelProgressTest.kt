package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 并发下载的进度聚合。 */
class ParallelProgressTest {

    @Test
    fun `多文件字节数相加`() {
        // 各报各的会让界面一会 3MB 一会 1MB；只有加起来才是单调递增的。
        val progress = ParallelProgress.aggregate(
            done = mapOf("a.mnn" to 1_000L, "b.mnn" to 2_000L, "c.mnn" to 500L),
            totalBytes = 10_000L,
        )
        assertEquals(3_500L, progress.bytesDownloaded)
        assertEquals(10_000L, progress.totalBytes)
        assertEquals(0.35f, progress.fraction!!, 0.0001f)
    }

    @Test
    fun `总大小未知时不给假百分比`() {
        val progress = ParallelProgress.aggregate(mapOf("a" to 100L), totalBytes = null)
        assertEquals(100L, progress.bytesDownloaded)
        assertNull(progress.totalBytes)
        assertNull("总大小未知就不该有 fraction", progress.fraction)
    }

    @Test
    fun `没有文件时是零而不是崩`() {
        val progress = ParallelProgress.aggregate(emptyMap(), totalBytes = 100L)
        assertEquals(0L, progress.bytesDownloaded)
        assertEquals(0f, progress.fraction!!, 0.0001f)
    }

    @Test
    fun `总大小为零时不产生除零`() {
        val progress = ParallelProgress.aggregate(mapOf("a" to 10L), totalBytes = 0L)
        assertNull(progress.fraction)
    }

    @Test
    fun `超出预估总大小时夹到 100 而不是超过`() {
        // 上游给的 Content-Length 与实际内容不一致时会出现这种情况。
        val progress = ParallelProgress.aggregate(mapOf("a" to 2_000L), totalBytes = 1_000L)
        assertEquals(1f, progress.fraction!!, 0.0001f)
    }

    @Test
    fun `fileName 取已下最多的那个`() {
        val progress = ParallelProgress.aggregate(
            mapOf("small" to 1L, "big" to 999L),
            totalBytes = 1_000L,
        )
        assertEquals("big", progress.fileName)
    }

    @Test
    fun `并发数归一化`() {
        assertEquals(1, ImageDownloadSettingsRules.normalizeConcurrency(0))
        assertEquals(1, ImageDownloadSettingsRules.normalizeConcurrency(-5))
        assertEquals(3, ImageDownloadSettingsRules.normalizeConcurrency(3))
        assertEquals(4, ImageDownloadSettingsRules.normalizeConcurrency(99))
    }

    @Test
    fun `并发数的默认值与往返`() {
        assertEquals(2, ImageDownloadSettings().concurrency)
        val settings = ImageDownloadSettings(concurrency = 4)
        val decoded = ImageDownloadSettingsCodec.decode(ImageDownloadSettingsCodec.encode(settings))
        assertEquals(4, decoded?.getOrNull()?.concurrency)
    }

    @Test
    fun `缺 concurrency 字段时用默认值（schema 演进）`() {
        val decoded = ImageDownloadSettingsCodec
            .decode("""{"baseUrl":"https://huggingface.co/","wifiOnly":true}""")
            ?.getOrNull()
        assertNotNull(decoded)
        assertEquals(2, decoded?.concurrency)
    }
}
