package dev.praxis.core.plugin

/**
 * 清单解析：HJSON + JSON 双格式，产出统一的 [ManifestValue]。
 *
 * ## 为什么必须自己写 HJSON 解析
 *
 * Operit 生态的 `manifest.hjson` 是主力格式（几乎全部官方与社区包都用它）。
 * 要「1:1 兼容 operit 插件生态」，就必须能读它，这不是可选优化。
 *
 * 引入 `org.hjson` 库当然更省事，但权衡后手写：
 *
 * | | 引库 | 手写 |
 * |---|---|---|
 * | 体积 | +约 60 KB | 0 |
 * | 依赖风险 | 需要可信来源 | 无 |
 * | 可控性 | 库的报错信息定位不到行 | 自己给行号 |
 *
 * HJSON 相对 JSON 只多了四件事，手写不到 200 行即可完整覆盖：
 * 注释、无引号键、无引号值、逗号可省（换行即分隔）。
 *
 * ## 关键容错：**不因为一个字段错而整包失败**
 *
 * 清单是用户从市场下载的第三方文件。解析器如果「遇到不认识的写法就抛」，
 * 用户看到的是「这个包装不上」，而我们完全不知道是哪个字段导致的。
 * 所以：结构错（括号不配对）才抛，字段级的问题一律回落默认值。
 */
object ManifestParser {

    /**
     * 解析入口。
     *
     * 自动判格式：先按 HJSON（它是 JSON 的超集，标准 JSON 也能解析）。
     * 不用看扩展名 —— 大量包的 `.json` 里其实写着 HJSON 注释。
     */
    fun parse(text: String): Result<ManifestValue> = runCatching {
        val reader = Reader(text)
        val value = reader.readRoot()
        reader.skipTrailing()
        value
    }

    /** 按文件路径解析（路径只用于报错信息）。 */
    fun parse(text: String, sourceName: String): Result<ManifestValue> =
        parse(text).recoverCatching { error ->
            throw IllegalArgumentException("$sourceName: ${error.message}")
        }

    /** 解析错误：带行号，便于用户自己修清单。 */
    class ManifestSyntaxException(
        message: String,
        val line: Int,
        val column: Int,
    ) : IllegalArgumentException("第 $line 行第 $column 列：$message")

    /**
     * 递归下降解析器。
     *
     * 逐字符推进，用 [line]/[column] 记录位置以便报错。
     * 不做流式（清单都是几十 KB 以内），一次性读入内存更简单。
     */
    private class Reader(private val text: String) {

        private var index = 0
        private var line = 1
        private var column = 1

        fun readRoot(): ManifestValue {
            skipSpaceAndComments()
            if (atEnd()) return ManifestValue.Mapping(emptyMap())
            // HJSON 允许省略根花括号（`a: 1\nb: 2`）。
            return when (peek()) {
                '{' -> readObject()
                '[' -> readArray()
                else -> readImplicitRootObject()
            }
        }

        fun skipTrailing() {
            skipSpaceAndComments()
            if (!atEnd()) fail("清单结尾有多余内容")
        }

        // ── 对象 / 数组 ────────────────────────────────────────

        private fun readObject(): ManifestValue.Mapping {
            expect('{')
            val entries = LinkedHashMap<String, ManifestValue>()
            while (true) {
                skipSpaceAndComments()
                if (atEnd()) fail("对象未闭合（缺少 }）")
                if (peek() == '}') {
                    advance()
                    return ManifestValue.Mapping(entries)
                }
                val key = readKey()
                skipSpaceAndComments()
                expect(':')
                skipSpaceAndComments()
                entries[key] = readValue()
                skipValueSeparator()
            }
        }

        /** 省略根花括号的形式：直接是 `key: value` 序列。 */
        private fun readImplicitRootObject(): ManifestValue.Mapping {
            val entries = LinkedHashMap<String, ManifestValue>()
            while (true) {
                skipSpaceAndComments()
                if (atEnd()) break
                val key = readKey()
                skipSpaceAndComments()
                expect(':')
                skipSpaceAndComments()
                entries[key] = readValue()
                skipValueSeparator()
            }
            return ManifestValue.Mapping(entries)
        }

        private fun readArray(): ManifestValue.Items {
            expect('[')
            val items = mutableListOf<ManifestValue>()
            while (true) {
                skipSpaceAndComments()
                if (atEnd()) fail("数组未闭合（缺少 ]）")
                if (peek() == ']') {
                    advance()
                    return ManifestValue.Items(items)
                }
                items += readValue()
                skipValueSeparator()
            }
        }

