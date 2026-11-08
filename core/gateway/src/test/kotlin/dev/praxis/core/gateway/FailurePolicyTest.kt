package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败分类 -> 动作（文档 7.4 那张表逐行）。
 *
 * ## 为什么这张表必须逐行钉住
 *
 * 归错类的后果不是报错，而是**罚错账号**：
 *
 * - 内容拦截当成账号失败 -> 换个号还是被拦，白罚一个健康账号；
 * - 413 也换号 -> 同家再撞必然一样，纯浪费；
 * - 401 冷却账号 -> 用户续期完之后发现账号「还在冷却」。
 *
 * 这些都是「看起来在工作」的错误，线上只会表现为「有些号莫名其妙不能用」。
 */
class FailurePolicyTest {

    private val now = 1_000_000L

    @Test
    fun 额度受限_冷却到上游给的重置时刻() {
        val decision = FailurePolicy.decide(
            kind = FailureKind.QuotaLimited,
            nowMillis = now,
            quotaResetAtMillis = now + 120_000L,
        )

        assertEquals(FailoverAction.MarkQuotaAndSwitch, decision.action)
        assertEquals(120_000L, decision.cooldownMillis)
    }

    @Test
    fun 额度受限_认不出重置时刻用十分钟() {
        val decision = FailurePolicy.decide(FailureKind.QuotaLimited, nowMillis = now)

        assertEquals(FailurePolicy.DEFAULT_QUOTA_COOLDOWN_MS, decision.cooldownMillis)
    }

    @Test
    fun 重置时刻已过时不产生负冷却() {
        val decision = FailurePolicy.decide(
            kind = FailureKind.QuotaLimited,
            nowMillis = now,
            quotaResetAtMillis = now - 60_000L,
        )

        assertEquals(0L, decision.cooldownMillis)
    }

    @Test
    fun 凭证过期_先续期且不冷却账号() {
        val decision = FailurePolicy.decide(FailureKind.CredentialExpired, nowMillis = now)

        assertEquals(FailoverAction.TryRefreshThenSwitch, decision.action)
        assertEquals("账号不该被冷却：用户续期后要能立刻用", 0L, decision.cooldownMillis)
    }

    @Test
    fun 内容拦截_只换家不罚号() {
        val decision = FailurePolicy.decide(FailureKind.ContentBlocked, nowMillis = now)

        assertEquals(FailoverAction.SwitchProviderOnly, decision.action)
        assertEquals(0L, decision.cooldownMillis)
    }

    @Test
    fun 体积超限_不换号() {
        val decision = FailurePolicy.decide(FailureKind.PayloadTooLarge, nowMillis = now)

        assertEquals("同家再撞必然一样，换号纯浪费", FailoverAction.NoSwitch, decision.action)
        assertEquals(0L, decision.cooldownMillis)
    }

    @Test
    fun 上游错误_退避并换号() {
        val decision = FailurePolicy.decide(FailureKind.UpstreamError, nowMillis = now)

        assertEquals(FailoverAction.BackoffAndSwitch, decision.action)
        assertEquals(FailurePolicy.BACKOFF_BASE_MS, decision.cooldownMillis)
    }

    @Test
    fun 请求非法_不换号不重试() {
        val decision = FailurePolicy.decide(FailureKind.InvalidRequest, nowMillis = now)

        assertEquals(FailoverAction.FailFast, decision.action)
        assertEquals(0L, decision.cooldownMillis)
    }

    @Test
    fun 只有两类失败会冷却账号() {
        // 这条是「不罚错号」的总结性断言：将来若有人给 413/内容拦截加了冷却，这里会红。
        val cooldownKinds = FailureKind.entries.filter {
            FailurePolicy.decide(it, nowMillis = now).cooldownMillis > 0L
        }

        assertEquals(
            listOf(FailureKind.QuotaLimited, FailureKind.UpstreamError),
            cooldownKinds,
        )
    }

    @Test
    fun 冷却键区分账号与上游模型() {
        // 网关 issue #200：冷却写错键位 -> 账号看着没限额却被反复选中。
        val a = CooldownKey(accountId = "acct-1", wireModel = "gpt-x")
        val b = CooldownKey(accountId = "acct-1", wireModel = "gpt-y")
        val c = CooldownKey(accountId = "acct-2", wireModel = "gpt-x")

        assertNotEquals("同账号不同模型是两条冷却", a, b)
        assertNotEquals("不同账号同模型是两条冷却", a, c)
        assertEquals(a, CooldownKey("acct-1", "gpt-x"))
        assertTrue(a.hashCode() == CooldownKey("acct-1", "gpt-x").hashCode())
    }
}
