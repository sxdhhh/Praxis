package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 自定义家 modelsJson 的两种历史形状（G3 收尾）。 */
class ProviderModelsShapesTest {

    @Test
    fun `旧行清单解析为能力位全 null 的条目`() {
        val models = BuiltinModelsCodec.parseCapsList("deepseek-chat\ndeepseek-reasoner\n")
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), models.map { it.id })
        assertNull(models[0].supportsImages)
    }

    @Test
    fun `JSON 数组形状带能力位`() {
        val text = BuiltinModelsCodec.encodeCapsList(
            listOf(
                BuiltinModelsCodec.BuiltinModelCaps(
                    id = "gemini-2.5-pro", supportsImages = true, maxInputTokens = 1_000_000,
                ),
                BuiltinModelsCodec.BuiltinModelCaps(id = "plain"),
            ),
        )
        val models = BuiltinModelsCodec.parseCapsList(text)
        assertEquals("gemini-2.5-pro", models[0].id)
        assertEquals(true, models[0].supportsImages)
        assertEquals(1_000_000, models[0].maxInputTokens)
        assertNull(models[1].supportsImages)
    }

    @Test
    fun `空串与空白返回空表`() {
        assertEquals(0, BuiltinModelsCodec.parseCapsList("").size)
        assertEquals(0, BuiltinModelsCodec.parseCapsList("   ").size)
    }
}