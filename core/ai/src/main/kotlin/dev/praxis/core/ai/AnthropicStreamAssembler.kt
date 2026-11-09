package dev.praxis.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Anthropic Messages 的**流式组装**（文档 §5.1，`AnthropicStreamAssembler`）。
 *
 * 把 SSE 的 `data:` JSON 一片片变成 [AiStreamEvent]。
 *
 * ## 两个必须齐的用量字段（文档 §5.1 要点 1）
 *
 * - `message_start.message.usage`：`input_tokens` 与 `cache_read_input_tokens`
 * - `message_delta.usage`：`output_tokens`
 *
 * 少任何一个，客户端算出来的缓存命中率就恒为 0（网关 issue #424）。
 * 所以用量不是「收到就往上报」，而是**攒到 finish() 一次性报**：
 * 输入在开头、输出在结尾，中途报任何一次都是不全的。
 *
 * ## 工具参数是分片到达的（要点 3）
 *
 * `input_json_delta.partial_json` 按 `index` 累积，到 `message_stop` 才算齐。
 * 中途只发 [AiStreamEvent.ToolCallDelta] 这种纯进度事件（让界面知道它在动），
 * 真正的 [AiStreamEvent.ToolCalls] 在 finish() 发。
 */
class AnthropicStreamAssembler {

    private var inputTokens: Int = 0
    private var cacheReadTokens: Int = 0
    private var outputTokens: Int = 0
    private var sawUsage: Boolean = false

    private var stopReason: String? = null
    private var errorMessage: String? = null

    /** 每个 content block 的状态（文本块不需要，工具块才需要累积）。 */
    private class ToolState(val id: String, val name: String) {
        val json = StringBuilder()
    }

    private val tools = linkedMapOf<Int, ToolState>()

    /** 上游给过的停止原因（`end_turn` / `max_tokens` / `tool_use`）。 */
    fun stopReason(): String? = stopReason

    /** 上游在流里报的 error（没有则为 null）。 */
    fun errorMessage(): String? = errorMessage

    /** 吃一片 SSE 的 data 内容。 */
    fun feed(payload: String): List<AiStreamEvent> {
        val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return emptyList()
        return when (root.type()) {
            "message_start" -> {
                root["message"]?.obj()?.get("usage")?.obj()?.let { usage ->
                    inputTokens = usage.int("input_tokens") ?: inputTokens
                    cacheReadTokens = usage.int("cache_read_input_tokens") ?: cacheReadTokens
                    sawUsage = true
                }
                emptyList()
            }

            "content_block_start" -> {
                val index = root.int("index") ?: 0
                val block = root["content_block"]?.obj()
                if (block?.type() == "tool_use") {
                    tools[index] = ToolState(
                        id = block.string("id").orEmpty(),
                        name = block.string("name").orEmpty(),
                    )
                }
                emptyList()
            }

            "content_block_delta" -> {
                val index = root.int("index") ?: 0
                val delta = root["delta"]?.obj()
                when (delta?.type()) {
                    "text_delta" -> listOf(AiStreamEvent.Delta(delta.string("text").orEmpty()))
                    "thinking_delta" -> listOf(
                        AiStreamEvent.ReasoningDelta(delta.string("thinking").orEmpty()),
                    )
                    "input_json_delta" -> {
                        val state = tools.getOrPut(index) { ToolState("", "") }
                        state.json.append(delta.string("partial_json").orEmpty())
                        listOf(
                            AiStreamEvent.ToolCallDelta(
                                argumentsChars = state.json.length,
                                toolName = state.name,
                                callIndex = index,
                            ),
                        )
                    }
                    else -> emptyList()
                }
            }

            "message_delta" -> {
                root["delta"]?.obj()?.let { delta ->
                    stopReason = delta.string("stop_reason") ?: stopReason
                }
                root["usage"]?.obj()?.let { usage ->
                    outputTokens = usage.int("output_tokens") ?: outputTokens
                    sawUsage = true
                }
                emptyList()
            }

            "error" -> {
                errorMessage = root["error"]?.obj()?.string("message")
                    ?: "上游报了一个没带 detail 的错误"
                emptyList()
            }

            // ping / message_stop / content_block_stop：不需要产出事件。
            else -> emptyList()
        }
    }

    /**
     * 收尾：依次发工具调用、用量、结束。
     *
     * 用量在最后发是刻意的（见类注释）：输入在开头、输出在结尾，
     * 中途报任何一次都不全。
     */
    fun finish(): List<AiStreamEvent> {
        val events = mutableListOf<AiStreamEvent>()
        if (tools.isNotEmpty()) {
            events += AiStreamEvent.ToolCalls(
                tools.entries.map { (_, state) ->
                    ToolCall(
                        id = state.id,
                        name = state.name,
                        // 参数解析不了就原样发出：让工具层报「参数不是合法 JSON」，
                        // 比在这里编一个 {} 出去（会静默丢参数）诚实得多。
                        argumentsJson = state.json.toString().ifBlank { "{}" },
                    )
                },
            )
        }
        if (sawUsage && (inputTokens > 0 || outputTokens > 0 || cacheReadTokens > 0)) {
            events += AiStreamEvent.Usage(
                promptTokens = inputTokens,
                completionTokens = outputTokens,
                cacheReadTokens = cacheReadTokens,
            )
        }
        events += AiStreamEvent.Done(stopReason)
        return events
    }

    private fun JsonObject.type(): String? = string("type")

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun kotlinx.serialization.json.JsonElement.obj(): JsonObject? =
        runCatching { jsonObject }.getOrNull()
}
