package dev.praxis.core.gateway.adapters

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 签到的结果。takenToday=true 是**正常结果**（409 分支），不是错误。 */
data class CheckinResult(
    val takenToday: Boolean,
    /** 网关/上游给的文案（如「今日已签到」「+10 积分」）。 */
    val message: String = "",
)

/**
 * 签到/积分页的**纯判定**：把网关返回的原始 JSON 翻成 UI 文案。
 *
 * 为什么单独一个对象：409 是正常结果（已领取）、积分汇总的字段名随上游而变，
 * 这些规则必须能被单测钉住，不能埋在 UI 里。
 */
object CheckinRules {

    /** 从 claim-and-report 的响应里抠「余额」文案（字段名多分支兜底）。 */
    fun creditsText(responseBody: String): String? {
        return runCatching {
            val root = Json.parseToJsonElement(responseBody).jsonObject
            val data = (root["data"] as? kotlinx.serialization.json.JsonObject) ?: root
            // 各家字段名不同：credits / balance / total / remain
            for (key in listOf("credits", "balance", "total", "remain")) {
                val value = data[key]?.jsonPrimitive?.contentOrNull
                if (!value.isNullOrBlank()) return@runCatching key + " " + value
            }
            null
        }.getOrNull()
    }

    /** 把 claimCheckin 的原始响应翻成一句话。 */
    fun claimMessage(result: CheckinResult): String = when {
        result.takenToday -> "今天已经领过了： " + (result.message.ifBlank { "今日已签到" })
        else -> "签到成功" + (result.message.ifBlank { "" })
    }
}