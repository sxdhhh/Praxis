package dev.praxis.core.ai

/**
 * 摘要提示词与配置（1:1 移植 Operit `FunctionalPrompts`，`core/config/FunctionalPrompts.kt`）。
 *
 * ## 为什么中文与英文各一份
 *
 * 上游有两份完整提示词（`SUMMARY_PROMPT` / `SUMMARY_PROMPT_EN`），
 * 并按「对话内容是否中文」自动选（`LocaleUtils.usesChineseContent`）。
 *
 * 不是「翻译一下」——两份的结构略有不同：中文用 `【小节】`、
 * 英文用 `[Section]`；分隔线长度也不同（中文 28 个 `=`、英文 39 个）。
 * 直接翻译会在「用英文模型跑中文对话」时出现中英混排的标题，
 * 模型输出结构随之漂移。
 *
 * ## 为什么小节可配置
 *
 * 上游 `ConversationSummaryConfig` 允许三件事：
 *
 *  - `sectionOverrides`：改某个小节的标题 / 指令 / **关掉**；
 *  - `globalRules`：在提示词末尾追加全局规则；
 *  - `dialogueReviewEnabled`：是否在摘要末尾附「对话回顾」逐条清单。
 *
 * Praxis 只移植前两项 —— 第三项需要 `roleName` 与「已激活包热身」那一整套
 * 上下文，而 Praxis 没有多角色概念，搬过来只会产生空清单。
 */
object SummaryPrompts {

    /** 四个固定小节的 id（与上游 `summarySectionIds` 同名，便于对档）。 */
    val SECTION_IDS: List<String> = listOf("core_task", "interaction", "progress", "key_info")

    private val TITLES_ZH =
        listOf("核心任务状态", "互动情节与设定", "对话历程与概要", "关键信息与上下文")

    private val TITLES_EN = listOf(
        "Core Task Status",
        "Interaction & Scenario",
        "Conversation Progress & Overview",
        "Key Information & Context",
    )

    /** 选提示词。 */
    fun summaryPrompt(useEnglish: Boolean): String =
        if (useEnglish) SUMMARY_PROMPT_EN else SUMMARY_PROMPT

    fun sectionTitles(useEnglish: Boolean): List<String> =
        if (useEnglish) TITLES_EN else TITLES_ZH

    /**
     * 拼最终要发给模型的 system 提示词（对齐上游 `buildSummarySystemPrompt`）。
     *
     * 三件事，顺序有语义：
     *
     *  1. 取基础提示词（中 / 英）；
     *  2. 有小节覆盖时**重写**提示词正文（见 [applySectionOverrides]）；
     *  3. 追加「上一次的摘要」，最后追加全局规则。
     *
     * ## 为什么「上一次的摘要」放在 system 里而不是 user 里
     *
     * Praxis 旧实现把它放在 user 消息正文开头（「上一次的摘要：…」）。
     * 上游放在 system 尾部。差别在**权重**：放 user 里，模型会把它当成
     * 「用户这轮说的话的一部分」，可能回一句「好的，我理解你的摘要」；
     * 放 system 里它是背景约束，模型直接进入「生成新摘要」的任务。
     */
    fun buildSummarySystemPrompt(
        previousSummary: String?,
        useEnglish: Boolean,
        globalRules: String? = null,
        sectionOverrides: List<SummarySectionOverride> = emptyList(),
    ): String {
        var prompt = summaryPrompt(useEnglish).trimIndent()
        if (sectionOverrides.isNotEmpty()) {
            prompt = applySectionOverrides(sectionOverrides, useEnglish)
        }
        val withPrevious = appendPreviousSummary(prompt, previousSummary, useEnglish)
        val rules = globalRules?.trim()
        return if (rules.isNullOrBlank()) withPrevious else "$withPrevious\n\n$rules"
    }

