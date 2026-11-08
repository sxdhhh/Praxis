package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 状态文案（文档 9.0 的三态灯 + 冷却倒计时）。
 *
 * ## 为什么这些字符串值得单测
 *
 * 倒计时取整方向错一分钟、冷却不带原因，都属于「能用但会误导」的缺陷：
 * 用户按错误的时间点回去，发现还是不能用。
 */
class GatewayStatusTextTest {

    private val now = 1_000_000L
    private val minute = 60_000L

    private fun status(
        state: GatewayAccountState,
        reason: GatewayCooldownReason? = null,
        untilMillis: Long? = null,
        limitedModels: List<String> = emptyList(),
        sink: Boolean = false,
    ) = GatewayAccountStatus("a1", state, untilMillis, reason, limitedModels, sink)

    @Test
    fun 三态各有文案() {
        assertEquals("正常", GatewayStatusText.label(status(GatewayAccountState.Normal)))
        assertEquals("冷却", GatewayStatusText.label(status(GatewayAccountState.Cooling)))
        assertEquals("已停用", GatewayStatusText.label(status(GatewayAccountState.Disabled)))
    }

    @Test
    fun 冷却原因说明区分三种来源() {
        val backoff = GatewayStatusText.reasonText(
            status(GatewayAccountState.Cooling, GatewayCooldownReason.Backoff, now + minute),
        )
        val breaker = GatewayStatusText.reasonText(
            status(GatewayAccountState.Cooling, GatewayCooldownReason.Breaker, now + minute),
        )
        val quota = GatewayStatusText.reasonText(
            status(GatewayAccountState.Cooling, GatewayCooldownReason.QuotaLimit, now + minute),
        )

        assertEquals("上游连续出错，暂时退避", backoff)
        assertEquals("熔断中，多次失败后自动停用一段时间", breaker)
        assertEquals("额度用尽，等上游重置", quota)
    }

    @Test
    fun 非冷却态没有原因说明() {
        assertNull(GatewayStatusText.reasonText(status(GatewayAccountState.Normal)))
    }

    @Test
    fun 倒计时向上取整() {
        // 12 分 59 秒 -> 13 分钟：宁可说多不少。
        assertEquals(
            "还有 13 分钟",
            GatewayStatusText.countdownText(now + 12L * minute + 59_000L, now),
        )
    }

    @Test
    fun 不足一分钟单独文案() {
        assertEquals("不到 1 分钟", GatewayStatusText.countdownText(now + 30_000L, now))
    }

    @Test
    fun 超过一小时按小时显示() {
        assertEquals("还有 2 小时", GatewayStatusText.countdownText(now + 90L * minute, now))
    }

    @Test
    fun 已到期或没有冷却时不显示倒计时() {
        assertNull(GatewayStatusText.countdownText(null, now))
        assertNull(GatewayStatusText.countdownText(now, now))
        assertNull(GatewayStatusText.countdownText(now - 1L, now))
    }

    @Test
    fun 部分模型受限时给出提示() {
        val one = GatewayStatusText.limitedModelsText(
            status(GatewayAccountState.Normal, limitedModels = listOf("gpt-x")),
        )
        val many = GatewayStatusText.limitedModelsText(
            status(GatewayAccountState.Normal, limitedModels = listOf("gpt-x", "gpt-y")),
        )

        assertEquals("gpt-x 额度受限", one)
        assertEquals("gpt-x 等 2 个模型额度受限", many)
    }

    @Test
    fun 没有受限模型时无提示() {
        assertNull(GatewayStatusText.limitedModelsText(status(GatewayAccountState.Normal)))
    }

    @Test
    fun 垫底单独给提示() {
        assertEquals(
            "垫底（仍可用，排在最后）",
            GatewayStatusText.sinkText(status(GatewayAccountState.Normal, sink = true)),
        )
        assertNull(GatewayStatusText.sinkText(status(GatewayAccountState.Normal)))
    }
}
