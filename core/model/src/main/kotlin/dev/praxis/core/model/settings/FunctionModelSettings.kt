package dev.praxis.core.model.settings

/**
 * 可单独指定模型的**功能位**（对齐 Operit 的 `FunctionType` 与它的「功能模型配置」页）。
 *
 * ## 为什么要有这个概念
 *
 * Operit 那一页的核心不是「多几个设置项」，而是承认一件事：
 * **不同功能对模型的要求根本不同**。
 *
 * | 功能 | 要模型干嘛 | 关键要求 |
 * |---|---|---|
 * | 对话 | 多轮长上下文 + 工具调用 | 上下文长、指令跟随强 |
 * | 上下文总结 | 压缩历史 | 便宜、快（要反复跑） |
 * | 生成标题 | 从第一句话悟出主题 | 极便宜（一句话的事） |
 * | 记忆更新 | 抽取事实并归类 | 结构化输出稳定 |
 * | 翻译 | 语言转换 | 多语言覆盖 |
 * | Grep 检索 | 规划检索与筛选结果 | 便宜、可控 |
 *
 * 用一个「最强模型」全包，等于让「生成标题」这种一句话任务也按最贵的价格结算。
 * 分开配置是**成本问题**，不是洁癖。
 *
 * ## 与 Operit 的差异（刻意的）
 *
 * Operit 的 `FunctionType` 有 20 多项（含「环境配置」「UI 控制器」「图像识别」
 * 「音频识别」「视频识别」等）。Praxis 当前**没有**那些能力对应的真实实现
 * （没有视觉模型接线、没有 UI 自动化引擎），所以这里只列**已经有真实调用点**
 * 的功能位。列出来却点不动，就是假入口。
 *
 * 后续每接一个真实能力（如图像识别），在这里加一项即可 ——
 * 枚举是 `entries` 驱动 UI 的，不需要改页面。
 */
enum class FunctionSlot(
    /** 存储 key。**不要改**：它写进 DataStore，改了等于把用户配置清零。 */
    val key: String,
    val title: String,
    val description: String,
) {
    CHAT(
        key = "chat",
        title = "对话",
        description = "主要的对话功能，用于与用户进行日常交互",
    ),
    CONTEXT_SUMMARY(
        key = "context_summary",
        title = "上下文总结",
        description = "压缩上下文，生成聊天摘要",
    ),
    TITLE(
        key = "title",
        title = "AI 总结标题",
        description = "根据用户第一条消息自动生成简短、准确的对话标题",
    ),
    MEMORY_UPDATE(
        key = "memory_update",
        title = "记忆更新",
        description = "处理记忆并保存到记忆库",
    ),
    TRANSLATION(
        key = "translation",
        title = "翻译功能",
        description = "多语言翻译，支持文本内容的语言转换",
    ),
    GREP(
        key = "grep",
        title = "Grep 检索",
        description = "为代码检索做规划与结果筛选",
    ),
    ;

    companion object {
        fun byKey(key: String?): FunctionSlot? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 一个功能位的模型覆盖配置。
 *
 * ## 为什么是「覆盖」而不是「完整配置」
 *
 * 只存 `model`（以及可选的 provider / baseUrl）而**不存 apiKey**：
 * 密钥属于「服务商账号」这一层，不属于某个功能。若允许每个功能各存一把 Key，
 * 用户换一次服务商就要改六处，且密钥加密存储的条目会从 1 条变成 6 条。
 *
 * `null` 表示「跟随全局」——这是默认值，也是绝大多数用户该用的值。
 * 界面上显示成「跟随全局（deepseek-chat）」而不是空白，避免用户以为没配。
 */
data class FunctionModelOverride(
    /** 模型 ID；`null` = 跟随全局 [ModelSettings.model]。 */
    val model: String? = null,
    /**
     * 服务商覆盖；`null` = 跟随全局。
     *
     * 允许按功能换服务商是真实需求：总结/标题这种任务可以走便宜的国内模型，
     * 而对话走能力更强的模型。但它同时意味着 baseUrl 也可能不同，
     * 所以两者必须一起覆盖，否则会拼出「A 家的地址 + B 家的模型」。
     */
    val provider: ApiProvider? = null,
    /** 与 [provider] 配套的地址；两者要么都为 null，要么都有值。 */
    val baseUrl: String? = null,
) {
    /** 是否真的覆盖了全局配置。 */
    val isOverridden: Boolean get() = model != null
}

/**
 * 全部功能位的覆盖表。
 *
 * 用 `Map` 而不是六个具名字段：新增功能位时不需要改这个类，
 * 也不会让构造函数参数爆炸。缺失的 key 视为「跟随全局」。
 */
data class FunctionModelSettings(
    val overrides: Map<FunctionSlot, FunctionModelOverride> = emptyMap(),
) {
    /** 取某功能位的覆盖；没有则返回默认（跟随全局）。 */
    operator fun get(slot: FunctionSlot): FunctionModelOverride =
        overrides[slot] ?: FunctionModelOverride()

    /** 返回替换了某功能位后的新实例（不可变，便于 StateFlow 差分）。 */
    fun with(slot: FunctionSlot, override: FunctionModelOverride): FunctionModelSettings {
        val next = overrides.toMutableMap()
        if (override.isOverridden) {
            next[slot] = override
        } else {
            // 清空覆盖而不是存一个全 null 的对象：让「跟随全局」在存储里也只有一种表示。
            next.remove(slot)
        }
        return copy(overrides = next)
    }

    /**
     * 解析某功能位**最终**该用的模型与服务商。
     *
     * 单一入口，避免调用侧各写一遍 fallback 逻辑（写歪一次就是
     * 「标题用对话模型」这种查不出来的 bug）。
     */
    fun resolve(slot: FunctionSlot, global: ModelSettings): ResolvedModel {
        val o = this[slot]
        val provider = o.provider ?: global.provider
        // ## 为什么跟随全局时要取 `activeModel` 而不是 `global.model`
        // （2026-10-24 修，与 `ModelSettings.activeModel` 同步引入）
        //
        // `global.model` 现在是**模型池**（`a,b,c`）。直接把它填进
        // `ResolvedModel.model` 有两个后果：
        //  1. 功能位请求（标题 / 总结 / 记忆更新）会把整串发给服务端 → 400；
        //  2. `SettingsBackedTitleGenerator` 等调用方拿到的是池子，
        //     日志里也会打印成一串，排查时看不出到底用了哪个模型。
        //
        // 功能位自己覆盖时（`o.model != null`）它本身就是单个模型名，
        // 不经过池子解析 —— 功能位的输入框本来就不支持多选。
        val model = o.model ?: global.activeModel
        return ResolvedModel(
            model = model,
            provider = provider,
            baseUrl = o.baseUrl ?: global.baseUrl,
            temperature = global.temperature,
            isOverridden = o.isOverridden,
        )
    }
}

/** 解析后的实际请求参数。`apiKey` 不在其中：它按 [provider] 单独从仓库取。 */
data class ResolvedModel(
    val model: String,
    val provider: ApiProvider,
    val baseUrl: String,
    val temperature: Double,
    /** 是否是功能位自己的模型（而不是跟随全局）。 */
    val isOverridden: Boolean,
)