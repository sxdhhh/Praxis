package dev.praxis.data.gateway

import dev.praxis.core.gateway.ProviderProtocol
import dev.praxis.core.gateway.ProviderProtocols

/**
 * 内置家：地址 + 显示名 + 协议。
 *
 * ## 关于「地址从哪来」（这一点必须写清楚）
 *
 * 每一条都来自该家**公开的 API 文档**（见每行后面的注释指向的文档站点），
 * 但**没有在本环境逐个抓取验证**（沙箱里外网抓取被拦）。所以：
 *
 * - 界面上把这一层说成「内置地址，可覆盖」，而不是「绝对正确」；
 * - 账号级 `baseUrlOverride` 与「自定义提供商」两条路都能改掉它；
 * - 地址错了会**响亮地失败**：客户端现在会把响应正文带进错误消息
 *   （那是之前修的一个缺口），不会变成「默默不回话」。
 *
 * ## 为什么只给「能对上协议」的家
 *
 * 这些都是 OpenAI 兼容的 `/chat/completions`（或 Anthropic Messages）。
 * 有自己一套私有协议的（例如某些家的签名鉴权）**不列**：列了也连不上。
 */
object BuiltinProviders {

    private data class Builtin(
        val id: String,
        val label: String,
        val baseUrl: String,
    )

    private val TABLE: List<Builtin> = listOf(
        // ── 国际 ──
        Builtin("openai", "OpenAI", "https://api.openai.com/v1"),
        Builtin("anthropic", "Anthropic", "https://api.anthropic.com"),
        Builtin("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
        Builtin("groq", "Groq", "https://api.groq.com/openai/v1"),
        Builtin("mistral", "Mistral", "https://api.mistral.ai/v1"),
        Builtin("together", "Together AI", "https://api.together.xyz/v1"),
        Builtin("xai", "xAI", "https://api.x.ai/v1"),
        Builtin("fireworks", "Fireworks", "https://api.fireworks.ai/inference/v1"),
        // 这两家的端点**不带 /v1**，所以直接写完整端点（resolve 会原样使用）。
        Builtin("perplexity", "Perplexity", "https://api.perplexity.ai/chat/completions"),
        Builtin("deepinfra", "DeepInfra", "https://api.deepinfra.com/v1/openai/chat/completions"),
        Builtin("cerebras", "Cerebras", "https://api.cerebras.ai/v1"),
        Builtin("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1"),
        // ── 国内 ──
        Builtin("deepseek", "DeepSeek", "https://api.deepseek.com"),
        Builtin("moonshot", "Kimi / Moonshot", "https://api.moonshot.cn/v1"),
        Builtin("dashscope", "通义千问（百炼）", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        Builtin("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"),
        Builtin("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1"),
        Builtin("ark", "火山方舟（豆包）", "https://ark.cn-beijing.volces.com/api/v3"),
        Builtin("hunyuan", "腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1"),
        Builtin("baichuan", "百川", "https://api.baichuan-ai.com/v1"),
        Builtin("minimax", "MiniMax", "https://api.minimax.chat/v1"),
        Builtin("stepfun", "阶跃星辰", "https://api.stepfun.com/v1"),
        Builtin("lingyiwanwu", "零一万物", "https://api.lingyiwanwu.com/v1"),
        Builtin("qianfan", "百度千帆", "https://qianfan.baidubce.com/v2"),
        Builtin("sensenova", "商汤日日新", "https://api.sensenova.cn/compatible-mode/v1"),
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
