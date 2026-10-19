package dev.praxis.core.tool

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限策略 + 注册表 + 执行器的行为锁。
 *
 * 重点锁三件事：
 *  1. 三档的覆盖关系（只有三档，不是四档）；
 *  2. 差一档 ASK、差两档 FORBID（不是「一律 ASK」）；
 *  3. 执行严格串行且结果同序。
 */
class ToolLayerTest {

    // ---------- 权限档位 ----------

    @Test
    fun `there are exactly three tiers`() {
        // 锁死数量：加档位时这个测试会红，强迫重新审视
        // （Operit 的第四档 admin 实测零 override，已砍掉）。
        assertEquals(3, PermissionTier.entries.size)
    }

    @Test
    fun `root allows everything, standard allows only standard`() {
        assertTrue(PermissionTier.ROOT.allows(PermissionTier.STANDARD))
        assertTrue(PermissionTier.ROOT.allows(PermissionTier.DEBUGGER))
        assertTrue(PermissionTier.ROOT.allows(PermissionTier.ROOT))

        assertTrue(PermissionTier.STANDARD.allows(PermissionTier.STANDARD))
        assertTrue(!PermissionTier.STANDARD.allows(PermissionTier.DEBUGGER))
        assertTrue(!PermissionTier.STANDARD.allows(PermissionTier.ROOT))
    }

    @Test
    fun `policy allows when covered`() {
        assertEquals(
            PermissionDecision.ALLOW,
            DefaultPermissionPolicy.decide(PermissionTier.DEBUGGER, PermissionTier.STANDARD),
        )
    }

    @Test
    fun `policy asks when exactly one tier short`() {
        assertEquals(
            PermissionDecision.ASK,
            DefaultPermissionPolicy.decide(PermissionTier.STANDARD, PermissionTier.DEBUGGER),
        )
        assertEquals(
            PermissionDecision.ASK,
            DefaultPermissionPolicy.decide(PermissionTier.DEBUGGER, PermissionTier.ROOT),
        )
    }

    @Test
    fun `policy forbids when two tiers short`() {
        assertEquals(
            PermissionDecision.FORBID,
            DefaultPermissionPolicy.decide(PermissionTier.STANDARD, PermissionTier.ROOT),
        )
    }

    // ---------- 注册表 ----------

    @Test
    fun `duplicate name throws instead of silently overriding`() {
        val registry = ToolRegistry()
        registry.register(FakeTool("dup", PermissionTier.STANDARD))
        val error = runCatching { registry.register(FakeTool("dup", PermissionTier.ROOT)) }
        assertTrue(error.isFailure)
        // 原实现没被覆盖
        assertEquals(PermissionTier.STANDARD, registry.find("dup")!!.spec.tier)
    }

    @Test
    fun `descriptors are filtered by tier`() {
        val registry = ToolRegistry()
        registry.registerAll(
            FakeTool("a", PermissionTier.STANDARD),
            FakeTool("b", PermissionTier.DEBUGGER),
            FakeTool("c", PermissionTier.ROOT),
        )

        assertEquals(listOf("a"), registry.descriptorsFor(PermissionTier.STANDARD).map { it.name })
        assertEquals(
            listOf("a", "b"),
            registry.descriptorsFor(PermissionTier.DEBUGGER).map { it.name },
        )
        assertEquals(
            listOf("a", "b", "c"),
            registry.descriptorsFor(PermissionTier.ROOT).map { it.name },
        )
    }

    // ---------- 执行器 ----------

    @Test
    fun `execution is strictly serial and keeps input order`() = runTest {
        val log = mutableListOf<String>()
        val registry = ToolRegistry()
        registry.registerAll(
            RecordingTool("first", log),
            RecordingTool("second", log),
            RecordingTool("third", log),
        )

        val executor = ToolExecutor(registry)
        val results = executor.executeAll(
            invocations = listOf(
                ToolInvocation("c1", "first", "{}"),
                ToolInvocation("c2", "second", "{}"),
                ToolInvocation("c3", "third", "{}"),
            ),
            context = FixedContext(PermissionTier.STANDARD),
        )

        // 顺序 == 调用顺序
        assertEquals(listOf("first", "second", "third"), results.map { it.invocation.name })
        // 严格串行：每个工具都在下一个开始前跑完
        assertEquals(listOf("start:first", "end:first", "start:second", "end:second", "start:third", "end:third"), log)
    }

    @Test
    fun `unknown tool returns error result instead of throwing`() = runTest {
        val executor = ToolExecutor(ToolRegistry())
        val results = executor.executeAll(
            invocations = listOf(ToolInvocation("c1", "nope", "{}")),
            context = FixedContext(PermissionTier.STANDARD),
        )
        assertEquals(1, results.size)
        assertTrue(results[0].result.isError)
        assertTrue(results[0].result.content.contains("未知工具"))
    }

    @Test
    fun `ask without handler is denied`() = runTest {
        val registry = ToolRegistry()
        registry.registerAll(RecordingTool("needsDebug", mutableListOf(), PermissionTier.DEBUGGER))

        val results = ToolExecutor(registry).executeAll(
            invocations = listOf(ToolInvocation("c1", "needsDebug", "{}")),
            context = FixedContext(PermissionTier.STANDARD),
            askHandler = null,
        )
        assertTrue(results[0].result.isError)
        assertEquals(PermissionDecision.ASK, results[0].decision)
    }

    @Test
    fun `ask approved by user executes the tool`() = runTest {
        val registry = ToolRegistry()
        registry.registerAll(RecordingTool("needsDebug", mutableListOf(), PermissionTier.DEBUGGER))

        val results = ToolExecutor(registry).executeAll(
            invocations = listOf(ToolInvocation("c1", "needsDebug", "{}")),
            context = FixedContext(PermissionTier.STANDARD),
            askHandler = { true },
        )
        assertTrue(!results[0].result.isError)
    }

    @Test
    fun `forbidden tool is refused with actionable message`() = runTest {
        val registry = ToolRegistry()
        registry.registerAll(RecordingTool("rootOnly", mutableListOf(), PermissionTier.ROOT))

        val results = ToolExecutor(registry).executeAll(
            invocations = listOf(ToolInvocation("c1", "rootOnly", "{}")),
            context = FixedContext(PermissionTier.STANDARD),
        )
        assertTrue(results[0].result.isError)
        // 必须明确是「被拒」而非「失败」，模型下一步策略不同
        assertTrue(results[0].result.content.contains("权限不足"))
        assertEquals(PermissionDecision.FORBID, results[0].decision)
    }

    private class FakeTool(
        override val spec: ToolSpec,
    ) : Tool {
        constructor(name: String, tier: PermissionTier) : this(
            ToolSpec(
                name = name,
                description = "fake",
                parametersJson = "{}",
                tier = tier,
            ),
        )

        override suspend fun execute(argumentsJson: String, context: ToolContext) =
            ToolResult("ok")
    }

    private class RecordingTool(
        private val name: String,
        private val log: MutableList<String>,
        tier: PermissionTier = PermissionTier.STANDARD,
    ) : Tool {
        override val spec = ToolSpec(
            name = name,
            description = "recording",
            parametersJson = "{}",
            tier = tier,
        )

        override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
            log += "start:$name"
            log += "end:$name"
            return ToolResult("done:$name")
        }
    }

    private class FixedContext(override val tier: PermissionTier) : ToolContext
}