    /**
     * 按覆盖项重写提示词正文。
     *
     * ## 实现方式：按标题切块再重组
     *
     * 不用「字符串替换」而是「切成 prefix / sections / suffix 三段再拼」：
     *
     * - 替换法要精确匹配原块（含换行与缩进），一处空格不同就静默失效；
     * - 切块法只在标题边界上操作，标题是提示词里最稳定的锚点。
     *
     * `enabled = false` 的小节整块消失（连标题一起），这正是
     * 「关掉某个小节」该有的效果 —— 只删内容留标题会让模型输出空小节。
     */
    fun applySectionOverrides(
        overrides: List<SummarySectionOverride>,
        useEnglish: Boolean,
    ): String {
        val template = splitTemplate(useEnglish)
        val byId = overrides.associateBy { it.id.trim() }
        return buildString {
            append(template.prefix)
            template.sections.forEach { section ->
                val override = byId[section.id]
                if (override?.enabled == false) return@forEach
                val title = override?.title?.trim()?.takeIf { it.isNotBlank() }
                val instruction = override?.instruction?.trim()?.takeIf { it.isNotBlank() }
                if (title == null && instruction == null) {
                    append(section.block)
                } else {
                    append(sectionHeader(title ?: section.title, useEnglish))
                    append('\n')
                    append(instruction ?: section.instruction)
                    append("\n\n")
                }
            }
            append(template.suffix)
        }
    }

    /** 解析出小节的默认形态（供设置页展示与「恢复默认」）。 */
    fun defaultSections(useEnglish: Boolean): List<SummarySectionOverride> =
        splitTemplate(useEnglish).sections.map {
            SummarySectionOverride(id = it.id, title = it.title, instruction = it.instruction)
        }

    // ── 内部：模板切分 ──────────────────────────────────────────────

    private data class Template(
        val prefix: String,
        val sections: List<Section>,
        val suffix: String,
    )

    private data class Section(
        val id: String,
        val title: String,
        val instruction: String,
        /** 原始块（含标题行），未覆盖时原样拼回。 */
        val block: String,
    )

    private fun splitTemplate(useEnglish: Boolean): Template {
        val prompt = summaryPrompt(useEnglish).trimIndent()
        val titles = sectionTitles(useEnglish)
        val separator = if (useEnglish) {
            "======================================="
        } else {
            "============================"
        }
        val starts = titles.map { title ->
            val header = sectionHeader(title, useEnglish)
            prompt.indexOf(header).also {
                check(it >= 0) { "摘要提示词缺少小节：$title" }
            }
        }
        val sections = starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: prompt.indexOf(separator, start)
            check(end >= 0) { "摘要提示词缺少小节结尾：${titles[index]}" }
            val block = prompt.substring(start, end)
            val instruction = block.substringAfter('\n').trimEnd()
            Section(
                id = SECTION_IDS[index],
                title = titles[index],
                instruction = instruction,
                block = block,
            )
        }
        val lastEnd = starts.last() + sections.last().block.length
        return Template(
            prefix = prompt.substring(0, starts.first()),
            sections = sections,
            suffix = prompt.substring(lastEnd),
        )
    }

    private fun sectionHeader(title: String, useEnglish: Boolean): String =
        if (useEnglish) "[$title]" else "【$title】"

    /**
     * 追加「上一次的摘要」（对齐上游 `appendPreviousSummary`）。
     *
     * 措辞里有两个关键约束：「用于继承上下文」与「生成一份全新的、更完整的」。
     * 前者告诉模型这段是要继承的旧内容（不是本轮输入）；
     * 后者明确要求**合并**而不是复述 —— 少了这句，模型常把旧摘要原样吐回来。
     */
    private fun appendPreviousSummary(
        prompt: String,
        previousSummary: String?,
        useEnglish: Boolean,
    ): String {
        if (previousSummary.isNullOrBlank()) return prompt
        return prompt + if (useEnglish) {
            "\n\nPrevious Summary (to inherit context):\n" +
                previousSummary.trim() + "\n" +
                "Please merge the key information from the previous summary with the new " +
                "conversation and generate a brand-new, more complete summary."
        } else {
            "\n\n上一次的摘要（用于继承上下文）：\n" +
                previousSummary.trim() + "\n" +
                "请将以上摘要中的关键信息，与本次新的对话内容相融合，" +
                "生成一份全新的、更完整的摘要。"
        }
    }
}

