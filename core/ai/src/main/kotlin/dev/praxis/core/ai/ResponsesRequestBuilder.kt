package dev.praxis.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI **Responses** 协议的出站编码（文档 §5.1，`ResponsesRequestBuilder`）。
 *
 * ## 与 Chat Completions 的五处差异（这就是为什么不能复用同一个 builder）
 *
 * 1. system 提示词走顶层 `instructions`，不是 `messages` 里的一条；
 * 2. 对话在 `input` 数组里，不是 `messages`；
 * 3. **工具调用不是消息的一部分**：assistant 的调用与工具结果各自是 input 里的
 *    一个**独立条目**（`function_call` / `function_call_output`），靠 `call_id` 关联；
 * 4. 上限字段叫 `max_output_tokens`（不是 `max_tokens`）；
 * 5. 内容块类型名不同：`input_text` / `output_text` / `input_image`。
 *
 * 第 3 条最容易写错：把它当成 Chat 的 `tool_calls` 字段嵌进 assistant 消息里，
 * 上游会报「input[1] 不是合法的 item」。
 */
object ResponsesRequestBuilder {

    private const val DEFAULT_MAX_OUTPUT_TOKENS: Int = 4096

    fun build(request: ChatRequest): String {
        val instructions = request.messages
            .filter { it.role == ChatRole.System }
            .map { it.text }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        val payload = buildJsonObject {
            put("model", request.model)
            put("stream", request.stream)
            put("temperature", request.temperature)
            put("max_output_tokens", request.maxTokens ?: DEFAULT_MAX_OUTPUT_TOKENS)
            if (instructions.isNotBlank()) put("instructions", instructions)

            // 工具按名去重（与 Anthropic 同一条坑，网关 #513）。
            val tools = request.tools.distinctBy { it.name }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", parseOrEmptySchema(tool.parametersJson))
                        }
                    }
                }
            }

            putJsonArray("input") {
                request.messages.forEach { message ->
                    when (message.role) {
                        ChatRole.System -> Unit

                        ChatRole.Tool -> {
                            // 工具结果：一个独立条目，不是一条 role=tool 的消息。
                            addJsonObject {
                                put("type", "function_call_output")
                                put("call_id", message.toolResult?.callId.orEmpty())
                                put("output", message.toolResult?.content.orEmpty())
                            }
                        }

                        ChatRole.Assistant -> {
                            if (message.text.isNotBlank()) {
                                addJsonObject {
                                    put("role", "assistant")
                                    putJsonArray("content") {
                                        addJsonObject {
                                            put("type", "output_text")
                                            put("text", message.text)
                                        }
                                    }
                                }
                            }
                            message.toolCalls.forEach { call ->
                                addJsonObject {
                                    put("type", "function_call")
                                    put("call_id", call.id)
                                    put("name", call.name)
                                    // arguments 在 Responses 里是**字符串**（与 Chat 一致，
                                    // 与 Anthropic 的 input 对象相反）—— 别把两者写混。
                                    put("arguments", call.argumentsJson)
                                }
                            }
                        }

                        else -> {
                            addJsonObject {
                                put("role", "user")
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "input_text")
                                        put("text", message.text)
                                    }
                                    message.images.forEach { image ->
                                        addJsonObject {
                                            put("type", "input_image")
                                            put("image_url", image.toDataUrl())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return payload.toString()
    }

    /** 解析工具 schema；解析不了就给空 object schema（不把坏字符串发出去）。 */
    private fun parseOrEmptySchema(json: String): JsonObject =
        runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { }
            }
}
