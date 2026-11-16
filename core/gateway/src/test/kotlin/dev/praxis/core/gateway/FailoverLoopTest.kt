package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败切换循环（文档 7.4 + 12 的换号上限）。
 *
 * ## 这一层验的就是 P1 的验收句
 *
 * 「两个账号，改坏一个的 key 自动换号」—— 换不换、什么时候放弃，
 * 全在这几十行里。发请求不在这一层，所以它可以脱离网络完整单测。
 */
class FailoverLoopTest {

    private val budget = FailoverBudget(maxSwitches = 3)

    private fun decide(
        kind: FailureKind,
        switches: Int = 0,
    ): FailoverStep = FailoverLoop.onFailure(
        kind = kind,
        state = FailoverState(switches = switches),
        budget = budget,
    )

    @Test
    fun 成功就是结束() {
        assertEquals(FailoverStep.Done, FailoverLoop.onSuccess())
    }

    @Test
    fun 额度受限换号() {
        assertEquals(FailoverStep.SwitchAccount, decide(FailureKind.QuotaLimited))
    }

    @Test
    fun 换号会计数() {
        val next = FailoverLoop.advance(FailoverState(), FailoverStep.SwitchAccount)

        assertEquals(1, next.switches)
    }

    @Test
    fun 达到换号上限后放弃() {
        val step = decide(FailureKind.QuotaLimited, switches = 3)

        assertTrue("换号次数到顶就该停", step is FailoverStep.GiveUp)
        assertTrue(
            (step as FailoverStep.GiveUp).reason.contains("上限"),
        )
    }

    @Test
    fun 凭证过期直接换号并标需要重新登录() {
        // Praxis 没有续期执行者：返回「去续期」只会没人做（审计 G5 的最糟状态）。
        // 语义降级为换号；needsRelogin 由 RouterSession 标，见 RouterSessionTest。
        assertEquals(FailoverStep.SwitchAccount, decide(FailureKind.CredentialExpired))
    }

    @Test
    fun 体积超限不换号直接放弃() {
        val step = decide(FailureKind.PayloadTooLarge)

        assertTrue(step is FailoverStep.GiveUp)
        assertTrue((step as FailoverStep.GiveUp).reason.contains("体积"))
    }

    @Test
    fun 请求非法直接放弃() {
        assertTrue(decide(FailureKind.InvalidRequest) is FailoverStep.GiveUp)
    }

    @Test
    fun 内容拦截换号但不罚号() {
        // 罚号与否在 FailurePolicy / RouteState 那一层；循环这里只看「换不换」。
        assertEquals(FailoverStep.SwitchAccount, decide(FailureKind.ContentBlocked))
    }

    @Test
    fun 上游错误换号() {
        assertEquals(FailoverStep.SwitchAccount, decide(FailureKind.UpstreamError))
    }

    @Test
    fun 放弃与结束都不改状态() {
        val state = FailoverState(switches = 2)

        assertEquals(state, FailoverLoop.advance(state, FailoverStep.Done))
        assertEquals(state, FailoverLoop.advance(state, FailoverStep.GiveUp("x")))
    }

    @Test
    fun 默认换号上限是三() {
        // 文档 12 明确给了这个默认值：换号当重试只会更快撞 429。
        assertEquals(3, FailoverBudget.DEFAULT_MAX_SWITCHES)
    }
}
