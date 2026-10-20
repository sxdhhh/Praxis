package dev.praxis.data

import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolRegistry
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 记录收到的参数，返回可配置结果。 */
private class FakeTool(
    override val spec: ToolSpec,
    private val result: (String) -> ToolResult,
) : Tool {
    var lastArgs: String? = null

    override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
        lastArgs = argumentsJson
        return result(argumentsJson)
    }
}

private fun spec(name: String) = ToolSpec(
    name = name,
    description = "test",
    parametersJson = "{}",
)

private val ctx = object : ToolContext {
    override val tier: PermissionTier = PermissionTier.STANDARD
}

class ToolWorkflowPortTest {

    private fun portWith(vararg tools: Tool): Pair<ToolWorkflowPort, ToolRegistry> {
        val registry = ToolRegistry()
        tools.forEach { registry.register(it) }
        return ToolWorkflowPort(registry) { ctx } to registry
    }

    @Test
    fun `unknown tool returns readable error instead of throwing`() = runTest {
        val (port, _) = portWith()

        val out = port.call("nope", "{}")

        assertTrue(out.contains("工具不存在"))
        assertTrue(out.contains("nope"))
    }

    @Test
    fun `empty args are normalized to empty json object`() = runTest {
        val tool = FakeTool(spec("list_files")) { ToolResult("ok") }
        val (port, _) = portWith(tool)

        port.call("list_files", "   ")

        // 关键：空串不能直接传给 execute，否则工具内部 JSONObject("") 抛异常。
        assertEquals("{}", tool.lastArgs)
    }

    @Test
    fun `args are passed through unchanged when non-empty`() = runTest {
        val tool = FakeTool(spec("read_file")) { ToolResult("ok") }
        val (port, _) = portWith(tool)
        val args = """{"path":"readme.md"}"""

        port.call("read_file", args)

        assertEquals(args, tool.lastArgs)
    }

    @Test
    fun `successful result is returned as-is`() = runTest {
        val (port, _) = portWith(FakeTool(spec("echo")) { ToolResult("hello") })

        assertEquals("hello", port.call("echo", "{}"))
    }

    @Test
    fun `error result is prefixed so workflow can detect failure`() = runTest {
        val (port, _) = portWith(
            FakeTool(spec("bad")) { ToolResult("权限不足", isError = true) },
        )

        val out = port.call("bad", "{}")

        // 引擎靠这个前缀/标记识别失败并中止（ERROR_MARKERS 包含「工具调用失败」）。
        assertTrue(out.startsWith("工具调用失败"))
        assertTrue(out.contains("权限不足"))
    }

    @Test
    fun `thrown exception becomes readable text not a crash`() = runTest {
        val (port, _) = portWith(
            FakeTool(spec("boom")) { error("kaboom") },
        )

        val out = port.call("boom", "{}")

        assertTrue(out.contains("工具调用异常"))
        assertTrue(out.contains("kaboom"))
    }

    @Test
    fun `context is fetched per call so tier changes take effect`() = runTest {
        var tier = PermissionTier.STANDARD
        val registry = ToolRegistry()
        val seen = mutableListOf<PermissionTier>()
        registry.register(
            object : Tool {
                override val spec = spec("who")
                override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
                    seen += context.tier
                    return ToolResult("ok")
                }
            },
        )
        val port = ToolWorkflowPort(registry) {
            object : ToolContext {
                override val tier: PermissionTier = tier
            }
        }

        port.call("who", "{}")
        tier = PermissionTier.DEBUGGER
        port.call("who", "{}")

        // 现取而不是构造时缓存：否则用户改了档位工作流仍用旧的。
        assertEquals(listOf(PermissionTier.STANDARD, PermissionTier.DEBUGGER), seen)
    }
}
