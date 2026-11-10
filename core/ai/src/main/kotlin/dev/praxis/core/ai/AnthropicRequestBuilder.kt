package dev.praxis.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Anthropic Messages 协议的**出站编码**（文档 §5.1，`AnthropicRequestBuilder`）。
 *
 * ## 为什么单独一个文件、且放在 core
 *
 * 它是纯函数（`ChatRequest` 进、JSON 字符串出），放 core 就能逐字段单测；
 * 混在 provider 里就只能靠整包编译保证没写错 —— 而协议字段写错的表现是
 * 上游一句 `400 invalid_request_error`，看不出哪里错了。
 *
 * ## 与 OpenAI 兼容的三处实质差异
 *
 * 1. **system 是顶层字段**，不是 `messages` 里 role=system 的一条；
 * 2. **content 永远是数组**（文本也是一个 `{"type":"text"}` 块），不像 OpenAI
 *    可以用裸字符串；
 * 3. **工具结果的形态完全不同**：Anthropic 把它放在 **user 消息的 content 里**
 *    作为一个 `tool_result` 块，而不是独立的 `role: "tool"` 消息。
 *
 * 第 3 条最容易写错 —— 写错时上游不会说「你 tool_result 放错了」，
 * 它只会说消息角色不对。
 */
object AnthropicRequestBuilder {

    /** Anthropic 要求的版本头；缺了直接 400。 */
    const val API_VERSION: String = "2023-06-01"

    /** `max_tokens` 必填（Anthropic 不给我们默认的机会）。 */
    private const val DEFAULT_MAX_TOKENS: Int = 4096

    /**
     * 把统一形态的 [request] 编成 `/v1/messages` 的请求体。
     *
     * @param thinking 是否带上 extended thinking 参数。Anthropic 的 thinking
     *   与 OpenAI 的 reasoning 不是一回事，这里只在**用户显式开了思考**时才带，
     *   避免给不支持它的模型发一个会 400 的字段。
     */
    fun build(
        request: ChatRequest,
        thinking: Boolean = false,
        /**
         * 给每个工具补 `type: "custom"`（上游 quirks.anthropicToolType）。
         *
         * MiniMax 的 Claude 兼容端点**拒绝没有 type 的工具**；而 DeepSeek 恰好相反
         * （拒绝 `type:"custom"`），所以这是**按家特判**，默认关。
         */
        toolTypeCustom: Boolean = false,
    ): String {
        val systemParts = request.messages
            .filter { it.role == ChatRole.System }
            .map { it.text }
            .filter { it.isNotBlank() }

        val payload = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens ?: DEFAULT_MAX_TOKENS)
            put("stream", request.stream)
            put("temperature", request.temperature)
            if (systemParts.isNotEmpty()) {
                // 多条 system 合成一条：Anthropic 只认一个顶层 system。
                put("system", systemParts.joinToString("\n\n"))
            }
            if (thinking) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", (request.maxTokens ?: DEFAULT_MAX_TOKENS) / 2)
                }
            }
            // 工具按名去重（文档 §5.1 要点 2）：重名会让上游 400（网关 #513）。
            val tools = request.tools.distinctBy { it.name }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            // 按家特判（quirks.anthropicToolType）：见 build 的参数说明。
                            if (toolTypeCustom) put("type", "custom")
                            put("name", tool.name)
                            put("description", tool.description)
                            // parametersJson 是**已经序列化好的字符串**，必须原样嵌进去；
                            // 再套一层字符串会让上游收到的 input_schema 是个字符串。
                            put("input_schema", parseOrEmptySchema(tool.parametersJson))
                        }
                    }
                }
            }
            putJsonArray("messages") {
                request.messages
                    .filter { it.role != ChatRole.System }
                    .forEach { message -> add(encodeMessage(message)) }
            }
        }
        return payload.toString()
    }

    /**
     * 一条消息编成 Anthropic 的形态。
     *
     * 工具结果在这里被**搬进 user 消息**（见类注释第 3 条）。
     */
    private fun encodeMessage(message: ChatMessage): JsonObject = buildJsonObject {
        when (message.role) {
            ChatRole.Tool -> {
                // 工具结果：Anthropic 的 user 消息里放 tool_result 块。
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", message.toolResult?.callId.orEmpty())
                        put("content", message.toolResult?.content.orEmpty())
                        if (message.toolResult?.isError == true) put("is_error", true)
                    }
                }
            }

            ChatRole.Assistant -> {
                put("role", "assistant")
                putJsonArray("content") {
                    if (message.text.isNotBlank()) {
                        addJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        }
                    }
                    message.toolCalls.forEach { call ->
                        addJsonObject {
                            put("type", "tool_use")
                            put("id", call.id)
                            put("name", call.name)
                            // argumentsJson 原样嵌入（它是完整 JSON，不是字符串）。
                            put("input", parseOrEmptyObject(call.argumentsJson))
                        }
                    }
                }
            }

            else -> {
                // user（以及任何非 system/assistant/tool 的角色）
                put("role", "user")
                putJsonArray("content") {
                    message.images.forEach { image ->
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", image.mimeType)
                                put("data", image.base64)
                            }
                        }
                    }
                    // 文本块即使为空也写：Anthropic 不接受空 content 数组。
                    if (message.text.isNotBlank() || message.images.isEmpty()) {
                        addJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        }
                    }
                }
            }
        }
    }

    /** 解析工具 schema；解析不了就给个空对象 schema（而不是把坏字符串发出去）。 */
    private fun parseOrEmptySchema(json: String): JsonObject =
        runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: emptySchema()

    private fun parseOrEmptyObject(json: String): JsonObject =
        runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())

    private fun emptySchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }
}
