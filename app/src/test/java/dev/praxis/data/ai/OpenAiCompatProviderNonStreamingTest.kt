package dev.praxis.data.ai

import dev.praxis.core.ai.AiErrorKind
import dev.praxis.core.ai.AiException
import dev.praxis.core.ai.AiStreamEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 非流式分支的两个新行为（文档 §7.4 的产出点）。
 *
 * 内容拦截在真实上游里几乎总是 **HTTP 200 + finish_reason: content_filter**，
 * 所以「只按状态码分类」永远命中不了这一类 —— 这两条测试守的就是那个产出点。
 */
class OpenAiCompatProviderNonStreamingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun body(finishReason: String, content: String = "", toolCalls: String = ""): String {
        val tools = if (toolCalls.isEmpty()) "" else ",\"tool_calls\":" + toolCalls
        return "{\"choices\":[{\"finish_reason\":\"" + finishReason +
            "\",\"message\":{\"content\":\"" + content + "\"" + tools + "}}]}"
    }

    @Test
    fun `finish_reason 是 content_filter 且没有正文时抛内容拦截`() {
        var caught: AiException? = null
        try {
            OpenAiCompatProvider.emitNonStreaming(body("content_filter"), json)
        } catch (e: AiException) {
            caught = e
        }
        assertTrue("应当抛 AiException", caught != null)
        assertEquals(AiErrorKind.CONTENT_BLOCKED, caught!!.error.kind)
    }

    @Test
    fun `已经有正文时不抛而是保留结果`() {
        // 丢掉已经生成的内容去换家，只会让用户白等一次。
        val events = OpenAiCompatProvider.emitNonStreaming(body("content_filter", content = "半截回答"), json)
        assertEquals(2, events.size)
        assertTrue(events[0] is AiStreamEvent.Delta)
        assertTrue(events[1] is AiStreamEvent.Done)
        assertEquals("content_filter", (events[1] as AiStreamEvent.Done).finishReason)
    }

    @Test
    fun `正常响应仍是 Delta 加 Done`() {
        val events = OpenAiCompatProvider.emitNonStreaming(body("stop", content = "你好"), json)
        assertEquals(2, events.size)
        assertEquals("你好", (events[0] as AiStreamEvent.Delta).text)
        assertEquals("stop", (events[1] as AiStreamEvent.Done).finishReason)
    }

    @Test
    fun `只有工具调用时不算拦截`() {
        val toolCalls = "[{\"id\":\"1\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]"
        val events = OpenAiCompatProvider.emitNonStreaming(body("tool_calls", toolCalls = toolCalls), json)
        assertTrue(events.any { it is AiStreamEvent.ToolCalls })
        assertTrue(events.last() is AiStreamEvent.Done)
    }

    @Test
    fun `缺少 choices 时仍是协议错而不是静默空结果`() {
        var caught: AiException? = null
        try {
            OpenAiCompatProvider.emitNonStreaming("{\"id\":\"x\"}", json)
        } catch (e: AiException) {
            caught = e
        }
        assertEquals(AiErrorKind.PROTOCOL, caught?.error?.kind)
    }
}
