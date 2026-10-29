package dev.praxis.core.ai

/**
 * AI 层契约：与具体 provider 无关，只描述「一次请求长什么样、回来的流长什么样」。
 *
 * 本模块不允许出现 HTTP 客户端、密钥、重试实现 —— 那是 `core:ai:provider` 的事。
 * 这样 provider 换掉（OpenAI / Anthropic / 本地 llama）时，上层一行不用改。
 */

/**
 * 思考强度。
 *
 * ## 为什么是 typealias 而不是在这里定义
 *
 * 它同时被 `ModelSettings`（`core:model`）与 `ChatRequest`（本模块）需要，
 * 而依赖方向是 `core:ai → core:model`。定义在这里会让 `core:model`
 * 必须反向依赖 `core:ai` —— 循环依赖，编译直接失败。
 *
 * 所以真正的枚举在 `dev.praxis.core.model.settings.ThinkingEffort`；
 * 这里保留别名，使既有的 `dev.praxis.core.ai.ThinkingEffort` 写法全部继续可用。
 */
typealias ThinkingEffort = dev.praxis.core.model.settings.ThinkingEffort

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
    /**
     * 附带图片（多模态输入）。
     *
     * ## 为什么挂在消息上而不是单独的 content parts 列表
     *
     * OpenAI 兼容协议里，带图片的 user 消息形态是：
     *
     * ```json
     * {"role":"user","content":[
     *   {"type":"text","text":"这是什么"},
     *   {"type":"image_url","image_url":{"url":"data:image/png;base64,…"}}
     * ]}
     * ```
     *
     * 也就是「文本 + 图片」混在同一个 `content` 数组里。用
     * `text: String` + `images: List<ChatImage>` 两个字段表达它，
     * 序列化时再拼成数组 —— 好处是**现有 44 处 `ChatMessage(...)` 调用点
     * 一行都不用改**（字段有默认值），而且 `text` 的语义没变
     * （它始终是「这条消息的文字部分」）。
     *
     * 若改成 `content: List<ContentPart>` 的通用模型，44 处调用点全要改，
     * 且 `ContextAssembler` / `SummaryCondenser` / `ToolExecutor` 里大量
     * 把 `text` 当字符串处理的地方都要加适配层 —— 收益只是理论上的通用性。
     *
     * ## 为什么必须有默认空列表
     *
     * 见上：保住 44 处调用点。也表达了「没有图片是常态」。
     */
    val images: List<ChatImage> = emptyList(),
)

/**
 * 一张随消息发送的图片。
 *
 * ## 为什么存 base64 而不是本地文件路径
 *
 * 协议要的是 `data:<mime>;base64,<payload>` 这种内联形态。存路径的话，
 * 序列化时还得同步读文件（在 IO 线程、且文件可能已被用户删掉/被
 * 系统清理）。而图片一旦被用户「发送」，它的内容就应该固定下来 ——
 * 这与聊天记录「不可变」的性质一致。
 *
 * 代价是内存：一张 1MB 的图变成约 1.37MB 的 base64 字符串。
 * 这也是必须在**选图时就压缩**的原因（见 `ChatImageData` 的说明），
 * 而不是把原图直接塞进来。
 *
 * ## 为什么 mimeType 单独存
 *
 * `data:` URL 必须声明类型（`data:image/png;base64,…`）。服务端靠它
 * 决定怎么解码；写错（比如把 jpeg 写成 png）在部分网关上会直接 400。
 * 所以它不能靠猜，必须在解码图片时从文件头/ImageDecoder 拿准。
 */
data class ChatImage(
    /** MIME 类型，如 `image/png` / `image/jpeg` / `image/webp`。 */
    val mimeType: String,
    /** 原始字节的 base64（**不含** `data:` 前缀，序列化时再拼）。 */
    val base64: String,
) {
    /**
     * 拼成协议要的 `data:` URL。
     *
     * 为什么不直接存成 `dataUrl` 一个字段：分开存让「换 mime 不重新编码」
     * 成为可能，也让日志里截断 base64 时不会把前缀一起截掉。
     */
    fun toDataUrl(): String = "data:$mimeType;base64,$base64"
}

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
    /**
     * 产生这个结果的工具名。
     *
     * ## 为什么必须有（2026-10-22 补）
     *
     * OpenAI 规范里 `role: "tool"` 消息的 `name` 是**可选**，但相当一批
     * 兼容网关（自建中转、部分国产平台）会校验它，并与上一条 assistant
     * 消息里 `tool_calls[].function.name` 比对；缺了就：
     *
     * ```
     * 400 invalid_request_error: tool message must have a name
     * ```
     *
     * 这类报错在「只用 OpenAI 官方」时永远看不到，所以很容易被判成
     * 「用户用的是劣质中转」。实际上是我们少发了一个字段。
     *
     * ## 为什么默认空串而不是 null
     *
     * 空串 = 「不知道名字」，序列化时**不写** name 字段（与旧行为一致）；
     * null 会强迫所有调用点处理，而大多数场景（无 tool 的历史消息、
     * 单测）确实没有名字。用空串能让旧代码零改动地继续工作。
     */
    val name: String = "",
)

/** 一次请求的入参。 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDescriptor> = emptyList(),
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val thinkingEffort: ThinkingEffort = ThinkingEffort.None,
    /**
     * 是否用流式（SSE）请求。
     *
     * ## 为什么这个开关必须存在（2026-10-24）
     *
     * 此前 provider 里写死 `put("stream", true)`，用户没有任何办法关掉它。
     * 两种真实场景需要关：
     *
     *  1. **会吐 `<think>` / `<tool>` 标签的模型**：逐字渲染时先显示半个标签，
     *     再被解析器吃掉，正文会剧烈抖动；
     *  2. **网络极差**：一段段跳字的观感比等一整段更差，还更容易断在半路。
     *
     * ## 非流式下 UI 仍然要有反馈
     *
     * 关掉流式不等于关掉进度：`stage` 仍会停在 `Connecting`，
     * 收到完整响应后一次性 seal 进气泡。所以这里只影响**传输方式**，
     * 不影响状态机的阶段语义。
     */
    val stream: Boolean = true,
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

    /**
     * 工具调用参数正在流式生成（进度上报，不携带可执行内容）。
     *
     * ## 为什么需要这个事件（2026-10-29）
     *
     * 上游发 tool_calls 是**分片**的，参数可能几十 KB（写长文件、传大 JSON）。
     * 在只有 [ToolCalls] 一个终态事件的情况下，从第一个分片到收齐之间
     * 界面上**一个字节都不显示** —— 用户看到的就是「光标一直闪，像卡死」。
     *
     * 这个事件只服务于「让用户知道它在动」，因此：
     *
     *  - 不携带 arguments 片段（避免上层误当完整 JSON 去解析）；
     *  - 只给累计字符数与工具名（够画「write_file · 1.2k 字」）；
     *  - 允许高频（每个分片一条），上层自己做节流。
     *
     * 纯进度事件，**不参与**任何协议回传，丢弃它是安全的。
     */
    data class ToolCallDelta(
        /** 已收到参数的总字符数（跨所有分片累计）。 */
        val argumentsChars: Int,
        /** 当前正在生成的工具名（第一片之后才已知，可能为空）。 */
        val toolName: String,
        /** 本次回复里第几个工具调用（0 起）。 */
        val callIndex: Int,
    ) : AiStreamEvent
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
