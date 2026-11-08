package dev.praxis.core.gateway

/**
 * 熔断四参（配置键见文档 6.4 的 gateway_breaker_*）。
 *
 * ## 为什么不给默认值
 *
 * 文档 6.5 的构造签名只画了一个 clock，但这四个值本身来自配置。
 * 给默认值会让「忘了接线」看起来像正常工作 —— 熔断悄悄用一组谁也没选过的
 * 阈值在跑。宁可要求调用方显式传。
 */
data class BreakerConfig(
    /** 总开关。关掉时 breakerOpen 恒为 false。 */
    val enabled: Boolean,
    /** 连续失败多少次后打开。 */
    val failureThreshold: Int,
    /** 打开后保持多久（毫秒）。 */
    val openMillis: Long,
    /** 打开期间成功多少次后恢复（当前实现只记录，不据此自动恢复）。 */
    val successThreshold: Int,
)

/**
 * 路由运行期状态：纯内存、可注入时钟（文档 6.5）。
 *
 * 四张表：
 *
 * - 退避 backoff：键 accountId，上游错误后 15 分钟起指数翻倍，上限 1 小时；
 * - 垫底 sink：键 accountId，标记后 TTL 30 分钟，排序时排到队尾；
 * - 熔断 breaker：键 accountId，连续失败到阈值后打开一段时间；
 * - 限额 limit：键 CooldownKey（账号 + 上游模型），429 到上游给的重置时刻。
 *
 * ## 时钟为什么必须注入
 *
 * 退避翻倍、TTL 到期、熔断恢复全与时间有关。直接用系统时钟就只能靠 sleep
 * 来测，那是慢且不稳的测试。注入之后每条时间规则都能用「把钟往前拨」来验。
 */
class RouteState(
    private val clock: () -> Long,
    private val breaker: BreakerConfig,
) {

    private class BackoffEntry(var untilMillis: Long, var attempts: Int)

    private val backoffs = mutableMapOf<String, BackoffEntry>()
    private val sinks = mutableMapOf<String, Long>()
    private val limits = mutableMapOf<CooldownKey, Long>()
    private val breakerFailures = mutableMapOf<String, Int>()
    private val breakerOpenUntil = mutableMapOf<String, Long>()

    /** 退避到期时刻；未在退避或已过期返回 null。 */
    fun backoffUntil(id: String): Long? = backoffs[id]?.untilMillis?.takeIf { it > clock() }

    /**
     * 记一次退避，返回新的到期时刻。
     *
     * 时长 = 15 分钟乘以 2 的 n 次方（n 从 0 起），上限 1 小时 ——
     * 连续失败 3 次就撞到上限。
     */
    fun markBackoff(id: String): Long {
        val now = clock()
        val attempts = backoffs[id]?.attempts ?: 0
        val until = now + backoffDuration(attempts)
        backoffs[id] = BackoffEntry(until, attempts + 1)
        return until
    }

    /** 垫底到期时刻；未标记或已过期返回 null。 */
    fun sinkUntil(id: String): Long? = sinks[id]?.takeIf { it > clock() }

    /**
     * 标记垫底（还有额度但已受限时用），TTL 30 分钟。
     *
     * 文档 6.5 只列了读方法；写方法由 7.4 的动作列推出 —— 额度受限那行写着
     * 「还有额度时另加垫底」，没有写入口就实现不了那半句。
     */
    fun markSink(id: String): Long {
        val until = clock() + SINK_TTL_MS
        sinks[id] = until
        return until
    }

    /** 该账号该上游模型的限额到期时刻；无或已过期返回 null。 */
    fun limitUntil(key: CooldownKey): Long? = limits[key]?.takeIf { it > clock() }

    /**
     * 记一次限额冷却。
     *
     * @param untilMillis 上游给的重置时刻；null = 认不出，用 10 分钟（文档 7.4 第 1 行）
     */
    fun markLimit(key: CooldownKey, untilMillis: Long?): Long {
        val until = untilMillis ?: (clock() + FailurePolicy.DEFAULT_QUOTA_COOLDOWN_MS)
        limits[key] = until
        return until
    }

    /**
     * 熔断的到期时刻；未打开或已过期返回 null。
     *
     * 文档 6.5 的签名里只有 breakerOpen（布尔）。但界面上要显示「还有多久恢复」，
     * 布尔答不了这个问题 —— 于是补一个只读访问器，与 markSink / markLimit 同理。
     */
    fun breakerUntil(id: String): Long? {
        if (!breaker.enabled) return null
        return breakerOpenUntil[id]?.takeIf { it > clock() }
    }

    /** 熔断是否打开。未启用时恒为 false。 */
    fun breakerOpen(id: String): Boolean = breakerUntil(id) != null

    /**
     * 一次成功。
     *
     * 文档 6.5：清 backoff + sink + 熔断计数。**不清限额** —— 429 的冷却时长
     * 是上游给的，本地成功一次不代表额度回来了。
     */
    fun onSuccess(id: String) {
        backoffs.remove(id)
        sinks.remove(id)
        breakerFailures.remove(id)
        breakerOpenUntil.remove(id)
    }

    /**
     * 一次失败。
     *
     * ## 为什么只处理账号级效果
     *
     * 7.4 的表里需要账号级冷却的只有「上游 5xx / 网络」一行（退避）。额度受限的
     * 冷却键是 (账号, 上游模型)，而这里只拿得到账号 —— 那一档必须由调用方用
     * markLimit 显式标记。这不是遗漏，是键的粒度决定的。
     *
     * 其余四类按表都是「不罚账号」：内容拦截换谁都被拦、413 同家必再撞、
     * 凭证过期不该冷却账号（用户续期后要能立刻用）、请求非法与账号无关。
     */
    fun onFailure(id: String, kind: FailureKind) {
        when (kind) {
            FailureKind.UpstreamError -> {
                markBackoff(id)
                countBreakerFailure(id)
            }

            FailureKind.QuotaLimited,
            FailureKind.CredentialExpired,
            FailureKind.ContentBlocked,
            FailureKind.PayloadTooLarge,
            FailureKind.InvalidRequest,
            -> Unit
        }
    }

    private fun countBreakerFailure(id: String) {
        if (!breaker.enabled) return
        if (breaker.failureThreshold <= 0) return
        val next = (breakerFailures[id] ?: 0) + 1
        if (next >= breaker.failureThreshold) {
            breakerFailures[id] = 0
            breakerOpenUntil[id] = clock() + breaker.openMillis
        } else {
            breakerFailures[id] = next
        }
    }

    private fun backoffDuration(attempts: Int): Long {
        // 位移前先封顶，避免 attempts 很大时移位绕回成负数。
        val shift = attempts.coerceAtMost(20)
        val scaled = FailurePolicy.BACKOFF_BASE_MS shl shift
        return if (scaled <= 0L) BACKOFF_CAP_MS else scaled.coerceAtMost(BACKOFF_CAP_MS)
    }

    companion object {
        /** 退避上限（文档 6.5）。 */
        const val BACKOFF_CAP_MS: Long = 60L * 60L * 1000L

        /** 垫底 TTL（文档 6.5）。 */
        const val SINK_TTL_MS: Long = 30L * 60L * 1000L
    }
}
