package dev.praxis.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端解析的行为锁。
 *
 * 重点钉住三件容易回归的事：
 *  1. 跨分片的转义序列不能漏到屏幕上（终端输出天然会任意切分）；
 *  2. 多参数 SGR 必须全部生效（只认第一个是最常见的 bug）；
 *  3. 宽字符占两格且不跨行拆开。
 */
class AnsiScannerTest {

    private val esc = 27.toChar()

    private fun text(events: List<AnsiEvent>): String =
        events.filterIsInstance<AnsiEvent.Text>().joinToString("") { it.text }

    @Test
    fun `plain text becomes one text event`() {
        val events = AnsiScanner().feed("hello")

        assertEquals(1, events.size)
        assertEquals("hello", (events.single() as AnsiEvent.Text).text)
    }

    @Test
    fun `sgr is parsed into params`() {
        val events = AnsiScanner().feed(esc + "[1;31m")
        val csi = events.single() as AnsiEvent.Csi

        assertEquals(listOf(1, 31), csi.params)
        assertEquals('m', csi.finalChar)
        assertEquals(null, csi.privateMarker)
    }

    @Test
    fun `private marker is captured`() {
        val csi = AnsiScanner().feed(esc + "[?25l").single() as AnsiEvent.Csi

        assertEquals('?', csi.privateMarker)
        assertEquals(listOf(25), csi.params)
        assertEquals('l', csi.finalChar)
    }

    @Test
    fun `sequence split across chunks is not leaked as text`() {
        val scanner = AnsiScanner()

        // 这是最关键的一条：前半截不能当正文吐出去。
        assertTrue(scanner.feed(esc + "[3").isEmpty())
        val events = scanner.feed("1m")

        assertEquals("", text(events))
        assertEquals(listOf(31), (events.single() as AnsiEvent.Csi).params)
    }

    @Test
    fun `control characters are reported separately`() {
        val events = AnsiScanner().feed("a" + 13.toChar() + 10.toChar() + "b")

        assertTrue(events.any { it is AnsiEvent.Control && it.code.code == 0x0D })
        assertTrue(events.any { it is AnsiEvent.Control && it.code.code == 0x0A })
        assertEquals("ab", text(events))
    }

    @Test
    fun `osc terminated by bel`() {
        val osc = AnsiScanner().feed(esc + "]0;title" + 7.toChar()).single() as AnsiEvent.Osc

        assertEquals("0;title", osc.payload)
    }

    @Test
    fun `unknown escape becomes esc event`() {
        val event = AnsiScanner().feed(esc + "(B").first()

        assertTrue(event is AnsiEvent.Esc)
    }

    @Test
    fun `reset drops pending`() {
        val scanner = AnsiScanner()
        scanner.feed(esc + "[1")
        scanner.reset()

        // 重置后不应把上次的残片拼进来。
        assertEquals("x", text(scanner.feed("x")))
    }
}

/** 缓冲层：光标、换行、滚动、宽度。 */
class TerminalBufferTest {

    private fun rowText(buffer: TerminalBuffer, row: Int): String =
        buffer.line(row).joinToString("") { it.char.toString() }.trimEnd()

    @Test
    fun `println advances lines`() {
        val buffer = TerminalBuffer(columns = 10, rows = 3)

        buffer.putChar('a')
        buffer.carriageReturn()
        buffer.lineFeed()
        buffer.putChar('b')

        assertEquals("a", rowText(buffer, 0))
        assertEquals("b", rowText(buffer, 1))
    }

    @Test
    fun `scroll at bottom pushes lines into scrollback`() {
        val buffer = TerminalBuffer(columns = 10, rows = 2)

        // 写三行到只有两行的屏幕：第一行应该被挤进历史。
        "abc".forEach { buffer.putChar(it) }
        buffer.carriageReturn(); buffer.lineFeed()
        "def".forEach { buffer.putChar(it) }
        buffer.carriageReturn(); buffer.lineFeed()
        "ghi".forEach { buffer.putChar(it) }

        assertEquals(1, buffer.scrollbackSize())
        assertEquals("def", rowText(buffer, 0))
        assertEquals("ghi", rowText(buffer, 1))
    }

    @Test
    fun `cjk occupies two cells`() {
        val buffer = TerminalBuffer(columns = 10, rows = 1)

        buffer.putChar('中')   // 中

        assertEquals(2, buffer.cursorColumn)
        assertTrue(buffer.isContinuation(0, 1))
        assertFalse(buffer.isContinuation(0, 0))
    }

    @Test
    fun `wide char wraps instead of splitting`() {
        val buffer = TerminalBuffer(columns = 3, rows = 2)

        buffer.putChar('a')
        buffer.putChar('b')
        // 只剩 1 格，宽字符应该整字换行而不是拆成两行。
        buffer.putChar('中')

        // 整字换到第 2 行，且光标落在字后（宽字符占 2 格），不是第 0 列。
        assertEquals(1, buffer.cursorRow)
        assertEquals(2, buffer.cursorColumn)
        assertEquals('中', buffer.line(1)[0].char)
        assertEquals('a', buffer.line(0)[0].char)
        assertEquals('b', buffer.line(0)[1].char)
    }

    @Test
    fun `char width covers common cjk ranges`() {
        assertEquals(2, TerminalBuffer.charWidth('中'))
        assertEquals(2, TerminalBuffer.charWidth('あ'))  // あ
        assertEquals(2, TerminalBuffer.charWidth('Ａ'))  // Ａ
        assertEquals(1, TerminalBuffer.charWidth('a'))
        assertEquals(0, TerminalBuffer.charWidth(27.toChar()))
    }

