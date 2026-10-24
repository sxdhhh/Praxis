package dev.praxis.core.model.character

/**
 * 角色卡提示词组装与工具过滤。
 *
 * 全部是**纯函数**，没有 Context、没有 IO、没有 Flow —— 这样能用纯 JVM 单测
 * 锁住顺序与边界，而不是等到设备上发现「语气不对」再回来猜。
 */
object CharacterPromptComposer {

    /** 各段之间的分隔。与 Operit 一致：两个换行。 */
    private const val SECTION_SEPARATOR = "\n\n"

    /**
     * 组装角色卡提示词（1:1 复刻 Operit `CharacterCardManager.combinePrompts`）。
     *
     * ## 顺序是语义的一部分
     *
     * Operit 的拼接顺序固定为：
     *
     * ```
     * characterSetting   ← 角色设定（最重要，决定"你是谁"）
     * otherContent{Chat|Voice}  ← 其他内容（补充行为约束）
     * attachedTags[*].promptContent  ← 标签（可复用的片段）
     * advancedCustomPrompt  ← 高级自定义（排最后，用来覆盖前面）
     * ```
     *
     * 改动顺序会改变模型的表现：`advancedCustomPrompt` 放前面就压不住
     * 角色设定里与之矛盾的描述。所以这里不"优化"顺序，逐条照搬。
     *
     * ## 空段必须整段跳过，不能留下多余空行
     *
     * 每段都是 `if (isNotBlank()) { append(x); append("\n\n") }`。
     * 若某个字段是空串仍 append 分隔符，会攒出 `"\n\n\n\n\n\n"`，
     * 部分模型会把它当成「此处有意留白」而输出空行开头的回答。
     * 最后统一 [String.trim] 去掉尾部残留的 separators。
     *
     * ## 标签按 [tagLookup] 查，查不到的静默跳过
     *
     * 用户删掉一个标签后，卡片里的 `attachedTagIds` 会残留旧 id。
     * Operit 用 `mapNotNull { try { ... } catch { null } }` 处理 ——
     * 这里用可空返回的 lambda 表达同一语义，但**不吞异常**：
     * 异常应该由调用方暴露（若吞掉，标签系统坏了也看不出来），
     * 只有"id 不存在"这一种情况返回 null。
     *
     * @param card 角色卡
     * @param tagLookup 按 id 查标签；返回 null 表示该 id 已不存在
     * @param additionalTagIds 额外附加的标签（会话级临时标签）
     * @param functionType 聊天 / 语音，决定用哪个 otherContent
     * @return 合并后的提示词；全空时返回空串（调用方据此决定是否插入 system）
     */
    fun compose(
        card: CharacterCard,
        tagLookup: (String) -> PromptTag?,
        additionalTagIds: List<String> = emptyList(),
        functionType: PromptFunctionType = PromptFunctionType.Chat,
    ): String {
        // distinct() 的位置很重要：先合并再 distinct，与 Operit 一致。
        // 若只对 additionalTagIds 去重、不跨两个来源去重，
        // 「卡片自带 + 会话再加一次」会让同一段提示词出现两遍。
        val allTagIds = (card.attachedTagIds + additionalTagIds).distinct()

        val tags = allTagIds.mapNotNull { id -> tagLookup(id) }

        return buildString {
            if (card.characterSetting.isNotBlank()) {
                append(card.characterSetting)
                append(SECTION_SEPARATOR)
            }

            val otherContent = when (functionType) {
                PromptFunctionType.Voice -> card.otherContentVoice
                PromptFunctionType.Chat -> card.otherContentChat
            }
            if (otherContent.isNotBlank()) {
                append(otherContent)
                append(SECTION_SEPARATOR)
            }

            tags.forEach { tag ->
                if (tag.promptContent.isNotBlank()) {
                    append(tag.promptContent)
                    append(SECTION_SEPARATOR)
                }
            }

            if (card.advancedCustomPrompt.isNotBlank()) {
                append(card.advancedCustomPrompt)
                append(SECTION_SEPARATOR)
            }
        }.trim()
    }

    /**
     * 只用卡片自身字段组装（不含标签）。
     *
     * 用于「标签还没加载完就要出预览」的场景：界面上的提示词预览不能因为
     * 一次数据库查询没回来就整块空白。
     */
    fun composeWithoutTags(
        card: CharacterCard,
        functionType: PromptFunctionType = PromptFunctionType.Chat,
    ): String = compose(
        card = card,
        tagLookup = { null },
        additionalTagIds = emptyList(),
        functionType = functionType,
    )
}

/**
 * 按角色卡的白名单过滤工具。
 *
 * ## 关键语义（最容易写错的地方）
 *
 * `config.enabled == false` 表示**不做额外限制**，而不是「禁用所有工具」。
 * Operit 的默认值就是 `enabled = false`，含义是「用全局开关」。
 *
 * 所以两个分支必须分开：
 *
 * | enabled | 结果 |
 * |---|---|
 * | false | 全部放行（返回原列表） |
 * | true | 只保留在白名单里的 |
 *
 * 若把 false 当「全禁」，用户新建角色卡后 AI 一个工具都用不了，
 * 而界面上那个开关看起来是关着的 —— 这是最难排查的一类 bug。
 */
object CharacterToolFilter {

    /**
     * @param config 角色卡的工具白名单配置
     * @param allToolNames 当前全部可用工具名
     * @return 过滤后允许使用的工具名。顺序保持 [allToolNames] 的原顺序
     *   （UI 上工具列表的顺序不该因为过滤而跳动）
     */
    fun allowedTools(
        config: CharacterToolAccessConfig,
        allToolNames: List<String>,
    ): List<String> {
        if (!config.enabled) return allToolNames

        val allowed = config.normalized().allowedBuiltinTools.toSet()
        return allToolNames.filter { it in allowed }
    }

    /**
     * 单个工具是否可用。
     *
     * 与 [allowedTools] 必须给出**一致**的答案 —— 分两处实现是必要的，
     * 因为执行时判定的是单个工具（不想为每次执行都构造整个列表），
     * 但两处规则相同，这里用同一个归一化入口保证不漂移。
     */
    fun isAllowed(config: CharacterToolAccessConfig, toolName: String): Boolean {
        if (!config.enabled) return true
        return toolName in config.normalized().allowedBuiltinTools
    }
}
