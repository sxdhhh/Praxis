package dev.praxis.ui.design.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动跟随滚动的**判据**测试（对应缺陷第 10 项）。
 *
 * ## 为什么这两个函数值得单独测
 *
 * 用户报的是「丝滑滚动不是切页的时候自动滚到底部，而是有新内容输出的时候才滚动」。
 * 这类问题**极难靠真机肉眼复现** —— 要在切页那一瞬间正好看着列表，
 * 而且「它滚了一下」与「它本来就在底部」在观感上很容易混。
 *
 * 把判据抽成纯函数之后，行为就被这段话固定住了：
 *  - 切页（条目数没变多）→ 绝不滚；
 *  - 有新内容（条目数变多）→ 在跟随状态下才滚；
 *  - 未测量 → 绝不滚（旧版把「未测量」当「到底」，是误滚的根因）。
 */
class AutoFollowScrollTest {

    // ── shouldFollowScroll ────────────────────────────────────────

    @Test
    fun `切换页面时条目数没变多 不滚动`() {
        // 旧实现会在这里滚到底。切页/切 tab/重组都走这条路径。
        assertFalse(
            shouldFollowScroll(
                previousCount = 20,
                currentCount = 20,
                measured = true,
                autoFollow = true,
                initialAutoFollow = true,
            ),
        )
    }

    @Test
    fun `条目数变少也不滚动`() {
        // 删除消息、清屏、会话切换（新会话更短）都会走到这里。
        assertFalse(
            shouldFollowScroll(
                previousCount = 50,
                currentCount = 3,
                measured = true,
                autoFollow = true,
                initialAutoFollow = true,
            ),
        )
    }

    @Test
    fun `有新内容且处于跟随时滚动`() {
        assertTrue(
            shouldFollowScroll(
                previousCount = 10,
                currentCount = 11,
                measured = true,
                autoFollow = true,
                initialAutoFollow = true,
            ),
        )
    }

    @Test
    fun `有新内容但用户已上滑关闭跟随时不滚动`() {
        // 用户正在翻历史，不能被新内容拽下去。
        assertFalse(
            shouldFollowScroll(
                previousCount = 10,
                currentCount = 11,
                measured = true,
                autoFollow = false,
                initialAutoFollow = true,
            ),
        )
    }

    @Test
    fun `未测量时不滚动 即使条目数变多`() {
        // 布局还没稳定时滚动会滚到不存在的下标上。
        assertFalse(
            shouldFollowScroll(
                previousCount = 0,
                currentCount = 30,
                measured = false,
                autoFollow = true,
                initialAutoFollow = true,
            ),
        )
    }

    @Test
    fun `首次到达由 initialAutoFollow 决定`() {
        // 聊天/日志：true → 打开就贴最新。
        assertTrue(
            shouldFollowScroll(
                previousCount = -1,
                currentCount = 42,
                measured = true,
                autoFollow = false,   // 首次时开关还没建立，不应影响判定
                initialAutoFollow = true,
            ),
        )
        // 设置页/会话列表：false → 打开停在顶部。
        assertFalse(
            shouldFollowScroll(
                previousCount = -1,
                currentCount = 42,
                measured = true,
                autoFollow = true,
                initialAutoFollow = false,
            ),
        )
    }

    @Test
    fun `条目数为零时不滚动`() {
        assertFalse(
            shouldFollowScroll(
                previousCount = -1,
                currentCount = 0,
                measured = true,
                autoFollow = true,
                initialAutoFollow = true,
            ),
        )
    }

    // ── isAtBottom ───────────────────────────────────────────────

    @Test
    fun `未测量时绝不算到底`() {
        // ★ 这是旧实现误滚的直接原因：last == null 被当成「到底」。
        assertFalse(isAtBottom(ready = false, lastVisibleIndex = -1, totalItems = 0))
        // 即使 totalItems 已经有值，只要「不可判断」就不能算到底。
        assertFalse(isAtBottom(ready = false, lastVisibleIndex = 5, totalItems = 10))
    }

    @Test
    fun `看到最后一项才算到底`() {
        assertTrue(isAtBottom(ready = true, lastVisibleIndex = 9, totalItems = 10))
    }

    @Test
    fun `没看到最后一项不算到底`() {
        assertFalse(isAtBottom(ready = true, lastVisibleIndex = 3, totalItems = 10))
    }

    @Test
    fun `单条目列表视为到底`() {
        assertTrue(isAtBottom(ready = true, lastVisibleIndex = 0, totalItems = 1))
    }

    @Test
    fun `totalItems 为 0 时不算到底`() {
        assertFalse(isAtBottom(ready = true, lastVisibleIndex = 0, totalItems = 0))
    }

    // ── unknownRemainingPx（2026-10-29：止抽搐）───────────────

    /**
     * 末项未进视口时，剩余量**不得超过一屏**。
     *
     * 旧实现返回整屏（≈2000px），弹簧的 a ∝ target 会被顶到 260000 px/s²，
     * 一帧就把速度干到 4000px/s；末项入视口后刹不住 → 冲过头再顿一下，
     * 这就是用户说的「时而抽搐时而正常」。
     */
    @Test
    fun `未知剩余量 不超过一屏`() {
        val px = unknownRemainingPx(
            lastItemSize = 4000,   // 夸张地大（工具卡可能真有这么高）
            afterContentPadding = 0,
            viewportHeight = 2000,
        )
        assertTrue("不应超过视口高度，实际 $px", px <= 2000)
    }

    /** 剩余量至少推进一项，否则末项永远进不了视口（会死循环）。 */
    @Test
    fun `未知剩余量 至少一项高度`() {
        val px = unknownRemainingPx(
            lastItemSize = 120,
            afterContentPadding = 8,
            viewportHeight = 2000,
        )
        assertEquals(128, px)
    }

    /** 条目高度为 0（未测量完）时不能返回 0。 */
    @Test
    fun `未知剩余量 零高度时仍为正`() {
        assertTrue(
            unknownRemainingPx(lastItemSize = 0, afterContentPadding = 0, viewportHeight = 0) > 0,
        )
    }

    // ── nextAutoFollow（2026-10-29）─────────────────────────

    /**
     * 到底了就必须恢复跟随 —— 即使此刻 isScrollInProgress 为 true。
     *
     * 这对应真机上的一个具体场景：用户手动滑到底，回弹动画还没结束。
     * 若先判 inProgress，会被当成「离开底部」→ 之后的新内容不跟随。
     */
    @Test
    fun `到底优先 即便正在滚动 `() {
        assertTrue(
            nextAutoFollow(atBottom = true, inProgress = true, selfScroll = false, current = false),
        )
    }

    /** 用户自己拖离底部 → 关跟随。 */
    @Test
    fun `用户拖离底部 关闭跟随`() {
        assertFalse(
            nextAutoFollow(atBottom = false, inProgress = true, selfScroll = false, current = true),
        )
    }

    /** 我们自己的滚动不能关跟随（否则「滚两下就不跟了」）。 */
    @Test
    fun `自身滚动 不改跟随`() {
        assertTrue(
            nextAutoFollow(atBottom = false, inProgress = true, selfScroll = true, current = true),
        )
    }

    /** 静止且不在底部：保持原状（不要因为一次探测就把跟随掀了）。 */
    @Test
    fun `静止时保持原状态`() {
        assertTrue(
            nextAutoFollow(atBottom = false, inProgress = false, selfScroll = false, current = true),
        )
    }
}
