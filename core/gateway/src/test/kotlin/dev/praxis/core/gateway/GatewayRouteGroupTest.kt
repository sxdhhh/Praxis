package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 路由组（文档 §5.5）。 */
class GatewayRouteGroupTest {

    private fun group(name: String = "fast", members: List<RouteGroupMember> = listOf(
        RouteGroupMember("openai", "gpt-4o-mini"),
        RouteGroupMember("deepseek", "deepseek-chat"),
    )) = GatewayRouteGroup(name = name, members = members)

    @Test
    fun `组名命中时给出成员的候选，忽略大小写`() {
        val candidates = GatewayRouteGroupCodec.candidatesFor("FAST", listOf(group()))
        assertEquals(2, candidates!!.size)
        assertEquals("openai", candidates[0].provider)
        assertEquals("gpt-4o-mini", candidates[0].wireModel)
        assertEquals("deepseek", candidates[1].provider)
    }

    @Test
    fun `不是组就返回 null`() {
        // 让调用方继续走映射 / 原生承载，而不是拿到空候选当成「没有可用账号」。
        assertNull(GatewayRouteGroupCodec.candidatesFor("gpt-4o", listOf(group())))
        assertNull(GatewayRouteGroupCodec.candidatesFor("", listOf(group())))
    }

    @Test
    fun `编解码往返`() {
        val groups = listOf(group(), group("slow", listOf(RouteGroupMember("anthropic", "claude-x"))))
        assertEquals(groups, GatewayRouteGroupCodec.decode(GatewayRouteGroupCodec.encode(groups))?.getOrNull())
    }

    @Test
    fun `没配过返回 null、坏内容返回失败`() {
        assertNull(GatewayRouteGroupCodec.decode(null))
        assertNull(GatewayRouteGroupCodec.decode("  "))
        assertTrue(GatewayRouteGroupCodec.decode("not json")?.isFailure == true)
    }

    @Test
    fun `校验 空名 空格名 无成员`() {
        assertEquals(GatewayRouteGroupError.MissingName, GatewayRouteGroupRules.validate(group(name = "  ")))
        assertEquals(
            GatewayRouteGroupError.NameWithWhitespace,
            GatewayRouteGroupRules.validate(group(name = "my fast")),
        )
        assertEquals(
            GatewayRouteGroupError.NoMembers,
            GatewayRouteGroupRules.validate(group(members = emptyList())),
        )
    }

    @Test
    fun `校验 成员缺家或缺模型`() {
        assertEquals(
            GatewayRouteGroupError.MemberMissingProvider,
            GatewayRouteGroupRules.validate(group(members = listOf(RouteGroupMember("", "gpt-x")))),
        )
        assertEquals(
            GatewayRouteGroupError.MemberMissingModel,
            GatewayRouteGroupRules.validate(group(members = listOf(RouteGroupMember("openai", "")))),
        )
    }

    @Test
    fun `校验 重名要拦（忽略大小写）`() {
        // 匹配本身忽略大小写，允许 Fast 与 fast 并存会让「哪个生效」变成看顺序。
        assertEquals(
            GatewayRouteGroupError.DuplicateName,
            GatewayRouteGroupRules.validate(group(name = "Fast"), listOf(group(name = "fast"))),
        )
        assertNull(GatewayRouteGroupRules.validate(group(name = "other"), listOf(group())))
    }

    @Test
    fun `归一化去掉成员里的空项`() {
        val normalized = GatewayRouteGroupRules.normalize(
            group(name = "  fast ", members = listOf(
                RouteGroupMember(" openai ", " gpt-x "),
                RouteGroupMember("", "gpt-y"),
            )),
        )
        assertEquals("fast", normalized.name)
        assertEquals(listOf(RouteGroupMember("openai", "gpt-x")), normalized.members)
    }

    @Test
    fun `每种错误都有人话`() {
        GatewayRouteGroupError.entries.forEach { error ->
            assertTrue(error.userMessage().isNotBlank())
        }
    }

    @Test
    fun `候选链里组优先于映射`() {
        // 两者都是「别名 → 上游」的形状，同名时必须有个先后。
        val result = CandidateChain.of(
            requestedModel = "fast",
            nativeModels = listOf(NativeModel(provider = "native", id = "fast")),
            mappings = listOf(
                ModelMapping(alias = "fast", target = "mapped-model", provider = "mapped"),
            ),
            defaultProvider = null,
            groups = listOf(group()),
        )
        assertEquals(2, result.candidates.size)
        assertEquals("openai", result.candidates[0].provider)
    }

    @Test
    fun `没有组时行为不变`() {
        val result = CandidateChain.of(
            requestedModel = "fast",
            nativeModels = listOf(NativeModel(provider = "native", id = "fast")),
            mappings = emptyList(),
            defaultProvider = null,
        )
        assertEquals(listOf("native"), result.candidates.map { it.provider })
    }
}
