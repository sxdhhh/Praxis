package dev.praxis.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「屏幕被写到多深」与「行版本号」的行为锁。
 *
 * ## 这两个量各自修掉一个真机缺陷
 *
 * | 量 | 修掉的缺陷 | 不用它会怎样 |
 * |---|---|---|
 * | [TerminalBuffer.deepestUsedRow] | 无限向下滚 / 空白也能滚 | 固定 200 行全交给 UI |
 * | [TerminalBuffer.rowVersion] | 打字卡顿（每键重建 16000 个对象） | 无法判断哪行能复用 |
 *
 * 两者都是**纯逻辑**，能用单测完整覆盖 —— 而它们一旦回归，症状是
 * 「滚动又坏了」「打字又卡了」，在真机上要靠手感判断，很难定位。
 */
class TerminalBufferReuseTest {

    // ── deepestUsedRow：内容高度 ────────────────────────────────

    @Test
    fun `新建缓冲的内容高度是第 0 行`() {
        // 空缓冲也要有 1 行：光标停在那里。返回 0 会让 UI 算出 0 行，
        // 列表高度为 0，光标无处可去。
        val buffer = TerminalBuffer(columns = 10, rows = 5)
        assertEquals(0, buffer.deepestUsedRow)
    }

    @Test
    fun `写入字符不改变行深度`() {
        // 同一行内写多字符，深度仍是 0。若这里涨了，
        // 「行数」会跟着字符数涨，列表高度会抖动。
        val buffer = TerminalBuffer(columns = 10, rows = 5)
        "hello".forEach { buffer.putChar(it) }
        assertEquals(0, buffer.deepestUsedRow)
    }

    @Test
    fun `换行推进内容高度`() {
        // 这是「内容到第几行」的主路径：`ls` 之类的输出全靠 LF 推进。
        val buffer = TerminalBuffer(columns = 10, rows = 5)
        "a".forEach { buffer.putChar(it) }
        buffer.lineFeed()
        buffer.lineFeed()
        assertEquals(2, buffer.deepestUsedRow)
    }

    @Test
    fun `光标回跳不让内容高度回退`() {
        // ## 这条锁的是「按光标裁剪」的致命缺陷
        //
        // ANSI 允许 `ESC[1;1H` 把光标拉回首行（进度条重绘、`top`、`vim`）。
        // 若 UI 用 `cursorRow` 当内容高度，那一刻会把下面写好的内容整段裁掉 ——
        // 用户看到屏幕突然只剩第一行。
        val buffer = TerminalBuffer(columns = 10, rows = 5)
        repeat(3) {
            buffer.putChar('x')
            buffer.lineFeed()
        }
        val before = buffer.deepestUsedRow
        buffer.moveTo(1, 1)
        assertEquals("光标回跳后内容高度不能变", before, buffer.deepestUsedRow)
        assertEquals("光标本身确实回到首行", 0, buffer.cursorRow)
    }

    @Test
    fun `清屏把内容高度归零`() {
        // `clear` 走 `eraseInDisplay(2)`。不归零的话清屏后仍留一大片空白，
        // 正是用户报的「清屏了但还是能往下滚」。
        val buffer = TerminalBuffer(columns = 10, rows = 5)
        repeat(4) {
            buffer.putChar('y')
            buffer.lineFeed()
        }
        assertTrue(buffer.deepestUsedRow > 0)

        buffer.eraseInDisplay(2)
        assertEquals(0, buffer.deepestUsedRow)
    }

    @Test
    fun `光标以下清空后内容高度收缩到光标行`() {
        // `eraseInDisplay(0)`：从光标清到末尾。下面的行没了，高度必须跟着降，
        // 否则列表底部留着一片点不到的空白。
        val buffer = TerminalBuffer(columns = 10, rows = 6)
        repeat(5) {
            buffer.putChar('z')
            buffer.lineFeed()
        }
        buffer.moveTo(2, 1)
        buffer.eraseInDisplay(0)
        assertEquals("收缩到光标行（2 行 1 基 → 索引 1）", 1, buffer.deepestUsedRow)
    }

