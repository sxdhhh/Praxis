package dev.praxis.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式文本槽（`ChatState.streamingText`）的语义测试。
 *
 * ## 为什么这一层必须锁住
 *
 * 它是「长对话不卡」的实现基础：流式正文不再改动 `bubbles`，
 * 于是所有以 `bubbles` 为 key 的下游计算（最贵的是定位器预览的全文归一化）
 * 在流式期间**完全不执行**。
 *
 * 一旦有人把文本改回写 `bubbles`，性能会静默回退 —— 不会有编译错误、
 * 不会有崩溃，只是「聊久了开始卡」。这类回退只能靠测试拦住。
 *
 * 这里不测 Composable（那要 instrumentation），只测状态契约：
 *  1. 文本更新**不换** `bubbles` 的引用；
 *  2. 流式槽与 `bubbles` 的取值优先级；
 *  3. 定稿后槽被清空（不留两份权威文本）。
 */
class ChatStreamingTextTest {

    /** 构造一个「已定稿」的气泡。 */
    private fun bubble(id: Long, text: String, streaming: Boolean = false) = ChatBubble(
        id = id,
        role = BubbleRole.Assistant,
        text = text,
        streaming = streaming,
    )

    @Test
    fun `默认没有流式文本`() {
        assertTrue(ChatState().streamingText.isEmpty())
    }

    @Test
    fun `写入流式文本不改变 bubbles 的内容`() {
        val before = ChatState(bubbles = listOf(bubble(1, "已完成")))
        // 模拟 `applyStreamText` 的效果（不经过 ViewModel，直接构造状态）。
        val after = before.copy(streamingText = mapOf(2L to "正在"))

        assertEquals(before.bubbles, after.bubbles)
        // 这一条是关键：`bubbles` 必须是**同一个**列表对象。
        // 引用相等才能保证下游 `remember(bubbles)` 不重算。
        assertSame(before.bubbles, after.bubbles)
    }

    @Test
    fun `气泡文本优先取流式槽`() {
        // UI 的取值公式就是这一行（ChatScreen 里 `?:`）。
        val state = ChatState(
            bubbles = listOf(bubble(2, "")),
            streamingText = mapOf(2L to "片段"),
        )
        val display = state.streamingText[2L] ?: state.bubbles[0].text
        assertEquals("片段", display)
    }

    @Test
    fun `没有流式槽时退回气泡文本`() {
        val state = ChatState(bubbles = listOf(bubble(3, "定稿内容")))
        val display = state.streamingText[3L] ?: state.bubbles[0].text
        assertEquals("定稿内容", display)
    }

    @Test
    fun `其它气泡的流式槽不影响本条`() {
        val state = ChatState(
            bubbles = listOf(bubble(1, "第一条"), bubble(2, "第二条")),
            streamingText = mapOf(2L to "第二条-流式中"),
        )
        // 第一条不受影响：map 按 id 索引，不会串到别的条目。
        assertEquals("第一条", state.streamingText[1L] ?: state.bubbles[0].text)
        assertEquals("第二条-流式中", state.streamingText[2L] ?: state.bubbles[1].text)
    }

    @Test
    fun `定稿后流式槽为空`() {
        // `sealStreamText` 的效果：文本合进 bubbles、槽清掉。
        val sealed = ChatState(
            bubbles = listOf(bubble(5, "完整回答")),
            streamingText = emptyMap(),
        )
        assertNull(sealed.streamingText[5L])
        assertEquals("完整回答", sealed.bubbles[0].text)
    }

    @Test
    fun `同一 id 的文本被覆盖而不是追加`() {
        // 流式槽存的是「当前全文」，不是增量片段。
        // 若误做成追加，UI 会看到重复文本（模型输出两遍）。
        var state = ChatState()
        state = state.copy(streamingText = state.streamingText + (7L to "一"))
        state = state.copy(streamingText = state.streamingText + (7L to "一二"))

        assertEquals("一二", state.streamingText[7L])
    }

    @Test
    fun `并发多条流式时各自独立`() {
        // 工具调用场景可能同时有 2~3 条在写（模型并行产出）。
        var state = ChatState()
        state = state.copy(streamingText = state.streamingText + (1L to "A1"))
        state = state.copy(streamingText = state.streamingText + (2L to "B1"))
        state = state.copy(streamingText = state.streamingText + (1L to "A2"))

        assertEquals("A2", state.streamingText[1L])
        assertEquals("B1", state.streamingText[2L])
        assertEquals(2, state.streamingText.size)
    }
}