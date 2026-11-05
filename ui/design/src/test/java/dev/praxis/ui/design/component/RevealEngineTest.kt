package dev.praxis.ui.design.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 揭示节奏引擎的数值契约。
 *
 * ## 为什么这些边界必须测
 *
 * 出字逻辑里有两个「静默失效」的陷阱：
 *
 *  1. 积压很小时 `speed × dt` 取整成 0 —— 文字**永远出不来**，
 *     表现为「回复卡住不动」，而界面上看不出任何异常；
 *  2. 积压很大时若不设上限 —— 一次性吐出几百字，等同于「没有揭示」。
 *
 * 两者都不会抛异常，只能靠断言钉住。
 */
class RevealEngineTest {

    /** 低负载：接近基础速度（90 字符/秒 × 32ms ≈ 3 字/帧）。 */
    @Test
    fun `低积压时接近基础速度`() {
        val chars = RevealEngine.charsThisFrame(backlog = 10, dt = 0.032f)
        // 90 × 0.032 = 2.88，加上积压项后取整应在 3~6 之间。
        assertTrue("低积压下每帧 $chars 字，应从容", chars in 3..6)
    }

    /**
     * 高积压：速度提升，但**必须受单帧绝对上限约束**。
     *
     * ## 这条测试抓到过一个真实缺陷
     *
     * 最初只有 `v = 90 + backlog^1.25 × 0.9`。backlog = 1000 时算出
     * 5151 字/秒 —— 32ms 一帧就是 **165 字**，一屏文字一两帧刷完。
     * 那已经不是「追赶」而是「倾泻」，揭示引擎等于没做。
     *
     * 修法是加 `MAX_CHARS_PER_FRAME` 绝对上限，而不是调小系数 ——
     * 调系数会让低积压时也变慢，那是两件不同的事。
     */
    @Test
    fun `高积压时加速但不倾泻`() {
        val backlog = 1000
        val chars = RevealEngine.charsThisFrame(backlog = backlog, dt = 0.032f)
        assertTrue("积压 1000 时每帧 $chars 字，应明显快于基础速度", chars > 6)
        assertTrue(
            "积压 1000 时一帧吐了 $chars 字，超过绝对上限",
            chars <= RevealEngine.MAX_CHARS_PER_FRAME,
        )
    }

    /** 积压持续增大时，每帧输出也不应无限增长（上限是硬的）。 */
    @Test
    fun `极大积压也只按上限输出`() {
        val huge = RevealEngine.charsThisFrame(backlog = 100_000, dt = 0.032f)
        assertEquals(RevealEngine.MAX_CHARS_PER_FRAME, huge)
    }

    /**
     * 积压为 1 — 2 时必须每帧至少出 1 字。
     *
     * 不设这个下限时 `90 × 0.032 ≈ 2.88`，取整得 3 —— 看起来没问题；
     * 但低帧率下 `dt` 被钳到 0.001 时只有 0.09 字，取整成 0，
     * 最后两个字就永远卡在那里。
     */
    @Test
    fun `极小积压也至少出一字`() {
        // ## 断言要按「下限」写，不能按「一次吐完」写
        //
        // 这里刻意用 dt = 0.001（1 毫秒）：按理算出来是 0.09 个字，取整得 0。
        // 引擎的下限把它抬到 1 —— 这正是要验证的行为：**永远不饿死**。
        //
        // 但不能断言「backlog 2 就吐 2」：dt 这么小时引擎只该吐 1，
        // 剩下的留在下一帧。一次吐 2 意味着单位时间效率被 dt 放大，
        // 那是错的（会在大 backlog + 小 dt 时突然爆字）。
        assertEquals(1, RevealEngine.charsThisFrame(backlog = 1, dt = 0.001f))
        assertEquals(1, RevealEngine.charsThisFrame(backlog = 2, dt = 0.001f))
        // 正常帧间隔下 backlog 2 会一次吐完（90 × 0.032 ≈ 2.9，取整 3，钳到 2）。
        assertEquals(2, RevealEngine.charsThisFrame(backlog = 2, dt = 0.032f))
    }

    @Test
    fun `无积压不出字`() {
        assertEquals(0, RevealEngine.charsThisFrame(backlog = 0, dt = 0.032f))
    }

