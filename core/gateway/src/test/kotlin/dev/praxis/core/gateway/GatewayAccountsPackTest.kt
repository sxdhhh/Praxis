package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关整包形状：**读得进来、写得回去**。
 *
 * 这组用例的存在理由是一次真实的失败：Praxis 之前导出的是「一账号一文件」，
 * 而网关导出（也是它导入）的是整包，于是「导出 → 回灌网关」这条路从来不通。
 *
 * 注意：用例里的凭证全是编的，不要换成真 token。
 */
class GatewayAccountsPackTest {

    /** 与真实导出的键逐字一致的最小样例（凭证为假值）。 */
    private val realShape = """
        {
          "version": 1,
          "exportedAt": 1794000000000,
          "accounts": [
            {
              "id": "custom-acct-aaaa1111",
              "provider": "custom-1f2e3d",
              "name": "Gemini",
              "baseUrl": "http://127.0.0.1:3001/v1",
              "apiKey": "sk-fake",
              "refreshToken": "",
              "enabled": true,
              "priority": 67,
              "addedAt": 1793900000000
            },
            {
              "id": "user-uuid-bbbb2222",
              "provider": "workbuddy",
              "name": "某个账号",
              "accessToken": "at-fake",
              "refreshToken": "rt-fake",
              "enabled": true,
              "edition": "cn",
              "expiresAt": 1796307435812
            }
          ],
          "customProviders": [
            {
              "id": "custom-1f2e3d",
              "name": "Gemini",
              "baseUrl": "http://127.0.0.1:3001/v1",
              "models": [
                { "id": "gemini-2.5-pro", "enabled": true },
                { "id": "gemini-2.5-flash", "enabled": false }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `读整包并区分 API Key 与 OAuth 两种凭证`() {
        val pack = GatewayAccountsPackCodec.decode(realShape).getOrThrow()
        assertEquals(1, pack.version)
        assertEquals(1794000000000L, pack.exportedAt)
        assertEquals(2, pack.accounts.size)

        val apiKeyAccount = pack.accounts[0]
        assertTrue(apiKeyAccount.hasCredential)
        assertFalse("有 apiKey 就不是 OAuth", apiKeyAccount.isOauth)
        assertEquals("http://127.0.0.1:3001/v1", apiKeyAccount.resolvedBaseUrl)

        val oauthAccount = pack.accounts[1]
        assertTrue(oauthAccount.hasCredential)
        assertTrue(oauthAccount.isOauth)
        assertEquals("cn", oauthAccount.edition)
    }

    @Test
    fun `没有账号的整包算解析失败而不是空成功`() {
        // 「成功导入了 0 个」是最容易让人以为自己在做别的事的结果。
        val empty = """{"version":1,"exportedAt":1,"accounts":[]}"""
        assertTrue(GatewayAccountsPackCodec.decode(empty).isFailure)
        assertTrue(GatewayAccountsPackCodec.decode("不是 JSON").isFailure)
    }

    @Test
    fun `关掉的模型标志位被保留`() {
        val pack = GatewayAccountsPackCodec.decode(realShape).getOrThrow()
        val provider = pack.customProviders.single()
        assertEquals(
            listOf("gemini-2.5-pro", "gemini-2.5-flash"),
            provider.models.map { it.id },
        )
        assertFalse(provider.models[1].enabled)
    }

    @Test
    fun `写回去仍是网关认的形状`() {
        val pack = GatewayAccountsPackCodec.decode(realShape).getOrThrow()
        val text = GatewayAccountsPackCodec.encode(pack)
        assertTrue(text.contains("accounts"))
        assertTrue(text.contains("customProviders"))
        assertTrue(text.contains("exportedAt"))
        val again = GatewayAccountsPackCodec.decode(text).getOrThrow()
        assertEquals(2, again.accounts.size)
        assertEquals("sk-fake", again.accounts[0].apiKey)
        assertEquals("rt-fake", again.accounts[1].refreshToken)
    }

    @Test
    fun `仅配置粒度导出时清空凭证而不是写占位符`() {
        // 写 *** 的话，网关会把它当真 Key 存进去，然后每条都 401 —— 比不带更糟。
        val accounts = listOf(
            GatewayAccountFile(
                format = ACCOUNT_FORMAT,
                id = "a1",
                provider = "custom-1f2e3d",
                name = "Gemini",
                apiKey = "sk-real-looking",
                baseUrl = "http://127.0.0.1:3001/v1",
            ),
        )
        val file = GatewayExportPlanner.packFile(accounts = accounts, includeSecrets = false)
        assertEquals("agent2api-accounts.json", file.relativePath)
        assertFalse("不能出现真凭证", file.content.contains("sk-real-looking"))
        assertFalse("也不能出现占位符", file.content.contains(GatewayExporter.REDACTED))
        val pack = GatewayAccountsPackCodec.decode(file.content).getOrThrow()
        assertNull(pack.accounts.single().apiKey)
    }

    @Test
    fun `含凭证粒度保留凭证`() {
        val accounts = listOf(
            GatewayAccountFile(
                format = ACCOUNT_FORMAT,
                id = "a1",
                provider = "custom-1f2e3d",
                apiKey = "sk-real-looking",
            ),
        )
        val file = GatewayExportPlanner.packFile(accounts = accounts, includeSecrets = true)
        val pack = GatewayAccountsPackCodec.decode(file.content).getOrThrow()
        assertEquals("sk-real-looking", pack.accounts.single().apiKey)
    }

    @Test
    fun `导出计划里整包是可选项`() {
        val accounts = listOf(
            GatewayAccountFile(
                format = ACCOUNT_FORMAT,
                id = "a1",
                provider = "openai",
                apiKey = "k",
            ),
        )
        val without = GatewayExportPlanner.build(accounts = accounts, date = "20261116")
        assertTrue(
            "默认不产出整包，老用例的文件清单保持不变",
            without.files.none { it.relativePath == GatewayExportPlanner.PACK_FILE_NAME },
        )
        val with = GatewayExportPlanner.build(
            accounts = accounts,
            date = "20261116",
            emitGatewayPack = true,
            exportedAt = 1794000000000L,
        )
        assertTrue(with.files.any { it.relativePath == GatewayExportPlanner.PACK_FILE_NAME })
    }
}