package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 路由回放（文档 §5.5 的降级形态）。 */
class RouteReplayLogTest {

    private fun entry(model: String) = RouteReplayEntry(
        model = model,
        tried = listOf("a1/gpt-x"),
        skipped = emptyList(),
        outcome = "成功",
        atMillis = 1L,
    )

    @Test
    fun `最近的排在最前`() {
        // 排障看的是「刚才」，最新的那条最容易用得上。
        val log = RouteReplayLog()
        log.record(entry("first"))
        log.record(entry("second"))
        assertEquals(listOf("second", "first"), log.recent().map { it.model })
    }

    @Test
    fun `超出容量时丢最旧的`() {
        val log = RouteReplayLog(capacity = 3)
        (1..5).forEach { log.record(entry("m$it")) }
        assertEquals(listOf("m5", "m4", "m3"), log.recent().map { it.model })
    }

    @Test
    fun `容量为一也能工作`() {
        val log = RouteReplayLog(capacity = 1)
        log.record(entry("a"))
        log.record(entry("b"))
        assertEquals(listOf("b"), log.recent().map { it.model })
    }

    @Test
    fun `默认容量是文档说的 50`() {
        assertEquals(50, RouteReplayLog.DEFAULT_CAPACITY)
    }

    @Test
    fun `清空之后没有残留`() {
        val log = RouteReplayLog()
        log.record(entry("a"))
        log.clear()
        assertTrue(log.recent().isEmpty())
    }

    @Test
    fun `保留跳过原因（排障真正要看的东西）`() {
        val log = RouteReplayLog()
        log.record(
            RouteReplayEntry(
                model = "gpt-x",
                tried = listOf("a1/gpt-x"),
                skipped = listOf(SkippedAttempt("a2", "gpt-x", SkipReason.CoolingDown)),
                outcome = "成功",
                atMillis = 1L,
            ),
        )
        val recorded = log.recent().single()
        assertEquals(SkipReason.CoolingDown, recorded.skipped.single().reason)
        assertEquals("a2", recorded.skipped.single().accountId)
    }
}
