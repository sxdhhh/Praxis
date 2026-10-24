package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摘要输入瘦身（1:1 对齐 Operit `AIMessageManager.summarizeMemory` 的 condense*）。
 *
 * 这一层的价值全在「压掉多少」与「有没有丢掉关键结构」两个点上，
 * 所以断言都针对具体数字与具体片段。
 */
class SummaryCondenserTest {

    // ── headTail 的三个边界 ──────────────────────────────────────

    @Test
    fun `短文本原样返回`() {
        assertEquals("abc", SummaryCondenser.headTail("abc", 10, 5))
    }

    @Test
    fun `超长文本保留头尾并插省略号`() {
        val text = "A".repeat(50) + "B".repeat(50)
        val out = SummaryCondenser.headTail(text, 5, 3)
        assertTrue(out.startsWith("AAAAA"))
        assertTrue(out.endsWith("BBB"))
        assertTrue(out.contains("..."))
        // 5 + 3 + 3 = 11 字符上限。
        assertEquals(11, out.length)
    }

    @Test
    fun `头尾都为 0 时返回省略号`() {
        assertEquals("...", SummaryCondenser.headTail("x".repeat(100), 0, 0))
    }

    @Test
    fun `只留头时省略号在尾部`() {
        val out = SummaryCondenser.headTail("x".repeat(100), 5, 0)
        assertEquals("xxxxx...", out)
    }

    @Test
    fun `只留尾时省略号在头部`() {
        val out = SummaryCondenser.headTail("x".repeat(100), 0, 5)
        assertEquals("...xxxxx", out)
    }

    @Test
    fun `空白被折叠成单空格`() {
        assertEquals("a b c", SummaryCondenser.normalizeWhitespace("a\n\n  b\t\rc"))
    }

    // ── 用户消息 ────────────────────────────────────────────────

    @Test
    fun `用户消息的附件换成占位而不是删除`() {
        val raw = "看这个 <workspace_attachment>大量内容</workspace_attachment> 谢谢"
        val out = SummaryCondenser.condenseUser(raw)
        // 附件本身消失。
        assertFalse(out.contains("大量内容"))
        // 但「带过附件」这个事实保留。
        assertTrue(out.contains("已省略"))
        assertTrue(out.contains("看这个"))
    }

    @Test
    fun `用户消息里的工具结果压成元信息加预览`() {
        // 闭合标签用运行时拼接：源码里不出现连续的尖括号序列，
        // 便于在传输/工具链里保持可读且不被误解析。
        val open = "<tool_result name=\"read_file\" status=\"success\">"
        val raw = open + "文件内容".repeat(50) + "</tool_result>"
        val out = SummaryCondenser.condenseUser(raw)
        assertTrue(out.contains("success"))
        // 长内容被截断（140 + 56 + 3 = 199 上限）。
        assertTrue(out.length < raw.length)
    }

    // ── 助手消息 ────────────────────────────────────────────────

    @Test
    fun `思考块被剥掉`() {
        val raw = "<think>自言自语很久很久</think>真正的回答"
        val out = SummaryCondenser.condenseAssistant(raw)
        assertFalse(out.contains("自言自语"))
        assertTrue(out.contains("真正的回答"))
    }

    @Test
    fun `空内容返回占位`() {
        assertEquals("[空]", SummaryCondenser.condenseAssistant("   "))
    }

    @Test
    fun `工具调用压成一行元信息`() {
        val raw = """
            我来看一下文件。
            <tool name="read_file"><param name="path">/root/a.kt</param></tool>
            读到了内容。
        """.trimIndent()
        val out = SummaryCondenser.condenseAssistant(raw)
        assertTrue("缺少工具名：$out", out.contains("read_file"))
        assertTrue(out.contains("path"))
        // 三段应都在（文本 + 工具 + 文本）。
        assertTrue(out.contains("我来看一下文件"))
        assertTrue(out.contains("读到了内容"))
    }

    @Test
    fun `工具结果带上成功或失败标记`() {
        val ok = SummaryCondenser.condenseAssistant(
            "<tool_result name=\"t\" status=\"success\"><content>好</content></tool_result>",
        )
        assertTrue(ok.contains("成功"))
        val bad = SummaryCondenser.condenseAssistant(
            "<tool_result name=\"t\" status=\"error\"><error>坏了</error></tool_result>",
        )
        assertTrue(bad.contains("失败"))
    }

    @Test
    fun `最后一段文本获得更大预算`() {
        // 构造「中间长文本 + 工具 + 末尾长文本」。末尾那段应保留更多字符。
        val midText = "中".repeat(1000)
        val endText = "末".repeat(1000)
        val raw = "$midText<tool name=\"t\"><param name=\"p\">v</param></tool>$endText"
        val out = SummaryCondenser.condenseAssistant(raw)
        val midKept = out.windowed(1).count { it == "中" }
        val endKept = out.windowed(1).count { it == "末" }
        // 中间 120+48=168 上限，末尾 280+120=400 上限。
        assertTrue("中间保留 $midKept", midKept <= 168)
        assertTrue("末尾保留 $endKept（应大于中间的 168）", endKept > 168)
    }

    @Test
    fun `片段过多时中间被省略`() {
        val sb = StringBuilder()
        repeat(40) { index ->
            sb.append("片段$index。")
            sb.append("<tool name=\"t$index\"><param name=\"p\">v</param></tool>")
        }
        val out = SummaryCondenser.condenseAssistant(sb.toString())
        assertTrue(out.contains("中间省略"))
        // 头 12 + 尾 10 都保留。
        assertTrue(out.contains("片段0"))
        assertTrue(out.contains("片段39"))
        // 中间的没保留。
        assertFalse(out.contains("片段20"))
    }

    // ── 分块 ────────────────────────────────────────────────────

    @Test
    fun `分块能区分 tool 与 tool_result`() {
        val text = "前文<tool name=\"a\"><param name=\"x\">1</param></tool>" +
            "中段<tool_result name=\"a\" status=\"success\"><content>2</content></tool_result>后文"
        val segments = SummaryCondenser.splitSegments(text)
        assertEquals(4, segments.size)
        assertTrue(segments[0] is SummaryCondenser.Segment.Text)
        assertTrue(segments[1] is SummaryCondenser.Segment.Tool)
        assertTrue(segments[2] is SummaryCondenser.Segment.ToolResult)
        assertTrue(segments[3] is SummaryCondenser.Segment.Text)
    }

    @Test
    fun `带随机后缀的标签名也能归一`() {
        val text = "<tool_ab12 name=\"t\"><param name=\"p\">v</param></tool_ab12>"
        val segments = SummaryCondenser.splitSegments(text)
        assertTrue("应为工具块：$segments", segments.any { it is SummaryCondenser.Segment.Tool })
    }

    @Test
    fun `标签被换成空格而不是删除`() {
        // 若直接删标签，`好<tool/>的` 会变成「好的的」黏在一起。
        assertEquals("好 的", SummaryCondenser.stripXmlTags("好<tool/>的"))
    }
}
