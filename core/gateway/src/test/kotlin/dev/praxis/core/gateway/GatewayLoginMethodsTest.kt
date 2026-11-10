package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 登录方式（文档 §9.9）。 */
class GatewayLoginMethodsTest {

    @Test
    fun `直填 Key 永远在第一位`() {
        // 它是唯一一定成功的方式：不依赖页面能否打开。
        val methods = GatewayLoginMethods.forProvider("OpenAI", "https://api.openai.com")
        assertEquals(GatewayLoginType.Api, methods.first().type)
        assertEquals(2, methods.size)
    }

    @Test
    fun `没有地址时不提供打开登录页`() {
        // 没地址的按钮会打开空白页 —— 那就是假入口。
        assertEquals(1, GatewayLoginMethods.forProvider("自定义家", null).size)
        assertEquals(1, GatewayLoginMethods.forProvider("自定义家", "   ").size)
        assertEquals(1, GatewayLoginMethods.forProvider("自定义家", "不是地址").size)
    }

    @Test
    fun `有 http 地址时给出登录页方式并带上地址`() {
        val methods = GatewayLoginMethods.forProvider("Anthropic", "https://api.anthropic.com")
        val oauth = methods.single { it.type == GatewayLoginType.OAuth }
        assertEquals("https://api.anthropic.com", oauth.landingUrl)
        assertTrue(oauth.instructions!!.contains("不代管"))
    }

    @Test
    fun `从回调地址里取 api_key`() {
        assertEquals(
            "sk-abc",
            GatewayLoginMethods.extractCredential("https://x.com/cb?api_key=sk-abc&state=1"),
        )
    }

    @Test
    fun `从回调地址里取 code`() {
        assertEquals("xyz", GatewayLoginMethods.extractCredential("myapp://cb?code=xyz"))
        assertEquals(
            "tok-9",
            GatewayLoginMethods.extractCredential("https://a/b#access_token=tok-9&x=1"),
        )
    }

    @Test
    fun `直接粘贴 Key 时原样返回`() {
        assertEquals("sk-pasted", GatewayLoginMethods.extractCredential("  sk-pasted  "))
    }

    @Test
    fun `空输入返回空串`() {
        assertEquals("", GatewayLoginMethods.extractCredential(""))
        assertEquals("", GatewayLoginMethods.extractCredential("   "))
    }

    @Test
    fun `参数值只取到下一个 &`() {
        // 取多了会把 state 一起塞进 Key，而那个错误在请求时才暴露。
        assertEquals("k", GatewayLoginMethods.extractCredential("https://x?code=k&state=zzz"))
    }
}
