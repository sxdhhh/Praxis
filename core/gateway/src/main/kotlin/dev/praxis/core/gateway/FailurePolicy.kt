package dev.praxis.core.gateway

/**
 * 一次请求失败的分类。
 *
 * 分类的依据与优先级见文档 7.4 的表 —— 那张表是网关 upstream 的核心：
 * **同一个失败如果归错类，后果不是报错，而是罚错账号**。
 */
enum class FailureKind {
    /** 429 / insufficient quota / 正文配额标记。 */
    QuotaLimited,

    /** 401 / token 失效文案。 */
    CredentialExpired,

    /** 200 但正文是拒绝标记，或 finish_reason 命中。 */
    ContentBlocked,

    /** 413 / 折叠后的体积判据。 */
    PayloadTooLarge,

    /** 上游 5xx 与网络错误。 */
    UpstreamError,

    /** 400 且不在「不重试」白名单里。 */
    InvalidRequest,
}

/**
 * 失败之后该做什么。
 *
 * 刻意做成独立枚举而不是几个布尔（switchAccount / cooldown / retry）：
 * 那些布尔之间有很多无意义组合（例如「不换号却要退避」），而这里的每一种
 * 都是文档表里真实存在的一行。
 */
enum class FailoverAction {
    /** 标记「该账号该模型」额度受限 + 换号；还有额度时另加垫底。 */
    MarkQuotaAndSwitch,

    /** 先尝试续期一次，仍失败标 needsRelogin 并换号；**不冷却账号**。 */
    TryRefreshThenSwitch,

    /** **不罚账号**，换家（换谁都会被拦，罚号只是白罚）。 */
    SwitchProviderOnly,

    /** **不换号**：同家再撞必然一样。剥图重试或直接报错。 */
    NoSwitch,

    /** 退避 + 换号。 */
    BackoffAndSwitch,

    /** 不换号、不重试，直接报给用户。 */
    FailFast,
}

/** 一条失败判定结果。 */
data class FailureDecision(
    val kind: FailureKind,
    val action: FailoverAction,
    /**
     * 冷却时长（毫秒）。0 = 不冷却。
     *
     * 只有 [FailoverAction.MarkQuotaAndSwitch] 与 [FailoverAction.BackoffAndSwitch]
     * 会给非零值 —— 其余几类「冷却账号」本身就是错的（见 7.4 的冷却列）。
     */
    val cooldownMillis: Long,
)

/**
 * 失败分类 -> 动作。
 *
 * ## 这一层只管映射
 *
 * 「怎么判断出 401」是 HTTP 客户端的事；这里只回答「401 之后该怎么做」。
 * 分开的好处是这张表可以逐行单测，而不需要造 HTTP 响应。
 */
object FailurePolicy {

    /** 429 认不出上游重置时刻时的兜底冷却（文档 7.4 第 1 行）。 */
    const val DEFAULT_QUOTA_COOLDOWN_MS: Long = 10L * 60L * 1000L

    /** 5xx / 网络的初始退避（其后翻倍，上限由 RouteState 管）。 */
    const val BACKOFF_BASE_MS: Long = 15L * 60L * 1000L

    /**
     * @param quotaResetAtMillis 上游给出的额度重置时刻；null = 认不出，
     *        用 [DEFAULT_QUOTA_COOLDOWN_MS]。注意这里收的是**时刻**不是时长。
     * @param nowMillis 当前时刻，用于把「重置时刻」换算成冷却时长
     */
    /**
     * 该失败对应哪种动作。
     *
     * 单独抽出来是为了让「只需要动作、不关心冷却时长」的调用方（换号循环）
     * 不必为了拿一个枚举而编造一个 now 时间戳。表只有一份，decide 也走它。
     */
    fun actionFor(kind: FailureKind): FailoverAction = when (kind) {
        FailureKind.QuotaLimited -> FailoverAction.MarkQuotaAndSwitch
        FailureKind.CredentialExpired -> FailoverAction.TryRefreshThenSwitch
        FailureKind.ContentBlocked -> FailoverAction.SwitchProviderOnly
        FailureKind.PayloadTooLarge -> FailoverAction.NoSwitch
        FailureKind.UpstreamError -> FailoverAction.BackoffAndSwitch
        FailureKind.InvalidRequest -> FailoverAction.FailFast
    }

    fun decide(
        kind: FailureKind,
        nowMillis: Long,
        quotaResetAtMillis: Long? = null,
    ): FailureDecision = when (kind) {
        FailureKind.QuotaLimited -> FailureDecision(
            kind = kind,
            action = FailoverAction.MarkQuotaAndSwitch,
            cooldownMillis = quotaResetAtMillis
                ?.let { (it - nowMillis).coerceAtLeast(0L) }
                ?: DEFAULT_QUOTA_COOLDOWN_MS,
        )

        FailureKind.CredentialExpired -> FailureDecision(
            kind = kind,
            action = FailoverAction.TryRefreshThenSwitch,
            cooldownMillis = 0L,
        )

        FailureKind.ContentBlocked -> FailureDecision(
            kind = kind,
            action = FailoverAction.SwitchProviderOnly,
            cooldownMillis = 0L,
        )

        FailureKind.PayloadTooLarge -> FailureDecision(
            kind = kind,
            action = FailoverAction.NoSwitch,
            cooldownMillis = 0L,
        )

        FailureKind.UpstreamError -> FailureDecision(
            kind = kind,
            action = FailoverAction.BackoffAndSwitch,
            cooldownMillis = BACKOFF_BASE_MS,
        )

        FailureKind.InvalidRequest -> FailureDecision(
            kind = kind,
            action = FailoverAction.FailFast,
            cooldownMillis = 0L,
        )
    }
}
