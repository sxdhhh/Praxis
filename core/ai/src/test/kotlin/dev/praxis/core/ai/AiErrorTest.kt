package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误归类表。
 *
 * 这张表决定「失败后是自动重试还是停下来让用户改配置」，
 * 归错类会造成两种相反的事故：对 400 一直重试刷爆配额，
 * 或者对 429 直接放弃。
 */
class AiErrorTest {

    @Test
    fun `http codes map to expected kinds`() {
        assertEquals(AiErrorKind.AUTH, AiError.fromHttpCode(401, "").kind)
        assertEquals(AiErrorKind.AUTH, AiError.fromHttpCode(403, "").kind)
        assertEquals(AiErrorKind.RATE_LIMIT, AiError.fromHttpCode(429, "").kind)
        assertEquals(AiErrorKind.NETWORK, AiError.fromHttpCode(408, "").kind)
        assertEquals(AiErrorKind.SERVER, AiError.fromHttpCode(500, "").kind)
        assertEquals(AiErrorKind.SERVER, AiError.fromHttpCode(503, "").kind)
        assertEquals(AiErrorKind.INVALID_REQUEST, AiError.fromHttpCode(400, "").kind)
        assertEquals(AiErrorKind.UNKNOWN, AiError.fromHttpCode(302, "").kind)
    }

    @Test
    fun `retryable follows the kind table`() {
        assertTrue(AiError(AiErrorKind.NETWORK, "").retryable)
        assertTrue(AiError(AiErrorKind.SERVER, "").retryable)
        assertTrue(AiError(AiErrorKind.RATE_LIMIT, "").retryable)
        assertTrue(AiError(AiErrorKind.STREAM_INTERRUPTED, "").retryable)

        assertFalse(AiError(AiErrorKind.AUTH, "").retryable)
        assertFalse(AiError(AiErrorKind.INVALID_REQUEST, "").retryable)
        assertFalse(AiError(AiErrorKind.CANCELLED, "").retryable)
        assertFalse(AiError(AiErrorKind.UNKNOWN, "").retryable)
    }

    @Test
    fun `retryAfter is only passed through from the server`() {
        assertNull(AiError.fromHttpCode(500, "").retryAfterMillis)
        assertEquals(4200L, AiError.fromHttpCode(429, "", retryAfterMillis = 4200L).retryAfterMillis)
    }
}