package dev.praxis.ui.widgets.markdown

/**
 * 轻量 Markdown 解析器（块级 + 行内）。
 *
 * ## 为什么自己写，而不是引第三方库
 *
 * 三个硬约束：
 *
 * 1. **必须离线可编**。本项目的构建是 `--offline`，任何新依赖都进不来；
 *    而把一个 Markdown 库连它的传递依赖一起搬进来，等于把构建改成联网。
 * 2. **不能照抄 Operit 那套**。取证 `/root/Operit`：它的 Markdown 是
 *    `ui/common/markdown/` 下 **14 个文件、7907 行**（含 Canvas 节点渲染器
 *    1780 行、代码高亮 1046 行、表格 669 行、流式渲染 1249 行）。
 *    那是为「Canvas 自绘 + 流式增量 + 图片/音频/视频嵌入」设计的整套管线，
 *    整体搬进 Praxis 会带进一大堆用不到的节点类型与渲染器注册机制。
 * 3. **只需要聊天回复里真正会出现的那一档**。AI 输出用到的 Markdown 其实很窄：
 *    标题、段落、粗斜体、行内代码、围栏代码块、无序/有序列表、引用、表格、分隔线。
 *    这些用 300 行左右能完整覆盖，且完全可单测。
 *
 * ## 设计取舍
 *
 * - **纯函数、零 Compose 依赖**：放在 `markdown` 子包里，可以直接在
 *   `src/test` 里跑（本项目已有 10 个模块的 JVM 单测基建）。
 * - **不追求 CommonMark 完备**：故意不做 HTML、脚注、引用式链接、
 *   嵌套超过两层的列表。遇到不认识的行就当作段落 —— 宁可少渲染，
 *   也不要把内容吃掉（吞内容是比渲染不漂亮严重得多的缺陷）。
 * - **宽容优先**：未闭合的围栏代码块会一直吃到文末并把内容保留下来
 *    （流式输出时几乎必然出现「围栏只写了一半」的中间态）。
 */
object MarkdownParser {

    /** 解析成块列表。空文本返回空列表。 */
    fun parse(source: String): List<MdBlock> {
        if (source.isBlank()) return emptyList()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<MdBlock>()
        var i = 0

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()

            when {
                line.isBlank() -> i++

                // 围栏代码块：``` 或 ~~~，语言标签可选。
                isFence(line) -> {
                    val fence = fenceOf(line)
                    val lang = line.trim().removePrefix(fence).trim()
                    i++
                    val code = ArrayList<String>()
                    // 未闭合时吃到文末：流式输出的中间态必须能显示已收到的代码，
                    // 而不是整段消失。
                    while (i < lines.size && !lines[i].trimEnd().startsWith(fence)) {
                        code += lines[i]
                        i++
                    }
                    if (i < lines.size) i++ // 吃掉收尾围栏
                    out += MdBlock.Code(language = lang.ifBlank { null }, code = code.joinToString("\n"))
                }

                // 分隔线：--- / *** / ___（三个及以上）
                isDivider(line) -> {
                    out += MdBlock.Divider
                    i++
                }

                // ATX 标题：# 到 ######
                headingLevel(line) > 0 -> {
                    val level = headingLevel(line)
                    val text = line.trimStart().removePrefix("#".repeat(level)).trim()
                    out += MdBlock.Heading(level = level, text = text)
                    i++
                }

                // 引用：连续的 > 行合成一块
                line.trimStart().startsWith(">") -> {
                    val buf = ArrayList<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        buf += lines[i].trimStart().removePrefix(">").removePrefix(" ").trimEnd()
                        i++
                    }
                    out += MdBlock.Quote(text = buf.joinToString("\n").trim())
                }

                // 表格：表头行 + 分隔行（|---|---|）
                isTableHeader(lines, i) -> {
                    val header = splitRow(lines[i])
                    i += 2 // 跳过表头与分隔行
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        rows += splitRow(lines[i])
                        i++
                    }
                    out += MdBlock.Table(header = header, rows = rows)
                }

                // 无序列表：- / * / +
                bulletOf(line) != null -> {
                    val items = ArrayList<String>()
                    while (i < lines.size) {
                        val b = bulletOf(lines[i]) ?: break
                        items += b
                        i++
                        // 缩进的续行并入上一项（AI 常在列表项里换行）
                        while (i < lines.size && lines[i].startsWith("  ") && lines[i].isNotBlank() &&
                            bulletOf(lines[i]) == null
                        ) {
                            items[items.lastIndex] = items.last() + " " + lines[i].trim()
                            i++
                        }
                    }
                    out += MdBlock.BulletList(items = items)
                }

                // 有序列表：1. / 1)
                orderedOf(line) != null -> {
                    val items = ArrayList<String>()
                    while (i < lines.size) {
                        val o = orderedOf(lines[i]) ?: break
                        items += o
                        i++
                        while (i < lines.size && lines[i].startsWith("  ") && lines[i].isNotBlank() &&
                            orderedOf(lines[i]) == null
                        ) {
                            items[items.lastIndex] = items.last() + " " + lines[i].trim()
                            i++
                        }
                    }
                    out += MdBlock.OrderedList(items = items)
                }

