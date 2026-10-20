package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文预算与装配器的行为。
 *
 * 这些用例的靶子是**产物形状**而不是内部实现：`fit` 返回的列表会被直接
 * 序列化成上游请求体，任何一条畸形序列都是 400。所以断言都写成
 * 「哪种消息必须还在 / 必须不在」，而不是「内部下标算对了」。
 */
class AiContextBudgetTest {

    private fun user(text: String) = ChatMessage(role = ChatRole.User, text = text)
    private fun assistant(text: String) = ChatMessage(role = ChatRole.Assistant, text = text)
    private fun system(text: String) = ChatMessage(role = ChatRole.System, text = text)

    private fun toolCall(id: String, args: String = "{}") =
        ToolCall(id = id, name = "read_file", argumentsJson = args)

    // --- costOf -------------------------------------------------------------

    @Test
    fun `cost counts text and tool arguments`() {
        // 工具参数确实占上游 token，不算会让预算被吃穿。
        val m = ChatMessage(
            role = ChatRole.Assistant,
            text = "abc",
            toolCalls = listOf(toolCall("1", """{"path":"/x"}""")),
        )
        // text 3 + name(read_file)=9 + args=13
        assertEquals(3 + "read_file".length + """{"path":"/x"}""".length, AiContextBudget.costOf(m))
    }

    @Test
    fun `cost counts tool result content`() {
        val m = ChatMessage(
            role = ChatRole.Tool,
            text = "ab",
            toolResult = ToolResult(callId = "1", content = "xyz"),
        )
        assertEquals(5, AiContextBudget.costOf(m))
    }

    // --- fit ----------------------------------------------------------------

    @Test
    fun `under budget returns the same list untouched`() {
        val list = listOf(user("hi"), assistant("hello"))
        assertEquals(list, AiContextBudget.fit(list, maxChars = 1000))
    }

    @Test
    fun `oldest ordinary messages are dropped first`() {
        // 每条 10 字符，预算只够 2 条：最旧的两条该消失，最近两条该留下。
        //
        // 文本必须各不相同：ChatMessage 是 data class，用相同文本会让
        // `contains` 靠值相等命中任意一条，这条断言就永远成立、测不出东西。
        val list = (0..3).map { user("m$it-1234567") }
        assertEquals("每条必须是 10 字符", 10, list[0].text.length)

        val out = AiContextBudget.fit(list, maxChars = 20)

        assertEquals("只该剩两条", 2, out.size)
        assertTrue("最近的必须留下", out.any { it.text == "m3-1234567" })
        assertTrue(out.any { it.text == "m2-1234567" })
        assertFalse("最旧的必须被丢", out.any { it.text == "m0-1234567" })
        assertFalse(out.any { it.text == "m1-1234567" })
    }

    @Test
    fun `system messages are never dropped`() {
        // system 承载技能清单与记忆注入：丢了会让模型行为突变，用户无从排查。
        val sys = system("s".repeat(100))
        val list = listOf(sys) + (1..10).map { user("u".repeat(100)) }

        val out = AiContextBudget.fit(list, maxChars = 150)

        assertTrue("system 必须保留", out.any { it.role == ChatRole.System })
        assertTrue("预算装不下全部 11 条，必须真的丢了东西", out.size < list.size)
    }

    @Test
    fun `last user message survives even when it alone busts the budget`() {
        val list = listOf(user("old".repeat(100)), user("NEW-QUESTION"), assistant("ok"))
        val out = AiContextBudget.fit(list, maxChars = 50)

        assertTrue("用户当下的问题不能丢", out.any { it.text == "NEW-QUESTION" })
    }

    @Test
    fun `tool call and result are kept together`() {
        // 只留 toolCalls 会得到「要调工具但没结果」，只留结果会得到「有结果没请求」，
        // 两种畸形序列上游都会 400。
        val call = ChatMessage(role = ChatRole.Assistant, text = "", toolCalls = listOf(toolCall("c1")))
        val result = ChatMessage(
            role = ChatRole.Tool,
            text = "",
            toolResult = ToolResult(callId = "c1", content = "r".repeat(200)),
        )
        val list = listOf(user("go"), call, result, user("next"))

        val out = AiContextBudget.fit(list, maxChars = 10)

        assertTrue("工具调用必须保留", out.any { it.toolCalls.any { c -> c.id == "c1" } })
        assertTrue("工具结果必须保留", out.any { it.toolResult?.callId == "c1" })
        assertTrue("预算装不下，必须真的丢了普通消息", out.size < list.size)
    }

