package dev.praxis.core.model.settings

/**
 * 模型服务配置。
 *
 * ## 为什么 baseUrl / apiKey 要放进设置而不是 BuildConfig
 *
 * 写死在 BuildConfig 意味着「换个服务商就要重新打包」，等于普通用户用不了。
 * 这是产品级缺陷，不是便利性问题。
 *
 * ## 为何 [apiKey] 不在这里
 *
 * 这个 data class 会被日志、crash report、状态快照带出去。
 * 密钥单独存取（见 SettingsRepository），避免它跟着整个配置对象到处流。
 */
data class ModelSettings(
    /** 服务商接口地址，可填 `https://api.x.com` 或带 `/v1` 后缀。 */
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * 模型 ID，例如 `gpt-4o-mini` / `deepseek-chat`。
     *
     * ## 为什么这里可以存**逗号分隔的多个模型**（1:1 对齐 Operit）
     *
     * 上游 `ModelConfigData.modelName` 就是这样一个字段：它同时承担
     * 「已保存的模型池」与「当前使用的模型」两个角色，靠 `modelIndex`
     * 区分（见 `getModelByIndex` / `getValidModelIndex`）。
     *
     * 用户在设置页勾选多个模型之后，字段里是 `a,b,c`；真正发请求时取
     * [activeModel] 得到单个模型名。**绝不能把整串直接发出去** ——
     * 服务端会报 model not found。
     *
     * ## 为什么不做成 `List<String>`
     *
     * 那样要改 DataStore 的存储编码（现在是 `put(KEY_MODEL, String)`），
     * 已有用户的配置要么写迁移、要么读旧值时兼容两条路径。而逗号串
     * 与上游**逐字一致**，序列化零成本、跨版本零迁移，且人眼可读
     * （排查问题时直接看库就能知道存了什么）。
     *
     * 模型 ID 本身不含逗号（OpenAI / Gemini / Anthropic 的 id 都只用
     * `[a-zA-Z0-9._:-]`），所以逗号是安全的分隔符。
     */
    val model: String = DEFAULT_MODEL,
    /**
     * 当前使用 [model] 池里的第几个（0 起）。
     *
     * ## 为什么需要它（上游 `ModelConfigSummary.modelIndex` 的同位实现）
     *
     * [model] 可以存多个模型（`a,b,c`），但**每次请求只能发一个**。
     * 没有这个索引，「池里用哪个」就没有表达方式。
     *
     * ## 越界怎么办
     *
     * 一律由 [activeModel] 兜底回落第 0 个并**不写回设置**：
     *  - 用户可能刚删掉池里最后一个模型，索引暂时越界；
     *  - 也可能从备份导入了一个索引更大的配置。
     *
     * 这两种情况下「静默用第一个」比「报错不许聊」合理得多。
     * 上游 `getValidModelIndex` 是同一个口径（越界 → 0）。
     */
    val modelIndex: Int = 0,

    /** 采样温度。0 更确定，1 更发散。 */
    val temperature: Double = 0.7,

    /**
     * 选中的服务商预设。
     *
     * 它**不参与请求构造**（真正发出去的是 [baseUrl]），
     * 只用来在设置页回填「当前选的是哪家」，以及决定是否需要 API Key。
     * 这样用户改了地址之后仍然记得自己是从哪家改过来的。
     */
    val provider: ApiProvider = ApiProvider.Custom,

    /**
     * 当前模型的最大上下文（token）。0 表示未填。
     *
     * 只用于**显示进度条的分母**与「是否该压缩」的判断，不参与请求构造。
     * 之所以要用户填：同名模型在不同服务商那里上限不同，内置表必然过时。
     */
    val contextLimit: Int = 0,

    /**
     * 压缩阈值（0.1..0.95）。上下文占用达到此比例时触发压缩。
     *
     * 默认 0.75：压缩本身要一次模型调用，卡到 0.95 再压很可能
     * 「要压缩的那次请求自己就超限了」。
     */
    val compactThreshold: Float = 0.75f,

    /**
     * 思考强度。
     *
     * 抽象成三档而不是直接暴露各家参数（`reasoning_effort` /
     * `thinking_budget` / `enable_thinking`）：不同服务商的名字与取值域都不同，
     * 直接暴露会让用户在换服务商后必须重配一遍，且填错就 400。
     */
    val thinkingEffort: ThinkingEffort = ThinkingEffort.None,
) {
    /**
     * 已保存的模型池（把 [model] 按逗号切开、去空白、丢空项）。
     *
     * 与上游 `getModelList` 逐字等价：
     *
     * ```kotlin
     * modelName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
     * ```
     *
     * 去空项这一步是必须的：`a,,b` 或 `a,`（用户手删末尾逗号时很常见）
     * 否则会产生空字符串模型名，发出去就是 `model: ""` → 400。
     */
    val modelList: List<String>
        get() = parseModelList(model)

    /**
     * **真正发请求时要用的那一个模型名。**
     *
     * ## 这是本轮修的核心缺陷
     *
     * 之前 `SettingsBackedProvider` 直接把 `settings.model` 塞进请求体
     * （`put("model", request.model)`）。在模型池支持多选之后，那样做会把
     * `"a,b,c"` 整串发出去 —— 服务端不认识这个模型名，直接 400。
     *
     * 这里做两件事：
     *  1. 按 [modelIndex] 从池里取；
     *  2. 越界时回落第 0 个（与上游 `getValidModelIndex` 同口径）。
     *
     * ## 池为空时返回空串
     *
     * 空串会让 [isConfigured] 为 false，UI 显示「未配置」，并且
     * `OpenAiCompatProvider` 会照常发请求、由服务端报错 —— 两者都能接受。
     * 返回 null 会迫使每个调用点都处理可空，代价不值。
     */
    val activeModel: String
        get() {
            val list = modelList
            if (list.isEmpty()) return ""
            // 越界回落第 0 个：不抛异常、不写回设置，只保证「总有可用模型」。
            val index = if (modelIndex in list.indices) modelIndex else 0
            return list[index]
        }

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && activeModel.isNotBlank()

    companion object {
        /** 留空而不是塞一个真实地址：用户必须自己确认填哪个服务商。 */
        const val DEFAULT_BASE_URL: String = ""
        const val DEFAULT_MODEL: String = ""
    }
}

