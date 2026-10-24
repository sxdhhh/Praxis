package dev.praxis.core.model.settings

/**
 * 模型服务商预设。
 *
 * ## 为什么需要这张表（对齐 Operit 的核心体验）
 *
 * 对齐前的 Praxis：只有一个空的「接口地址」输入框。用户要自己去翻服务商文档、
 * 抄地址、抄模型名 —— 抄错一个字符就是 `404` 或 `model not found`，
 * 而错误信息是服务端返回的英文 JSON，用户看不出是「地址填错」还是「模型名填错」。
 *
 * Operit 的做法是：**选服务商 → 自动填默认端点与默认模型**，
 * 用户只需要贴 Key。这一层「不用查文档」的体验是它配置页最关键的部分，
 * 比多给几个滑块重要得多。
 *
 * ## 为什么放在 core:model 而不是 UI 层
 *
 * 这是**纯数据**（地址、模型名、是否需要 key），没有任何 Compose 依赖。
 * 放在 core 有三个好处：
 *  1. 可纯 JVM 单测（`ProviderPresetsTest`）—— 地址写错能当场发现；
 *  2. `SettingsBackedProvider` 需要用它判断「这个服务商是否真需要 key」；
 *  3. 以后做「一键切换服务商」时不必把数据从 UI 层倒出来。
 *
 * ## 与 Operit 的差异（有意收窄）
 *
 * Operit 有 38 种 `ApiProviderType`，因为它真的实现了 5 种协议
 * （OpenAI Chat / OpenAI Responses / Anthropic / Gemini / 各家私有）。
 *
 * Praxis 的 `OpenAiCompatProvider` **只实现了 OpenAI Chat Completions 一种协议**。
 * 所以这里只收录「确实能用 OpenAI 兼容协议跑通」的服务商 —— 共 14 个。
 * 把 Anthropic / Gemini 列进来会让用户选了之后必然失败，那比不列更糟。
 *
 * 等真实现了 Anthropic 协议（`/v1/messages` + 不同的 SSE 结构），再往这里加。
 *
 * ## 「自定义」为什么必须留
 *
 * 中转站（one-api / new-api）是中文用户最常见的用法，地址千奇百怪。
 * 没有「自定义」这一项，用户就只能选一个最近的预设再手动改地址 ——
 * 那会让人误以为「必须用预设里的服务商」。
 */
enum class ApiProvider(
    /** 显示名（简短，够区分即可）。 */
    val label: String,
    /** 默认接口地址；空串表示「必须用户自己填」。 */
    val defaultEndpoint: String,
    /** 默认模型名；空串表示「必须用户自己填」。 */
    val defaultModel: String,
    /** 是否必须有 API Key。false 仅用于本地推理（无鉴权）。 */
    val requiresApiKey: Boolean = true,
) {
    // ── 国内常用（多数有 OpenAI 兼容端点）────────────────────────────
    DeepSeek(
        label = "DeepSeek",
        defaultEndpoint = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
    ),
    ZhiPu(
        label = "智谱 GLM",
        defaultEndpoint = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4-plus",
    ),
    Moonshot(
        label = "月之暗面 Kimi",
        defaultEndpoint = "https://api.moonshot.cn/v1",
        defaultModel = "moonshot-v1-8k",
    ),
    Aliyun(
        label = "阿里云百炼（通义）",
        defaultEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-plus",
    ),
    SiliconFlow(
        label = "硅基流动",
        defaultEndpoint = "https://api.siliconflow.cn/v1",
        defaultModel = "Qwen/Qwen2.5-7B-Instruct",
    ),

    // ── 国际常用 ──────────────────────────────────────────────────
    OpenAI(
        label = "OpenAI",
        defaultEndpoint = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
    ),
    XAi(
        label = "xAI Grok",
        defaultEndpoint = "https://api.x.ai/v1",
        defaultModel = "grok-2-latest",
    ),
    Mistral(
        label = "Mistral",
        defaultEndpoint = "https://api.mistral.ai/v1",
        defaultModel = "mistral-large-latest",
    ),
    OpenRouter(
        label = "OpenRouter（聚合）",
        defaultEndpoint = "https://openrouter.ai/api/v1",
        defaultModel = "openai/gpt-4o-mini",
    ),

    // ── 本地 / 自建（无鉴权，明文 http 也常见）──────────────────────
    Ollama(
        label = "Ollama（本地）",
        defaultEndpoint = "http://127.0.0.1:11434/v1",
        defaultModel = "qwen2.5",
        // 本地 Ollama 默认不校验 key；逼用户填一个假的反而制造困惑。
        requiresApiKey = false,
    ),
    LmStudio(
        label = "LM Studio（本地）",
        defaultEndpoint = "http://127.0.0.1:1234/v1",
        defaultModel = "",
        requiresApiKey = false,
    ),
    LlamaCpp(
        label = "llama.cpp（本地）",
        defaultEndpoint = "http://127.0.0.1:8080/v1",
        defaultModel = "",
        requiresApiKey = false,
    ),

    // ── 兜底 ──────────────────────────────────────────────────────
    Custom(
        label = "自定义 / 中转站",
        defaultEndpoint = "",
        defaultModel = "",
    ),
    ;

    /** 是否可以「选完就基本能用」（两端都有默认值）。 */
    val hasFullDefaults: Boolean
        get() = defaultEndpoint.isNotBlank() && defaultModel.isNotBlank()

    companion object {
        /**
         * 按名还原，找不到回落到 [Custom]。
         *
         * 用「按名查找」而不是 `valueOf`：降级安装或枚举改名时
         * `valueOf` 会抛异常把设置页打挂（本项目已确认过的坑）。
         */
        fun fromNameOrDefault(name: String?): ApiProvider =
            entries.firstOrNull { it.name == name } ?: Custom
    }
}