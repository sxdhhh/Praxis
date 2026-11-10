package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图片压缩策略（文档 §5.18）。 */
class ChatImageCompressionTest {

    private val on = ChatImageCompression(enabled = true, maxEdge = 512, quality = 80, thresholdKb = 256)

    @Test
    fun `开关关着时什么都不做`() {
        val decision = ChatImageCompressionRules.decide(on.copy(enabled = false), 4000, 3000, 5_000_000L)
        assertEquals(ChatImageCompressionDecision.Keep, decision)
    }

    @Test
    fun `小图不碰（再压只是多掉一层画质）`() {
        val decision = ChatImageCompressionRules.decide(on, 2000, 1500, 100L * 1024L)
        assertEquals(ChatImageCompressionDecision.Keep, decision)
    }

    @Test
    fun `大图按长边压`() {
        val decision = ChatImageCompressionRules.decide(on, 4000, 3000, 5_000_000L)
        assertEquals(ChatImageCompressionDecision.Compress(512, 80), decision)
    }

    @Test
    fun `尺寸已小于长边但体积仍超阈值时 只降质量不改尺寸`() {
        // 高质小尺寸图仍可能很大：这时改尺寸是白掉画质。
        val decision = ChatImageCompressionRules.decide(on, 300, 400, 900L * 1024L)
        assertEquals(ChatImageCompressionDecision.Compress(400, 80), decision)
    }

    @Test
    fun `尺寸未知时不猜（宁可不动）`() {
        assertEquals(ChatImageCompressionDecision.Keep, ChatImageCompressionRules.decide(on, 0, 0, 5_000_000L))
    }

    @Test
    fun `体积未知时按尺寸判断`() {
        val decision = ChatImageCompressionRules.decide(on, 4000, 3000, 0L)
        assertTrue(decision is ChatImageCompressionDecision.Compress)
    }

    @Test
    fun `阈值 0 表示不按体积豁免`() {
        val zero = on.copy(thresholdKb = 0)
        val decision = ChatImageCompressionRules.decide(zero, 4000, 3000, 10L)
        assertEquals(ChatImageCompressionDecision.Compress(512, 80), decision)
    }

    @Test
    fun `归一化夹边界`() {
        val wild = ChatImageCompression(enabled = true, maxEdge = 1, quality = 999, thresholdKb = -5)
        val n = wild.normalized()
        assertEquals(ChatImageCompression.MIN_MAX_EDGE, n.maxEdge)
        assertEquals(ChatImageCompression.MAX_QUALITY, n.quality)
        assertEquals(ChatImageCompression.MIN_THRESHOLD_KB, n.thresholdKb)
    }

    @Test
    fun `默认值等于既有硬编码（512 与 80）`() {
        val d = ChatImageCompression()
        assertEquals(512, d.maxEdge)
        assertEquals(80, d.quality)
        // 默认关：改写用户的图是有副作用的操作，不该默认发生。
        assertEquals(false, d.enabled)
    }
}
