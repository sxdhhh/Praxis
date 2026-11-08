package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 添加账号草稿的校验与归一化（文档 §9.3）。 */
class GatewayAccountDraftTest {

    private fun draft(
        provider: String = "custom-abc",
        baseUrl: String = "https://api.example.com/v1",
        apiKey: String = "sk-1",
        name: String = "",
    ) = GatewayAccountDraft(providerId = provider, name = name, baseUrl = baseUrl, apiKey = apiKey)

    @Test
    fun `填写完整时通过`() {
        assertNull(GatewayAccountDraftRules.validate(draft()))
    }

    @Test
    fun `没选渠道时报 MissingProvider`() {
        assertEquals(
            GatewayAccountDraftError.MissingProvider,
            GatewayAccountDraftRules.validate(draft(provider = "  ")),
        )
    }

    @Test
    fun `没填地址时报 MissingBaseUrl`() {
        assertEquals(
            GatewayAccountDraftError.MissingBaseUrl,
            GatewayAccountDraftRules.validate(draft(baseUrl = "")),
        )
    }

    @Test
    fun `只填域名时按协议缺失处理`() {
        // 这是最常见的少写：用户以为「api.example.com」就够了。
        assertEquals(
            GatewayAccountDraftError.BadBaseUrlScheme,
            GatewayAccountDraftRules.validate(draft(baseUrl = "api.example.com/v1")),
        )
    }

    @Test
    fun `只有协议没有主机也不算地址`() {
        assertEquals(
            GatewayAccountDraftError.BadBaseUrlScheme,
            GatewayAccountDraftRules.validate(draft(baseUrl = "https://")),
        )
    }

    @Test
    fun `没填 Key 时报 MissingApiKey`() {
        assertEquals(
            GatewayAccountDraftError.MissingApiKey,
            GatewayAccountDraftRules.validate(draft(apiKey = "   ")),
        )
    }

    @Test
    fun `归一化去掉首尾空白与地址尾部斜杠`() {
        val normalized = GatewayAccountDraftRules.normalize(
            draft(provider = " custom-abc ", baseUrl = "  https://api.example.com/v1/  ", apiKey = " sk-1 "),
        )
        assertEquals("custom-abc", normalized.providerId)
        assertEquals("https://api.example.com/v1", normalized.baseUrl)
        assertEquals("sk-1", normalized.apiKey)
    }

    @Test
    fun `名字留空时用 provider 顶上`() {
        assertEquals("custom-abc", GatewayAccountDraftRules.normalize(draft(name = "  ")).name)
    }

    @Test
    fun `名字填了就保留`() {
        assertEquals("我的中转", GatewayAccountDraftRules.normalize(draft(name = " 我的中转 ")).name)
    }

    @Test
    fun `内置家现在没有默认地址`() {
        // 内置家清单要等 P5；在那之前「选内置家就能不填地址」不成立，页面上必须要求填。
        assertTrue(!GatewayAccountDraftRules.hasDefaultBaseUrl("openai"))
    }
}
