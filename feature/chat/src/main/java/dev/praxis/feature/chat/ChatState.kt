package dev.praxis.feature.chat

import dev.praxis.core.ai.AiError

/** 消息在 UI 上的角色（与 core:ai 的 ChatRole 分开：UI 多一个 Error 态）。 */
enum class BubbleRole {
    User,
    Assistant,
    Tool,
    Error,
}

/**
 * 一条消息的 UI 投影。
 *
 * [id] 用 Long 自增而非随机 UUID：列表 key 要稳定且可比较，
 * 随机值在重组时不会变，但排序时会乱。
 */
data class ChatBubble(
    val id: Long,
    val role: BubbleRole,
    val text: String,
    /** 是否还在流式写入（决定是否显示光标）。 */
    val streaming: Boolean = false,
    /**
     * 工具调用信息（仅 [BubbleRole.Tool] 有值）。
     *
     * 单独一个字段而不是塞进 [text]：工具卡片要能折叠、能单独渲染耗时与风险，
     * 塞进正文就只能当纯文本，用户没法区分“模型说的话”与“工具返回的内容”。
     */
    val tool: ToolCallInfo? = null,
)

/** 一次工具调用的 UI 投影。 */
data class ToolCallInfo(
    val name: String,
    /** 原始 JSON 参数。**不截断**：截断后无法复制出去复现。 */
    val argumentsJson: String,
    /** 工具返回体。执行中为 null。 */
    val result: String? = null,
    /** 是否失败。 */
    val isError: Boolean = false,
    /** 耗时（毫秒）。未完成时为 null。 */
    val elapsedMs: Long? = null,
)

/** 聊天页状态。 */
data class ChatState(
    val bubbles: List<ChatBubble> = emptyList(),
    val input: String = "",
    val isStreaming: Boolean = false,
    /** 当前错误；非 null 时显示错误气泡与重试入口。 */
    val error: AiError? = null,
) {
    val canSend: Boolean get() = input.isNotBlank() && !isStreaming
}

/** 一次性事件（不该进状态，否则重组会重复触发）。 */
sealed interface ChatEvent {
    /** 流式写入的增量。 */
    data class Delta(val bubbleId: Long, val text: String) : ChatEvent

    /** 流结束。 */
    data class Finished(val bubbleId: Long) : ChatEvent

    /** 失败。 */
    data class Failed(val bubbleId: Long, val error: AiError) : ChatEvent
}