/**
 * 一个小节的覆盖项（对齐 Operit `SummarySectionOverride`）。
 *
 * 三个字段都可空，`null` 表示「沿用默认」。这样存储时只保存真正改过的项，
 * 默认值升级（改了提示词）能自动生效，不会被旧覆盖卡住。
 */
data class SummarySectionOverride(
    val id: String,
    val enabled: Boolean? = null,
    val title: String? = null,
    val instruction: String? = null,
)

/**
 * 上下文压缩（1:1 移植 Operit 的压缩链路）。
 *
 * ## 上游链路长什么样
 *
 * ```
 * ① 判定要不要压（shouldGenerateSummary）
 *      token 占用率 ≥ 阈值  或者  自上次摘要后用户消息数 ≥ 条数阈值
 * ② 挑出要折叠的范围（summarizeMemory）
 *      上一条 summary 之后的所有 user/ai 消息
 * ③ 逐条瘦身（condense*）           ← SummaryCondenser
 * ④ 调模型生成摘要（generateSummary）
 *      提示词 = FunctionalPrompts.SUMMARY_PROMPT（中/英，小节可覆盖）
 * ⑤ 摘要作为一条 sender="summary" 的消息插到队列里
 * ⑥ 后续请求只带「最后一条 summary 及其之后」的消息（getMemoryFromMessages）
 * ```
 *
 * ## Praxis 的表示差异
 *
 * 上游把摘要存成**一条消息**（可渲染、可点开看全文、可编辑）。Praxis 用
 * [CompactedContext]（summary 文本 + coveredCount 覆盖游标）表示：
 *
 * | 上游 | Praxis | 原因 |
 * |---|---|---|
 * | `sender == "summary"` 的消息 | `compacted.summary` | Praxis 消息表没有 sender 枚举 |
 * | `indexOfLast { it.sender == "summary" }` | `compacted.coveredCount` | 游标天然幂等 |
 * | 插入到「最后一条 ai 之后」 | 插在 system 之后 | 见 [CompactionApplier] |
 *
 * 压缩的**行为**（何时压、压多少、怎么瘦身、用什么提示词）与上游一致；
 * 只有「结果怎么存」不同。
 */
object ContextCompactor {

    /**
     * 触发压缩的默认阈值（上下文占上限的比例）。
     *
     * 0.75 而不是 0.9：压缩本身要一次模型调用，且摘要模型看到的输入也不小。
     * 卡在 0.95 再压，很可能「要压缩的那次请求自己就超限了」。
     *
     * 用户可在模型页改这个值（`ModelSettings.compactThreshold`）。
     */
    const val DEFAULT_THRESHOLD: Float = 0.75f

    /**
     * 压缩后保留为原样的最近消息条数。
     *
     * ## 上游没有这个常量，它用「上一条 summary 之后的全部」
     *
     * 上游每次压缩都折掉**除最后一条 summary 之后**的全部消息，然后
     * 立刻由 `sendMessageInternal(isAutoContinuation = true)` 触发续写 ——
     * 也就是说它**压缩完马上又发一轮**，所以不需要「留几条给用户看」。
     *
     * Praxis 不在压缩后自动续写（自动续写会消耗 token，且用户没要求）。
     * 所以必须保留最近若干条：否则用户刚说完的话被折进摘要，
     * 而屏幕上又看不到新回复，观感是「我的消息消失了」。
     */
    const val KEEP_RECENT_MESSAGES: Int = 8

    /** 一次压缩至少折叠这么多条，否则不值得花一次模型调用。 */
    private const val MIN_FOLD: Int = 2

    /**
     * 条数阈值默认值。
     *
     * 20 而不是 10：Praxis 保留最近 [KEEP_RECENT_MESSAGES]（8）条不折叠，
     * 用 10 会让「可折叠量」经常只有 2 条 —— 摘要模型输入太小、质量差，
     * 却照样花一次调用。20 能保证每次至少折叠十几条，摘要才有信息量。
     */
    const val DEFAULT_MESSAGE_COUNT_THRESHOLD: Int = 20

