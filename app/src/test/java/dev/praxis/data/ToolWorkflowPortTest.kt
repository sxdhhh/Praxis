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

    /**
     * 单条用例 30 秒闸门（2026-11-05）。
     *
     * ## 为什么加在每一个 :app 测试类上
     *
     * `:app:testDebugUnitTest` 在 CI 上卡住过 17 分钟不结束
     * （正常 0.3~1.4 分钟），而 Gradle **默认不给测试任务设超时**，
     * 也**不打印正在跑哪条用例** —— 于是只看到一个不动的进度条。
     *
     * 试过在 app/build.gradle.kts 里配 `Test.timeout` 与
     * `testLogging.events(started)`：对**被取消**的任务，Gradle 会把
     * 缓冲的输出一起丢掉，日志里一行都没有。
     *
     * 所以改成让**断言层**自己超时 —— 失败信息里直接带类名与方法名，
     * 不依赖 Gradle 的输出缓冲。
     *
     * 30 秒是宽裕值：本文件最快的用例不到 10 毫秒。
     */
    @get:org.junit.Rule
    val praxisTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

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
