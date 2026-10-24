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
        // 只有内置人格一条。
        assertEquals(1, layers.size)
        assertTrue(layers[0].text.contains("Praxis"))
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
        assertEquals(2, layers.size)
        layers.forEach { assertTrue(it.text.isNotBlank()) }
    }
}