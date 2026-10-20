package dev.praxis.core.workflow

/**
 * 受限条件表达式。
 *
 * ## 为什么不用现成表达式引擎
 *
 * 工作流定义会**持久化并可能被导入**（用户分享一个工作流给别人）。
 * 那种情况下，一个完整的脚本表达式引擎（JS/Lua/SpEL）就是一个代码执行入口：
 * 别人分享一个「工作流」就等于往你设备上放了一段可执行代码。
 * 这不是能力问题，是安全边界问题。
 *
 * 所以只支持四种形式，全部可穷举、可静态校验：
 *
 * ```
 * {{var}}                 真值判断（非空且不是 false/0）
 * {{var}} == <字面量>      相等
 * {{var}} != <字面量>      不等
 * {{var}} contains <字面量> 包含
 * ```
 *
 * 解析失败一律返回 false 而不是抛异常：工作流是用户手改的配置，
 * 一个写错的表达式不该让整个自动化崩掉，而应该让它走 else 分支并在轨迹里留痕。
 */
object ConditionExpression {

    /**
     * 求值。
     *
     * @param expression 形如 `{{status}} == ok`
     * @param variables 当前上下文
     */
    fun evaluate(expression: String, variables: Map<String, String>): Boolean {
        val text = expression.trim()
        if (text.isEmpty()) return false

        // 按表顺序匹配，首个命中生效（表里 contains 排最前，
        // 否则 `a contains b` 会被 == / != 先抢掉）。
        //
        // 用枚举而不是字符串比对：曾经这里比的是去掉空格的 "contains"，
        // 而表里存的是带空格的 " contains "，两者永不相等，
        // contains 静默退化为 false。枚举从类型上消除了这类错误。
        val match = OPERATORS.firstOrNull { text.contains(it.token) }
            ?: return truthy(resolve(text, variables))

        val left = text.substringBefore(match.token).trim()
        val right = text.substringAfter(match.token).trim()
        if (left.isEmpty() || right.isEmpty()) return false

        val actual = resolve(left, variables) ?: return false
        val expected = unquote(right)

        return when (match.kind) {
            OpKind.CONTAINS -> actual.contains(expected)
            OpKind.NE -> actual != expected
            OpKind.EQ -> actual == expected
        }
    }

    /**
     * 变量引用或字面量 → 实际值。
     *
     * 不是 `{{...}}` 形式就当作字面量原样返回（值本身写死在表达式里）。
     */
    internal fun resolve(token: String, variables: Map<String, String>): String? {
        val t = token.trim()
        if (!t.startsWith("{{") || !t.endsWith("}}")) return unquote(t)

        val key = t.removePrefix("{{").removeSuffix("}}").trim()
        if (key.isEmpty()) return null

        // 未定义的变量返回 null（而不是空串）：空串会让 `== ""` 这种判断
        // 把「没这个变量」误判为「变量存在但为空」。
        return variables[key]
    }

    /** 真值判断：非空、不是 false、不是 0。 */
    private fun truthy(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        val v = value.trim().lowercase()
        return v != "false" && v != "0" && v != "null"
    }

    /** 去掉成对的双引号或单引号。 */
    private fun unquote(raw: String): String {
        val s = raw.trim()
        if (s.length >= 2) {
            val first = s.first()
            val last = s.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length - 1)
            }
        }
        return s
    }

    /**
     * 操作符表。顺序重要：`contains` 必须排在 `==` / `!=` 前面。
     *
     * 双字母操作符排单字母前同理（虽然目前没有单字母操作符，
     * 但以后加 `>` 时这个顺序会保护它）。
     */
    private enum class OpKind { CONTAINS, NE, EQ }

    private data class Operator(val token: String, val kind: OpKind)

    private val OPERATORS = listOf(
        Operator(" contains ", OpKind.CONTAINS),
        Operator("!=", OpKind.NE),
        Operator("==", OpKind.EQ),
    )
}

/**
 * 模板插值：把 `{{key}}` 换为上下文里的值。
 *
 * 未定义的 key **原样保留** `{{key}}`，而不是换成空串。
 * 理由：工具参数里的 `{{readme}}` 万一拼错，原样发出去时用户能在日志里
 * 看到「咦这里有个 {{readme}} 没被替换」，而换成空串就变成静默的错误参数，
 * 工具可能拿空路径去删目录。
 */
fun renderTemplate(template: String, variables: Map<String, String>): String {
    if (template.isEmpty() || variables.isEmpty()) return template

    val out = StringBuilder(template.length)
    var i = 0
    while (i < template.length) {
        val open = template.indexOf("{{", i)
        if (open < 0) {
            out.append(template, i, template.length)
            break
        }
        val close = template.indexOf("}}", open + 2)
        if (close < 0) {
            // 没有闭合：剩余部分原样输出（不把用户的半截模板吃掉）。
            out.append(template, i, template.length)
            break
        }

        out.append(template, i, open)
        val key = template.substring(open + 2, close).trim()
        val value = variables[key]
        if (value != null) out.append(value) else out.append(template, open, close + 2)
        i = close + 2
    }
    return out.toString()
}
