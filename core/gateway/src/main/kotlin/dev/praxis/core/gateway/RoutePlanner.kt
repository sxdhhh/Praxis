package dev.praxis.core.gateway

/**
 * 参与选路的账号视图。
 *
 * 字段名照文档 6.1 的 GatewayAccountEntity（providerId / enabled / priority /
 * maxConcurrent / credentialsCipher / baseUrlOverride），运行期那几项来自内存而非表。
 */
data class AccountView(
    val id: String,
    val providerId: String,
    val enabled: Boolean,
    val priority: Int,
    /** 0 = 不限。 */
    val maxConcurrent: Int,
    /** credentialsCipher 能否解出。解不出就等于没有凭证。 */
    val hasCredentials: Boolean,
    val baseUrlOverride: String? = null,
    /**
     * 图片能力位。null = **缺键**。
     *
     * 文档 7.2 第 3 行明确要求「缺键放行」：绝大多数老账号都没有这个字段，
     * 缺键就拦等于把所有人都拦掉。
     */
    val supportsImages: Boolean? = null,
    /** 运行期：当前并发数。 */
    val runningConcurrent: Int = 0,
    /** 运行期：本分钟已发请求数；配合 [requestLimitPerMinute]，0 = 不限。 */
    val requestsThisMinute: Int = 0,
    val requestLimitPerMinute: Int = 0,
)

/**
 * 每家的运行期限流（文档 7.2 第 6 行：每账号/每家的 RPM）。
 *
 * 与账号级限流的区别：同一家有多个账号时，家级配额是它们共用的。
 * 只做账号级会漏掉这种情况 —— 一家 10 个账号各 5 RPM，实际还是会撞上游的
 * 家级配额。
 */
data class ProviderUsage(
    val provider: String,
    val requestsThisMinute: Int,
    /** 0 = 不限。 */
    val limitPerMinute: Int = 0,
) {
    /** 该家的配额是否已经用满。 */
    val exhausted: Boolean
        get() = limitPerMinute > 0 && requestsThisMinute >= limitPerMinute
}

/** 被过滤掉的原因。枚举而不是字符串：界面要按类分组展示。 */
enum class SkipReason {
    Disabled,
    NoCredentials,
    ImageNotSupported,
    CoolingDown,
    ConcurrencyFull,
    RateLimited,
}

/** 一个可尝试的组合：某账号 + 某上游模型。 */
data class PlannedAttempt(
    val accountId: String,
    val provider: String,
    val wireModel: String,
    val reasoning: String?,
    /** 处于垫底态：仍可用，但排在最后。 */
    val sink: Boolean,
)

/** 一个被跳过的组合及其原因。 */
data class SkippedAttempt(
    val accountId: String,
    val wireModel: String,
    val reason: SkipReason,
)

/** 选路结果。 */
data class RoutePlan(
    /** 按尝试顺序排好的组合；空 = 当前无可用账号。 */
    val attempts: List<PlannedAttempt>,
    val skipped: List<SkippedAttempt>,
    /**
     * 会话粘性是否被清掉。
     *
     * 文档 7.3：粘性命中但账号不可用时要清掉，否则下次还钉死在那个账号上。
     * 清理由调用方写回（这一层不持有存储）。
     */
    val stickyCleared: Boolean,
) {
    val isEmpty: Boolean get() = attempts.isEmpty()
}

/**
 * 过滤与排序（文档 7.2 / 7.3）。
 *
 * ## 过滤顺序有讲究
 *
 * 文档明说「先便宜的判定」：启用与凭证是内存里的布尔，能力位是一次 map 查找，
 * 冷却要查四张表，并发与限流要读运行期计数。顺序反了不只是慢，还会让
 * 「为什么这个账号被跳过」变得难以解释（先判限流就会把一个已停用的账号
 * 报成 RateLimited）。
 *
 * ## 一处文档自相矛盾的地方，按「软降级」实现
 *
 * 7.2 第 4 行把 sink 列在**冷却过滤器**里（字面读就是「垫底态直接过滤掉」），
 * 而 7.3 又说 sink「排到队尾」。两者不可能同时成立：若在 7.2 就滤掉，
 * 7.3 那句排序规则永远没有作用对象。
 *
 * 取 7.3 的解释：sink 是**软降级**（仍可用，排最后）。理由是「还有额度时另加
 * 垫底」这个设计的意图本身就是「还能用，但先别用它」。
 *
 * ## 未实现的部分（明确标注，不是遗漏）
 *
 * 7.2 第 6 行的限流器包含「每家的 RPM 与 token 预算」两项。这一层只实现了
 * 每账号的 RPM；token 预算需要先有用量统计（P3 的请求记录表），现在没有数据源，
 * 硬写一个只会是假的。
 */
