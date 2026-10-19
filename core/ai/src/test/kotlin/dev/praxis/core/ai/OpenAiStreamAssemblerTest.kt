package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组装器的核心断言：工具调用必须**先累积完整再吐出**。
 *
 * 这里刻意模拟上游的真实分片方式（id/name 只在第一片，arguments 逐片追加），
 * 因为「每片发一个 ToolCall」这个错误在真机上表现为「偶发 400」，极难复现。
 */
class OpenAiStreamAssemblerTest {

    @Test
    fun `text deltas are emitted as they arrive`() {
        val a = OpenAiStreamAssembler()
        val e1 = a.feed(delta("你"))
        val e2 = a.feed(delta("好"))

        assertEquals(listOf(AiStreamEvent.Delta("你")), e1)
        assertEquals(listOf(AiStreamEvent.Delta("好")), e2)
        assertEquals("你好", a.accumulatedText())
    }

    @Test
    fun `empty content chunk produces no event`() {
        val a = OpenAiStreamAssembler()
        assertTrue(a.feed(delta("")).isEmpty())
    }

    @Test
    fun `tool call fragments are accumulated and emitted only at finish`() {
        val a = OpenAiStreamAssembler()

        // 第一片：id + name，arguments 为空
        a.feed(toolChunk("""{"index":0,"id":"call_a","function":{"name":"read_file","arguments":""}}"""))
        // 后续片：只有 arguments 增量，没有 id/name
        a.feed(toolChunk("""{"index":0,"function":{"arguments":"{\"path\":"}}"""))
        a.feed(toolChunk("""{"index":0,"function":{"arguments":"\"/sdcard/a.txt\"}"}}"""))

        val events = a.finish()
        val toolCalls = events.filterIsInstance<AiStreamEvent.ToolCalls>().single()

        assertEquals(1, toolCalls.calls.size)
        assertEquals("call_a", toolCalls.calls[0].id)
        assertEquals("read_file", toolCalls.calls[0].name)
        // 关键：拼接后的参数必须是完整合法 JSON，一个字符都不能少
        assertEquals("{\"path\":\"/sdcard/a.txt\"}", toolCalls.calls[0].argumentsJson)
    }

    @Test
    fun `two parallel tool calls stay separated by index`() {
        val a = OpenAiStreamAssembler()
        a.feed(toolChunk("""{"index":0,"id":"c0","function":{"name":"a","arguments":"{"}}"""))
        a.feed(toolChunk("""{"index":1,"id":"c1","function":{"name":"b","arguments":"{"}}"""))
        a.feed(toolChunk("""{"index":0,"function":{"arguments":"}"}}"""))
        a.feed(toolChunk("""{"index":1,"function":{"arguments":"}"}}"""))

        val calls = a.finish().filterIsInstance<AiStreamEvent.ToolCalls>().single().calls
        assertEquals(2, calls.size)
        assertEquals("c0", calls[0].id)
        assertEquals("{}", calls[0].argumentsJson)
        assertEquals("c1", calls[1].id)
        assertEquals("{}", calls[1].argumentsJson)
    }

    @Test
    fun `ToolCalls event comes before Done`() {
        val a = OpenAiStreamAssembler()
        a.feed(toolChunk("""{"index":0,"id":"c0","function":{"name":"x","arguments":"{}"}}"""))

        val events = a.finish()
        assertTrue(events[0] is AiStreamEvent.ToolCalls)
        assertTrue(events.last() is AiStreamEvent.Done)
    }

    @Test
    fun `finish reason is captured`() {
        val a = OpenAiStreamAssembler()
        a.feed("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        val done = a.finish().filterIsInstance<AiStreamEvent.Done>().single()
        assertEquals("stop", done.finishReason)
    }

    @Test
    fun `malformed payload is ignored instead of throwing`() {
        val a = OpenAiStreamAssembler()
        // 半截 JSON 不应该让整个流崩掉
        assertTrue(a.feed("{\"choices\":[").isEmpty())
        assertTrue(a.finish().isNotEmpty()) // 仍然能给 Done
    }

    @Test
    fun `unknown fields do not break parsing`() {
        val a = OpenAiStreamAssembler()
        val events = a.feed(
            """{"id":"x","object":"chat.completion.chunk","choices":[{"delta":{"content":"hi"},"index":0}]}"""
        )
        assertEquals(listOf(AiStreamEvent.Delta("hi")), events)
    }

    private fun delta(text: String) =
        """{"choices":[{"delta":{"content":"$text"},"finish_reason":null}]}"""

    private fun toolChunk(toolCall: String) =
        """{"choices":[{"delta":{"tool_calls":[$toolCall]},"finish_reason":null}]}"""
}
