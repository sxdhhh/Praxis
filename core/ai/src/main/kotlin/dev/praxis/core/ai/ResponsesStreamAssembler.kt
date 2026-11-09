package dev.praxis.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI **Responses** 的流式组装（文档 §5.1，`ResponsesStreamAssembler`）。
 *
 * 把 SSE 的 `data:` JSON 变成 [AiStreamEvent]。事件名以 `response.` 打头。
 *
 * ## 与 Chat Completions 组装器的三处结构差异
 *
 * 1. 正文增量事件叫 `response.output_text.delta`（不是 `choices[0].delta.content`）；
 * 2. 工具参数有**自己的**增量事件 `response.function_call_arguments.delta`，
 *    并且带 `item_id` —— 多个工具并行时靠它区分（Chat 那边靠数组下标）；
 * 3. 用量只在末尾一个事件 `response.completed.response.usage` 里给全，
 *    没有「开头给输入、结尾给输出」的分段。
 *
 * 所以这个组装器**不需要**像 Anthropic 那样攒两段用量 —— 它天然是全的。
 */
class ResponsesStreamAssembler {

    private var inputTokens: Int = 0
    private var outputTokens: Int = 0
    private var cachedTokens: Int = 0
    private var sawUsage: Boolean = false

    private var stopReason: String? = null
    private var errorMessage: String? = null

    /** item_id -> (call_id, name)；工具条目在 `output_item.added` 时登记。 */
    private class ToolState(val callId: String, val name: String) {
        val json = StringBuilder()
    }

    private val tools = linkedMapOf<String, ToolState>()

    fun stopReason(): String? = stopReason

    fun errorMessage(): String? = errorMessage

    fun feed(payload: String): List<AiStreamEvent> {
        val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return emptyList()
        return when (root.type()) {
            "response.output_text.delta" ->
                listOf(AiStreamEvent.Delta(root.string("delta").orEmpty()))

            "response.reasoning_summary_text.delta" ->
                listOf(AiStreamEvent.ReasoningDelta(root.string("delta").orEmpty()))

            "response.output_item.added" -> {
                val item = root["item"]?.obj()
                if (item?.type() == "function_call") {
                    val itemId = item.string("id").orEmpty()
                    tools[itemId] = ToolState(
                        callId = item.string("call_id").orEmpty(),
                        name = item.string("name").orEmpty(),
                    )
                }
                emptyList()
            }

            "response.function_call_arguments.delta" -> {
                val itemId = root.string("item_id").orEmpty()
                val state = tools.getOrPut(itemId) { ToolState("", "") }
                state.json.append(root.string("delta").orEmpty())
                listOf(
                    AiStreamEvent.ToolCallDelta(
                        argumentsChars = state.json.length,
                        toolName = state.name,
                        callIndex = tools.keys.indexOf(itemId),
                    ),
                )
            }

            "response.completed" -> {
                val response = root["response"]?.obj()
                response?.let { resp ->
                    stopReason = resp.string("status") ?: stopReason
                    resp["usage"]?.obj()?.let { usage ->
                        inputTokens = usage.int("input_tokens") ?: inputTokens
                        outputTokens = usage.int("output_tokens") ?: outputTokens
                        cachedTokens = usage["input_tokens_details"]?.obj()
                            ?.int("cached_tokens") ?: cachedTokens
                        sawUsage = true
                    }
                }
                emptyList()
            }

            "response.failed", "error" -> {
                errorMessage = root["error"]?.obj()?.string("message")
                    ?: root["response"]?.obj()?.string("error")
                    ?: "上游报了一个没带 detail 的错误"
                emptyList()
            }

            else -> emptyList()
        }
    }

    /** 收尾：工具调用、用量、结束。 */
    fun finish(): List<AiStreamEvent> {
        val events = mutableListOf<AiStreamEvent>()
        if (tools.isNotEmpty()) {
            events += AiStreamEvent.ToolCalls(
                tools.values.map { state ->
                    ToolCall(
                        id = state.callId,
                        name = state.name,
                        argumentsJson = state.json.toString().ifBlank { "{}" },
                    )
                },
            )
        }
        if (sawUsage && (inputTokens > 0 || outputTokens > 0 || cachedTokens > 0)) {
            events += AiStreamEvent.Usage(
                promptTokens = inputTokens,
                completionTokens = outputTokens,
                cacheReadTokens = cachedTokens,
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