                else -> {
                    // 段落：连续非空行合成一段（软换行渲染时保留为空格）
                    val buf = ArrayList<String>()
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !isFence(lines[i]) && headingLevel(lines[i]) == 0 &&
                        !lines[i].trimStart().startsWith(">") &&
                        bulletOf(lines[i]) == null && orderedOf(lines[i]) == null &&
                        !isDivider(lines[i]) && !isTableHeader(lines, i)
                    ) {
                        buf += lines[i].trim()
                        i++
                    }
                    // 防御：上面条件若一条都不满足会死循环，理论上不会发生，
                    // 但仍推进一步保证前进。
                    if (buf.isEmpty()) {
                        out += MdBlock.Paragraph(text = lines[i].trim())
                        i++
                    } else {
                        out += MdBlock.Paragraph(text = buf.joinToString("\n"))
                    }
                }
            }
        }
        return out
    }

    /**
     * 把一行文本解析成行内片段序列。
     *
     * 顺序很重要：先抓 `code`（代码里的星号不该被当成粗体），再抓粗体/斜体，
     * 最后抓链接。用一个扫描器而不是正则链，是为了避免「正则互相吃掉字符」。
     */
    fun parseInline(text: String): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<MdInline>()
        val plain = StringBuilder()
        var i = 0

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out += MdInline.Plain(plain.toString())
                plain.setLength(0)
            }
        }

        while (i < text.length) {
            val c = text[i]

            // 行内代码：`code`
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    out += MdInline.Code(text.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }

            // 粗体：**text** 或 __text__
            if (c == '*' && i + 1 < text.length && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    flushPlain()
                    out += MdInline.Bold(text.substring(i + 2, end))
                    i = end + 2
                    continue
                }
            }
            if (c == '_' && i + 1 < text.length && text[i + 1] == '_') {
                val end = text.indexOf("__", i + 2)
                if (end > i + 1) {
                    flushPlain()
                    out += MdInline.Bold(text.substring(i + 2, end))
                    i = end + 2
                    continue
                }
            }

            // 斜体：*text* 或 _text_
            if (c == '*' || c == '_') {
                val end = text.indexOf(c, i + 1)
                if (end > i + 1) {
                    flushPlain()
                    out += MdInline.Italic(text.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }

            // 删除线：~~text~~
            if (c == '~' && i + 1 < text.length && text[i + 1] == '~') {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 1) {
                    flushPlain()
                    out += MdInline.Strike(text.substring(i + 2, end))
                    i = end + 2
                    continue
                }
            }

            // 链接：[text](url)
            if (c == '[') {
                val close = text.indexOf(']', i + 1)
                if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                    val closeParen = text.indexOf(')', close + 2)
                    if (closeParen > close) {
                        flushPlain()
                        out += MdInline.Link(
                            label = text.substring(i + 1, close),
                            url = text.substring(close + 2, closeParen),
                        )
                        i = closeParen + 1
                        continue
                    }
                }
            }

            plain.append(c)
            i++
        }
        flushPlain()
        return out
    }

    // ── 判定辅助 ────────────────────────────────────────────────────

    private fun isFence(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("```") || t.startsWith("~~~")
    }

    private fun fenceOf(line: String): String =
        if (line.trimStart().startsWith("~~~")) "~~~" else "```"

    private fun isDivider(line: String): Boolean {
        val t = line.trim()
        if (t.length < 3) return false
        val ch = t.first()
        if (ch != '-' && ch != '*' && ch != '_') return false
        return t.all { it == ch || it == ' ' }
    }

    private fun headingLevel(line: String): Int {
        val t = line.trimStart()
        var n = 0
        while (n < t.length && n < 6 && t[n] == '#') n++
        // `#` 后必须是空格或行尾，否则 `#hashtag` 会被误判成标题
        if (n == 0) return 0
        if (n < t.length && t[n] != ' ') return 0
        return n
    }

    private fun bulletOf(line: String): String? {
        val t = line.trimStart()
        if (t.length < 2) return null
        val c = t[0]
        if (c != '-' && c != '*' && c != '+') return null
        if (t[1] != ' ') return null
        // 排除分隔线（`- - -`）：它们已在上一个分支被吃掉，这里再防一次。
        if (isDivider(t)) return null
        return t.substring(2).trim()
    }

    private fun orderedOf(line: String): String? {
        val t = line.trimStart()
        var i = 0
        while (i < t.length && t[i].isDigit()) i++
        if (i == 0 || i >= t.length) return null
        val sep = t[i]
        if (sep != '.' && sep != ')') return null
        if (i + 1 >= t.length || t[i + 1] != ' ') return null
        return t.substring(i + 2).trim()
    }

    private fun isTableHeader(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        val header = lines[index]
        val sep = lines[index + 1]
        if (!header.contains('|')) return false
        val cells = splitRow(sep)
        if (cells.isEmpty()) return false
        // 分隔行每格必须是 --- / :--- / ---: / :---:
        return cells.all { cell ->
            val c = cell.trim()
            c.isNotEmpty() && c.all { it == '-' || it == ':' } && c.count { it == '-' } >= 3
        }
    }

    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split('|').map { it.trim() }
    }
}

/** 块级节点。 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class OrderedList(val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Divider : MdBlock
}

/** 行内片段。 */
sealed interface MdInline {
    data class Plain(val text: String) : MdInline
    data class Bold(val text: String) : MdInline
    data class Italic(val text: String) : MdInline
    data class Strike(val text: String) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val label: String, val url: String) : MdInline
}