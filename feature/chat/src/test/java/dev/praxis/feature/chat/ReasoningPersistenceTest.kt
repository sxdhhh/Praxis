package dev.praxis.feature.chat

import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 思考过程的流向与持久化。
 *
 * ## 它守的是什么（2026-11-05）
 *
 * 用户原话：「思考应该默认展开然后思考完后自动折叠，**然后思考完后不能消失**」。
 *
 * 「不能消失」有两层意思，第二层是这次真正修的：
 *
 *  1. 流式结束后折叠块还在（不因为 `isStreaming` 变 false 就把整块丢掉）；
 *  2. **重启后还在** —— 旧实现思考只活在内存里，杀进程就没了。
 *     这是「重进丢内容」的翻版，只是丢的是推理。
 *
 * 第 2 条需要落库，所以测试里必须有一个**会记录**的 persistence。
 * `ChatPersistence.updateMessageReasoning` 的默认实现是空操作 ——
 * 用它测不出任何东西，必须覆写。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReasoningPersistenceTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** 记录思考落库调用。 */
    private class RecordingPersistence : ChatPersistence {
        /** 行号 -> 思考文本。null 值也会被记下来（用于验「不写空串」）。 */
        val reasoningWrites = mutableMapOf<Long, String?>()

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

        override suspend fun updateMessageContent(messageId: Long, content: String) = Unit

        /**
         * 这个覆写是整个测试的前提。
         *
         * 不覆写就会拿到接口的默认空实现，「思考有没有落库」永远测不出来 ——
         * 而那正是用户报的那个 bug。
         */
        override suspend fun updateMessageReasoning(messageId: Long, reasoning: String?) {
            reasoningWrites[messageId] = reasoning
        }

        override suspend fun loadMessages(sessionId: String): List<PersistedMessage> = emptyList()
        override suspend fun listSessions(): List<SessionItem> = emptyList()
        override suspend fun deleteSession(sessionId: String) = Unit
        override suspend fun setSessionPinned(sessionId: String, pinned: Boolean) = Unit
        override suspend fun updateSessionTitle(sessionId: String, title: String) = Unit
    }

    private class ScriptedProvider(private val events: List<AiStreamEvent>) : AiProvider {
        override val displayName: String = "scripted"

        override fun stream(request: ChatRequest): Flow<AiStreamEvent> = flow {
            events.forEach { emit(it) }
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 构造被测 ViewModel。
     *
     * ## 必须注入 `persistDispatcher`（2026-11-06）
     *
     * 它的默认值是 `Dispatchers.IO` —— 真实线程池。而 `restoreIfNeeded` 的
     * 「队列恢复 + 消息恢复 + 补偿发送」都在 `persistScope` 里跑（即那个
     * dispatcher），`advanceUntilIdle()` **只推进 test dispatcher**，
     * 等不到真实线程上的活。
     *
     * 后果：`send()` 被「等恢复」拦下，补偿发送迟迟不到，断言拿到空状态。
     * 换了 dispatcher 之后整条链路都可确定性推进。
     */
    private fun vm(
        events: List<AiStreamEvent>,
        persistence: ChatPersistence? = null,
    ) = ChatViewModel(
        provider = ScriptedProvider(events),
        model = "test",
        chatRepository = persistence,
        persistDispatcher = dispatcher,
    )

    private fun ChatViewModel.reasoningOfAssistant(): String? =
        state.value.bubbles.lastOrNull { it.role == BubbleRole.Assistant }?.reasoning

    /** 有思考时：落库 + 写进气泡 + 清空流式槽。 */
    @Test
    fun `reasoning is sealed into bubble and persisted`() = runTest {
        val p = RecordingPersistence()
        val vm = vm(
            listOf(
                AiStreamEvent.ReasoningDelta("先想一下"),
                AiStreamEvent.ReasoningDelta("……想好了"),
                AiStreamEvent.Delta("答案是 42"),
                AiStreamEvent.Done(finishReason = "stop"),
            ),
            p,
        )

        vm.onInputChange("问题")
        vm.send()
        advanceUntilIdle()

        // ① 落到气泡（折叠块显示的就是它）。
        assertEquals("先想一下……想好了", vm.reasoningOfAssistant())
        // ② 流式槽清空（不留两份权威文本）。
        assertNull("流式槽应清空", vm.state.value.streamingReasoning)
        // ③ 落库了，且文本与气泡一致。
        val written = p.reasoningWrites.values.filterNotNull()
        assertEquals("应有一次思考落库", 1, written.size)
        assertEquals("先想一下……想好了", written.first())
    }

    /**
     * 没有思考时不写库。
     *
     * 写空串会让 UI 画出一个空的折叠块 —— 看起来像坏了。
     * 所以契约是「值为 null」，而不是「值为空串」。
     */
    @Test
    fun `no reasoning means no write`() = runTest {
        val p = RecordingPersistence()
        val vm = vm(
            listOf(
                AiStreamEvent.Delta("直接回答"),
                AiStreamEvent.Done(finishReason = "stop"),
            ),
            p,
        )

        vm.onInputChange("问题")
        vm.send()
        advanceUntilIdle()

        assertNull("不该有思考", vm.reasoningOfAssistant())
        assertTrue(
            "没有思考时不该写库（写空串会画出一个空折叠块）",
            p.reasoningWrites.values.all { it == null },
        )
    }

    /**
     * 只在收尾写一次，不是每个 delta 写一次。
     *
     * 思考在 UI 上是一个整体，中途的半截没有单独价值；
     * 而每秒几十次写库会把 SQLite 打满。
     */
    @Test
    fun `reasoning is written once not per delta`() = runTest {
        val p = RecordingPersistence()
        val vm = vm(
            listOf(
                AiStreamEvent.ReasoningDelta("一"),
                AiStreamEvent.ReasoningDelta("二"),
                AiStreamEvent.ReasoningDelta("三"),
                AiStreamEvent.ReasoningDelta("四"),
                AiStreamEvent.Delta("答"),
                AiStreamEvent.Done(finishReason = "stop"),
            ),
            p,
        )

        vm.onInputChange("问题")
        vm.send()
        advanceUntilIdle()

        assertEquals("一二三四", vm.reasoningOfAssistant())
        assertEquals("多次 delta 只该写一次库", 1, p.reasoningWrites.size)
    }

    /** 思考与正文互不干扰：两者都要保留。 */
    @Test
    fun `body and reasoning are both kept`() = runTest {
        val p = RecordingPersistence()
        val vm = vm(
            listOf(
                AiStreamEvent.ReasoningDelta("推理内容"),
                AiStreamEvent.Delta("正文内容"),
                AiStreamEvent.Done(finishReason = "stop"),
            ),
            p,
        )

        vm.onInputChange("问题")
        vm.send()
        advanceUntilIdle()

        val last = vm.state.value.bubbles.last { it.role == BubbleRole.Assistant }
        assertEquals("推理内容", last.reasoning)
        assertEquals("正文内容", last.text)
    }

    /** 思考内容首尾空白会被去掉（模型常带换行）。 */
    @Test
    fun `reasoning is trimmed`() = runTest {
        val p = RecordingPersistence()
        val vm = vm(
            listOf(
                AiStreamEvent.ReasoningDelta("\n  想过了  \n"),
                AiStreamEvent.Delta("答"),
                AiStreamEvent.Done(finishReason = "stop"),
            ),
            p,
        )

        vm.onInputChange("问题")
        vm.send()
        advanceUntilIdle()

        assertEquals("想过了", vm.reasoningOfAssistant())
    }
}
