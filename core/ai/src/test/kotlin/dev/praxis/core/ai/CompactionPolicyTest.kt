package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩策略的现行契约：**不截断**（2026-11-07，用户要求）。
 *
 * 旧契约是「留首尾、砍中段、插 省略N字符 标记」。用户的实际反馈是
 * 「输入太长会被省略」—— 长消息和工具输出都被削成残句，模型基于半截内容
 * 作答，而用户以为自己说全了。
 *
 * 所以现在的契约只剩一条：内容原样返回。真正装不下时由 AiContextBudget
 * 整条丢弃最旧的消息（丢整条，看得见少了哪一句）。
 */
class CompactionPolicyTest {

    @Test
    fun 长文本原样返回不砍中段() {
        val long = "H".repeat(200) + "M".repeat(500) + "T".repeat(80)

        val out = CompactionPolicy.compactText(long)

        assertEquals("必须与输入完全一致", long, out)
        assertTrue("不能包含省略标记", !out.contains("省略"))
    }

    @Test
    fun 工具返回原样返回() {
        val long = "Z".repeat(5000)

        val out = CompactionPolicy.compactToolResult(long)

        assertEquals(long, out)
        assertTrue(!out.contains("省略"))
    }

    @Test
    fun 工具调用参数原样返回() {
        // 长参数极易被误当成普通文本裁掉，导致上游解析失败。
        val long = "x".repeat(2000)

        val out = CompactionPolicy.compactArguments(long)

        assertSame(long, out)
        assertEquals(long.length, out.length)
    }

    @Test
    fun 代理对不会被切断() {
        val emoji = "😀"
        val text = emoji.repeat(400)

        val out = CompactionPolicy.compactText(text)

        assertEquals(text, out)
        assertFalse(Character.isLowSurrogate(out.first()))
        assertFalse(Character.isHighSurrogate(out.last()))
    }
}
