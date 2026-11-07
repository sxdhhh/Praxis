package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示词分层与优先级（2026-11-07：改成单条 + 模板填充）。
 *
 * 用户要求「系统提示词权重最高，用户其次，让 ai 一定要照着来不能偏离」。
 * 这三条里「权重」是唯一的实现事实，必须被测试钉住 ——
 * 顺序被后来的改动打乱时，功能会静默失效（模型仍会回答，只是不再守规则）。
 *
 * ## 现在为什么是**一条**而不是五条
 *
 * 照搬 Operit：它从头到尾只有一条 system，模板占位符逐段填充。
 * 五条 system 的问题在于多数网关只保留其中一条，用户写的规则可能整条丢失，
 * 而界面上没有任何异常可查。
 *
 * 所以在单条里断言**各层的相对顺序**：用 indexOf 比较，而不是数列表下标。
 */
class SystemPromptBuilderTest {

    private fun only(settings: SystemPromptSettings, skills: String? = null, memory: String? = null, summary: String? = null): String =
        SystemPromptBuilder.buildLayers(
            settings = settings,
            skills = skills,
            memory = memory,
            summary = summary,
        ).single().text

    @Test
    fun 只产出一条system且角色正确() {
        val layers = SystemPromptBuilder.buildLayers(settings = SystemPromptSettings(custom = "规则"))
        assertEquals(1, layers.size)
        assertEquals(ChatRole.System, layers[0].role)
        assertTrue(layers[0].text.isNotBlank())
    }

    @Test
    fun 用户提示词排在最前且带最高优先级声明() {
        val text = only(
            SystemPromptSettings(custom = "永远用中文回答"),
            skills = "技能A",
            memory = "用户是后端工程师",
        )
        assertTrue(text.contains("最高优先级"))
        assertTrue(text.contains("永远用中文回答"))
        assertTrue(
            "用户规则必须比人设更靠前",
            text.indexOf("永远用中文回答") < text.indexOf("Praxis"),
        )
    }

    @Test
    fun 顺序为用户规则人设技能记忆摘要() {
        val text = only(
            SystemPromptSettings(custom = "规则X"),
            skills = "技能Y",
            memory = "记忆Z",
            summary = "摘要W",
        )
        val order = listOf("规则X", "Praxis", "技能Y", "记忆Z", "摘要W").map { text.indexOf(it) }
        assertTrue("各层必须按序出现，实际位置：" + order.joinToString(", "), order.isSorted())
        assertTrue("不能残留占位符", !text.contains("SECTION"))
    }

    @Test
    fun 执行约束只追加一次且在最后() {
        val text = only(SystemPromptSettings(custom = "规则"), memory = "记忆")
        assertTrue("最后带约束", text.contains("执行约束"))
        assertEquals("约束只写一遍", 1, text.split("执行约束").size - 1)
        assertTrue("约束要在最后", text.indexOf("执行约束") > text.indexOf("记忆"))
    }

    @Test
    fun 未设置用户提示词时不影响其余分层() {
        val text = only(SystemPromptSettings())
        assertTrue(text.contains("Praxis"))
        assertTrue("没有手动记忆时要注入工作流说明", text.contains("search_memory"))
        assertTrue("不能残留占位符", !text.contains("SECTION"))
    }

    @Test
    fun 空白技能与记忆不产生空层() {
        val text = only(SystemPromptSettings(custom = "规则"), skills = "   ", memory = "")
        assertFalse("空白技能不该出现技能层标题", text.contains("可用技能"))
        assertTrue("不能残留占位符", !text.contains("SECTION"))
    }

    @Test
    fun 有手动记忆时不注入工作流说明() {
        val text = only(SystemPromptSettings(), memory = "记忆Z")
        assertTrue(text.contains("记忆Z"))
        assertFalse(text.contains("search_memory"))
    }

    @Test
    fun 用户模板可整体替换骨架() {
        // 照搬 Operit 的 customSystemPromptTemplate：只留人设那一段。
        val text = SystemPromptBuilder.buildLayers(
            settings = SystemPromptSettings(
                custom = "这套骨架是用户自己写的",
                template = SystemPromptTemplates.SELF_INTRODUCTION,
            ),
            skills = "技能",
            memory = "记忆",
        ).single().text
        // 「规则」这个词在 ADHERENCE_INSTRUCTION 里就出现过，不能用它判；
        // 用用户原文这一整句才说明这一层确实没被注入。
        assertTrue("模板里没写的层不该出现", !text.contains("这套骨架是用户自己写的"))
        assertTrue("模板里写的层要出现", text.contains("Praxis"))
        assertTrue("不能残留占位符", !text.contains("SECTION"))
    }

    @Test
    fun flatten对单条是恒等的() {
        val layers = SystemPromptBuilder.buildLayers(settings = SystemPromptSettings(custom = "规则"))
        val flat = SystemPromptBuilder.flatten(layers)
        assertEquals(1, flat.size)
        assertEquals(layers[0].text, flat[0].text)
    }

    @Test
    fun 空模板回落到出厂模板() {
        val text = SystemPromptBuilder.buildLayers(settings = SystemPromptSettings()).single().text
        assertFalse(text.contains("SKILLS_SECTION"))
        assertFalse(text.contains("MEMORY_SECTION"))
    }
}