    /**
     * 组装压缩请求（`buildRequest`）。
     *
     * @param previousSummary 上一次摘要。上游把它拼进 **system** 尾部
     *   （`appendPreviousSummary`），不是 user 正文 —— 见 [SummaryPrompts]。
     * @param messages 本次要折叠的消息（已排除保留最近若干条）。
     * @param model 用哪个模型。调用方应传「摘要功能位」配置的模型。
     * @param useEnglish 用哪份提示词。默认按内容语言判断（见 [detectUseEnglish]）。
     * @param globalRules 追加在提示词末尾的全局规则。
     * @param sectionOverrides 小节覆盖。
     */
    fun buildRequest(
        previousSummary: String?,
        messages: List<ChatMessage>,
        model: String,
        useEnglish: Boolean = detectUseEnglish(messages),
        globalRules: String? = null,
        sectionOverrides: List<SummarySectionOverride> = emptyList(),
    ): ChatRequest {
        val systemPrompt = SummaryPrompts.buildSummarySystemPrompt(
            previousSummary = previousSummary,
            useEnglish = useEnglish,
            globalRules = globalRules,
            sectionOverrides = sectionOverrides,
        )
        return ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = ChatRole.System, text = systemPrompt),
                // 对话正文单独一条 user 消息：与 system 分开，模型才知道
                // 「上面是任务说明、下面是待处理的材料」。
                ChatMessage(role = ChatRole.User, text = renderMessages(messages)),
            ),
            // 摘要不需要创造性。温度调低：同一段对话两次压缩应得到相近结果，
            // 否则「用户看到的摘要」与「模型手里的摘要」会来回漂。
            temperature = 0.3,
        )
    }

    /**
     * 把消息渲染成「带序号的角色 + 瘦身后正文」（对齐上游非群聊分支）。
     *
     * ## 为什么要加 `#序号`
     *
     * 上游写法是 `"#${index + 1}: $content"`。序号让摘要模型能引用
     * 「第 3 条提到的路径」这类指代，而摘要的「对话历程」小节正是要
     * 描述先后顺序 —— 没有序号时它只能写「之前」「后来」这类模糊表述。
     *
     * ## 为什么不用 JSON
     *
     * JSON 的键名与转义会白占可观比例（工具结果里的代码转义后接近翻倍），
     * 而压缩本来就是为了省上下文。
     */
    fun renderMessages(messages: List<ChatMessage>): String = buildString {
        messages.forEachIndexed { index, message ->
            // 按角色走不同瘦身策略 —— 这是上游 summarizeMemory 的核心。
            val body = when (message.role) {
                ChatRole.User -> SummaryCondenser.condenseUser(message.text)
                ChatRole.Assistant -> SummaryCondenser.condenseAssistant(message.text)
                else -> SummaryCondenser.headTail(message.text, 240, 96)
            }
            if (body.isNotBlank()) {
                append("#")
                append(index + 1)
                append(": ")
                append(body)
                append("\n")
            }
            // 工具调用 / 结果单独成行：它们不是「模型的正文」，
            // 混进同一条会破坏序号的可读性。
            message.toolCalls.forEach { call ->
                append("  [#")
                append(index + 1)
                append(" 工具调用] ")
                append(call.name)
                append("(")
                append(call.argumentsJson)
                append(")\n")
            }
            message.toolResult?.let { result ->
                val status = if (result.isError) "失败" else "成功"
                append("  [#")
                append(index + 1)
                append(" 工具结果(")
                append(status)
                append(")] ")
                append(SummaryCondenser.headTail(result.content, 140, 56))
                append("\n")
            }
        }
    }

    /**
     * 自动判断该用哪份提示词（对齐上游 `LocaleUtils.usesChineseContent(context)`）。
     *
     * 上游按上下文配置的 locale 判，但那依赖 `context`；纯 JVM 里只能看内容。
     *
     * ## 为什么门槛是 30 个汉字而不是 1
     *
     * 中文对话里常夹着英文（路径、命令、代码）。反过来，英文对话里也可能
     * 出现一两个汉字（用户偶尔打中文）。只判「有没有汉字」会让
     * 「英文技术讨论 + 一个中文词」误判成中文语境。
     * 30 个汉字是一个短句的量级，低于它基本可以认为只是零星出现。
     */
    fun detectUseEnglish(messages: List<ChatMessage>): Boolean {
        var cjk = 0
        for (message in messages) {
            for (ch in message.text) {
                if (ch.code in 0x4E00..0x9FFF) {
                    cjk++
                    if (cjk >= 30) return false
                }
            }
        }
        return true
    }

    /**
     * 计算「压缩窗口」：应当折叠的范围。
     *
     * @return 折叠条数（从历史开头算）。返回 0 表示不值得压缩。
     *
     * ## 为什么保留最近 [KEEP_RECENT_MESSAGES] 条而不是按比例
     *
     * 按比例（例如留 20%）在短对话里会留太少 —— 只有 12 条时留 2 条，
     * 用户刚说的话就被折进摘要，而他明明还看得见屏幕。
     * 按固定条数：无论多长，最近这一段总是原样保留，与用户看到的画面一致。
     */
    fun compactCount(totalMessages: Int): Int {
        val foldable = totalMessages - KEEP_RECENT_MESSAGES
        if (foldable < MIN_FOLD) return 0
        return foldable
    }

    /**
     * 是否该压缩（对齐上游 `shouldGenerateSummary` 的**双阈值**判定）。
     *
     * ## 上游的两个条件
     *
     * ```
     * if (maxTokens > 0 && currentTokens / maxTokens >= tokenUsageThreshold) return true
     * if (enableByMessageCount && userMessagesSinceLastSummary >= countThreshold) return true
     * ```
     *
     * 第二个条件是 Praxis 旧实现**完全缺失**的：
     *
     * - 只靠 token 阈值时，若用户把「上下文上限」填成 0（未知），
     *   `maxTokens > 0` 恒假 → **永不压缩**；
     * - 即使填了上限，短消息密集的对话（用户连续追问十几轮）
     *   可能体积不大但轮次很多，摘要质量反而更需要刷新。
     *
     * ## `strictThreshold` 为真时只认 token
     *
     * 上游的条数阈值默认是关的。Praxis 默认打开（[DEFAULT_MESSAGE_COUNT_THRESHOLD]），
     * 因为用户要求「一比一引入压缩」，而条数阈值是上游能力的一部分。
     * 需要严格按 token 时传 `strictThreshold = true`。
     *
     * @param currentTokens 当前上下文 token 数
     * @param maxTokens 上下文上限（0 = 未知）
     * @param tokenUsageThreshold 0..1
     * @param userMessagesSinceLastSummary 自上次摘要后的用户消息条数
     * @param messageCountThreshold 条数阈值（<=0 关闭）
     * @param strictThreshold 只按 token 判（忽略条数）
     */
    fun shouldCompact(
        currentTokens: Int,
        maxTokens: Int,
        tokenUsageThreshold: Float,
        userMessagesSinceLastSummary: Int,
        messageCountThreshold: Int = DEFAULT_MESSAGE_COUNT_THRESHOLD,
        strictThreshold: Boolean = false,
    ): Boolean {
        if (maxTokens > 0 && tokenUsageThreshold > 0f) {
            val ratio = currentTokens.toFloat() / maxTokens
            if (ratio >= tokenUsageThreshold) return true
        }
        if (strictThreshold) return false
        if (messageCountThreshold > 0 &&
            userMessagesSinceLastSummary >= messageCountThreshold
        ) {
            return true
        }
        return false
    }
}

