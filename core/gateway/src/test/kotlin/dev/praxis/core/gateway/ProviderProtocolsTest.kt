package dev.praxis.core.gateway

import dev.praxis.core.ai.AnthropicRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 家 → 协议的分派（文档 §5.1）。 */
class ProviderProtocolsTest {

    @Test
    fun `anthropic 与 claude 走 Messages`() {
        assertEquals(ProviderProtocol.AnthropicMessages, ProviderProtocols.of("anthropic"))
        assertEquals(ProviderProtocol.AnthropicMessages, ProviderProtocols.of("Claude"))
        assertEquals(ProviderProtocol.AnthropicMessages, ProviderProtocols.of("  ANTHROPIC  "))
    }

    @Test
    fun `其余家按 OpenAI 兼容`() {
        // 这不是「失败就退回默认」：OpenAI 兼容是事实上的行业基线，
        // 而 Anthropic 是明确的分叉。
        assertEquals(ProviderProtocol.OpenAiChat, ProviderProtocols.of("openai"))
        assertEquals(ProviderProtocol.OpenAiChat, ProviderProtocols.of("deepseek"))
        assertEquals(ProviderProtocol.OpenAiChat, ProviderProtocols.of("我自己搭的中转"))
    }

    @Test
    fun `两家的鉴权头不同`() {
        val openai = ProviderProtocols.authHeaders(ProviderProtocol.OpenAiChat, "sk-1")
        assertEquals("Bearer sk-1", openai["Authorization"])
        assertFalse("OpenAI 不该带 x-api-key", openai.containsKey("x-api-key"))

        val anthropic = ProviderProtocols.authHeaders(ProviderProtocol.AnthropicMessages, "sk-2")
        assertEquals("sk-2", anthropic["x-api-key"])
        assertEquals(AnthropicRequestBuilder.API_VERSION, anthropic["anthropic-version"])
        assertFalse("Anthropic 不该带 Bearer", anthropic.containsKey("Authorization"))
    }

    @Test
    fun `请求路径不同`() {
        assertEquals("/v1/chat/completions", ProviderProtocols.path(ProviderProtocol.OpenAiChat))
        assertEquals("/v1/messages", ProviderProtocols.path(ProviderProtocol.AnthropicMessages))
        assertTrue(ProviderProtocols.path(ProviderProtocol.AnthropicMessages).startsWith("/v1/"))
    }
}
