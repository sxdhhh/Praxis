package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面行模型的组装（cp：账号状态推导）。
 *
 * 这一层很薄，但值得钉两条：状态确实接了运行期状态（不是永远正常），
 * 以及停用的账号不会被算成冷却。
 */
class GatewayAccountRowTest {

    private var now = 1_000_000L
    private val minute = 60_000L

    private fun state() = RouteState(
        clock = { now },
        breaker = BreakerConfig(false, 3, 5L * minute, 1),
    )

    @Test
    fun 组装时带上账号字段与状态() {
        val row = GatewayAccountRow.from(
            id = "a1",
            name = "工作号",
            providerLabel = "OpenAI",
            enabled = true,
            wireModels = listOf("gpt-x"),
            state = state(),
        )

        assertEquals("a1", row.id)
        assertEquals("工作号", row.name)
        assertEquals("OpenAI", row.providerLabel)
        assertEquals(GatewayAccountState.Normal, row.status.state)
    }

    @Test
    fun 状态确实来自运行期而不是写死正常() {
        val s = state()
        s.onFailure("a1", FailureKind.UpstreamError)

        val row = GatewayAccountRow.from("a1", "工作号", "OpenAI", true, listOf("gpt-x"), s)

        assertEquals(GatewayAccountState.Cooling, row.status.state)
        assertEquals(GatewayCooldownReason.Backoff, row.status.reason)
    }

    @Test
    fun 停用优先于冷却() {
        val s = state()
        s.onFailure("a1", FailureKind.UpstreamError)

        val row = GatewayAccountRow.from("a1", "工作号", "OpenAI", false, listOf("gpt-x"), s)

        assertEquals(GatewayAccountState.Disabled, row.status.state)
    }

    @Test
    fun 不知道模型清单时状态仍是正常() {
        val s = state()
        s.markLimit(CooldownKey("a1", "gpt-x"), null)

        val row = GatewayAccountRow.from("a1", "工作号", "OpenAI", true, emptyList(), s)

        assertEquals(GatewayAccountState.Normal, row.status.state)
        assertTrue(row.status.limitedModels.isEmpty())
    }
}
