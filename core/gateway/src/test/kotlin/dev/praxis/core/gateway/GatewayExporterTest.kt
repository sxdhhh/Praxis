package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出的两条硬要求（文档 8.5）。
 *
 * ## 为什么往返测试比逐字段断言更有用
 *
 * 「双向可逆」的失败方式通常是少了一个字段、或多了一个空字段：导出后再导入，
 * 某个字段悄悄变回默认值。逐字段断言只能覆盖我想到的字段；往返一次则把我
 * 没想到的也一起验了 —— 只要 parse(export(x)) != x 就红。
 */
class GatewayExporterTest {

    /** 文档 8.2 的样例形状（去掉注释）。 */
    private val sampleJson = """
        {
          "format": "agent2api-account",
          "id": "custom-acct-28f993d4dbb3",
          "provider": "custom-3f2a91b04c7e",
          "name": "我的中转-1",
          "apiKey": "sk-xxxx",
          "baseUrl": "https://api.example.com/v1",
          "customProviders": [
            { "id": "custom-3f2a91b04c7e", "name": "我的中转", "baseUrl": "https://api.example.com/v1" }
          ]
        }
    """.trimIndent()

    /** 带 OAuth 令牌：验证「仅配置」粒度会把令牌清空。 */
    private val oauthJson = """
        {
          "format": "agent2api-account",
          "id": "oauth-acct-1",
          "provider": "openai",
          "name": "OAuth 号",
          "apiKey": "sk-xxxx",
          "accessToken": "at-1"
        }
    """.trimIndent()

    /** 带桌面端专属键：验证它们不会被回灌。 */
    private val noisyJson = """
        {
          "format": "agent2api-account",
          "id": "acct-2",
          "provider": "openai",
          "name": "带桌面字段",
          "apiKey": "sk-yyyy",
          "desktop": { "secret": "x" },
          "pluginSecret": "y"
        }
    """.trimIndent()

    @Test
    fun 含凭证导出后能原样读回() {
        val original = GatewayImporter.parse(sampleJson).getOrThrow()

        val exported = GatewayExporter.accountFile(original, includeSecrets = true)
        val roundTripped = GatewayImporter.parse(exported).getOrThrow()

        assertEquals("往返后字段必须完全一致", original, roundTripped)
    }

    @Test
    fun 仅配置粒度会打码且清空令牌() {
        val original = GatewayImporter.parse(oauthJson).getOrThrow()

        val exported = GatewayExporter.accountFile(original, includeSecrets = false)
        val roundTripped = GatewayImporter.parse(exported).getOrThrow()

        assertEquals(GatewayExporter.REDACTED, roundTripped.apiKey)
        assertNull("仅配置粒度不该带 OAuth 令牌", roundTripped.accessToken)
        assertNull(roundTripped.refreshToken)
        // 打码不该顺手把别的信息也丢了。
        assertEquals("oauth-acct-1", roundTripped.id)
    }

    @Test
    fun 网关的无关键不会被导出() {
        val exported = GatewayExporter.accountFile(
            GatewayImporter.parse(noisyJson).getOrThrow(),
            includeSecrets = true,
        )

        assertFalse("pluginSecret 属于桌面端，不该回灌", exported.contains("pluginSecret"))
        assertFalse("desktop 属于桌面端，不该回灌", exported.contains("desktop"))
    }

    @Test
    fun 含凭证的文件名强制带_secrets() {
        assertTrue(
            GatewayExporter.accountFileName("openai-工作号-20261107-001", includeSecrets = true)
                .endsWith("-secrets.json"),
        )
        assertFalse(
            GatewayExporter.accountFileName("openai-工作号-20261107-001", includeSecrets = false)
                .contains("secrets"),
        )
    }
}
