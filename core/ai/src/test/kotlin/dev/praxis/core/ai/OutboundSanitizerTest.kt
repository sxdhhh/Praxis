package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 出站脱敏（文档 §5.18）。 */
class OutboundSanitizerTest {

    @Test
    fun `没有规则时原样返回`() {
        assertEquals("原文", OutboundSanitizer.apply("原文", emptyList()))
    }

    @Test
    fun `命中就替换`() {
        val rules = listOf(SanitizeRule(pattern = "\\d{11}"))
        assertEquals("我的号是 ***", OutboundSanitizer.apply("我的号是 13800138000", rules))
    }

    @Test
    fun `自定义替换文本`() {
        val rules = listOf(SanitizeRule(pattern = "secret", replacement = "[已隐藏]"))
        assertEquals("这是 [已隐藏]", OutboundSanitizer.apply("这是 secret", rules))
    }

    @Test
    fun `禁用的规则不生效`() {
        val rules = listOf(SanitizeRule(pattern = "abc", enabled = false))
        assertEquals("abc", OutboundSanitizer.apply("abc", rules))
    }

    @Test
    fun `坏正则被单独挑出 不影响其它规则`() {
        // 用户在设置里手写正则，写错是常态；让整条请求失败比不脱敏更糟。
        val rules = listOf(
            SanitizeRule(pattern = "(["),
            SanitizeRule(pattern = "\\d{11}"),
        )
        val compiled = OutboundSanitizer.compile(rules)
        assertEquals(listOf("(["), compiled.broken)
        assertEquals(1, compiled.rules.size)
        assertEquals("***", OutboundSanitizer.apply("13800138000", rules))
    }

    @Test
    fun `空 pattern 被跳过（不算坏正则）`() {
        val compiled = OutboundSanitizer.compile(listOf(SanitizeRule(pattern = "   ")))
        assertTrue(compiled.rules.isEmpty())
        assertTrue("空 pattern 不是「坏正则」", compiled.broken.isEmpty())
    }

    @Test
    fun `大小写敏感（不预设放宽）`() {
        // 令牌常是大小写敏感的，放宽会把用户没打算改的文本也改掉。
        val rules = listOf(SanitizeRule(pattern = "token"))
        assertEquals("Token 不动 token→***", "Token 不动 ***", OutboundSanitizer.apply("Token 不动 token", rules))
    }

    @Test
    fun `顺序由列表决定（后一条看得到前一条的结果）`() {
        val rules = listOf(
            SanitizeRule(pattern = "a-b", replacement = "X"),
            SanitizeRule(pattern = "X", replacement = "Y"),
        )
        assertEquals("Y", OutboundSanitizer.apply("a-b", rules))
    }

    @Test
    fun `编解码往返`() {
        val rules = listOf(
            SanitizeRule(pattern = "\\d+", replacement = "#", enabled = true),
            SanitizeRule(pattern = "x", replacement = "***", enabled = false),
        )
        assertEquals(rules, SanitizeRuleCodec.decode(SanitizeRuleCodec.encode(rules))?.getOrNull())
    }

    @Test
    fun `没配过返回 null 坏内容返回失败`() {
        assertEquals(null, SanitizeRuleCodec.decode(null))
        assertEquals(null, SanitizeRuleCodec.decode("  "))
        assertTrue(SanitizeRuleCodec.decode("不是 JSON")?.isFailure == true)
    }
}