/**
 * 压缩后的上下文。
 *
 * [summary] 是替换掉被折叠部分的那段文本；[coveredCount] 表示历史里
 * 前多少条已经被折进摘要。两者一起构成「幂等游标」：
 * 第二次压缩时只处理 `coveredCount` 之后的消息，
 * 且把新摘要**整体替换**旧摘要（而不是追加 —— 追加会让它无限增长，
 * 压缩本身反而撑爆上下文）。
 *
 * [createdAt] 供 UI 显示「摘要生成于何时」，也用于「点开看全文」这类定位。
 */
data class CompactedContext(
    val summary: String = "",
    val coveredCount: Int = 0,
    val createdAt: Long = 0L,
) {
    val isActive: Boolean get() = summary.isNotBlank()

    /** 摘要自身的 token 数（用于在 UI 上显示「压缩省了多少」）。 */
    val summaryTokens: Int get() = TokenEstimator.estimate(summary)

    companion object {
        /** 无压缩。 */
        val None: CompactedContext = CompactedContext()
    }
}

/**
 * 把 [CompactedContext] 应用到消息列表上。
 *
 * 放在 object 里而不是 `CompactedContext` 的方法：它是**消息列表的变换**，
 * 依赖 `ChatMessage` 的角色排序约定，与「压缩结果」这个数据结构是两件事。
 */
