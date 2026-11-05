package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示词分层与优先级。
 *
 * 用户要求「系统提示词权重最高，用户其次，让 ai 一定要照着来不能偏离」。
 * 这三条里「权重」是唯一的实现事实，必须被测试钉住 ——
 * 顺序被后来的改动打乱时，功能会静默失效（模型仍会回答，只是不再守规则）。
 */
class SystemPromptBuilderTest {

    @Test
    fun `用户提示词排在最前且带最高优先级声明`() {
        val layers = SystemPromptBuilder.buildLayers(
            settings = SystemPromptSettings(custom = "永远用中文回答"),
            skills = "技能A",
            memory = "用户是后端工程师",
        )
        val first = layers.first()
        assertEquals(ChatRole.System, first.role)
        assertTrue(first.text.contains("最高优先级"))
        assertTrue(first.text.contains("永远用中文回答"))
    }

    @Test
    fun `顺序为用户 内置 技能 记忆`() {
        val layers = SystemPromptBuilder.buildLayers(
            settings = SystemPromptSettings(custom = "规则X"),
            skills = "技能Y",
            memory = "记忆Z",
        )
        assertEquals(4, layers.size)
        assertTrue(layers[0].text.contains("规则X"))
        assertTrue(layers[1].text.contains("Praxis"))
        assertTrue(layers[2].text.contains("技能Y"))
        assertTrue(layers[3].text.contains("记忆Z"))
    }

    @Test
    fun `未设置用户提示词时不影响其余分层`() {
        val layers = SystemPromptBuilder.buildLayers(settings = SystemPromptSettings())
        // 内置人格 + 记忆工作流说明（2026-11-05 起，没有手动记忆时
        // 会注入一段「先搜记忆 -> 开工 -> 写回记忆」的说明，
        // 否则模型不知道自己有 search_memory / save_memory）。
        assertEquals(2, layers.size)
        assertTrue(layers[0].text.contains("Praxis"))
        assertTrue(layers[1].text.contains("search_memory"))
    }

    @Test
    fun `执行约束只追加在最后一条`() {
        val layers = SystemPromptBuilder.buildLayers(
            settings = SystemPromptSettings(custom = "规则"),
            memory = "记忆",
        )
        // 最后一条带约束。
        assertTrue(layers.last().text.contains("执行约束"))
        // 其余条不带（重复写会稀释分量）。
        layers.dropLast(1).forEach {
            assertFalse(it.text.contains("执行约束"))
        }
    }

    @Test
    fun `flatten 合并成单条并保留优先级标题`() {
        val layers = SystemPromptBuilder.buildLayers(
            settings = SystemPromptSettings(custom = "规则"),
            memory = "记忆",
        )
        val flat = SystemPromptBuilder.flatten(layers)
        assertEquals(1, flat.size)
        assertTrue(flat[0].text.contains("最高优先级"))
        assertTrue(flat[0].text.contains("记忆"))
    }

    @Test
    fun `空白技能与记忆不产生空 system`() {
        val layers = SystemPromptBuilder.buildLayers(
            settings = SystemPromptSettings(custom = "规则"),
            skills = "   ",
            memory = "",
        )
        // 用户规则 + 内置人格 + 记忆工作流说明。
        // 空白字符串仍会生成**非空**内容 —— 系统提示词里不会出现空 system 消息
        //（空 system 会被部分网关报 400）。
        assertEquals(3, layers.size)
        layers.forEach { assertTrue(it.text.isNotBlank()) }
    }

    /**
     * 有手动记忆时**不**注入工作流说明（两者互斥）。
     *
     * 用户把记忆贴进输入框时，他给的是材料；此时再叠一段「你自己去搜」
     * 会互相干扰 —— 而且记忆已在上下文里，再搜一遍是浪费。
     */
    @Test
    fun `有手动记忆时不注入工作流说明`() {
        val layers = SystemPromptBuilder.buildLayers(
            settings = SystemPromptSettings(),
            memory = "记忆Z",
        )
        assertTrue(layers.any { it.text.contains("记忆Z") })
        assertTrue(layers.none { it.text.contains("search_memory") })
    }
}