package dev.praxis.core.gateway

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** 请求记录的保留期（文档 §5.7）。 */
class GatewayRetentionTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun millisAt(hour: Int, day: Int): Long =
        LocalDateTime.of(2026, 11, day, hour, 30).atZone(shanghai).toInstant().toEpochMilli()

    @Test
    fun `切点是保留天数之前的零点`() {
        // 与日聚合同一个口径：按本地时区的那一天算，不是 now - N*24h。
        val cutoff = GatewayRetention.cutoffMillis(millisAt(15, 10), days = 7, zone = shanghai)
        val expected = LocalDateTime.of(2026, 11, 3, 0, 0).atZone(shanghai).toInstant().toEpochMilli()
        assertEquals(expected, cutoff)
    }

    @Test
    fun `当天早晚取到同一个切点`() {
        // 这点很重要：否则「保留 7 天」会随一天里的时刻漂移。
        assertEquals(
            GatewayRetention.cutoffMillis(millisAt(1, 10), 7, shanghai),
            GatewayRetention.cutoffMillis(millisAt(23, 10), 7, shanghai),
        )
    }

    @Test
    fun `跨月回推正确`() {
        val cutoff = GatewayRetention.cutoffMillis(millisAt(12, 2), days = 7, zone = shanghai)
        val expected = LocalDateTime.of(2026, 10, 26, 0, 0).atZone(shanghai).toInstant().toEpochMilli()
        assertEquals(expected, cutoff)
    }

    @Test
    fun `天数被夹到合法区间`() {
        assertEquals(
            GatewayRetention.cutoffMillis(millisAt(12, 10), 0, shanghai),
            GatewayRetention.cutoffMillis(millisAt(12, 10), 1, shanghai),
        )
        assertEquals(
            GatewayRetention.cutoffMillis(millisAt(12, 10), 99999, shanghai),
            GatewayRetention.cutoffMillis(millisAt(12, 10), 365, shanghai),
        )
    }

    @Test
    fun `默认是 7 天`() {
        assertEquals(7, GatewayRetention.DEFAULT_DAYS)
        assertEquals(7, GatewayRetention.parseDays(null))
        assertEquals(7, GatewayRetention.parseDays(7))
    }

    @Test
    fun `坏值不会变成零或负数`() {
        // 0 天等于「什么都不留」，那不是用户想要的（他要的是少留一点）。
        assertEquals(1, GatewayRetention.parseDays(0))
        assertEquals(1, GatewayRetention.parseDays(-5))
        assertEquals(365, GatewayRetention.parseDays(100000))
    }
}
