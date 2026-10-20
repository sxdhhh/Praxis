package dev.praxis.core.workflow

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记录调用并回放预设输出的假工具。 */
private class FakeToolPort(
    private val responses: Map<String, String> = emptyMap(),
    private val throwOn: Set<String> = emptySet(),
) : WorkflowToolPort {
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun call(toolName: String, argsJson: String): String {
        calls += toolName to argsJson
        if (toolName in throwOn) error("boom: $toolName")
        return responses[toolName] ?: "ok:$toolName"
    }
}

class ConditionExpressionTest {

    @Test
    fun `equality matches`() {
        assertTrue(ConditionExpression.evaluate("{{s}} == ok", mapOf("s" to "ok")))
        assertFalse(ConditionExpression.evaluate("{{s}} == ok", mapOf("s" to "bad")))
    }

    @Test
    fun `inequality matches`() {
        assertTrue(ConditionExpression.evaluate("{{s}} != ok", mapOf("s" to "bad")))
        assertFalse(ConditionExpression.evaluate("{{s}} != ok", mapOf("s" to "ok")))
    }

    @Test
    fun `contains matches`() {
        // 这条曾经因操作符表与 when 分支的字符串不一致而永远返回 false。
        assertTrue(ConditionExpression.evaluate("{{s}} contains 成功", mapOf("s" to "执行成功")))
        assertFalse(ConditionExpression.evaluate("{{s}} contains 成功", mapOf("s" to "失败")))
    }

    @Test
    fun `bare variable is truthy check`() {
        assertTrue(ConditionExpression.evaluate("{{s}}", mapOf("s" to "x")))
        assertFalse(ConditionExpression.evaluate("{{s}}", mapOf("s" to "")))
        assertFalse(ConditionExpression.evaluate("{{s}}", mapOf("s" to "false")))
        assertFalse(ConditionExpression.evaluate("{{s}}", mapOf("s" to "0")))
    }

    @Test
    fun `undefined variable is falsy not empty string`() {
        // 关键：未定义不能当空串，否则 `== ""` 会把「没这变量」误判。
        assertFalse(ConditionExpression.evaluate("{{missing}}", emptyMap()))
        assertFalse(ConditionExpression.evaluate("{{missing}} == \"\"", emptyMap()))
    }

    @Test
    fun `quoted literal is unquoted`() {
        assertTrue(ConditionExpression.evaluate("{{s}} == \"a b\"", mapOf("s" to "a b")))
        assertTrue(ConditionExpression.evaluate("{{s}} == 'a b'", mapOf("s" to "a b")))
    }

    @Test
    fun `malformed expression returns false instead of throwing`() {
        assertFalse(ConditionExpression.evaluate("", emptyMap()))
        assertFalse(ConditionExpression.evaluate("== ok", emptyMap()))
        assertFalse(ConditionExpression.evaluate("{{s}} ==", mapOf("s" to "ok")))
    }
}

class RenderTemplateTest {

    @Test
    fun `replaces known keys`() {
        assertEquals("你好 sxd", renderTemplate("你好 {{name}}", mapOf("name" to "sxd")))
    }

    @Test
    fun `keeps unknown key literally`() {
        // 关键：不能换成空串，否则拼错的参数会变成静默的错误值。
        assertEquals("路径 {{typo}}", renderTemplate("路径 {{typo}}", mapOf("other" to "x")))
    }

    @Test
    fun `keeps unclosed placeholder`() {
        assertEquals("abc {{name", renderTemplate("abc {{name", mapOf("name" to "x")))
    }

    @Test
    fun `replaces multiple keys`() {
        assertEquals("a-b", renderTemplate("{{x}}-{{y}}", mapOf("x" to "a", "y" to "b")))
    }
}

class WorkflowEngineTest {

    private fun wf(vararg nodes: WorkflowNode) = WorkflowDefinition("w", "测试", nodes.toList())

