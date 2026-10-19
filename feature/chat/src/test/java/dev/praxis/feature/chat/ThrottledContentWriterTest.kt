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
}
