package dev.praxis.core.ai

/**
 * AI 层契约：与具体 provider 无关，只描述「一次请求长什么样、回来的流长什么样」。
 *
 * 本模块不允许出现 HTTP 客户端、密钥、重试实现 —— 那是 `core:ai:provider` 的事。
 * 这样 provider 换掉（OpenAI / Anthropic / 本地 llama）时，上层一行不用改。
 */

/** 思考强度。用于把「让模型想多久」从各家不同参数里抽象出来。 */
enum class ThinkingEffort {
    None,
    Low,
    Medium,
    High,
}

/** 聊天角色。工具结果用 [Tool]，不复用 [User]。 */
enum class ChatRole {
    System,
    User,
    Assistant,
    Tool,
}

/**
 * 一段对话内容。
 *
 * [toolCalls] 与 [toolResults] 分开存：一条 assistant 消息可以同时有文字与多个工具调用，
 * 而工具结果是独立的 tool 消息。合并成一个字段会让「工具没跑完就断了」无法表达。
 */
data class ChatMessage(
    val role: ChatRole,
    val text: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResult: ToolResult? = null,
)

/** 模型发起的一次工具调用。 */
data class ToolCall(
    val id: String,
    val name: String,
    /** 原始 JSON 字符串。**永不截断**：截断会产生非法 JSON，上游直接 400。 */
    val argumentsJson: String,
)

/** 工具执行结果。 */
data class ToolResult(
    val callId: String,
    val content: String,
    val isError: Boolean = false,
)

/** 一次请求的入参。 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDescriptor> = emptyList(),
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val thinkingEffort: ThinkingEffort = ThinkingEffort.None,
)

/** 工具声明（发给模型的那一份）。 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    /** 参数 JSON Schema（已序列化好的字符串）。 */
    val parametersJson: String,
)

/** 流式事件。UI 只消费这三种，不需要知道 SSE 协议细节。 */
sealed interface AiStreamEvent {

    /** 增量文本。可能为空串（心跳），调用方需容忍。 */
    data class Delta(val text: String) : AiStreamEvent

    /** 模型发起的工具调用已完整收齐（参数 JSON 可解析）。 */
    data class ToolCalls(val calls: List<ToolCall>) : AiStreamEvent

    /** 正常结束。[finishReason] 为 provider 给的原始值。 */
    data class Done(val finishReason: String?) : AiStreamEvent
}

/**
 * Provider 契约。
 *
 * 只实现 [stream] 一个方法：非流式调用 = 消费流并拼接，不需要第二套接口，
 * 避免两套实现行为不一致（这是最常见的 provider 层 bug 来源）。
 */
interface AiProvider {

    /** 展示名，用于日志与设置页。 */
    val displayName: String

    /**
     * 发起一次流式请求。
     *
     * 失败一律抛 [AiException]，调用方按 [AiException.error] 归类处理，
     * 不要自己 catch 具体异常类型（那会把 provider 细节泄露到 UI 层）。
     */
    fun stream(request: ChatRequest): kotlinx.coroutines.flow.Flow<AiStreamEvent>
}

/** 统一的失败包装。 */
class AiException(val error: AiError) : Exception(error.message, error.cause)
