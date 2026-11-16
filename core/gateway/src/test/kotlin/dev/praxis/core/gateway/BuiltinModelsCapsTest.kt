package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置家清单的**详细形态**（名字 + 能力位）。
 *
 * 关键点只有两个：旧形状（纯字符串数组）必须还能读；
 * 能力位的「没声明」必须保真为 null 而不是 false。
 */
class BuiltinModelsCapsTest {

    @Test
    fun `旧形状纯字符串数组仍能解码且能力位为 null`() {
        val legacy = """{"openai": ["gpt-4o", "gpt-4o-mini"]}"""
        val decoded = BuiltinModelsCodec.decodeDetailed(legacy)!!.getOrThrow()
        val models = decoded["openai"]!!
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), models.map { it.id })
        assertNull(models[0].supportsImages)
        assertNull(models[0].maxInputTokens)
    }

    @Test
    fun `详细形态往返不丢能力位`() {
        val caps = listOf(
            BuiltinModelsCodec.BuiltinModelCaps(
                id = "raccoon-8c4485",
                supportsImages = true,
                supportsReasoning = true,
                supportsToolCall = false,
                maxInputTokens = 1_000_000,
                maxOutputTokens = 100_000,
            ),
            BuiltinModelsCodec.BuiltinModelCaps(id = "plain-model"),
        )
        val text = BuiltinModelsCodec.encodeDetailed(mapOf("raccoon" to caps))
        assertTrue(text.contains("supports_images"))
        assertTrue(text.contains("max_input_tokens"))
        // encode 按 id 去重排序（与旧 merge 同语义），所以按 id 取，不按下标。
        val back = BuiltinModelsCodec.decodeDetailed(text)!!.getOrThrow()["raccoon"]!!
        val rich = back.single { it.id == "raccoon-8c4485" }
        val plain = back.single { it.id == "plain-model" }
        assertEquals(true, rich.supportsImages)
        assertEquals(false, rich.supportsToolCall)
        assertEquals(1_000_000, rich.maxInputTokens)
        // 没声明能力位的条目保持全 null。
        assertNull(plain.supportsImages)
        assertNull(plain.maxInputTokens)
    }

    @Test
    fun `合并空清单保留旧的`() {
        val current = mapOf("openai" to listOf(BuiltinModelsCodec.BuiltinModelCaps(id = "gpt-4o")))
        val merged = BuiltinModelsCodec.mergeDetailed(current, "raccoon", emptyList())
        assertEquals(current, merged)
        val merged2 = BuiltinModelsCodec.mergeDetailed(
            current, "raccoon", listOf(BuiltinModelsCodec.BuiltinModelCaps(id = "raccoon-8c4485")))
        assertEquals(2, merged2.size)
    }

    @Test
    fun `坏内容返回失败而不是崩`() {
        val result = BuiltinModelsCodec.decodeDetailed("不是 JSON")!!
        assertTrue(result.isFailure)
    }
}