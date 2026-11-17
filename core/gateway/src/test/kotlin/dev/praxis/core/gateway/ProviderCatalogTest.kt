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
        // 1 = agent2api（经网关入口，API preset 已删）；
        // 33 = agent2api-providers 插件包声明 34 个里去掉 `demo`（示例，hidden 且默认停用）；
        // 4  = agent2api-web-providers 网页插件包。
        assertEquals(1, counts[ProviderKind.Api])
        assertEquals(33, counts[ProviderKind.AgentPlugin])
        assertEquals(4, counts[ProviderKind.WebPlugin])
        assertEquals(38, ProviderCatalog.all().size)
    }

    @Test
    fun `经网关入口在目录里且只要 Key`() {
        // 「插件家在 Praxis 可用」的现实路径：一台 Agent2API 当成一家。
        // 它不是从上游 preset 抄来的，是我们自己的接入形态 —— 但归类必须是 Api 直连 + ApiKey，
        // 否则它会被当成插件家跳过，或被挂上假的网页登录。
        val entry = ProviderCatalog.of("agent2api")!!
        assertEquals(ProviderKind.Api, entry.kind)
        assertEquals(ProviderLoginKind.ApiKey, entry.login)
        assertEquals("Agent2API 网关", ProviderCatalog.labelOf("agent2api"))
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
        assertEquals("Agent2API 网关", ProviderCatalog.of("AGENT2API")?.label)
    }

    @Test
    fun `有证据的登录方式被记住`() {
        // 全部来自取证矩阵（lookme/index/read/07-provider-login-matrix.md，
        // 基准 ProvidersPlugin/Agent2Providers main@3055f41）：协议申报 + login_start 实现双证据。
        assertEquals(ProviderLoginKind.ApiKey, ProviderCatalog.loginOf("commandcode"))
        assertEquals(ProviderLoginKind.ApiKey, ProviderCatalog.loginOf("modelscope"))
        assertEquals(ProviderLoginKind.DeviceCode, ProviderCatalog.loginOf("qwenwork"))
        assertEquals(ProviderLoginKind.DeviceCode, ProviderCatalog.loginOf("cline-free"))
        assertEquals(ProviderLoginKind.CookieImport, ProviderCatalog.loginOf("doubaowork"))
        assertEquals(ProviderLoginKind.TokenImport, ProviderCatalog.loginOf("raccoon"))
        assertEquals(ProviderLoginKind.CookieImport, ProviderCatalog.loginOf("deepseek-web"))
        assertEquals(ProviderLoginKind.WebLogin, ProviderCatalog.loginOf("kiro"))
        assertEquals(ProviderLoginKind.WebLogin, ProviderCatalog.loginOf("zcode"))
        assertEquals(ProviderLoginKind.WebLogin, ProviderCatalog.loginOf("antigravity"))
    }

    @Test
    fun `短信登录与网页登录分开`() {
        // loomy 与 autoclaw 国内版走的是上游短信接口，不是网页；
        // 混成「网页登录」会让界面打开一个没有意义的页面。
        assertEquals(ProviderLoginKind.SmsCode, ProviderCatalog.loginOf("loomy"))
        assertEquals(ProviderLoginKind.SmsCode, ProviderCatalog.loginOf("autoclaw"))
        assertEquals("手机号验证码", ProviderCatalog.loginLabel("autoclaw"))
        // 而国际版确实走网页（两跳 OAuth）。
        assertEquals(ProviderLoginKind.WebLogin, ProviderCatalog.loginOf("autoclaw-intl"))
    }

    @Test
    fun `只剩未公开源码的网页家有未知`() {
        // 有源码的 34 家已全部取证；只剩网页插件包那三家（源码不在公开仓库）保持 Unknown。
        val unknown = ProviderCatalog.all()
            .filter { it.login == ProviderLoginKind.Unknown }
            .map { it.id }
            .sorted()
        assertEquals(listOf("chatglm", "tabbit", "tabbit-intl"), unknown)
    }

    @Test
    fun `登录方式的人话只有一处`() {
        assertEquals("直填 API Key", ProviderCatalog.loginLabel("agent2api"))
        assertEquals("网页登录", ProviderCatalog.loginLabel("kiro"))
        assertEquals("官方设备授权", ProviderCatalog.loginLabel("qwenwork"))
        assertEquals("网页 Cookie / userToken", ProviderCatalog.loginLabel("deepseek-web"))
        // 取证之后 qoder 不再是未知：它是 PKCE 设备授权。
        assertEquals("官方设备授权", ProviderCatalog.loginLabel("qoder"))
        // 真正未知的只剩源码未公开的网页家。
        assertEquals("上游未声明登录方式", ProviderCatalog.loginLabel("tabbit"))
    }

    @Test
    fun `未知 id 按最保守的方式处理`() {
        // 自定义家不在目录里：不能因此不给入口，也不能编一个网页登录。
        assertNull(ProviderCatalog.of("my-own-home"))
        assertEquals(ProviderLoginKind.ApiKey, ProviderCatalog.loginOf("my-own-home"))
        assertEquals("my-own-home", ProviderCatalog.labelOf("my-own-home"))
        assertNull(ProviderCatalog.kindOf("unknown-home"))
    }
}
