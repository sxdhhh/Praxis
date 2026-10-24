package dev.praxis.feature.chat

import dev.praxis.ui.widgets.markdown.MarkdownParser
import dev.praxis.ui.widgets.markdown.MdBlock
import dev.praxis.ui.widgets.markdown.MdInline

/**
 * Markdown → 纯文本（对齐 Operit `markdownToPlainTextForCopy`）。
 *
 * ## 为什么复制预览表需要它
 *
 * 上游的复制预览有三档：**纯文本 / Markdown 源 / XML 源**。
 * 前两档的差别就是「有没有 Markdown 记号」：
 *
 * ```
 * ## 标题            →  标题
 * - 项目一           →  • 项目一
 * **粗体**           →  粗体
 * [文档](https://x)  →  文档 (https://x)
 * ```
 *
 * 用户想粘到微信 / 备忘录时，纯文本档才是合适的；想粘回 Markdown 编辑器
 * 时才用源档。少一档就等于强迫用户自己删记号。
 *
 * ## 为什么复用项目自己的 [MarkdownParser] 而不是抄上游那 14 个文件
 *
 * 上游的 `MarkdownPlainTextRenderer` 依赖它的 Canvas 节点树
 * （`MarkdownNodeStable` / `NativeMarkdownSplitter` / `MarkdownProcessorType`），
 * 那是 7900 行的渲染管线。这里的 `MarkdownParser` 是纯函数、零 Compose 依赖，
 * 块级 + 行内的语义覆盖范围与聊天回复实际出现的语法一致。
 *
 * ## 与上游的一处刻意差异：公式不做 MathML 转换
 *
 * 上游会调 `LatexMathMlConverter.convertAll` 把 `$…$` 转成 MathML 文本。
 * Praxis 没有那个转换器，且聊天里公式出现频率远低于代码/列表 ——
 * 这里原样保留公式源码（`$x^2$`），比丢掉它更好。
 */
object ChatCopyText {

    /** 把 Markdown 转成可读纯文本（保留段落与列表结构，去掉记号）。 */
    fun markdownToPlainText(markdown: String): String {
        if (markdown.isBlank()) return ""
        val blocks = MarkdownParser.parse(markdown)
        return joinBlocks(blocks).trim()
    }

    /**
     * 逐个拼接块。
     *
     * ## 列表项之间只留一个换行
     *
     * 用空行分隔会让一个 5 项列表在纯文本里变成 5 个「段落」，
     * 粘到聊天框后行距很大。相邻两个列表项用单换行、其余用空行 ——
     * 与上游 `joinBlocks` 的判断一致（`bothListItems`）。
     */
    private fun joinBlocks(blocks: List<MdBlock>): String {
        val sb = StringBuilder()
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                val prev = blocks[index - 1]
                sb.append(if (isListItem(prev) && isListItem(block)) "\n" else "\n\n")
            }
            sb.append(renderBlock(block))
        }
        return sb.toString()
    }

    private fun isListItem(block: MdBlock): Boolean =
        block is MdBlock.BulletList || block is MdBlock.OrderedList

    private fun renderBlock(block: MdBlock): String = when (block) {
        is MdBlock.Heading -> inline(block.text)
        is MdBlock.Paragraph -> inline(block.text)
        // 代码块保留内容与围栏语言标注：纯文本里语言名是唯一能表明
        // 「这段是什么语言」的信息，去掉后代码就失去了上下文。
        is MdBlock.Code -> {
            val body = block.code
            if (block.language.isNullOrBlank()) body else "${block.language}\n$body"
        }
        is MdBlock.BulletList -> block.items.joinToString("\n") { "• " + inline(it) }
        is MdBlock.OrderedList -> block.items
            .mapIndexed { i, item -> "${i + 1}. " + inline(item) }
            .joinToString("\n")
        is MdBlock.Quote -> block.text.lineSequence()
            .joinToString("\n") { inline(it) }
        is MdBlock.Table -> buildString {
            append(block.header.joinToString("\t") { inline(it) })
            block.rows.forEach { row ->
                append('\n')
                append(row.joinToString("\t") { inline(it) })
            }
        }
        MdBlock.Divider -> ""
    }

    /** 行内片段 → 纯文本。 */
    private fun inline(text: String): String =
        MarkdownParser.parseInline(text).joinToString("") { piece ->
            when (piece) {
                is MdInline.Plain -> piece.text
                is MdInline.Bold -> piece.text
                is MdInline.Italic -> piece.text
                is MdInline.Strike -> piece.text
                is MdInline.Code -> piece.text
                // 链接保留 URL：纯文本里「点不了」，没有 URL 就等于丢了目标。
                is MdInline.Link ->
                    if (piece.url.isBlank()) piece.label else "${piece.label} (${piece.url})"
            }
        }
}