    @Test
    fun `order of surviving messages is preserved`() {
        val list = listOf(user("a".repeat(50)), assistant("b".repeat(50)), user("c"))
        val out = AiContextBudget.fit(list, maxChars = 60)

        val expectedOrder = list.filter { it in out }
        assertEquals(expectedOrder, out)
    }

    @Test
    fun `non-positive budget is a no-op instead of wiping everything`() {
        // 传 0 或负数更可能是「没配」而不是「一点上下文都不要」，
        // 静默清空会让模型突然什么都不知道。
        val list = listOf(user("hi"), assistant("hello"))
        assertEquals(list, AiContextBudget.fit(list, maxChars = 0))
        assertEquals(list, AiContextBudget.fit(list, maxChars = -1))
    }

    @Test
    fun `overBudget reports the same threshold as fit`() {
        val list = listOf(user("x".repeat(100)))
        assertTrue(AiContextBudget.overBudget(list, maxChars = 50))
        assertFalse(AiContextBudget.overBudget(list, maxChars = 200))
    }

    // --- ContextAssembler ---------------------------------------------------

    @Test
    fun `assembler compacts long text before dropping`() {
        // 关键顺序：先压缩后丢弃。单条超大文本压缩后能留下，
        // 若先丢弃就会整条扔掉，白白损失一整轮对话。
        val huge = user("H".repeat(10_000))
        val out = ContextAssembler.assemble(listOf(huge), maxChars = 2_000)

        assertEquals(1, out.size)
        assertTrue("压缩后必须装得下", AiContextBudget.costOf(out[0]) <= 2_000)
        assertTrue("必须保留首部", out[0].text.startsWith("HHH"))
    }

    @Test
    fun `assembler never truncates tool arguments`() {
        // 截断参数 JSON → 非法 JSON → 上游 400，且重试无效。
        val args = """{"path":"/sdcard/x.txt","note":"${"y".repeat(3000)}"}"""
        val call = ChatMessage(role = ChatRole.Assistant, text = "", toolCalls = listOf(toolCall("c1", args)))

        val out = ContextAssembler.assemble(listOf(user("go"), call), maxChars = 500)

        val kept = out.first { it.toolCalls.isNotEmpty() }
        assertEquals(args, kept.toolCalls[0].argumentsJson)
    }

    @Test
    fun `assembler never truncates system messages`() {
        val sys = system("s".repeat(5000))
        val out = ContextAssembler.assemble(listOf(sys, user("q")), maxChars = 1000)

        assertEquals(sys, out.first { it.role == ChatRole.System })
    }

    @Test
    fun `assembler compacts tool result content with the tighter budget`() {
        val result = ChatMessage(
            role = ChatRole.Tool,
            text = "",
            toolResult = ToolResult(callId = "c1", content = "Z".repeat(5000)),
        )
        val out = ContextAssembler.assemble(listOf(user("q"), result), maxChars = 10_000)

        val r = out.first { it.role == ChatRole.Tool }.toolResult!!
        assertTrue("工具结果应被压到 TOOL_HEAD+TOOL_TAIL 量级",
            r.content.length <= CompactionPolicy.TOOL_HEAD + CompactionPolicy.TOOL_TAIL + 40)
    }

    @Test
    fun `assembler with compact disabled only drops`() {
        // 关掉压缩时，单条超预算消息会被整条丢弃而不是被改写。
        val huge = user("H".repeat(5_000))
        val out = ContextAssembler.assemble(listOf(huge), maxChars = 100, compact = false)

        // 唯一一条且是 user，但它是最后一条 user → 被 pin 住，故仍在且未改写。
        assertEquals(1, out.size)
        assertEquals(5_000, out[0].text.length)
    }

    @Test
    fun `empty input is returned as is`() {
        assertEquals(emptyList<ChatMessage>(), ContextAssembler.assemble(emptyList()))
    }
}