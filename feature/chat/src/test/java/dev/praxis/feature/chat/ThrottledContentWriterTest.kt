package dev.praxis.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节流器的行为锁。
 *
 * 关键保证两条：
 *  1. 高频提交时写入次数被压下来（否则节流没意义）；
 *  2. **终态一定写到库**（否则用户看到文字但重启后消失）。
 *
 * 用可控时间源（不用 Thread.sleep）：时间推进由测试显式控制，
 * 结果确定，不会因机器快慢而 flaky。
 */
class ThrottledContentWriterTest {

    private var clock = 0L
    private val writes = mutableListOf<Pair<Long, String>>()

    private fun newWriter(minInterval: Long = 100L) = ThrottledContentWriter(
        minIntervalMs = minInterval,
        nowMs = { clock },
        write = { rowId, content -> writes += rowId to content },
    )

    @Test
    fun `first submit writes immediately`() {
        val w = newWriter()
        w.submit(1L, "a")
        assertEquals(listOf(1L to "a"), writes)
    }

    @Test
    fun `submits inside the window are suppressed`() {
        val w = newWriter()
        w.submit(1L, "a")   // 写入
        clock += 10
        w.submit(1L, "ab")  // 抑制
        clock += 10
        w.submit(1L, "abc") // 抑制

        assertEquals(1, writes.size)
    }

    @Test
    fun `submit after the window writes again`() {
        val w = newWriter()
        w.submit(1L, "a")
        clock += 100
        w.submit(1L, "ab")
        assertEquals(listOf(1L to "a", 1L to "ab"), writes)
    }

    @Test
    fun `flush writes the latest suppressed content`() {
        val w = newWriter()
        w.submit(1L, "a")
        clock += 10
        w.submit(1L, "ab")   // 抑制，暂存 "ab"
        clock += 10
        w.submit(1L, "abc")  // 抑制，暂存覆盖为 "abc"
        w.flush(1L)

        // 最后写入的必须是**最新**内容，不能是中间态。
        assertEquals(1L to "abc", writes.last())
    }

    @Test
    fun `flush without pending is a no-op`() {
        val w = newWriter()
        w.submit(1L, "a")
        w.flush(1L)   // 没有暂存
        assertEquals(1, writes.size)
    }

    @Test
    fun `flush twice does not duplicate`() {
        val w = newWriter()
        w.submit(1L, "a")
        clock += 10
        w.submit(1L, "ab")
        w.flush(1L)
        val countAfterFirst = writes.size
        w.flush(1L)
        assertEquals(countAfterFirst, writes.size)
    }

    @Test
    fun `different rows are throttled independently`() {
        val w = newWriter()
        w.submit(1L, "a")
        w.submit(2L, "x")
        // 第二个 row 首次提交不该被第一个的窗口影响。
        assertEquals(listOf(1L to "a", 2L to "x"), writes)
    }

    @Test
    fun `hundred submits collapse to about one tenth`() {
        val w = newWriter(minInterval = 100L)
        // 模拟 100 个 delta，每 10ms 一个 → 总时长 990ms。
        repeat(100) { i ->
            w.submit(1L, "chunk$i")
            clock += 10
        }
        w.flush(1L)

        // 理论最多 11 次（首写 + 每 100ms 一次 + flush），远小于 100。
        assertTrue("writes=${writes.size} should be <= 12", writes.size <= 12)
        // 但最后一次必须是完整内容。
        assertEquals("chunk99", writes.last().second)
    }

    @Test
    fun `forget clears pending so a later flush writes nothing`() {
        val w = newWriter()
        w.submit(1L, "a")
        clock += 10
        w.submit(1L, "ab")
        w.forget(1L)
        w.flush(1L)
        assertEquals(1, writes.size)
    }

    // ── drainPending（2026-10-22 新增，第 9 项：onCleared 兜底落盘）──────

    /**
     * `onCleared` 的兜底必须能拿到「所有」还在节流窗口里的正文。
     *
     * 为什么这条值得单测：它是「退出后记录就没了」的最后一道防线。
     * 一旦 `drainPending` 只返回一条、或返回后不清空，就会出现
     * 「A 气泡的正文被写进了 B 气泡的行」这类错位 —— 比丢失更难发现。
     */
    @Test
    fun `drainPending returns all pending entries from multiple rows`() {
        val w = newWriter()
        // 两行都在窗口内被抑制（先各写一次，再各提交一次）。
        w.submit(1L, "a1")
        w.submit(2L, "b1")
        clock += 10
        w.submit(1L, "a2")   // 抑制，进 pending
        w.submit(2L, "b2")   // 抑制，进 pending

        val drained = w.drainPending()

        assertEquals(mapOf(1L to "a2", 2L to "b2"), drained)
        // drain 本身不触发写入：写入由调用方决定（onCleared 里自己写库）。
        assertEquals(2, writes.size)
    }

    /** drain 之后再 drain 必须为空，避免重复写同一份正文。 */
    @Test
    fun `drainPending is idempotent after first call`() {
        val w = newWriter()
        w.submit(1L, "a")
        clock += 10
        w.submit(1L, "ab")

        assertEquals(mapOf(1L to "ab"), w.drainPending())
        assertTrue(w.drainPending().isEmpty())
    }

    /** 没有待写内容时返回空 map，不抛异常（onCleared 的常见路径）。 */
    @Test
    fun `drainPending on empty writer returns empty map`() {
        val w = newWriter()
        assertTrue(w.drainPending().isEmpty())
    }

    /** drain 之后 flush 不应再写出旧内容（内部状态已清）。 */
    @Test
    fun `flush after drain writes nothing more`() {
        val w = newWriter()
        w.submit(1L, "a")
        clock += 10
        w.submit(1L, "ab")
        w.drainPending()
        val before = writes.size
        w.flush(1L)
        assertEquals(before, writes.size)
    }
}
