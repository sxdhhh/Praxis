package dev.praxis.core.gateway

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 日聚合键（文档 §5.7，网关 #329 的教训）。 */
class GatewayDailyKeyTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun millisAt(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `按给定时区算日期`() {
        val ts = millisAt(shanghai, 2026, 11, 9, 1, 0)
        assertEquals("2026-11-09", GatewayDailyKey.of(ts, shanghai))
    }

    @Test
    fun `同一时刻在 UTC 与东八区可能不是同一天`() {
        // 这正是 #329：容器按 UTC 聚合，东八区用户的「今天」整体错 8 小时。
        val ts = millisAt(shanghai, 2026, 11, 9, 1, 0)
        assertNotEquals(
            GatewayDailyKey.of(ts, shanghai),
            GatewayDailyKey.of(ts, ZoneOffset.UTC),
        )
        assertEquals("2026-11-08", GatewayDailyKey.of(ts, ZoneOffset.UTC))
    }

    @Test
    fun `跨零点切换日期`() {
        val before = millisAt(shanghai, 2026, 11, 9, 23, 59)
        val after = millisAt(shanghai, 2026, 11, 10, 0, 1)
        assertEquals("2026-11-09", GatewayDailyKey.of(before, shanghai))
        assertEquals("2026-11-10", GatewayDailyKey.of(after, shanghai))
    }

    @Test
    fun `往前推 N 天`() {
        val ts = millisAt(shanghai, 2026, 11, 9, 12, 0)
        assertEquals("2026-11-09", GatewayDailyKey.daysBefore(ts, shanghai, 0))
        assertEquals("2026-11-02", GatewayDailyKey.daysBefore(ts, shanghai, 7))
        assertEquals("2026-10-10", GatewayDailyKey.daysBefore(ts, shanghai, 30))
    }

    @Test
    fun `跨月与跨年的回推`() {
        val ts = millisAt(shanghai, 2026, 1, 1, 0, 30)
        assertEquals("2025-12-31", GatewayDailyKey.daysBefore(ts, shanghai, 1))
        assertEquals("2025-12-01", GatewayDailyKey.daysBefore(ts, shanghai, 31))
    }
}
