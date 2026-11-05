package dev.praxis.core.spill

import kotlin.math.max

/**
 * 长文本的溢出保存结果。
 *
 * ## 什么是不透明定位符
 *
 * [locator] 是一串**不能从内容推出来**的标识（形如 `spill-7f3a9c1b`）。
 * 模型看到一个定位符，只能：
 *
 *  - 原样回传给 `read_spill` 去取正文；
 *  - 或者把它当成「这里还有更多内容」的标记。
 *
 * 它**不能**被模型猜出来、也不携带原文片段 —— 这一点是刻意的：
 * 如果定位符里含着内容前缀，模型可能把它当正文读进去，
 * 而那段内容其实已经在别处展示过了，等于重复占上下文。
 *
 * @param locator 不透明标识，交给模型用来取回正文。
 * @param totalBytes 原始字节数（模型据此判断「到底有多大」）。
 * @param totalLines 原始行数。
 * @param previewHead 保留的开头片段（策略允许时非空）。
 * @param previewTail 保留的结尾片段。
 */
data class SpilledText(
    val locator: String,
    val totalBytes: Int,
    val totalLines: Int,
    val previewHead: String,
    val previewTail: String,
) {
    /**
     * 拼给模型看的文本。
     *
     * ## 为什么必须写清「去哪里取、怎么取」
     *
     * 只说「输出太长已保存」是没用的 —— 模型不知道该调什么工具、
     * 也不知道定位符是什么。三样东西缺一不可：
     * 定位符、取回的工具名、原始规模。
     */
    fun toModelText(): String = buildString {
        appendLine("[输出过大，已保存到溢出存储]")
        appendLine("原文 $totalLines 行 / $totalBytes 字节。")
        appendLine("定位符：$locator")
        appendLine("取回方式：调用 read_spill 工具，参数 locator="$locator"。")
        if (previewHead.isNotBlank() || previewTail.isNotBlank()) {
            appendLine()
            appendLine("--- 开头片段 ---")
            appendLine(previewHead.trimEnd())
            if (previewTail.isNotBlank()) {
                appendLine("--- 结尾片段 ---")
                appendLine(previewTail.trimEnd())
            }
        }
    }
}

/**
 * 溢出存储策略：决定「什么该存、保存后留多少给模型看」。
 *
 * ## 为什么策略要按 token 预算而不是按字符数
 *
 * 字符数与 token 数的比例随语言变化很大（中文约 1 字 1 token，
 * 英文约 4 字符 1 token）。按字符数切，中文长命令与英文长日志
 * 会得到完全不同的预算效果。
 *
 * 这里用 [TokenEstimator] 的口径（与上下文预算同一套），保证
 * 「留多少给模型」与「占多少上下文」是同一个数。
 */
object SpillPolicy {

    /** 超过这个字符数就进入溢出存储。 */
    const val SPILL_THRESHOLD_CHARS = 16_000

    /** 保存后给模型看的开头片段上限（字符）。 */
    const val DEFAULT_HEAD_CHARS = 4_000

    /** 保存后给模型看的结尾片段上限（字符）。 */
    const val DEFAULT_TAIL_CHARS = 1_500

    /**
     * 判断是否需要溢出保存。
     *
     * ## 为什么用 `>= ` 而不是 `>`
     *
     * 阈值本身就是一个「已经太大了」的信号。差一个字符就整段塞进上下文
     * 没有意义，而让它走溢出路径的代价很小。
     */
    fun shouldSpill(text: String, thresholdChars: Int = SPILL_THRESHOLD_CHARS): Boolean =
        text.length >= thresholdChars

    /**
     * 从原文切出「开头 + 结尾」两段预览。
     *
     * ## 为什么两端都留
     *
     * 命令输出的**开头**通常是「它在干什么」（apt 的包列表、ls 的目录），
     * **结尾**通常是「结果如何」（错误信息、统计行）。
     * 中间被截掉的部分，恰恰是最不需要逐字看的重复内容。
     *
     * 只留开头会让模型看不到失败原因；只留结尾会丢失上下文。
     *
     * ## 为什么中间要写明「省略了多少」
     *
     * 不写清楚，模型会把「开头片段」和「结尾片段」当成连续内容读，
     * 得出错误的因果判断。
     */
    fun makePreview(
        text: String,
        headChars: Int = DEFAULT_HEAD_CHARS,
        tailChars: Int = DEFAULT_TAIL_CHARS,
    ): Pair<String, String> {
        // 文本没那么长时整段返回，不做无意义的切割。
        if (text.length <= headChars + tailChars) return text to ""
        val head = text.substring(0, headChars.coerceAtMost(text.length))
        val tailStart = max(0, text.length - tailChars)
        val tail = if (tailChars <= 0) "" else text.substring(tailStart)
        val omitted = text.length - head.length - tail.length
        // 用显式的省略行隔开两端，避免模型把它们读成连续的。
        val markedHead = head.trimEnd() + "\n\n…（中间省略 $omitted 字符）…\n\n"
        return markedHead to tail.trimStart()
    }
}
