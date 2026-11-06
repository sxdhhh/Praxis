package dev.praxis.core.model

import java.util.concurrent.atomic.AtomicLong

/**
 * 为聊天消息分配**严格单调递增**的时间戳。
 *
 * ## 为什么需要它（2026-11-07，R6）
 *
 * 从这一轮开始，消息的排序键是 createdAt（时间戳），不再是 orderIndex。
 * 这么做是因为 orderIndex 有两个治不好的问题：
 *
 *  1. **它是数据库算出来的**（MAX(orderIndex) + 1）。分配点与业务代码
 *     隔着一次异步调用，谁先拿到锁不确定 —— 这正是「重进会话顺序乱」
 *     的根因（R3 只把两条路径串起来，没改分配机制）。
 *  2. **它容不下「插到历史中间」**。摘要要插在被折叠那段的末尾，
 *     用 orderIndex 就得把后面所有行整体后移（R9 要处理的事）。
 *     而时间戳天然可以在两者之间取一个值。
 *
 * ## 为什么不能直接用 System.currentTimeMillis()
 *
 * 同一毫秒内可以插入很多条（工具连续返回时尤其明显）。
 * 两条同毫秒的消息按 id 兜底虽然能排序，但一旦跨了「插入中间」
 * 的场景（R9 取中点），同毫秒会让中点算不出来。
 *
 * 所以这里保证**严格递增**：max(now, last + 1)。
 * 时钟回拨（用户改时间、NTP 校正）也不会产生倒序。
 *
 * ## 为什么是 class 而不是 object
 *
 * 全局单例在测试里必须 reset()，而并发测试两个用例会互相污染。
 * 做成 class 后每个测试自己 new 一个，天然隔离；生产环境由 Koin 提供一个
 * single 实例，保证 ChatRepository 与 ChatViewModel 拿到的是同一个。
 */
class MessageTimestampAllocator(
    /** 时钟来源，注入是为了让测试能控制「同一毫秒」。 */
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val lastIssued = AtomicLong(0L)

    /**
     * 分配下一个时间戳。
     *
     * 保证：返回值 **严格大于** 此前任何一次 next() 或 observe() 的值。
     *
     * 用 CAS 循环而不是 synchronized：这是每条消息都会走一次的路径，
     * 而 CAS 在无竞争时不会进内核态。
     */
    fun next(): Long {
        while (true) {
            val previous = lastIssued.get()
            val candidate = maxOf(now(), previous + 1L)
            if (lastIssued.compareAndSet(previous, candidate)) return candidate
        }
    }

    /**
     * 把水位推高到至少 timestamp。
     *
     * ## 什么时候必须调
     *
     * 从数据库恢复历史之后。否则新消息可能拿到一个比历史更小的时间戳
     * （比如进程刚重启，lastIssued 还是 0），于是它会被排到历史前面 ——
     * 用户看到刚发的话出现在几小时前的对话里。
     *
     * 只前推、不回退：传入一个更小的值不会让水位下降。
     */
    fun observe(timestamp: Long) {
        while (true) {
            val previous = lastIssued.get()
            if (timestamp <= previous) return
            if (lastIssued.compareAndSet(previous, timestamp)) return
        }
    }

    /** 当前水位（调试与断言用）。 */
    fun watermark(): Long = lastIssued.get()
}
