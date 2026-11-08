package dev.praxis.core.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 账号实体到选路视图的映射：存储形态 -> 判断形态。 */
class GatewayAccountMapperTest {

    private fun entity(
        id: String = "acct-1",
        providerId: String = "openai",
        enabled: Boolean = true,
        priority: Int = 3,
        maxConcurrent: Int = 2,
        baseUrlOverride: String? = null,
    ) = GatewayAccountEntity(
        id = id,
        providerId = providerId,
        name = "工作号",
        baseUrlOverride = baseUrlOverride,
        enabled = enabled,
        priority = priority,
        maxConcurrent = maxConcurrent,
        credentialsCipher = "enc",
        expiringAt = null,
        createdAt = 0L,
    )

    @Test
    fun 字段逐一搬到选路视图() {
        val view = entity(baseUrlOverride = "https://x/v1").toAccountView(hasCredentials = true)

        assertEquals("acct-1", view.id)
        assertEquals("openai", view.providerId)
        assertEquals(true, view.enabled)
        assertEquals(3, view.priority)
        assertEquals(2, view.maxConcurrent)
        assertEquals("https://x/v1", view.baseUrlOverride)
        assertEquals(true, view.hasCredentials)
    }

    @Test
    fun 能力位缺键保持_null_而不是默认_false() {
        // 这一条很关键：若映射成 false 而不是 null，文档 7.2 的「缺键放行」
        // 就会退化成「一律不支持图片」，带图请求全部无号可用。
        assertNull(entity().toAccountView(hasCredentials = true).supportsImages)
    }

    @Test
    fun 运行期计数可以被传入() {
        val view = entity().toAccountView(
            hasCredentials = true,
            runningConcurrent = 1,
            requestsThisMinute = 7,
            requestLimitPerMinute = 10,
        )

        assertEquals(1, view.runningConcurrent)
        assertEquals(7, view.requestsThisMinute)
        assertEquals(10, view.requestLimitPerMinute)
    }
}
