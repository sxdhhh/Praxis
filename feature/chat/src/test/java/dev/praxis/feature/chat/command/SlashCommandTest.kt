package dev.praxis.feature.chat.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/` 命令解析的边界。
 *
 * ## 为什么要这么细
 *
 * 解析器一旦「宽容」，就会把用户的普通消息误吞成命令 ——
 * 例如用户真想发「/usr/bin 这个路径怎么改」。那种 bug 表现为
 * **消息发不出去且没有提示**，用户只会觉得「这个 App 坏了」。
 *
 * 所以每一条拒绝规则都必须有测试钉住。
 */
class SlashCommandTest {

    @Test
    fun `裸命令名`() {
        val parsed = SlashCommand.parse("/plan")
        assertNotNull(parsed)
        assertEquals("plan", parsed!!.name)
        assertEquals("", parsed.rawInput)
    }

    @Test
    fun `带参数时保留分隔空白`() {
        val parsed = SlashCommand.parse("/plan review")
        assertEquals("plan", parsed!!.name)
        // 保留前导空格：handler 需要还原用户到底打了什么。
        assertEquals(" review", parsed.rawInput)
    }

    @Test
    fun `多个空格原样保留`() {
        assertEquals("  review", SlashCommand.parse("/plan  review")!!.rawInput)
    }

    /** 前导空格不认：可能是缩进、可能是引用块。 */
    @Test
    fun `前导空格不是命令`() {
        assertNull(SlashCommand.parse(" /plan"))
    }

    /** 大写不认：避免 /Plan 与 /plan 被视为同一命令的歧义。 */
    @Test
    fun `大写不是命令`() {
        assertNull(SlashCommand.parse("/Plan"))
    }

    /** 路径不能被当成命令 —— 这是最容易被误吞的真实输入。 */
    @Test
    fun `路径不是命令`() {
        assertNull(SlashCommand.parse("/usr/bin"))
    }

    @Test
    fun `非法字符不是命令`() {
        assertNull(SlashCommand.parse("/plan?"))
        assertNull(SlashCommand.parse("/pl an"))
    }

    @Test
    fun `只有斜杠不是命令`() {
        assertNull(SlashCommand.parse("/"))
        assertNull(SlashCommand.parse("//"))
    }

    @Test
    fun `不在开头不是命令`() {
        assertNull(SlashCommand.parse("hi /plan"))
    }

    @Test
    fun `空串不是命令`() {
        assertNull(SlashCommand.parse(""))
    }

    @Test
    fun `连字符与下划线合法`() {
        assertEquals("a-b_c1", SlashCommand.parse("/a-b_c1")!!.name)
    }

    @Test
    fun `数字开头不合法`() {
        assertNull(SlashCommand.parse("/1plan"))
    }

    @Test
    fun `未知命令解析成功但查不到定义`() {
        val parsed = SlashCommand.parse("/nope")
        assertNotNull("语法上仍是命令", parsed)
        assertNull("但注册表里没有", SlashCommand.find(parsed!!, BuiltinCommands.all))
    }

    @Test
    fun `已知命令能查到定义`() {
        val parsed = SlashCommand.parse("/compact")!!
        assertEquals("compact", SlashCommand.find(parsed, BuiltinCommands.all)?.name)
    }

    /** 内置命令都是本地操作，不应提交给模型。 */
    @Test
    fun `内置命令都不提交模型`() {
        BuiltinCommands.all.forEach {
            assertEquals("${it.name} 不该提交给模型", false, it.submitsToModel)
        }
    }

    /** 命令名必须都合法，否则 `/xxx` 永远匹配不到。 */
    @Test
    fun `内置命令名全部可被解析`() {
        BuiltinCommands.all.forEach {
            val parsed = SlashCommand.parse("/" + it.name)
            assertNotNull("${it.name} 无法被解析", parsed)
            assertEquals(it.name, parsed!!.name)
        }
    }
}
