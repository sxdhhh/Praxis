package dev.praxis.core.gateway

/**
 * 自动续写配置（gateway_auto_continue + maxRounds）。
 *
 * ## 默认关闭写在类型上，而不是靠调用方自觉
 *
 * 文档 7.5 的原话是「自动续写要花钱，不能擅自开」。把 enabled 的默认值设成
 * false，意味着**任何忘了传配置的调用路径都是关闭的**；反过来（默认 true）
 * 只要有一处接线漏了，用户的请求就会在不知情的情况下多跑一轮。
 */
data class AutoContinueConfig(
    val enabled: Boolean = false,
    val maxRounds: Int = DEFAULT_MAX_ROUNDS,
) {
    companion object {
        /** 文档 7.5：轮数上限 3。 */
        const val DEFAULT_MAX_ROUNDS: Int = 3
    }
}

/** 上一轮结束时的信号。 */
enum class FinishSignal {
    /** finish_reason = length：被长度截断。 */
    LengthLimit,

    /** 上游断流：连接在回答中途结束。 */
    StreamCut,

    /** 正常结束，不需要续写。 */
    Normal,
}

/** 不续写的原因。 */
enum class AutoContinueStopReason {
    /** 总开关关着（默认就是关的）。 */
    Disabled,

    /** 回答没被腰斩。 */
    NotTruncated,

    /** 已经续到轮数上限。 */
    MaxRoundsReached,
}

/** 续写决策。 */
sealed interface AutoContinueDecision {
    /** 再来一轮。[nextRound] 从 1 开始计。 */
    data class Continue(val nextRound: Int) : AutoContinueDecision

    /** 停。理由要能展示给用户（「为什么它没接着说下去」）。 */
    data class Stop(val reason: AutoContinueStopReason) : AutoContinueDecision
}

/**
 * 自动续写判定（文档 7.5）。
 *
 * 回答被腰斩（finish_reason=length 或上游断流）时决定要不要自动再问一轮，
 * 把话说完。Praxis 的对话层已有手动「继续」按钮，这里补的是**自动**那一半。
 *
 * 放在路由层之上（GatewayProvider 的外层）：续写与「选哪个账号」无关，
 * 换号逻辑不该知道有这回事。
 */
object AutoContinuePolicy {

    /**
     * @param roundsDone 本次回答已经自动续过几轮
     */
    fun decide(
        config: AutoContinueConfig,
        signal: FinishSignal,
        roundsDone: Int,
    ): AutoContinueDecision {
        if (!config.enabled) {
            return AutoContinueDecision.Stop(AutoContinueStopReason.Disabled)
        }
        if (signal == FinishSignal.Normal) {
            return AutoContinueDecision.Stop(AutoContinueStopReason.NotTruncated)
        }
        if (roundsDone >= config.maxRounds) {
            return AutoContinueDecision.Stop(AutoContinueStopReason.MaxRoundsReached)
        }
        return AutoContinueDecision.Continue(nextRound = roundsDone + 1)
    }
}
