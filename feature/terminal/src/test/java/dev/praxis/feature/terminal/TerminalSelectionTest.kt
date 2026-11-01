package dev.praxis.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TerminalSelection] 的几何计算。
 *
 * 这些用例覆盖的都是**真机上会出现的形态**，不是构造的边界：
 * 反向拖选、单行内选择、跨行选择、宽字符起选。
 */
class TerminalSelectionTest {

    @Test
    fun \`正向拖选时 normalized 保持原样\`() {
        val s = TerminalSelection(anchorLine = 2, anchorCol = 3, focusLine = 5, focusCol = 7)
        val n = s.normalized
        assertEquals(2, n.startLine)
        assertEquals(3, n.startCol)
        assertEquals(5, n.endLine)
        assertEquals(7, n.endCol)
    }

    @Test
    fun \`反向拖选时 normalized 会把起点换到前面\`() {
        // 用户从下往上拖：anchor 在下面，focus 在上面。
        val s = TerminalSelection(anchorLine = 5, anchorCol = 7, focusLine = 2, focusCol = 3)
        val n = s.normalized
        assertEquals(2, n.startLine)
        assertEquals(3, n.startCol)
        assertEquals(5, n.endLine)
        assertEquals(7, n.endCol)
    }

    @Test
    fun \`同一行内反向拖也正确\`() {
        val s = TerminalSelection(anchorLine = 4, anchorCol = 9, focusLine = 4, focusCol = 2)
        val n = s.normalized
        assertEquals(4, n.startLine)
        assertEquals(2, n.startCol)
        assertEquals(4, n.endLine)
        assertEquals(9, n.endCol)
    }

    @Test
    fun \`单点未拖动时 isEmpty 为真\`() {
        assertTrue(TerminalSelection(3, 3, 3, 3).isEmpty)
        assertFalse(TerminalSelection(3, 3, 3, 4).isEmpty)
        assertFalse(TerminalSelection(3, 3, 4, 3).isEmpty)
    }

    @Test
    fun \`单行选区只覆盖那一行的区间\`() {
        val n = TerminalSelection(4, 2, 4, 5).normalized
        assertEquals(2 until 6, n.columnRangeOn(4))
        assertNull(n.columnRangeOn(3))
        assertNull(n.columnRangeOn(5))
    }

    @Test
    fun \`跨行选区：首行从起点列开始、中间整行、末行到终点列\`() {
        val n = TerminalSelection(2, 3, 5, 7).normalized

        // 首行：从第 3 列到行尾
        val first = n.columnRangeOn(2)!!
        assertEquals(3, first.first)
        assertEquals(Int.MAX_VALUE - 1, first.last)

        // 中间行：整行
        val mid = n.columnRangeOn(3)!!
        assertEquals(0, mid.first)

        // 末行：从第 0 列到第 7 列（含）
        val last = n.columnRangeOn(5)!!
        assertEquals(0, last.first)
        assertEquals(7, last.last)
    }

    @Test
    fun \`withFocus 只改焦点端不动锚点\`() {
        val s = TerminalSelection(2, 3, 2, 3)
        val moved = s.withFocus(line = 6, col = 1)
        assertEquals(2, moved.anchorLine)
        assertEquals(3, moved.anchorCol)
        assertEquals(6, moved.focusLine)
        assertEquals(1, moved.focusCol)
    }

    @Test
    fun \`宽字符只要与区间有交叠就算选中\`() {
        // 一个宽字符占第 4、5 两格；选区只覆盖到第 4 格时，它也算选中。
        val range = 4..4
        assertTrue(isCellSelected(range, col = 4, charLen = 2))
        // 选区从第 5 格开始（落在宽字符的右半格）也应算选中。
        assertTrue(isCellSelected(5..8, col = 4, charLen = 2))
        // 完全在左侧则不选中。
        assertFalse(isCellSelected(0..3, col = 4, charLen = 2))
    }

    @Test
    fun \`普通字符按单格判定\`() {
        assertTrue(isCellSelected(3..6, col = 3, charLen = 1))
        assertTrue(isCellSelected(3..6, col = 6, charLen = 1))
        assertFalse(isCellSelected(3..6, col = 2, charLen = 1))
        assertFalse(isCellSelected(3..6, col = 7, charLen = 1))
    }

    @Test
    fun \`range 为 null 时一律不选中\`() {
        assertFalse(isCellSelected(null, col = 0, charLen = 1))
    }
}
