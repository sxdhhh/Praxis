package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量导出形状的导入（用户实测的那份：顶层 accounts 数组 + accessToken 型凭证）。
 *
 * 样本照抄真实导出的字段名（endpoint / provider / accessToken / refreshToken / nickname），
 * 只把令牌换成占位串。
 */
class GatewayImporterBulkTest {

    private val bulk = """
{
  "accounts": [
    {
      "id": "user-46c7a13a-95f1-4596-9b2b-7c4b40acafe4",
      "provider": "workbuddy",
      "name": "小宇.",
      "nickname": "小宇.",
      "accessToken": "TOKEN-A",
      "refreshToken": "REFRESH-A",
      "endpoint": "https://copilot.tencent.com",
      "domain": "www.codebuddy.cn",
      "enabled": true,
      "priority": 30
    },
    {
      "id": "user-d6d9e695-ef88-4656-8403-ad240fe52c6f",
      "provider": "workbuddy-intl",
      "name": "hujiayucc",
      "accessToken": "TOKEN-B",
      "endpoint": "https://www.workbuddy.ai",
      "enabled": true,
      "priority": 10
    },
    {
      "id": "user-key-1",
      "provider": "openai",
      "name": "我的 OpenAI",
      "apiKey": "sk-test",
      "endpoint": "https://api.openai.com/v1"
    }
  ]
}
"""

    @Test
    fun `批量导出能解析出全部账号`() {
        val accounts = GatewayImporter.parseAll(bulk).getOrThrow().accounts
        assertEquals(3, accounts.size)
        assertEquals("workbuddy", accounts[0].provider)
        // 名字优先 name，空则用 nickname。
        assertEquals("小宇.", accounts[0].name)
        // 真实导出里地址叫 endpoint。
        assertEquals("https://copilot.tencent.com", accounts[0].baseUrl)
        assertEquals("TOKEN-A", accounts[0].accessToken)
        assertEquals(ACCOUNT_FORMAT, accounts[0].format)
    }

    @Test
    fun `批量导出里 OAuth 型被跳过 且两个原因都说出来`() {
        // 这两个账号同时命中「OAuth 型」与「不认识这家」——一次说清两条，
        // 否则用户补完一条还会再撞上第二条。
        val plan = GatewayImporter.prepare(
            files = mapOf("agent2api-accounts.json" to bulk),
            existingIds = emptySet(),
            knownProviders = setOf("openai"),
        )
        assertEquals("应当只接受那条 apiKey 型", 1, plan.accepted.size)
        assertEquals("openai", plan.accepted[0].provider)
        assertEquals(2, plan.report.skipped.size)
        val first = plan.report.skipped[0].reason.orEmpty()
        assertTrue("应当提到 OAuth：" + first, first.contains("OAuth"))
        assertTrue("应当提到不认识这家：" + first, first.contains("workbuddy"))
    }

    @Test
    fun `单账号文件仍然能解析`() {
        val single = """{"format":"agent2api-account","id":"a1","provider":"openai","name":"n","apiKey":"sk"}"""
        val accounts = GatewayImporter.parseAll(single).getOrThrow().accounts
        assertEquals(1, accounts.size)
        assertEquals("a1", accounts[0].id)
    }

    @Test
    fun `既不是单文件也不是批量时报出可读原因`() {
        val result = GatewayImporter.parseAll("{\"foo\":1}")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("要能直接展示给用户：" + message, message.contains("format"))
    }

    @Test
    fun `批量里有一条缺 id 时整份失败并指出第几条`() {
        val bad = """{"accounts":[{"id":"ok-1","provider":"openai","apiKey":"sk"},{"provider":"openai"}]}"""
        val result = GatewayImporter.parseAll(bad)
        assertTrue(result.isFailure)
        assertTrue(
            "要说清第几条：" + result.exceptionOrNull()?.message,
            result.exceptionOrNull()?.message.orEmpty().contains("第 2 条"),
        )
    }

    @Test
    fun `批量导出里的自定义家会进计划（含启停过滤）`() {
        val withProviders = """
{
  "accounts": [
    {"id": "user-key-1", "provider": "custom-e1a0f12f950e", "name": "Gemini", "apiKey": "sk-1"}
  ],
  "customProviders": [
    {"id": "custom-e1a0f12f950e", "name": "Gemini", "baseUrl": "http://1.2.3.4:3001/v1",
     "models": [
       {"id": "gemini-3.8-flash", "enabled": true, "reasoning": ""},
       {"id": "gemini-3.8-flash-tts", "enabled": true, "reasoning": ""},
       {"id": "retired-model", "enabled": false, "reasoning": ""}
     ]}
  ]
}
"""
        val plan = GatewayImporter.prepare(
            files = mapOf("agent2api-accounts.json" to withProviders),
            existingIds = emptySet(),
            knownProviders = emptySet(),
        )
        assertEquals("custom-* 一律放行", 1, plan.accepted.size)
        assertEquals(1, plan.providers.size)
        val provider = plan.providers[0]
        assertEquals("http://1.2.3.4:3001/v1", provider.baseUrl)
        assertEquals(2, provider.models.size)
        assertTrue("关掉的模型不该导入", !provider.models.contains("retired-model"))
    }
}
