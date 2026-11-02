package dev.praxis.feature.terminal

import androidx.compose.ui.graphics.Color
import dev.praxis.core.terminal.CellStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildAnnotatedScreen] 的单测。
 *
 * ## 为什么补这一组（2026-11-03）
 *
 * P1 把输出区从「每行一个 LazyColumn item」改成「整屏一条 Text + SelectionContainer」，
 * 随之删掉了自研选择模型和它的 11 条单测。
 * `buildAnnotatedScreen` 是这个改动的核心（屏幕内容全靠它拼成一条文本），
 * 却一条测试都没有 —— 相当于改动最集中的地方覆盖反而比改之前更低。
 *
 * 这些测试全部只依赖纯数据结构（`TerminalLine` / `RenderCell` / `CellStyle`），
 * 不碰 Compose 运行时、不碰 PTY，因此在**任何机器上都能跑**，
 * 不依赖真机或 arm64 环境。
 */
class TerminalAnnotatedScreenTest {

    private val fg = Color.White

    /** 造一行：字符串展开成默认样式的格子。 */
    private fun line(text: String): TerminalLine =
        TerminalLine(text.map { RenderCell(it, CellStyle.Default, isContinuation = false) })

    @Test
    fun joinsLinesWithNewline() {
        val out = buildAnnotatedScreen(listOf(line("abc"), line("def")), fg)
        assertEquals("abc\ndef", out.text)
    }

    @Test
    fun trimsTrailingWhitespacePerLine() {
        // 终端每行都撑满列宽，不裁的话「复制」会带一长串空格。
        val out = buildAnnotatedScreen(listOf(line("abc         "), line("de    ")), fg)
        assertEquals("abc\nde", out.text)
    }

    @Test
    fun keepsBlankLineAsEmpty() {
        // 空行在终端里是有意义的输出（命令之间的间隔），不能被吃掉。
        val out = buildAnnotatedScreen(listOf(line("a"), line("     "), line("b")), fg)
        assertEquals("a\n\nb", out.text)
    }

    @Test
    fun skipsContinuationCells() {
        // 宽字符占两格：左半格存真字符、右半格是占位。
        // 不去掉占位的话，屏幕上会出现「汉字 + 一个多余空格」。
        val wide = TerminalLine(
            listOf(
                RenderCell('中', CellStyle.Default, isContinuation = false),
                RenderCell(' ', CellStyle.Default, isContinuation = true),
                RenderCell('x', CellStyle.Default, isContinuation = false),
            ),
        )
        val out = buildAnnotatedScreen(listOf(wide), fg)
        assertEquals("中x", out.text)
    }

    @Test
    fun emptyInputGivesEmptyString() {
        assertEquals("", buildAnnotatedScreen(emptyList(), fg).text)
    }

    @Test
    fun singleLineHasNoTrailingNewline() {
        // 末尾多一个 \n 会让选择手柄的位置看起来不对（多出一行可选择）。
        val out = buildAnnotatedScreen(listOf(line("only")), fg)
        assertEquals("only", out.text)
    }

    @Test
    fun preservesStyleSpans() {
        // 相邻同款式的格子应合并成一个 span；不同款式必须切开。
        val bold = CellStyle(bold = true)
        val mixed = TerminalLine(
            listOf(
                RenderCell('a', CellStyle.Default, false),
                RenderCell('b', CellStyle.Default, false),
                RenderCell('c', bold, false),
            ),
        )
        val out = buildAnnotatedScreen(listOf(mixed), fg)
        assertEquals("abc", out.text)
        // 至少要有两个 span：前两个 a/b 一组，c 单独一组。
        assertTrue(
            "粗体格子没有被切分成独立 span，样式会丢",
            out.spanStyles.size >= 2,
        )
    }
}
