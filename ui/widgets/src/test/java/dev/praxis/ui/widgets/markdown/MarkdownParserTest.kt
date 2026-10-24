package dev.praxis.ui.widgets.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 解析器的回归护栏。
 *
 * ## 为什么这些用例值得写
 *
 * 解析器是纯函数，但它的**错误方式是「静默吞内容」**：
 * 少一个前进分支就是死循环，多一个贪婪匹配就把后面的正文吃掉。
 * 这类 bug 在界面上表现为「AI 回复缺了一段」，用户根本不会想到是 Markdown 的问题。
 * 所以每个块类型都要有一条「能正确识别」+ 一条「边界不误判」。
 */
class MarkdownParserTest {

    // ── 块级 ────────────────────────────────────────────────────────

    @Test
    fun `标题按级别解析`() {
        val blocks = MarkdownParser.parse("# 一级\n### 三级")
        assertEquals(2, blocks.size)
        assertEquals(MdBlock.Heading(1, "一级"), blocks[0])
        assertEquals(MdBlock.Heading(3, "三级"), blocks[1])
    }

    /** `#hashtag` 不是标题：`#` 后必须有空格。 */
    @Test
    fun `井号紧跟文字不算标题`() {
        val blocks = MarkdownParser.parse("#标签 不是标题")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun `围栏代码块保留语言与内容`() {
        val blocks = MarkdownParser.parse("```kotlin\nval a = 1\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1", code.code)
    }

    /**
     * 未闭合围栏必须**吃到文末并保留内容**。
     *
     * 这条守的是流式输出：AI 正在写代码块时，界面上每 100ms 就可能出现
     * 「``` 已到、结尾 ``` 还没到」的中间态。若解析器在无闭合时丢弃代码，
     * 用户会看到代码块整段闪烁消失再出现。
     */
    @Test
    fun `未闭合围栏仍然保留内容`() {
        val blocks = MarkdownParser.parse("```python\nprint(1)")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertEquals("print(1)", code.code)
    }

    @Test
    fun `无序列表与有序列表分别解析`() {
        val bullet = MarkdownParser.parse("- 甲\n- 乙")
        assertEquals(1, bullet.size)
        assertEquals(listOf("甲", "乙"), (bullet[0] as MdBlock.BulletList).items)

        val ordered = MarkdownParser.parse("1. 一\n2. 二")
        assertEquals(1, ordered.size)
        assertEquals(listOf("一", "二"), (ordered[0] as MdBlock.OrderedList).items)
    }

    /** 列表项的缩进续行要并进上一项，而不是变成第 2 项。 */
    @Test
    fun `列表缩进续行并入上一项`() {
        val blocks = MarkdownParser.parse("- 第一行\n  续行")
        val list = blocks[0] as MdBlock.BulletList
        assertEquals(1, list.items.size)
        assertTrue(list.items[0].contains("第一行"))
        assertTrue(list.items[0].contains("续行"))
    }

    @Test
    fun `引用连续行合成一块`() {
        val blocks = MarkdownParser.parse("> 甲\n> 乙")
        assertEquals(1, blocks.size)
        assertEquals("甲\n乙", (blocks[0] as MdBlock.Quote).text)
    }

    @Test
    fun `表格识别表头与数据行`() {
        val blocks = MarkdownParser.parse("| A | B |\n|---|---|\n| 1 | 2 |")
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("A", "B"), table.header)
        assertEquals(1, table.rows.size)
        assertEquals(listOf("1", "2"), table.rows[0])
    }

    /** 只有竖线没有分隔行 → 不是表格，应当是段落（不能把普通文本吃掉）。 */
    @Test
    fun `没有分隔行不判为表格`() {
        val blocks = MarkdownParser.parse("a | b")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun `分隔线与段落区分`() {
        val blocks = MarkdownParser.parse("上\n\n---\n\n下")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Divider, blocks[1])
    }

    /** 段落里的多个非空行合成一段，保留换行符。 */
    @Test
    fun `连续段落行合为一块`() {
        val blocks = MarkdownParser.parse("第一行\n第二行")
        assertEquals(1, blocks.size)
        assertEquals("第一行\n第二行", (blocks[0] as MdBlock.Paragraph).text)
    }

    /** 解析必须前进：任何输入都不能死循环。这里用一段混合垃圾内容兜底。 */
    @Test
    fun `混合内容不死循环且不丢段落`() {
        val src = """
            # 标题
            段落一
            - 项
            1. 有序
            > 引用
            ```
            code
            ```
            ---
            | a | b |
            |---|---|
            | 1 | 2 |
        """.trimIndent()
        val blocks = MarkdownParser.parse(src)
        // 七种块都应出现（标题/段落/无序/有序/引用/代码/分隔线/表格）
        assertTrue(blocks.any { it is MdBlock.Heading })
        assertTrue(blocks.any { it is MdBlock.Paragraph })
        assertTrue(blocks.any { it is MdBlock.BulletList })
        assertTrue(blocks.any { it is MdBlock.OrderedList })
        assertTrue(blocks.any { it is MdBlock.Quote })
        assertTrue(blocks.any { it is MdBlock.Code })
        assertTrue(blocks.any { it is MdBlock.Divider })
        assertTrue(blocks.any { it is MdBlock.Table })
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(MarkdownParser.parse("").isEmpty())
        assertTrue(MarkdownParser.parse("   \n\n  ").isEmpty())
    }

    // ── 行内 ────────────────────────────────────────────────────────

    @Test
    fun `行内粗体斜体删除线`() {
        val parts = MarkdownParser.parseInline("**粗** *斜* ~~删~~")
        assertTrue(parts.any { it is MdInline.Bold && it.text == "粗" })
        assertTrue(parts.any { it is MdInline.Italic && it.text == "斜" })
        assertTrue(parts.any { it is MdInline.Strike && it.text == "删" })
    }

    /** 行内代码里的星号不能被当成粗体 —— 这是 `parseInline` 里最关键的顺序约束。 */
    @Test
    fun `行内代码优先于粗体`() {
        val parts = MarkdownParser.parseInline("看 `a**b**c` 这里")
        val code = parts.filterIsInstance<MdInline.Code>().single()
        assertEquals("a**b**c", code.text)
        assertTrue(parts.none { it is MdInline.Bold })
    }

    @Test
    fun `行内链接拆出标签与地址`() {
        val parts = MarkdownParser.parseInline("见 [文档](https://example.com/a)")
        val link = parts.filterIsInstance<MdInline.Link>().single()
        assertEquals("文档", link.label)
        assertEquals("https://example.com/a", link.url)
    }

    /** 未闭合的 `**` 不能吞掉后面的文字，应当原样保留为纯文本。 */
    @Test
    fun `未闭合强调符退化为纯文本`() {
        val parts = MarkdownParser.parseInline("价格 ** 未知")
        val text = parts.joinToString("") {
            when (it) {
                is MdInline.Plain -> it.text
                is MdInline.Bold -> it.text
                is MdInline.Italic -> it.text
                is MdInline.Strike -> it.text
                is MdInline.Code -> it.text
                is MdInline.Link -> it.label
            }
        }
        assertTrue(text.contains("价格"))
        assertTrue(text.contains("未知"))
        assertTrue(text.contains("**"))
    }
}