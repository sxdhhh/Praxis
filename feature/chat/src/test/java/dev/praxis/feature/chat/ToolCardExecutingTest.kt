package dev.praxis.feature.chat

import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatRequest
import dev.praxis.core.ai.ToolCall
import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.Tool
import dev.praxis.core.tool.ToolContext
import dev.praxis.core.tool.ToolExecutor
import dev.praxis.core.tool.ToolRegistry
import dev.praxis.core.tool.ToolResult
import dev.praxis.core.tool.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 工具卡「执行中」状态必须可见。
 *
 * ## 它守的是什么（2026-11-05）
 *
 * 用户反馈：「工具执行中的状态我没看到啊」。
 *
 * 根因是**很多网关一次性返回完整 tool_calls**，不发增量分片 ——
 * 于是「收到 ToolCallDelta 时提前建卡」那条路一次都没走到；
 * 而卡片只在 `runToolCalls` 跑完才建，一建出来就已经带着结果，
 * 用户永远看不到「执行中」。
 *
 * 修法：在 `ToolCalls` 分支（**开始执行之前**）补建卡片，
 * `result = null` 即为「执行中」。
 *
 * ## 为什么必须用闸门工具
 *
 * 「执行中」是一个**中间态**。要断言它，必须让工具停在半路 ——
 * 否则工具瞬间跑完，只能看到终态，断言就变成了「结果卡存在」，
 * 那恰好是修复前也成立的事（所以旧测试抓不到这个 bug）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolCardExecutingTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** 停在半路的工具：`execute` 里等闸门。 */
    private class GatedTool(private val gate: CompletableDeferred<Unit>) : Tool {
        override val spec: ToolSpec = ToolSpec(
            name = "gated_tool",
            description = "测试用工具",
            parametersJson = """{"type":"object","properties":{}}""",
        )

        override suspend fun execute(argumentsJson: String, context: ToolContext): ToolResult {
            gate.await()
            return ToolResult(content = "工具跑完了")
        }
    }

    private object TestContext : ToolContext {
        override val tier: PermissionTier = PermissionTier.ROOT
    }

    /** 一次性吐出完整 ToolCalls（模拟不发分片的网关）。 */
    private class WholeToolCallsProvider(private val calls: List<ToolCall>) : AiProvider {
        override val displayName: String = "whole-tool-calls"

        override fun stream(request: ChatRequest): Flow<AiStreamEvent> = flow {
            emit(AiStreamEvent.ToolCalls(calls))
            // 工具跑完之后模型还会再答一轮；这里给个终止。
            emit(AiStreamEvent.Done(finishReason = "tool_calls"))
        }
    }

    /**
     * 总是放行的执行器。
     *
     * ## 为什么不能让默认策略来判
     *
     * ChatViewModel.runToolCalls 传的是 askHandler = null（本轮不做弹框征询），
     * 而 null 表示「ASK 一律降级为 FORBID」。
     *
     * 于是用默认策略时，一个 STANDARD 工具在当前档位下被判 ASK -> 直接拒绝，
     * execute 根本不会被调用 —— 测试会卡在「没有卡片」上，
     * 而那不是我要验的东西（权限判定另有测试）。
     *
     * 这里显式放行，让测试只关注「卡片什么时候出现」。
     */
    private fun allowAll(registry: ToolRegistry) = ToolExecutor(
        registry = registry,
        policy = { _, _ -> dev.praxis.core.tool.PermissionDecision.ALLOW },
    )
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 核心用例：工具还在跑时，卡片已经存在且 `result == null`。
     *
     * 这正是用户说的「我没看到执行中」—— 修复前这里会**没有工具卡**。
     */
    @Test
    fun `card exists with no result while tool is running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val registry = ToolRegistry().apply { register(GatedTool(gate)) }
        val vm = ChatViewModel(
            provider = WholeToolCallsProvider(
                listOf(
                    ToolCall(id = "c1", name = "gated_tool", argumentsJson = "{}"),
                ),
            ),
            model = "test",
            toolExecutor = allowAll(registry),
            toolContext = TestContext,
        )

        vm.onInputChange("跑个工具")
        vm.send()
        // 只推进到「工具被调用、闸门未开」这一刻 —— 不能再推，
        // 否则工具就跑完了，中间态消失。
        advanceUntilIdle()

        val tool = vm.state.value.bubbles.firstOrNull { it.role == BubbleRole.Tool }
        assertNotNull("工具跑之前就该有卡片（用户要看到「执行中」）", tool)
        assertNull("此时不该有结果 -> UI 显示「执行中」", tool!!.tool?.result)
        assertEquals("gated_tool", tool.tool?.name)

        // 放行，让协程收尾，避免测试结束时留下悬挂的协程。
        gate.complete(Unit)
        advanceUntilIdle()
    }

    /** 工具跑完后，同一张卡片被填上结果（不是又插一张）。 */
    @Test
    fun `same card is filled after the tool finishes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val registry = ToolRegistry().apply { register(GatedTool(gate)) }
        val vm = ChatViewModel(
            provider = WholeToolCallsProvider(
                listOf(ToolCall(id = "c1", name = "gated_tool", argumentsJson = "{}")),
            ),
            model = "test",
            toolExecutor = allowAll(registry),
            toolContext = TestContext,
        )

        vm.onInputChange("跑个工具")
        vm.send()
        advanceUntilIdle()
        // 记录「执行中」那张卡的 id。
        val runningId = vm.state.value.bubbles.first { it.role == BubbleRole.Tool }.id

        gate.complete(Unit)
        advanceUntilIdle()

        val tools = vm.state.value.bubbles.filter { it.role == BubbleRole.Tool }
        assertEquals("不该多插一张卡", 1, tools.size)
        assertEquals("应该是同一张卡被填上", runningId, tools[0].id)
        assertNotNull("跑完后应有结果", tools[0].tool?.result)
        assertTrue(
            "结果内容应可见",
            tools[0].tool?.result?.content?.contains("工具跑完了") == true,
        )
    }

    /**
     * 多个工具调用时，每个都先建卡。
     *
     * 模型一口气返回 3 个调用是常见形态（读文件 -> 改 -> 再读）。
     * 逐个执行时，用户应当同时看到「1 个在跑、2 个在等」，
     * 而不是「跑到第几个才冒出第几张卡」。
     */
    @Test
    fun `all calls get a card before any of them finishes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val registry = ToolRegistry().apply { register(GatedTool(gate)) }
        val vm = ChatViewModel(
            provider = WholeToolCallsProvider(
                listOf(
                    ToolCall(id = "c1", name = "gated_tool", argumentsJson = "{\"a\":1}"),
                    ToolCall(id = "c2", name = "gated_tool", argumentsJson = "{\"b\":2}"),
                    ToolCall(id = "c3", name = "gated_tool", argumentsJson = "{\"c\":3}"),
                ),
            ),
            model = "test",
            toolExecutor = allowAll(registry),
            toolContext = TestContext,
        )

        vm.onInputChange("跑三个工具")
        vm.send()
        advanceUntilIdle()

        val tools = vm.state.value.bubbles.filter { it.role == BubbleRole.Tool }
        assertEquals("三个调用应各有一张卡", 3, tools.size)
        assertTrue("都还没有结果", tools.all { it.tool?.result == null })

        gate.complete(Unit)
        advanceUntilIdle()
    }

    /** 未装配工具层时不建卡（如实告知，而不是画一张永远「执行中」的卡）。 */
    @Test
    fun `no executor means no card`() = runTest {
        val vm = ChatViewModel(
            provider = WholeToolCallsProvider(
                listOf(ToolCall(id = "c1", name = "gated_tool", argumentsJson = "{}")),
            ),
            model = "test",
            // 不传 toolExecutor / toolContext
        )

        vm.onInputChange("跑个工具")
        vm.send()
        advanceUntilIdle()

        assertTrue(
            "未装配时不该有工具卡",
            vm.state.value.bubbles.none { it.role == BubbleRole.Tool },
        )
    }
}
