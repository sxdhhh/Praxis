package dev.praxis.core.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * 滑动窗口限流器。对齐 Operit 的 `SlidingWindowRateLimiter`。
 *
 * ## 为什么用滑动窗口而不是固定窗口（每分钟换桶）
 *
 * 固定窗口有一个真实的恶化案例：限额 10/分钟，用户在 12:00:59 发 10 个、
 * 12:01:00 再发 10 个 —— 两秒内 20 个请求，全部打到服务端。
 * 服务端按它自己的窗口计数，直接 429。
 *
 * 滑动窗口记的是「最近 60 秒内发了几个」，任意时刻都不会超过限额，
 * 这才是 RPM 的真实语义。
 *
 * ## 为什么用 [Mutex] 而不是原子计数
 *
 * 判定「能不能发」与「记一次发送」必须是原子的。拆成两步的话，
 * 两个协程可能同时看到「还剩 1 个名额」，然后都发出去 —— 超限。
 */
class SlidingWindowRateLimiter(
    val maxRequestsPerMinute: Int,
    private val windowMs: Long = 60_000L,
) {
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Long>()

    /**
     * 尝试占一个名额。
     *
     * @return 0 表示已占到位、可以立即发；大于 0 表示还需等多少毫秒。
     *
     * ## 为什么返回等待时长而不是直接 delay
     *
     * 直接在这里 `delay` 会让「等待」发生在锁外还是锁内变成隐患：
     * 锁内 delay 会堵死所有人，锁外 delay 则要额外保证顺序。
     * 返回时长让调用方自己决定怎么等（并且能响应取消）。
     */
    suspend fun tryAcquire(nowMs: Long = System.currentTimeMillis()): Long {
        if (maxRequestsPerMinute <= 0) return 0L
        return mutex.withLock {
            // 先清掉窗口外的旧记录。
            while (timestamps.isNotEmpty() && nowMs - timestamps.first() >= windowMs) {
                timestamps.removeFirst()
            }
            if (timestamps.size >= maxRequestsPerMinute) {
                val oldest = timestamps.first()
                // 至少要等 1ms：返回 0 会被当成「已占到位」。
                (windowMs - (nowMs - oldest)).coerceAtLeast(1L)
            } else {
                timestamps.addLast(nowMs)
                0L
            }
        }
    }

    /** 阻塞式占位：一直等到拿到名额。被取消时抛 CancellationException。 */
    suspend fun acquire() {
        while (true) {
            val waitMs = tryAcquire()
            if (waitMs <= 0L) return
            delay(waitMs)
        }
    }

    /** 当前窗口内已用掉的请求数。仅用于展示与测试。 */
    suspend fun usedInWindow(nowMs: Long = System.currentTimeMillis()): Int = mutex.withLock {
        while (timestamps.isNotEmpty() && nowMs - timestamps.first() >= windowMs) {
            timestamps.removeFirst()
        }
        timestamps.size
    }
}

/**
 * 请求节流器：把限流与并发限制合并成一个「进场许可证」。
 *
 * ## 为什么不直接包一层 AiProvider
 *
 * 包 provider 需要新建一个类、把 [ChatRequest] 原样转发、还要处理
 * 取消透传。而 Praxis 真正需要节流的地方只有一处（`SettingsBackedProvider`
 * 发请求之前），在那里 `throttle.acquire()` 就够了 —— 少一层包装就少一处
 * 「包装没转发某个字段」的隐患。
 *
 * ## 两个限制的顺序
 *
 * 先拿并发名额，再等限流窗口。反过来说：拿不到并发就不应该消耗限流名额
 * （否则会在没发请求的情况下把额度用掉）。这是 Operit 的
 * `RateLimitedAIService` 里的顺序，也是唯一正确的顺序。
 */
class RequestThrottle(
    /** 每分钟最多请求数。0 或负数 = 不限。 */
    val requestLimitPerMinute: Int = 0,
    /** 最大并发请求数。0 或负数 = 不限。 */
    val maxConcurrentRequests: Int = 0,
) {
    private val rateLimiter: SlidingWindowRateLimiter? =
        if (requestLimitPerMinute > 0) {
            SlidingWindowRateLimiter(requestLimitPerMinute)
        } else {
            null
        }

    private val semaphore: Semaphore? =
        if (maxConcurrentRequests > 0) Semaphore(maxConcurrentRequests) else null

    /** 两个限制都没配时不需要任何等待。调用方可据此完全跳过。 */
    val isNoOp: Boolean
        get() = rateLimiter == null && semaphore == null

    /**
     * 等到可以发请求为止，返回一个必须被 close 的许可证。
     *
     * ## 为什么返回许可证而不是 acquire/release 两个方法
     *
     * 两个方法必然出现「acquire 了但某条异常路径忘了 release」，
     * 名额就此永久泄漏，几次之后彻底卡死。用 `use { }` 把释放绑在
     * 作用域退出上，编译器的结构化作用域会保证它一定被调用。
     */
    suspend fun acquire(): Permit {
        val sem = semaphore
        // 先占并发位：拿不到就不该消耗限流额度。
        sem?.acquire()
        try {
            rateLimiter?.acquire()
        } catch (t: Throwable) {
            // 限流阶段被取消（用户停止生成）时必须把并发位还回去。
            sem?.release()
            throw t
        }
        return Permit(sem)
    }

    /** 并发名额的持有凭证。`close()` 幂等。 */
    class Permit internal constructor(private val semaphore: Semaphore?) : AutoCloseable {
        private var released = false

        override fun close() {
            if (released) return
            released = true
            semaphore?.release()
        }
    }
}
