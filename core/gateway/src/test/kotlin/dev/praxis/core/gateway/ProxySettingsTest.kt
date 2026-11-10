package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 代理池与账号级绑定（文档 §5.9）。 */
class ProxySettingsTest {

    private val nodeA = ProxyNode(id = "p1", label = "香港", host = "1.2.3.4", port = 8080)
    private val nodeB = ProxyNode(
        id = "p2",
        label = "家宽",
        kind = ProxyNodeKind.Socks,
        host = "5.6.7.8",
        port = 1080,
        username = "u",
        password = "p",
    )

    @Test
    fun `没开代理时一律直连`() {
        val settings = ProxySettings(enabled = false, nodes = listOf(nodeA), binding = mapOf("a1" to "p1"))
        assertNull(ProxyRules.egressFor("a1", settings))
    }

    @Test
    fun `没绑定的账号直连`() {
        val settings = ProxySettings(enabled = true, nodes = listOf(nodeA), binding = emptyMap())
        assertNull(ProxyRules.egressFor("a1", settings))
    }

    @Test
    fun `绑定生效`() {
        val settings = ProxySettings(enabled = true, nodes = listOf(nodeA, nodeB), binding = mapOf("a1" to "p2"))
        assertEquals("p2", ProxyRules.egressFor("a1", settings)?.id)
    }

    @Test
    fun `绑定的节点被删掉时直连 而不是报错`() {
        // 删一个出口不该让绑着它的账号全废掉；界面会显示成「未绑定」。
        val settings = ProxySettings(enabled = true, nodes = emptyList(), binding = mapOf("a1" to "p1"))
        assertNull(ProxyRules.egressFor("a1", settings))
    }

    @Test
    fun `同一个出口函数给三处用 结果一致`() {
        // 文档 §5.9 的要点：余额查询 / 拉清单 / 转发必须同一出口。
        val settings = ProxySettings(enabled = true, nodes = listOf(nodeA), binding = mapOf("a1" to "p1"))
        val first = ProxyRules.egressFor("a1", settings)
        val second = ProxyRules.egressFor("a1", settings)
        val third = ProxyRules.egressFor("a1", settings)
        assertEquals(first?.id, second?.id)
        assertEquals(second?.host, third?.host)
    }

    @Test
    fun `校验 空名 空主机 坏端口 重名`() {
        assertEquals(ProxyNodeError.MissingLabel, ProxyRules.validateNode(nodeA.copy(label = " ")))
        assertEquals(ProxyNodeError.MissingHost, ProxyRules.validateNode(nodeA.copy(host = "")))
        assertEquals(ProxyNodeError.BadPort, ProxyRules.validateNode(nodeA.copy(port = 0)))
        assertEquals(ProxyNodeError.BadPort, ProxyRules.validateNode(nodeA.copy(port = 70000)))
        assertEquals(ProxyNodeError.DuplicateLabel, ProxyRules.validateNode(nodeB.copy(label = "香港"), listOf(nodeA)))
        assertNull(ProxyRules.validateNode(nodeB, listOf(nodeA)))
    }

    @Test
    fun `同名判据忽略大小写 但改自己不算重名`() {
        assertEquals(
            ProxyNodeError.DuplicateLabel,
            ProxyRules.validateNode(nodeB.copy(label = "HK"), listOf(nodeA.copy(label = "hk"))),
        )
        assertNull(ProxyRules.validateNode(nodeA.copy(label = "香港"), listOf(nodeA)))
    }

    @Test
    fun `归一化去空白 空用户名当没有`() {
        val normalized = ProxyRules.normalizeNode(
            nodeB.copy(label = "  家宽  ", host = " 5.6.7.8 ", username = "  ", password = ""),
        )
        assertEquals("家宽", normalized.label)
        assertEquals("5.6.7.8", normalized.host)
        assertNull(normalized.username)
        assertNull(normalized.password)
    }

    @Test
    fun `往返`() {
        val settings = ProxySettings(
            enabled = true,
            nodes = listOf(nodeA, nodeB),
            binding = mapOf("a1" to "p1", "a2" to "p2"),
        )
        assertEquals(settings, ProxySettingsCodec.decode(ProxySettingsCodec.encode(settings))?.getOrNull())
    }

    @Test
    fun `没配过返回 null 坏内容返回失败`() {
        assertNull(ProxySettingsCodec.decode(null))
        assertNull(ProxySettingsCodec.decode("  "))
        assertTrue(ProxySettingsCodec.decode("not json")?.isFailure == true)
        assertTrue(ProxySettingsCodec.decode("""{"nodes":[{"id":"p"}]}""")?.isFailure == true)
    }

    @Test
    fun `解码时清掉悬空绑定`() {
        val text = """{"enabled":true,"nodes":[],"binding":{"a1":"gone"}}"""
        val decoded = ProxySettingsCodec.decode(text)?.getOrNull()
        assertTrue(decoded!!.binding.isEmpty())
    }

    @Test
    fun `pruneBinding 不动有效绑定`() {
        val settings = ProxySettings(
            enabled = true,
            nodes = listOf(nodeA),
            binding = mapOf("a1" to "p1", "a2" to "gone"),
        )
        assertEquals(mapOf("a1" to "p1"), ProxyRules.pruneBinding(settings).binding)
    }
}
