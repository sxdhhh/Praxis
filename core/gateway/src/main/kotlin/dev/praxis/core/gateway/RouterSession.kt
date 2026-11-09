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

    /**
     * 有候选，但本轮能试的组合都试过了。
     *
     * 与 [NoCandidate] 分开：前者是「一个都没得选」（要引导去配账号），
     * 后者是「试过了都不行」（要说清是哪些、为什么）。
     */
    data class Exhausted(
        val tried: List<String>,
        val lastKind: FailureKind?,
    ) : RouteOutcome

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
    /** 选路策略（文档 §9.4）。默认值 = 移植前的行为（顺序 + 粘性）。 */
    private val policy: GatewayRoutingPolicy = GatewayRoutingPolicy(),
) {

    /** 轮询的单调计数：每个请求转一位（顺序策略下它不被读）。 */
    private var rotationSeed: Int = 0
    /**
     * token 预算（默认不限）。
     *
     * 名字上带 token 是为了与构造参数 `budget: FailoverBudget` 区分 ——
     * 那个是「一次请求最多试几个组合」，这个是「一个账号能花多少」
     * （两者都叫 budget 会读错，编译器也不允许）。
     */
    private var tokenBudget: GatewayTokenBudget = GatewayTokenBudget()
    /** 各账号在窗口内的用量；空 = 都没有记录。 */
    private var accountSpend: Map<String, Long> = emptyMap()

    private var stickyAccountId: String? = null
    private var failover = FailoverState()
    private var lastOutcome: RouteOutcome? = null
    private var lastKind: FailureKind? = null

    /** 需要重新登录的账号（401 续期之后仍然失败）。由调用方决定怎么落库。 */
    private val reloginAccounts = mutableSetOf<String>()

    /**
     * 本轮已经试过的 (账号, 上游模型)。
     *
     * ## 为什么必须有这个集合
     *
     * 7.4 的表里，凭证过期（401）与内容拦截都写着**不冷却账号**。于是「换号」
     * 这个动作在过滤条件里没有任何东西能保证它真的换 —— 同一个账号会立刻又被
     * 选出来，原地死循环（这是我写完第一版、被自己的用例抓出来的）。
     *
     * 键是「账号 + 上游模型」而不是只有账号：同一个账号换一个模型仍可能成功，
     * 那是另一条候选，不该被误排除。
     */
    private val tried = mutableSetOf<Pair<String, String>>()

    /** 已经成功用过的账号（会话粘性的来源）。 */
    var stickyAfterSuccess: String? = null
        private set

    val needsRelogin: Set<String> get() = reloginAccounts

    /** 设置会话粘性（上一次这个会话用的账号）。 */
    fun setStickyAccount(accountId: String?) {
        stickyAccountId = accountId
    }

    /**
     * token 预算与各账号的使用量（文档 §7.2 第 6 条）。
     *
     * 由调用方**每次请求前**推入：`next()` 是同步的，不能在里面查库；
     * 而用量会随每次请求变化，缓存一份在会话里比每轮 plan 都带参数更简单。
     */
    fun setBudget(budget: GatewayTokenBudget, accountSpend: Map<String, Long>) {
        this.tokenBudget = budget
        this.accountSpend = accountSpend
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
            policy = policy,
            rotationSeed = rotationSeed,
            budget = tokenBudget,
            accountSpend = accountSpend,
        )
        // 每取一次就推进轮询计数：这是「摊开请求」的定义，不是每轮会话一次。
        rotationSeed += 1

        if (plan.stickyCleared) {
            // 文档 7.3：粘性命中但账号不可用时要清掉，否则下次还钉死在那个账号上。
            stickyAccountId = null
        }

        val remaining = plan.attempts.filter { candidate ->
            (candidate.accountId to candidate.wireModel) !in tried
        }

        val attempt = remaining.firstOrNull()
        if (attempt == null) {
            lastOutcome = if (plan.attempts.isEmpty()) {
                RouteOutcome.NoCandidate(plan.skipped)
            } else {
                RouteOutcome.Exhausted(
                    tried = tried.map { it.first + "/" + it.second },
                    lastKind = lastKind,
                )
            }
            return null
        }

        tried += attempt.accountId to attempt.wireModel
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