    @Test
    fun `linear flow reaches output`() = runTest {
        val port = FakeToolPort(mapOf("echo" to "hello"))
        val result = WorkflowEngine(port).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "a"),
                WorkflowNode.Tool("a", "调用", "echo", outputKey = "r", nextId = "o"),
                WorkflowNode.Output("o", template = "结果：{{r}}"),
            ),
        )

        assertTrue(result.success)
        assertEquals(listOf("结果：hello"), result.outputs)
        assertEquals("hello", result.variables["r"])
    }

    @Test
    fun `condition takes then branch`() = runTest {
        val result = WorkflowEngine(FakeToolPort()).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "c"),
                WorkflowNode.Condition("c", "判断", "{{v}} == yes", thenId = "y", elseId = "n"),
                WorkflowNode.Output("y", template = "走了 then"),
                WorkflowNode.Output("n", template = "走了 else"),
            ),
            mapOf("v" to "yes"),
        )

        assertEquals(listOf("走了 then"), result.outputs)
    }

    @Test
    fun `condition takes else branch`() = runTest {
        val result = WorkflowEngine(FakeToolPort()).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "c"),
                WorkflowNode.Condition("c", "判断", "{{v}} == yes", thenId = "y", elseId = "n"),
                WorkflowNode.Output("y", template = "走了 then"),
                WorkflowNode.Output("n", template = "走了 else"),
            ),
            mapOf("v" to "no"),
        )

        assertEquals(listOf("走了 else"), result.outputs)
    }

    @Test
    fun `missing trigger fails fast`() = runTest {
        val result = WorkflowEngine(FakeToolPort()).run(
            wf(WorkflowNode.Output("o", template = "x")),
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("入口"))
    }

    @Test
    fun `cycle is stopped by step limit`() = runTest {
        // a → b → a 形成环：必须被步数上限拦住，而不是挂死。
        val result = WorkflowEngine(FakeToolPort(), maxSteps = 10).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "a"),
                WorkflowNode.Transform("a", "A", "x", "k", nextId = "b"),
                WorkflowNode.Transform("b", "B", "x", "k", nextId = "a"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("步数上限"))
    }

    @Test
    fun `tool exception aborts and records error step`() = runTest {
        val port = FakeToolPort(throwOn = setOf("boom"))
        val result = WorkflowEngine(port).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "a"),
                WorkflowNode.Tool("a", "危险工具", "boom", outputKey = "r", nextId = "o"),
                WorkflowNode.Output("o", template = "不该到这里"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.outputs.isEmpty())
        assertTrue(result.steps.any { it.isError })
        // 失败时不能写上下文，否则下游会拿错误文本当数据。
        assertNull(result.variables["r"])
    }

    @Test
    fun `tool error text aborts too`() = runTest {
        // 端口不抛异常、但返回错误文本的路径也必须中止。
        val port = FakeToolPort(mapOf("bad" to "工具不存在：bad"))
        val result = WorkflowEngine(port).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "a"),
                WorkflowNode.Tool("a", "坏工具", "bad", nextId = "o"),
                WorkflowNode.Output("o", template = "不该到这里"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.outputs.isEmpty())
    }

    @Test
    fun `transform writes variable used downstream`() = runTest {
        val result = WorkflowEngine(FakeToolPort()).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "x"),
                WorkflowNode.Transform("x", "拼", "{{a}}/{{b}}", "joined", nextId = "o"),
                WorkflowNode.Output("o", template = "{{joined}}"),
            ),
            mapOf("a" to "1", "b" to "2"),
        )

        assertEquals(listOf("1/2"), result.outputs)
    }

    @Test
    fun `args template is rendered before calling tool`() = runTest {
        val port = FakeToolPort()
        WorkflowEngine(port).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "a"),
                WorkflowNode.Tool("a", "读", "read_file", argsTemplate = "{\"path\":\"{{p}}\"}", nextId = null),
            ),
            mapOf("p" to "/tmp/x"),
        )

        assertEquals("read_file" to "{\"path\":\"/tmp/x\"}", port.calls.single())
    }

    @Test
    fun `trigger with no next ends immediately`() = runTest {
        val result = WorkflowEngine(FakeToolPort()).run(
            wf(WorkflowNode.Trigger("t", nextId = null)),
        )

        assertTrue(result.success)
        assertTrue(result.outputs.isEmpty())
    }

    @Test
    fun `duration limit aborts`() = runTest {
        var clock = 0L
        val result = WorkflowEngine(
            FakeToolPort(),
            maxSteps = 100,
            maxDurationMs = 1000,
            nowMs = { clock },
        ).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "a"),
                WorkflowNode.Transform("a", "A", "x", "k", nextId = "b"),
                WorkflowNode.Transform("b", "B", "x", "k", nextId = "c"),
                WorkflowNode.Transform("c", "C", "x", "k", nextId = null),
            ),
        )

        // 未超时时应成功（对照组），保证限制不是无条件生效。
        assertTrue(result.success)

        // 超时用例：时钟必须在**运行过程中**前进。
        //
        // 曾经这里直接把 clock 设成 5000 再跑——那是错的：run() 开头
        // `startedAt = nowMs()` 也读到 5000，于是 now - startedAt 恒为 0，
        // 永远不超时。必须让时钟每次读取都跳一步。
        var ticking = 0L
        val timedOut = WorkflowEngine(
            FakeToolPort(),
            maxSteps = 100,
            maxDurationMs = 1000,
            nowMs = {
                ticking += 600
                ticking
            },
        ).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "a"),
                WorkflowNode.Transform("a", "A", "x", "k", nextId = "b"),
                WorkflowNode.Transform("b", "B", "x", "k", nextId = "c"),
                WorkflowNode.Transform("c", "C", "x", "k", nextId = null),
            ),
        )
        assertFalse(timedOut.success)
        assertTrue(timedOut.error!!.contains("时长上限"))
    }

    // ---- 以下四条针对「静默成功」缺陷（R18）----

    @Test
    fun `dangling nextId on entry fails instead of silently succeeding`() = runTest {
        // 曾经：入口指向不存在的节点 → current 为 null → 循环不执行 →
        // 返回 success = true。用户看到「运行成功」，实际一个节点都没跑。
        val result = WorkflowEngine(FakeToolPort()).run(
            wf(WorkflowNode.Trigger("t", nextId = "nowhere")),
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("不存在"))
    }

    @Test
    fun `dangling nextId on tool fails`() = runTest {
        val result = WorkflowEngine(FakeToolPort()).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "a"),
                WorkflowNode.Tool("a", "读", "echo", nextId = "missing"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("不存在"))
    }

    @Test
    fun `dangling branch on condition fails`() = runTest {
        val result = WorkflowEngine(FakeToolPort()).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "c"),
                WorkflowNode.Condition("c", "判断", "{{v}} == yes", thenId = "ghost", elseId = null),
            ),
            mapOf("v" to "yes"),
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("不存在"))
    }

    @Test
    fun `null branch on condition is still a legal end`() = runTest {
        // 关键反例：elseId = null 表示「到此结束」，不能当成错误。
        val result = WorkflowEngine(FakeToolPort()).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "c"),
                WorkflowNode.Condition("c", "判断", "{{v}} == yes", thenId = null, elseId = null),
            ),
            mapOf("v" to "no"),
        )

        assertTrue(result.success)
    }

    @Test
    fun `tool output containing not-found phrase is not treated as failure`() = runTest {
        // 曾经 ERROR_MARKERS 用子串匹配且含 "not found"：
        // 读到一份含该词的源码就会被误判为工具失败而中止。
        val port = FakeToolPort(mapOf("read_file" to "// handle file not found gracefully\nval x = 1"))
        val result = WorkflowEngine(port).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "a"),
                WorkflowNode.Tool("a", "读", "read_file", outputKey = "src", nextId = "o"),
                WorkflowNode.Output("o", template = "{{src}}"),
            ),
        )

        assertTrue(result.success)
    }

    @Test
    fun `tool failure text at the start still aborts`() = runTest {
        // 前缀匹配必须仍然能识别真失败。
        val port = FakeToolPort(mapOf("bad" to "工具调用失败：权限不足"))
        val result = WorkflowEngine(port).run(
            wf(
                WorkflowNode.Trigger("t", nextId = "a"),
                WorkflowNode.Tool("a", "坏", "bad", nextId = "o"),
                WorkflowNode.Output("o", template = "不该到这里"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.outputs.isEmpty())
    }
}
