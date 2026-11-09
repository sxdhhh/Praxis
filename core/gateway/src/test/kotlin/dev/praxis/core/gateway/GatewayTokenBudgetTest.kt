package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** token 预算（文档 §7.2）。 */
class GatewayTokenBudgetTest {

    @Test
    fun `没设额度就是关着`() {
        // 默认给个数字会让用户莫名撞上「额度用完了」，而他从没设过任何额度。
        val budget = GatewayTokenBudget()
        assertFalse(budget.enabled)
        assertFalse(budget.isExhausted("a1", mapOf("a1" to 999_999L)))
    }

    @Test
    fun `达到上限即算用完`() {
        val budget = GatewayTokenBudget(perAccountTokens = 1000L)
        assertFalse(budget.isExhausted("a1", mapOf("a1" to 999L)))
        assertTrue(budget.isExhausted("a1", mapOf("a1" to 1000L)))
        assertTrue(budget.isExhausted("a1", mapOf("a1" to 5000L)))
    }

    @Test
    fun `没有记录的账号按 0 算`() {
        val budget = GatewayTokenBudget(perAccountTokens = 1000L)
        assertFalse(budget.isExhausted("never-used", emptyMap()))
    }

    @Test
    fun `只看自己的账号`() {
        // 另一个账号用爆了不该牵连这个。
        val budget = GatewayTokenBudget(perAccountTokens = 1000L)
        assertFalse(budget.isExhausted("a1", mapOf("a2" to 99_999L)))
    }

    @Test
    fun `窗口起点按小时折算`() {
        val budget = GatewayTokenBudget(perAccountTokens = 1L, windowHours = 24)
        assertEquals(24L * 3600L * 1000L, 1_800_000_000_000L - budget.windowStartMillis(1_800_000_000_000L))
    }

    @Test
    fun `校验规则`() {
        assertNull(GatewayTokenBudgetRules.validate(GatewayTokenBudget(perAccountTokens = 1000L)))
        assertNull(GatewayTokenBudgetRules.validate(GatewayTokenBudget()))
        assertTrue(GatewayTokenBudgetRules.validate(GatewayTokenBudget(perAccountTokens = -1L)) != null)
        assertTrue(GatewayTokenBudgetRules.validate(GatewayTokenBudget(windowHours = 0)) != null)
        assertTrue(GatewayTokenBudgetRules.validate(GatewayTokenBudget(windowHours = 1000)) != null)
    }
}
