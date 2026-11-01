package dev.praxis.feature.terminal

/**
 * 终端输出的**文本选择模型**。
 *
 * ## 为什么需要单独一套模型（2026-11-01）
 *
 * 终端输出区是 LazyColumn 逐行渲染的，**每一行是一个独立的 Text**。
 * 这意味着 Compose 自带的 SelectionContainer 在这里没用：
 * 它只能选中「同一个 Text 内部」的字符，跨行拖选会直接断掉，
 * 而终端里最常做的恰恰是「选中从命令到结果的几行」。
 *
 * 所以选择状态得自己维护：用（行下标, 列下标）表示两个端点，
 * 再在渲染时把落在区间内的格子刷成选中色。
 *
 * ## 为什么端点用「格子下标」而不是「字符下标」
 *
 * 终端缓冲是**定宽格子阵列**（一行固定 cols 格，宽字符占两格）。
 * 用字符下标的话，「中文」与「a」都是一个字符但占的格子数不同，
 * 行内的像素位置就对不上，手指落在哪一格也就无法换算。
 * 用格子下标，行内第 k 格恒等于「左边距 + k × 字宽」，换算是最简单的乘法。
 *
 * ## 坐标约定
 *
 * - 行下标：对应 TerminalState.active.lines 的下标（含 scrollback，0 是最老的一行）。
 * - 列下标：对应 TerminalLine.cells 的下标（含宽字符右半格）。
 * - 比较时先比行、行相同再比列，**顺序无关**：先往左上拖也成立，
 *   由 [normalized] 保证起点永远在前。
 */
data class TerminalSelection(
    val anchorLine: Int,
    val anchorCol: Int,
    val focusLine: Int,
    val focusCol: Int,
) {

    /** 归一化后的区间，起点在前。 */
    val normalized: NormalizedSelection
        get() = if (anchorLine < focusLine || (anchorLine == focusLine && anchorCol <= focusCol)) {
            NormalizedSelection(anchorLine, anchorCol, focusLine, focusCol)
        } else {
            NormalizedSelection(focusLine, focusCol, anchorLine, anchorCol)
        }

    /** 是否只是「点了一下」没有拖动（此时不该当作选区处理）。 */
    val isEmpty: Boolean
        get() = anchorLine == focusLine && anchorCol == focusCol

    /** 拖动时更新焦点端。anchor 不动 —— 那是「按住的位置」。 */
    fun withFocus(line: Int, col: Int): TerminalSelection =
        copy(focusLine = line, focusCol = col)
}

/** [TerminalSelection] 归一化之后的结果，渲染与取文本都用它。 */
data class NormalizedSelection(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
) {
    /**
     * 第 [line] 行在这一行上应被选中的列区间，左闭右开。
     *
     * 返回 null 表示这一行与选区无关，渲染时不必处理。
     */
    fun columnRangeOn(line: Int): IntRange? {
        if (line < startLine || line > endLine) return null
        val from = if (line == startLine) startCol else 0
        // 终点列**包含**在内：手指停在某格上，那一格应当被选中。
        val to = if (line == endLine) endCol + 1 else Int.MAX_VALUE
        if (to <= from) return null
        return from until to
    }
}

/**
 * 一个格子是否落在选区里。
 *
 * [charLen] 是这一格字符占的格子数（宽字符 2、普通字符 1）。
 * 判据是「格子的**任意一半**落在区间内即算选中」——
 * 否则在宽字符中间起选时，会出现「选了半个汉字」的诡异状态。
 */
fun isCellSelected(range: IntRange?, col: Int, charLen: Int): Boolean {
    if (range == null) return false
    val lastCol = col + (charLen - 1).coerceAtLeast(0)
    return col <= range.last && lastCol >= range.first
}
