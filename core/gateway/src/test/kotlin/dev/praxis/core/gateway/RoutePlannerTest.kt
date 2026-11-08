package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过滤与排序（文档 7.2 / 7.3）。
 *
 * ## 为什么过滤顺序也要验
 *
 * 文档说「先便宜的判定」。顺序错了不只是慢：一个已停用的账号会被报成
 * 「限流已满」，用户去查限流设置，永远查不出问题。所以有一条用例专门钉
 * 「停用优先于限流」。
 */
class RoutePlannerTest {

    private var now = 1_000_000L

    private fun state() = RouteState(clock = { now }, breaker = BreakerConfig(false, 3, 60_000L, 1))

    private fun account(
        id: String = "a1",
        provider: String = "openai",
        enabled: Boolean = true,
        hasCredentials: Boolean = true,
        priority: Int = 0,
        supportsImages: Boolean? = null,
        maxConcurrent: Int = 0,
        running: Int = 0,
        rpm: Int = 0,
        used: Int = 0,
    ) = AccountView(
        id = id,
        providerId = provider,
        enabled = enabled,
        priority = priority,
        maxConcurrent = maxConcurrent,
        hasCredentials = hasCredentials,
        supportsImages = supportsImages,
        runningConcurrent = running,
        requestsThisMinute = used,
        requestLimitPerMinute = rpm,
    )

    private val gptX = ModelCandidate(provider = "openai", wireModel = "gpt-x")

    private fun plan(
        accounts: List<AccountView>,
        state: RouteState = state(),
        candidates: List<ModelCandidate> = listOf(gptX),
        needsImages: Boolean = false,
        sticky: String? = null,
    ) = RoutePlanner.plan(candidates, accounts, state, needsImages, sticky)

    @Test
    fun 停用的账号被跳过() {
        val result = plan(listOf(account(enabled = false)))

        assertTrue(result.attempts.isEmpty())
        assertEquals(SkipReason.Disabled, result.skipped.single().reason)
    }

    @Test
    fun 没有凭证的账号被跳过() {
        val result = plan(listOf(account(hasCredentials = false)))

        assertEquals(SkipReason.NoCredentials, result.skipped.single().reason)
    }

    @Test
    fun 带图请求跳过明确不支持图片的账号() {
        val result = plan(listOf(account(supportsImages = false)), needsImages = true)

        assertEquals(SkipReason.ImageNotSupported, result.skipped.single().reason)
    }

    @Test
    fun 不带图时不看能力位() {
        val result = plan(listOf(account(supportsImages = false)), needsImages = false)

        assertEquals(1, result.attempts.size)
    }

    @Test
    fun 能力位缺键时放行() {
        // 绝大多数老账号没有这个字段；缺键就拦等于把所有人都拦掉（文档 7.2 第 3 行）。
        val result = plan(listOf(account(supportsImages = null)), needsImages = true)

        assertEquals(1, result.attempts.size)
    }

    @Test
    fun 退避中的账号被跳过() {
        val s = state()
        s.onFailure("a1", FailureKind.UpstreamError)

        val result = plan(listOf(account()), state = s)

        assertEquals(SkipReason.CoolingDown, result.skipped.single().reason)
    }

    @Test
    fun 熔断打开时账号被跳过() {
        val s = RouteState(clock = { now }, breaker = BreakerConfig(true, failureThreshold = 1, openMillis = 60_000L, successThreshold = 1))
        s.onFailure("a1", FailureKind.UpstreamError)

        val result = plan(listOf(account()), state = s)

        assertEquals(SkipReason.CoolingDown, result.skipped.single().reason)
    }

    @Test
    fun 限额只封住那一个上游模型() {
        val s = state()
        s.markLimit(CooldownKey("a1", "gpt-x"), null)

        val both = listOf(gptX, ModelCandidate(provider = "openai", wireModel = "gpt-y"))
        val result = plan(listOf(account()), state = s, candidates = both)

        assertEquals(listOf("gpt-y"), result.attempts.map { it.wireModel })
        assertEquals(SkipReason.CoolingDown, result.skipped.single().reason)
    }