    @Test
    fun `内容高度不超过 rows`() {
        // 折行 + 换行写满整屏后继续写：靠 scrollUp 消耗，深度必须停在 rows-1。
        // 超出会让 UI 取行时越界（虽然有 coerce，但那是兜底不是设计）。
        val buffer = TerminalBuffer(columns = 4, rows = 3)
        repeat(20) {
            buffer.putChar('a')
            buffer.lineFeed()
        }
        assertEquals(2, buffer.deepestUsedRow)
    }

    // ── rowVersion：能否复用快照 ────────────────────────────────

    @Test
    fun `写入字符推进该行版本`() {
        val buffer = TerminalBuffer(columns = 10, rows = 5)
        val before = buffer.rowVersion(0)
        buffer.putChar('a')
        assertNotEquals("写过就必须推进版本，否则 UI 复用旧快照", before, buffer.rowVersion(0))
    }

    @Test
    fun `只改一行不影响其它行的版本`() {
        // ## 这条是「打字卡顿」修法的核心
        //
        // 只有真正被改写的那一行需要重建。其余行版本不变 → 复用对象 →
        // `remember` 直接命中，不重跑 `buildAnnotatedLine`。
        val buffer = TerminalBuffer(columns = 10, rows = 5)
        // 先让 0/1 两行都有内容，再单独改第 1 行。
        "a".forEach { buffer.putChar(it) }
        buffer.lineFeed()
        "b".forEach { buffer.putChar(it) }

        val v0 = buffer.rowVersion(0)
        val v2 = buffer.rowVersion(2)
        buffer.putChar('c')

        assertEquals("第 0 行没被写，版本不能变", v0, buffer.rowVersion(0))
        assertEquals("第 2 行没被写，版本不能变", v2, buffer.rowVersion(2))
    }

    @Test
    fun `同一行连续写两次版本持续递增`() {
        // 用 `= 1` 而不是 `++` 的实现在这里会失败：进度条反复重绘同一行时，
        // 版本停在 1 就会让 UI 认为「没变化」，画面卡在第一次的形态。
        val buffer = TerminalBuffer(columns = 10, rows = 3)
        val v0 = buffer.rowVersion(0)
        buffer.putChar('a')
        val v1 = buffer.rowVersion(0)
        buffer.putChar('b')
        val v2 = buffer.rowVersion(0)

        assertTrue("版本必须单调递增", v1 > v0)
        assertTrue("第二次写入也要推进", v2 > v1)
    }

    @Test
    fun `滚屏推进所有行的版本`() {
        // ## 为什么这条必须成立
        //
        // 滚屏后每一格的内容都换了行号。若版本不推进，UI 会继续复用
        // 「上滚前那一刻」的快照 —— 画面表现为「文字停在原位不动，只有光标在走」。
        val buffer = TerminalBuffer(columns = 4, rows = 3)
        val before = (0 until 3).map { buffer.rowVersion(it) }

        buffer.scrollUp(1)

        (0 until 3).forEach { row ->
            assertNotEquals(
                "滚屏后第 $row 行内容变了，版本必须推进",
                before[row],
                buffer.rowVersion(row),
            )
        }
    }

    @Test
    fun `清行推进该行版本`() {
        val buffer = TerminalBuffer(columns = 10, rows = 3)
        buffer.putChar('a')
        val before = buffer.rowVersion(0)
        buffer.eraseInDisplay(2)
        assertNotEquals("清屏后这一行从有字变空白，版本必须推进", before, buffer.rowVersion(0))
    }

    @Test
    fun `越界行号取版本不崩溃`() {
        // UI 在「行数刚变少」的一帧里可能用旧下标取版本。
        // 越界时给 0（永不等于任何真实版本）比抛异常安全：
        // 那次会退化成「重建这一行」，结果正确。
        val buffer = TerminalBuffer(columns = 10, rows = 3)
        assertEquals(0, buffer.rowVersion(-1))
        assertEquals(0, buffer.rowVersion(3))
        assertEquals(0, buffer.rowVersion(999))
    }
}