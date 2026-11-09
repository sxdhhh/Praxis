package dev.praxis.core.gateway

/**
 * token 预算（文档 §7.2 的过滤链第 6 条「每账号/每家的 token 预算」）。
 *
 * ## 为什么是「滚动窗口」而不是「自然日」
 *
 * 自然日（本地时区 00:00 清零）在报表上更好读，但作为**限流**它有个坏处：
 * 23:59 用爆额度的人，00:01 就又能用满 —— 对上游来说这是突刺。
 * 滚动窗口（近 N 小时）避免了这一点，代价是「今天用了多少」这种问法不适用。
 * 报表仍按自然日（那是展示），限流按滚动窗口（那是保护）—— 两者口径不同是**有意**的。
 *
 * ## 默认关（0 = 不限）
 *
 * 不设预算就不限流。默认给一个数字会让用户莫名撞上「额度用完了」，
 * 而他从没设过任何额度。
 */
data class GatewayTokenBudget(
    /** 每个账号在窗口内的 token 上限；0 = 不限。 */
    val perAccountTokens: Long = 0L,
    /** 窗口长度（小时）。 */
    val windowHours: Int = 24,
) {
    /** 是否启用（没有额度就是关着）。 */
    val enabled: Boolean get() = perAccountTokens > 0L

    /** 窗口起点（毫秒）。 */
    fun windowStartMillis(nowMillis: Long): Long =
        nowMillis - windowHours.toLong() * 60L * 60L * 1000L

    /**
     * 该账号在窗口内的用量是否已经用完预算。
     *
     * @param spend 该账号在窗口内的 token 合计（没有记录 = 0）。
     */
    fun isExhausted(accountId: String, spend: Map<String, Long>): Boolean {
        if (!enabled) return false
        val used = spend[accountId] ?: 0L
        return used >= perAccountTokens
    }
}

/** 预算参数的校验（纯函数）。 */
object GatewayTokenBudgetRules {

    /** @return 第一处不合法，null = 通过。 */
    fun validate(budget: GatewayTokenBudget): String? = when {
        budget.perAccountTokens < 0L -> "预算不能是负数（0 表示不限）"
        budget.windowHours !in 1..720 -> "窗口要在 1~720 小时之间"
        else -> null
    }
}
