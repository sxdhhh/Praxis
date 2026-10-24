package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存压力判定的回归护栏。
 *
 * ## 为什么这个测试值得写（判定层最容易出的三类错）
 *
 * 1. **阈值边界算不算**：`>=` 还是 `>`。写成 `>` 时「刚好 92%」不触发危险档，
 *    而那一档正是「下一帧就可能崩」的位置。
 * 2. **信号互相遮蔽**：一条 if 链里先 return，后面那条信号就永远无效。
 *    旧实现把 trim 放在最前面，于是「系统顺手发了个后台 trim」会把
 *    堆的真实水位完全盖住。
 * 3. **滞回方向反了**：降档死区写成「加」而不是「减」，会导致
 *    永不降档（档位焊死在最高点，一直在清缓存 / 压缩）。
 *
 * 这三类错在真机上都不报错，只是行为怪。所以全部用断言锁死。
 */
class MemoryPressurePolicyTest {

    /** 造一个只看堆占用率的快照（系统项都填 0，表示读不到）。 */
    private fun heap(ratio: Float): MemorySnapshot {
        val max = 100L * 1024L * 1024L
        return MemorySnapshot(
            javaHeapUsedBytes = (max * ratio).toLong(),
            javaHeapMaxBytes = max,
        )
    }

    // ── 阈值边界 ─────────────────────────────────────────────

    @Test
    fun `heap below elevated threshold is normal`() {
        assertEquals(MemoryPressure.Normal, MemoryPressurePolicy.rawLevel(heap(0.74f)))
    }

    @Test
    fun `heap exactly at elevated threshold is elevated`() {
        // 边界必须含等号：0.75 就是「已经到这一档」。
        assertEquals(MemoryPressure.Elevated, MemoryPressurePolicy.rawLevel(heap(0.75f)))
    }

    @Test
    fun `heap exactly at high threshold is high`() {
        assertEquals(MemoryPressure.High, MemoryPressurePolicy.rawLevel(heap(0.85f)))
    }

    @Test
    fun `heap exactly at critical threshold is critical`() {
        assertEquals(MemoryPressure.Critical, MemoryPressurePolicy.rawLevel(heap(0.92f)))
    }

    @Test
    fun `unknown heap max degrades to zero ratio rather than crashing`() {
        // `Runtime.maxMemory()` 读不到时上层传 0；此时占用率必须是 0 而不是除零。
        val snapshot = MemorySnapshot(javaHeapUsedBytes = 50L, javaHeapMaxBytes = 0L)
        assertEquals(0f, snapshot.javaHeapRatio, 0.0001f)
        assertEquals(MemoryPressure.Normal, MemoryPressurePolicy.rawLevel(snapshot))
    }

    // ── 信号不互相遮蔽（旧实现的真实缺陷）──────────────────────

    @Test
    fun `background trim level does not hide a low heap`() {
        // TRIM_MEMORY_UI_HIDDEN：切后台就会发，与内存压力无关。
        // 旧实现把它归到 >= 15 → Critical，于是切一次后台回来判定永久是危险档。
        val snapshot = heap(0.30f).copy(trimLevel = MemoryPressurePolicy.TRIM_UI_HIDDEN)
        assertEquals(MemoryPressure.Elevated, MemoryPressurePolicy.rawLevel(snapshot))
    }

    @Test
    fun `background trim levels map to elevated only`() {
        // 40 / 60 / 80 是后台 LRU 档位：可以清缓存，但不该压缩
        // （后台不发消息，压缩纯属浪费一次模型调用）。
        for (trim in listOf(40, 60, 80)) {
            val level = MemoryPressurePolicy.trimLevelToPressure(trim)
            assertEquals("trim=$trim", MemoryPressure.Elevated, level)
            assertFalse("trim=$trim 不应触发压缩", MemoryPressurePolicy.shouldCompact(level))
        }
    }

    @Test
    fun `foreground trim levels map by severity`() {
        assertEquals(
            MemoryPressure.Normal,
            MemoryPressurePolicy.trimLevelToPressure(0),
        )
        assertEquals(
            MemoryPressure.Elevated,
            MemoryPressurePolicy.trimLevelToPressure(MemoryPressurePolicy.TRIM_RUNNING_MODERATE),
        )
        assertEquals(
            MemoryPressure.High,
            MemoryPressurePolicy.trimLevelToPressure(MemoryPressurePolicy.TRIM_RUNNING_LOW),
        )
        assertEquals(
            MemoryPressure.Critical,
            MemoryPressurePolicy.trimLevelToPressure(MemoryPressurePolicy.TRIM_RUNNING_CRITICAL),
        )
    }

