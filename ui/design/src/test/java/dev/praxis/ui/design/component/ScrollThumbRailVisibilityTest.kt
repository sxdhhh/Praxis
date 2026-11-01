package dev.praxis.ui.design.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⑥ 聊天页回底按钮：显示判定的两个纯函数。
 *
 * ## 为什么必须测（2026-11-02）
 *
 * 用户拿标注截图指出「右侧竖直进度条要去掉、回底按钮要上移」。
 * 位置改好之后，我复查显示逻辑时发现一个更严重的问题：
 *
 * ```
 * visible = showWhenIdle || showButton     // showWhenIdle 默认 true
 * ```
 *
 * `||` 让 `visible` **恒为 true** —— 按钮永远显示，`isAtBottom` 算得再准也被短路。
 * 症状是「已经滚到底了按钮还亮着，按下去什么都不会发生」。用户不会觉得那是
 * 多余的按钮，只会觉得「这按钮点了没反应」。
 *
 * 把判定抽成纯函数之后，这类错误可以被测试拦住。
 */
class ScrollThumbRailVisibilityTest {

    // ── isAtBottom ────────────────────────────────────────────────

    /** 无可见项（空列表 / 还没测量）按「到底」处理。 */
    @Test
    fun `没有可见项时算到底`() {
        assertTrue(isAtBottom(lastIndex = null, lastBottomEdge = null, totalItemsCount = 0, contentEnd = 1000))
    }

    /** 尾项没露出来 -> 未到底。 */
    @Test
    fun `尾项未出现时不算到底`() {
        assertFalse(isAtBottom(lastIndex = 3, lastBottomEdge = 900, totalItemsCount = 10, contentEnd = 1000))
    }

    /**
     * 尾项**只露出一点点**也算未到底。
     *
     * 这是本函数存在的核心理由：`lastVisibleIndex == total - 1` 在末项只露 1px
     * 时同样成立，那时用户其实还在半空。必须比底边位置。
     */
    @Test
    fun `尾项只露一点时不算到底`() {
        // 末项 offset=990、size=200 -> 底边 1190，内容区末端 1000，远未贴底。
        assertFalse(isAtBottom(lastIndex = 9, lastBottomEdge = 1190, totalItemsCount = 10, contentEnd = 1000))
    }

    /** 尾项底边贴住内容区末端 -> 到底。 */
    @Test
    fun `尾项贴底时算到底`() {
        assertTrue(isAtBottom(lastIndex = 9, lastBottomEdge = 1000, totalItemsCount = 10, contentEnd = 1000))
    }

    /**
     * +1 容差：取整误差让「刚好贴底」差 1px 时仍算到底。
     *
     * 少了这 1px 容忍，真正到底时按钮会一直亮着。
     */
    @Test
    fun `贴底差一像素仍算到底`() {
        assertTrue(isAtBottom(lastIndex = 9, lastBottomEdge = 1001, totalItemsCount = 10, contentEnd = 1000))
    }

    /** 差两像素就不算到底了（容差不能无限放大）。 */
    @Test
    fun `贴底差两像素不算到底`() {
        assertFalse(isAtBottom(lastIndex = 9, lastBottomEdge = 1002, totalItemsCount = 10, contentEnd = 1000))
    }

    /**
     * 内容不足一屏：只有一项且贴底 -> 到底（不显示按钮）。
     *
     * 初始只有一张「未完成配置」卡片时就是这种情形，此时不该有回底按钮。
     */
    @Test
    fun `内容不足一屏时算到底`() {
        assertTrue(isAtBottom(lastIndex = 0, lastBottomEdge = 300, totalItemsCount = 1, contentEnd = 1000))
    }

    // ── shouldShowScrollToBottom ─────────────────────────────────

    /**
     * **这条是本次修复的核心**：到底时不显示。
     *
     * 旧实现 `showWhenIdle || !atBottom` 在 showWhenIdle=true（默认值）时
     * 恒为 true —— 按钮永远亮着。这条断言会直接变红。
     */
    @Test
    fun `到底时不显示`() {
        assertFalse(
            "到底了还显示按钮，用户按下去什么都不会发生 —— 那就是「点了没反应」",
            shouldShowScrollToBottom(atBottom = true),
        )
    }

    /** 未到底时显示（那时按下去真的会滚到底，是有用的）。 */
    @Test
    fun `未到底时显示`() {
        assertTrue(shouldShowScrollToBottom(atBottom = false))
    }
}
