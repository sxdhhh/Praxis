package dev.praxis.data.gateway

import dev.praxis.core.gateway.ProviderProtocol
import dev.praxis.core.gateway.ProviderProtocols

/**
 * 内置家：地址 + 显示名 + 协议。
 *
 * ## ⚠️ 这份表**抄自上游注册表**，不要自己写地址
 *
 * 上游权威源（本机）：
 * `C:\creat-agent\Agent2API\desktop-tauri\ui\preset-providers.js`
 * 那张表本身抄自 9Router 的 `open-sse/providers/registry`，并注明了每家的特判。
 *
 * 我此前凭印象写了一份 25 家的地址表，与上游**多处不符**（例如智谱：上游用的是
 * Coding 套餐的 `api.z.ai/api/anthropic` 与中国站 `open.bigmodel.cn/api/coding/paas/v4`，
 * 而不是我以为的 `/api/paas/v4`；HuggingFace 我也漏了）。那份表已作废。
 *
 * ## 还没搬的部分（明确标注，不装完整）
 *
 * 上游每条还带 `quirks` —— 对某些家是**硬门槛**：
 *
 * | quirks | 谁需要 | 缺了会怎样 |
 * |---|---|---|
 * | `headers: Anthropic-Beta` | Anthropic / GLM Coding | 部分 beta 特性不可用 |
 * | `headers: HTTP-Referer / X-Title` | OpenRouter | 不影响转发（推荐标识头） |
 * | `urlSuffix: ?beta=true` | GLM Coding | 缺了 beta 特性不可用 |
 * | `clientEmulation` | OpenCode Zen | 上游按客户端形态校验，一律 403 |
 *
 * 这些**必须先扩端口再列家**：现在的自定义家模型没有「静态额外头 / URL 后缀」两个字段，
 * 列出来会让用户连不上而看不出原因。所以本表暂时只列**不加 quirks 也能连**的家。
 * 下一步：给 provider 记录加 `extraHeaders` / `urlSuffix` 两列（一次迁移），再续抄剩余条目。
 */
object BuiltinProviders {

    private data class Builtin(
        val id: String,
        val label: String,
        val baseUrl: String,
    )

    /**
     * 与上游 `preset-providers.js` 逐条对应的子集（只取无需 quirks 的）。
     *
     * 地址语义与上游一致：`chat_completions` 家填到版本段（客户端 `ChatEndpoint.resolve`
     * 会补 `/chat/completions`），`anthropic` 家填到站点根（协议路径补 `/v1/messages`）。
     */
    private val TABLE: List<Builtin> = listOf(
        Builtin("openai", "OpenAI", "https://api.openai.com/v1"),
        Builtin("anthropic", "Anthropic", "https://api.anthropic.com"),
        Builtin("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
        Builtin("huggingface", "HuggingFace", "https://router.huggingface.co/v1"),
        Builtin("nvidia", "NVIDIA", "https://integrate.api.nvidia.com/v1"),
        Builtin("deepseek", "DeepSeek", "https://api.deepseek.com"),
        Builtin("glm-cn", "GLM 中国", "https://open.bigmodel.cn/api/coding/paas/v4"),
    )

    /** 全部内置家 id（顺序即界面顺序）。 */
    fun ids(): List<String> = TABLE.map { it.id }

    /** 显示名；未知 id 原样返回（自定义家的名字由用户自己定）。 */
    fun label(id: String): String =
        TABLE.firstOrNull { it.id.equals(id, ignoreCase = true) }?.label ?: id

    /** 内置地址；未知 id 返回 null（那时查账号覆盖与 provider 表）。 */
    fun baseUrl(id: String): String? =
        TABLE.firstOrNull { it.id.equals(id, ignoreCase = true) }?.baseUrl

    /** 这家用的线上协议（文档 §5.1 的协议面）。 */
    fun protocol(id: String): ProviderProtocol = ProviderProtocols.of(id)
}