    @Test
    fun `high heap survives a background trim`() {
        // 堆 88% + 后台 trim → 必须是 High（堆没被 trim 盖住）。
        val snapshot = heap(0.88f).copy(trimLevel = MemoryPressurePolicy.TRIM_UI_HIDDEN)
        assertEquals(MemoryPressure.High, MemoryPressurePolicy.rawLevel(snapshot))
    }

    @Test
    fun `lowMemory flag lifts to high but not critical`() {
        val snapshot = heap(0.10f).copy(lowMemory = true)
        assertEquals(MemoryPressure.High, MemoryPressurePolicy.rawLevel(snapshot))
    }

    @Test
    fun `system ratio alone only lifts to elevated`() {
        // 系统 95% / 堆 10% → 只到 Elevated（清缓存），不进压缩档。
        // 理由：`availMem` 含可回收 page cache，长期偏高是常态。
        val snapshot = MemorySnapshot(
            javaHeapUsedBytes = 10L,
            javaHeapMaxBytes = 100L,
            systemAvailBytes = 5L,
            systemTotalBytes = 100L,
        )
        val level = MemoryPressurePolicy.rawLevel(snapshot)
        assertEquals(MemoryPressure.Elevated, level)
        assertTrue(MemoryPressurePolicy.shouldTrimCaches(level))
        assertFalse(MemoryPressurePolicy.shouldCompact(level))
    }

    @Test
    fun `system ratio below threshold with low heap stays normal`() {
        val snapshot = MemorySnapshot(
            javaHeapUsedBytes = 10L,
            javaHeapMaxBytes = 100L,
            systemAvailBytes = 20L,
            systemTotalBytes = 100L,
        )
        assertEquals(MemoryPressure.Normal, MemoryPressurePolicy.rawLevel(snapshot))
    }

    // ── 滞回（降档死区）────────────────────────────────────────

    @Test
    fun `upgrade is immediate with no deadband`() {
        // 升档方向不留余量：一旦到阈值就升，否则就晚了。
        assertEquals(
            MemoryPressure.Critical,
            MemoryPressurePolicy.evaluate(heap(0.93f), previous = MemoryPressure.Normal),
        )
    }

    @Test
    fun `stay inside deadband keeps previous level`() {
        // 上一档 High（0.85），现在 0.83 —— 高于 0.85-0.05=0.80，仍在死区内，不降。
        assertEquals(
            MemoryPressure.High,
            MemoryPressurePolicy.evaluate(heap(0.83f), previous = MemoryPressure.High),
        )
    }

    @Test
    fun `drop below deadband floor actually downgrades`() {
        // 0.79 < 0.80：出死区，降回 Elevated。
        assertEquals(
            MemoryPressure.Elevated,
            MemoryPressurePolicy.evaluate(heap(0.79f), previous = MemoryPressure.High),
        )
    }

    @Test
    fun `deadband boundary is exclusive on the floor`() {
        // 恰好等于 0.80 时 `> 0.80` 不成立 → 允许降档。
        // 这条锁的是「死区边界用 > 而不是 >=」，改方向会立刻红。
        assertEquals(
            MemoryPressure.Elevated,
            MemoryPressurePolicy.evaluate(heap(0.80f), previous = MemoryPressure.High),
        )
    }

    @Test
    fun `lowMemory blocks downgrade even when heap is low`() {
        val snapshot = heap(0.10f).copy(lowMemory = true)
        assertEquals(
            MemoryPressure.High,
            MemoryPressurePolicy.evaluate(snapshot, previous = MemoryPressure.High),
        )
    }

    @Test
    fun `system pressure blocks downgrade from elevated`() {
        // 系统 95% / 堆 10%，上一档 Elevated → 必须留在 Elevated。
        // 若死区只查堆，这里会降到 Normal，然后下一轮又升回 Elevated：
        // 每 15 秒抖一次，每次都跟着清一轮缓存。
        val snapshot = MemorySnapshot(
            javaHeapUsedBytes = 10L,
            javaHeapMaxBytes = 100L,
            systemAvailBytes = 5L,
            systemTotalBytes = 100L,
        )
        assertEquals(
            MemoryPressure.Elevated,
            MemoryPressurePolicy.evaluate(snapshot, previous = MemoryPressure.Elevated),
        )
    }

