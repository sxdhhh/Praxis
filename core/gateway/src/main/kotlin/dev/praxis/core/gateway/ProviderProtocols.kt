package dev.praxis.core.gateway

import dev.praxis.core.ai.AnthropicRequestBuilder

/** 一家上游说哪种协议（文档 §5.1 的四类入口里，App 用得到的两种）。 */
enum class ProviderProtocol(
    /** 协议标识，写进配置时用它。 */
    val id: String,
) {
    /** OpenAI Chat Completions（`/v1/chat/completions`）。绝大多数中转与国产平台都是它。 */
    OpenAiChat("openai"),

    /** Anthropic Messages（`/v1/messages`）。 */
    AnthropicMessages("anthropic"),

    /**
     * OpenAI Responses（`/v1/responses`）。
     *
     * 与 Chat Completions 是**同一家的两个协议**，不是两家 —— 所以内置表里没有
     * 任何一家默认走它（官方 OpenAI 走 Chat 更通用）。目前只有用户把自定义家
     * 的 id 写成 `responses` / `openai-responses` 才会走到这里。
     */
    Responses("responses"),
}

/**
 * 哪家说哪种协议。
 *
 * ## 为什么用「内置表 + 兼容默认」，而不是数据库字段
 *
 * 加一个 `protocol` 列意味着一次 Room 迁移，而这个信息**本来就不该由用户填**：
 * 协议是上游自身的属性，用户不该被要求知道「我用的是不是 Messages 协议」。
 * 内置表覆盖已知的那几家，其余按 OpenAI 兼容处理 ——
 *
 * **这不是「失败就退回默认」**：OpenAI 兼容是事实上的行业基线（自建中转、
 * 国产平台几乎都提供它），而 Anthropic 是明确的分叉。给未知家一个"什么都不通"
 * 的选项，只会让用户没法用自定义中转。
 */
object ProviderProtocols {

    /** 明确说 Anthropic 协议的家（按 provider id，大小写不敏感）。 */
    private val ANTHROPIC_HOSTS = setOf(
        "anthropic",
        "claude",
    )

    /** 明确说 Responses 协议的家（自定义家里的 id）。 */
    private val RESPONSES_HOSTS = setOf("responses", "openai-responses")

    fun of(providerId: String): ProviderProtocol {
        val normalized = providerId.trim().lowercase()
        return when {
            normalized in ANTHROPIC_HOSTS -> ProviderProtocol.AnthropicMessages
            normalized in RESPONSES_HOSTS -> ProviderProtocol.Responses
            else -> ProviderProtocol.OpenAiChat
        }
    }

    /**
     * 该协议的鉴权头。
     *
     * OpenAI 用 `Authorization: Bearer`，Anthropic 用 `x-api-key` + 版本头 ——
     * 两家的头不能互换（Anthropic 会把缺版本头当成 400）。
     */
    fun authHeaders(protocol: ProviderProtocol, apiKey: String): Map<String, String> = when (protocol) {
        ProviderProtocol.OpenAiChat -> mapOf("Authorization" to "Bearer " + apiKey)
        ProviderProtocol.AnthropicMessages -> mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to AnthropicRequestBuilder.API_VERSION,
        )
        // Responses 与 Chat 是同一家的两个协议，鉴权头一致。
        ProviderProtocol.Responses -> mapOf("Authorization" to "Bearer " + apiKey)
    }

    /** 请求路径。 */
    fun path(protocol: ProviderProtocol): String = when (protocol) {
        ProviderProtocol.OpenAiChat -> "/v1/chat/completions"
        ProviderProtocol.AnthropicMessages -> "/v1/messages"
        ProviderProtocol.Responses -> "/v1/responses"
    }
}
