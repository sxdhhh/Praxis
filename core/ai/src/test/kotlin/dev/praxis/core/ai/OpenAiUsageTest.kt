package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 上游 usage 的解析（网关报表与 token 预算的数据源）。 */
class OpenAiUsageTest {

    @Test
    fun `标准的 OpenAI 用量`() {
        val body = """{"usage":{"prompt_tokens":12,"completion_tokens":34}}"""
        val usage = OpenAiUsage.parseJson(body)
        assertEquals(12, usage?.promptTokens)
        assertEquals(34, usage?.completionTokens)
        assertEquals(0, usage?.cacheReadTokens)
    }

    @Test
    fun `Anthropic 风格的缓存字段`() {
        val body = """{"usage":{"prompt_tokens":10,"completion_tokens":5,"cache_read_input_tokens":7}}"""
        assertEquals(7, OpenAiUsage.parseJson(body)?.cacheReadTokens)
    }

    @Test
    fun `prompt_tokens_details 里的缓存字段也认`() {
        val body = """{"usage":{"prompt_tokens":10,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":3}}}"""
        assertEquals(3, OpenAiUsage.parseJson(body)?.cacheReadTokens)
    }

    @Test
    fun `上游没给 usage 时返回 null 而不是 0`() {
        // 「没有数据」与「用了 0 token」在报表里是两回事。
        assertNull(OpenAiUsage.parseJson("""{"choices":[]}"""))
        assertNull(OpenAiUsage.parseJson("not json"))
        assertNull(OpenAiUsage.parseJson(""))
    }

    @Test
    fun `usage 是空壳时按没给处理`() {
        // 否则报表会出现一堆「用了 0 token」的记录，污染均值。
        assertNull(OpenAiUsage.parseJson("""{"usage":{}}"""))
        assertNull(OpenAiUsage.parseJson("""{"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))
    }

    @Test
    fun `只有一个字段时也认`() {
        val usage = OpenAiUsage.parseJson("""{"usage":{"completion_tokens":9}}""")
        assertEquals(0, usage?.promptTokens)
        assertEquals(9, usage?.completionTokens)
    }

    @Test
    fun `usage 不是对象时不算崩`() {
        assertNull(OpenAiUsage.parseJson("""{"usage":"none"}"""))
        assertNull(OpenAiUsage.parseJson("""{"usage":123}"""))
    }
}
