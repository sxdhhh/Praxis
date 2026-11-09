package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预算用尽时 planner 要跳过该账号（文档 §7.2 第 6 条）。 */
class RoutePlannerBudgetTest {

    private val now = 1_700_000_000_000L

    private fun state() = RouteState(clock = { now }, breaker = BreakerConfig(false, 3, 60_000L, 1))

    private fun account(id: String) = AccountView(
        id = id,
        providerId = "openai",
        enabled = true,
        priority = 0,
        maxConcurrent = 0,
        hasCredentials = true,
        supportsImages = null,
        runningConcurrent = 0,
        requestsThisMinute = 0,
        requestLimitPerMinute = 0,
    )

    private val gptX = ModelCandidate(provider = "openai", wireModel = "gpt-x")

    @Test
    fun `没设预算时不跳过`() {
        val plan = RoutePlanner.plan(
            candidates = listOf(gptX),
            accounts = listOf(account("a1")),
            state = state(),
            needsImages = false,
            stickyAccountId = null,
            accountSpend = mapOf("a1" to 999_999L),
        )
        assertEquals(1, plan.attempts.size)
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `预算用尽时跳过并给出原因`() {
        val plan = RoutePlanner.plan(
            candidates = listOf(gptX),
            accounts = listOf(account("a1")),
            state = state(),
            needsImages = false,
            stickyAccountId = null,
            budget = GatewayTokenBudget(perAccountTokens = 1000L),
            accountSpend = mapOf("a1" to 1000L),
        )
        assertTrue(plan.attempts.isEmpty())
        assertEquals(SkipReason.BudgetExhausted, plan.skipped.single().reason)
    }

    @Test
    fun `另一个账号还有额度就还能用`() {
        val plan = RoutePlanner.plan(
            candidates = listOf(gptX),
            accounts = listOf(account("a1"), account("a2")),
            state = state(),
            needsImages = false,
            stickyAccountId = null,
            budget = GatewayTokenBudget(perAccountTokens = 1000L),
            accountSpend = mapOf("a1" to 1000L),
        )
        assertEquals(1, plan.attempts.size)
        assertEquals("a2", plan.attempts.single().accountId)
        assertEquals(SkipReason.BudgetExhausted, plan.skipped.single().reason)
    }

    @Test
    fun `未达上限时照常可用`() {
        val plan = RoutePlanner.plan(
            candidates = listOf(gptX),
            accounts = listOf(account("a1")),
            state = state(),
            needsImages = false,
            stickyAccountId = null,
            budget = GatewayTokenBudget(perAccountTokens = 1000L),
            accountSpend = mapOf("a1" to 999L),
        )
        assertEquals(1, plan.attempts.size)
    }
}