/**
 * 把逗号分隔的模型串切成列表。**逐字照抄 Operit `getModelList`**。
 *
 * 独立成顶层函数而不是私有方法：`ModelScreen` 的弹窗要用它把
 * 已保存的池子还原成「哪些勾上了」；`SettingsBackedProvider` 要用它取模型。
 * 放在 `ModelSettings` 的伴生对象里也行，但顶层函数在 Kotlin 里
 * 更像「工具函数」，调用处也不需要多一层点号。
 */
fun parseModelList(model: String): List<String> {
    if (model.isEmpty()) return emptyList()
    return model.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * 把选中的模型列表拼回逗号串。**与上游弹窗的确认按钮同一条语义**。
 *
 * ## 为什么顺序要由调用方决定（而不是按选中顺序）
 *
 * 上游的做法是：
 *
 * ```kotlin
 * val orderedSelection = modelsList.map { it.id }      // 按服务端返回顺序遍历
 *     .filter { selectedModels.value.contains(it) }    // 只留勾上的
 * modelNameInput = orderedSelection.joinToString(",")
 * ```
 *
 * 也就是**按 `/models` 返回的顺序**拼接，而不是按用户点击顺序。
 * 这不是随意的：`modelIndex` 是「池里的第几个」，如果顺序随点击变化，
 * 用户下次打开设置时「当前使用」就会指向另一个模型。
 * 按固定顺序（服务端列表）拼接，索引才有稳定含义。
 *
 * 本函数只负责拼接与去重，顺序由调用方传进来。
 */
fun joinModelList(models: List<String>): String =
    models.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
