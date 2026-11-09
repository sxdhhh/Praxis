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

    /** 保留期清理（文档 §5.7 的数据保留）。 */
    suspend fun purgeBefore(beforeMillis: Long): Int = dao.purgeRequestsBefore(beforeMillis)
}
