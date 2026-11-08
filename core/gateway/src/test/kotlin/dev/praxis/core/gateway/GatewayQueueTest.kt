package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选队列摊平（文档 9.4）。
 *
 * 界面靠它回答两个问题：「我会先用谁」「那些没参与的是为什么」。
 * 位次错一位，用户对「我排第几」的判断就错了。
 */
class GatewayQueueTest {

    private fun attempt(id: String, sink: Boolean = false) = PlannedAttempt(
        accountId = id,
        provider = "custom-a",
        wireModel = "m1",
        reasoning = null,
        sink = sink,
    )

    private fun skipped(id: String, reason: SkipReason) = SkippedAttempt(
        accountId = id,
        wireModel = "m1",
        reason = reason,
    )

    @Test
    fun 可用的按位次从一起排() {
        val plan = RoutePlan(
            attempts = listOf(attempt("a1"), attempt("a2")),
            skipped = emptyList(),
            stickyCleared = false,
        )

        val queue = GatewayQueue.of(plan)

        assertEquals(listOf(1, 2), queue.map { it.position })
        assertEquals(listOf("a1", "a2"), queue.map { it.accountId })
        assertTrue(queue.all { it.usable })
    }

    @Test
    fun 跳过的排在可用之后且位次为零() {
        val plan = RoutePlan(
            attempts = listOf(attempt("a1")),
            skipped = listOf(skipped("a9", SkipReason.Disabled)),
            stickyCleared = false,
        )

        val queue = GatewayQueue.of(plan)

        assertEquals(listOf("a1", "a9"), queue.map { it.accountId })
        assertEquals(0, queue.last().position)
        assertFalse(queue.last().usable)
        assertNull(queue.first().skipReason)
    }

    @Test
    fun 垫底标记被保留() {
        val plan = RoutePlan(
            attempts = listOf(attempt("a1"), attempt("a2", sink = true)),
            skipped = emptyList(),
            stickyCleared = false,
        )

        val queue = GatewayQueue.of(plan)

        assertFalse(queue.first().sink)
        assertTrue(queue.last().sink)
    }

    @Test
    fun 空计划给空队列() {
        val plan = RoutePlan(attempts = emptyList(), skipped = emptyList(), stickyCleared = false)

        assertTrue(GatewayQueue.of(plan).isEmpty())
    }

    @Test
    fun 每种跳过原因都有文案() {
        SkipReason.entries.forEach { reason ->
            assertTrue(
                "缺少文案：" + reason,
                GatewayQueue.reasonText(reason).isNotBlank(),
            )
        }
    }
}
