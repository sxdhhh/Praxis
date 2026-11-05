package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩的收益投影与可逆性。
 *
 * ## 为什么这条链必须钉住
 *
 * 旧模型只有 `coveredCount`（折叠了几条）。而「几条」回答不了用户真正
 * 关心的问题：**这次压缩到底省了多少上下文**？
 *
 * 3 条短消息和 3 条长工具输出，折叠条数一样，省下的预算差几十倍。
 */
class CompactionBenefitTest {

    /**
     * 净收益 = 折叠掉 - 摘要本身。
     *
     * 只报摘要大小是不对的：摘要本身也占上下文，真正的收益要减掉它。
     */
    @Test
    fun `净收益要减去摘要开销`() {
        val summary = "摘".repeat(100) // 约 100 token
        val compacted = CompactedContext(
            summary = summary,
            coveredCount = 5,
            coveredTokens = 1000,
        )
        assertEquals(1000 - compacted.summaryTokens, compacted.savedTokens)
        assertTrue("净收益应为正", compacted.savedTokens > 0)
    }

    /**
     * 只折叠两条短消息时净收益**可能为负**，必须如实反映。
     *
     * 掩盖负值（clamp 到 0）会让用户失去「这次压得不值」的判断依据 ——
     * 而压缩是有损的，压得不值就不该压。
     */
    @Test
    fun `小压缩可能负收益`() {
        val compacted = CompactedContext(
            summary = "这是一段比原文还长的摘要".repeat(10),
            coveredCount = 2,
            coveredTokens = 10,
        )
        assertTrue("应能表示负收益", compacted.savedTokens < 0)
    }

    /** 未记录 coveredTokens 时退回 0（老存档读回来），不应崩。 */
    @Test
    fun `缺 coveredTokens 时收益为零减去摘要`() {
        val compacted = CompactedContext(summary = "摘要", coveredCount = 3)
        assertEquals(0, compacted.coveredTokens)
        assertEquals(-compacted.summaryTokens, compacted.savedTokens)
    }

    /**
     * 压缩**不删除原始消息** —— 这是可逆性的基础。
     *
     * 若将来有人改成「物理删除被折叠的消息」，撤销就再也回不来了。
     */
    @Test
    fun `压缩只改发送范围不删原文`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.System, text = "系统"),
            ChatMessage(role = ChatRole.User, text = "a"),
            ChatMessage(role = ChatRole.Assistant, text = "b"),
            ChatMessage(role = ChatRole.User, text = "c"),
        )
        val compacted = CompactedContext(summary = "摘要", coveredCount = 2)
        val applied = CompactionApplier.apply(messages, compacted)
        // 被折叠的两条不再发送……
        assertTrue(applied.none { it.text == "a" || it.text == "b" })
        // ……但**入参 messages 本身未被修改**（可逆的前提）。
        assertEquals(4, messages.size)
    }

    /** 撤销 = 传 None，所有消息都应回来。 */
    @Test
    fun `撤销后全部消息回来`() {
        val messages = listOf(
            ChatMessage(role = ChatRole.User, text = "a"),
            ChatMessage(role = ChatRole.Assistant, text = "b"),
        )
        val applied = CompactionApplier.apply(messages, CompactedContext.None)
        assertEquals(messages, applied)
    }
}
