package dev.praxis.feature.terminal

// CellStyle 定义在 :core:terminal（不是本模块），必须显式 import ——
// 漏了它 CI 直接报 Unresolved reference 'CellStyle'（2026-11-02 踩过）。
import dev.praxis.core.terminal.CellStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复制动作的**取文本**环节：`extractSelectionText`。
 *
 * ## 为什么单独补这一组（2026-11-02）
 *
 * ①「终端无法长按复制、无法全选」修复后，我在模拟器上验到了：
 * 长按出选择态、操作条（复制/全选/完成）都在、选区跨行高亮。
 * 但**「复制出来的文本对不对」一直没被测过** —— `extractSelectionText`
 * 此前零测试覆盖，而它是「复制」真正产出的那一半。
 *
 * 剪贴板内容在 adb 层读不出来（API 30 无 `cmd clipboard`、`dumpsys` 为空、
 * binder 需要 GET_CLIPBOARD 权限），所以退而求其次：
 * 把「交给剪贴板之前的那段文本」用单测钉死。
 * `clipboard.setText(AnnotatedString(text))` 那一行由 WiringGuardTest 守着，
 * 两者合起来覆盖了「选中 → 取文本 → 写剪贴板」的全链路。
 *
 * ## 终端行的特殊性（这些正是容易写错的地方）
 *
 *  - 定宽行必然有大量**尾空格**，直接拼会把它们带进剪贴板；
 *  - 宽字符（中文）占两格，右半格是**占位格**，不能重复输出；
 *  - 首行从中间开始时只能取起点之后的部分。
 */
class TerminalExtractSelectionTest {

    /** 造一行：普通字符逐格铺开，`|` 表示宽字符的占位右半格。 */
    private fun line(text: String, width: Int = 20): TerminalLine {
        val cells = mutableListOf<RenderCell>()
        for (ch in text) {
            cells.add(RenderCell(ch, CellStyle(), isContinuation = false))
        }
        while (cells.size < width) {
            cells.add(RenderCell(' ', CellStyle(), isContinuation = false))
        }
        return TerminalLine(cells)
    }

    /** 造一行含宽字符的：宽字符后跟一个占位右半格。 */
    private fun wideLine(parts: List<Pair<String, Boolean>>, width: Int = 20): TerminalLine {
        val cells = mutableListOf<RenderCell>()
        for ((s, isWide) in parts) {
            cells.add(RenderCell(s[0], CellStyle(), isContinuation = false))
            if (isWide) cells.add(RenderCell(' ', CellStyle(), isContinuation = true))
        }
        while (cells.size < width) {
            cells.add(RenderCell(' ', CellStyle(), isContinuation = false))
        }
        return TerminalLine(cells)
    }

    private fun sel(a: Int, b: Int, c: Int, d: Int) = TerminalSelection(a, b, c, d).normalized

    @Test
    fun `单行选择只取选中区间`() {
        val lines = listOf(line("hello world"))
        // endCol 是**包含式**（见 columnRangeOn 的 `endCol + 1`），
        // 所以选 hello（下标 0..4）要写 (0,0,0,4)，正好 5 个字符。
        assertEquals("hello", extractSelectionText(lines, sel(0, 0, 0, 4)))
    }

    @Test
    fun `跨行选择用换行连接`() {
        val lines = listOf(line("first"), line("second"), line("third"))
        assertEquals("first\nsecond\nthird", extractSelectionText(lines, sel(0, 0, 2, 4)))
    }

    /**
     * 行尾空格不得带进剪贴板。
     *
     * 终端每行都是定宽（80 列），不裁掉的话用户粘贴出来会带一长串空格，
     * 放进编辑器里整段缩进全乱 —— 这是「复制能用但很难用」的典型来源。
     */
    @Test
    fun `行尾空白被裁掉`() {
        val lines = listOf(line("abc"), line("de"))
        val out = extractSelectionText(lines, sel(0, 0, 1, 19))
        assertEquals("abc\nde", out)
        assertFalse("不应残留尾空格", out.contains("abc "))
    }

    /**
     * 宽字符的占位右半格只输出一次。
     *
     * 中文在终端里占两格，第二格是 `isContinuation = true` 的占位。
     * 不过滤的话「中文」会变成「中 文 」—— 每个字后面多一个空格。
     */
    @Test
    fun `宽字符占位格不重复输出`() {
        val lines = listOf(wideLine(listOf("中" to true, "文" to true)))
        // 「中文」占 4 格：中(0,1) 文(2,3)，endCol=3 是包含式，正好覆盖。
        assertEquals("中文", extractSelectionText(lines, sel(0, 0, 0, 3)))
    }

    /**
     * 从行中间开始选时，只取起点之后的部分。
     *
     * 用户往往从一行的中间开始拖 —— 若错写成「整行」，
     * 粘贴出来会多出他没选的前半句。
     */
    @Test
    fun `首行从中间开始时只取后半段`() {
        val lines = listOf(line("prefix-target"))
        // "prefix-target" 里 target 从下标 7 到 12（含），共 6 个字符。
        assertEquals("target", extractSelectionText(lines, sel(0, 7, 0, 12)))
    }

    /**
     * 空列表不崩（新终端还没任何输出时用户也可能长按）。
     */
    @Test
    fun `空缓冲区返回空串`() {
        assertEquals("", extractSelectionText(emptyList(), sel(0, 0, 0, 0)))
    }

    /**
     * 选区整体落在空白行区时不产出换行噪声。
     *
     * 用户从屏幕底部的空白区开始拖很常见，此时结果是空串而不是一串 `\n`。
     */
    @Test
    fun `只选中空白行时结果为空`() {
        val lines = listOf(line("abc"), line(""))
        val out = extractSelectionText(lines, sel(1, 0, 1, 19))
        assertEquals("", out)
    }

    /**
     * 反向保证：选区确实覆盖了内容，不是总返回空串。
     *
     * 上面几条「结果为空」的断言若被误写成恒真，这条会揭穿它。
     */
    @Test
    fun `全选能取到全部非空内容`() {
        val lines = listOf(line("aaa"), line("bbb"))
        val out = extractSelectionText(lines, sel(0, 0, 1, 19))
        assertTrue("全选必须包含两行内容", out.contains("aaa") && out.contains("bbb"))
    }
}