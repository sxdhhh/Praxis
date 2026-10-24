package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI 标准 toolcall 协议的组装行为锁定。
 *
 * ## 这些断言对应三个**真实会造成线上故障**的缺陷（2026-10-22 修）
 *
 * 每一条都对应一种「用户以为是 AI 笨」的表象：
 *
 * | 缺陷 | 表象 |
 * |---|---|
 * | assistant 的 tool_calls 分片未按 index 累积 | 回传半截 JSON，上游 400 |
 * | 无 id 时兜底 `call_<name>` 撞车 | 同名工具并行 → duplicate tool_call_id |
 * | 空 arguments 原样回传 | 无参工具必 400（arguments 不是合法 JSON） |
 *
 * 前两条在 [OpenAiStreamAssembler]（本模块，纯 JVM 可测）；
 * 第三条在 provider 的序列化层（Android 模块），这里只锁定
 * 「组装器原样给出空串」这一事实，避免两处都补而掩盖空串来源。
 */
class OpenAiToolCallProtocolTest {

    private fun payload(delta: String, finish: String? = null): String =
        """{"choices":[{"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}"""

    @Test
    fun `分片 arguments 按 index 累积成完整 JSON`() {
        val a = OpenAiStreamAssembler()
        a.feed(payload("""{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"read_file","arguments":""}}]}"""))
        a.feed(payload("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}"""))
        a.feed(payload("""{"tool_calls":[{"index":0,"function":{"arguments":"\"/sdcard/a.txt\"}"}}]}"""))

        val calls = a.finish().filterIsInstance<AiStreamEvent.ToolCalls>().single().calls
        assertEquals(1, calls.size)
        assertEquals("call_a", calls[0].id)
        assertEquals("read_file", calls[0].name)
        assertEquals("""{"path":"/sdcard/a.txt"}""", calls[0].argumentsJson)
    }

    @Test
    fun `多个工具调用按 index 分别累积`() {
        val a = OpenAiStreamAssembler()
        a.feed(payload("""{"tool_calls":[{"index":0,"id":"c0","function":{"name":"read_file","arguments":""}},{"index":1,"id":"c1","function":{"name":"list_dir","arguments":""}}]}"""))
        a.feed(payload("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":\"a\"}"}}]}"""))
        a.feed(payload("""{"tool_calls":[{"index":1,"function":{"arguments":"{\"path\":\"b\"}"}}]}"""))

        val calls = a.finish().filterIsInstance<AiStreamEvent.ToolCalls>().single().calls
        assertEquals(2, calls.size)
        assertEquals("c0", calls[0].id)
        assertEquals("""{"path":"a"}""", calls[0].argumentsJson)
        assertEquals("c1", calls[1].id)
        assertEquals("""{"path":"b"}""", calls[1].argumentsJson)
    }

    @Test
    fun `无参工具累积出的 arguments 是空串`() {
        val a = OpenAiStreamAssembler()
        a.feed(payload("""{"tool_calls":[{"index":0,"id":"call_n","type":"function","function":{"name":"linux_status","arguments":""}}]}"""))

        val calls = a.finish().filterIsInstance<AiStreamEvent.ToolCalls>().single().calls
        assertEquals("linux_status", calls[0].name)
        // ★ 组装器设计上不做 trim/补全。补成 `{}` 是序列化层的责任。
        //   若这里改成 `{}`，说明有人补错了位置 —— 会在序列化层掩盖真正的空串来源。
        assertEquals("", calls[0].argumentsJson)
    }

    @Test
    fun `缺少 id 时兜底 id 带 index 不会撞车`() {
        val a = OpenAiStreamAssembler()
        a.feed(payload("""{"tool_calls":[{"index":0,"function":{"name":"read_file","arguments":"{\"path\":\"a\"}"}}]}"""))
        a.feed(payload("""{"tool_calls":[{"index":1,"function":{"name":"read_file","arguments":"{\"path\":\"b\"}"}}]}"""))

        val calls = a.finish().filterIsInstance<AiStreamEvent.ToolCalls>().single().calls
        assertEquals(2, calls.size)
        assertEquals("同名工具的两个调用必须有不同的 id", 2, calls.map { it.id }.toSet().size)
        assertTrue(calls[0].id.startsWith("call_"))
    }

    @Test
    fun `文本增量与工具调用可以同时存在`() {
        val a = OpenAiStreamAssembler()
        a.feed(payload("""{"content":"我来读一下"}"""))
        a.feed(payload("""{"tool_calls":[{"index":0,"id":"c0","function":{"name":"read_file","arguments":"{}"}}]}"""))

        val events = a.finish()
        assertTrue(events.filterIsInstance<AiStreamEvent.Delta>().any { it.text.contains("读一下") })
        assertEquals(1, events.filterIsInstance<AiStreamEvent.ToolCalls>().single().calls.size)
    }

    @Test
    fun `先 ToolCalls 后 Done`() {
        val a = OpenAiStreamAssembler()
        a.feed(payload("""{"tool_calls":[{"index":0,"id":"c0","function":{"name":"t","arguments":"{}"}}]}""", finish = "tool_calls"))

        val events = a.finish()
        val toolIdx = events.indexOfFirst { it is AiStreamEvent.ToolCalls }
        val doneIdx = events.indexOfFirst { it is AiStreamEvent.Done }
        assertTrue("ToolCalls 必须在 Done 之前", toolIdx in 0 until doneIdx)
        assertEquals("tool_calls", (events[doneIdx] as AiStreamEvent.Done).finishReason)
    }

    @Test
    fun `非法 JSON 负载不会抛异常`() {
        val a = OpenAiStreamAssembler()
        a.feed("")
        a.feed("not json")
        a.feed("{}")
        assertTrue(a.finish().any { it is AiStreamEvent.Done })
    }
}