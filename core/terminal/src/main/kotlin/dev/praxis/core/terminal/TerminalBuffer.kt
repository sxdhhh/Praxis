package dev.praxis.core.terminal

/**
 * 一个格子的样式。
 *
 * 颜色用 256 色索引 + 默认色标记，而不是直接存 ARGB：
 * - 终端里颜色是调色板索引，实际显示色由主题决定（深色/浅色主题下 0 号色不同）；
 * - 存索引才能换主题时不变内容，渲染层再查表。
 */
data class CellStyle(
    val foreground: Int = DEFAULT_COLOR,
    val background: Int = DEFAULT_COLOR,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    companion object {
        /** 默认色：渲染层用自己的主题前景/背景。 */
        const val DEFAULT_COLOR = -1

        val Default = CellStyle()
    }
}

/** 一个字符格子。[char] 为空格表示空。 */
data class Cell(
    val char: Char = ' ',
    val style: CellStyle = CellStyle.Default,
) {
    val isBlank: Boolean get() = char == ' '
}

/**
 * 终端屏幕缓冲。
 *
 * ## 三个必须自己算的东西
 *
 * 1. **宽度只算 CJK，不引完整 Unicode 表**：完整的 East Asian Width 需要一张几万条的
 *    数据表，编译进 APK 不划算。这里只判「常见宽字符区段占 2 格」，其余一律 1 格。
 *    代价是部分生僻符号宽度可能错一格，但不会崩、不会越界。
 * 2. **换行不是插入行**：写到行尾自动折行（软换行），光标下移；CR 回到行首；LF 下移不回头。
 * 3. **滚动用环形结构**：[scrollTop]..[scrollBottom] 是滚动区，滚出区顶的行进 [scrollback]，
 *    这样历史输出不丢且内存有上限（[maxScrollback]）。
 */
