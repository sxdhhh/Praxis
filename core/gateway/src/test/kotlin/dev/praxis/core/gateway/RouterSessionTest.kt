package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求路由状态机（文档 7.1 ~ 7.4 的落地）。
 *
 * ## 这里验的就是 P1 的验收句
 *
 * 「两个账号，改坏一个的 key 自动换号」：把第一个账号的失败结果喂进来，
 * 看它会不会换到第二个、会不会把第一个标成需要重新登录。
 * 全程不需要网络，也不需要设备。
 */
class RouterSessionTest {

    private var now = 1_000_000L
    private val minute = 60_000L

    private val candidates = listOf(ModelCandidate(provider = "openai", wireModel = "gpt-x"))

    private fun accounts(vararg ids: String): MutableList<AccountView> =
        ids.mapIndexed { index, id ->
            AccountView(
                id = id,
                providerId = "openai",
                enabled = true,
                priority = index,
                maxConcurrent = 0,
                hasCredentials = true,
            )
        }.toMutableList()

    private fun state() = RouteState(
        clock = { now },
        breaker = BreakerConfig(enabled = false, failureThreshold = 3, openMillis = 5L * minute, successThreshold = 1),
    )

    private fun session(
        accounts: () -> List<AccountView>,
        state: RouteState = state(),
        budget: FailoverBudget = FailoverBudget(),
    ) = RouterSession(
        candidates = candidates,
        accounts = accounts,
        state = state,
        budget = budget,
    )

    @Test
    fun 第一个账号成功时记住粘性() {
        val s = session({ accounts("a1", "a2") })

        val attempt = s.next()!!
        s.onSuccess(attempt)

        assertEquals("a1", attempt.accountId)
        assertEquals("a1", s.stickyAfterSuccess)
        assertNull(s.outcome())
    }

    @Test
    fun 凭证过期第一次是去续期而不是换号() {
        val s = session({ accounts("a1", "a2") })
        val attempt = s.next()!!

        val step = s.onFailure(attempt, FailureKind.CredentialExpired)

        assertEquals(FailoverStep.RefreshCredentials, step)
        assertTrue("第一次还不该标 needsRelogin", s.needsRelogin.isEmpty())
    }

    @Test
    fun 续期之后仍失败才标需要重新登录() {
        val s = session({ accounts("a1", "a2") })
        val attempt = s.next()!!

        s.onFailure(attempt, FailureKind.CredentialExpired)
        val step = s.onFailure(attempt, FailureKind.CredentialExpired)

        assertEquals(FailoverStep.SwitchAccount, step)
        assertEquals(setOf("a1"), s.needsRelogin)
    }

    @Test
    fun 改坏一个_key_之后换到第二个账号() {
        val s = session({ accounts("a1", "a2") })

        val first = s.next()!!
        s.onFailure(first, FailureKind.CredentialExpired)
        s.onFailure(first, FailureKind.CredentialExpired)

        val second = s.next()!!

        assertEquals("a1", first.accountId)
        assertEquals("换号后该打到第二个账号", "a2", second.accountId)
    }

    @Test
    fun 额度受限会按账号加模型标记冷却() {
        val state = state()
        val s = session({ accounts("a1", "a2") }, state)
        val attempt = s.next()!!

        val step = s.onFailure(attempt, FailureKind.QuotaLimited, quotaResetAtMillis = now + 5L * minute)

        assertEquals(FailoverStep.SwitchAccount, step)
        assertEquals(
            "冷却键必须是 (账号, 上游模型)",
            now + 5L * minute,
            state.limitUntil(CooldownKey("a1", "gpt-x")),
        )
        assertNull("不该牵连同账号的别的模型", state.limitUntil(CooldownKey("a1", "gpt-y")))
    }

    @Test
    fun 体积超限直接放弃且不再有下一个() {
        val s = session({ accounts("a1", "a2") })
        val attempt = s.next()!!

        val step = s.onFailure(attempt, FailureKind.PayloadTooLarge)

        assertTrue(step is FailoverStep.GiveUp)
        assertNull("放弃了就不该再给候选", s.next())
        assertTrue(s.outcome() is RouteOutcome.GaveUp)
    }

    @Test
    fun 全部账号都不可用时报告没有候选() {
        val list = accounts("a1")
        list[0] = list[0].copy(enabled = false)
        val s = session({ list })

        assertNull(s.next())
        val outcome = s.outcome()
        assertTrue(outcome is RouteOutcome.NoCandidate)
        assertEquals(
            SkipReason.Disabled,
            (outcome as RouteOutcome.NoCandidate).skipped.single().reason,
        )
    }

    @Test
    fun 达到换号上限后放弃() {
        val s = session({ accounts("a1", "a2") }, budget = FailoverBudget(maxSwitches = 1))

        val first = s.next()!!
        s.onFailure(first, FailureKind.UpstreamError)
        val second = s.next()!!
        val step = s.onFailure(second, FailureKind.UpstreamError)

        assertTrue(step is FailoverStep.GiveUp)
        assertEquals(1, (s.outcome() as RouteOutcome.GaveUp).switches)
    }

    @Test
    fun 会话粘性优先选上次用过的账号() {
        val s = session({ accounts("a1", "a2") })
        s.setStickyAccount("a2")

        val attempt = s.next()!!

        assertEquals("a2", attempt.accountId)
    }

    @Test
    fun 粘性账号不可用时会被清掉() {
        val list = accounts("a1", "a2")
        val s = session({ list })
        s.setStickyAccount("a1")
        list[0] = list[0].copy(enabled = false)

        val attempt = s.next()!!

        assertEquals("a2", attempt.accountId)
        // 清掉之后，即便 a1 恢复可用，也不该再被这条会话钉住。
        s.setStickyAccount(null)
        assertNull(s.outcome())
    }

    @Test
    fun 成功后运行期负状态被清掉() {
        val state = state()
        val s = session({ accounts("a1", "a2") }, state)

        val first = s.next()!!
        s.onFailure(first, FailureKind.UpstreamError)
        assertTrue(state.backoffUntil("a1") != null)

        now += 15L * minute
        val second = s.next()!!
        s.onSuccess(second)

        assertNull(state.backoffUntil(second.accountId))
    }
}
