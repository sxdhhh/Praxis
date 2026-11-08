package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * modelRules 的解析与回写（文档 8.3）。
 *
 * ## 最要紧的一条是 seeded
 *
 * 它是网关的内部去重标记：导入时忽略没问题，但**导出必须原样带回去**，
 * 否则把 Praxis 导出的配置再灌回网关，会重复播种同一批模型。这种错误
 * 不会报错，只会让网关那边的模型列表悄悄变多。
 */
class GatewayModelRulesTest {

    private val sample = """
        {
          "disabled":     [ { "id": "gpt-x", "provider": "openai", "enabled": false, "reasoning": null } ],
          "mappings":     [ { "alias": "fast", "target": "gpt-x", "provider": "openai", "reasoning": null, "enabled": true } ],
          "custom":       [ { "provider": "openai", "id": "gpt-y" } ],
          "capabilities": [ { "provider": "openai", "id": "gpt-x", "values": { "supportsImages": true } } ],
          "seeded":       [ "openai/gpt-x" ]
        }
    """.trimIndent()

    @Test
    fun 解析文档样例() {
        val rules = GatewayModelRulesCodec.parse(sample).getOrThrow()

        assertEquals(1, rules.disabled.size)
        assertEquals(1, rules.mappings.size)
        assertEquals(1, rules.custom.size)
        assertEquals(1, rules.capabilities.size)
        assertEquals(listOf("openai/gpt-x"), rules.seeded)
    }

    @Test
    fun 映射转成候选链能吃的形状() {
        val rules = GatewayModelRulesCodec.parse(sample).getOrThrow()

        assertEquals(
            listOf(ModelMapping(alias = "fast", target = "gpt-x", provider = "openai", reasoning = null, enabled = true)),
            rules.toRoutingMappings(),
        )
    }

    @Test
    fun 未启用的映射也照样转出交给候选链判断() {
        // 这一层不替候选链做过滤：两处各判一次，迟早不一致。
        val text = """
            { "mappings": [ { "alias": "fast", "target": "m", "provider": "openai", "enabled": false } ] }
        """.trimIndent()

        val rules = GatewayModelRulesCodec.parse(text).getOrThrow()

        assertEquals(listOf(false), rules.toRoutingMappings().map { it.enabled })
    }

    @Test
    fun 导出必须保留_seeded() {
        val rules = GatewayModelRulesCodec.parse(sample).getOrThrow()

        val encoded = GatewayModelRulesCodec.encode(rules)

        assertTrue("seeded 丢了，回灌会重复播种", encoded.contains("openai/gpt-x"))
    }

    @Test
    fun 导出要保留显式写的默认值() {
        // disabled 里的 enabled=false 正好等于默认值。不写出来的话，
        // 这条规则会从「显式禁用」退化成「没有这条」。
        val rules = GatewayModelRulesCodec.parse(sample).getOrThrow()

        val encoded = GatewayModelRulesCodec.encode(rules)

        assertTrue("disabled 这条规则要写出来", encoded.contains("disabled"))
        assertEquals("显式 false 要保留", rules.disabled, GatewayModelRulesCodec.parse(encoded).getOrThrow().disabled)
    }

    @Test
    fun 往返后内容相等() {
        val rules = GatewayModelRulesCodec.parse(sample).getOrThrow()

        val roundTripped = GatewayModelRulesCodec.parse(GatewayModelRulesCodec.encode(rules)).getOrThrow()

        assertEquals(rules, roundTripped)
    }

    @Test
    fun 缺字段得到空列表而不是失败() {
        val rules = GatewayModelRulesCodec.parse("{}").getOrThrow()

        assertTrue(rules.disabled.isEmpty())
        assertTrue(rules.mappings.isEmpty())
        assertTrue(rules.seeded.isEmpty())
    }

    @Test
    fun 能力位读出图片支持() {
        val rules = GatewayModelRulesCodec.parse(sample).getOrThrow()

        assertEquals(true, rules.capabilities.single().supportsImages())
    }

    @Test
    fun 能力位缺键返回_null_表示未知() {
        val text = """
            { "capabilities": [ { "provider": "openai", "id": "gpt-x", "values": { } } ] }
        """.trimIndent()

        val rules = GatewayModelRulesCodec.parse(text).getOrThrow()

        // null = 未知，调用方按文档 7.2「缺键放行」处理；返回 false 会让
        // 带图请求把所有老账号都跳过。
        assertNull(rules.capabilities.single().supportsImages())
    }

    @Test
    fun 能判断某模型是否被禁用() {
        val rules = GatewayModelRulesCodec.parse(sample).getOrThrow()

        assertTrue(rules.isDisabled(provider = "openai", id = "gpt-x"))
        assertTrue(!rules.isDisabled(provider = "openai", id = "gpt-y"))
    }
}
