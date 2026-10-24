package dev.praxis.core.model.character

/**
 * 角色卡（1:1 复刻 Operit 的 `CharacterCard`，字段语义逐条对齐）。
 *
 * ## 为什么所有字段都用不可空类型 + 默认值
 *
 * Operit 的实体是 Room `@Entity`，字段全部带默认值。这里保持一致：
 * 从旧版本升级、从酒馆 JSON 导入、从备份恢复时，缺失字段都能落到默认值，
 * 不会因为「某个可选字段是 null」而在 UI 层到处写 `?: ""`。
 *
 * ## 与 Operit 的字段对应表
 *
 * | 本字段 | Operit | 说明 |
 * |---|---|---|
 * | [id] | `id` | 主键。默认卡固定为 [DEFAULT_ID] |
 * | [name] | `name` | 显示名 |
 * | [description] | `description` | 简介（**不**注入提示词） |
 * | [characterSetting] | `characterSetting` | 角色设定（引导词） |
 * | [openingStatement] | `openingStatement` | 开场白（仅新建会话时用） |
 * | [otherContentChat] | `otherContentChat` | 其他内容（聊天模式） |
 * | [otherContentVoice] | `otherContentVoice` | 其他内容（语音模式） |
 * | [attachedTagIds] | `attachedTagIds` | 附着的标签 id |
 * | [advancedCustomPrompt] | `advancedCustomPrompt` | 高级自定义（排在最后） |
 * | [marks] | `marks` | 备注（**不**进提示词） |
 * | [chatModelBindingMode] | 同名 | 跟随全局 / 固定配置 |
 * | [memoryProfileBindingMode] | 同名 | 跟随全局 / 固定配置 |
 * | [toolAccessConfig] | 同名 | 工具白名单 |
 * | [isDefault] | `isDefault` | 是否内置默认卡 |
 *
 * ## `marks` 与 `description` 为什么不进提示词
 *
 * Operit 的注释写得很明确：「备注信息（不会被拼接到提示词中）」。
 * 这不是遗漏而是设计：用户会把「这张卡是测 X 用的」「作者是谁」写在备注里，
 * 若拼接进提示词，模型的语气会被这些元信息污染。
 */
data class CharacterCard(
    val id: String,
    val name: String,
    /** 简介。界面上显示，**不**注入提示词。 */
    val description: String = "",
    /** 角色设定（引导词）。注入顺序第 1。 */
    val characterSetting: String = "",
    /** 开场白。新建会话时作为第一条助手消息的候选用。 */
    val openingStatement: String = "",
    /** 其他内容（聊天）。注入顺序第 2。 */
    val otherContentChat: String = "",
    /** 其他内容（语音）。语音模式下替代 [otherContentChat]。 */
    val otherContentVoice: String = "",
    /** 附着的标签 id 列表。注入顺序第 3（按 id 顺序）。 */
    val attachedTagIds: List<String> = emptyList(),
    /** 高级设置的自定义引导词。注入顺序第 4（最后）。 */
    val advancedCustomPrompt: String = "",
    /** 备注。**不**注入提示词。 */
    val marks: String = "",
    /** 对话模型绑定模式。 */
    val chatModelBindingMode: CharacterModelBindingMode = CharacterModelBindingMode.FollowGlobal,
    /** 固定绑定时的配置 id。 */
    val chatModelConfigId: String? = null,
    /** 固定绑定时的模型下标。 */
    val chatModelIndex: Int = 0,
    /** 记忆配置绑定模式。 */
    val memoryProfileBindingMode: CharacterMemoryBindingMode =
        CharacterMemoryBindingMode.FollowGlobal,
    /** 固定绑定时的记忆配置 id。 */
    val memoryProfileId: String? = null,
    /** 工具白名单。 */
    val toolAccessConfig: CharacterToolAccessConfig = CharacterToolAccessConfig(),
    /** 是否内置默认角色卡（不可删除）。 */
    val isDefault: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 是否为内置默认卡（[DEFAULT_ID]）。比 [isDefault] 更可靠：后者可被导入数据覆盖。 */
    val isBuiltinDefault: Boolean get() = id == DEFAULT_ID

    companion object {
        /**
         * 内置默认角色卡的固定 id。
         *
         * 与 Operit 的 `DEFAULT_CHARACTER_CARD_ID = "default_character"` 一致。
         * 固定字符串而不是 UUID：默认卡要在「首次启动自动创建」和
         * 「用户删光后恢复」两处被按 id 引用，用随机 id 就没法幂等创建。
         */
        const val DEFAULT_ID: String = "default_character"

        /** 默认卡显示名。 */
        const val DEFAULT_NAME: String = "Praxis"
    }
}

/**
 * 对话模型绑定模式。
 *
 * 用枚举而不是字符串常量 + `normalize()`（Operit 是后者）：
 * 字符串的好处是「未知值回落」，坏处是调用方永远不知道合法值有哪些。
 * 这里两者都保留 —— 枚举定义合法值，[fromStorage] 负责回落，
 * 存储层写 [storage]（字符串），保证与既有数据兼容。
 */
