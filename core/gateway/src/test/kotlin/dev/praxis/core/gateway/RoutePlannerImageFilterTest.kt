package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐模型图片能力过滤（G3）：带图请求只拦**明确声明不支持**的那个模型。
 */
class RoutePlannerImageFilterTest {

    private fun state() = RouteState(clock = { 0L }, breaker = BreakerConfig(false, 3, 60_000L, 1))

    private fun account(id: String = "a1") = AccountView(
        id = id, providerId = "p", enabled = true, priority = 1,
        maxConcurrent = 0, hasCredentials = true,
    )

    private fun candidate(wire: String = "m") = ModelCandidate(
        provider = "p", wireModel = wire,
    )

    @Test
    fun `模型明确不支持图片时跳过并给专属理由`() {
        val plan = RoutePlanner.plan(
            candidates = listOf(candidate()),
            accounts = listOf(account()),
            state = state(),
            needsImages = true,
            stickyAccountId = null,
            modelImageCapable = { _, _ -> false },
        )
        assertEquals(0, plan.attempts.size)
        assertEquals(SkipReason.ImageModelNotCapable, plan.skipped.single().reason)
    }

    @Test
    fun `查表返回 true 或不知道时放行`() {
        val pass = RoutePlanner.plan(
            candidates = listOf(candidate()),
            accounts = listOf(account()),
            state = state(),
            needsImages = true,
            stickyAccountId = null,
            modelImageCapable = { _, _ -> true },
        )
        assertEquals(1, pass.attempts.size)

        // 表里没有这一条 = 不知道 = 放行（把 null 当 false 会拦掉没上报的家）。
        val unknown = RoutePlanner.plan(
            candidates = listOf(candidate("other")),
            accounts = listOf(account()),
            state = state(),
            needsImages = true,
            stickyAccountId = null,
            modelImageCapable = { _, wire -> if (wire == "m") true else null },
        )
        assertEquals(1, unknown.attempts.size)
    }

    @Test
    fun `不带图的请求不查表`() {
        val plan = RoutePlanner.plan(
            candidates = listOf(candidate()),
            accounts = listOf(account()),
            state = state(),
            needsImages = false,
            stickyAccountId = null,
            modelImageCapable = { _, _ -> false },
        )
        assertEquals(1, plan.attempts.size)
        assertTrue(plan.skipped.isEmpty())
    }
}