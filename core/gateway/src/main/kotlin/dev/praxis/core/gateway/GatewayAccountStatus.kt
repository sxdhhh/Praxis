package dev.praxis.core.gateway

/** 账号在界面上的三态（文档 9.0 的 MiuixBadge 三态灯）。 */
enum class GatewayAccountState {
    /** 可用。 */
    Normal,

    /** 冷却中（有倒计时）。 */
    Cooling,

    /** 被用户手动停用。 */
    Disabled,
}

/** 冷却的三种来源。文案不同，用户要采取的动作也不同。 */
enum class GatewayCooldownReason {
    /** 上游错误后的退避。 */
    Backoff,

    /** 熔断。 */
    Breaker,

    /** 额度用尽（429），等上游重置。 */
    QuotaLimit,
}

/**
 * 一个账号当前的状态（纯推导，不持有任何东西）。
 *
 * ## 为什么限额要单独列 limitedModels
 *
 * 限额的键是 (账号, 上游模型)（文档 6.5），不是整个账号。一个账号可能只是
 * gpt-x 被限，而 gpt-y 还能用 —— 这时把整条账号标成「冷却中」是错的，
 * 用户会以为这个号整个不能用了。
 *
 * 所以：**只有全部模型都被限**才算账号级冷却；部分被限时状态仍是正常，
 * 但把受限的模型列出来（界面可以写成「1 个模型受限」）。
 */
data class GatewayAccountStatus(
    val accountId: String,
    val state: GatewayAccountState,
    /** 冷却结束时刻；state 不是 Cooling 时为 null。 */
    val untilMillis: Long?,
    /** 冷却来源；state 不是 Cooling 时为 null。 */
    val reason: GatewayCooldownReason?,
    /** 被额度封住的上游模型（可能只是其中几个）。 */
    val limitedModels: List<String>,
    /** 处于垫底态：仍可用，但排队尾。 */
    val sink: Boolean,
)

/**
 * 从运行期状态推导界面状态。
 *
 * 判定顺序：停用 -> 退避 -> 熔断 -> 限额。「停用」放最前：用户自己关掉的账号，
 * 他最想知道的是「我关了它」，而不是「它正好在冷却」。
 */
object GatewayAccountStatusResolver {

    /**
     * @param wireModels 该账号承载的上游模型（即候选链里会用到它的那些名字）。
     *        空表示「还不知道它承载什么」—— 这时限额无法判定，状态按正常处理，
     *        并把 limitedModels 留空。这是明确的「不判定」，不是「判定为没限」。
     */
    fun resolve(
        accountId: String,
        enabled: Boolean,
        wireModels: List<String>,
        state: RouteState,
    ): GatewayAccountStatus {
        val sink = state.sinkUntil(accountId) != null

        if (!enabled) {
            return GatewayAccountStatus(
                accountId = accountId,
                state = GatewayAccountState.Disabled,
                untilMillis = null,
                reason = null,
                limitedModels = emptyList(),
                sink = sink,
            )
        }

        state.backoffUntil(accountId)?.let { until ->
            return cooling(accountId, GatewayCooldownReason.Backoff, until, sink)
        }

        state.breakerUntil(accountId)?.let { until ->
            return cooling(accountId, GatewayCooldownReason.Breaker, until, sink)
        }

        val limited = wireModels
            .filter { state.limitUntil(CooldownKey(accountId, it)) != null }
        val allLimited = wireModels.isNotEmpty() && limited.size == wireModels.size
        if (allLimited) {
            // 全部模型都受限时，账号恢复可用的时刻是其中最早到期的那个。
            val until = limited
                .mapNotNull { state.limitUntil(CooldownKey(accountId, it)) }
                .minOrNull()
            return cooling(accountId, GatewayCooldownReason.QuotaLimit, until, sink)
                .copy(limitedModels = limited)
        }

        return GatewayAccountStatus(
            accountId = accountId,
            state = GatewayAccountState.Normal,
            untilMillis = null,
            reason = null,
            limitedModels = limited,
            sink = sink,
        )
    }

    private fun cooling(
        accountId: String,
        reason: GatewayCooldownReason,
        untilMillis: Long?,
        sink: Boolean,
    ) = GatewayAccountStatus(
        accountId = accountId,
        state = GatewayAccountState.Cooling,
        untilMillis = untilMillis,
        reason = reason,
        limitedModels = emptyList(),
        sink = sink,
    )
}
