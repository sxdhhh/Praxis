package dev.praxis.feature.chat

import dev.praxis.core.ai.AiProvider
import dev.praxis.core.ai.AiStreamEvent
import dev.praxis.core.ai.ChatRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「AI 生成会话标题」的**触发点**契约。
 *
 * ## 为什么必须有这条测试（2026-11-01）
 *
 * 这条链路的每一块都单独存在、单独能跑、单独有测试：
 *
 *  - [SessionTitle]（清洗 / 兜底 / 提示词）—— 有 `SessionTitleTest`；
 *  - [SettingsBackedTitleGenerator] —— 在 `:app`，构造好、注入了；
 *  - `ChatViewModel.generateTitleIfPossible` —— 写好了，还带三条防御；
 *  - `:app` 里 `titleGenerator = SettingsBackedTitleGenerator(...)` —— 接了。
 *
 * 但它们之间的**那根线**断了：`generateTitleIfPossible` 被写在
 * `launchPersist { … }` 里面，而它内部是一次真实的网络请求。
 * 于是标题生成被「写库成功」这一个前置条件拖住 —— 用户看到的现象是
 * 「对话一切正常，只是会话列表里永远只有首句前 24 字」。
 *
 * 这正是 README 那条纪律说的形态：**能力在、接线在、触发点不在**。
 * 单测测的都是零件，没有一条测「发了第一条消息之后标题到底有没有被生成」。
 *
 * 这条测试补的就是那一环：用假 provider + 假仓储跑一次 `send()`，
 * 断言 [ChatTitleGenerator] 真的被调用、且结果真的写回了会话标题。
 */
class ChatTitleTriggerTest {

    // ## 必须换掉 Main（2026-11-01 修）
    //
    // `ChatViewModel` 在构造时就访问 `viewModelScope`，而它跑在
    // `Dispatchers.Main.immediate` 上 —— 纯 JVM 单测里没有 Android Looper。
    // 同时 `persistScope` 的调度器要注入同一个测试调度器，否则
    // `advanceUntilIdle()` 推不动恢复 / 落盘 / 标题生成，断言会在协程
    // 跑完之前执行，得到「生成器没被调用」这类假失败。
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 只吐一个 Done 的空回答：本测试不关心聊天内容，只关心标题副作用。 */
    private class SilentProvider : AiProvider {
        override val displayName: String = "silent"
        override fun stream(request: ChatRequest): Flow<AiStreamEvent> = flow {
            emit(AiStreamEvent.Done(finishReason = "stop"))
        }
    }

    /**
     * 记录写库动作的假仓储。
     *
     * `titles` 模拟库里的当前标题：`ensureSession` 会把它设成兜底值，
     * `updateSessionTitle` 覆盖它，而 `generateTitleIfPossible` 靠
     * `listSessions()` 读回来比较 —— 三者必须一起假，否则「只在标题仍是
     * 兜底值时才覆盖」那条防御测不出来。
     */
    private class FakePersistence : ChatPersistence {
        val titles = mutableMapOf<String, String>()
        private var rowId = 1L

        override suspend fun ensureSession(sessionId: String, title: String) {
            // 与真实实现一致：已存在就不改（见 ChatRepository.ensureSession）。
            titles.putIfAbsent(sessionId, title)
        }

        override suspend fun appendMessage(
            sessionId: String,
            role: String,
            content: String,
            toolName: String?,
            toolArgs: String?,
            toolCallId: String?,
            isError: Boolean,
        ): Long = rowId++

        override suspend fun updateMessageContent(messageId: Long, content: String) = Unit

        override suspend fun loadMessages(sessionId: String): List<PersistedMessage> = emptyList()

        override suspend fun listSessions(): List<SessionItem> =
            titles.map { (id, t) -> SessionItem(id = id, title = t, updatedAt = 0L) }

        override suspend fun deleteSession(sessionId: String) {
            titles.remove(sessionId)
        }

        override suspend fun setSessionPinned(sessionId: String, pinned: Boolean) = Unit

        override suspend fun updateSessionTitle(sessionId: String, title: String) {
            titles[sessionId] = title
        }
    }

    @Test
    fun `发第一条消息后会调用标题生成并把结果写回会话`() = runTest {
        val repo = FakePersistence()
        val seen = mutableListOf<String>()
        val vm = ChatViewModel(
            provider = SilentProvider(),
            model = "test",
            chatRepository = repo,
            sessionId = "s1",
            // 真实实现里这一步是一次网络请求，这里只验证「被调用、结果被用上」。
            titleGenerator = ChatTitleGenerator { userText ->
                seen += userText
                "模型总结的标题"
            },
            persistDispatcher = testDispatcher,
        )

        vm.onInputChange("帮我把这个报错查一下")
        vm.send()
        advanceUntilIdle()

        assertEquals(
            "标题生成必须拿到用户原文",
            listOf("帮我把这个报错查一下"),
            seen,
        )
        assertEquals(
            "AI 标题必须覆盖兜底标题写回会话（生成失败才保留兜底）",
            "模型总结的标题",
            repo.titles["s1"],
        )
    }

    @Test
    fun `不是第一条消息时不重复生成标题`() = runTest {
        val repo = FakePersistence()
        var calls = 0
        val vm = ChatViewModel(
            provider = SilentProvider(),
            model = "test",
            chatRepository = repo,
            sessionId = "s2",
            titleGenerator = ChatTitleGenerator {
                calls++
                "AI 标题 " + calls
            },
            persistDispatcher = testDispatcher,
        )

        vm.onInputChange("第一句")
        vm.send()
        advanceUntilIdle()
        vm.onInputChange("第二句")
        vm.send()
        advanceUntilIdle()

        // 每轮都重新总结会让标题不停变，侧边栏里看起来像在乱跳。
        assertEquals("只有本会话第一条消息才触发生成", 1, calls)
        assertEquals("AI 标题 1", repo.titles["s2"])
    }

    @Test
    fun `生成失败时保留兜底标题且不影响落盘`() = runTest {
        val repo = FakePersistence()
        val vm = ChatViewModel(
            provider = SilentProvider(),
            model = "test",
            chatRepository = repo,
            sessionId = "s3",
            titleGenerator = ChatTitleGenerator { error("网络断了") },
            persistDispatcher = testDispatcher,
        )

        vm.onInputChange("  多行 输入\n带换行  ")
        vm.send()
        advanceUntilIdle()

        // 兜底标题 = 压平空白后截断（见 SessionTitle.fallback）。
        assertEquals(
            "生成失败必须保留兜底标题，而不是留空或抛到 UI",
            "多行 输入 带换行",
            repo.titles["s3"],
        )
        // 标题失败不该把整轮发送标记成失败：用户消息气泡必须还在。
        assertTrue(vm.state.value.bubbles.any { it.role == BubbleRole.User })
    }
}