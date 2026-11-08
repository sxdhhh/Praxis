package dev.praxis.core.imagegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 生图的默认参数（文档 §7.1 的「画布」与「采样」两个分区）。
 *
 * ## 为什么它必须真的是工具的默认值
 *
 * 用户在设置页把画布改成 768×768、步数改成 30，然后让 AI 画图时**没说尺寸** ——
 * 那就该按 768×768 / 30 步走。如果工具永远用 [ImageGenParams] 的出厂默认值，
 * 这两个分区就是一对读不到的死配置（和「并发数」那类假开关同罪）。
 */
data class ImageGenDefaults(
    val width: Int = ImageGenParams.DEFAULT_WIDTH,
    val height: Int = ImageGenParams.DEFAULT_HEIGHT,
    val steps: Int = ImageGenParams.DEFAULT_STEPS,
    val cfg: Float = 7f,
    /** 默认负向提示词；空 = 不写。 */
    val negative: String = "",
    /** 默认调度器；null = 用引擎默认。 */
    val scheduler: String? = null,
)

/** 默认值的校验（纯函数）。 */
object ImageGenDefaultsRules {

    /**
     * 返回第一处不合法，null = 通过。
     *
     * 与 `ImageGenParamsRules` 同一套边界（256~1024 / 1~60 / 1~20），但这里**不查档位**：
     * 档位是「这台机器上这个模型能跑什么」，属于运行时信息，不该在保存默认值时判死。
     */
    fun validate(defaults: ImageGenDefaults): String? = when {
        defaults.width !in 256..1024 -> "宽要在 256~1024 之间"
        defaults.height !in 256..1024 -> "高要在 256~1024 之间"
        defaults.steps !in 1..60 -> "步数要在 1~60 之间"
        defaults.cfg < 1f || defaults.cfg > 20f -> "CFG 要在 1~20 之间"
        defaults.cfg.isNaN() -> "CFG 不是有效数字"
        else -> null
    }
}

/** `imagegen_defaults` 的编解码。 */
object ImageGenDefaultsCodec {

    fun encode(defaults: ImageGenDefaults): String = buildJsonObject {
        put("width", defaults.width)
        put("height", defaults.height)
        put("steps", defaults.steps)
        put("cfg", defaults.cfg)
        put("negative", defaults.negative)
        put("scheduler", defaults.scheduler)
    }.toString()

    /** @param text null/空白 = 没配过（返回 null）；坏内容 = failure。 */
    fun decode(text: String?): Result<ImageGenDefaults>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root: JsonObject = Json.parseToJsonElement(text).jsonObject
            val defaults = ImageGenDefaults(
                width = root.intOr("width", ImageGenParams.DEFAULT_WIDTH),
                height = root.intOr("height", ImageGenParams.DEFAULT_HEIGHT),
                steps = root.intOr("steps", ImageGenParams.DEFAULT_STEPS),
                cfg = root.floatOr("cfg", 7f),
                negative = root["negative"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                scheduler = root["scheduler"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() },
            )
            // 越界算坏内容：静默夹到边界会让用户以为「我设的 2000 生效了」。
            ImageGenDefaultsRules.validate(defaults)?.let { reason -> error(reason) }
            defaults
        }
    }

    private fun JsonObject.intOr(key: String, fallback: Int): Int {
        val raw = this[key]?.jsonPrimitive?.contentOrNull ?: return fallback
        return raw.toIntOrNull() ?: error(key + " 不是整数")
    }

    private fun JsonObject.floatOr(key: String, fallback: Float): Float {
        val raw = this[key]?.jsonPrimitive?.contentOrNull ?: return fallback
        return raw.toFloatOrNull() ?: error(key + " 不是数字")
    }
}
