package dev.praxis.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 上游 `usage` 对象的解析（OpenAI 兼容）。
 *
 * ## 为什么单独抽出来
 *
 * 「有没有用量」和「请求成不成功」是两件事：很多中转站返回 200 但没有 `usage`。
 * 解析写在 provider 的响应处理里，就只能靠整包编译保证没写错；抽出来可以逐条钉
 * 住各种形状（有的给 `cached_tokens`、有的给 `cache_read_input_tokens`、有的都不给）。
 */
object OpenAiUsage {

    /**
     * 从响应体（或流式最后一片）里取 usage。
     *
     * @return null = 上游没给（**不是**「用量为 0」）。区分这两者很重要：
     *   报表里「没有数据」与「用了 0 token」是两回事。
     */
    fun parse(root: JsonObject): AiStreamEvent.Usage? {
        val usage = root["usage"]?.let { element ->
            runCatching { element.jsonObject }.getOrNull()
        } ?: return null
        val prompt = usage.int("prompt_tokens") ?: 0
        val completion = usage.int("completion_tokens") ?: 0
        val cache = usage.int("cache_read_input_tokens")
            ?: usage.int("cached_tokens")
            ?: usage.nestedInt("prompt_tokens_details", "cached_tokens")
            ?: 0
        // 三个都是 0 且 usage 对象存在：上游给了个空壳，按「没给」处理 ——
        // 否则报表会出现一堆「用了 0 token」的记录，污染均值。
        if (prompt == 0 && completion == 0 && cache == 0) return null
        return AiStreamEvent.Usage(
            promptTokens = prompt,
            completionTokens = completion,
            cacheReadTokens = cache,
        )
    }

    /** 直接从 JSON 文本解析（非流式响应体用）。 */
    fun parseJson(body: String): AiStreamEvent.Usage? {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return null
        return parse(root)
    }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.nestedInt(key: String, nested: String): Int? {
        val child = this[key]?.let { element ->
            runCatching { element.jsonObject }.getOrNull()
        } ?: return null
        return child[nested]?.jsonPrimitive?.intOrNull
    }
}
