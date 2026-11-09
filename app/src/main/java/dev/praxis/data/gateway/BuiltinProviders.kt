package dev.praxis.data.gateway

/**
 * 内置家的地址表（文档 §5.1 / 组十四）。
 *
 * ## 为什么只有两家
 *
 * 文档列的是「21 家」，但那 21 家的地址、协议、模型清单要在 P5 的 Rust 内核里一起给。
 * 这里**只放我能确认的两家**：官方 Anthropic 与官方 OpenAI。
 *
 * 其余家的地址我不打算凭印象写 —— 写错的表现是「配了账号却一直 404/401」，
 * 而用户会以为是自己填错了 key。宁可让他在「自定义家」里填，也不给错的默认值。
 *
 * ## 为什么这两家很关键
 *
 * OpenAI 是 OpenAI 兼容协议的基线；Anthropic 是**唯一的分叉协议**（`/v1/messages`）。
 * 有了这两家，「换家」与「协议分派」都能端到端走通一次，剩下的家只是往表里加数据。
 */
object BuiltinProviders {

    /** provider id -> baseUrl（不含 `/v1`，由各 provider 自己拼路径）。 */
    private val BASE_URLS: Map<String, String> = mapOf(
        "anthropic" to "https://api.anthropic.com",
        "openai" to "https://api.openai.com",
    )

    fun baseUrl(providerId: String): String? = BASE_URLS[providerId.trim().lowercase()]

    /** 当前内置的全部家（给界面列候选）。 */
    fun ids(): List<String> = BASE_URLS.keys.sorted()
}