enum class CharacterModelBindingMode(val storage: String) {
    /** 跟随全局模型设置。 */
    FollowGlobal("FOLLOW_GLOBAL"),

    /** 固定用某个配置。 */
    FixedConfig("FIXED_CONFIG"),
    ;

    companion object {
        /** 存储值 → 枚举。未知值一律回落 [FollowGlobal]（与 Operit 的 normalize 一致）。 */
        fun fromStorage(value: String?): CharacterModelBindingMode =
            entries.firstOrNull { it.storage == value } ?: FollowGlobal
    }
}

/** 记忆配置绑定模式。语义与 [CharacterModelBindingMode] 对称。 */
enum class CharacterMemoryBindingMode(val storage: String) {
    FollowGlobal("FOLLOW_GLOBAL"),
    FixedProfile("FIXED_PROFILE"),
    ;

    companion object {
        fun fromStorage(value: String?): CharacterMemoryBindingMode =
            entries.firstOrNull { it.storage == value } ?: FollowGlobal
    }
}

/**
 * 角色卡的工具白名单。
 *
 * ## 语义：`enabled = false` 表示「不限制」
 *
 * 这一点最容易搞反。Operit 的默认值是 `enabled = false`，
 * 含义是「本卡不做额外限制，用全局的工具开关」——
 * **不是**「禁用所有工具」。
 *
 * 若反过来理解（false = 全禁），用户新建一张卡会发现 AI 什么都不能做，
 * 而界面上那个开关看起来又是关着的，极难排查。
 *
 * 所以：[enabled] 为 false 时 [CharacterToolFilter] 必须返回「全部放行」。
 */
data class CharacterToolAccessConfig(
    val enabled: Boolean = false,
    /** 允许的内置工具名。空列表在 [enabled] 为 true 时表示「一个都不许」。 */
    val allowedBuiltinTools: List<String> = emptyList(),
    /** 允许的插件包。 */
    val allowedPackages: List<String> = emptyList(),
    /** 允许的技能。 */
    val allowedSkills: List<String> = emptyList(),
    /** 允许的 MCP 服务。 */
    val allowedMcpServers: List<String> = emptyList(),
) {
    /**
     * 去掉首尾空白、去掉空串、去重。
     *
     * 从酒馆 JSON 或手写配置导入时，用户很容易带进 `" read_file"` 或空串。
     * 空串留在白名单里不会匹配任何工具，但会让「白名单非空」
     * 这个判断为真 —— 结果是一张「看着配了白名单、实际全禁」的卡。
     */
    fun normalized(): CharacterToolAccessConfig = copy(
        allowedBuiltinTools = normalizeEntries(allowedBuiltinTools),
        allowedPackages = normalizeEntries(allowedPackages),
        allowedSkills = normalizeEntries(allowedSkills),
        allowedMcpServers = normalizeEntries(allowedMcpServers),
    )

    /** 是否选了任何「外部」资源（插件/技能/MCP）。 */
    val hasExternalSelections: Boolean
        get() = allowedPackages.isNotEmpty() ||
            allowedSkills.isNotEmpty() ||
            allowedMcpServers.isNotEmpty()

    private fun normalizeEntries(values: List<String>): List<String> =
        values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/**
 * 提示词标签。
 *
 * 与 Operit 的 `PromptTag` 字段一致（id/name/description/promptContent/tagType）。
 * 标签是**可复用**的提示词片段：同一段「语气要冷淡」可以挂到多张角色卡上，
 * 改一次全部生效 —— 这是它与「直接写进 advancedCustomPrompt」的关键差别。
 */
data class PromptTag(
    val id: String,
    val name: String,
    val description: String = "",
    /** 标签的提示词内容。只有非空时才被注入。 */
    val promptContent: String = "",
    val tagType: PromptTagType = PromptTagType.Custom,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 标签类型。枚举名与 Operit 的 `TagType` 一一对应。 */
enum class PromptTagType(val storage: String, val label: String) {
    /** 语气风格。 */
    Tone("TONE", "语气"),

    /** 角色设定。 */
    Character("CHARACTER", "角色"),

    /** 功能性。 */
    Function("FUNCTION", "功能"),

    /** 自定义。 */
    Custom("CUSTOM", "自定义"),
    ;

    companion object {
        fun fromStorage(value: String?): PromptTagType =
            entries.firstOrNull { it.storage == value } ?: Custom
    }
}

/**
 * 提示词用途：聊天还是语音。
 *
 * 决定用 [CharacterCard.otherContentChat] 还是 [CharacterCard.otherContentVoice]。
 * Operit 用 `PromptFunctionType`，这里保持同名语义但收敛成两值 ——
 * 它的枚举实际也只有这两个分支在 `combinePrompts` 里被用到。
 */
enum class PromptFunctionType {
    Chat,
    Voice,
}