object CompactionApplier {

    /** 摘要消息的标题（UI 与发送内容共用同一份措辞）。 */
    const val SUMMARY_HEADER: String = "# 早前对话摘要"

    /**
     * 生成真正发给上游的消息列表。
     *
     * 顺序上有一条硬约束：摘要必须插在**所有 system 消息之后、第一条
     * 用户/助手消息之前**。
     *
     * 理由：本轮的用户指令在最末尾，system（含用户自定义系统提示词）
     * 在最前面。摘要夹在中间，模型读到的顺序是
     * 「你是谁 → 之前发生过什么 → 现在要你做什么」，这是它训练时的格式。
     * 把摘要塞到最后会变成一条「最新信息」，模型可能把它当指令执行。
     */
    fun apply(
        messages: List<ChatMessage>,
        compacted: CompactedContext,
    ): List<ChatMessage> {
        if (!compacted.isActive) return messages
        val summaryMessage = ChatMessage(
            role = ChatRole.System,
            text = "$SUMMARY_HEADER\n\n" +
                "以下是更早的对话（原始消息已因上下文长度被折叠）。" +
                "它是历史事实，不是本轮指令；请据此保持连贯，但一切以本轮用户请求为准。\n\n" +
                compacted.summary.trim(),
        )
        val leading = messages.takeWhile { it.role == ChatRole.System }
        val rest = messages.drop(leading.size)
        // 已经被折叠掉的那些消息不再发送：它们的信息已在摘要里。
        val remaining = rest.drop(compacted.coveredCount.coerceAtMost(rest.size))
        return leading + summaryMessage + remaining
    }
}
/**
 * 中文摘要提示词（Operit `FunctionalPrompts.SUMMARY_PROMPT` 原文，未改动一处措辞）。
 *
 * ## 为什么每一句都值得照抄
 *
 * 这份提示词里每条要求都对应一个真实的失效模式：
 *
 * | 要求 | 不这么做会怎样 |
 * |---|---|
 * | 「不得更改格式结构」 | 每轮摘要结构不同，下一轮摘要模型读不懂上一份 |
 * | 「宁可内容多一点」 | 摘要成了唯一历史参考，过度压缩等于永久删掉历史 |
 * | 「时序重点：先聚焦最后 30%」 | 长对话里最新指令淹没在早期内容里 |
 * | 「自包含：即使 AI 完全忘记」 | 摘要只写「继续上次的工作」，等于没写 |
 * | 「核心任务状态…最后补充进度拆解」 | 模型不知道自己做到哪了，会重复已完成的工作 |
 */
