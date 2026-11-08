package dev.praxis.core.gateway

import dev.praxis.core.ai.AiErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 错误 -> 失败分类（文档 7.4 的落地）。
 *
 * ## 这张表错一行的后果
 *
 * 不是报错，而是**罚错账号**：把 429 当上游错误会让账号白退避 15 分钟；
 * 把内容拦截当账号失败，会让一个健康账号被换掉还被冷却。
 */
class AiErrorMappingTest {

    @Test
    fun 额度相关的两种都归到额度受限() {
        assertEquals(FailureKind.QuotaLimited, AiErrorKind.QUOTA_LIMITED.toFailureKind())
        assertEquals(FailureKind.QuotaLimited, AiErrorKind.RATE_LIMIT.toFailureKind())
    }

    @Test
    fun 凭证相关的两种都归到凭证过期() {
        assertEquals(FailureKind.CredentialExpired, AiErrorKind.TOKEN_EXPIRED.toFailureKind())
        assertEquals(FailureKind.CredentialExpired, AiErrorKind.AUTH.toFailureKind())
    }

    @Test
    fun 内容拦截单独一类() {
        assertEquals(FailureKind.ContentBlocked, AiErrorKind.CONTENT_BLOCKED.toFailureKind())
    }

    @Test
    fun 请求非法不换号() {
        assertEquals(FailureKind.InvalidRequest, AiErrorKind.INVALID_REQUEST.toFailureKind())
    }

    @Test
    fun 其余都按上游错误退避换号() {
        val upstream = listOf(
            AiErrorKind.NETWORK,
            AiErrorKind.SERVER,
            AiErrorKind.STREAM_INTERRUPTED,
            AiErrorKind.PROTOCOL,
            AiErrorKind.UNKNOWN,
        )
        upstream.forEach {
            assertEquals("kind=" + it, FailureKind.UpstreamError, it.toFailureKind())
        }
    }

    @Test
    fun 每一种_kind_都有映射() {
        // 将来 core:ai 再加 kind 时，这条会红，逼着人来决定它该怎么处置。
        AiErrorKind.entries.forEach { kind ->
            assertTrue("没有映射：" + kind, runCatching { kind.toFailureKind() }.isSuccess)
        }
    }
}
