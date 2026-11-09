package dev.praxis.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * 那四参的引擎侧早已完整实现（`RouteState` 的 [BreakerConfig] + planner 的 `breakerOpen` 判定）；
 * 本轮把「持久化 + 接到 RouteState」补上，四个字段才**第一次变得有意义**。
 */
data class GatewayRoutingPolicy(
    val strategy: RoutingStrategy = RoutingStrategy.Sequential,
    /** 粘性：同一会话尽量固定用同一个账号（关掉就每个请求重新挑）。 */
    val stickyEnabled: Boolean = true,

    // ── 容错（文档 §9.4 的「容错」分区）──
    //
    // 引擎侧早就实现了熔断（RouteState 的 BreakerConfig + planner 的 breakerOpen 判定），
    // 这里只是把四个值变成可配置的。默认值与移植时的硬编码值一致（关、3 次、60 秒、1 次）。

    /** 熔断总开关。 */
    val breakerEnabled: Boolean = false,
    /** 连续失败多少次后打开。 */
    val breakerFailureThreshold: Int = 3,
    /** 打开后保持多久（毫秒）。 */
    val breakerOpenMillis: Long = 60_000L,
    /** 打开期间成功多少次后恢复（当前实现只记录，不据此自动恢复）。 */
    val breakerSuccessThreshold: Int = 1,

    // ── token 预算（§7.2 第 6 条）──

    /** 每账号在窗口内的 token 上限；0 = 不限（默认）。 */
    val tokenBudgetPerAccount: Long = 0L,
    /** 预算窗口（小时）。用滚动窗口而不是自然日：见 [GatewayTokenBudget] 的说明。 */
    val budgetWindowHours: Int = 24,
) {
    /** 交给 planner / RouteSession 的预算视图。 */
    fun toTokenBudget(): GatewayTokenBudget = GatewayTokenBudget(
        perAccountTokens = tokenBudgetPerAccount,
        windowHours = budgetWindowHours,
    )
    /** 交给 RouteState 的四参视图。 */
    fun toBreakerConfig(): BreakerConfig = BreakerConfig(
        enabled = breakerEnabled,
        failureThreshold = breakerFailureThreshold,
        openMillis = breakerOpenMillis,
        successThreshold = breakerSuccessThreshold,
    )
}

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
        put("breakerEnabled", policy.breakerEnabled)
        put("breakerFailureThreshold", policy.breakerFailureThreshold)
        put("breakerOpenMillis", policy.breakerOpenMillis)
        put("breakerSuccessThreshold", policy.breakerSuccessThreshold)
        put("tokenBudgetPerAccount", policy.tokenBudgetPerAccount)
        put("budgetWindowHours", policy.budgetWindowHours)
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
            // 容错四参是后加的（第一版只有 strategy/sticky）。**缺失用默认值**是 schema 演进，
            // 不是兜底：老配置的含义没变，只是多了几个没配过的项。但类型错（写了中文）算坏内容。
            val defaults = GatewayRoutingPolicy()
            GatewayRoutingPolicy(
                strategy = strategy,
                stickyEnabled = sticky,
                breakerEnabled = root.boolOr("breakerEnabled", defaults.breakerEnabled),
                breakerFailureThreshold = root.intOr(
                    "breakerFailureThreshold",
                    defaults.breakerFailureThreshold,
                ),
                breakerOpenMillis = root.longOr("breakerOpenMillis", defaults.breakerOpenMillis),
                breakerSuccessThreshold = root.intOr(
                    "breakerSuccessThreshold",
                    defaults.breakerSuccessThreshold,
                ),
                tokenBudgetPerAccount = root.longOr(
                    "tokenBudgetPerAccount",
                    defaults.tokenBudgetPerAccount,
                ),
                budgetWindowHours = root.intOr(
                    "budgetWindowHours",
                    defaults.budgetWindowHours,
                ),
            )
        }
    }
    /** 缺字段 -> 默认值；类型不对 -> 抛错（由 runCatching 变成 failure）。 */
    private fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean {
        val raw = this[key]?.jsonPrimitive?.contentOrNull ?: return fallback
        return raw.toBooleanStrictOrNull() ?: error(key + " 不是布尔值")
    }

    private fun JsonObject.intOr(key: String, fallback: Int): Int {
        val raw = this[key]?.jsonPrimitive?.contentOrNull ?: return fallback
        return raw.toIntOrNull() ?: error(key + " 不是整数")
    }

    private fun JsonObject.longOr(key: String, fallback: Long): Long {
        val raw = this[key]?.jsonPrimitive?.contentOrNull ?: return fallback
        return raw.toLongOrNull() ?: error(key + " 不是整数")
    }
}
