package dev.praxis.core.data.gateway

import dev.praxis.core.gateway.ModelEntry
import dev.praxis.core.gateway.ModelKind
import dev.praxis.core.gateway.ModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结构化模型条目的存储映射（文档 6.3）。
 *
 * ## 最要紧的两条
 *
 * 1. 往返必须完全相等 —— 三态能力位（null / true / false）最容易在存储层被压成
 *    两种：把 null 写成 false 之后，文档 7.2 的「缺键放行」就失效了。
 * 2. 读到不认识的枚举名要抛错，不能退回默认值。
 */
class GatewayModelMapperTest {

    private val full = ModelEntry(
        provider = "openai",
        id = "gpt-x",
        name = "GPT-X",
        kind = ModelKind.Image,
        source = ModelSource.ModelsDev,
        enabled = false,
        credits = 1.5,
        supportsToolCall = true,
        supportsImages = false,
        supportsVideo = null,
        supportsReasoning = true,
        supportsImageGeneration = null,
        supportsImageEditing = false,
        inputModalities = listOf("text", "image"),
        maxInputTokens = 128000,
        maxOutputTokens = 4096,
        reasoningEfforts = listOf("low", "high"),
        reasoningDefault = "low",
        reasoningFixed = null,
        reasoningSource = "models.dev",
    )

    @Test
    fun 往返后条目完全相等() {
        assertEquals(full, full.toEntity().toModelEntry())
    }

    @Test
    fun 三态能力位不会在存储层被压成两态() {
        val roundTripped = full.toEntity().toModelEntry()

        assertNull("null 必须还是 null（未声明 = 放行）", roundTripped.supportsVideo)
        assertNull(roundTripped.supportsImageGeneration)
        assertEquals(java.lang.Boolean.TRUE, roundTripped.supportsToolCall)
        assertEquals(java.lang.Boolean.FALSE, roundTripped.supportsImages)
    }

    @Test
    fun 空列表往返仍是空列表() {
        val minimal = ModelEntry(provider = "openai", id = "gpt-y", source = ModelSource.Native)

        val roundTripped = minimal.toEntity().toModelEntry()

        assertTrue(roundTripped.inputModalities.isEmpty())
        assertTrue(roundTripped.reasoningEfforts.isEmpty())
    }

    @Test
    fun 列表用换行编码() {
        val entity = full.toEntity()

        assertEquals("text\nimage", entity.inputModalities)
        assertEquals("low\nhigh", entity.reasoningEfforts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 不认识的模型类型直接抛错() {
        // 退回默认值会让用户看到一条「类型不对但能跑」的模型，把问题藏起来。
        full.toEntity().copy(kind = "hologram").toModelEntry()
    }

    @Test(expected = IllegalArgumentException::class)
    fun 不认识的来源直接抛错() {
        full.toEntity().copy(source = "telepathy").toModelEntry()
    }
}