    /** 暂停时本帧不动（低帧率 + 屏幕外）。 */
    @Test
    fun `暂停时不出字`() {
        assertEquals(0, RevealEngine.charsThisFrame(backlog = 500, dt = 0.032f, paused = true))
    }

    /** dt 必须被钳位：主线程卡 200ms 不能一次吐 0.2 秒的量。 */
    @Test
    fun `超大 dt 被钳位`() {
        val clamped = RevealEngine.charsThisFrame(backlog = 500, dt = 0.2f)
        val normal = RevealEngine.charsThisFrame(backlog = 500, dt = RevealEngine.MAX_DT)
        assertEquals("dt 未被钳位，卡顿后会出现大跳", normal, clamped)
    }

    /** 背压：滞后为 0 时满速，滞后达预留空间时降到最低倍速。 */
    @Test
    fun `背压随滞后线性下降`() {
        assertEquals(1f, RevealEngine.backpressure(lagPx = 0f, reservePx = 100f), 1e-4f)
        assertEquals(
            RevealEngine.MIN_BACKPRESSURE,
            RevealEngine.backpressure(lagPx = 100f, reservePx = 100f),
            1e-4f,
        )
        val mid = RevealEngine.backpressure(lagPx = 50f, reservePx = 100f)
        assertTrue("中位背压应在两者之间", mid > RevealEngine.MIN_BACKPRESSURE && mid < 1f)
    }

    /** 预留空间为 0（未测量）时不施加背压，否则会把揭示卡死。 */
    @Test
    fun `预留空间为零时不背压`() {
        assertEquals(1f, RevealEngine.backpressure(lagPx = 50f, reservePx = 0f), 1e-4f)
    }

    /**
     * 换行平滑：一次 28px 的跳跃必须拆成多帧。
     *
     * 这是「踢移感」的直接来源：行高 24~28px，不平滑时滚动跟着跳一格。
     */
    @Test
    fun `整数行的跳跃摊到多帧`() {
        var carry = 0f
        var frames = 0
        var total = 0f
        // 模拟一次 28px 的跳跃。
        var pending = 28f
        while (pending > 0f || carry > 0f) {
            val (out, next) = RevealEngine.smoothLineAdvance(pending, carry)
            pending = 0f
            carry = next
            total += out
            frames++
            if (frames > 10) break
        }
        assertEquals("总量必须守恒", 28f, total, 1e-3f)
        assertTrue("28px 应拆成多帧（实际 $frames 帧）", frames >= 4)
    }

    /** 小于单帧上限的位移直接放出，不引入延迟。 */
    @Test
    fun `小位移不平滑`() {
        val (out, carry) = RevealEngine.smoothLineAdvance(5f, 0f)
        assertEquals(5f, out, 1e-4f)
        assertEquals(0f, carry, 1e-4f)
    }

    /** 负向位移（内容变矮）必须瞬时生效，不平滑。 */
    @Test
    fun `负位移不平滑`() {
        val (out, carry) = RevealEngine.smoothLineAdvance(-20f, 0f)
        assertEquals(-20f, out, 1e-4f)
        assertEquals(0f, carry, 1e-4f)
    }

    /** 只有「低帧率 + 屏幕外」才暂停。低帧率但可见时不暂停。 */
    @Test
    fun `暂停仅在低帧率且不可见时`() {
        assertTrue(RevealEngine.shouldPause(fps = 20.0, bubbleVisible = false))
        assertFalse("可见时掉帧不该暂停", RevealEngine.shouldPause(fps = 20.0, bubbleVisible = true))
        assertFalse(RevealEngine.shouldPause(fps = 60.0, bubbleVisible = false))
    }

    /** 帧率估计要平滑：单帧抖动不该让判断翻转。 */
    @Test
    fun `帧率估计平滑抖动`() {
        var fps = 0.0
        repeat(10) { fps = RevealEngine.estimateFps(fps, 1f / 60f) }
        assertEquals(60.0, fps, 1.0)
        // 一帧突然掉到 10fps，估计值只应轻微下降而不是直接翻到 10。
        val dipped = RevealEngine.estimateFps(fps, 0.1f)
        assertTrue("单帧抖动导致估计值暴跌到 $dipped", dipped > 40.0)
    }
}