object RoutePlanner {

    fun plan(
        candidates: List<ModelCandidate>,
        accounts: List<AccountView>,
        state: RouteState,
        /** 本次请求是否带图；决定要不要查能力位。 */
        needsImages: Boolean,
        /** 本会话上次用的账号；null = 没有粘性。 */
        stickyAccountId: String?,
        /** 各家的运行期用量；空表示没有配置家级限流。 */
        providerUsages: List<ProviderUsage> = emptyList(),
    ): RoutePlan {
        val skipped = mutableListOf<SkippedAttempt>()
        val usable = mutableListOf<PlannedAttempt>()

        candidates.forEach { candidate ->
            accounts
                .filter { it.providerId == candidate.provider }
                .forEach { account ->
                    val providerExhausted = providerUsages
                        .firstOrNull { it.provider == candidate.provider }
                        ?.exhausted == true
                    val reason = blockingReason(
                        account = account,
                        candidate = candidate,
                        state = state,
                        needsImages = needsImages,
                        providerExhausted = providerExhausted,
                    )
                    if (reason == null) {
                        usable += PlannedAttempt(
                            accountId = account.id,
                            provider = candidate.provider,
                            wireModel = candidate.wireModel,
                            reasoning = candidate.reasoning,
                            sink = state.sinkUntil(account.id) != null,
                        )
                    } else {
                        skipped += SkippedAttempt(account.id, candidate.wireModel, reason)
                    }
                }
        }

        val priorityOf = accounts.associate { it.id to it.priority }
        val groupOrder = accounts.withIndex().associate { it.value.id to it.index }

        // 7.3 的三步按文档列出的顺序依次施加；Kotlin 的 sortedBy 是稳定排序，
        // 所以后一步不会打乱前一步已经定好的相对顺序。
        var ordered = usable.sortedWith(
            compareBy({ priorityOf[it.accountId] ?: 0 }, { groupOrder[it.accountId] ?: 0 }),
        )

        val stickyUsable = stickyAccountId != null &&
            ordered.any { it.accountId == stickyAccountId }
        if (stickyUsable) {
            // 粘性命中：把它提到最前（其余仍按上面的顺序作为后备）。
            ordered = ordered.sortedBy { if (it.accountId == stickyAccountId) 0 else 1 }
        }

        // 垫底排到队尾。
        ordered = ordered.sortedBy { if (it.sink) 1 else 0 }

        return RoutePlan(
            attempts = ordered,
            skipped = skipped,
            stickyCleared = stickyAccountId != null && !stickyUsable,
        )
    }

    /** 按 7.2 的顺序返回**第一个**拦住的理由；null = 通过。 */
    private fun blockingReason(
        account: AccountView,
        candidate: ModelCandidate,
        state: RouteState,
        needsImages: Boolean,
        providerExhausted: Boolean,
    ): SkipReason? {
        if (!account.enabled) return SkipReason.Disabled
        if (!account.hasCredentials) return SkipReason.NoCredentials

        // 缺键（null）放行，只有明确 false 才跳过。
        if (needsImages && account.supportsImages == false) return SkipReason.ImageNotSupported

        val key = CooldownKey(account.id, candidate.wireModel)
        val backoff = state.backoffUntil(account.id) != null
        val breaker = state.breakerOpen(account.id)
        val limited = state.limitUntil(key) != null
        if (backoff || breaker || limited) return SkipReason.CoolingDown

        if (account.maxConcurrent > 0 && account.runningConcurrent >= account.maxConcurrent) {
            return SkipReason.ConcurrencyFull
        }

        if (account.requestLimitPerMinute > 0 &&
            account.requestsThisMinute >= account.requestLimitPerMinute
        ) {
            return SkipReason.RateLimited
        }

        // 家级配额用满时，这一家的所有账号都跳过：换号也躲不开共享配额。
        if (providerExhausted) return SkipReason.RateLimited

        return null
    }
}
