package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 总开关的四种组合（文档 §11 末尾的回滚要求）。
 *
 * 关键是第三行：网关档案 + 开关关闭**不能**回落到单配置路径 ——
 * 那不是「今天的行为」，而是「拿空地址去发请求」。
 */
class GatewayGateTest {

    @Test
    fun `单配置档案不受开关影响`() {
        assertEquals(OutboundRoute.SingleProfile, GatewayGate.decide(profileIsGateway = false, gatewayEnabled = true))
        assertEquals(OutboundRoute.SingleProfile, GatewayGate.decide(profileIsGateway = false, gatewayEnabled = false))
    }

    @Test
    fun `网关档案且开关打开时走网关`() {
        assertEquals(OutboundRoute.Gateway, GatewayGate.decide(profileIsGateway = true, gatewayEnabled = true))
    }

    @Test
    fun `网关档案但开关关闭时明确停用而不是回落`() {
        assertEquals(OutboundRoute.GatewayDisabled, GatewayGate.decide(profileIsGateway = true, gatewayEnabled = false))
    }

    @Test
    fun `四种组合都只落到三个产出的其中一个`() {
        val combos = listOf(true to true, true to false, false to true, false to false)
        val routes = combos.map { (profile, enabled) -> GatewayGate.decide(profile, enabled) }
        assertEquals(4, routes.size)
        assertEquals(2, routes.count { it == OutboundRoute.SingleProfile })
        assertEquals(1, routes.count { it == OutboundRoute.Gateway })
        assertEquals(1, routes.count { it == OutboundRoute.GatewayDisabled })
    }
}
