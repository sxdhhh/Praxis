package dev.praxis.core.gateway

/** 换号预算（对应文档 6.4 的三项重试配置）。 */
data class FailoverBudget(
    /** 换号次数上限（gateway_retry_switch）。文档 12 给出默认值 3。 */
    val maxSwitches: Int = DEFAULT_MAX_SWITCHES,
) {
    companion object {
        /** 文档 12：「换号次数上限默认 3」—— 换号当重试只会更快撞 429。 */
        const val DEFAULT_MAX_SWITCHES: Int = 3
    }
}

/** 循环状态。 */
data class FailoverState(
    val switches: Int = 0,
    /** 是否已经尝试过续期：凭证过期只续一次。 */
    val refreshed: Boolean = false,
)

/** 循环的下一步。 */
sealed interface FailoverStep {
    /** 换下一个候选账号。 */
    data object SwitchAccount : FailoverStep

    /** 原地续期一次（仅凭证过期）。 */
    data object RefreshCredentials : FailoverStep

    /** 结束，把错误报给用户。 */
    data class GiveUp(val reason: String) : FailoverStep

    /** 成功。 */
    data object Done : FailoverStep
}

/**
 * 失败切换循环：**纯决策，不发请求**。
 *
 * ## 它回答的唯一问题
 *
 * 「这次失败之后，下一步是换号、续期，还是直接告诉用户别试了」。
 * 发请求由 GatewayProvider 做；这里只管决策，所以能脱离网络完整单测 ——
 * P1 的验收「两个账号，改坏一个的 key 自动换号」就是在这一层验的。
 *
 * ## 单账号内重试（gateway_retry_count）本轮不纳入
 *
 * 文档 6.4 有这个键，但 7.4 的失败表**每一类都直接给了动作**，没有一类写着
 * 「先原地重试一次」。硬塞一个「5xx 先原地重试」等于自己发明语义。等 P3 有
 * 真实请求记录、看清哪些失败值得原地重试之后再补。
 */
object FailoverLoop {

    fun onSuccess(): FailoverStep = FailoverStep.Done

    fun onFailure(
        kind: FailureKind,
        state: FailoverState,
        budget: FailoverBudget,
    ): FailoverStep = when (FailurePolicy.actionFor(kind)) {
        // 请求本身非法：换号、重试都不会好转，直接报。
        FailoverAction.FailFast -> FailoverStep.GiveUp(
            reason = "请求本身非法，重试与换号都不会好转",
        )

        // 体积超限：同家再撞必然一样，先处理请求本身。
        FailoverAction.NoSwitch -> FailoverStep.GiveUp(
            reason = "响应体积超限，需要先处理请求本身",
        )

        // 凭证过期：先原地续期一次，仍失败再换号（文档 7.4 第 2 行）。
        FailoverAction.TryRefreshThenSwitch ->
            if (!state.refreshed) {
                FailoverStep.RefreshCredentials
            } else {
                switchOrGiveUp(state, budget)
            }

        FailoverAction.MarkQuotaAndSwitch,
        FailoverAction.SwitchProviderOnly,
        FailoverAction.BackoffAndSwitch,
        -> switchOrGiveUp(state, budget)
    }

    /** 施加一步之后的循环状态。换号才会计数，续期只记一次。 */
    fun advance(state: FailoverState, step: FailoverStep): FailoverState = when (step) {
        FailoverStep.SwitchAccount -> state.copy(switches = state.switches + 1)
        FailoverStep.RefreshCredentials -> state.copy(refreshed = true)
        else -> state
    }

    private fun switchOrGiveUp(state: FailoverState, budget: FailoverBudget): FailoverStep =
        if (state.switches >= budget.maxSwitches) {
            FailoverStep.GiveUp(
                reason = "已换号 " + state.switches + " 次，达到上限（gateway_retry_switch）",
            )
        } else {
            FailoverStep.SwitchAccount
        }
}
