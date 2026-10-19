package dev.praxis.core.ai

/**
 * 上下文超预算时的确定性压缩策略。
 *
 * 三条硬约束（都是从 Operit 那边踩出来的）：
 *  1. **argumentsJson 绝不截断**。截一半的 JSON 会让上游直接 400，
 *     重试也救不回来，比丢掉整段内容更糟。
 *  2. 保留首尾、砍中段。首部通常含声明与任务描述，尾部通常是最近进展。
 *  3. 纯函数、无随机、无 IO —— 同一输入必须得到同一输出，否则写不出可断言的单测，
 *     也解释不清「为什么这次请求变了」。
 *
 * 这里只做「留给模型看的那一份」，落库的原文一律不动。
 */
object CompactionPolicy {

    /** 普通文本：留前 200 字符 + 后 80 字符。 */
    const val TEXT_HEAD = 200
    const val TEXT_TAIL = 80

    /** 工具返回体：留前 140 字符 + 后 56 字符。 */
    const val TOOL_HEAD = 140
    const val TOOL_TAIL = 56

    private const val ELLIPSIS_PREFIX = " …[省略 "
    private const val ELLIPSIS_SUFFIX = " 字符]… "

    /**
     * 压缩普通文本（用户消息、模型回复、文档片段）。
     *
     * 长度不超过 `TEXT_HEAD + TEXT_TAIL` 时原样返回，不加任何标记。
     */
    fun compactText(text: String): String = compact(text, TEXT_HEAD, TEXT_TAIL)

    /** 压缩工具返回体。规则同上，只是阈值更紧。 */
    fun compactToolResult(text: String): String = compact(text, TOOL_HEAD, TOOL_TAIL)

    /**
     * 压缩工具调用参数。
     *
     * 恒定原样返回 —— 签名保留是为了调用方统一写法，
     * 让「绝不截断」这条约束在类型层面显式可见，而不是散落在调用点的注释里。
     */
    fun compactArguments(argumentsJson: String): String = argumentsJson

    private fun compact(text: String, head: Int, tail: Int): String {
        if (text.length <= head + tail) return text

        // 不在代理对中间切断：切点若落在低位代理上，往后挪一位。
        var headEnd = head
        if (headEnd < text.length && text[headEnd].isLowSurrogate()) headEnd++

        var tailStart = text.length - tail
        if (tailStart > 0 && text[tailStart].isLowSurrogate()) tailStart++

        val omitted = tailStart - headEnd
        if (omitted <= 0) return text

        return buildString(headEnd + tail) {
            append(text, 0, headEnd)
            append(ELLIPSIS_PREFIX)
            append(omitted)
            append(ELLIPSIS_SUFFIX)
            append(text, tailStart, text.length)
        }
    }

    private fun Char.isLowSurrogate(): Boolean = this in '\uDC00'..'\uDFFF'
}
