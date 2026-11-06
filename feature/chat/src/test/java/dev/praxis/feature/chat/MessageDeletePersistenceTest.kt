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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 删除必须落盘（BUG-C）。
 *
 * ## 它守的是什么（2026-11-07）
 *
 * 用户原话：「点击删除结果重启软件后删除的卡片又回来了」。
 *
 * 根因不是「忘了调用删除」，而是**端口层根本没有这个方法**：
 * ChatPersistence 原来只有 deleteSession（连会话一起删）和
 * clearMessages（清空全部），没有「删一条」。于是 ChatViewModel.removeBubble
 * 只能把它从 bubbles 与 history 里摘掉，数据库那一行原封不动；
 * 下次 loadMessages 一读，卡片就复活。
 *
 * ## 为什么测试替身必须「有状态」
 *
 * 用记录式替身（只记录调用）能证明「删了」，但证明不了「重启不复活」。
 * 只有真的把行从表里移除、再让新的 ViewModel 从同一份表恢复，
 * 才能覆盖用户实际经历的那条路径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageDeletePersistenceTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /**
     * 有状态的内存「数据库」。
     *
     * rows 就是表：appendMessage 插行、deleteMessage 删行、
     * loadMessages 读行。这样「删掉再重进」才能被真实验证。
     */
    private class InMemoryPersistence : ChatPersistence {
        val rows = linkedMapOf<Long, PersistedMessage>()
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
        ): Long {
            val id = nextId++
            rows[id] = PersistedMessage(
                id = id,
                role = role,
                content = content,
                toolName = toolName,
                toolArgs = toolArgs,
                isError = isError,
                createdAt = id,
                orderIndex = (id - 1).toInt(),
            )
            return id
        }

        override suspend fun updateMessageContent(messageId: Long, content: String) {
            rows[messageId]?.let { rows[messageId] = it.copy(content = content) }
        }

        /**
         * 整个测试的前提。
         *
         * 不覆写就会拿到端口的空默认实现 —— 那正是 BUG-C 的形态：
         * 编译通过、界面正常、只有重启才暴露。
         */
        override suspend fun deleteMessage(messageId: Long) {
            rows.remove(messageId)
        }

        override suspend fun loadMessages(sessionId: String): List<PersistedMessage> =
            rows.values.toList()

        override suspend fun listSessions(): List<SessionItem> = emptyList()
        override suspend fun deleteSession(sessionId: String) = Unit
        override suspend fun setSessionPinned(sessionId: String, pinned: Boolean) = Unit
        override suspend fun updateSessionTitle(sessionId: String, title: String) = Unit
    }

    private class SilentProvider : AiProvider {
        override val displayName: String = "silent"

        override fun stream(request: ChatRequest): Flow<AiStreamEvent> = flow {
            emit(AiStreamEvent.Delta("好的"))
            emit(AiStreamEvent.Done(finishReason = "stop"))
        }
    }

    private fun vm(persistence: ChatPersistence) = ChatViewModel(
        provider = SilentProvider(),
        model = "test",
        chatRepository = persistence,
        persistDispatcher = dispatcher,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 删用户气泡：库里那一行必须消失。 */
    @Test
    fun deletingUserBubbleRemovesItsRow() = runTest {
        val p = InMemoryPersistence()
        val vm = vm(p)

        vm.onInputChange("你好")
        vm.send()
        advanceUntilIdle()

        // 一轮对话落两行：user + assistant 占位。
        assertEquals("应先落 user 与 assistant 两行", 2, p.rows.size)
        val userBubble = vm.state.value.bubbles.first { it.role == BubbleRole.User }

        vm.onMessageAction(userBubble.id, MessageAction.Delete)
        vm.confirmDeleteMessage()
        advanceUntilIdle()

        assertEquals("库里的 user 行应被删掉", 1, p.rows.size)
        assertTrue("剩下的应是 assistant", p.rows.values.none { it.role == "user" })
    }

    /** 删完「重启」（新 VM 从同一份库恢复）不该复活。 */
    @Test
    fun deletedMessageDoesNotReviveAfterReload() = runTest {
        val p = InMemoryPersistence()
        val first = vm(p)

        first.onInputChange("这句话我要删掉")
        first.send()
        advanceUntilIdle()

        val userBubble = first.state.value.bubbles.first { it.role == BubbleRole.User }
        first.onMessageAction(userBubble.id, MessageAction.Delete)
        first.confirmDeleteMessage()
        advanceUntilIdle()

        // 模拟重启：同一个 persistence，全新的 ViewModel。
        val second = vm(p)
        second.restoreIfNeeded()
        advanceUntilIdle()

        assertTrue(
            "被删的用户消息重启后不该复活",
            second.state.value.bubbles.none { it.role == BubbleRole.User },
        )
        assertFalse(
            "库里也不该再有它",
            p.rows.values.any { it.content.contains("这句话我要删掉") },
        )
    }

    /** 取消删除时不得动库（防止「点取消也删了」）。 */
    @Test
    fun cancellingDeleteKeepsTheRow() = runTest {
        val p = InMemoryPersistence()
        val vm = vm(p)

        vm.onInputChange("保留")
        vm.send()
        advanceUntilIdle()
        val before = p.rows.size

        val userBubble = vm.state.value.bubbles.first { it.role == BubbleRole.User }
        vm.onMessageAction(userBubble.id, MessageAction.Delete)
        vm.dismissDeleteMessage()
        advanceUntilIdle()

        assertEquals("取消删除不该动库", before, p.rows.size)
    }
}
