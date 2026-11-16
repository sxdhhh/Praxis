package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 登录方式（文档 §9.9）。 */
class GatewayLoginMethodsTest {

    @Test
    fun `只有 API Key 的家不给网页登录`() {
        // 这就是那条「扯」的修复：api.openai.com 没有网页登录这回事，
        // 旧实现只要有地址就挂一条「打开登录页」，点开是官网首页。
        listOf("https://api.openai.com", "https://api.deepseek.com", "https://api.z.ai/api/anthropic")
            .forEach { url ->
                val methods = GatewayLoginMethods.forProvider("某 API 家", url)
                assertEquals("不该有第二种入口：" + url, 1, methods.size)
                assertEquals(GatewayLoginType.Api, methods.first().type)
            }
    }

    @Test
    fun `确认有网页登录的家才给两个入口`() {
        val methods = GatewayLoginMethods.forProvider(
            "Loomy",
            "https://loomy.example.com",
            ProviderLoginKind.WebLogin,
        )
        assertEquals(2, methods.size)
        assertEquals(GatewayLoginType.Api, methods.first().type)
        assertEquals(GatewayLoginType.OAuth, methods[1].type)
    }

    @Test
    fun `网页登录的家在没有地址时也只给粘贴入口`() {
        // 没有地址的按钮会打开空白页 —— 那就是假入口。
        listOf(null, "   ", "不是地址").forEach { url ->
            val methods = GatewayLoginMethods.forProvider("某家", url, ProviderLoginKind.WebLogin)
            assertEquals("url=" + url, 1, methods.size)
            assertEquals(GatewayLoginType.Api, methods.first().type)
        }
    }

    @Test
    fun `每种登录方式的文案跟着凭据来源变`() {
        // 文案指错地方，用户就会去错的地方找 Key。
        fun label(kind: ProviderLoginKind) =
            GatewayLoginMethods.forProvider("某家", null, kind).first().label
        assertTrue(label(ProviderLoginKind.ApiKey).contains("API Key"))
        assertTrue(label(ProviderLoginKind.TokenImport).contains("令牌"))
        assertTrue(label(ProviderLoginKind.CookieImport).contains("Cookie"))
        assertTrue(label(ProviderLoginKind.DeviceCode).contains("设备授权"))
        // Unknown 按最保守的一条走：跟 ApiKey 同一句话。
        assertEquals(label(ProviderLoginKind.ApiKey), label(ProviderLoginKind.Unknown))
    }

    @Test
    fun `登录页方式要带上确认过的地址`() {
        val methods = GatewayLoginMethods.forProvider(
            "Kiro",
            "https://kiro.example.com",
            ProviderLoginKind.WebLogin,
        )
        val oauth = methods.single { it.type == GatewayLoginType.OAuth }
        assertEquals("https://kiro.example.com", oauth.landingUrl)
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
