package dev.praxis.core.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 请求队列的等待上限（文档 §5.18 的「请求队列」）。 */
class RequestThrottleQueueTest {

    @Test
    fun `上限为 0 时等同无限等（旧行为）`() = runBlocking {
        val throttle = RequestThrottle(maxConcurrentRequests = 1, maxWaitMs = 0L)
        val first = throttle.acquire()
        // 第二个请求会一直等；这里只验证「不返回 null」这件事的另一面 ——
        // 上限为 0 时 tryAcquire 走 acquire，不会因为超时而放弃。
        val job = async { throttle.tryAcquire() }
        delay(120)
        assertTrue("上限 0 时不该结束等待", job.isActive)
        first.close()
        val second = job.await()
        assertNotNull(second)
        second!!.close()
    }

    @Test
    fun `等不到名额时返回 null`() = runBlocking {
        val throttle = RequestThrottle(maxConcurrentRequests = 1, maxWaitMs = 80L)
        val held = throttle.acquire()
        val start = System.currentTimeMillis()
        val got = throttle.tryAcquire()
        val cost = System.currentTimeMillis() - start
        assertNull("占满且超时后应当拿不到", got)
        assertTrue("应当真的等过（cost=" + cost + "ms）", cost >= 60)
        held.close()
    }

    @Test
    fun `释放后立刻能拿到`() = runBlocking {
        val throttle = RequestThrottle(maxConcurrentRequests = 1, maxWaitMs = 300L)
        val held = throttle.acquire()
        val job = async { throttle.tryAcquire() }
        delay(40)
        held.close()
        val got = job.await()
        assertNotNull("释放后应当拿到", got)
        got!!.close()
    }

    @Test
    fun `两个限制都没配时是空操作（上限不影响这条）`() {
        val throttle = RequestThrottle(requestLimitPerMinute = 0, maxConcurrentRequests = 0, maxWaitMs = 5000L)
        assertTrue("没有限制就该是空操作：上限只是等待上限，不该凭空造出限制", throttle.isNoOp)
    }

    @Test
    fun `限流窗口仍按每分钟生效（上限没破坏它）`() = runBlocking {
        val throttle = RequestThrottle(requestLimitPerMinute = 2, maxWaitMs = 200L)
        val a = throttle.tryAcquire()
        val b = throttle.tryAcquire()
        assertNotNull(a)
        assertNotNull(b)
        a!!.close()
        b!!.close()
        // 第三次会撞窗口 → 200ms 内拿不到。
        val c = throttle.tryAcquire()
        assertNull("窗口内第三次应当等不到", c)
    }
}