internal val SUMMARY_PROMPT: String = """
你是负责生成对话摘要的AI助手。你的任务是根据"上一次的摘要"（如果提供）和"最近的对话内容"，生成一份全新的、独立的、全面的摘要。这份新摘要将完全取代之前的摘要，成为后续对话的唯一历史参考。

**必须严格遵循以下固定格式输出，不得更改格式结构：**

==========对话摘要==========

【核心任务状态】
[先交代用户最新需求的内容与情境类型（真实执行/角色扮演/故事/假设等），再说明当前所处步骤、已完成的动作、正在处理的事项以及下一步。]
[明确任务状态（已完成/进行中/等待中），列出未完成的依赖或所需信息；如在等待用户输入，说明原因与所需材料。]
[显式覆盖信息搜集、任务执行、代码编写或其他关键环节的状态，哪怕某环节尚未启动也要说明原因。]
[最后补充最近一次任务的进度拆解：哪些已完成、哪些进行中、哪些待处理。]

【互动情节与设定】
[如存在虚构或场景设定，概述名称、角色身份、背景约束及其来源，避免把剧情当成现实。]
[用1-2段概括近期关键互动：谁提出了什么、目的为何、采用何种表达方式、对任务或剧情的影响，以及仍需确认的事项。]
[若用户给出剧本/业务/策略等非技术内容，提炼要点并说明它们如何指导后续输出。]

【对话历程与概要】
[用不少于3段描述整体演进，每段包含“行动+目的+结果”，可涵盖技术、业务、剧情或策略等不同主题，需特别点名信息搜集、任务执行、代码编写等阶段的衔接；如涉及具体代码，可引用关键片段以辅助说明。]
[突出转折、已解决的问题和形成的共识，引用必要的路径、命令、场景节点或原话，确保读者能看懂上下文和因果关系。]

【关键信息与上下文】
- [信息点1：用户需求、限制、背景或引用的文件/接口/角色等，说明其具体内容及作用。]
- [信息点2：技术或剧本结构中的关键元素（函数、配置、日志、人物动机等）及其意义。]
- [信息点3：问题或创意的探索路径、验证结果与当前状态。]
- [信息点4：影响后续决策的因素，如优先级、情绪基调、角色约束、外部依赖、时间节点。]
- [信息点5+：补充其他必要细节，覆盖现实与虚构信息。每条至少两句：先述事实，再讲影响或后续计划。]

============================

**格式要求：**
1. 必须使用上述固定格式，包括分隔线、标题标识符【】、列表符号等，不得更改。
2. 标题"对话摘要"必须放在第一行，前后用等号分隔。
3. 每个部分必须使用【】标识符作为标题，标题后换行。
4. "核心任务状态"、"互动情节与设定"、"对话历程与概要"使用段落形式；方括号只为示例，实际输出不需保留.
5. "关键信息与上下文"使用列表格式，每个信息点以"- "开头.
6. 结尾使用等号分隔线.

**内容要求：**
1. 语言风格：专业、清晰、客观.
2. 内容长度：不要限制字数，根据对话内容的复杂程度和重要性，自行决定合适的长度。可以写得详细一些，确保重要信息不丢失。宁可内容多一点，也不要因为过度精简导致关键信息丢失或失真。每个部分都要具备充分篇幅，绝不能以一句话敷衍.
3. 信息完整性：优先保证信息的完整性和准确性，技术与非技术内容都需提供必要证据或引用.
4. 内容还原：摘要既要说明“过程如何推进”，也要写清“实际产出/讨论内容是什么”，必要时引用结果文本、结论、代码片段或参数，确保在没有原始对话的情况下依然能完全还原信息本身.
5. 目标：生成的摘要必须是自包含的。即使AI完全忘记了之前的对话，仅凭这份摘要也能够准确理解历史背景、当前状态、具体进度和下一步行动.
6. 时序重点：请先聚焦于最新一段对话（约占输入的最后30%），明确最新指令、问题和进展，再回顾更早的内容。若新消息与旧内容冲突或更新，应以最新对话为准，并解释差异.
""".trimIndent()

/**
 * 英文摘要提示词（Operit `FunctionalPrompts.SUMMARY_PROMPT_EN` 原文）。
 *
 * 结构与中文版不同（`[Section]` 而非 `【小节】`、39 个 `=` 而非 28 个），
 * 所以不能靠翻译生成 —— 见 [SummaryPrompts] 的说明。
 */
