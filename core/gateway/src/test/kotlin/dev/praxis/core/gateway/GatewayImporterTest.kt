package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关账号导入的报告规则（文档 §8.2 / §8.6）。
 *
 * ## 为什么值得单测
 *
 * 导入最容易出的问题不是「报错」，而是**静默少导了几个**：用户导了 8 个账号，
 * 列表里只多了 5 个，而屏幕上没有任何解释。四段报告就是为这件事存在的 ——
 * 所以每条规则都要有用例钉住「它是进 skipped 还是 failed，理由是什么」。
 */
class GatewayImporterTest {

    private val known = setOf("openai", "anthropic")

    /** 按网关真实字段名拼一个账号文件。缺省值都合法，用例只改自己关心的那个。 */
    private fun file(
        id: String = "acct-1",
        provider: String = "openai",
        name: String = "我的中转",
        apiKey: String? = "sk-xxxx",
        baseUrl: String? = "https://api.example.com/v1",
        accessToken: String? = null,
        format: String = ACCOUNT_FORMAT,
    ): String {
        val lines = mutableListOf(
            "{",
            "  \"format\": \"" + format + "\",",
            "  \"id\": \"" + id + "\",",
            "  \"provider\": \"" + provider + "\",",
            "  \"name\": \"" + name + "\"",
        )
        if (apiKey != null) lines += "  ,\"apiKey\": \"" + apiKey + "\""
        if (baseUrl != null) lines += "  ,\"baseUrl\": \"" + baseUrl + "\""
        if (accessToken != null) lines += "  ,\"accessToken\": \"" + accessToken + "\""
        lines += "}"
        return lines.joinToString("\n")
    }

    @Test
    fun 正常账号计入新增() {
        val report = GatewayImporter.report(
            files = mapOf("a.json" to file()),
            existingIds = emptySet(),
            knownProviders = known,
        )

        assertEquals(1, report.added.size)
        assertEquals(0, report.updated.size + report.skipped.size + report.failed.size)
    }

    @Test
    fun 同_id_已存在计入更新() {
        val report = GatewayImporter.report(
            files = mapOf("a.json" to file(id = "acct-1")),
            existingIds = setOf("acct-1"),
            knownProviders = known,
        )

        assertEquals(listOf("acct-1"), report.updated.map { it.id })
        assertTrue(report.added.isEmpty())
    }

    @Test
    fun OAuth_账号被跳过并说明原因() {
        val report = GatewayImporter.report(
            files = mapOf("oauth.json" to file(apiKey = null, accessToken = "at-1")),
            existingIds = emptySet(),
            knownProviders = known,
        )

        assertEquals(1, report.skipped.size)
        assertTrue(report.skipped[0].reason.orEmpty().contains("API Key"))
    }

    @Test
    fun 未知提供商被跳过() {
        val report = GatewayImporter.report(
            files = mapOf("x.json" to file(provider = "gemini")),
            existingIds = emptySet(),
            knownProviders = known,
        )

        assertEquals(1, report.skipped.size)
        assertTrue(report.skipped[0].reason.orEmpty().contains("gemini"))
    }

    @Test
    fun custom_开头的家一律放行() {
        val report = GatewayImporter.report(
            files = mapOf("c.json" to file(provider = "custom-3f2a91")),
            existingIds = emptySet(),
            knownProviders = known,
        )

        assertEquals(1, report.added.size)
    }

    @Test
    fun 缺_format_记为失败并带文件名() {
        val report = GatewayImporter.report(
            files = mapOf("broken.json" to file(format = "")),
            existingIds = emptySet(),
            knownProviders = known,
        )

        assertEquals(1, report.failed.size)
        assertEquals("broken.json", report.failed[0].id)
        assertTrue(report.failed[0].reason.orEmpty().isNotEmpty())
    }

    @Test
    fun 同提供商同地址提示可能重复() {
        val report = GatewayImporter.report(
            files = mapOf(
                "a.json" to file(id = "a1"),
                "b.json" to file(id = "a2"),
            ),
            existingIds = emptySet(),
            knownProviders = known,
        )

        assertEquals(2, report.added.size)
        assertEquals(1, report.added.count { it.reason != null })
    }

    // ── 导入计划（P1 落库用，2026-11-08）──

    @Test
    fun 计划里的账号与报告的段一致() {
        val plan = GatewayImporter.prepare(
            files = mapOf(
                "a.json" to file(id = "a1"),
                "b.json" to file(id = "a2"),
            ),
            existingIds = setOf("a2"),
            knownProviders = known,
        )

        assertEquals(1, plan.report.added.size)
        assertEquals(1, plan.report.updated.size)
        // 新增与更新都该落库，所以都在 accepted 里。
        assertEquals(listOf("a1", "a2"), plan.accepted.map { it.id }.sorted())
    }

    @Test
    fun 被跳过的账号不在计划里() {
        val plan = GatewayImporter.prepare(
            files = mapOf(
                "ok.json" to file(id = "a1"),
                "oauth.json" to file(id = "a2", apiKey = null, accessToken = "at-1"),
            ),
            existingIds = emptySet(),
            knownProviders = known,
        )

        assertEquals(listOf("a1"), plan.accepted.map { it.id })
        assertEquals(1, plan.report.skipped.size)
    }

    @Test
    fun 自定义提供商按_id_去重() {
        val withProvider = """
            {
              "format": "agent2api-account",
              "id": "a1",
              "provider": "custom-abc",
              "name": "一号",
              "apiKey": "k",
              "customProviders": [ { "id": "custom-abc", "name": "我的中转" } ]
            }
        """.trimIndent()
        val sameProvider = withProvider.replaceFirst("a1", "a2")

        val plan = GatewayImporter.prepare(
            files = mapOf("1.json" to withProvider, "2.json" to sameProvider),
            existingIds = emptySet(),
            knownProviders = known,
        )

        assertEquals("同一个自定义家只该登记一次", 1, plan.providers.size)
        assertEquals(2, plan.accepted.size)
    }

    @Test
    fun report_与_prepare_用的是同一份规则() {
        val files = mapOf("a.json" to file(id = "a1"))

        val direct = GatewayImporter.report(files, emptySet(), known)
        val viaPlan = GatewayImporter.prepare(files, emptySet(), known).report

        assertEquals("两处规则一旦分家，报告与落库就会对不上", direct, viaPlan)
    }
}