    @Test
    fun `foreground trim blocks downgrade`() {
        // 前台 trim 仍在有效期内（MemoryMonitor 已负责 60s 过期）→ 不降档。
        val snapshot = heap(0.10f).copy(
            trimLevel = MemoryPressurePolicy.TRIM_RUNNING_MODERATE,
        )
        assertEquals(
            MemoryPressure.Elevated,
            MemoryPressurePolicy.evaluate(snapshot, previous = MemoryPressure.Elevated),
        )
    }

    @Test
    fun `normal previous never blocks anything`() {
        // previous = Normal 时没有可降的档位，任何采样都直接给原始判定。
        assertEquals(
            MemoryPressure.Normal,
            MemoryPressurePolicy.evaluate(heap(0.10f), previous = MemoryPressure.Normal),
        )
    }

    // ── 动作门槛 ─────────────────────────────────────────────

    @Test
    fun `trim caches starts at elevated but compact starts at high`() {
        // 这两条门槛是「清缓存免费、压缩要钱」这条设计决策的唯一落点。
        assertFalse(MemoryPressurePolicy.shouldTrimCaches(MemoryPressure.Normal))
        assertTrue(MemoryPressurePolicy.shouldTrimCaches(MemoryPressure.Elevated))
        assertTrue(MemoryPressurePolicy.shouldTrimCaches(MemoryPressure.High))

        assertFalse(MemoryPressurePolicy.shouldCompact(MemoryPressure.Normal))
        assertFalse(MemoryPressurePolicy.shouldCompact(MemoryPressure.Elevated))
        assertTrue(MemoryPressurePolicy.shouldCompact(MemoryPressure.High))
        assertTrue(MemoryPressurePolicy.shouldCompact(MemoryPressure.Critical))
    }

    @Test
    fun `heap thresholds are strictly increasing`() {
        // 顺序错了会让某一档永远不可达（例如 High > Critical 时 High 被吞掉）。
        assertTrue(MemoryPressurePolicy.HEAP_ELEVATED < MemoryPressurePolicy.HEAP_HIGH)
        assertTrue(MemoryPressurePolicy.HEAP_HIGH < MemoryPressurePolicy.HEAP_CRITICAL)
        assertTrue(MemoryPressurePolicy.HEAP_CRITICAL <= 1f)
        // 死区必须小于相邻档位间距，否则上一档永远不会降。
        val gap = MemoryPressurePolicy.HEAP_HIGH - MemoryPressurePolicy.HEAP_ELEVATED
        assertTrue(MemoryPressurePolicy.DEADBAND < gap)
    }

    @Test
    fun `heapThresholdFor matches the constants`() {
        assertEquals(0f, MemoryPressurePolicy.heapThresholdFor(MemoryPressure.Normal), 0f)
        assertEquals(
            MemoryPressurePolicy.HEAP_ELEVATED,
            MemoryPressurePolicy.heapThresholdFor(MemoryPressure.Elevated),
            0f,
        )
        assertEquals(
            MemoryPressurePolicy.HEAP_HIGH,
            MemoryPressurePolicy.heapThresholdFor(MemoryPressure.High),
            0f,
        )
        assertEquals(
            MemoryPressurePolicy.HEAP_CRITICAL,
            MemoryPressurePolicy.heapThresholdFor(MemoryPressure.Critical),
            0f,
        )
    }

    // ── 触发原因标记 ──────────────────────────────────────────

    @Test
    fun `compaction trigger describes its origin`() {
        assertEquals("manual", CompactionTrigger.Manual.describe())
        assertEquals(
            "memory:High",
            CompactionTrigger.ByMemoryPressure(MemoryPressure.High).describe(),
        )
    }

    // ── 快照自检 ──────────────────────────────────────────────

    @Test
    fun `describe includes heap and ratio`() {
        val text = heap(0.5f).describe()
        assertTrue(text.contains("java="))
        assertTrue(text.contains("50%"))
    }

    @Test
    fun `unavailable is only when both sources are missing`() {
        assertTrue(MemorySnapshot().isUnavailable)
        // 只有系统项读得到：不算不可用（还能降级判系统压力）。
        assertFalse(
            MemorySnapshot(systemTotalBytes = 100L, systemAvailBytes = 50L).isUnavailable,
        )
        assertFalse(MemorySnapshot(javaHeapMaxBytes = 100L).isUnavailable)
    }
}
