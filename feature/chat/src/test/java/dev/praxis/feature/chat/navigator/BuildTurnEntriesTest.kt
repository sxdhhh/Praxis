package dev.praxis.feature.chat.navigator

import dev.praxis.feature.chat.BubbleRole
import dev.praxis.feature.chat.ChatBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定位器「按轮聚合」的回归测试。
 *
 * ## 它守的是什么
 *
 * 旧实现逐条收气泡，一轮 AI 回复（多条 Assistant + 中间的工具卡）在定位器里
 * 变成「4 ai / 5 ai / 6 ai / 7 ai」、内容是「0」这种空正文。
 * 用户看到十几条一模一样的 ai 条目，完全无法据此跳转。
 *
 * 这类缺陷**编译器、lint、UI 测试都不报**：页面照常渲染、条目照常可点，
 * 只是「列表内容不对」。所以用纯 JVM 单测钉住聚合规则。
 */
class BuildTurnEntriesTest {

    private var seq = 0L

    private fun bubble(
        role: BubbleRole,
        text: String,
    ) = ChatBubble(id = ++seq, role = role, text = text, timestamp = seq * 1000)

    /** 一轮完整对话：提问 → 说话 → 工具 → 工具 → 说话 → 提问。 */
    @Test
    fun oneTurnWithTools_collapsesToOneEntry() {
        val bubbles = listOf(
            bubble(BubbleRole.User, "你好"),
            bubble(BubbleRole.Assistant, "我先看看"),
            bubble(BubbleRole.Tool, "tool-result-1"),
            bubble(BubbleRole.Tool, "tool-result-2"),
            bubble(BubbleRole.Assistant, "结论如下"),
            bubble(BubbleRole.User, "再见"),
            bubble(BubbleRole.Assistant, "bye"),
        )

        val entries = buildTurnEntries(bubbles)

        // 4 轮：用户"你好" / AI 回复（合并） / 用户"再见" / AI "bye"
        assertEquals("一轮 AI 回复应合并成一条", 4, entries.size)
        assertEquals(listOf("user", "ai", "user", "ai"), entries.map { it.preview.sender })
        // 合并条落在轮首（"我先看看" 那条 Assistant），跳转才能落到回复开头。
        assertEquals(1, entries[1].bubbleIndex)
    }

    /** 合并条的预览取「第一段非空正文」，不是工具结果也不是空串。 */
    @Test
    fun mergedPreview_usesFirstNonBlankAssistantText() {
        val bubbles = listOf(
            bubble(BubbleRole.User, "问题"),
            bubble(BubbleRole.Assistant, ""),           // 只吐了 tool_calls
            bubble(BubbleRole.Tool, "工具输出不应该当预览"),
            bubble(BubbleRole.Assistant, "真正的回答"),
        )

        val entries = buildTurnEntries(bubbles)

        assertEquals(2, entries.size)
        assertEquals("真正的回答", entries[1].preview.previewContent)
    }

    /** contentLength 取整轮正文之和：条形图要反映这一轮的体量。 */
    @Test
    fun mergedContentLength_sumsAllAssistantText() {
        val bubbles = listOf(
            bubble(BubbleRole.User, "q"),
            bubble(BubbleRole.Assistant, "abc"),
            bubble(BubbleRole.Tool, "x"),
            bubble(BubbleRole.Assistant, "de"),
        )

        assertEquals(5, buildTurnEntries(bubbles)[1].preview.contentLength)
    }

    /** Error 单独成条，且不把后面的 Assistant 吞进同一轮。 */
    @Test
    fun error_isItsOwnEntryAndBreaksTheTurn() {
        val bubbles = listOf(
            bubble(BubbleRole.User, "q"),
            bubble(BubbleRole.Assistant, "a"),
            bubble(BubbleRole.Error, "超时"),
            bubble(BubbleRole.Assistant, "重试后的回答"),
        )

        val entries = buildTurnEntries(bubbles)

        assertEquals(4, entries.size)
        assertEquals(listOf("user", "ai", "error", "ai"), entries.map { it.preview.sender })
    }

    /** 游离的 Tool（前面没有 Assistant）不单独成条。 */
    @Test
    fun orphanTool_isSkipped() {
        val bubbles = listOf(
            bubble(BubbleRole.User, "q"),
            bubble(BubbleRole.Tool, "孤立的工具结果"),
            bubble(BubbleRole.Assistant, "a"),
        )

        val entries = buildTurnEntries(bubbles)

        assertEquals(2, entries.size)
        assertTrue(entries.none { it.preview.sender == "tool" })
    }

    /** 空列表不炸。 */
    @Test
    fun emptyInput_returnsEmpty() {
        assertTrue(buildTurnEntries(emptyList()).isEmpty())
    }
}
