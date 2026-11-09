package dev.praxis.core.gateway

/**
 * 一条待落库的请求记录（文档 6.1 的 `gateway_request` 的领域视图）。
 *
 * ## 为什么不直接用 Room 实体
 *
 * 构造记录的是 `GatewayProvider`（:app），而规则（失败清零、状态码归一）属于领域知识，
 * 要在 core 里单测。实体在 :core:data（Android 模块，本机跑不了测试）。
 */
data class GatewayUsageDraft(
    /** 对外模型名（用户请求的那个）。 */
    val model: String,
    /** 真正发给上游的名字；换号后可能与对外名不同。 */
    val upstreamModel: String?,
    val providerId: String?,
    val accountId: String?,
    /** 200 = 成功；0 = 没拿到响应；其余为上游 HTTP 码。 */
    val status: Int,
    val error: String?,
    /** 这一轮试了几个组合。 */
    val attempts: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheReadTokens: Int,
    val durationMs: Long,
    /** 连通性测试产生的请求不入报表。 */
    val isTest: Boolean = false,
)

/** 记录的构造规则（纯函数）。 */
object GatewayUsageRules {

    const val STATUS_OK: Int = 200

    /** 没有拿到任何响应时的状态码（网络层失败）。 */
    const val STATUS_NO_RESPONSE: Int = 0

    /**
     * 失败请求的 token 一律清零（文档 §5.7）。
     *
     * ## 为什么必须清零
     *
     * 上游报错时 usage 常常缺失，而我们可能已经从**前一次尝试**里收到了半截用量。
     * 留着它会让「失败暴增」在报表上看起来像「用量暴增」—— 那是两个完全不同的信号。
     */
    fun tokensFor(
        success: Boolean,
        promptTokens: Int,
        completionTokens: Int,
        cacheReadTokens: Int,
    ): Triple<Int, Int, Int> = if (success) {
        Triple(promptTokens, completionTokens, cacheReadTokens)
    } else {
        Triple(0, 0, 0)
    }

    /** 构造一条记录：token 按上面那条规则归一。 */
    fun build(
        draft: GatewayUsageDraft,
        success: Boolean,
    ): GatewayUsageDraft {
        val (prompt, completion, cache) = tokensFor(
            success = success,
            promptTokens = draft.promptTokens,
            completionTokens = draft.completionTokens,
            cacheReadTokens = draft.cacheReadTokens,
        )
        return draft.copy(
            promptTokens = prompt,
            completionTokens = completion,
            cacheReadTokens = cache,
        )
    }
}
