package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 解析器的边界行为。
 *
 * 这些用例的价值在于：它们用的是**真实分片形状**（在任意位置切断），
 * 而不是「一次性喂完整字符串」—— 后者永远不会暴露跨 chunk 的 bug。
 */
class SseParserTest {

    @Test
    fun `single complete event is parsed`() {
        val p = SseParser()
        val out = p.feed("data: {\"a\":1}\n\n")
        assertEquals(listOf("{\"a\":1}"), out)
    }

    @Test
    fun `event split across chunks is not lost`() {
        val p = SseParser()
        // 在 "data:" 中间切开 —— 正则方案在这里必定丢数据。
        assertEquals(emptyList<String>(), p.feed("dat"))
        assertEquals(emptyList<String>(), p.feed("a: {\"a\":"))
        assertEquals(listOf("{\"a\":1}"), p.feed("1}\n\n"))
    }

    @Test
    fun `multiple events in one chunk are all returned in order`() {
        val p = SseParser()
        val out = p.feed("data: one\n\ndata: two\n\ndata: three\n\n")
        assertEquals(listOf("one", "two", "three"), out)
    }

    @Test
    fun `DONE marker is not emitted as data`() {
        val p = SseParser()
        val out = p.feed("data: {\"x\":1}\n\ndata: [DONE]\n\n")
        assertEquals(listOf("{\"x\":1}"), out)
    }

    @Test
    fun `CRLF boundaries are handled`() {
        val p = SseParser()
        val out = p.feed("data: hi\r\n\r\n")
        assertEquals(listOf("hi"), out)
    }

    @Test
    fun `trailing event without blank line is flushed by finish`() {
        val p = SseParser()
        // 有些兼容实现最后不发空行。
        assertEquals(emptyList<String>(), p.feed("data: tail"))
        assertEquals(listOf("tail"), p.finish())
    }

    @Test
    fun `event and id fields are ignored`() {
        val p = SseParser()
        val out = p.feed("event: message\nid: 7\ndata: body\n\n")
        assertEquals(listOf("body"), out)
    }

    @Test
    fun `multi-line data is joined with newline`() {
        val p = SseParser()
        val out = p.feed("data: line1\ndata: line2\n\n")
        assertEquals(listOf("line1\nline2"), out)
    }

    @Test
    fun `empty stream yields nothing`() {
        val p = SseParser()
        assertTrue(p.feed("").isEmpty())
        assertTrue(p.finish().isEmpty())
    }

    @Test
    fun `oversize pending without boundary is dropped and flagged`() {
        val p = SseParser()
        // 模拟异常网关：只吐字节，永不发空行，缓冲会一直涨。
        val junk = "x".repeat(SseParser.MAX_PENDING_CHARS + 1)
        assertTrue(p.feed(junk).isEmpty())
        assertTrue(p.didDropOversize())
    }

    @Test
    fun `complete events before oversize junk are still salvaged`() {
        val p = SseParser()
        val junk = "y".repeat(SseParser.MAX_PENDING_CHARS + 1)
        // 关键语义：超长保护不能把「已经完整的事件」一起吞掉。
        assertEquals(listOf("ok"), p.feed("data: ok\n\n" + junk))
        assertTrue(p.didDropOversize())
    }

    @Test
    fun `parser keeps working after dropping oversize junk`() {
        val p = SseParser()
        p.feed("z".repeat(SseParser.MAX_PENDING_CHARS + 1))
        assertTrue(p.didDropOversize())
        // 丢弃后缓冲已清空，后续正常流必须继续可用。
        assertEquals(listOf("{\"a\":1}"), p.feed("data: {\"a\":1}\n\n"))
    }
}
