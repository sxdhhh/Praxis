package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 报表的展示规则（文档 §9.6）。 */
class GatewayReportFormatTest {

    private fun summary(requests: Int, failures: Int, tokens: Long = 0L, buckets: Int = 0) =
        GatewayUsageSummary(
            requests = requests,
            failures = failures,
            tokens = tokens,
            byModel = (1..buckets).map { i -> GatewayUsageBucket("m" + i, i, 0, 0L) },
        )

    @Test
    fun `没有请求时成功率是未知而不是 0`() {
        // 「没有数据」与「全失败」在报表上是两回事。
        assertNull(summary(0, 0).successRate)
        assertEquals("—", GatewayReportFormat.successRateText(null))
        assertEquals("0%", GatewayReportFormat.successRateText(0.0))
    }

    @Test
    fun `成功率保留一位小数`() {
        assertEquals("92.3%", GatewayReportFormat.successRateText(0.923))
        assertEquals("50%", GatewayReportFormat.successRateText(0.5))
    }

    @Test
    fun `全成功显示 100 而不是 99_9`() {
        // 12 次里 11 次成功是 91.7%；这里测的是「一次都没失败」被四舍五入成 99.9% 的情况。
        assertEquals("100%", GatewayReportFormat.successRateText(1.0))
        assertEquals("100%", GatewayReportFormat.successRateText(0.99999))
    }

    @Test
    fun `token 数按量级缩写`() {
        assertEquals("999", GatewayReportFormat.tokenText(999))
        assertEquals("1.5k", GatewayReportFormat.tokenText(1_500))
        assertEquals("12.3k", GatewayReportFormat.tokenText(12_345))
        assertEquals("1.5M", GatewayReportFormat.tokenText(1_500_000))
    }

    @Test
    fun `汇总行把四件事说全`() {
        val text = GatewayReportFormat.requestsText(summary(10, 2, tokens = 12_345))
        assertEquals(true, text.contains("10 次"))
        assertEquals(true, text.contains("失败 2"))
        assertEquals(true, text.contains("80%"))
        assertEquals(true, text.contains("12.3k tokens"))
    }

    @Test
    fun `窗口按天定义`() {
        val today = GatewayReportWindow("今日", 1)
        assertEquals(1L, today.days)
        assertEquals("近 7 天", GatewayReportWindow("近 7 天", 7).label)
    }
}
