package dev.praxis.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间戳分配器（2026-11-07，R6）。
 *
 * ## 四条不变式
 *
 *  1. **严格递增**：同一毫秒内连续分配也逐个 +1，不相等；
 *  2. **抗时钟回拨**：时钟退后也不产生倒序；
 *  3. **observe 前推水位**：恢复历史后新消息必须排在历史之后；
 *  4. **observe 不回退**：传入更小的值不降低水位。
 *
 * 第 1 条是排序正确性的根：消息按 createdAt 排序，一旦出现相等，
 * 同毫秒的两条就退化成「按 id 兜底」—— 而 id 是数据库分配的，
 * 与业务顺序没有必然关系（这正是 orderIndex 的老毛病）。
 */
class MessageTimestampAllocatorTest {

    /** 同一毫秒内连续分配：必须逐个递增，不能相等。 */
    @Test
    fun strictlyIncreasingWithinSameMillisecond() {
        val allocator = MessageTimestampAllocator(now = { 1_000L })

        val first = allocator.next()
        val second = allocator.next()
        val third = allocator.next()

        assertEquals(1_000L, first)
        assertEquals(1_001L, second)
        assertEquals(1_002L, third)
    }

    /** 时钟回拨：返回值仍必须递增。 */
    @Test
    fun clockGoingBackwardsDoesNotProduceDuplicates() {
        var clock = 5_000L
        val allocator = MessageTimestampAllocator(now = { clock })

        val before = allocator.next()
        clock = 1_000L
        val after = allocator.next()

        assertTrue("时钟回拨后仍须递增", after > before)
    }

    /** observe 把水位推高，之后 next() 必须大于它。 */
    @Test
    fun observeRaisesWatermark() {
        val allocator = MessageTimestampAllocator(now = { 100L })

        allocator.observe(9_999L)
        // 水位断言必须放在 next() **之前**：next() 自己也会推高水位，
        // 先调它再断言 9_999 等于在验一个已经被自己改掉的值。
        assertEquals(9_999L, allocator.watermark())

        val next = allocator.next()
        assertTrue("新时间戳必须排在已观察的历史之后", next > 9_999L)
    }

    /** observe 只前推：传入更小的值不得让水位下降。 */
    @Test
    fun observeNeverLowersWatermark() {
        val allocator = MessageTimestampAllocator(now = { 100L })

        allocator.observe(5_000L)
        allocator.observe(10L)

        assertEquals("水位不该被拉低", 5_000L, allocator.watermark())
    }
}
