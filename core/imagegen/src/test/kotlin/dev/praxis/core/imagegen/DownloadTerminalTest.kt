package dev.praxis.core.imagegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整次下载的终态收敛规则。
 *
 * 这组用例的存在理由是一条真实红灯：并发下载时「传输失败」有一半概率被报成「用户取消」。
 * 见 [DownloadTerminal] 的 KDoc。
 */
class DownloadTerminalTest {

    private val failed = DownloadResult.Failed("网络断了")
    private val cancelled = DownloadResult.Cancelled

    @Test
    fun `全部成功时没有终态`() {
        assertNull(DownloadTerminal.pick(listOf(null, null), userCancelled = false))
    }

    @Test
    fun `第一个文件失败就是 Failed`() {
        val picked = DownloadTerminal.pick(listOf(failed, null), userCancelled = false)
        assertTrue("实际=" + picked, picked is DownloadResult.Failed)
    }

    @Test
    fun `同伴被中止时仍然报真失败而不是取消`() {
        // 现场：一个文件抛 IOException 置 abort，另一个正在跑的文件读到 abort 返回 Cancelled。
        // 两种顺序都必须收敛成 Failed —— 否则用户没点取消却看到「已取消」，真实原因被吞。
        val peerFirst = DownloadTerminal.pick(listOf(cancelled, failed), userCancelled = false)
        val failureFirst = DownloadTerminal.pick(listOf(failed, cancelled), userCancelled = false)
        assertTrue("顺序 A 实际=" + peerFirst, peerFirst is DownloadResult.Failed)
        assertTrue("顺序 B 实际=" + failureFirst, failureFirst is DownloadResult.Failed)
    }

    @Test
    fun `真失败的原因被原样带出去`() {
        val picked = DownloadTerminal.pick(listOf(cancelled, failed), userCancelled = false)
        assertEquals("网络断了", (picked as DownloadResult.Failed).detail)
    }

    @Test
    fun `空间不足也算真失败且优先于取消`() {
        val space = DownloadResult.InsufficientSpace(requiredBytes = 10L, freeBytes = 1L)
        val picked = DownloadTerminal.pick(listOf(cancelled, space), userCancelled = false)
        assertTrue("实际=" + picked, picked is DownloadResult.InsufficientSpace)
    }

    @Test
    fun `用户点取消时取消优先`() {
        // 用户按下取消后，别的文件可能同时报错（连接被关掉）。此时该报取消，不该弹红字。
        val picked = DownloadTerminal.pick(listOf(failed, cancelled), userCancelled = true)
        assertTrue("实际=" + picked, picked is DownloadResult.Cancelled)
    }

    @Test
    fun `用户取消时即使没有 Cancelled 也要带回失败原因`() {
        val picked = DownloadTerminal.pick(listOf(failed, null), userCancelled = true)
        assertTrue("实际=" + picked, picked is DownloadResult.Failed)
    }

    @Test
    fun `空结果不产生终态`() {
        assertNull(DownloadTerminal.pick(emptyList(), userCancelled = false))
        assertNull(DownloadTerminal.pick(emptyList(), userCancelled = true))
    }
}
