package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动续写判定（文档 7.5）。
 *
 * ## 最要紧的一条是「默认关闭」
 *
 * 自动续写会真的多花一次请求。所以这里不只测决策逻辑，还要直接钉住
 * 配置的默认值 —— 默认值一旦被谁改成 true，这条用例就红。
 */
class AutoContinuePolicyTest {

    private val on = AutoContinueConfig(enabled = true, maxRounds = 3)

    @Test
    fun 默认配置是关闭的() {
        assertFalse(AutoContinueConfig().enabled)
    }

    @Test
    fun 默认轮数上限是三() {
        assertEquals(3, AutoContinueConfig.DEFAULT_MAX_ROUNDS)
        assertEquals(3, AutoContinueConfig().maxRounds)
    }

    @Test
    fun 关闭时被截断也不续() {
        val decision = AutoContinuePolicy.decide(
            config = AutoContinueConfig(),
            signal = FinishSignal.LengthLimit,
            roundsDone = 0,
        )

        assertEquals(
            AutoContinueDecision.Stop(AutoContinueStopReason.Disabled),
            decision,
        )
    }

    @Test
    fun 关闭优先于其它一切() {
        // 关着的时候连「没被截断」都不用判 —— 否则理由会变成 NotTruncated，
        // 用户看到的解释就错了（他关掉了功能，不是「回答没被截断」）。
        assertEquals(
            AutoContinueDecision.Stop(AutoContinueStopReason.Disabled),
            AutoContinuePolicy.decide(AutoContinueConfig(), FinishSignal.Normal, 0),
        )
    }

    @Test
    fun 长度截断时续第一轮() {
        assertEquals(
            AutoContinueDecision.Continue(1),
            AutoContinuePolicy.decide(on, FinishSignal.LengthLimit, roundsDone = 0),
        )
    }

    @Test
    fun 上游断流同样触发续写() {
        assertEquals(
            AutoContinueDecision.Continue(1),
            AutoContinuePolicy.decide(on, FinishSignal.StreamCut, roundsDone = 0),
        )
    }

    @Test
    fun 正常结束不续() {
        assertEquals(
            AutoContinueDecision.Stop(AutoContinueStopReason.NotTruncated),
            AutoContinuePolicy.decide(on, FinishSignal.Normal, roundsDone = 0),
        )
    }

    @Test
    fun 轮次按已续次数递增() {
        assertEquals(
            AutoContinueDecision.Continue(3),
            AutoContinuePolicy.decide(on, FinishSignal.LengthLimit, roundsDone = 2),
        )
    }

    @Test
    fun 达到上限后停止() {
        assertEquals(
            AutoContinueDecision.Stop(AutoContinueStopReason.MaxRoundsReached),
            AutoContinuePolicy.decide(on, FinishSignal.LengthLimit, roundsDone = 3),
        )
    }

    @Test
    fun 上限为零时立刻停() {
        val zero = AutoContinueConfig(enabled = true, maxRounds = 0)

        assertTrue(
            AutoContinuePolicy.decide(zero, FinishSignal.LengthLimit, 0) is
                AutoContinueDecision.Stop,
        )
    }
}
