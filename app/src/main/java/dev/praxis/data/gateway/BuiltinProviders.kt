package dev.praxis.data.gateway

import dev.praxis.core.gateway.ProviderProtocol

/** 一家的特判（上游 preset-providers.js 的 `quirks` + 账号默认值）。 */
data class BuiltinQuirks(
    /** 合并到默认头上的静态额外头（同名覆盖默认头）。 */
    val headers: Map<String, String> = emptyMap(),
    /** 出站 URL 的查询串后缀（GLM / MiniMax 的 Claude 兼容端点要 ?beta=true）。 */
    val urlSuffix: String = "",
    /** 发 Anthropic 上游时给每个工具补 `type: "custom"`（MiniMax 要求，DeepSeek 相反）。 */
    val anthropicToolType: Boolean = false,
    /** 该上游无需鉴权（Ollama 本地、OpenCode Zen 免费档）。 */
    val noAuth: Boolean = false,
    /**
     * 该家要求「伪装成官方客户端」才能连（OpenCode Zen 免费档）。
     *
     * **未搬**：它会改写请求体与四个会话头（上游 2026-10-14 对着官方客户端 v2.0.27 实测）。
     * 列是列出来（照抄上游目录），但界面上明确标「暂不能连」，不假装能用。
     */
    val needsClientEmulation: Boolean = false,
)

/**
 * 内置家：**逐条抄自上游注册表**，不要自己写地址或特判。
 *
 * 上游权威源（本机）：`C:\creat-agent\Agent2API\desktop-tauri\ui\preset-providers.js`
 * （它本身抄自 9Router 的 `open-sse/providers/registry`，并注明了每家的特判）。
 *
 * ## 我上一版错在哪（留作记录）
 *
 * 我凭印象写了 25 家地址，与上游多处不符：智谱写成 `/api/paas/v4`（上游是 Coding 套餐的
 * `api.z.ai/api/anthropic` 与中国站 `open.bigmodel.cn/api/coding/paas/v4`）、漏了 HuggingFace、
 * 其余十几家（mistral/groq/ark/qianfan…）上游**根本没有对应条目** —— 全是我瞎补的。
 * 这一版逐条对应上游，没有的就删。
 *
 * ## 地址语义（与上游一致）
 *
 * - `chat_completions` 家：填到版本段（如 `.../v1`、`.../api/coding/paas/v4`），
 *   客户端 `ChatEndpoint.resolve` 会补 `/chat/completions`；
 * - `anthropic` 家：填到站点根或 anthropic 路径（如 `api.z.ai/api/anthropic`），
 *   协议路径补 `/v1/messages`。
 *
 * ## 协议必须由这张表定，不能按 id 猜
 *
 * GLM Coding / Minimax 用的是 **Anthropic 协议**。此前选路只按 id 映射
 * （`ProviderProtocols.of`），那两家会被当成 OpenAI 兼容发出去 —— 选了套餐反而连不上。
 */
object BuiltinProviders {

    private const val ANTHROPIC_BETA =
        "claude-code-20250219,interleaved-thinking-2025-05-14"

    private val BETA_HEADERS = mapOf("Anthropic-Beta" to ANTHROPIC_BETA)

    /** Cline 官方客户端形态（抄自上游后端 providers/cline/headers.rs 的 DEFAULT_HEADERS）。 */
    private val CLINE_HEADERS = mapOf(
        "User-Agent" to "Cline/3.0.62",
        "HTTP-Referer" to "https://cline.bot",
        "X-Title" to "Cline",
        "X-IS-MULTIROOT" to "false",
        "X-CLIENT-TYPE" to "cline-sdk",
        "X-CLIENT-VERSION" to "3.0.62",
        "X-PLATFORM" to "terminal",
        "X-PLATFORM-VERSION" to "3.0.62",
        "X-CORE-VERSION" to "0.0.70",
    )

    data class Builtin(
        val id: String,
        val label: String,
        val protocol: ProviderProtocol,
        /** null = 自建面板，地址由用户填（上游的 sub2api / newapi）。 */
        val baseUrl: String?,
        val quirks: BuiltinQuirks = BuiltinQuirks(),
    )

