package dev.praxis.core.ai

/**
 * 上下文预算：把一段消息列表裁到「模型能吃得下」的规模。
 *
 * ## 与 [CompactionPolicy] 的分工（设计文档 03-business 1.5）
 *
 *  - **本类只做丢弃**：整条消息地扔，不改写任何一条的内容。
 *  - **[CompactionPolicy] 只做压缩**：逐条截断文本，不增减消息条数。
 *
 * 两者串联的顺序是「先压缩、后丢弃」：压缩后每条都变小，能保住更多轮次的
 * 上下文骨架（谁在什么时候说了什么），比直接砍掉半段对话更可读。
 *
 * ## 为什么建类而不是在调用点写循环
 *
 * 裁剪规则有三条硬约束，散在调用点必然写漏（这正是本类存在的原因：
 * 之前 [CompactionPolicy] 写好了但没人调，长对话把完整历史直接发给上游）：
 *
 *  1. **system 消息永不丢弃**。它承载技能清单与记忆注入，丢了模型会「失忆」
 *     且行为突变，是用户可见的退化。
 *  2. **最后一条 user 消息永不丢弃**。它是用户当下的问题，丢了等于没问。
 *  3. **工具调用与它的结果必须成组保留**。只留 `Assistant(toolCalls)` 会得到
 *     「模型要调工具但没有结果」；只留 `Tool(result)` 会得到「有结果但没有请求」。
 *     两种畸形序列都会被上游直接 400。
 *
 * ## 什么是「一条消息的成本」
 *
 * 用字符数估算而不是 token 数：本项目不引 tokenizer（各 provider 分词器不同，
 * 引一个必然对另一个不准）。字符数是**相对量**，用来决定「先扔谁」足够，
 * 不追求绝对精确 —— 这条取舍写在设计文档里，不是临时决定。
 */
object AiContextBudget {

    /**
     * 默认预算（字符）。
     *
     * 约合 2 万 token（中英混合按 1 字符 ≈ 0.6 token 粗估）。
     * 主流模型 32k~128k 上下文都能容纳，同时留足输出空间。
     */
    const val DEFAULT_MAX_CHARS = 32_000

    /**
     * 单条消息的字数。
     *
     * 有 `toolCalls` 时把参数 JSON 也算进去 —— 它确实占上游的 token，
     * 不算会让「看起来很小」的工具调用把预算吃穿。
     */
    fun costOf(message: ChatMessage): Int {
        var n = message.text.length
        for (call in message.toolCalls) n += call.name.length + call.argumentsJson.length
        message.toolResult?.let { n += it.content.length }
        return n
    }

    /**
     * 裁到预算内。
     *
     * 返回的新列表保持原顺序；不做 [CompactionPolicy] 的压缩（那是调用方的事，
     * 两步分开才能分别测试与分别解释）。
     *
     * 丢弃顺序：从**最旧的非 system 消息**开始。理由是最旧的上下文对当前回答
     * 贡献最小，而最近的几轮几乎总是最相关的。
     */
    fun fit(messages: List<ChatMessage>, maxChars: Int = DEFAULT_MAX_CHARS): List<ChatMessage> {
        if (maxChars <= 0) return messages
        var total = messages.sumOf { costOf(it) }
        if (total <= maxChars) return messages

        // 不可丢弃的项先算成下标集合，后面只在这个集合外挑受害者。
        // 用显式集合而不是「在循环里反复判 role」：规则一多，漏判一处就会发出畸形序列，
        // 而集合式写法让「哪些不可丢」只有一处定义。
        val lastUser = messages.indexOfLast { it.role == ChatRole.User }
        val pinned = messages.withIndex()
            .filter { (i, m) ->
                m.role == ChatRole.System ||
                    m.role == ChatRole.Tool ||
                    (m.role == ChatRole.Assistant && m.toolCalls.isNotEmpty()) ||
                    i == lastUser
            }
            .map { it.index }
            .toHashSet()

        val keep = BooleanArray(messages.size) { true }

        // 从最旧的、可丢弃的普通文本消息开始丢。
        for (i in messages.indices) {
            if (total <= maxChars) break
            if (i in pinned) continue
            keep[i] = false
            total -= costOf(messages[i])
        }

        // 走到这里仍然超预算：说明全是不可丢弃项（system 很长 / 一次超大工具输出）。
        // 此时保持原样返回，由 CompactionPolicy 在下一层把单条内容压小 ——
        // 在这里继续砍就会破坏「成组保留」的硬约束，宁可让上游报错也不要发出畸形序列。
        return messages.filterIndexed { i, _ -> keep[i] }
    }

    /** 是否已经装不下（供调用方决定要不要先压缩）。 */
    fun overBudget(messages: List<ChatMessage>, maxChars: Int = DEFAULT_MAX_CHARS): Boolean =
        messages.sumOf { costOf(it) } > maxChars
}