internal val SUMMARY_PROMPT_EN: String = """
You are an AI assistant responsible for generating a conversation summary. Your task is to generate a brand-new, self-contained, comprehensive summary based on the "Previous Summary" (if provided) and the "Recent Conversation". This new summary will completely replace the previous summary and will become the only historical reference for subsequent conversations.

**You MUST follow the fixed output format below strictly. Do NOT change the structure.**

==========Conversation Summary==========

[Core Task Status]
[First describe the user's latest request and the scenario type (real execution / roleplay / story / hypothetical, etc.), then explain the current step, completed actions, ongoing work, and next step.]
[Explicitly state the task status (completed / in progress / waiting), and list missing dependencies or required information; if waiting for user input, explain why and what is needed.]
[Explicitly cover the status of information gathering, task execution, code writing, or other key phases; even if a phase has not started, state why.]
[Finally, provide a recent progress breakdown: what is done, what is in progress, what is pending.]

[Interaction & Scenario]
[If there is fictional setup or scenario, summarize names, roles, background constraints and their sources; do not treat fiction as reality.]
[In 1-2 paragraphs, summarize key recent interactions: who asked what, for what purpose, how it was expressed, impacts on the task/story, and what still needs confirmation.]
[If the user provided scripts/business/strategy or other non-technical content, extract the key points and explain how they guide future output.]

[Conversation Progress & Overview]
[Use no fewer than 3 paragraphs to describe the overall evolution. Each paragraph should include "action + intent + result". You may cover technical, business, story, or strategy topics. Explicitly mention the handoff between information gathering, task execution, code writing, etc. If relevant, quote key code snippets.]
[Highlight turning points, resolved issues, and agreements reached. Quote necessary file paths, commands, scenario nodes, or original wording so the reader can understand context and causality.]

[Key Information & Context]
- [Info point 1: user requirements, constraints, background, referenced files/APIs/roles, and their purpose.]
- [Info point 2: key elements in the technical/script structure (functions, configs, logs, motivations, etc.) and their meaning.]
- [Info point 3: exploration path, verification results, and current status.]
- [Info point 4: factors affecting future decisions, such as priorities, emotional tone, role constraints, external dependencies, deadlines.]
- [Info point 5+: any other necessary details covering both real and fictional information. Each point must have at least two sentences: state the fact, then explain its impact or next plan.]

=======================================

**Formatting requirements:**
1. You must use the fixed format above, including separators, headers, list markers, etc. Do not change them.
2. The title "Conversation Summary" must be on the first line, surrounded by '='.
3. Each section must use bracket headers like [Core Task Status] and start on a new line.
4. "Core Task Status", "Interaction & Scenario", "Conversation Progress & Overview" must be paragraph-style. Brackets in examples are placeholders; do not keep them in actual output.
5. "Key Information & Context" must be a list, each item starting with "- ".
6. End with the separator line.

**Content requirements:**
1. Style: professional, clear, objective.
2. Length: do not limit length. Decide an appropriate length based on complexity and importance. Prefer being detailed to avoid missing key information.
3. Completeness: prioritize completeness and accuracy. Provide evidence/quotes when needed.
4. Reconstruction: the summary must describe both "how the process progressed" and "what the actual outputs/discussion were". Quote resulting text, conclusions, code snippets, or parameters when needed.
5. Goal: the summary must be self-contained so that even if the AI forgets the original conversation, it can fully reconstruct context, current status, progress, and next actions.
6. Recency: focus first on the most recent part of the conversation (about the last 30% of input), then review earlier content. If new messages conflict with old content, use the latest messages and explain the differences.
""".trimIndent()

/**
 * 压缩的用户可配项（对齐 Operit ConversationSummaryConfig 的可用子集）。
 *
 * ## 为什么单独一个 data class
 *
 * 三项都有「默认值 = 上游默认」的语义，且都从设置页读。放进 [CompactedContext]
 * 会变成「压缩结果」的一部分（错位）；做成 [ContextCompactor] 的参数又会
 * 让每轮调用都传一大串。单独一个不可变配置对象最直白。
 *
 * @param messageCountThreshold 自上次摘要后的用户消息数达到多少就压缩。
 * @param globalRules 追加在摘要提示词末尾的全局规则（例如「务必保留所有文件路径」）。
 * @param useEnglish 强制指定提示词语言；null 表示按内容自动判断。
 */
data class CompactionSettings(
    val messageCountThreshold: Int = ContextCompactor.DEFAULT_MESSAGE_COUNT_THRESHOLD,
    val globalRules: String? = null,
    val useEnglish: Boolean? = null,
)
