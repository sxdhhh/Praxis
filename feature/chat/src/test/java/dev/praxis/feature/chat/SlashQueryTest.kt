package dev.praxis.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `slashQuery`（命令候选的过滤词）的取值规则。
 *
 * ## 为什么这个派生属性值得单独测
 *
 * 它同时决定「命令面板显不显示」与「按什么过滤」。判错的表现是：
 *  - 该收起时没收起 → 面板挂在输入框上方挡视线；
 *  - 该显示时没显示 → 用户打了 `/` 什么都没发生，以为功能坏了。
 */
class SlashQueryTest {

    private fun queryOf(input: String) = ChatState(input = input).slashQuery

    @Test
    fun `只有斜杠时过滤词为空`() {
        // 空串表示「显示全部命令」。
        assertEquals("", queryOf("/"))
    }

    @Test
    fun `打了一半返回前缀`() {
        assertEquals("co", queryOf("/co"))
    }

    @Test
    fun `命令名打全仍显示`() {
        // 仍显示，便于用户确认「要打的就是这条」再按回车。
        assertEquals("compact", queryOf("/compact"))
    }

    @Test
    fun `进入参数阶段就收起`() {
        assertNull(queryOf("/compact "))
        assertNull(queryOf("/plan review"))
    }

    @Test
    fun `不在开头不显示`() {
        assertNull(queryOf("hi /co"))
        assertNull(queryOf(" /co"))
    }

    @Test
    fun `普通输入不显示`() {
        assertNull(queryOf("你好"))
        assertNull(queryOf(""))
    }
}
