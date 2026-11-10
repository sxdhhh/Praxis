package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 脱敏设置（开关 + 规则）与「一行一条」的文本互转。 */
class SanitizeSettingsTest {

    @Test
    fun `开关关着时规则不生效 但保留`() {
        // 开关与规则是两件事：「先别改我的正文」不等于「把规则全删了」。
        val settings = SanitizeSettings(
            enabled = false,
            rules = listOf(SanitizeRule(pattern = "\\d+")),
        )
        assertTrue(settings.effectiveRules.isEmpty())
        assertEquals(1, settings.rules.size)
    }

    @Test
    fun `开关打开且有序规则时才生效`() {
        val settings = SanitizeSettings(enabled = true, rules = listOf(SanitizeRule("x")))
        assertEquals(1, settings.effectiveRules.size)
    }

    @Test
    fun `默认是关的`() {
        // 改写用户正文是有副作用的操作，不该默认发生。
        assertFalse(SanitizeSettings().enabled)
    }

    @Test
    fun `编解码往返`() {
        val settings = SanitizeSettings(
            enabled = true,
            rules = listOf(
                SanitizeRule("\\d{11}", "***", true),
                SanitizeRule("token", "[X]", false),
            ),
        )
        assertEquals(settings, SanitizeSettingsCodec.decode(SanitizeSettingsCodec.encode(settings))?.getOrNull())
    }

    @Test
    fun `没配过返回 null 坏内容返回失败`() {
        assertNull(SanitizeSettingsCodec.decode(null))
        assertNull(SanitizeSettingsCodec.decode("   "))
        assertTrue(SanitizeSettingsCodec.decode("not json")?.isFailure == true)
        assertTrue(SanitizeSettingsCodec.decode("""{"rules":[{}]}""")?.isFailure == true)
    }

    @Test
    fun `缺键用默认值（schema 演进）`() {
        val decoded = SanitizeSettingsCodec.decode("""{"enabled":true}""")?.getOrNull()
        assertTrue(decoded!!.enabled)
        assertTrue(decoded.rules.isEmpty())
    }

    @Test
    fun `文本转规则：忽略空行与注释行`() {
        // 用户会想给自己的规则加个说明。
        val rules = SanitizeRuleLines.fromText("\\d{11}\n\n# 手机号\ntoken")
        assertEquals(listOf("\\d{11}", "token"), rules.map { it.pattern })
    }

    @Test
    fun `规则转文本再转回来不丢`() {
        val rules = listOf(SanitizeRule("a"), SanitizeRule("b"))
        assertEquals(rules, SanitizeRuleLines.fromText(SanitizeRuleLines.toText(rules)))
    }
}
