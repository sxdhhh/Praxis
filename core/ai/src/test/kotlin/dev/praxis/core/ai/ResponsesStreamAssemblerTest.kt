package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Responses 流式组装（文档 §5.1）。 */
class ResponsesStreamAssemblerTest {

    @Test
    fun `正文增量变成 Delta`() {
        val events = ResponsesStreamAssembler().feed(
            """{"type":"response.output_text.delta","delta":"你好"}""",
        )
        assertEquals("你好", (events[0] as AiStreamEvent.Delta).text)
    }

    @Test
    fun `思考增量走 ReasoningDelta`() {
        val events = ResponsesStreamAssembler().feed(
            """{"type":"response.reasoning_summary_text.delta","delta":"想想"}""",
        )
        assertTrue(events[0] is AiStreamEvent.ReasoningDelta)
    }

    @Test
    fun `用量在 response_completed 里一次给全`() {
        // 与 Anthropic 不同：那边是「开头给输入、结尾给输出」，这里天然是全的。
        val assembler = ResponsesStreamAssembler()
        val during = assembler.feed(
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":30,"output_tokens":12,"input_tokens_details":{"cached_tokens":8}}}}""",
        )
        assertTrue("完成事件本身不该直接吐用量", during.none { it is AiStreamEvent.Usage })
        val usage = assembler.finish().filterIsInstance<AiStreamEvent.Usage>().single()
        assertEquals(30, usage.promptTokens)
        assertEquals(12, usage.completionTokens)
        assertEquals(8, usage.cacheReadTokens)
    }

    @Test
    fun `状态变成停止原因`() {
        val assembler = ResponsesStreamAssembler()
        assembler.feed("""{"type":"response.completed","response":{"status":"incomplete"}}""")
        assertEquals("incomplete", assembler.stopReason())
    }

    @Test
    fun `工具参数按 item_id 累积`() {
        val assembler = ResponsesStreamAssembler()
        assembler.feed(
            """{"type":"response.output_item.added","item":{"id":"it_1","type":"function_call","call_id":"call_1","name":"read_file"}}""",
        )
        val first = assembler.feed(
            """{"type":"response.function_call_arguments.delta","item_id":"it_1","delta":"{\"pa"}""",
        )
        val second = assembler.feed(
            """{"type":"response.function_call_arguments.delta","item_id":"it_1","delta":"th\":\"/x\"}"}""",
        )
        assertEquals(4, (first[0] as AiStreamEvent.ToolCallDelta).argumentsChars)
        assertEquals(13, (second[0] as AiStreamEvent.ToolCallDelta).argumentsChars)
        assertEquals("read_file", (second[0] as AiStreamEvent.ToolCallDelta).toolName)
    }

    @Test
    fun `两个工具并行时互不干扰`() {
        // Chat 那边靠数组下标，这里靠 item_id —— 这正是要测的点。
        val assembler = ResponsesStreamAssembler()
        assembler.feed(
            """{"type":"response.output_item.added","item":{"id":"it_1","type":"function_call","call_id":"c1","name":"a"}}""",
        )
        assembler.feed(
            """{"type":"response.output_item.added","item":{"id":"it_2","type":"function_call","call_id":"c2","name":"b"}}""",
        )
        assembler.feed("""{"type":"response.function_call_arguments.delta","item_id":"it_1","delta":"{\"x\":1}"}""")
        assembler.feed("""{"type":"response.function_call_arguments.delta","item_id":"it_2","delta":"{\"y\":2}"}""")
        val calls = assembler.finish().filterIsInstance<AiStreamEvent.ToolCalls>().single().calls
        assertEquals(2, calls.size)
        assertEquals("a", calls[0].name)
        assertEquals("""{"x":1}""", calls[0].argumentsJson)
        assertEquals("b", calls[1].name)
        assertEquals("""{"y":2}""", calls[1].argumentsJson)
    }

    @Test
    fun `没有参数的工具给空对象`() {
        val assembler = ResponsesStreamAssembler()
        assembler.feed(
            """{"type":"response.output_item.added","item":{"id":"it_1","type":"function_call","call_id":"c1","name":"now"}}""",
        )
        val call = assembler.finish().filterIsInstance<AiStreamEvent.ToolCalls>().single().calls[0]
        assertEquals("{}", call.argumentsJson)
    }

    @Test
    fun `失败事件被记下来`() {
        val assembler = ResponsesStreamAssembler()
        assembler.feed("""{"type":"response.failed","error":{"message":"超载"}}""")
        assertEquals("超载", assembler.errorMessage())
    }

    @Test
    fun `没有用量时不发 Usage 事件`() {
        val assembler = ResponsesStreamAssembler()
        assembler.feed("""{"type":"response.output_text.delta","delta":"hi"}""")
        assertTrue(assembler.finish().none { it is AiStreamEvent.Usage })
    }

    @Test
    fun `坏 payload 与未知事件不影响后续`() {
        val assembler = ResponsesStreamAssembler()
        assertTrue(assembler.feed("不是 JSON").isEmpty())
        assertTrue(assembler.feed("""{"type":"response.created"}""").isEmpty())
        assertEquals("好", (assembler.feed("""{"type":"response.output_text.delta","delta":"好"}""")[0] as AiStreamEvent.Delta).text)
    }

    @Test
    fun `永远以 Done 收尾`() {
        val events = ResponsesStreamAssembler().finish()
        assertTrue(events.last() is AiStreamEvent.Done)
        assertNull(ResponsesStreamAssembler().stopReason())
    }
}
