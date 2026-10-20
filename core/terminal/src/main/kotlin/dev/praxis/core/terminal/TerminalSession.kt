package dev.praxis.core.terminal

/**
 * 终端会话：把 ANSI 事件逐个应用到 [TerminalBuffer]，并提供「喂字节、取屏幕」两个入口。
 *
 * ## 为什么单独抽一层
 *
 * `AnsiScanner` 只负责「切事件」，`TerminalBuffer` 只负责「存格子」。
 * 两者的衔接全在这里：SGR 怎么改样式、CSI 的各个终止字节分别对应哪个操作。
 * 把这层抽出来后，扫描器和缓冲都能单独测，会话层也能测（同步纯函数）。
 *
 * ## 只实现会被用到的指令
 *
 * 完整 xterm 指令集有上百条（DEC 私有模式、鼠标上报、字符集切换……）。
 * 这里只做「跑 shell 命令看输出」必需的：光标移动、清屏清行、SGR 颜色与属性。
 * 未识别的 CSI 一律忽略 —— 这比「猜一个近似行为」更安全，屏幕不会因为一条
 * 没见过的指令而全乱。
 */
class TerminalSession(
    columns: Int,
    rows: Int,
    maxScrollback: Int = TerminalBuffer.DEFAULT_SCROLLBACK,
) {
    val buffer = TerminalBuffer(columns, rows, maxScrollback)

    private val scanner = AnsiScanner()

    /** SGR 当前累积状态。 */
    private var style = CellStyle.Default

    /** 喂一块输出。 */
    fun feed(chunk: String) {
        scanner.feed(chunk).forEach(::apply)
    }

    /** 清空屏幕与历史（重开会话）。 */
    fun reset() {
        scanner.reset()
        style = CellStyle.Default
        buffer.setStyle(style)
        buffer.eraseInDisplay(2)
        buffer.moveTo(1, 1)
        buffer.resetScrollRegion()
    }

    /** 把单个事件应用到缓冲。 */
    fun apply(event: AnsiEvent) {
        when (event) {
            is AnsiEvent.Text -> event.text.forEach { buffer.putChar(it) }

            is AnsiEvent.Control -> when (event.code.code) {
                0x0D -> buffer.carriageReturn()   // CR
                0x0A -> buffer.lineFeed()         // LF
                0x08 -> buffer.backspace()        // BS
                0x09 -> buffer.tab()              // TAB
                // BEL、垂直制表等：不需要声音/滚动，直接忽略。
                else -> Unit
            }

            is AnsiEvent.Csi -> applyCsi(event)

            // OSC 一般是标题设置（含终端标题、超链接），当前不消费。
            is AnsiEvent.Osc -> Unit
            is AnsiEvent.Esc -> Unit
        }
    }

    private fun applyCsi(csi: AnsiEvent.Csi) {
        // 畸形序列（伪终止字节）直接丢，不做任何动作。
        if (csi.finalChar == AnsiScanner.FINAL_CANCEL) return

        val p = csi.params
        val first = p.firstOrNull() ?: 0

        when (csi.finalChar) {
            // --- 光标移动 ---
            'A' -> buffer.moveBy(-(first.coerceAtLeast(1)), 0)
            'B' -> buffer.moveBy(first.coerceAtLeast(1), 0)
            'C' -> buffer.moveBy(0, first.coerceAtLeast(1))
            'D' -> buffer.moveBy(0, -(first.coerceAtLeast(1)))
            'E' -> { buffer.moveBy(first.coerceAtLeast(1), 0); buffer.carriageReturn() }
            'F' -> { buffer.moveBy(-(first.coerceAtLeast(1)), 0); buffer.carriageReturn() }
            'G' -> buffer.moveTo(buffer.cursorRow + 1, first.coerceAtLeast(1))
            'd' -> buffer.moveTo(first.coerceAtLeast(1), buffer.cursorColumn + 1)
            'H', 'f' -> buffer.moveTo(
                p.getOrNull(0)?.takeIf { it > 0 } ?: 1,
                p.getOrNull(1)?.takeIf { it > 0 } ?: 1,
            )

            // --- 擦除 ---
            'J' -> buffer.eraseInDisplay(first)
            'K' -> buffer.eraseInLine(first)

            // --- 插入 / 删除 ---
            'P' -> buffer.deleteChars(first.coerceAtLeast(1))
            '@' -> buffer.insertChars(first.coerceAtLeast(1))

            // --- 滚动 ---
            'S' -> buffer.scrollUp(first.coerceAtLeast(1))
            'T' -> buffer.scrollDown(first.coerceAtLeast(1))

            // --- 滚动区 ---
            'r' -> {
                val top = p.getOrNull(0)?.takeIf { it > 0 } ?: 1
                val bottom = p.getOrNull(1)?.takeIf { it > 0 } ?: buffer.rows
                buffer.setScrollRegion(top, bottom)
            }

            // --- 样式 ---
            'm' -> applySgr(p)

            // --- 设备状态 / 私有模式：不响应 ---
            else -> Unit
        }
    }

    /**
     * 应用 SGR（Select Graphic Rendition）。
     *
     * 空参数（`ESC[m`）等价于 0，即重置。这一段是终端里最容易写漏的地方：
     * 一次 SGR 可以带多个参数（`1;31;42m`），必须按顺序全部应用。
     */
    private fun applySgr(params: List<Int>) {
        val codes = if (params.isEmpty()) listOf(0) else params
        var next = style

        for (code in codes) {
            next = when (code) {
                0 -> CellStyle.Default
                1 -> next.copy(bold = true)
                3 -> next.copy(italic = true)
                4 -> next.copy(underline = true)
                7 -> next.copy(inverse = true)
                22 -> next.copy(bold = false)
                23 -> next.copy(italic = false)
                24 -> next.copy(underline = false)
                27 -> next.copy(inverse = false)

                // 30-37 前景基本色；90-97 前景亮色。
                in 30..37 -> next.copy(foreground = code - 30)
                in 90..97 -> next.copy(foreground = code - 90 + 8)
                // 40-47 背景基本色；100-107 背景亮色。
                in 40..47 -> next.copy(background = code - 40)
                in 100..107 -> next.copy(background = code - 100 + 8)

                39 -> next.copy(foreground = CellStyle.DEFAULT_COLOR)
                49 -> next.copy(background = CellStyle.DEFAULT_COLOR)

                // 256 色与真彩色的扩展参数（38/48 后跟 5;n 或 2;r;g;b）由循环外的处理接管，
                // 这里不单独处理，避免把 n 当成一个新的 SGR 码。
                else -> next
            }
        }

        style = applyExtendedColors(codes, next)
        buffer.setStyle(style)
    }

    /**
     * 处理 256 色 / 真彩色。
     *
     * `38;5;n`（前景 256 色）、`38;2;r;g;b`（前景真彩）、背景同理。
     * 真彩色没有 256 色索引可存，因此编码成 `0x1000000 + RGB` 这种「假索引」，
     * 渲染层见到 >= 0x1000000 就按真彩画。这样 [CellStyle] 的字段类型不用为真彩再扩。
     */
    private fun applyExtendedColors(codes: List<Int>, base: CellStyle): CellStyle {
        var result = base
        var i = 0
        while (i < codes.size) {
            val code = codes[i]
            if (code == 38 || code == 48) {
                val mode = codes.getOrNull(i + 1)
                when (mode) {
                    5 -> {
                        val index = codes.getOrNull(i + 2) ?: 0
                        result = if (code == 38) {
                            result.copy(foreground = index.coerceIn(0, 255))
                        } else {
                            result.copy(background = index.coerceIn(0, 255))
                        }
                        i += 3
                        continue
                    }

                    2 -> {
                        val r = codes.getOrNull(i + 2) ?: 0
                        val g = codes.getOrNull(i + 3) ?: 0
                        val b = codes.getOrNull(i + 4) ?: 0
                        val rgb = TRUE_COLOR_FLAG or
                            (r.coerceIn(0, 255) shl 16) or
                            (g.coerceIn(0, 255) shl 8) or
                            b.coerceIn(0, 255)
                        result = if (code == 38) {
                            result.copy(foreground = rgb)
                        } else {
                            result.copy(background = rgb)
                        }
                        i += 5
                        continue
                    }
                }
            }
            i++
        }
        return result
    }

    companion object {
        /**
         * 真彩色标记位。
         *
         * 0x1000000 不可能出现在 0..255 的调色板索引里，也不会与 RGB（最大 0xFFFFFF）冲突，
         * 所以可以用一个 Int 字段同时表达「调色板索引」与「真彩色」。
         */
        const val TRUE_COLOR_FLAG = 0x1000000
    }
}
