package dev.praxis.core.gateway

import dev.praxis.core.ai.AiErrorKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 三类错误的产出点（文档 §7.4）。
 *
 * 它守的是一条**代价不对称**的规则：判错不会报错，会**罚错账号** ——
 * 「额度耗尽」被判成「限速」会让这个账号一直被选中、一直失败；
 * 「内容拦截」被判成「上游错误」会白白让好账号进冷却。
 */
class UpstreamErrorClassifierTest {

    @Test
    fun `429 带配额标记按额度耗尽`() {
        assertEquals(
            AiErrorKind.QUOTA_LIMITED,
            UpstreamErrorClassifier.classify(429, "{\"error\":{\"message\":\"You exceeded your current quota\"}}"),
        )
    }

    @Test
    fun `429 没有配额标记只是限速`() {
        assertEquals(AiErrorKind.RATE_LIMIT, UpstreamErrorClassifier.classify(429, "{\"error\":\"rate limit reached\"}"))
    }

    @Test
    fun `200 正文写 insufficient_quota 也算额度耗尽`() {
        // 不少网关用 200 + 正文表达额度耗尽；只读状态码必然漏判。
        assertEquals(
            AiErrorKind.QUOTA_LIMITED,
            UpstreamErrorClassifier.classify(200, "insufficient_quota"),
        )
    }

    @Test
    fun `finish_reason 命中内容拦截时不看状态码`() {
        assertEquals(
            AiErrorKind.CONTENT_BLOCKED,
            UpstreamErrorClassifier.classify(200, "", finishReason = "content_filter"),
        )
    }

    @Test
    fun `401 带过期标记按令牌过期而不是密钥错`() {
        assertEquals(
            AiErrorKind.TOKEN_EXPIRED,
            UpstreamErrorClassifier.classify(401, "{\"error\":\"token has expired\"}"),
        )
    }

    @Test
    fun `401 没有过期标记仍是认证失败`() {
        assertEquals(AiErrorKind.AUTH, UpstreamErrorClassifier.classify(401, "invalid api key"))
    }

    @Test
    fun `413 按请求不合法归类不再重试`() {
        assertEquals(AiErrorKind.INVALID_REQUEST, UpstreamErrorClassifier.classify(413, "payload too large"))
    }

    @Test
    fun `上下文超限的正文标记也归到请求不合法`() {
        assertEquals(
            AiErrorKind.INVALID_REQUEST,
            UpstreamErrorClassifier.classify(400, "This model's maximum context length is 8192 tokens"),
        )
    }

    @Test
    fun `5xx 与网络超时仍走既有口径`() {
        assertEquals(AiErrorKind.SERVER, UpstreamErrorClassifier.classify(503, "upstream busy"))
        assertEquals(AiErrorKind.NETWORK, UpstreamErrorClassifier.classify(408, ""))
    }

    @Test
    fun `200 正文只是提到 content policy 不算拦截`() {
        // 用户在提示词里讨论这个词时，正文里会带着它一起回来。
        assertEquals(AiErrorKind.UNKNOWN, UpstreamErrorClassifier.classify(200, "the content policy of this site"))
    }

    @Test
    fun `4xx 正文带拦截标记时采纳`() {
        assertEquals(
            AiErrorKind.CONTENT_BLOCKED,
            UpstreamErrorClassifier.classify(400, "{\"error\":\"blocked by content policy\"}"),
        )
    }

    @Test
    fun `中文配额文案也能识别`() {
        assertEquals(AiErrorKind.QUOTA_LIMITED, UpstreamErrorClassifier.classify(400, "余额不足，请充值"))
    }

    @Test
    fun `同一状态码按正文分流而不是一刀切`() {
        // 这条是整张表的意义所在：三个 400 落到三个不同的分类。
        assertEquals(AiErrorKind.QUOTA_LIMITED, UpstreamErrorClassifier.classify(400, "quota exceeded"))
        assertEquals(AiErrorKind.TOKEN_EXPIRED, UpstreamErrorClassifier.classify(400, "invalid_grant"))
        assertEquals(AiErrorKind.INVALID_REQUEST, UpstreamErrorClassifier.classify(400, "bad request body"))
    }
}
