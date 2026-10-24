package dev.praxis.ui.design.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpringFollower] 的行为锁定。
 *
 * 这些断言不是「凑覆盖率」，而是把从 pk-node 抄来的三个手感参数
 * 与两条钳位规则**固定住**：任何人把它们改成「看起来更顺」的值，
 * 这里会立刻失败，而不是等到真机上「滚动变得一冲一停」再靠肉眼排查。
 */
class SpringFollowerTest {

    /** 模拟从 0 追到 target，返回 (最终位置, 用了几帧)。 */
    private fun runToRest(target: Float, frames: Int = 600): Pair<Float, Int> {
        var x = 0f
        var v = 0f
        var used = 0
        repeat(frames) {
            val r = SpringFollower.step(x = x, v = v, target = target, dt = 1f / 60f)
            x = r.position
            v = r.velocity
            used++
            if (r.atRest) return x to used
        }
        return x to used
    }

    @Test
    fun `追踪到底并停下`() {
        val (finalX, _) = runToRest(target = 1000f)
        assertEquals(1000f, finalX, SpringFollower.SPRING_REST_X)
    }

    @Test
    fun `略过阻尼 不冲过目标`() {
        // c=24 略大于临界阻尼 2*sqrt(130*1)≈22.8，所以全程不应超过 target。
        // 若有人把 c 调小（比如照抄成常见的 12），这条会失败 —— 那正是
        // 「滚到底又弹回来」的来源。
        var x = 0f
        var v = 0f
        var maxSeen = 0f
        repeat(600) {
            val r = SpringFollower.step(x, v, target = 1000f, dt = 1f / 60f)
            x = r.position
            v = r.velocity
            if (x > maxSeen) maxSeen = x
            if (r.atRest) return@repeat
        }
        assertTrue("不应越过目标，实际最大 $maxSeen", maxSeen <= 1000f + 0.01f)
    }

    @Test
    fun `速度连续 不是一阶追赶`() {
        // 二阶弹簧的特征：第 2 帧就应产生非零速度并已经位移。
        // 若积分退化成「x += (target-x)*0.5」式一阶追赶，速度字段永远是 0。
        val first = SpringFollower.step(x = 0f, v = 0f, target = 1000f, dt = 1f / 60f)
        assertTrue("首帧速度应大于 0", first.velocity > 0f)
        assertTrue("首帧应已位移", first.position > 0f)
    }

    @Test
    fun `越过起点时速度归零`() {
        // 已经在 0 还带一个向左的速度：位置夹回 0，速度必须清零，
        // 否则下一帧会继续往外拉（贴顶抖动）。
        val r = SpringFollower.step(x = 0f, v = -500f, target = 100f, dt = 1f / 60f)
        assertEquals(0f, r.position, 0.001f)
        assertEquals(0f, r.velocity, 0.001f)
    }

    @Test
    fun `越过终点只夹位置 不清速度`() {
        // pk-node 原实现是 `if (s.x > target) { s.x = target }`，没有清 v。
        // 保留速度有意义：目标随即变大（新内容到达）时能平滑接上。
        val r = SpringFollower.step(x = 990f, v = 5000f, target = 1000f, dt = 1f / 60f)
        assertEquals(1000f, r.position, 0.001f)
        assertTrue("速度应保留为正", r.velocity > 0f)
    }

    @Test
    fun `目标变远时能继续追 不需重启`() {
        // 这一条锁的是「一次启动、永不重启」这个核心行为：
        // 追到一半把 target 改大，速度不为零、继续前进。
        var x = 0f
        var v = 0f
        repeat(10) {
            val r = SpringFollower.step(x, v, target = 100f, dt = 1f / 60f)
            x = r.position
            v = r.velocity
        }
        val midX = x
        assertTrue("应已前进", midX > 0f)

        val r = SpringFollower.step(x, v, target = 5000f, dt = 1f / 60f)
        assertTrue("目标变大后应继续前进", r.position > midX)
        assertFalse("不应立刻静止", r.atRest)
    }

    @Test
    fun `掉帧钳位常量在合理区间`() {
        // 32ms ≈ 30fps 下限。太大（如 1/10 秒）会让卡顿后一步跳很远。
        assertEquals(0.032f, SpringFollower.SPRING_MAX_DT, 1e-6f)
        assertEquals(130f, SpringFollower.SPRING_K, 1e-6f)
        assertEquals(24f, SpringFollower.SPRING_C, 1e-6f)
        assertEquals(1f, SpringFollower.SPRING_M, 1e-6f)
    }
}