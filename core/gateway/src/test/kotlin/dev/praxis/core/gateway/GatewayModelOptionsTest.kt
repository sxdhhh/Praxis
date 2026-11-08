package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型页候选的合并规则（文档 §9.1）。 */
class GatewayModelOptionsTest {

    @Test
    fun `结构化条目排在目录名之前`() {
        assertEquals(
            listOf("my-gpt4o", "gpt-4o", "claude-3"),
            GatewayModelOptions.merge(
                structured = listOf("my-gpt4o"),
                catalog = listOf("gpt-4o", "claude-3"),
            ),
        )
    }

    @Test
    fun `大小写不敏感去重且保留先出现的写法`() {
        assertEquals(
            listOf("GPT-4o"),
            GatewayModelOptions.merge(
                structured = listOf("GPT-4o"),
                catalog = listOf("gpt-4o", "Gpt-4O"),
            ),
        )
    }

    @Test
    fun `空白与空串被丢掉`() {
        assertEquals(
            listOf("a"),
            GatewayModelOptions.merge(structured = listOf(" "), catalog = listOf("a", "")),
        )
    }

    @Test
    fun `两个来源都空时返回空列表而不是塞一个占位`() {
        assertTrue(GatewayModelOptions.merge(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `没有结构化条目时目录名原样保留`() {
        val catalog = listOf("a", "b")
        assertEquals(catalog, GatewayModelOptions.merge(emptyList(), catalog))
    }
}
