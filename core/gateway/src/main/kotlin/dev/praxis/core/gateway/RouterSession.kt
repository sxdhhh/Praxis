package dev.praxis.core.gateway

/**
 * 一次请求的结束方式。
 *
 * 用密封接口而不是「null / 异常」：调用方必须显式处理「没有可用账号」与
 * 「放弃了」两种情况，它们的用户可见文案完全不同（前者要引导去配账号，
 * 后者要说明为什么这次不行）。
 */
sealed interface RouteOutcome {

    /** 一个候选都没有：全被过滤掉了。带上每一条的跳过原因。 */
    data class NoCandidate(val skipped: List<SkippedAttempt>) : RouteOutcome

    /** 有候选，但接连失败后决定不再试。 */
    data class GaveUp(
        val reason: String,
        val lastKind: FailureKind?,
        val switches: Int,
    ) : RouteOutcome
}

/**
 * 请求路由状态机（文档 7.1 ~ 7.4 的落地）。
 *
 * ## 它做什么、不做什么
 *
 * 做：决定**下一步打哪个账号的哪个上游模型**，并在失败后更新运行期状态
 * （退避 / 熔断 / 限额）、收集「需要重新登录」的账号、执行换号预算。
 *
 * 不做：发请求。调用方拿到 [next] 给的组合去发，再把结果告诉 [onSuccess] /
 * [onFailure]。这样一来整条路由逻辑可以在纯 JVM 上验，不需要网络、不需要设备
 * —— P1 的验收句「两个账号，改坏一个的 key 自动换号」就是在这里验的。
 *
 * ## 典型用法
 *
 * 循环 -> next() 拿到 attempt -> 发请求 -> 成功则 onSuccess 并返回，
 * 失败则 onFailure 拿到一步决策（换号 / 续期 / 放弃）。
 */
class RouterSession(
    private val candidates: List<ModelCandidate>,
    /** 每次重新取账号：失败之后状态变了，过滤结果也会变。 */
    private val accounts: () -> List<AccountView>,
    private val state: RouteState,
    private val needsImages: Boolean = false,
    private val budget: FailoverBudget = FailoverBudget(),
    private val providerUsages: List<ProviderUsage> = emptyList(),
) {

    private var stickyAccountId: String? = null
    private var failover = FailoverState()
    private var lastOutcome: RouteOutcome? = null
    private var lastKind: FailureKind? = null

    /** 需要重新登录的账号（401 续期之后仍然失败）。由调用方决定怎么落库。 */
    private val reloginAccounts = mutableSetOf<String>()

    /** 已经成功用过的账号（会话粘性的来源）。 */
    var stickyAfterSuccess: String? = null
        private set

    val needsRelogin: Set<String> get() = reloginAccounts

    /** 设置会话粘性（上一次这个会话用的账号）。 */
    fun setStickyAccount(accountId: String?) {
        stickyAccountId = accountId
    }

    /**
     * 下一个要尝试的组合；null 表示没有可试的了（看 [outcome]）。
     */
    fun next(): PlannedAttempt? {
        if (lastOutcome != null) return null

        val plan = RoutePlanner.plan(
            candidates = candidates,
            accounts = accounts(),
            state = state,
            needsImages = needsImages,
            stickyAccountId = stickyAccountId,
            providerUsages = providerUsages,
        )

        if (plan.stickyCleared) {
            // 文档 7.3：粘性命中但账号不可用时要清掉，否则下次还钉死在那个账号上。
            stickyAccountId = null
        }

        val attempt = plan.attempts.firstOrNull()
        if (attempt == null) {
            lastOutcome = RouteOutcome.NoCandidate(plan.skipped)
            return null
        }
        return attempt
    }

    /** 这次成功了。记下粘性，清掉该账号的运行期负状态。 */
    fun onSuccess(attempt: PlannedAttempt) {
        state.onSuccess(attempt.accountId)
        stickyAccountId = attempt.accountId
        stickyAfterSuccess = attempt.accountId
    }

    /**
     * 这次失败了。
     *
     * @param quotaResetAtMillis 额度受限时，上游给的重置时刻（认不出就传 null）
     * @return 下一步决策：换号 / 续期一次 / 放弃
     */
    fun onFailure(
        attempt: PlannedAttempt,
        kind: FailureKind,
        quotaResetAtMillis: Long? = null,
    ): FailoverStep {
        lastKind = kind

        // 账号级效果（退避 / 熔断计数）照 7.4 的表走。
        state.onFailure(attempt.accountId, kind)

        // 额度受限是 (账号, 上游模型) 粒度的，必须显式标。
        if (kind == FailureKind.QuotaLimited) {
            state.markLimit(
                key = CooldownKey(attempt.accountId, attempt.wireModel),
                untilMillis = quotaResetAtMillis,
            )
        }

        val step = FailoverLoop.onFailure(kind = kind, state = failover, budget = budget)

        // 凭证过期：第一次是「去续期」，第二次才算这个账号真的需要重新登录。
        // 文档 7.4 第 2 行：尝试续期一次，仍失败标 needsRelogin + 换号。
        if (kind == FailureKind.CredentialExpired && step == FailoverStep.SwitchAccount) {
            reloginAccounts += attempt.accountId
        }

        failover = FailoverLoop.advance(failover, step)

        if (step is FailoverStep.GiveUp) {
            lastOutcome = RouteOutcome.GaveUp(
                reason = step.reason,
                lastKind = kind,
                switches = failover.switches,
            )
        }
        return step
    }

    /** 当前结束原因；仍在进行中返回 null。 */
    fun outcome(): RouteOutcome? = lastOutcome

    /** 最后一次失败的类别（用于给用户看「为什么没成」）。 */
    fun lastFailureKind(): FailureKind? = lastKind
}
