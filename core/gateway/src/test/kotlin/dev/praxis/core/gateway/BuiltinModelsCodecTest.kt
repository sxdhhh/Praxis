package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内置家的模型清单（存储与合并规则）。 */
class BuiltinModelsCodecTest {

    @Test
    fun `往返且按名排序去重`() {
        val models = mapOf("openai" to listOf("gpt-4o", "gpt-4o-mini", "gpt-4o"))
        val decoded = BuiltinModelsCodec.decode(BuiltinModelsCodec.encode(models))?.getOrNull()
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), decoded?.get("openai"))
    }

    @Test
    fun `没配过返回 null 坏内容返回失败`() {
        assertNull(BuiltinModelsCodec.decode(null))
        assertNull(BuiltinModelsCodec.decode("  "))
        assertTrue(BuiltinModelsCodec.decode("not json")?.isFailure == true)
        assertTrue(BuiltinModelsCodec.decode("""{\"openai\":1}""")?.isFailure == true)
    }

    @Test
    fun `合并只写这一家 不动别家`() {
        val current = mapOf("deepseek" to listOf("deepseek-chat"))
        val merged = BuiltinModelsCodec.merge(current, "openai", listOf("gpt-4o"))
        assertEquals(listOf("deepseek-chat"), merged["deepseek"])
        assertEquals(listOf("gpt-4o"), merged["openai"])
    }

    @Test
    fun `空清单当没拉到（保留旧的）`() {
        // 上游临时返回空数组时直接覆盖，会把可用模型抹掉 —— 比不刷新更糟。
        val current = mapOf("openai" to listOf("gpt-4o"))
        assertEquals(current, BuiltinModelsCodec.merge(current, "openai", emptyList()))
        assertEquals(current, BuiltinModelsCodec.merge(current, "openai", listOf("  ")))
    }

    @Test
    fun `合并会去空白与去重并排序`() {
        val merged = BuiltinModelsCodec.merge(emptyMap(), "x", listOf(" b ", "a", "b", ""))
        assertEquals(listOf("a", "b"), merged["x"])
    }
}
