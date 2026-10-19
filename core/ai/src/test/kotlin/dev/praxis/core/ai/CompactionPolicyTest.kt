package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩策略的四条不变量。
 *
 * 这些断言直接对应「上游会不会因为上下文被压缩而 400」这件事，
 * 属于回归护栏，不是形式主义：改常量、改切点、改省略号格式都必须同步想清楚。
 */
class CompactionPolicyTest {

    @Test
    fun `text within budget is returned verbatim`() {
        val short = "a".repeat(CompactionPolicy.TEXT_HEAD + CompactionPolicy.TEXT_TAIL)

        assertEquals(short, CompactionPolicy.compactText(short))
    }

    @Test
    fun `long text keeps head and tail and reports omitted count`() {
        val head = "H".repeat(CompactionPolicy.TEXT_HEAD)
        val middle = "M".repeat(500)
        val tail = "T".repeat(CompactionPolicy.TEXT_TAIL)
        val long = head + middle + tail

        val compacted = CompactionPolicy.compactText(long)

        assertTrue("必须保留首部", compacted.startsWith(head))
        assertTrue("必须保留尾部", compacted.endsWith(tail))
        assertFalse("中段必须被裁掉", compacted.contains(middle))
        assertTrue("中段字符数应被标注", compacted.contains("500"))
    }

    @Test
    fun `tool result uses its own tighter budget`() {
        val head = "H".repeat(CompactionPolicy.TOOL_HEAD)
        val tail = "T".repeat(CompactionPolicy.TOOL_TAIL)
        val body = "M".repeat(300)
        val long = head + body + tail

        val compacted = CompactionPolicy.compactToolResult(long)

        assertTrue(compacted.startsWith(head))
        assertTrue(compacted.endsWith(tail))
        assertFalse(compacted.contains(body))
    }

    @Test
    fun `arguments json is never truncated`() {
        // 长 JSON 极易被误当成普通文本裁掉，导致上游解析失败，这里必须原样返回。
        val json = """{"path":"/sdcard/Download/very-long-file-name.txt","note":"${"x".repeat(2000)}"}"""

        val compacted = CompactionPolicy.compactArguments(json)

        assertSame(json, compacted)
        assertEquals(json.length, compacted.length)
    }

    @Test
    fun `surrogate pairs are not split`() {
        // emoji 是代理对，切点落在低位代理上会产出非法字符串。
        val emoji = "😀"
        val text = emoji.repeat(400)

        val compacted = CompactionPolicy.compactText(text)

        assertTrue(compacted.startsWith(emoji))
        assertTrue(compacted.endsWith(emoji))
        // 首尾字符完整 → 不含孤立代理。
        assertFalse(compacted.first().isLowSurrogate())
        assertFalse(compacted.last().isHighSurrogate())
    }

    private fun Char.isLowSurrogate(): Boolean = this in '\uDC00'..'\uDFFF'
    private fun Char.isHighSurrogate(): Boolean = this in '\uD800'..'\uDBFF'
}