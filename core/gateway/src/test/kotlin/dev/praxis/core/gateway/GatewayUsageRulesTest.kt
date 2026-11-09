package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/** 请求记录的构造规则（文档 6.1 / §5.7）。 */
class GatewayUsageRulesTest {

    private fun draft(
        prompt: Int = 10,
        completion: Int = 20,
        cache: Int = 3,
    ) = GatewayUsageDraft(
        model = "gpt-x",
        upstreamModel = "gpt-x-0613",
        providerId = "openai",
        accountId = "a1",
        status = GatewayUsageRules.STATUS_OK,
        error = null,
        attempts = 1,
        promptTokens = prompt,
        completionTokens = completion,
        cacheReadTokens = cache,
        durationMs = 1234L,
    )

    @Test
    fun `成功时保留用量`() {
        val built = GatewayUsageRules.build(draft(), success = true)
        assertEquals(10, built.promptTokens)
        assertEquals(20, built.completionTokens)
        assertEquals(3, built.cacheReadTokens)
    }

    @Test
    fun `失败时三个 token 全部清零`() {
        // 留着半截会让「失败暴增」看起来像「用量暴增」。
        val built = GatewayUsageRules.build(draft(), success = false)
        assertEquals(0, built.promptTokens)
        assertEquals(0, built.completionTokens)
        assertEquals(0, built.cacheReadTokens)
    }

    @Test
    fun `清零不动其它字段`() {
        val built = GatewayUsageRules.build(draft(), success = false)
        assertEquals("gpt-x", built.model)
        assertEquals("openai", built.providerId)
        assertEquals(1, built.attempts)
        assertEquals(1234L, built.durationMs)
    }

    @Test
    fun `状态码常量符合文档`() {
        assertEquals(200, GatewayUsageRules.STATUS_OK)
        assertEquals(0, GatewayUsageRules.STATUS_NO_RESPONSE)
    }
}
