package dev.praxis.core.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑动窗口限流器与请求节流器。
 *
 * ## 为什么这些必须被测试
 *
 * 限流/并发的错都是**静默**的：多放了几个请求不会报错，
 * 只会在某天突然 429。而少放了请求（名额泄漏）的表现是
 * 「对话越来越慢，最后彻底不动」—— 用户根本不会想到
 * 是客户端自己的闸门卡住了。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestThrottleTest {

    @Test
    fun rate_limiter_allows_limit_then_waits() = runTest {
        val limiter = SlidingWindowRateLimiter(maxRequestsPerMinute = 3, windowMs = 1_000L)
        val now = 10_000L
        assertEquals(0L, limiter.tryAcquire(now))
        assertEquals(0L, limiter.tryAcquire(now))
        assertEquals(0L, limiter.tryAcquire(now))
        assertEquals(1_000L, limiter.tryAcquire(now))
    }

    @Test
    fun rate_limiter_releases_after_window() = runTest {
        val limiter = SlidingWindowRateLimiter(maxRequestsPerMinute = 2, windowMs = 1_000L)
        assertEquals(0L, limiter.tryAcquire(0L))
        assertEquals(0L, limiter.tryAcquire(0L))
        assertTrue(limiter.tryAcquire(0L) > 0L)
        assertEquals(0L, limiter.tryAcquire(1_000L))
        assertEquals(1, limiter.usedInWindow(1_000L))
    }

    @Test
    fun rate_limiter_with_zero_limit_never_blocks() = runTest {
        val limiter = SlidingWindowRateLimiter(maxRequestsPerMinute = 0)
        repeat(100) {
            assertEquals(0L, limiter.tryAcquire(0L))
        }
    }

    @Test
    fun throttle_is_noop_when_unconfigured() = runTest {
        val throttle = RequestThrottle(0, 0)
        assertTrue(throttle.isNoOp)
        repeat(50) {
            throttle.acquire().close()
        }
    }

    @Test
    fun throttle_limits_concurrency() = runTest {
        val throttle = RequestThrottle(maxConcurrentRequests = 2)
        val p1 = throttle.acquire()
        val p2 = throttle.acquire()
        var thirdDone = false
        val third = async {
            throttle.acquire().use { thirdDone = true }
        }
        yield()
        assertTrue(!thirdDone)
        p1.close()
        yield()
        assertTrue(thirdDone)
        p2.close()
        third.await()
    }

    @Test
    fun permit_close_is_idempotent() = runTest {
        val throttle = RequestThrottle(maxConcurrentRequests = 1)
        val p = throttle.acquire()
        p.close()
        p.close()
        val p2 = throttle.acquire()
        var blocked = false
        val extra = async {
            throttle.acquire().use {
                blocked = true
            }
        }
        yield()
        assertTrue(!blocked)
        p2.close()
        extra.await()
    }

    @Test
    fun used_in_window_never_exceeds_limit() = runTest {
        val limiter = SlidingWindowRateLimiter(maxRequestsPerMinute = 5, windowMs = 60_000L)
        repeat(5) { limiter.tryAcquire(0L) }
        assertEquals(5, limiter.usedInWindow(0L))
        assertTrue(limiter.tryAcquire(0L) > 0L)
        assertEquals(5, limiter.usedInWindow(0L))
    }

    @Test
    fun combined_limits_allow_configured_volume() = runTest {
        val throttle = RequestThrottle(requestLimitPerMinute = 10, maxConcurrentRequests = 2)
        val done = (1..3).map {
            async {
                throttle.acquire().use { }
                true
            }
        }.awaitAll()
        assertEquals(listOf(true, true, true), done)
    }
}