    // ---- 以下三条针对折行缺陷（R19）----

    @Test
    fun `char after full line is not lost`() {
        // 曾经：满行后 cursorColumn 被夹到 columns-1，下一个字符覆盖同一格。
        // 写 "abc" 到 3 列屏再写 X → 得到 "abX"，c 被吃掉。
        val buffer = TerminalBuffer(columns = 3, rows = 3)

        "abc".forEach { buffer.putChar(it) }
        // 此时光标仍在最后一列、尚未换行（延迟折行）。
        assertEquals(0, buffer.cursorRow)

        buffer.putChar('X')

        // 关键：abc 必须完整保留，X 落到下一行。
        assertEquals("abc", rowText(buffer, 0))
        assertEquals("X", rowText(buffer, 1))
    }

    @Test
    fun `explicit newline after full line does not double wrap`() {
        // pendingWrap 必须被显式换行消费掉，否则下一个字符会多空一行。
        val buffer = TerminalBuffer(columns = 3, rows = 4)

        "abc".forEach { buffer.putChar(it) }
        buffer.lineFeed()          // 显式换行
        buffer.carriageReturn()
        buffer.putChar('z')

        assertEquals("z", rowText(buffer, 1))
    }

    @Test
    fun `wide char on single column screen is dropped without crash`() {
        // 屏宽 1 时宽字符永远放不下；折行后仍是第 0 列，会写越界索引。
        val buffer = TerminalBuffer(columns = 1, rows = 2)

        buffer.putChar('中')

        // 不崩、不越界，光标停在合法位置。
        assertEquals(0, buffer.cursorRow)
        assertEquals(0, buffer.cursorColumn)
        assertEquals(false, buffer.isContinuation(0, 0))
    }

    @Test
    fun `erase in display clears everything`() {
        val buffer = TerminalBuffer(columns = 5, rows = 2)
        "hello".forEach { buffer.putChar(it) }

        buffer.eraseInDisplay(2)

        assertEquals("", rowText(buffer, 0))
        assertEquals("", rowText(buffer, 1))
    }
}

/** 会话层：SGR 应用与指令分发。 */
class TerminalSessionTest {

    private val esc = 27.toChar()

    @Test
    fun `sgr sets foreground color`() {
        val session = TerminalSession(columns = 10, rows = 2)

        session.feed(esc + "[31mX")

        val cell = session.buffer.line(0).first { !it.isBlank }
        assertEquals('X', cell.char)
        assertEquals(1, cell.style.foreground)
    }

    @Test
    fun `sgr with multiple params applies all`() {
        val session = TerminalSession(columns = 10, rows = 2)

        session.feed(esc + "[1;31;42mX")

        val cell = session.buffer.line(0).first { !it.isBlank }
        assertTrue(cell.style.bold)
        assertEquals(1, cell.style.foreground)
        assertEquals(2, cell.style.background)
    }

    @Test
    fun `sgr reset clears style`() {
        val session = TerminalSession(columns = 10, rows = 2)
        session.feed(esc + "[1;31m")
        session.feed(esc + "[0mX")

        val cell = session.buffer.line(0).first { !it.isBlank }
        assertFalse(cell.style.bold)
        assertEquals(CellStyle.DEFAULT_COLOR, cell.style.foreground)
    }

    @Test
    fun `256 color index is stored`() {
        val session = TerminalSession(columns = 10, rows = 2)

        session.feed(esc + "[38;5;123mX")

        val cell = session.buffer.line(0).first { !it.isBlank }
        assertEquals(123, cell.style.foreground)
    }

    @Test
    fun `true color is flagged`() {
        val session = TerminalSession(columns = 10, rows = 2)

        session.feed(esc + "[38;2;10;20;30mX")

        val cell = session.buffer.line(0).first { !it.isBlank }
        val expected = TerminalSession.TRUE_COLOR_FLAG or (10 shl 16) or (20 shl 8) or 30
        assertEquals(expected, cell.style.foreground)
    }

    @Test
    fun `cursor position is applied`() {
        val session = TerminalSession(columns = 10, rows = 3)

        session.feed(esc + "[2;3HX")

        assertEquals('X', session.buffer.line(1)[2].char)
    }

    @Test
    fun `erase line clears to end`() {
        val session = TerminalSession(columns = 6, rows = 1)
        session.feed("abcdef")
        session.feed(esc + "[3G")     // 光标到第 3 列
        session.feed(esc + "[0K")     // 从光标清到行尾

        val text = session.buffer.line(0).joinToString("") { it.char.toString() }
        assertEquals("ab    ", text)
    }

    @Test
    fun `split sequence through session renders cleanly`() {
        val session = TerminalSession(columns = 10, rows = 2)

        session.feed("ab" + esc + "[3")
        session.feed("1mcd")

        val text = session.buffer.line(0).joinToString("") { it.char.toString() }.trimEnd()
        // 屏幕上必须只有 abcd，不能把 ESC[31m 的碎片画出来。
        assertEquals("abcd", text)
    }

    @Test
    fun `session reset clears screen`() {
        val session = TerminalSession(columns = 5, rows = 2)
        session.feed("hello")

        session.reset()

        val text = session.buffer.line(0).joinToString("") { it.char.toString() }
        assertEquals("     ", text)
    }
}
