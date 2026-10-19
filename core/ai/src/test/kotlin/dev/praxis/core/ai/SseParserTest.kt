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
}
