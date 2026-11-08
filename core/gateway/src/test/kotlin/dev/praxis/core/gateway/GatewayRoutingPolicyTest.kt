package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 策略（顺序/轮询 + 粘性）与它的编解码（文档 §9.4）。 */
class GatewayRoutingPolicyTest {

    private var now = 1_000_000L

    private fun state() = RouteState(clock = { now }, breaker = BreakerConfig(false, 3, 60_000L, 1))

    private fun account(id: String, priority: Int = 0) = AccountView(
        id = id,
        providerId = "openai",
        enabled = true,
        priority = priority,
        maxConcurrent = 0,
        hasCredentials = true,
    )

    private val candidate = ModelCandidate(provider = "openai", wireModel = "gpt-x")

    private fun plan(
        accounts: List<AccountView>,
        policy: GatewayRoutingPolicy = GatewayRoutingPolicy(),
        sticky: String? = null,
        seed: Int = 0,
    ) = RoutePlanner.plan(
        candidates = listOf(candidate),
        accounts = accounts,
        state = state(),
        needsImages = false,
        stickyAccountId = sticky,
        policy = policy,
        rotationSeed = seed,
    )

    // ── 顺序策略 ──

    @Test
    fun `顺序策略按优先级排且忽略轮询种子`() {
        val accounts = listOf(account("a", priority = 0), account("b", priority = 1))
        val plan = plan(accounts, seed = 1)
        assertEquals(listOf("a", "b"), plan.attempts.map { it.accountId })
    }

    // ── 轮询 ──

    @Test
    fun `轮询按种子整体转位`() {
        val accounts = listOf(account("a", priority = 0), account("b", priority = 1), account("c", priority = 2))
        val policy = GatewayRoutingPolicy(strategy = RoutingStrategy.RoundRobin)
        assertEquals(listOf("a", "b", "c"), plan(accounts, policy, seed = 0).attempts.map { it.accountId })
        assertEquals(listOf("b", "c", "a"), plan(accounts, policy, seed = 1).attempts.map { it.accountId })
        assertEquals(listOf("c", "a", "b"), plan(accounts, policy, seed = 2).attempts.map { it.accountId })
    }

    @Test
    fun `轮询的种子超出长度时取模而不是越界`() {
        val accounts = listOf(account("a", 0), account("b", 1))
        val policy = GatewayRoutingPolicy(strategy = RoutingStrategy.RoundRobin)
        assertEquals(listOf("a", "b"), plan(accounts, policy, seed = 4).attempts.map { it.accountId })
        assertEquals(listOf("b", "a"), plan(accounts, policy, seed = 5).attempts.map { it.accountId })
    }

    @Test
    fun `只有一个账号时轮询等于顺序`() {
        val policy = GatewayRoutingPolicy(strategy = RoutingStrategy.RoundRobin)
        assertEquals(listOf("a"), plan(listOf(account("a")), policy, seed = 7).attempts.map { it.accountId })
    }

    // ── 粘性 ──

    @Test
    fun `粘性开启时上次账号排到最前`() {
        val accounts = listOf(account("a", 0), account("b", 1))
        val plan = plan(accounts, sticky = "b")
        assertEquals(listOf("b", "a"), plan.attempts.map { it.accountId })
        assertFalse(plan.stickyCleared)
    }

    @Test
    fun `粘性关掉时不再贴住上次账号`() {
        val accounts = listOf(account("a", 0), account("b", 1))
        val policy = GatewayRoutingPolicy(stickyEnabled = false)
        val plan = plan(accounts, policy, sticky = "b")
        assertEquals(listOf("a", "b"), plan.attempts.map { it.accountId })
    }

    @Test
    fun `粘性关掉时不该报粘性被清掉`() {
        // 用户没触发任何状态变化，界面不该冒出一条提示。
        val accounts = listOf(account("a", 0))
        val policy = GatewayRoutingPolicy(stickyEnabled = false)
        val plan = plan(accounts, policy, sticky = "gone")
        assertFalse(plan.stickyCleared)
    }

    @Test
    fun `粘性开启但账号已不可用时如实报 cleared`() {
        val accounts = listOf(account("a", 0))
        val plan = plan(accounts, sticky = "gone")
        assertTrue(plan.stickyCleared)
    }

    // ── 编解码 ──

    @Test
    fun `策略编解码往返`() {
        val policy = GatewayRoutingPolicy(RoutingStrategy.RoundRobin, stickyEnabled = false)
        assertEquals(policy, GatewayRoutingPolicyCodec.decode(GatewayRoutingPolicyCodec.encode(policy))?.getOrNull())
    }

    @Test
    fun `没配过时返回 null 而不是默认值`() {
        assertNull(GatewayRoutingPolicyCodec.decode(null))
        assertNull(GatewayRoutingPolicyCodec.decode("  "))
    }

    @Test
    fun `坏内容与不认识的策略都算失败`() {
        assertTrue(GatewayRoutingPolicyCodec.decode("{bad")!!.isFailure)
        assertTrue(GatewayRoutingPolicyCodec.decode("""{"strategy":"random","stickyEnabled":true}""")!!.isFailure)
        assertTrue(GatewayRoutingPolicyCodec.decode("""{"strategy":"Sequential"}""")!!.isFailure)
    }
}
