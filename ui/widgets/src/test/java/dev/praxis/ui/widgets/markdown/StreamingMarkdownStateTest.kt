package dev.praxis.ui.widgets.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式增量解析。
 *
 * ## 这些用例锁什么
 *
 * 增量解析的失效**在屏幕上几乎看不出来** —— 它要么「偶尔多一行」，
 * 要么「代码块被拆成两段」。所以必须用纯 JVM 测试把边界钉死，
 * 而不是靠肉眼观察渲染结果。
 *
 * 三条核心不变量：
 *  1. **等价性**：增量结果必须与「整段解析」逐块相等（这是正确性的根）；
 *  2. **不重复**：已冻结的块不能在下一次结果里出现两次；
 *  3. **可恢复**：文本变短（重新生成/编辑）时必须整体重置，不能拼接两段。
 */
class StreamingMarkdownStateTest {

    /** 逐块比较，失败时把差异打出来（只看 size 不够定位）。 */
    private fun assertSameBlocks(expected: List<MdBlock>, actual: List<MdBlock>) {
        assertEquals("块数不同：expected=$expected actual=$actual", expected.size, actual.size)
        expected.zip(actual).forEachIndexed { i, (e, a) ->
            assertEquals("第 $i 块不同", e, a)
        }
    }

    @Test
    fun `逐字符喂入与整段解析等价`() {
        val text = "## 标题\n\n第一段内容。\n\n- 项目一\n- 项目二\n\n最后一段。"
        val state = StreamingMarkdownState()
        var last: List<MdBlock> = emptyList()
        for (i in 1..text.length) {
            last = state.blocksFor(text.substring(0, i))
        }
        assertSameBlocks(MarkdownParser.parse(text), last)
    }

    @Test
    fun `代码块内部空行不当作切点`() {
        // 两段之间是空行，但代码块里的空行不能当块边界 ——
        // 切错了代码块会被拆成「未闭合围栏」+「裸文本」，渲染成两段。
        val code = "```kotlin\nfun a() {}\n\nfun b() {}\n```"
        val text = "引言段。\n\n$code\n\n收尾段。"
        val state = StreamingMarkdownState()
        var last: List<MdBlock> = emptyList()
        for (i in 1..text.length) last = state.blocksFor(text.substring(0, i))
        assertSameBlocks(MarkdownParser.parse(text), last)

        // 代码块必须是**一个**完整的块，内容含那个空行。
        val codes = last.filterIsInstance<MdBlock.Code>()
        assertEquals(1, codes.size)
        assertTrue(codes[0].code.contains("fun a()"))
        assertTrue(codes[0].code.contains("fun b()"))
    }

    @Test
    fun `未闭合围栏在流式中被宽容处理`() {
        // 流式写到一半必然出现「开了围栏没关」。
        // 解析器对此宽容（吃到文末），增量状态也必须如此，
        // 否则用户会看到代码块在写完 ``` 之前是普通文本。
        val state = StreamingMarkdownState()
        val partial = "说明\n\n```python\nprint(1)\n"
        val blocks = state.blocksFor(partial)
        val code = blocks.filterIsInstance<MdBlock.Code>()
        assertEquals(1, code.size)
        assertTrue(code[0].code.contains("print(1)"))
    }

    @Test
    fun `冻结后追加新块不重复旧块`() {
        val state = StreamingMarkdownState()
        val first = state.blocksFor("第一段。\n\n第二段。")
        assertEquals(2, first.size)

        val second = state.blocksFor("第一段。\n\n第二段。\n\n第三段。")
        assertEquals(3, second.size)
        // 前两块必须还是原来那两块（同一内容，且没有出现重复）。
        assertSameBlocks(MarkdownParser.parse("第一段。\n\n第二段。\n\n第三段。"), second)
    }

    @Test
    fun `文本变短时整体重置`() {
        val state = StreamingMarkdownState()
        state.blocksFor("很长的一段旧回答。\n\n还有第二段。\n\n第三段。")
        // 「重新生成」：全文换成一段短得多的新内容。
        val after = state.blocksFor("新回答。")
        assertSameBlocks(MarkdownParser.parse("新回答。"), after)
        // 旧内容一个字都不该残留。
        assertFalse(after.any { it is MdBlock.Paragraph && it.text.contains("旧回答") })
    }

    @Test
    fun `空文本与纯空白返回空列表`() {
        val state = StreamingMarkdownState()
        assertTrue(state.blocksFor("").isEmpty())
        assertTrue(state.blocksFor("   \n\n  ").isEmpty())
    }

    @Test
    fun `没有空行时不冻结但仍能正确解析`() {
        // 一整段没有空行长文本：stableCut 会返回 0（找不到安全切点），
        // 于是每次都整段解析 —— 正确性优先，性能退化成原样。
        val text = "这是一段没有任何空行的长文本，" + "内容继续。".repeat(30)
        val state = StreamingMarkdownState()
        val blocks = state.blocksFor(text)
        assertSameBlocks(MarkdownParser.parse(text), blocks)
    }

    @Test
    fun `表格跨多次追加后仍是一个表格`() {
        val rows = "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |"
        val text = "说明。\n\n$rows"
        val state = StreamingMarkdownState()
        var last: List<MdBlock> = emptyList()
        for (i in 1..text.length) last = state.blocksFor(text.substring(0, i))
        assertSameBlocks(MarkdownParser.parse(text), last)
        val tables = last.filterIsInstance<MdBlock.Table>()
        assertEquals(1, tables.size)
        assertEquals(2, tables[0].rows.size)
    }

    @Test
    fun `reset 后可以重新开始`() {
        val state = StreamingMarkdownState()
        state.blocksFor("旧内容。\n\n旧第二段。")
        state.reset()
        val fresh = state.blocksFor("全新的。")
        assertSameBlocks(MarkdownParser.parse("全新的。"), fresh)
    }
}