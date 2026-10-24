package dev.praxis.feature.chat

import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 输入栏状态机与取消收尾的回归护栏。
 *
 * ## 为什么这些必须测
 *
 * 这三个点都只能靠「跑起来看」，读代码看不出来：
 *  1. `stage` 的四段推进顺序 —— 漏一段用户就卡在某句文案上；
 *  2. `cancel()` 必须把 `isStreaming` 归位 —— 漏了发送键永久变成「停」；
 *  3. `CancellationException` 不能被当成错误 —— 漏了用户按停止会收到「未知错误」。
 *
 * 第 3 条尤其隐蔽：`CancellationException` 是 `Exception` 的子类，
 * `catch (e: Exception)` 一定接得住它，而编译期毫无提示。
 */
class ChatStageTest {

    /**
     * `viewModelScope` 跑在 `Dispatchers.Main.immediate` 上。
     *
     * ## 为什么这两行不能省
     *
     * 纯 JVM 单测里没有 Android 的 Looper，`Dispatchers.Main` 是一个
     * 未初始化的委托 —— 访问即抛 `IllegalStateException: Module with the Main
     * dispatcher had failed to initialize`。
     *
     * 换成 `UnconfinedTestDispatcher` 后，`viewModelScope.launch` 会在当前线程
     * 同步执行到第一个挂起点，`advanceUntilIdle()` 才能把整条流跑完。
     * 这也是 render/persist 两级作用域都能被测试观察到的前提。
     */
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUpMain() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    /** 逐个吐出给定的 delta，然后完成。 */
    private class ScriptedProvider(
        private val events: List<AiStreamEvent>,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : AiProvider {
        override val displayName: String = "scripted"

        override fun stream(request: ChatRequest): Flow<AiStreamEvent> = flow {
            for (event in events) {
                // gate 用于「停在半路」，让测试能观察中间态。
                gate?.await()
                emit(event)
            }
        }
    }

    /** 永不结束的流：用来测「用户中途取消」。 */
    private class HangingProvider(private val onStarted: CompletableDeferred<Unit>) : AiProvider {
        override val displayName: String = "hanging"

        override fun stream(request: ChatRequest): Flow<AiStreamEvent> = flow {
            emit(AiStreamEvent.Delta("开头"))
            onStarted.complete(Unit)
            // 永不给 Done：模拟长推理卡住。
            awaitCancellation()
        }
    }

    private class RecordingPersistence : ChatPersistence {
        val updates = mutableMapOf<Long, String>()
        private var nextId = 1L

        override suspend fun ensureSession(sessionId: String, title: String) = Unit

        override suspend fun appendMessage(
            sessionId: String,
            role: String,
            content: String,
            toolName: String?,
            toolArgs: String?,
            toolCallId: String?,
            isError: Boolean,
        ): Long = nextId++

        override suspend fun updateMessageContent(messageId: Long, content: String) {
            updates[messageId] = content
        }

        override suspend fun loadMessages(sessionId: String): List<PersistedMessage> = emptyList()
        override suspend fun listSessions(): List<SessionItem> = emptyList()
        override suspend fun deleteSession(sessionId: String) = Unit

        /** 置顶：本测试不关心侧边栏排序，只记下来便于将来断言。 */
        override suspend fun setSessionPinned(sessionId: String, pinned: Boolean) {
            pinnedCalls[sessionId] = pinned
        }

        val pinnedCalls = mutableMapOf<String, Boolean>()

        /**
         * 改标题：记录调用，供「AI 总结标题」相关断言使用。
         *
         * ## 为什么必须显式实现（而不是留空）
         *
         * `ChatPersistence` 新增这个抽象方法后，任何实现类都必须给出实现 ——
         * 包括测试里的假实现。改成 `= Unit` 是可以编译的，但这个假实现
         * 存在的意义就是「记录发生了什么」，留空会让将来写标题相关测试时
         * 只能看到一个永远为空的 map，误以为是业务没调用。
         */
        override suspend fun updateSessionTitle(sessionId: String, title: String) {
            titleCalls[sessionId] = title
        }

        val titleCalls = mutableMapOf<String, String>()
    }

    // ── 初始态 ──────────────────────────────────────────────────────

    @Test
    fun `初始状态是空闲且有内容才可发送`() {
        val state = ChatState()
        assertEquals(ChatStage.Idle, state.stage)
        assertFalse(state.isBusy)
        // 空输入不可发送：空白串也算空（发送前有 trim）。
        assertFalse(state.canSend)
        assertFalse(ChatState(input = "   ").canSend)
        assertTrue(ChatState(input = "你好").canSend)
    }

    // ── 阶段推进 ────────────────────────────────────────────────────

    @Test
    fun `发送后进入 Connecting，收到增量进入 Receiving，结束回到 Idle`() = runTest {
        val vm = ChatViewModel(
            provider = ScriptedProvider(
                listOf(
                    AiStreamEvent.Delta("你"),
                    AiStreamEvent.Delta("好"),
                    AiStreamEvent.Done(finishReason = "stop"),
                ),
            ),
            model = "test",
        )

        vm.onInputChange("在吗")
        vm.send()

        // ## 这里**不能**断言「send() 之后立刻是 Connecting」（2026-10-21 修正）
        //
        // `Dispatchers.Main` 被换成 `UnconfinedTestDispatcher` 后，
        // `viewModelScope.launch` 会**同步**执行到第一个挂起点，而
        // `ScriptedProvider` 的 flow 没有挂起点 —— 整条流（含 Done）在 send() 里就跑完了，
        // 返回时 stage 已经是 Idle。原先这里断言 Connecting，实际是在断言「协程被挂住了」，
        // 那是测试环境的巧合，不是行为契约。
        //
        // Connecting 的真实保证是：**它在第一个 token 之前设置**。
        // 用带闸门的 provider 才验得出来（见 userGate 用例）。
        advanceUntilIdle()

        assertEquals(ChatStage.Idle, vm.state.value.stage)
        assertFalse(vm.state.value.isStreaming)
        // 气泡：用户 1 条 + 助手 1 条。
        assertEquals(2, vm.state.value.bubbles.size)
        assertEquals("你好", vm.state.value.bubbles.last().text)
    }

    /**
     * `Connecting` 的真实契约：**在第一个 token 之前**必须已经设置。
     *
     * 用 gate 把 provider 卡在 emit 之前，才能观察到这个中间态 ——
     * 无闸门的 provider 在 Unconfined 调度器下会一口气跑到 Done，
     * 那时 stage 已经是 Idle，什么都测不到。
     */
    @Test
    fun `第一个 token 之前处于 Connecting`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = ChatViewModel(
            provider = ScriptedProvider(listOf(AiStreamEvent.Delta("嗯")), gate = gate),
            model = "test",
        )

        vm.onInputChange("在吗")
        vm.send()

        // 卡在第一个 token 之前：必须已经是 Connecting，且处于忙碌。
        assertEquals(ChatStage.Connecting, vm.state.value.stage)
        assertTrue(vm.state.value.isBusy)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ChatStage.Idle, vm.state.value.stage)
    }

    @Test
    fun `流式中再次 send 被忽略`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = ChatViewModel(
            provider = ScriptedProvider(listOf(AiStreamEvent.Delta("x")), gate = gate),
            model = "test",
        )

        vm.onInputChange("第一句")
        vm.send()
        vm.onInputChange("第二句")
        vm.send()

        // 第二次 send 必须被拒：否则会出现两个并行流往同一个 history 里写。
        assertEquals("第二句", vm.state.value.input)
        advanceUntilIdle()
    }

    // ── 取消收尾 ────────────────────────────────────────────────────

    @Test
    fun `取消后状态归位且保留已收到的正文`() = runTest {
        val started = CompletableDeferred<Unit>()
        val persistence = RecordingPersistence()
        val vm = ChatViewModel(
            provider = HangingProvider(started),
            model = "test",
            chatRepository = persistence,
        )

        vm.onInputChange("跑一个长任务")
        vm.send()
        advanceUntilIdle() // 让流跑到「已吐出一段、卡住」的位置
        started.await()

        assertEquals(ChatStage.Receiving, vm.state.value.stage)
        assertTrue(vm.state.value.isBusy)

        vm.cancel()
        advanceUntilIdle()

        // 三件事都要成立，缺一个都会在真机上表现为「卡住」或「丢内容」。
        assertEquals(ChatStage.Idle, vm.state.value.stage)
        assertFalse(vm.state.value.isStreaming)
        assertFalse(vm.state.value.isBusy)

        // 已收到的半截必须还在（屏幕上与状态里都不能清空）。
        val assistant = vm.state.value.bubbles.last()
        assertEquals(BubbleRole.Assistant, assistant.role)
        assertEquals("开头", assistant.text)
        assertFalse(assistant.streaming)
    }

    @Test
    fun `取消不产生错误气泡`() = runTest {
        val started = CompletableDeferred<Unit>()
        val vm = ChatViewModel(provider = HangingProvider(started), model = "test")

        vm.onInputChange("hi")
        vm.send()
        advanceUntilIdle()
        started.await()

        vm.cancel()
        advanceUntilIdle()

        // CancellationException 是 Exception 的子类。如果 catch 顺序写错，
        // 这里会出现一条 BubbleRole.Error 气泡 + error 非 null。
        assertNull("取消是用户主动行为，不该报错", vm.state.value.error)
        assertTrue(
            "取消不该在任何层产生 Error 气泡",
            vm.state.value.bubbles.none { it.role == BubbleRole.Error },
        )
    }

    @Test
    fun `空闲时取消是空操作`() = runTest {
        val vm = ChatViewModel(provider = ScriptedProvider(emptyList()), model = "test")

        vm.cancel() // 不该抛异常，也不该改状态

        assertEquals(ChatStage.Idle, vm.state.value.stage)
        assertFalse(vm.state.value.isBusy)
        assertTrue(vm.state.value.bubbles.isEmpty())
    }

    // ── 阶段文案 ────────────────────────────────────────────────────

    @Test
    fun `每个非空闲阶段都有文案`() {
        // 空文案会让 AnimatedVisibility 里出现一个只有转圈没有说明的条 —— 等于没说。
        ChatStage.entries
            .filter { it != ChatStage.Idle }
            .forEach { assertTrue("${it.name} 缺少文案", it.label.isNotBlank()) }
        assertEquals("", ChatStage.Idle.label)
    }
}