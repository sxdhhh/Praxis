package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账号状态推导（文档 9.0 的三态灯 + 冷却倒计时）。
 *
 * ## 为什么「部分模型被限」不能算账号冷却
 *
 * 限额的键是 (账号, 上游模型)，不是整个账号。一个号只是 gpt-x 被限、gpt-y
 * 还能用时，把整条账号标成「冷却中」会让用户以为这个号废了，跑去删号或换 key
 * —— 而实际上它在正常工作。
 */
class GatewayAccountStatusTest {

    private var now = 1_000_000L
    private val minute = 60_000L

    private fun state(breakerEnabled: Boolean = false, breakerOpenMillis: Long = 5L * minute) =
        RouteState(
            clock = { now },
            breaker = BreakerConfig(
                enabled = breakerEnabled,
                failureThreshold = 1,
                openMillis = breakerOpenMillis,
                successThreshold = 1,
            ),
        )

    private fun resolve(
        enabled: Boolean = true,
        wireModels: List<String> = listOf("gpt-x"),
        state: RouteState = state(),
        accountId: String = "a1",
    ) = GatewayAccountStatusResolver.resolve(accountId, enabled, wireModels, state)

    @Test
    fun 正常账号() {
        val status = resolve()

        assertEquals(GatewayAccountState.Normal, status.state)
        assertNull(status.untilMillis)
        assertNull(status.reason)
        assertTrue(status.limitedModels.isEmpty())
    }

    @Test
    fun 手动停用的账号是禁用态() {
        assertEquals(GatewayAccountState.Disabled, resolve(enabled = false).state)
    }

    @Test
    fun 停用优先于冷却() {
        val s = state()
        s.onFailure("a1", FailureKind.UpstreamError)

        // 用户关掉的账号，他最想知道的是「我关了它」，不是「它正好在冷却」。
        assertEquals(GatewayAccountState.Disabled, resolve(enabled = false, state = s).state)
    }

    @Test
    fun 退避中显示冷却与到期时刻() {
        val s = state()
        s.onFailure("a1", FailureKind.UpstreamError)

        val status = resolve(state = s)

        assertEquals(GatewayAccountState.Cooling, status.state)
        assertEquals(GatewayCooldownReason.Backoff, status.reason)
        assertEquals(now + 15L * minute, status.untilMillis)
    }

    @Test
    fun 退避先到期时改报熔断() {
        // 熔断窗口比退避长：先过掉退避，剩下的就是熔断。
        val longBreaker = state(breakerEnabled = true, breakerOpenMillis = 30L * minute)
        longBreaker.onFailure("a1", FailureKind.UpstreamError)

        now += 15L * minute

        val status = resolve(state = longBreaker)

        assertEquals(GatewayAccountState.Cooling, status.state)
        assertEquals(GatewayCooldownReason.Breaker, status.reason)
        assertEquals(now + 15L * minute, status.untilMillis)
    }

    @Test
    fun 全部模型被限额时才算账号冷却() {
        val s = state()
        s.markLimit(CooldownKey("a1", "gpt-x"), now + 5L * minute)
        s.markLimit(CooldownKey("a1", "gpt-y"), now + 3L * minute)

        val status = resolve(wireModels = listOf("gpt-x", "gpt-y"), state = s)

        assertEquals(GatewayAccountState.Cooling, status.state)
        assertEquals(GatewayCooldownReason.QuotaLimit, status.reason)
        assertEquals("恢复时刻取最早到期的那个", now + 3L * minute, status.untilMillis)
    }

    @Test
    fun 部分模型被限额时账号仍算正常() {
        val s = state()
        s.markLimit(CooldownKey("a1", "gpt-x"), null)

        val status = resolve(wireModels = listOf("gpt-x", "gpt-y"), state = s)

        assertEquals(GatewayAccountState.Normal, status.state)
        assertEquals(listOf("gpt-x"), status.limitedModels)
    }

    @Test
    fun 不知道承载哪些模型时不判定限额() {
        val s = state()
        s.markLimit(CooldownKey("a1", "gpt-x"), null)

        // 明确「不判定」而不是「判定为没限」：状态按正常处理，受限列表留空。
        val status = resolve(wireModels = emptyList(), state = s)

        assertEquals(GatewayAccountState.Normal, status.state)
        assertTrue(status.limitedModels.isEmpty())
    }

    @Test
    fun 垫底是独立标记不改变状态() {
        val s = state()
        s.markSink("a1")

        val status = resolve(state = s)

        assertEquals(GatewayAccountState.Normal, status.state)
        assertTrue("垫底仍可用，只是排队尾", status.sink)
    }

    @Test
    fun 冷却到期后回到正常() {
        val s = state()
        s.onFailure("a1", FailureKind.UpstreamError)

        now += 15L * minute

        assertEquals(GatewayAccountState.Normal, resolve(state = s).state)
    }
}
