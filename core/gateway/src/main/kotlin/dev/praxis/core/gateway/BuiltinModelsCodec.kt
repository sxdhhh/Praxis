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


    // ── 详细形态：名字 + 能力位（G3 / §5.4.1 第 6 条）──────────────

    /**
     * 一个内置家模型条目：id + 能力位。
     *
     * 能力位全部可空：null = 上游 /models 没声明（绝大多数 OpenAI 兼容端点只回 id），
     * **不等于不支持**。这个语义与 AiModelOption 逐字一致。
     */
    data class BuiltinModelCaps(
        val id: String,
        val supportsImages: Boolean? = null,
        val supportsReasoning: Boolean? = null,
        val supportsToolCall: Boolean? = null,
        val maxInputTokens: Int? = null,
        val maxOutputTokens: Int? = null,
    )

    /**
     * 编码详细形态：{ "openai": [ {"id":…, "supports_images":…}, … ] }。
     * 字段名与 /models 响应逐字一致（snake_case），肉眼核对两边时不用翻译。
     */
    fun encodeDetailed(models: Map<String, List<BuiltinModelCaps>>): String = buildJsonObject {
        models.toSortedMap().forEach { (provider, list) ->
            put(
                provider,
                buildJsonArray {
                    list.distinctBy { it.id }.sortedBy { it.id.lowercase() }.forEach { model ->
                        add(buildJsonObject {
                            put("id", model.id)
                            model.supportsImages?.let { put("supports_images", it) }
                            model.supportsReasoning?.let { put("supports_reasoning", it) }
                            model.supportsToolCall?.let { put("supports_tool_call", it) }
                            model.maxInputTokens?.let { put("max_input_tokens", it) }
                            model.maxOutputTokens?.let { put("max_output_tokens", it) }
                        })
                    }
                },
            )
        }
    }.toString()

    /**
     * 解码详细形态。**同时认旧形状**（元素是纯字符串）—— 那些条目能力位为 null。
     * 旧版本写下的清单不能因为升级就变成「读不出来」。
     */
    fun decodeDetailed(text: String?): Result<Map<String, List<BuiltinModelCaps>>>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            Json.parseToJsonElement(text).jsonObject.mapValues { (_, value) ->
                val array = value as? kotlinx.serialization.json.JsonArray
                    ?: error("模型清单不是数组")
                array.mapNotNull { element ->
                    when (element) {
                        is kotlinx.serialization.json.JsonPrimitive ->
                            element.contentOrNull?.let { BuiltinModelCaps(id = it) }
                        is kotlinx.serialization.json.JsonObject -> {
                            val idEl = element["id"] as? kotlinx.serialization.json.JsonPrimitive
                            val id = idEl?.contentOrNull ?: return@mapNotNull null
                            fun bool(key: String): Boolean? =
                                (element[key] as? kotlinx.serialization.json.JsonPrimitive)
                                    ?.contentOrNull?.toBooleanStrictOrNull()
                            fun num(key: String): Int? =
                                (element[key] as? kotlinx.serialization.json.JsonPrimitive)
                                    ?.contentOrNull?.toDoubleOrNull()?.toInt()
                            BuiltinModelCaps(
                                id = id,
                                supportsImages = bool("supports_images"),
                                supportsReasoning = bool("supports_reasoning"),
                                supportsToolCall = bool("supports_tool_call"),
                                maxInputTokens = num("max_input_tokens"),
                                maxOutputTokens = num("max_output_tokens"),
                            )
                        }
                        else -> null
                    }
                }
            }
        }
    }

    /** 详细形态的合并：与 [merge] 同一守门（空清单保留旧的）。 */
    fun mergeDetailed(
        current: Map<String, List<BuiltinModelCaps>>,
        providerId: String,
        fetched: List<BuiltinModelCaps>,
    ): Map<String, List<BuiltinModelCaps>> {
        val cleaned = fetched
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { it.id.lowercase() }
        if (cleaned.isEmpty()) return current
        return current + (providerId to cleaned)
    }


    // ── 自定义家 modelsJson 的两种形状（G3 收尾）──────────────────

    /** 编码「带能力位的清单」为 JSON 数组（写进 providers 表的 modelsJson）。 */
    fun encodeCapsList(models: List<BuiltinModelCaps>): String = buildJsonArray {
        models.distinctBy { it.id }.sortedBy { it.id.lowercase() }.forEach { model ->
            add(buildJsonObject {
                put("id", model.id)
                model.supportsImages?.let { put("supports_images", it) }
                model.supportsReasoning?.let { put("supports_reasoning", it) }
                model.supportsToolCall?.let { put("supports_tool_call", it) }
                model.maxInputTokens?.let { put("max_input_tokens", it) }
                model.maxOutputTokens?.let { put("max_output_tokens", it) }
            })
        }
    }.toString()

    /**
     * 解析 providers 表的 modelsJson —— 两种历史形状都认：
     * 旧 = 每行一个名字；新 = JSON 数组（带能力位）。判据看首字符，不是猜格式。
     */
    fun parseCapsList(modelsJson: String): List<BuiltinModelCaps> {
        val text = modelsJson.trim()
        if (text.isEmpty()) return emptyList()
        if (!text.startsWith("[")) {
            return text.split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { BuiltinModelCaps(id = it) }
        }
        return decodeDetailedArray(text)
    }

    private fun decodeDetailedArray(text: String): List<BuiltinModelCaps> =
        (Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonArray)
            .mapNotNull { element ->
                when (element) {
                    is kotlinx.serialization.json.JsonPrimitive ->
                        element.contentOrNull?.let { BuiltinModelCaps(id = it) }
                    is kotlinx.serialization.json.JsonObject -> {
                        val idEl = element["id"] as? kotlinx.serialization.json.JsonPrimitive
                        val id = idEl?.contentOrNull ?: return@mapNotNull null
                        fun bool(key: String): Boolean? =
                            (element[key] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.contentOrNull?.toBooleanStrictOrNull()
                        fun num(key: String): Int? =
                            (element[key] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.contentOrNull?.toDoubleOrNull()?.toInt()
                        BuiltinModelCaps(
                            id = id,
                            supportsImages = bool("supports_images"),
                            supportsReasoning = bool("supports_reasoning"),
                            supportsToolCall = bool("supports_tool_call"),
                            maxInputTokens = num("max_input_tokens"),
                            maxOutputTokens = num("max_output_tokens"),
                        )
                    }
                    else -> null
                }
            }
    /**
     * 从拉取结果构造条目（core:gateway 依赖 core:ai 是既有方向 —— AiErrorMapping 同）。
     */
    /**
     * 网关模型条目 → 能力位（G-内置：模型来自 /v1/models 的 owned_by 分组）。
     * 网关能力位缺失（null）时按「缺键放行」语义传 null。
     */
    fun from(entry: dev.praxis.core.gateway.adapters.GatewayModelEntry): BuiltinModelCaps = BuiltinModelCaps(
        id = entry.id,
        supportsImages = entry.supportsImages,
        supportsReasoning = entry.supportsReasoning,
        supportsToolCall = entry.supportsToolCall,
        maxInputTokens = entry.maxInputTokens,
        maxOutputTokens = entry.maxOutputTokens,
    )
    fun from(option: dev.praxis.core.ai.AiModelOption): BuiltinModelCaps = BuiltinModelCaps(
            id = option.id,
            supportsImages = option.supportsImages,
            supportsReasoning = option.supportsReasoning,
            supportsToolCall = option.supportsToolCall,
            maxInputTokens = option.maxInputTokens,
            maxOutputTokens = option.maxOutputTokens,
        )
}
