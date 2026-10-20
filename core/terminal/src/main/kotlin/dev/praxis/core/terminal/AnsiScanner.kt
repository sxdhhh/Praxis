package dev.praxis.core.terminal

/**
 * ANSI 转义序列扫出来的一个事件。
 *
 * 用事件流而不是「直接返回一段带样式的文本」，是因为终端输出的本质就是
 * 一串指令：同样两个字节在文本里和在 CSI 里含义完全不同。先切成事件，
 * 谁都能消费（缓冲、日志、录屏）。
 */
sealed interface AnsiEvent {

    /** 可打印文本（已合并成一个块，不是逐字符）。 */
    data class Text(val text: String) : AnsiEvent

    /** C0 控制字符：CR、LF、BS、TAB、BEL 等。 */
    data class Control(val code: Char) : AnsiEvent

    /**
     * CSI 序列：ESC 加左方括号，接参数，再接终止字节。
     *
     * [params] 里空参数已归一为 0（ANSI 里空参等价于 0）。
     * [privateMarker] 是问号、小于号、大于号、等号之一，普通序列为 null。
     */
    data class Csi(
        val params: List<Int>,
        val privateMarker: Char?,
        val finalChar: Char,
    ) : AnsiEvent

    /** OSC 序列：ESC 加右方括号，接载荷，以 BEL 或 ESC 加反斜杠结束。 */
    data class Osc(val payload: String) : AnsiEvent

    /** 未识别的两字节 ESC 序列（如字符集选择）。消费方一般直接忽略。 */
    data class Esc(val char: Char) : AnsiEvent
}

/**
 * ANSI 转义序列扫描器。
 *
 * ## 为什么必须保留残缺序列
 *
 * 终端输出是字节流，一个转义序列会被任意切开：一个 SGR 颜色序列完全可能分两次到达。
 * 任何「逐 chunk 独立解析」的实现都会在那一次切分时把半个序列当正文显示到屏幕上，
 * 屏幕上就会闪出一串乱码。
 *
 * 所以这里维持一个 [pending]：只要 chunk 末尾是未完成的序列，就原样存着等下一块。
 * 这与 `core:ai` 里 `SseParser` 保留残留缓冲是同一个思路。
 *
 * ## 残留必须有上限
 *
 * 残留一旦跨过 chunk 边界，下一次 feed 会把它拼在头部再解析；如果从不清理，
 * 一个永远不完整的序列会让 pending 无限增长。所以设置了 [MAX_PENDING] 上限：
 * 超过就丢弃（宁可丢一个畸形序列，也不能让内存无限涨）。
 *
 * ## 为什么码位用数值而不是字符字面量
 *
 * 控制字符在源码里写成转义序列时，极易在复制/生成环节被「解码成真实控制字符」，
 * 文件看起来是空的，编译器报 invalid character literal。用 [Char] 数值构造不会出这个问题。
 */
class AnsiScanner {

    private val pending = StringBuilder()

