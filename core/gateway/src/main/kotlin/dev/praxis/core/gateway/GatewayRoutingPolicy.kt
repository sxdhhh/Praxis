package dev.praxis.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 选路策略（文档 §9.4 的「策略」分区）。 */
enum class RoutingStrategy {
    /** 顺序：按优先级从高到低一直用第一个可用的（默认）。 */
    Sequential,

    /** 轮询：每个请求从下一个账号开始，把请求摊到多个账号上。 */
    RoundRobin,
}

/**
 * 选路与容错策略（文档 §9.4）。
 *
 * ## 只放**引擎真的会读**的字段
 *
 * 文档 §9.4 的「容错」分区还画了熔断四参（开关 / 阈值 / 超时 / 成功次数）。
 * 那四参的**引擎侧已经完整实现**（`RouteState` 的 [BreakerConfig] + planner 的
 * `breakerOpen` 判定），缺的只是「持久化 + 接到 RouteState」这一段。
 * 所以在接线完成之前，这里不放那四个字段 —— 放进来就是读不到的死配置。
 */
data class GatewayRoutingPolicy(
    val strategy: RoutingStrategy = RoutingStrategy.Sequential,
    /** 粘性：同一会话尽量固定用同一个账号（关掉就每个请求重新挑）。 */
    val stickyEnabled: Boolean = true,
)

/**
 * 策略的编解码（存 `gateway_routing_policy`）。
 *
 * 与 `imagegen_target` 同一取舍：显式字段名，不用 sealed 的类名做区分键 ——
 * 那玩意儿会写进用户的 DataStore，改一次类名就读不出来了。
 */
object GatewayRoutingPolicyCodec {

    fun encode(policy: GatewayRoutingPolicy): String = buildJsonObject {
        put("strategy", policy.strategy.name)
        put("stickyEnabled", policy.stickyEnabled)
    }.toString()

    /** @param text null/空白 = 没配过（返回默认策略）；坏内容 = failure。 */
    fun decode(text: String?): Result<GatewayRoutingPolicy>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val strategyName = root["strategy"]?.jsonPrimitive?.contentOrNull
            val strategy = RoutingStrategy.entries
                .firstOrNull { it.name == strategyName }
                ?: error("不认识的策略：" + (strategyName ?: "null"))
            val sticky = root["stickyEnabled"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull()
                ?: error("stickyEnabled 不是布尔值")
            GatewayRoutingPolicy(strategy = strategy, stickyEnabled = sticky)
        }
    }
}
