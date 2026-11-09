package dev.praxis.core.imagegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 生图的输出设置（文档 §7.1 的「输出」分区）。
 *
 * ## 只做 autoSave，showPreview 仍是未实现
 *
 * 「显示每步预览」要引擎在采样过程中推中间帧、再配一条缩略图管线 ——
 * 那是独立的一块工作量，不是加个开关。所以这里保留字段以便 `set_image_gen_config`
 * 能如实回一句「这项还没做」，但界面上不摆一个点了没反应的开关。
 */
data class ImageOutputSettings(
    /** 生成后自动存到相册（默认关：不打招呼就往相册里塞图是失礼的）。 */
    val autoSave: Boolean = false,
    /** 显示每步预览。**尚未实现**（见类注释）。 */
    val showPreview: Boolean = false,
)

/** `imagegen_output` 的编解码。 */
object ImageOutputSettingsCodec {

    fun encode(settings: ImageOutputSettings): String = buildJsonObject {
        put("autoSave", settings.autoSave)
        put("showPreview", settings.showPreview)
    }.toString()

    /** @param text null/空白 = 没配过（返回 null）；坏内容 = failure。 */
    fun decode(text: String?): Result<ImageOutputSettings>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root: JsonObject = Json.parseToJsonElement(text).jsonObject
            ImageOutputSettings(
                autoSave = root.boolOr("autoSave", false),
                showPreview = root.boolOr("showPreview", false),
            )
        }
    }

    private fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean {
        val raw = this[key]?.jsonPrimitive?.contentOrNull ?: return fallback
        return raw.toBooleanStrictOrNull() ?: error(key + " 不是布尔值")
    }
}

/** 一次「存相册」的结果。 */
sealed interface GallerySaveResult {

    /** 已写入相册。[uri] 仅用于日志。 */
    data class Saved(val uri: String) : GallerySaveResult

    /**
     * 这台机器上做不到（Android 9 及以下要存储权限，Praxis 还没接那套流程）。
     *
     * **不是失败**：它与「写盘出错」是两回事，提示语也不同 —— 用户需要知道的是
     * 「这个功能在你的系统版本上用不了」，而不是「保存失败了」。
     */
    data object Unsupported : GallerySaveResult

    /** 真的失败了（IO 异常、MediaStore 拒绝）。 */
    data class Failed(val detail: String) : GallerySaveResult
}