class TerminalBuffer(
    val columns: Int,
    val rows: Int,
    private val maxScrollback: Int = DEFAULT_SCROLLBACK,
) {
    init {
        require(columns > 0) { "columns must be positive" }
        require(rows > 0) { "rows must be positive" }
    }

    private val grid: MutableList<MutableList<Cell>> =
        MutableList(rows) { MutableList(columns) { Cell() } }

    /** 滚出屏幕的历史行（只读用，不参与重绘）。 */
    private val scrollback = ArrayDeque<MutableList<Cell>>()

    var cursorRow: Int = 0
        private set
    var cursorColumn: Int = 0
        private set

    /** 光标当前样式（下一个写入字符用它）。 */
    var style: CellStyle = CellStyle.Default
        private set

    /** 已写到行尾，下一个字符需先换行（真实终端的“延迟折行”语义）。 */
    private var pendingWrap: Boolean = false

    var scrollTop: Int = 0
        private set
    var scrollBottom: Int = rows - 1
        private set

    /** 宽字符占位后留下的“右半格”标记：这些格子不单独渲染（由左半格的宽字符画过去）。 */
    private val continuation: MutableList<MutableList<Boolean>> =
        MutableList(rows) { MutableList(columns) { false } }

    /** 取某行快照。行号超界返回空行。 */
    fun line(row: Int): List<Cell> =
        if (row in 0 until rows) grid[row].toList() else emptyList()

    /** 全部屏幕行快照。 */
    fun snapshot(): List<List<Cell>> = grid.map { it.toList() }

    /** 历史行数量。 */
    fun scrollbackSize(): Int = scrollback.size

    /** 第 [index] 条历史（0 为最旧）。 */
    fun scrollbackLine(index: Int): List<Cell>? = scrollback.getOrNull(index)?.toList()

    /** 设置当前样式（SGR 应用结果）。 */
    fun setStyle(next: CellStyle) {
        style = next
    }

    /**
     * 写入一个字符，按宽度规则推进光标。
     *
     * ## 折行用「待折行」标志而不是把光标夹到最后一列
     *
     * 真实终端的行为是「写在最后一列不立即换行，等下一个字符再换」。
     * 早前实现用 `cursorColumn = columns - 1` 表达这件事，结果是**下一个字符
     * 会覆盖同一格**：写 "abc" 到 3 列屏再写 X，得到 "abX"，c 被吃掉。
     *
     * 正确做法是置一个 [pendingWrap] 标志：光标留在原地（不再夹），
     * 下个字符到达时先换行再写。这样既保留了「不立即换行」，也不丢字符。
     *
     * ## columns == 1 时宽字符无法容纳
     *
     * 宽字符需要 2 列。屏宽为 1 时（极窄分屏/横屏小窗）它永远放不下，
     * 折行后仍是 0，会写出越界索引。这种情况**丢弃该字符**而非猛写：
     * 越界写入会被 writeCell 静默吞掉，但光标计算会错，后续输出全乱。
     */
    fun putChar(c: Char) {
        val width = charWidth(c)
        if (width <= 0) return

        // 上一字符已写到行尾、还没换行：此时才真正换。
        if (pendingWrap) {
            cursorColumn = 0
            lineFeed()
            pendingWrap = false
        }

        // 本行剩余空间放不下这个字符（宽字符只剩 1 格，或屏宽不足以容纳）。
        if (cursorColumn + width > columns) {
            if (width > columns) {
                // 屏宽本身装不下一个宽字符：丢弃，不写越界索引。
                return
            }
            cursorColumn = 0
            lineFeed()
        }

        writeCell(cursorRow, cursorColumn, c, style)
        if (width == 2) {
            // 右半格标成延续格：不参与字符渲染，仅占位。
            writeCell(cursorRow, cursorColumn + 1, ' ', style)
            continuation[cursorRow][cursorColumn + 1] = true
            cursorColumn += 2
        } else {
            cursorColumn += 1
        }

        // 恰好写到行尾：不立即换行，只标记，等下一个字符。
        if (cursorColumn >= columns) {
            cursorColumn = columns - 1
            pendingWrap = true
        }
    }

    /** 换行到下一行；在滚动区底部时滚动。 */
    fun lineFeed() {
        // 显式换行即已消费掉「待折行」状态，否则下个字符会多换一行。
        pendingWrap = false
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow += 1
        }
    }

    /** 回车。 */
    fun carriageReturn() {
        pendingWrap = false
        cursorColumn = 0
    }

    /** 退格：仅左移，不删字符（真实终端行为）。 */
    fun backspace() {
        pendingWrap = false
        if (cursorColumn > 0) cursorColumn -= 1
    }

    /** 制表：跳到下一个 8 的倍数。 */
    fun tab() {
        pendingWrap = false
        val next = ((cursorColumn / TAB_WIDTH) + 1) * TAB_WIDTH
        cursorColumn = next.coerceAtMost(columns - 1)
    }

    /** 移动到绝对坐标（1 基，ANSI 语义）。 */
    fun moveTo(row: Int, column: Int) {
        pendingWrap = false
        cursorRow = (row - 1).coerceIn(0, rows - 1)
        cursorColumn = (column - 1).coerceIn(0, columns - 1)
    }

    /** 相对移动（ANSI CUP 的简写：A/B/C/D）。 */
    fun moveBy(rowDelta: Int, columnDelta: Int) {
        pendingWrap = false
        cursorRow = (cursorRow + rowDelta).coerceIn(0, rows - 1)
        cursorColumn = (cursorColumn + columnDelta).coerceIn(0, columns - 1)
    }

    /** 设置滚动区（1 基，含边界）。参数不合法时忽略。 */
    fun setScrollRegion(top: Int, bottom: Int) {
        val t = (top - 1).coerceIn(0, rows - 1)
        val b = (bottom - 1).coerceIn(0, rows - 1)
        if (t >= b) return
        scrollTop = t
        scrollBottom = b
        pendingWrap = false
        cursorRow = t
        cursorColumn = 0
    }

    /** 重置滚动区为全屏。 */
    fun resetScrollRegion() {
        scrollTop = 0
        scrollBottom = rows - 1
    }

    /** 在滚动区内上滚 [n] 行。 */
    fun scrollUp(n: Int) {
        repeat(n.coerceAtLeast(1)) {
            val removed = grid.removeAt(scrollTop)
            // 全屏滚动时，滚出的行是有价值的历史；局部滚动区（如编辑器状态栏）不进历史。
            if (scrollTop == 0 && scrollBottom == rows - 1) {
                scrollback.addLast(removed)
                while (scrollback.size > maxScrollback) scrollback.removeFirst()
            }
            grid.add(scrollBottom, MutableList(columns) { Cell() })
            continuation.add(scrollBottom, MutableList(columns) { false })
            continuation.removeAt(scrollTop)
        }
    }

    /** 在滚动区内下滚 [n] 行。 */
    fun scrollDown(n: Int) {
        repeat(n.coerceAtLeast(1)) {
            grid.removeAt(scrollBottom)
            grid.add(scrollTop, MutableList(columns) { Cell() })
            continuation.removeAt(scrollBottom)
            continuation.add(scrollTop, MutableList(columns) { false })
        }
    }

    /**
     * 清屏。
     *
     * [mode]：0 = 光标到末尾；1 = 行首到光标；2 = 全屏（光标不动，ANSI 标准）。
     */
    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                clearRowRange(cursorRow, cursorColumn, columns)
                for (r in cursorRow + 1 until rows) clearRow(r)
            }

            1 -> {
                for (r in 0 until cursorRow) clearRow(r)
                clearRowRange(cursorRow, 0, cursorColumn + 1)
            }

            2 -> for (r in 0 until rows) clearRow(r)
        }
    }

    /** 清行。[mode]：0 = 光标到行尾；1 = 行首到光标；2 = 整行。 */
    fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> clearRowRange(cursorRow, cursorColumn, columns)
            1 -> clearRowRange(cursorRow, 0, cursorColumn + 1)
            2 -> clearRow(cursorRow)
        }
    }

    /** 删除 [n] 个字符（光标右侧左移）。 */
    fun deleteChars(n: Int) {
        val row = grid[cursorRow]
        val cont = continuation[cursorRow]
        repeat(n.coerceAtLeast(1)) {
            row.removeAt(cursorColumn)
            row.add(Cell())
            cont.removeAt(cursorColumn)
            cont.add(false)
        }
    }

    /** 插入 [n] 个空白字符（光标右侧右移）。 */
    fun insertChars(n: Int) {
        val row = grid[cursorRow]
        val cont = continuation[cursorRow]
        repeat(n.coerceAtLeast(1)) {
            row.add(cursorColumn, Cell())
            row.removeAt(row.lastIndex)
            cont.add(cursorColumn, false)
            cont.removeAt(cont.lastIndex)
        }
    }

    /** 光标处是否是一个宽字符的右半格（渲染层用它跳过）。 */
    fun isContinuation(row: Int, column: Int): Boolean =
        if (row in 0 until rows && column in 0 until columns) continuation[row][column] else false

    private fun writeCell(row: Int, column: Int, c: Char, s: CellStyle) {
        if (row !in 0 until rows || column !in 0 until columns) return
        grid[row][column] = Cell(c, s)
        continuation[row][column] = false
    }

    private fun clearRow(row: Int) {
        grid[row] = MutableList(columns) { Cell() }
        continuation[row] = MutableList(columns) { false }
    }

    private fun clearRowRange(row: Int, from: Int, to: Int) {
        val start = from.coerceIn(0, columns)
        val end = to.coerceIn(0, columns)
        for (c in start until end) {
            grid[row][c] = Cell()
            continuation[row][c] = false
        }
    }

    companion object {
        const val DEFAULT_SCROLLBACK = 1000
        const val TAB_WIDTH = 8

        /**
         * 字符显示宽度。
         *
         * 只覆盖「必然双宽」的常见区段（汉字、韩文、全角标点、假名）。
         * 其余一律 1 —— 宁可错一格，不要引巨大数据表。
         */
        fun charWidth(c: Char): Int {
            val code = c.code
            return when {
                code < 0x20 || code == 0x7F -> 0
                code in 0x1100..0x115F -> 2   // 韩文字母
                code in 0x2E80..0x303E -> 2   // CJK 部首、中文标点
                code in 0x3041..0x33FF -> 2   // 假名、CJK 兼容
                code in 0x3400..0x4DBF -> 2   // CJK 扩展 A
                code in 0x4E00..0x9FFF -> 2   // CJK 基本区
                code in 0xA000..0xA4CF -> 2   // 彝族文
                code in 0xAC00..0xD7A3 -> 2   // 韩文音节
                code in 0xF900..0xFAFF -> 2   // CJK 兼容汉字
                code in 0xFE30..0xFE6F -> 2   // CJK 兼容形式、小写变体
                code in 0xFF00..0xFF60 -> 2   // 全角 ASCII
                code in 0xFFE0..0xFFE6 -> 2   // 全角符号
                else -> 1
            }
        }
    }
}
