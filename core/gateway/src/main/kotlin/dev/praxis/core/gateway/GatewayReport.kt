package dev.praxis.core.gateway

/**
 * 报表里的一段时间（文档 §9.6 的「今日 / 近 7 天 / 近 30 天」）。
 *
 * 时长以「天」为单位、由 `GatewayDailyKey` 折算成起点时刻 —— 日界线必须用**本地时区**，
 * 否则东八区用户的「今天」会整体错 8 小时（网关 #329）。
 */
data class GatewayReportWindow(val label: String, val days: Long)

/** 一段时间内的用量汇总。 */
data class GatewayUsageSummary(
    val requests: Int,
    val failures: Int,
    val tokens: Long,
    /** 按请求数降序。 */
    val byModel: List<GatewayUsageBucket>,
) {
    /** 成功率；没有请求时返回 null（不是 0 —— 「没有数据」与「全失败」是两回事）。 */
    val successRate: Double?
        get() = if (requests == 0) null else (requests - failures).toDouble() / requests
}

/** 一个分组（模型 / 家）的用量。 */
data class GatewayUsageBucket(
    val name: String,
    val requests: Int,
    val failures: Int,
    val tokens: Long,
)

/** 报表的展示规则（纯函数，便于单测）。 */
object GatewayReportFormat {

    /**
     * 成功率显示成 `92.3%` / `50%`；没有请求时显示 `—`（而不是 `0%`）。
     *
     * 末尾的 `.0` 要去掉：`50.0%` 在报表里读起来像"精确到小数点后一位"，
     * 而它其实只是整数 —— 一位小数只在真的有时保留。
     */
    fun successRateText(rate: Double?): String {
        if (rate == null) return "—"
        if (rate >= 0.9999) return "100%"
        // 以「千分之一」为单位取整，避免浮点误差把 0.923 变成 92.2%。
        val tenths = (rate * 1000).toLong()
        val whole = tenths / 10
        val fraction = tenths % 10
        return if (fraction == 0L) {
            whole.toString() + "%"
        } else {
            whole.toString() + "." + fraction + "%"
        }
    }

    /** token 数太大时缩写，报表里一屏放得下。 */
    fun tokenText(tokens: Long): String = when {
        tokens >= 1_000_000 -> (tokens / 100_000).toDouble().div(10).toString() + "M"
        tokens >= 1_000 -> (tokens / 100).toDouble().div(10).toString() + "k"
        else -> tokens.toString()
    }

    fun requestsText(summary: GatewayUsageSummary): String =
        summary.requests.toString() + " 次 · 失败 " + summary.failures +
            " · 成功率 " + successRateText(summary.successRate) +
            " · " + tokenText(summary.tokens) + " tokens"
}
