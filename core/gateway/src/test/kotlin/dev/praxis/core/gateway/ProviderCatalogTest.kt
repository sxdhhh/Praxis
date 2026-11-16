package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提供商目录：**哪家能直接连、哪家必须经网关、哪家怎么登录**。
 *
 * 这组用例钉的不是数字，而是三条容易悄悄退化的性质：
 * 1. 「直接连」的家必须只要 API Key（否则会重新长出假网页登录）；
 * 2. 插件家必须一个都不能直接连（否则界面会出现连不上的入口）；
 * 3. id 唯一 —— 重复 id 会让选路把两家搞混。
 */
class ProviderCatalogTest {

    @Test
    fun `三类家的数量与上游一致`() {
        val counts = ProviderCatalog.counts()
        // 24 = 上游 preset 目录（preset-providers.js）；
        // 33 = agent2api-providers 插件包声明 34 个里去掉 `demo`（示例，hidden 且默认停用）；
        // 4  = agent2api-web-providers 网页插件包。
        assertEquals(24, counts[ProviderKind.Api])
        assertEquals(33, counts[ProviderKind.AgentPlugin])
        assertEquals(4, counts[ProviderKind.WebPlugin])
        assertEquals(61, ProviderCatalog.all().size)
    }

    @Test
    fun `示例提供商不进目录`() {
        // demo 是插件包的示例条目（hidden、默认停用），把它列出来只会让用户点到一个连不上的假家。
        assertNull(ProviderCatalog.of("demo"))
    }

    @Test
    fun `直接连的家只要 API Key`() {
        ProviderCatalog.direct().forEach { entry ->
            assertEquals("不该有别的登录方式：" + entry.id, ProviderLoginKind.ApiKey, entry.login)
            assertTrue(entry.direct)
        }
    }

    @Test
    fun `插件家都不直接连`() {
        ProviderCatalog.viaGateway().forEach { entry ->
            assertFalse("插件家必须经网关：" + entry.id, entry.direct)
        }
    }

    @Test
    fun `id 唯一且大小写不敏感地可查`() {
        val ids = ProviderCatalog.all().map { it.id.lowercase() }
        assertEquals("id 有重复", ids.size, ids.toSet().size)
        assertEquals("OpenAI", ProviderCatalog.of("OPENAI")?.label)
    }

    @Test
    fun `有证据的登录方式被记住`() {
        // 这四条都有上游出处（插件文档 / 插件仓库 README），改错会让用户去错的地方找凭据。
        assertEquals(ProviderLoginKind.ApiKey, ProviderCatalog.loginOf("commandcode"))
        assertEquals(ProviderLoginKind.DeviceCode, ProviderCatalog.loginOf("qwenwork"))
        assertEquals(ProviderLoginKind.CookieImport, ProviderCatalog.loginOf("doubaowork"))
        assertEquals(ProviderLoginKind.TokenImport, ProviderCatalog.loginOf("raccoon"))
        assertEquals(ProviderLoginKind.CookieImport, ProviderCatalog.loginOf("deepseek-web"))
    }

    @Test
    fun `登录方式的人话只有一处`() {
        assertEquals("直填 API Key", ProviderCatalog.loginLabel("openai"))
        assertEquals("网页登录", ProviderCatalog.loginLabel("kiro"))
        assertEquals("官方设备授权", ProviderCatalog.loginLabel("qwenwork"))
        assertEquals("网页 Cookie / userToken", ProviderCatalog.loginLabel("deepseek-web"))
        assertEquals("上游未声明登录方式", ProviderCatalog.loginLabel("qoder"))
    }

    @Test
    fun `未知 id 按最保守的方式处理`() {
        // 自定义家不在目录里：不能因此不给入口，也不能编一个网页登录。
        assertNull(ProviderCatalog.of("my-own-home"))
        assertEquals(ProviderLoginKind.ApiKey, ProviderCatalog.loginOf("my-own-home"))
        assertEquals("my-own-home", ProviderCatalog.labelOf("my-own-home"))
        assertEquals(ProviderKind.Api, ProviderCatalog.kindOf("openai"))
    }
}
