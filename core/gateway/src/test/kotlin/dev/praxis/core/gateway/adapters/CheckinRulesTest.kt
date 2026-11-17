package dev.praxis.core.gateway.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 签到判定：409 是正常结果、余额文案多字段兜底。 */
class CheckinRulesTest {

    @Test
    fun `已领取显示 warn 而不是错误`() {
        val message = CheckinRules.claimMessage(CheckinResult(takenToday = true, message = "今日已签到"))
        assertTrue(message.contains("已经领过"))
    }

    @Test
    fun `成功领取显示成功`() {
        val message = CheckinRules.claimMessage(CheckinResult(takenToday = false, message = ""))
        assertTrue(message.contains("签到成功"))
    }

    @Test
    fun `余额文案认 credits 字段`() {
        val text = CheckinRules.creditsText("""{"data":{"credits":"128"}}""".trimIndent())
        assertEquals("credits 128", text)
    }

    @Test
    fun `余额文案认 balance 字段`() {
        val text = CheckinRules.creditsText("""{"data":{"balance":"56.7"}}""".trimIndent())
        assertEquals("balance 56.7", text)
    }

    @Test
    fun `余额字段缺失返回 null`() {
        assertNull(CheckinRules.creditsText("""{"data":{}}""".trimIndent()))
        assertNull(CheckinRules.creditsText("不是 JSON"))
    }
}