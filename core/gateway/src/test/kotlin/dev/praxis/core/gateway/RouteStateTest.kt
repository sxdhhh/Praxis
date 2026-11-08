package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由运行期状态（文档 6.5 / 7.4）。
 *
 * ## 为什么这些用例全靠「拨钟」
 *
 * 退避翻倍、TTL 到期、熔断恢复全是时间规则。若用真实时钟，验一条就要 sleep
 * 十几分钟 —— 那种测试没人会跑，跑了也不稳。注入时钟之后每条规则都能在一
 * 毫秒内验完，而且验的是**边界**（刚好到期、刚好翻倍）而不是大概。
 */
class RouteStateTest {

    private var now = 1_000_000L

    private fun state(breaker: BreakerConfig = BreakerConfig(false, 3, 60_000L, 1)) =
        RouteState(clock = { now }, breaker = breaker)

    private val minute = 60_000L
    private val hour = 60L * minute

    @Test
    fun 退避首次是十五分钟() {
        val s = state()

        val until = s.markBackoff("acct-1")

        assertEquals(now + 15L * minute, until)
    }

    @Test
    fun 退避逐次翻倍并封顶一小时() {
        val s = state()

        assertEquals(15L * minute, s.markBackoff("a") - now)
        // 第二次：15m * 2
        now += 1
        assertEquals(30L * minute, s.markBackoff("a") - now)
        now += 1
        assertEquals(60L * minute, s.markBackoff("a") - now)
        now += 1
        // 第四次就该封顶（15m * 8 = 2h，超过 1h）
        assertEquals(hour, s.markBackoff("a") - now)
    }

    @Test
    fun 退避到期后读回_null() {
        val s = state()
        s.markBackoff("a")

        now += 15L * minute
        assertNull("刚好到期就该算过期", s.backoffUntil("a"))
    }

    @Test
    fun 垫底三十分钟到期() {
        val s = state()

        assertEquals(now + RouteState.SINK_TTL_MS, s.markSink("a"))
        assertTrue(s.sinkUntil("a") != null)

        now += RouteState.SINK_TTL_MS
        assertNull(s.sinkUntil("a"))
    }

    @Test
    fun 限额用上游给的重置时刻() {
        val s = state()
        val key = CooldownKey("acct-1", "gpt-x")

        s.markLimit(key, now + 5L * minute)

        assertEquals(now + 5L * minute, s.limitUntil(key))
    }

    @Test
    fun 限额认不出重置时刻用十分钟() {
        val s = state()
        val key = CooldownKey("acct-1", "gpt-x")

        s.markLimit(key, null)

        assertEquals(FailurePolicy.DEFAULT_QUOTA_COOLDOWN_MS, s.limitUntil(key)!! - now)
    }

    @Test
    fun 限额按上游模型隔离() {
        val s = state()
        val limited = CooldownKey("acct-1", "gpt-x")
        val other = CooldownKey("acct-1", "gpt-y")

        s.markLimit(limited, null)

        // 这正是网关 issue 200 的反面：只该封住出问题的那一个模型。
        assertTrue(s.limitUntil(limited) != null)
        assertNull(s.limitUntil(other))
    }

    @Test
    fun 熔断达到阈值才打开() {
        val s = state(BreakerConfig(enabled = true, failureThreshold = 3, openMillis = minute, successThreshold = 1))

        s.onFailure("a", FailureKind.UpstreamError)
        assertFalse("一次失败不该开熔断", s.breakerOpen("a"))
        s.onFailure("a", FailureKind.UpstreamError)
        assertFalse(s.breakerOpen("a"))
        s.onFailure("a", FailureKind.UpstreamError)
        assertTrue("第三次失败应打开", s.breakerOpen("a"))
    }

    @Test
    fun 熔断到期自动关闭() {
        val s = state(BreakerConfig(enabled = true, failureThreshold = 1, openMillis = minute, successThreshold = 1))
        s.onFailure("a", FailureKind.UpstreamError)

        assertTrue(s.breakerOpen("a"))
        now += minute
        assertFalse("到期即关闭", s.breakerOpen("a"))
    }

    @Test
    fun 未启用熔断时永远不开() {
        val s = state(BreakerConfig(enabled = false, failureThreshold = 1, openMillis = hour, successThreshold = 1))

        repeat(5) { s.onFailure("a", FailureKind.UpstreamError) }

        assertFalse(s.breakerOpen("a"))
    }

    @Test
    fun 成功清掉退避垫底与熔断() {
        val s = state(BreakerConfig(enabled = true, failureThreshold = 1, openMillis = hour, successThreshold = 1))
        s.onFailure("a", FailureKind.UpstreamError)
        s.markSink("a")

        s.onSuccess("a")

        assertNull(s.backoffUntil("a"))
        assertNull(s.sinkUntil("a"))
        assertFalse(s.breakerOpen("a"))
    }

    @Test
    fun 成功不清限额() {
        val s = state()
        val key = CooldownKey("a", "gpt-x")
        s.markLimit(key, null)

        s.onSuccess("a")

        // 429 的时长是上游给的：本地成功一次不代表额度回来了。
        assertTrue(s.limitUntil(key) != null)
    }

    @Test
    fun 只有上游错误会产生账号级效果() {
        val s = state()

        s.onFailure("a", FailureKind.ContentBlocked)
        s.onFailure("a", FailureKind.PayloadTooLarge)
        s.onFailure("a", FailureKind.CredentialExpired)
        s.onFailure("a", FailureKind.QuotaLimited)
        s.onFailure("a", FailureKind.InvalidRequest)

        assertNull("这五类按 7.4 都不罚账号", s.backoffUntil("a"))

        s.onFailure("a", FailureKind.UpstreamError)
        assertTrue(s.backoffUntil("a") != null)
    }
}
