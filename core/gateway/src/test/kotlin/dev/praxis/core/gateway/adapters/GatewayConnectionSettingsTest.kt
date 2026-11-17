package dev.praxis.core.gateway.adapters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网关连接设置：编解码 + 地址校验。 */
class GatewayConnectionSettingsTest {

    @Test
    fun `往返不丢字段`() {
        val settings = GatewayConnectionSettings(
            baseUrl = "http://192.168.1.80:3065",
            apiKey = "sk-gw",
            adminUser = "admin",
            adminPass = "p@ss",
        )
        val decoded = GatewayConnectionCodec.decode(GatewayConnectionCodec.encode(settings))!!.getOrThrow()
        assertEquals(settings, decoded)
    }

    @Test
    fun `没配过返回 null 而不是空对象`() {
        assertNull(GatewayConnectionCodec.decode(null))
        assertNull(GatewayConnectionCodec.decode("  "))
    }

    @Test
    fun `坏内容返回失败`() {
        assertTrue(GatewayConnectionCodec.decode("不是 JSON")!!.isFailure)
    }

    @Test
    fun `只填地址也能用`() {
        val settings = GatewayConnectionSettings(baseUrl = "http://192.168.1.80:3065")
        assertTrue(settings.isConfigured)
        assertFalse("没管理员凭据就不能用管理 API", settings.hasAdminCredentials)
    }

    @Test
    fun `地址校验拦住本机回环`() {
        // 真机上最常见的错误：127.0.0.1 指手机自己，永远连不上 PC。
        assertTrue(GatewayAddressRules.check("http://127.0.0.1:3065")!!.contains("手机自己"))
        assertTrue(GatewayAddressRules.check("http://localhost:3065")!!.contains("手机自己"))
    }

    @Test
    fun `地址校验要求协议头`() {
        assertTrue(GatewayAddressRules.check("192.168.1.80:3065")!!.contains("http://"))
        assertTrue(GatewayAddressRules.check("")!!.contains("先填"))
    }

    @Test
    fun `合法地址通过校验且规范化去掉尾斜杠`() {
        assertNull(GatewayAddressRules.check("http://192.168.1.80:3065"))
        assertEquals("http://192.168.1.80:3065", GatewayAddressRules.normalize("http://192.168.1.80:3065/"))
    }
}