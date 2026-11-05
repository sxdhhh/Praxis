package dev.praxis.core.tool

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 五类失败位置的归因。
 *
 * ## 为什么这条链必须钉住
 *
 * 旧实现把所有失败压成 `isError = true` 一句话。模型看到它的默认反应是
 * 「换个写法再试一次」—— 而这对「执行到一半失败」正是最危险的做法
 * （副作用可能翻倍）。
 *
 * 归因错了不会报任何错，只是模型在后台做错决定。
 */
class ToolFailureStageTest {

    /** 只有「副作用必然未发生」的两类才允许直接重试。 */
    @Test
    fun `只有参数与拒绝可安全重试`() {
        assertTrue(ToolFailureStage.Argument.safeToRetry)
        assertTrue(ToolFailureStage.Denied.safeToRetry)
        assertFalse(ToolFailureStage.Execution.safeToRetry)
        assertFalse(ToolFailureStage.Output.safeToRetry)
        assertFalse(ToolFailureStage.Rendering.safeToRetry)
    }

    /**
     * 执行阶段的提示必须包含「可能已产生部分效果」。
     *
     * 少了这句，模型会直接重试；而重试会再执行一次同样的副作用。
     */
    @Test
    fun `执行阶段提示要求先确认状态`() {
        val hint = ToolFailureStage.Execution.recoveryHint
        assertTrue("缺少副作用提醒：$hint", hint.contains("可能已产生部分效果"))
        assertTrue("缺少确认要求：$hint", hint.contains("确认"))
    }

    /** 输出阶段的提示必须点明「副作用可能已经发生」。 */
    @Test
    fun `输出阶段提示副作用可能已发生`() {
        assertTrue(ToolFailureStage.Output.recoveryHint.contains("副作用可能已经发生"))
    }

    /**
     * 呈现失败的提示必须明确「界面呈现失败不代表执行失败」。
     *
     * 这是最容易被忽略的一条：用户看到卡片是红的，会以为工具没跑。
     */
    @Test
    fun `呈现失败不等于执行失败`() {
        val hint = ToolFailureStage.Rendering.recoveryHint
        assertTrue("缺少澄清：$hint", hint.contains("不代表执行失败"))
    }

    /** 拒绝阶段的提示必须**劝阻**模型假设已获授权。 */
    @Test
    fun `拒绝阶段提示不要假设已授权`() {
        assertTrue(ToolFailureStage.Denied.recoveryHint.contains("不要假设已获得授权"))
    }

    // ── 归因是否正确接进 ToolExecutor ────────────────────────────

    /** 最小 ToolContext：本组用例只关心失败归因，不关心能力。 */
    private class FixedContext(override val tier: PermissionTier) : ToolContext

    private val ctx = FixedContext(PermissionTier.STANDARD)

    private class FakeTool(
        override val spec: ToolSpec,
        private val block: suspend () -> ToolResult,
    ) : Tool {
        override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult = block()
    }

    private fun registryOf(vararg tools: Tool): ToolRegistry = ToolRegistry().apply { registerAll(*tools) }

    /**
     * 未知工具 → [ToolFailureStage.Argument]。
     *
     * 模型给错了名字，改个名字就能成功，且此前不可能有任何副作用。
     */
    @Test
    fun `未知工具归因为参数`() = runTest {
        val executor = ToolExecutor(registryOf())
        val executions = executor.executeAll(
            listOf(ToolInvocation("c1", "nope", "{}")),
            context = ctx,
        )
        assertEquals(ToolFailureStage.Argument, executions.single().result.stage)
    }

    /** 工具抛异常 → [ToolFailureStage.Execution]（可能已部分执行）。 */
    @Test
    fun `抛异常归因为执行`() = runTest {
        val tool = FakeTool(
            ToolSpec(name = "boom", description = "", parametersJson = "{}"),
        ) { throw IllegalStateException("炸了") }
        val executor = ToolExecutor(registryOf(tool))
        val executions = executor.executeAll(
            listOf(ToolInvocation("c1", "boom", "{}")),
            context = ctx,
        )
        assertEquals(ToolFailureStage.Execution, executions.single().result.stage)
    }

    /** 参数错误 → [ToolFailureStage.Argument]，并带上修正提示。 */
    @Test
    fun `参数错误归因为参数`() = runTest {
        val tool = FakeTool(
            ToolSpec(name = "par", description = "", parametersJson = "{}"),
        ) { throw ToolArgumentException("缺少字段") }
        val executor = ToolExecutor(registryOf(tool))
        val execution = executor.executeAll(
            listOf(ToolInvocation("c1", "par", "{}")),
            context = ctx,
        ).single()
        assertEquals(ToolFailureStage.Argument, execution.result.stage)
        assertTrue("结果里没有修正提示", execution.result.content.contains("修正后可以重试"))
    }

    /** 成功时 stage 为 null。 */
    @Test
    fun `成功时无失败阶段`() = runTest {
        val tool = FakeTool(
            ToolSpec(name = "ok", description = "", parametersJson = "{}"),
        ) { ToolResult(content = "done") }
        val executor = ToolExecutor(registryOf(tool))
        val execution = executor.executeAll(
            listOf(ToolInvocation("c1", "ok", "{}")),
            context = ctx,
        ).single()
        assertEquals(null, execution.result.stage)
        assertEquals(ToolPresentState.Success, execution.presentState)
    }

    // ── 呈现状态映射 ──────────────────────────────────────────

    @Test
    fun `呈现状态按阶段区分`() {
        val base = ToolExecution(
            invocation = ToolInvocation("c", "t", "{}"),
            result = ToolResult("x", isError = true),
            decision = PermissionDecision.ALLOW,
        )
        assertEquals(
            ToolPresentState.Denied,
            base.copy(result = base.result.copy(stage = ToolFailureStage.Denied)).presentState,
        )
        assertEquals(
            ToolPresentState.InvalidArguments,
            base.copy(result = base.result.copy(stage = ToolFailureStage.Argument)).presentState,
        )
        assertEquals(
            ToolPresentState.Failed(ToolFailureStage.Execution),
            base.copy(result = base.result.copy(stage = ToolFailureStage.Execution)).presentState,
        )
    }
}
