package dev.praxis.core.ai

/**
 * 把 [CompactionPolicy]（逐条压缩）与 [AiContextBudget]（整条丢弃）串成一步。
 *
 * ## 为什么要有这一层
 *
 * 两个组件各自都写好了、也各有单测，但**都曾被漏接**在发送链路上：
 * `CompactionPolicy` 建完之后没有任何生产调用点，`AiContextBudget` 更是只存在于
 * 设计文档里。单测全绿完全掩盖了这件事 —— 因为它们只验证函数本身。
 *
 * 所以这里不只是「把两步串起来」，更是**唯一的接线点**：`ChatViewModel` 只需要调
 * 一个函数，就不会出现「只压缩不丢弃」或反过来的一半接线。
 *
 * ## 顺序为什么不能反
 *
 * 先压缩再丢弃。反过来（先丢后压）在预算紧张时会先扔掉整条消息，
 * 而压缩本可以把它们压到能留下 —— 结果同样是装得下，但保留了更多对话轮次。
 */
object ContextAssembler {

    /**
     * 组装最终发给上游的消息列表。
     *
     * @param messages 原始历史（含 system 注入、工具调用与结果）
     * @param maxChars 字符预算
     * @param compact 是否做逐条压缩。默认开；关掉只做丢弃时行为更容易预期。
     */
    fun assemble(
        messages: List<ChatMessage>,
        maxChars: Int = AiContextBudget.DEFAULT_MAX_CHARS,
        compact: Boolean = true,
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages

        val compacted = if (compact) messages.map(::compactOne) else messages
        return AiContextBudget.fit(compacted, maxChars)
    }

    /**
     * 压缩单条消息。
     *
     * 三处细节都对应真实故障：
     *  - **`argumentsJson` 原样保留**：截断会产生非法 JSON，上游直接 400，
     *    而且重试也救不回来（重试发的还是同一份被截断的参数）。
     *  - **工具结果用更紧的阈值**：工具返回体经常是几千字的文件内容，
     *    比闲聊文本更容易把预算吃穿。
     *  - **system 不压缩**：技能清单与记忆注入被截断会让模型行为突变，
     *    而且用户看到的是「AI 突然不按技能说话」，无从排查。
     */
    private fun compactOne(message: ChatMessage): ChatMessage {
        if (message.role == ChatRole.System) return message

        val text = when (message.role) {
            ChatRole.Tool -> CompactionPolicy.compactToolResult(message.text)
            else -> CompactionPolicy.compactText(message.text)
        }

        val result = message.toolResult?.let { r ->
            r.copy(content = CompactionPolicy.compactToolResult(r.content))
        }

        return message.copy(
            text = text,
            // toolCalls 里的 argumentsJson 不动：这是硬约束。
            toolCalls = message.toolCalls.map { it.copy(argumentsJson = CompactionPolicy.compactArguments(it.argumentsJson)) },
            toolResult = result,
        )
    }
}