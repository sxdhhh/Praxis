package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anthropic 流式组装（文档 §5.1）。 */
class AnthropicStreamAssemblerTest {

    private fun textChunk(text: String) =
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""

    @Test
    fun `正文增量变成 Delta`() {
        val events = AnthropicStreamAssembler().feed(textChunk("你好"))
        assertEquals(1, events.size)
        assertEquals("你好", (events[0] as AiStreamEvent.Delta).text)
    }

    @Test
    fun `思考增量走 ReasoningDelta 而不是 Delta`() {
        // 思考不进历史、不进记忆，混进 Delta 会让这两件事都要靠解析文本区分。
        val events = AnthropicStreamAssembler().feed(
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"想想"}}""",
        )
        assertTrue(events[0] is AiStreamEvent.ReasoningDelta)
        assertEquals("想想", (events[0] as AiStreamEvent.ReasoningDelta).text)
    }

    @Test
    fun `输入用量在 message_start 里`() {
        val assembler = AnthropicStreamAssembler()
        assembler.feed(
            """{"type":"message_start","message":{"usage":{"input_tokens":25,"cache_read_input_tokens":7}}}""",
        )
        val usage = assembler.finish().filterIsInstance<AiStreamEvent.Usage>().single()
        assertEquals(25, usage.promptTokens)
        assertEquals(7, usage.cacheReadTokens)
    }

    @Test
    fun `输出用量在 message_delta 里`() {
        val assembler = AnthropicStreamAssembler()
        assembler.feed(
            """{"type":"message_start","message":{"usage":{"input_tokens":10}}}""",
        )
        assembler.feed(
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":15}}""",
        )
        val usage = assembler.finish().filterIsInstance<AiStreamEvent.Usage>().single()
        assertEquals(10, usage.promptTokens)
        assertEquals(15, usage.completionTokens)
    }

    @Test
    fun `用量只在 finish 报一次而不是中途报`() {
        // 输入在开头、输出在结尾，中途报任何一次都不全。
        val assembler = AnthropicStreamAssembler()
        val during = mutableListOf<AiStreamEvent>()
        during += assembler.feed(
            """{"type":"message_start","message":{"usage":{"input_tokens":10}}}""",
        )
        during += assembler.feed(
            """{"type":"message_delta","delta":{},"usage":{"output_tokens":5}}""",
        )
        assertTrue("中途不该有用量事件", during.none { it is AiStreamEvent.Usage })
        assertEquals(1, assembler.finish().count { it is AiStreamEvent.Usage })
    }

    @Test
    fun `工具参数分片累积并给进度事件`() {
        val assembler = AnthropicStreamAssembler()
        assembler.feed(
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"read_file"}}""",
        )
        val first = assembler.feed(
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"pa"}}""",
        )
        val second = assembler.feed(
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"th\":\"/x\"}"}}""",
        )
        // 两片合起来是 {"path":"/x"} 共 13 个字符：第一片 4、累计 13。
        assertEquals(4, (first[0] as AiStreamEvent.ToolCallDelta).argumentsChars)
        assertEquals(13, (second[0] as AiStreamEvent.ToolCallDelta).argumentsChars)
        assertEquals("read_file", (second[0] as AiStreamEvent.ToolCallDelta).toolName)
    }

    @Test
    fun `工具调用在 finish 收齐`() {
        val assembler = AnthropicStreamAssembler()
        assembler.feed(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tu_1","name":"read_file"}}""",
        )
        assembler.feed(
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"/x\"}"}}""",
        )
        val calls = assembler.finish().filterIsInstance<AiStreamEvent.ToolCalls>().single().calls
        assertEquals(1, calls.size)
        assertEquals("tu_1", calls[0].id)
        assertEquals("read_file", calls[0].name)
        assertEquals("""{"path":"/x"}""", calls[0].argumentsJson)
    }

    @Test
    fun `没有参数的工具给空对象`() {
        val assembler = AnthropicStreamAssembler()
        assembler.feed(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"now"}}""",
        )
        val call = assembler.finish().filterIsInstance<AiStreamEvent.ToolCalls>().single().calls[0]
        assertEquals("{}", call.argumentsJson)
    }

    @Test
    fun `停止原因从 message_delta 取`() {
        val assembler = AnthropicStreamAssembler()
        assembler.feed("""{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""")
        assertEquals("max_tokens", assembler.stopReason())
        val done = assembler.finish().filterIsInstance<AiStreamEvent.Done>().single()
        assertEquals("max_tokens", done.finishReason)
    }

    @Test
    fun `错误事件被记下来`() {
        val assembler = AnthropicStreamAssembler()
        assembler.feed("""{"type":"error","error":{"type":"overloaded_error","message":"太忙了"}}""")
        assertEquals("太忙了", assembler.errorMessage())
    }

    @Test
    fun `坏 payload 与 ping 不影响后续`() {
        val assembler = AnthropicStreamAssembler()
        assertTrue(assembler.feed("不是 JSON").isEmpty())
        assertTrue(assembler.feed("""{"type":"ping"}""").isEmpty())
        assertEquals("好", (assembler.feed(textChunk("好"))[0] as AiStreamEvent.Delta).text)
    }

    @Test
    fun `没有用量时不发 Usage 事件`() {
        // 「没有数据」与「用了 0 token」是两回事。
        val assembler = AnthropicStreamAssembler()
        assembler.feed(textChunk("hi"))
        assertTrue(assembler.finish().none { it is AiStreamEvent.Usage })
        assertNull(assembler.errorMessage())
    }

    @Test
    fun `永远以 Done 收尾`() {
        val events = AnthropicStreamAssembler().finish()
        assertTrue(events.last() is AiStreamEvent.Done)
    }
}
