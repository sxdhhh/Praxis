package dev.praxis.core.imagegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 生成出来的一张图（工具结果里的最小信息）。 */
data class GeneratedImage(
    val path: String,
    val seed: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * 解析 `generate_image` 工具的结果 JSON。
 *
 * ## 为什么解析放在 core 而不是聊天层
 *
 * 聊天层要做的事是「把工具产的图变成气泡里的缩略图」。它需要知道**哪张图、在哪**，
 * 而那个格式由工具定义。两边各写一遍，改一处格式就会静默只在一半生效。
 * 放这里还能单测（聊天层没法在本机跑）。
 *
 * ## 为什么失败返回 null 而不是空列表
 *
 * 空列表会让调用方以为「解析成功、只是没有图」。返回 null 才能一眼区分
 * 「压根不是生图结果」。失败 JSON 里也有 message 字段，误判会在气泡里挂一张
 * 不存在的图 —— 用户看到破图而不是错误提示。
 */
object GenerateImageResultCodec {

    /**
     * @param toolContent 工具返回的正文（成功时是 JSON）
     * @return null = 不是成功结果（失败 JSON / 非 JSON / ok != true）
     */
    fun parse(toolContent: String): List<GeneratedImage>? {
        val root = runCatching { Json.parseToJsonElement(toolContent).jsonObject }.getOrNull()
            ?: return null
        if (root.string("ok")?.toBooleanStrictOrNull() != true) return null

        // 首选 images 数组（count 可能 > 1）；退而求其次用顶层 path。
        val fromArray = root["images"]
            ?.let { element -> runCatching { element.jsonArray }.getOrNull() }
            ?.mapNotNull { element -> runCatching { element.jsonObject.toImage() }.getOrNull() }
            .orEmpty()
        if (fromArray.isNotEmpty()) return fromArray

        val single = root.string("path") ?: return null
        return listOf(GeneratedImage(path = single))
    }

    private fun JsonObject.toImage(): GeneratedImage? {
        val path = string("path") ?: return null
        return GeneratedImage(
            path = path,
            seed = this["seed"]?.jsonPrimitive?.longOrNull,
            width = this["width"]?.jsonPrimitive?.intOrNull,
            height = this["height"]?.jsonPrimitive?.intOrNull,
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
