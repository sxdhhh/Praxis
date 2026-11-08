package dev.praxis.core.imagegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * `GET /health` 的响应模型（文档 §3.4 的就绪探测）。
 *
 * ## 为什么不直接 @Serializable 映射
 *
 * 文档只说 /health 是就绪探测，没给响应体 schema；而 §1.5 已经证明同一份源码的
 * 2.8.1 与 3.0.0 契约并不一致。所以这里**原样保留 body**（[raw]），只给出一个
 * 保守的 [ok] 判断：将来引擎换字段，调用方还能读原文，不用等这里改模型。
 *
 * ## [ok] 的判据
 *
 * 1. body 非空；
 * 2. body 是一个 JSON 对象（HTML 错误页 / 纯文本 404 在这里被否掉）；
 * 3. 若带 `status` 字段，它不是明确的「还没好」词（loading / starting / ...）。
 *
 * 反过来说：**[ok] 不能代替 HTTP 状态码**。真正的就绪判据是「HTTP 200 且 [ok]」，
 * 由调用方（:app 的 EngineProcess）组合 —— 本模块拿不到状态码，也不该假装拿得到。
 */
data class EngineHealth(
    /** 原样响应体。日志与诊断面板直接用它。 */
    val raw: String,
    /** body 看起来「已就绪」。见上方三条判据。 */
    val ok: Boolean,
)

object EngineHealthCodec {

    /** 引擎自报「还没好」的状态词。判定保守：**认不出的一律当作已就绪**。 */
    private val NOT_READY_STATUS: Set<String> = setOf(
        "error",
        "failed",
        "failure",
        "loading",
        "starting",
        "not_ready",
        "unavailable",
    )

    /** 坏 JSON / 空 body **不抛**，返回 `ok = false`（探测路径上抛异常会变成崩溃）。 */
    fun parse(body: String): EngineHealth {
        val text = body.trim()
        if (text.isEmpty()) return EngineHealth(raw = body, ok = false)
        val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return EngineHealth(raw = body, ok = false)
        val status = (obj[EngineProtocol.HealthField.STATUS] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.lowercase()
        val ok = status == null || status !in NOT_READY_STATUS
        return EngineHealth(raw = body, ok = ok)
    }
}