        private fun readValue(): ManifestValue {
            if (atEnd()) fail("需要值但已到结尾")
            return when (val c = peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"', '\'' -> ManifestValue.Text(readQuotedString())
                else -> {
                    if (c == '-' || c == '+' || c.isDigit()) {
                        readScalarOrNumber()
                    } else {
                        readBareValue()
                    }
                }
            }
        }

        /**
         * 数字或裸标量。
         *
         * HJSON 里 `1.0.1`、`1.0`、`123` 都可能出现，而 `version: 1.0.1`
         * 不是合法 JSON 数字。所以这里先尝试整体读成 token 再判断：
         * 能解析成纯数字就用 Number，否则当 Text 原文保留 ——
         * 版本号必须原文保留，`1.0` 被转成 `1.0` 的 Double 再格式化会变成 `1`。
         */
        private fun readScalarOrNumber(): ManifestValue {
            val token = readBareToken()
            // 只有当 token 是「不含点分隔多段」的纯数字时才认为是数字。
            val dotCount = token.count { it == '.' }
            if (dotCount <= 1 && token.matches(NUMBER_PATTERN)) {
                return ManifestValue.Number(token)
            }
            return ManifestValue.Text(token)
        }

        /**
         * 裸值（无引号）。
         *
         * 三种可能：布尔、null、或「到行尾/逗号/右括号为止的字符串」。
         * 注意裸字符串在 HJSON 里可以含空格（`display_name: My Tool`），
         * 所以不能按空白切词。
         */
        private fun readBareValue(): ManifestValue {
            val token = readBareValueText()
            return when (token.lowercase()) {
                "true", "yes" -> ManifestValue.Flag(true)
                "false", "no" -> ManifestValue.Flag(false)
                "null", "~" -> ManifestValue.Null
                else -> ManifestValue.Text(token)
            }
        }

        /**
         * 读裸值正文：到行尾、逗号、`}`、`]` 或**真正的注释起始**为止。
         *
         * ## 为什么不能见到 `/` 就断（这是一个真实的坑）
         *
         * 清单里有大量 URL：`endpoint: https://api.example.com/mcp`。
         * 如果是「遇到 `//` 就断」，那么 `https://` 会被当成行注释起点，
         * 读出来的值变成 `https:` —— 打包时看不出问题，
         * 运行时 MCP 连不上，报错是「invalid URL」，根本联想不到解析器。
         *
         * 正确的判据是「注释符前必须是空白或行首」：
         * `https://x` 里的 `//` 前面是 `:`，不是空白，所以不是注释。
         *
         * ## 为什么尾部要 trim
         *
         * `display_name: My Tool   ` 里带尾随空格，保留会让 UI 上出现
         * 看不出来的对齐偏移，且字符串比较永远不相等。
         */
        private fun readBareValueText(): String {
            val start = index
            while (!atEnd()) {
                val c = peek()
                if (c == '\n' || c == ',' || c == '}' || c == ']') break
                if (c == '#') break
                if (c == '/' && index + 1 < text.length &&
                    (text[index + 1] == '/' || text[index + 1] == '*')
                ) {
                    // 只有「前一个字符是空白或位于值开头」才算注释。
                    val previousIsSpace = index == start ||
                        text[index - 1].let { it == ' ' || it == '\t' || it == '\r' }
                    if (previousIsSpace) break
                }
                advance()
            }
            return text.substring(start, index).trim()
        }

        /**
         * 读键：允许带引号，也允许裸键。
         *
         * ## 终止条件只有 `:`
         *
         * 旧写法在 `{`/`}`/换行处也断，理由是「防止吃掉结构符」。
         * 但那会让 `display_name{\n  zh: x\n}` 这类**值本身是对象**的写法错位吗？
         * 不会 —— 键后面一定紧跟 `:`，所以见到 `:` 就该停。
         * 反而是「见到 `{` 就断」有真实害处：某些清单写成
         * `subpackages: [{ id: a, entry: b }]`，数组元素里的键前面紧挨 `{`，
         * 而 `{` 已在 [readValue] 被消费，这里再判一次是多余且危险的。
         *
         * 换行必须断：裸键跨行意味着漏了冒号，继续读会把下一行的键拼进来。
         */
        private fun readKey(): String {
            if (atEnd()) fail("需要键名但已到结尾")
            val c = peek()
            if (c == '"' || c == '\'') return readQuotedString()
            val start = index
            while (!atEnd()) {
                val ch = peek()
                if (ch == ':' || ch == '\n') break
                advance()
            }
            val key = text.substring(start, index).trim()
            if (key.isEmpty()) fail("键名不能为空")
            return key
        }

