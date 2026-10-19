package dev.praxis.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把 OpenAI 兼容协议的 data 负载组装成 [AiStreamEvent]。
 *
 * ## 关键难点：工具调用是**分片到达**的
 *
 * 上游发 tool_calls 的方式是：
 * ```
 * {"index":0,"id":"call_a","function":{"name":"read_file","arguments":""}}
 * {"index":0,"function":{"arguments":"{\"path\":"}}
 * {"index":0,"function":{"arguments":"\"/sdcard/a.txt\"}"}}
 * ```
 * id 与 name 只在**第一片**出现，之后只有 arguments 增量。
 * 所以必须按 `index` 累积到一桶里，直到流结束（或 finish_reason 出现）才发 [AiStreamEvent.ToolCalls]。
 *
 * 常见错误：每收到一片就发一个 ToolCall —— 那样每个调用的 argumentsJson 都是半截 JSON，
 * 上游下一轮直接 400。
 *
 * ## 为何不在这里解析 arguments
 *
 * 解析（parse 成 JsonObject）由工具层做：组装器只保证字符串**完整**，
 * 不去猜内容。这样即使模型给了非法 JSON，也能把原文回传给模型让它自己纠正。
 */
class OpenAiStreamAssembler(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /** 按 index 累积的工具调用。 */
    private val toolBuckets = sortedMapOf<Int, ToolBucket>()

    private var finishReason: String? = null
    private val textBuffer = StringBuilder()

    private class ToolBucket(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    )

    /** 喂一个 data 负载，返回本片可直接下发的事件（Delta / Done）。 */
    fun feed(dataPayload: String): List<AiStreamEvent> {
        val root = runCatching { json.parseToJsonElement(dataPayload).jsonObject }
            .getOrNull() ?: return emptyList()

        val events = mutableListOf<AiStreamEvent>()

        val choices = root["choices"]?.jsonArray ?: return events
        for (choice in choices) {
            val obj = choice.jsonObject

            obj["delta"]?.jsonObject?.let { delta ->
                delta["content"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { text ->
                        textBuffer.append(text)
                        events += AiStreamEvent.Delta(text)
                    }

                delta["tool_calls"]?.jsonArray?.forEach { tc ->
                    accumulateToolCall(tc.jsonObject)
                }
            }

            obj["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                finishReason = reason
            }
        }
        return events
    }

    /**
     * 流结束：吐出累积的工具调用与 Done。
     *
     * 顺序固定为「先 ToolCalls 后 Done」：UI 看到 Done 就要停止 loading，
     * 若工具调用排在 Done 之后，工具卡片会在 loading 结束后才冒出来，观感是闪一下。
     */
    fun finish(): List<AiStreamEvent> {
        val events = mutableListOf<AiStreamEvent>()
        if (toolBuckets.isNotEmpty()) {
            val calls = toolBuckets.values.map { bucket ->
                ToolCall(
                    id = bucket.id.ifEmpty { "call_${bucket.name}" },
                    name = bucket.name,
                    // 不 trim、不改一个字符：这是要回传上游的原文。
                    argumentsJson = bucket.args.toString(),
                )
            }
            events += AiStreamEvent.ToolCalls(calls)
        }
        events += AiStreamEvent.Done(finishReason)
        return events
    }

    /** 已累积的文本（供非流式场景拼接）。 */
    fun accumulatedText(): String = textBuffer.toString()

    private fun accumulateToolCall(obj: JsonObject) {
        val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val bucket = toolBuckets.getOrPut(index) { ToolBucket() }

        obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            bucket.id = it
        }
        obj["function"]?.jsonObject?.let { fn ->
            fn["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                bucket.name = it
            }
            fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { bucket.args.append(it) }
        }
    }
}