    /** 解析 [chunk]，返回本次能确定的事件。未完成的序列留在内部等下一块。 */
    fun feed(chunk: String): List<AnsiEvent> {
        if (chunk.isEmpty()) return emptyList()

        val work = if (pending.isEmpty()) chunk else pending.toString() + chunk
        pending.setLength(0)

        val events = ArrayList<AnsiEvent>(8)
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) {
                events += AnsiEvent.Text(text.toString())
                text.setLength(0)
            }
        }

        var i = 0
        while (i < work.length) {
            val c = work[i]

            when {
                c == ESC -> {
                    // 先把积攒的正文交出去，保证事件顺序正确。
                    flushText()
                    val consumed = consumeEscape(work, i, events)
                    if (consumed < 0) {
                        // 数据不够：从 ESC 起到末尾全部留到下一块。
                        pending.append(work, i, work.length)
                        if (pending.length > MAX_PENDING) pending.setLength(0)
                        return events
                    }
                    i += consumed
                }

                c.code < 0x20 || c.code == 0x7F -> {
                    flushText()
                    events += AnsiEvent.Control(c)
                    i++
                }

                else -> {
                    text.append(c)
                    i++
                }
            }
        }

        flushText()
        return events
    }

    /** 丢弃残留状态（重开会话时用）。 */
    fun reset() {
        pending.setLength(0)
    }

    /**
     * 尝试从 [start]（指向 ESC）开始消费一个转义序列。
     *
     * @return 本序列占用的字符数；数据不足时返 -1（由调用方留到下一块）。
     */
    private fun consumeEscape(work: String, start: Int, out: MutableList<AnsiEvent>): Int {
        if (start + 1 >= work.length) return -1

        val kind = work[start + 1]
        return when (kind) {
            CHAR_LBRACKET -> consumeCsi(work, start, out)
            CHAR_RBRACKET -> consumeOsc(work, start, out)
            // ESC 后紧跟另一个 ESC：上一个序列作废，从新的开始。
            ESC -> {
                out += AnsiEvent.Esc(ESC)
                1
            }

            else -> {
                out += AnsiEvent.Esc(kind)
                2
            }
        }
    }

    /** 消费 CSI：ESC 左方括号，接参数，再接终止字节。 */
    private fun consumeCsi(work: String, start: Int, out: MutableList<AnsiEvent>): Int {
        var i = start + 2
        var privateMarker: Char? = null
        val params = ArrayList<Int>(4)
        var current = StringBuilder()
        var sawParam = false

        while (i < work.length) {
            val c = work[i]

            when {
                // 参数字节：数字、分号。
                c in '0'..'9' -> {
                    current.append(c)
                    sawParam = true
                    i++
                }

                c == ';' -> {
                    params += current.toString().toIntOrNull() ?: 0
                    current = StringBuilder()
                    sawParam = true
                    i++
                }

                // 私有序号前缀（只在第一个位置合法）。
                (c == '?' || c == '<' || c == '>' || c == '=') && !sawParam && privateMarker == null -> {
                    privateMarker = c
                    i++
                }

                // 终止字节：0x40 到 0x7E。
                c.code in 0x40..0x7E -> {
                    if (current.isNotEmpty() || sawParam) {
                        params += current.toString().toIntOrNull() ?: 0
                    }
                    out += AnsiEvent.Csi(params, privateMarker, c)
                    return i - start + 1
                }

                // 中间字节（0x20 到 0x2F）：跳过，不影响解析。
                c.code in 0x20..0x2F -> {
                    i++
                }

                // 其他字节：不是合法 CSI，当作畸形序列丢弃。
                else -> {
                    out += AnsiEvent.Csi(params, privateMarker, FINAL_CANCEL)
                    return i - start
                }
            }
        }

        // 跑到末尾还没见到终止字节：数据不足。
        return -1
    }

    /** 消费 OSC：ESC 右方括号，载荷，以 BEL 或 ESC 加反斜杠结束。 */
    private fun consumeOsc(work: String, start: Int, out: MutableList<AnsiEvent>): Int {
        var i = start + 2
        val payload = StringBuilder()

        while (i < work.length) {
            val c = work[i]
            when {
                c.code == 0x07 -> {
                    out += AnsiEvent.Osc(payload.toString())
                    return i - start + 1
                }

                c == ESC -> {
                    // 字符串终止符是 ESC 加反斜杠（0x5C）。
                    if (i + 1 >= work.length) return -1
                    if (work[i + 1].code == 0x5C) {
                        out += AnsiEvent.Osc(payload.toString())
                        return i - start + 2
                    }
                    // 不是反斜杠：畸形 OSC，终止在这里。
                    out += AnsiEvent.Osc(payload.toString())
                    return i - start
                }

                else -> {
                    payload.append(c)
                    i++
                }
            }
        }

        return -1
    }

    companion object {
        /** ESC（0x1B）。 */
        val ESC: Char = 27.toChar()

        /** CSI 引导符：左方括号。 */
        private val CHAR_LBRACKET: Char = '['

        /** OSC 引导符：右方括号。 */
        private val CHAR_RBRACKET: Char = ']'

        /** 畸形 CSI 的伪终止字符：消费方可直接忽略。 */
        val FINAL_CANCEL: Char = 0.toChar()

        /** 残留缓冲上限：超过就丢（防一个永远不完整的序列把内存耗尽）。 */
        const val MAX_PENDING = 4096
    }
}