    private val TABLE: List<Builtin> = listOf(
        Builtin("openai", "OpenAI", ProviderProtocol.OpenAiChat, "https://api.openai.com/v1"),
        Builtin(
            "anthropic", "Anthropic", ProviderProtocol.AnthropicMessages,
            "https://api.anthropic.com",
            BuiltinQuirks(headers = BETA_HEADERS),
        ),
        Builtin(
            "openrouter", "OpenRouter", ProviderProtocol.OpenAiChat,
            "https://openrouter.ai/api/v1",
            BuiltinQuirks(
                headers = mapOf(
                    "HTTP-Referer" to "https://endpoint-proxy.local",
                    "X-Title" to "Endpoint Proxy",
                ),
            ),
        ),
        Builtin("huggingface", "HuggingFace", ProviderProtocol.OpenAiChat, "https://router.huggingface.co/v1"),
        Builtin("nvidia", "NVIDIA", ProviderProtocol.OpenAiChat, "https://integrate.api.nvidia.com/v1"),
        Builtin("deepseek", "DeepSeek", ProviderProtocol.OpenAiChat, "https://api.deepseek.com"),
        Builtin(
            "glm", "GLM Coding", ProviderProtocol.AnthropicMessages,
            "https://api.z.ai/api/anthropic",
            BuiltinQuirks(headers = BETA_HEADERS, urlSuffix = "?beta=true"),
        ),
        Builtin("glm-cn", "GLM 中国", ProviderProtocol.OpenAiChat, "https://open.bigmodel.cn/api/coding/paas/v4"),
        Builtin(
            "minimax", "Minimax Coding", ProviderProtocol.AnthropicMessages,
            "https://api.minimax.io/anthropic",
            BuiltinQuirks(headers = BETA_HEADERS, urlSuffix = "?beta=true", anthropicToolType = true),
        ),
        Builtin(
            "minimax-cn", "Minimax 中国", ProviderProtocol.AnthropicMessages,
            "https://api.minimaxi.com/anthropic",
            BuiltinQuirks(headers = BETA_HEADERS, urlSuffix = "?beta=true", anthropicToolType = true),
        ),
        Builtin("siliconflow", "SiliconFlow", ProviderProtocol.OpenAiChat, "https://api.siliconflow.com/v1"),
        Builtin(
            "volcengine-ark", "火山方舟", ProviderProtocol.OpenAiChat,
            "https://ark.cn-beijing.volces.com/api/coding/v3",
        ),
        Builtin("alicode", "Alibaba", ProviderProtocol.OpenAiChat, "https://coding.dashscope.aliyuncs.com/v1"),
        Builtin(
            "alicode-intl", "Alibaba Coding", ProviderProtocol.OpenAiChat,
            "https://coding-intl.dashscope.aliyuncs.com/v1",
        ),
        Builtin(
            "alims-intl", "Alibaba Studio", ProviderProtocol.OpenAiChat,
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
        ),
        Builtin(
            "alitp-intl", "Alibaba Token Plan", ProviderProtocol.OpenAiChat,
            "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
        ),
        Builtin(
            "xiaomi-tokenplan", "小米 MiMo (Token Plan)", ProviderProtocol.OpenAiChat,
            "https://token-plan-sgp.xiaomimimo.com/v1",
        ),
        Builtin("opencode-go", "OpenCode Go", ProviderProtocol.OpenAiChat, "https://opencode.ai/zen/go/v1"),
        Builtin(
            "opencode-zen", "OpenCode Zen", ProviderProtocol.OpenAiChat,
            "https://opencode.ai/inference/v1",
            BuiltinQuirks(noAuth = true, needsClientEmulation = true),
        ),
        Builtin(
            "clinepass", "Cline Pass", ProviderProtocol.OpenAiChat,
            "https://api.cline.bot/api/v1",
            BuiltinQuirks(headers = CLINE_HEADERS),
        ),
        Builtin(
            "ollama-local", "Ollama（本地）", ProviderProtocol.OpenAiChat,
            "http://localhost:11434/v1",
            BuiltinQuirks(noAuth = true),
        ),
        Builtin("sub2api", "Sub2API", ProviderProtocol.OpenAiChat, null),
        Builtin("newapi", "NewAPI", ProviderProtocol.OpenAiChat, null),
        Builtin(
            "sensenova", "商汤日日新", ProviderProtocol.OpenAiChat,
            "https://api.sensenova.cn/compatible-mode/v1",
        ),
    )

    /** 全部内置家 id（顺序即上游目录顺序）。 */
    fun ids(): List<String> = TABLE.map { it.id }

    fun label(id: String): String = entry(id)?.label ?: id

    fun baseUrl(id: String): String? = entry(id)?.baseUrl

    /** 协议：**表里有就按表**，没有才按 id 猜（自定义家）。 */
    fun protocol(id: String): ProviderProtocol = entry(id)?.protocol
        ?: dev.praxis.core.gateway.ProviderProtocols.of(id)

    fun quirks(id: String): BuiltinQuirks = entry(id)?.quirks ?: BuiltinQuirks()

    private fun entry(id: String): Builtin? =
        TABLE.firstOrNull { it.id.equals(id, ignoreCase = true) }
}
