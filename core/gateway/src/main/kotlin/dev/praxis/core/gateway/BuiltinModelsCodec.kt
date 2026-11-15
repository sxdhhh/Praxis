package dev.praxis.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 内置家的**模型清单**（拉下来之后存这里）。
 *
 * ## 为什么需要单独一份，而不是塞进自定义家表
 *
 * 内置家（OpenAI / DeepSeek / 智谱…）是**代码里的常量**，不是数据库里的行：
 * `GatewayAccountStore.nativeModels()` 只扫 `gateway_provider`（自定义家），
 * 所以内置家**天然贡献 0 个模型**。
 *
 * 后果非常具体：在内置家上建了账号，`CandidateChain` 找不到任何候选 ⇒
 * 选路返回「没有可用候选」⇒ **一句话都发不出去**，而界面上账号明明在那儿。
 *
 * ## 为什么不直接往 gateway_provider 里插一行内置家
 *
 * 那样 `providers()` 会把 OpenAI 当成「自定义提供商」列出来，用户能删它、
 * 也能再建一个同 id 的 —— 语义上错得离谱（内置家不可能被删掉）。
 *
 * 所以另存一个键：`providerId -> [模型名]`。
 */
object BuiltinModelsCodec {

    /** 编码成 JSON 对象（`{ "openai": ["gpt-4o", …] }`）。 */
    fun encode(models: Map<String, List<String>>): String = buildJsonObject {
        models.toSortedMap().forEach { (provider, list) ->
            put(
                provider,
                buildJsonArray {
                    list.distinct().sorted().forEach { model -> add(kotlinx.serialization.json.JsonPrimitive(model)) }
                },
            )
        }
    }.toString()

    /**
     * 解码。
     *
     * @param text null/空白 = 没配过（返回 null）；坏内容返回 failure ——
     * 与其它设置同一口径：读不出来就说读不出来。
     */
    fun decode(text: String?): Result<Map<String, List<String>>>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            Json.parseToJsonElement(text).jsonObject.mapValues { (_, value) ->
                value.let { element ->
                    (element as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: error("模型清单不是数组")
                }
            }
        }
    }

    /**
     * 合并一次拉取结果（拉取成功才写；空清单当没拉到，保留旧的）。
     *
     * 与图片清单那边同一个判断（`GatewayModelCatalogCache.decideWrite`）：
     * 上游临时返回空数组时直接覆盖，会把可用模型抹掉 —— 那比不刷新更糟。
     */
    fun merge(
        current: Map<String, List<String>>,
        providerId: String,
        fetched: List<String>,
    ): Map<String, List<String>> {
        val cleaned = fetched.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (cleaned.isEmpty()) return current
        return current + (providerId to cleaned)
    }
}
