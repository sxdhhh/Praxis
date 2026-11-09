package dev.praxis.core.gateway

/**
 * 一次路由决策的留痕（文档 §5.5 的「回放」，**降级为最近 N 条**）。
 *
 * ## 为什么只留内存、不落库
 *
 * 文档把回放定成「降级为最近 50 条」：它是**排障用**的瞬时视图，
 * 不是报表（报表有请求记录表）。落库要多一张表、一次迁移、一套保留策略 ——
 * 而用户真正会问的问题是「刚才那次为什么没走 A 账号」，那个答案就在最近几条里。
 *
 * 代价写清楚：**进程重启后回放是空的**。
 */
data class RouteReplayEntry(
    /** 请求模型（对外名）。 */
    val model: String,
    /** 这次请求尝试过的组合，按顺序，形如 `accountId/wireModel`。 */
    val tried: List<String>,
    /** 被跳过的组合与原因。 */
    val skipped: List<SkippedAttempt>,
    /** 一句话结局（成功 / 试完所有候选 / 没有可用候选）。 */
    val outcome: String,
    /** 记下它的时刻（毫秒）。 */
    val atMillis: Long,
)

/**
 * 最近若干条路由决策（环形缓冲，纯 Kotlin 便于单测）。
 *
 * ## 为什么是固定容量 + 覆盖最旧
 *
 * 排障看的是「刚才」，不是「上周」。固定容量让内存上限可预期（50 条 × 几十字节），
 * 也不会出现「跑了三天把内存吃满」这种事。
 *
 * ## 线程安全
 *
 * 网关的请求可能并发（多个会话）。这里用 `synchronized` 保护 —— 一条记录只有几个字段，
 * 锁的粒度足够小；用并发集合反而更绕（还要处理「写入时正在读」的顺序问题）。
 */
class RouteReplayLog(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayDeque<RouteReplayEntry>()

    fun record(entry: RouteReplayEntry) {
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    /** 最近的在前（最新的那条最容易用得上）。 */
    fun recent(): List<RouteReplayEntry> = synchronized(entries) {
        entries.reversed()
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    companion object {
        /** 文档 §5.5 的降级档位：最近 50 条。 */
        const val DEFAULT_CAPACITY: Int = 50
    }
}
