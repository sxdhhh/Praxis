package dev.praxis.data.gateway

import dev.praxis.core.data.gateway.GatewayUsageDao
import dev.praxis.core.data.gateway.GatewayRequestEntity
import dev.praxis.core.gateway.GatewayUsageDraft

/**
 * 请求记录的写入层（文档 6.1 的 `gateway_request`）。
 *
 * ## 只做一件事：把领域草稿翻译成实体
 *
 * 「失败清零」「状态码归一」这些规则在 `core:gateway` 的 GatewayUsageRules（有单测），
 * 这一层不重复判断 —— 否则两处规则迟早对不上，而报表错位是很难发现的错。
 */
class GatewayUsageStore(
    private val dao: GatewayUsageDao,
    private val clock: () -> Long,
) {

    suspend fun record(draft: GatewayUsageDraft) {
        dao.insertRequest(
            GatewayRequestEntity(
                ts = clock(),
                model = draft.model,
                upstreamModel = draft.upstreamModel,
                providerId = draft.providerId,
                accountId = draft.accountId,
                status = draft.status,
                error = draft.error,
                attempts = draft.attempts,
                promptTokens = draft.promptTokens,
                completionTokens = draft.completionTokens,
                cacheReadTokens = draft.cacheReadTokens,
                durationMs = draft.durationMs,
                isTest = draft.isTest,
            ),
        )
    }

    /** 近 [sinceMillis] 之后的请求数（报表与限流都要）。 */
    suspend fun countSince(sinceMillis: Long): Int = dao.countSince(sinceMillis)

    /** 近 [sinceMillis] 之后的 token 总数；没有记录时上游返回 null，这里给 0。 */
    suspend fun tokensSince(sinceMillis: Long): Long = dao.tokensSince(sinceMillis) ?: 0L

    /**
     * 最近 [days] 天的汇总（含今天）。
     *
     * ## 起点为什么这样算
     *
     * 「今日」= 今天 00:00（**本地时区**）；「近 7 天」= 6 天前的 00:00。
     * 用 `minusDays(days - 1)` 而不是 `minusDays(days)`：后者会把 7 天算成 8 个自然日，
     * 报表上表现为「近 7 天的数字比直觉大一点」，而没人会去数。
     */
    suspend fun summarySince(nowMillis: Long, days: Long, zone: java.time.ZoneId): dev.praxis.core.gateway.GatewayUsageSummary {
        val since = java.time.Instant.ofEpochMilli(nowMillis)
            .atZone(zone)
            .toLocalDate()
            .minusDays((days - 1).coerceAtLeast(0))
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val totals = dao.summarySince(since)
        val buckets = dao.byModelSince(since).map { row ->
            dev.praxis.core.gateway.GatewayUsageBucket(
                name = row.name,
                requests = row.requests,
                failures = row.failures,
                tokens = row.tokens,
            )
        }
        return dev.praxis.core.gateway.GatewayUsageSummary(
            requests = totals?.requests ?: 0,
            failures = totals?.failures ?: 0,
            tokens = totals?.tokens ?: 0L,
            byModel = buckets,
        )
    }

    /**
     * 窗口内各账号的 token 用量（token 预算判定用）。
     *
     * 没有记录的账号不会出现在 map 里 —— 判定侧按「没有 = 0」处理（有单测钉这条）。
     */
    suspend fun tokensByAccountSince(sinceMillis: Long): Map<String, Long> =
        dao.tokensByAccountSince(sinceMillis).associate { row -> row.accountId to row.tokens }

    /**
     * 最近的请求记录（日志页的「请求记录」视图）。
     *
     * 与报表口径不同：**包含测试请求**。报表排除它（会污染趋势），
     * 而排障时用户点了「测试连接」就该能在这里看到那条请求。
     */
    suspend fun recentRequests(limit: Int = 200): List<dev.praxis.core.gateway.GatewayRequestRecord> =
        dao.recentRequests(limit).map { row ->
            dev.praxis.core.gateway.GatewayRequestRecord(
                id = row.id,
                ts = row.ts,
                model = row.model,
                providerId = row.providerId,
                status = row.status,
                error = row.error,
                attempts = row.attempts,
                promptTokens = row.promptTokens,
                completionTokens = row.completionTokens,
                durationMs = row.durationMs,
                isTest = row.isTest,
            )
        }

    /** 保留期清理（文档 §5.7 的数据保留）。 */
    suspend fun purgeBefore(beforeMillis: Long): Int = dao.purgeRequestsBefore(beforeMillis)
}
