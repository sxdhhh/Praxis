package dev.praxis.core.gateway

import dev.praxis.core.ai.AiErrorKind

/**
 * 把一次上游失败判成 [AiErrorKind]（文档 §7.4 那张表的**产出点**）。
 *
 * ## 这一层此前是空的
 *
 * `AiError.fromHttpCode` 只看状态码：429 一律 RATE_LIMIT、401 一律 AUTH。
 * 于是「429 但正文写着 insufficient_quota」与「429 只是打了太快」被当成同一件事，
 * 而文档 §7.4 对它们的处置不同（前者要等上游重置，后者过一会儿就能用）。
 * 同理 401 的两种含义（密钥错 / 令牌过期）也被压成一种 —— 后者值得先续期一次。
 *
 * ## 为什么读正文而不只读状态码
 *
 * 上游（尤其各家网关）大量用 200/400/429 混着表达同一件事：
 * OpenAI 的额度耗尽可能是 429 + `insufficient_quota`，也可能是 200 + 正文 `insufficient quota`；
 * 内容拦截则几乎总是**HTTP 200** + `finish_reason: content_filter`。
 * 只读状态码在这两类上都必然误判，而误判的代价不是报错、是**罚错账号**。
 *
 * ## 判定顺序为什么是「正文标记优先于状态码」
 *
 * 状态码只有十几个，而同一状态码在不同家意味着不同的事；正文标记更具体。
 * 所以先按标记分类，落到状态码兜底。唯一例外是 [CONTENT_BLOCKED_MARKERS] ——
 * 它必须与 `finish_reason` 一起看，否则「请求里提到了 content_filter 这个词」
 * 也会被当成拦截（例如用户在提示词里讨论这个词）。
 */
object UpstreamErrorClassifier {

    /** 额度耗尽的正文标记（全部按小写匹配）。 */
    private val QUOTA_MARKERS = listOf(
        "insufficient_quota",
        "insufficient quota",
        "exceeded your current quota",
        "quota exceeded",
        "quota_exceeded",
        "out of credits",
        "credit balance is too low",
        "余额不足",
        "额度不足",
        "额度已用尽",
        "超出配额",
    )

    /** 令牌过期/失效的正文标记。**不包含** `invalid api key` —— 那是密钥错，不是过期。 */
    private val TOKEN_EXPIRED_MARKERS = listOf(
        "token has expired",
        "token expired",
        "expired_token",
        "access token expired",
        "refresh token expired",
        "please re-authenticate",
        "invalid_grant",
    )

    /** 内容拦截标记：只在与 finish_reason 同时出现时才采纳（见类 KDoc）。 */
    private val CONTENT_BLOCKED_MARKERS = listOf(
        "content_filter",
        "content_policy_violation",
        "content policy",
        "safety_system",
        "blocked by content",
    )

    /** 体积/上下文超限标记（413 一类）。 */
    private val TOO_LARGE_MARKERS = listOf(
        "request too large",
        "payload too large",
        "maximum context length",
        "context_length_exceeded",
        "too many tokens",
        "prompt is too long",
        "exceeds the maximum",
    )

    /**
     * @param status HTTP 状态码
     * @param body 响应正文（已 `take` 过的片段即可）
     * @param finishReason 流式/非流式里的 `finish_reason`；没有就传 null
     */
    fun classify(status: Int, body: String, finishReason: String? = null): AiErrorKind {
        val text = body.lowercase()

        // ① 内容拦截：finish_reason 是权威信号。
        //    判定与 core:ai 的流式累积器共用 [ContentFilterReason]，避免两处字面量漂移。
        if (dev.praxis.core.ai.ContentFilterReason.matches(finishReason)) {
            return AiErrorKind.CONTENT_BLOCKED
        }

        // ② 额度耗尽：正文标记优先（同一状态码可能只是限速）。
        if (QUOTA_MARKERS.any { text.contains(it) }) return AiErrorKind.QUOTA_LIMITED

        // ③ 令牌过期：与「密钥错」分开 —— 前者值得先续期一次。
        if (TOKEN_EXPIRED_MARKERS.any { text.contains(it) }) return AiErrorKind.TOKEN_EXPIRED

        // ④ 体积/上下文超限：归到「请求本身不合法」这一档（不换号）。
        if (status == 413 || TOO_LARGE_MARKERS.any { text.contains(it) }) {
            return AiErrorKind.INVALID_REQUEST
        }

        // ⑤ 正文里出现拦截标记但 finish_reason 没给：只在 4xx 上采纳。
        //    200 + 正文提到 content policy 多半是模型在讨论这个词，不该判拦截。
        if (status in 400..499 && CONTENT_BLOCKED_MARKERS.any { text.contains(it) }) {
            return AiErrorKind.CONTENT_BLOCKED
        }

        // ⑥ 状态码兜底（与 AiError.fromHttpCode 同一口径）。
        return when {
            status == 401 || status == 403 -> AiErrorKind.AUTH
            status == 429 -> AiErrorKind.RATE_LIMIT
            status == 408 -> AiErrorKind.NETWORK
            status in 500..599 -> AiErrorKind.SERVER
            status in 400..499 -> AiErrorKind.INVALID_REQUEST
            else -> AiErrorKind.UNKNOWN
        }
    }
}