    @Test
    fun 并发已满被跳过() {
        val result = plan(listOf(account(maxConcurrent = 2, running = 2)))

        assertEquals(SkipReason.ConcurrencyFull, result.skipped.single().reason)
    }

    @Test
    fun 每分钟限流已满被跳过() {
        val result = plan(listOf(account(rpm = 5, used = 5)))

        assertEquals(SkipReason.RateLimited, result.skipped.single().reason)
    }

    @Test
    fun 停用优先于限流() {
        // 顺序错了会把停用账号报成「限流已满」，用户去查限流设置永远查不出问题。
        val result = plan(listOf(account(enabled = false, rpm = 5, used = 5)))

        assertEquals(SkipReason.Disabled, result.skipped.single().reason)
    }

    @Test
    fun 按优先级升序排列() {
        val result = plan(
            listOf(
                account(id = "low", priority = 10),
                account(id = "high", priority = 1),
                account(id = "mid", priority = 5),
            ),
        )

        assertEquals(listOf("high", "mid", "low"), result.attempts.map { it.accountId })
    }

    @Test
    fun 垫底账号仍然可用但排在最后() {
        val s = state()
        s.markSink("sunk")

        val result = plan(
            listOf(account(id = "sunk", priority = 0), account(id = "normal", priority = 100)),
            state = s,
        )

        // sink 是软降级：优先级再高也排到最后，但不会被过滤掉。
        assertEquals(listOf("normal", "sunk"), result.attempts.map { it.accountId })
        assertTrue(result.attempts.last().sink)
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun 会话粘性命中时提前() {
        val result = plan(
            listOf(account(id = "sticky", priority = 100), account(id = "other", priority = 1)),
            sticky = "sticky",
        )

        assertEquals("sticky", result.attempts.first().accountId)
        assertFalse(result.stickyCleared)
    }

    @Test
    fun 粘性账号不可用时回落并标记清理() {
        val result = plan(
            listOf(account(id = "sticky", enabled = false), account(id = "other")),
            sticky = "sticky",
        )

        assertEquals(listOf("other"), result.attempts.map { it.accountId })
        // 不清掉的话下次还钉死在那个账号上（文档 7.3）。
        assertTrue(result.stickyCleared)
    }

    // ── 家级限流（文档 7.2 第 6 行「每账号/每家的 RPM」）──

    @Test
    fun 家级配额用满时该家所有账号都跳过() {
        val result = RoutePlanner.plan(
            candidates = listOf(gptX),
            accounts = listOf(account(id = "a1"), account(id = "a2")),
            state = state(),
            needsImages = false,
            stickyAccountId = null,
            providerUsages = listOf(
                ProviderUsage(provider = "openai", requestsThisMinute = 5, limitPerMinute = 5),
            ),
        )

        // 共享配额用满时换号也躲不开 —— 两个账号都得跳过。
        assertTrue(result.attempts.isEmpty())
        assertEquals(2, result.skipped.count { it.reason == SkipReason.RateLimited })
    }

    @Test
    fun 家级配额未满时照常可用() {
        val result = RoutePlanner.plan(
            candidates = listOf(gptX),
            accounts = listOf(account(id = "a1")),
            state = state(),
            needsImages = false,
            stickyAccountId = null,
            providerUsages = listOf(
                ProviderUsage(provider = "openai", requestsThisMinute = 1, limitPerMinute = 5),
            ),
        )

        assertEquals(1, result.attempts.size)
    }

    @Test
    fun 没有配置家级限流时不影响选路() {
        val result = RoutePlanner.plan(
            candidates = listOf(gptX),
            accounts = listOf(account(id = "a1")),
            state = state(),
            needsImages = false,
            stickyAccountId = null,
            providerUsages = listOf(
                ProviderUsage(provider = "anthropic", requestsThisMinute = 99, limitPerMinute = 5),
            ),
        )

        assertEquals("限的是别家，不该牵连这一家", 1, result.attempts.size)
    }
}