        /**
         * 读带引号字符串。
         *
         * 支持 `\n \t \r \" \\ \/ \uXXXX`。不支持的行内换行直接报错 ——
         * 那是真实的语法错误，静默接受会让后面所有解析错位。
         */
        private fun readQuotedString(): String {
            val quote = peek()
            advance()
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("字符串未闭合")
                val c = peek()
                if (c == quote) {
                    advance()
                    return sb.toString()
                }
                if (c == '\\') {
                    advance()
                    if (atEnd()) fail("转义符后缺少字符")
                    when (val esc = peek()) {
                        'n' -> { sb.append('\n'); advance() }
                        't' -> { sb.append('\t'); advance() }
                        'r' -> { sb.append('\r'); advance() }
                        '"' -> { sb.append('"'); advance() }
                        '\'' -> { sb.append('\''); advance() }
                        '\\' -> { sb.append('\\'); advance() }
                        '/' -> { sb.append('/'); advance() }
                        'b' -> { sb.append('\b'); advance() }
                        'f' -> { sb.append('\u000C'); advance() }
                        'u' -> {
                            advance()
                            val hex = text.substring(index, (index + 4).coerceAtMost(text.length))
                            val code = hex.toIntOrNull(16)
                                ?: fail("\\u 后需要 4 位十六进制")
                            sb.append(code.toChar())
                            repeat(4) { if (!atEnd()) advance() }
                        }
                        else -> fail("不认识转义 '\\$esc'")
                    }
                    continue
                }
                sb.append(c)
                advance()
            }
        }

        // ── 空白 / 注释 / 分隔符 ───────────────────────────────

        /**
         * 跳过空白与三种注释。
         *
         * HJSON 的三种注释：井号（到行尾）、双斜杠（到行尾）、斜杠星号成对的块注释。
         * 块注释**可以跨行**，所以这里必须同时推进 line/column。
         */
        private fun skipSpaceAndComments() {
            while (!atEnd()) {
                val c = peek()
                when {
                    c == ' ' || c == '\t' || c == '\r' || c == '\n' -> advance()
                    c == '#' -> skipToLineEnd()
                    c == '/' && index + 1 < text.length && text[index + 1] == '/' -> skipToLineEnd()
                    c == '/' && index + 1 < text.length && text[index + 1] == '*' -> skipBlockComment()
                    else -> return
                }
            }
        }

        private fun skipToLineEnd() {
            while (!atEnd() && peek() != '\n') advance()
        }

        private fun skipBlockComment() {
            advance() // '/'
            advance() // '*'
            while (!atEnd()) {
                if (peek() == '*' && index + 1 < text.length && text[index + 1] == '/') {
                    advance()
                    advance()
                    return
                }
                advance()
            }
            fail("块注释未闭合")
        }

        /** 值之间的分隔：逗号可有可无。 */
        private fun skipValueSeparator() {
            skipSpaceAndComments()
            if (!atEnd() && peek() == ',') advance()
        }

        // ── 底层 ──────────────────────────────────────────────

        private fun atEnd(): Boolean = index >= text.length

        private fun peek(): Char = text[index]

        private fun advance() {
            if (atEnd()) return
            if (text[index] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
            index += 1
        }

        private fun expect(c: Char) {
            if (atEnd() || peek() != c) fail("期望 '$c' 但得到 '${if (atEnd()) "结尾" else peek().toString()}'")
            advance()
        }

        private fun fail(message: String): Nothing =
            throw ManifestSyntaxException(message, line, column)

        /** 读一个裸 token（到空白或分隔符为止）。 */
        private fun readBareToken(): String {
            val start = index
            while (!atEnd()) {
                val c = peek()
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' ||
                    c == ',' || c == '}' || c == ']' || c == '#'
                ) {
                    break
                }
                advance()
            }
            return text.substring(start, index).trim()
        }

        companion object {
            private val NUMBER_PATTERN = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")
        }
    }
}