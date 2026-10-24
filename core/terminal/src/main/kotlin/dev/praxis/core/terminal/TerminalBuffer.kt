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

    /**
     * 屏幕区**曾经写到过的最深行号**（0 基，含）。
     *
     * ## 为什么不能直接用 [cursorRow] 当「内容高度」
     *
     * 绝大多数输出确实是「光标一直往下推」，用 `cursorRow` 就够了。
     * 但 ANSI 允许光标往回跳（`moveTo` / `moveBy`，`clear` 前的进度条重绘、
     * `top`、`vim` 都这么干）。一次 `ESC[1;1H` 就把 `cursorRow` 拉回 0，
     * 于是「按光标裁剪」会把下面已经写好的内容整段裁掉 —— 用户看到屏幕突然
     * 只剩第一行。
     *
     * 也不能在 UI 层「扫所有行找最后一个非空格子」：`publish()` 在每次按键
     * 都会调一次（`onInputChange`），而一次全屏扫描是
     * `rows × columns = 200 × 80 = 16000` 格 —— 打字时每键 16000 次判断，
     * 那是把输入延迟直接写进主线程。
     *
     * 所以在这里**增量维护**：写字符 / 换行时顺手取 max，UI 只需读一个 Int。
     *
     * ## 为什么它不需要在 scrollUp 时递减
     *
     * 全屏滚动时行整体上移，最深行不变（内容只是往上挪，屏幕仍是满的）；
     * 局部滚动区（`scrollTop > 0`）不影响这块「内容高度」的语义。
     * 可见行数只需要「屏幕被写到多深」，与具体是哪几行无关。
     */
    var deepestUsedRow: Int = 0
        private set

    /**
     * 每一行的修改计数（行号 → 递增版本）。
     *
     * ## 为什么需要它（终端打字卡顿的直接原因，2026-10-24）
     *
     * `TerminalViewModel.publish()` 每次都会调 `linesOf` 把整个屏幕重建成
     * `List<TerminalLine>`（每行 80 个 `RenderCell`）。而 `onInputChange`
     * 每按一个键都会 `publish()` 一次 —— 于是**每敲一个字符**就分配
     * `行数 × 80` 个对象（200 行就是 16000 个）。
     *
     * 结果是三件事一起变慢：分配、GC、以及 LazyColumn 里所有可见行的
     * `remember(visible)` 全部失效（`visible` 是新建的 list，虽然
     * data class 判等能命中，但构建它的成本已经付出）。
     *
     * 有了行版本号，`linesOf` 可以对**没变的行复用上一次的 `TerminalLine`
     * 对象**（引用相等 → `remember` 直接命中 → 传输与比较都变成 O(1)）。
     *
     * ## 为什么用 Int 数组而不是 Map
     *
     * 行号是 `0 until rows` 的连续下标，用 `IntArray(rows)` 最省：
     * 无装箱、无哈希、且能整体 `fill` 复位。Map 在每键调用的路径上是浪费。
     *
     * ## 什么时候递增
     *
     * 只在**真的改写了这一行**的地方：`writeCell`、`clearRow`、
     * `clearRowRange`，以及 `scrollUp`/`scrollDown` 里被搬动的行。
     * 用 `++` 而不是 `= 1`：同一个值被连续改写两次（进度条）也要能区分
     * 「又变了」，否则复用的快照会停留在第一次的状态。
     */
    private val rowVersions: IntArray = IntArray(rows)

    /** 取某行的修改计数（供 UI 判断能否复用上一次的快照）。 */
    fun rowVersion(row: Int): Int = if (row in 0 until rows) rowVersions[row] else 0

    /** 把整屏的版本号推进一格（用于「整体搬迁后所有行都可能变了」）。 */
    private fun bumpAllRowVersions() {
        for (i in rowVersions.indices) rowVersions[i]++
    }

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
        // 换行本身就意味着「这一行已经属于内容」：即使新行还没写字符，
        // 视觉上光标已经在那里了。不更新的话 `echo` 输出最后一行不带换行时
        // 行数会少 1，看着像最后一行被吃掉。
        if (cursorRow > deepestUsedRow) deepestUsedRow = cursorRow
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
        // 行整体上移 → 每一格的内容都换了行号，UI 的快照一行都不能复用。
        // 这是终端最频繁的路径（每次输出到屏底都滚一行），
        // 不推进的话复用逻辑会拿旧快照画出**错行**（上滚后文字停在原位）。
        bumpAllRowVersions()
    }

    /** 在滚动区内下滚 [n] 行。 */
    fun scrollDown(n: Int) {
        repeat(n.coerceAtLeast(1)) {
            grid.removeAt(scrollBottom)
            grid.add(scrollTop, MutableList(columns) { Cell() })
            continuation.removeAt(scrollBottom)
            continuation.add(scrollTop, MutableList(columns) { false })
        }
        // 行整体下移 → 每一格的内容都换了行号，UI 的快照一行都不能复用。
        bumpAllRowVersions()
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
                // 光标以下全被清掉 → 内容高度收缩到光标行。
                // 不收缩的话 `clear` 之后行数仍是清屏前那么高，界面留着
                // 一大片空白（正是「清屏了但还是能往下滚」的来源）。
                deepestUsedRow = cursorRow
            }

            1 -> {
                for (r in 0 until cursorRow) clearRow(r)
                clearRowRange(cursorRow, 0, cursorColumn + 1)
                // 光标以上被清空，但光标以下还可能有内容 → 高度至少是光标行。
                // 这里不能收缩到 cursorRow：下面那些行还在屏幕上。
                if (deepestUsedRow < cursorRow) deepestUsedRow = cursorRow
            }

            2 -> {
                for (r in 0 until rows) clearRow(r)
                // 整屏清空 → 内容高度归零。`TerminalSession.reset()` 走的就是
                // 这条分支，之后 `moveTo(1,1)` 把光标放回首行，于是
                // 「清屏后只剩 1 行」——与真实终端一致。
                deepestUsedRow = 0
            }
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
        // 维护「屏幕被写到多深」：`deepestUsedRow` 的 KDoc 说明了为什么必须增量维护。
        // 注意这里**不跳过空格**：`echo "a b"` 里那个空格也是写出来的内容，
        // 跳过会让中间空格行判定变浅（虽然那不影响行数，但少一条特例更不容易出错）。
        if (row > deepestUsedRow) deepestUsedRow = row
        // 这一行变了 → 让 UI 知道不能复用上一帧的快照（见 rowVersions 的 KDoc）。
        rowVersions[row]++
    }

    private fun clearRow(row: Int) {
        grid[row] = MutableList(columns) { Cell() }
        continuation[row] = MutableList(columns) { false }
        if (row in rowVersions.indices) rowVersions[row]++
    }

    private fun clearRowRange(row: Int, from: Int, to: Int) {
        val start = from.coerceIn(0, columns)
        val end = to.coerceIn(0, columns)
        for (c in start until end) {
            grid[row][c] = Cell()
            continuation[row][c] = false
        }
        // 只清了一部分也要算「变了」：UI 上这一行确实从有色文本变成空白。
        if (end > start && row in rowVersions.indices) rowVersions[row]++
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
