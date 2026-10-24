package dev.praxis.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ModelCatalog` 的回归测试。
 *
 * ## 为什么这两个函数最需要测试
 *
 * 「拉取模型」这条链上有三段：算 URL → 发请求 → 解析。发请求要网络，
 * 而**算 URL 与解析**都是纯函数，且它们正是最容易写错的两处：
 *
 * - URL 错 → 永远 404，但人看到的只是「拉取失败」，不知道该改哪；
 * - 解析错 → 列表是空的或全是 `models/xxx`（把 Gemini 的完整名当 id 用，
 *   发进请求体必然 400）。
 *
 * 这两处写错在真机上排查成本极高（要连服务商、看日志、对协议），
 * 所以在这里用固定样例锁死。
 */
class ModelCatalogTest {

    // ── URL 派生 ─────────────────────────────────────────────────────

    @Test
    fun `裸域名补 v1`() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            ModelCatalog.modelsUrl("https://api.deepseek.com"),
        )
    }

    @Test
    fun `带版本段只补 models`() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            ModelCatalog.modelsUrl("https://api.deepseek.com/v1"),
        )
    }

    @Test
    fun `完整端点先剥 chat-completions`() {
        // 这是用户最容易踩的一种写法（直接从文档里抄完整端点）。
        // 不剥就会得到 `.../v1/chat/completions/models` → 必 404。
        assertEquals(
            "https://api.deepseek.com/v1/models",
            ModelCatalog.modelsUrl("https://api.deepseek.com/v1/chat/completions"),
        )
    }

    @Test
    fun `尾斜杠不影响结果`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            ModelCatalog.modelsUrl("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun `非 v版本路径也识别兼容模式`() {
        // 阿里云百炼的地址是 `/compatible-mode/v1`，末段是 v1 → 认。
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
            ModelCatalog.modelsUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
    }

    @Test
    fun `空地址返回空串`() {
        // 不返回 `/v1/models` 这种"看似合理"的相对路径 ——
        // 那会让上层以为配置没问题，然后发一个必然失败的请求。
        assertEquals("", ModelCatalog.modelsUrl(""))
        assertEquals("", ModelCatalog.modelsUrl("   "))
    }

    // ── 解析：OpenAI 格式 ────────────────────────────────────────────

    @Test
    fun `解析 OpenAI data 数组`() {
        val body = """
            {"object":"list","data":[
              {"id":"gpt-4o-mini","object":"model"},
              {"id":"gpt-4o","object":"model"}
            ]}
        """.trimIndent()
        val result = ModelCatalog.parse(body)
        assertTrue(result.isSuccess)
        val ids = result.getOrThrow().map { it.id }
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), ids)
    }

    @Test
    fun `解析结果按 id 排序且去重`() {
        // 中转站常见：同模型多个别名指向同一个 id，不去重会出现两行一样的。
        val body = """
            {"data":[
              {"id":"b-model"},{"id":"a-model"},{"id":"b-model"}
            ]}
        """.trimIndent()
        val options = ModelCatalog.parse(body).getOrThrow()
        assertEquals(listOf("a-model", "b-model"), options.map { it.id })
    }

    // ── 解析：Gemini 格式 ───────────────────────────────────────────

    @Test
    fun `解析 Gemini 取 name 末段并把 displayName 当显示名`() {
        // Gemini 返回 `name: "models/gemini-2.0-flash"`。
        // 直接把整串当 id 发进请求体会 400 —— 这是必须锁死的行为。
        val body = """
            {"models":[
              {"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash",
               "supportedGenerationMethods":["generateContent"]}
            ]}
        """.trimIndent()
        val options = ModelCatalog.parse(body).getOrThrow()
        assertEquals(1, options.size)
        assertEquals("gemini-2.0-flash", options[0].id)
        assertEquals("Gemini 2.0 Flash", options[0].name)
    }

    // ── 解析：Anthropic 格式 ────────────────────────────────────────

    @Test
    fun `解析 Anthropic display_name`() {
        val body = """
            {"data":[
              {"id":"claude-3-5-sonnet-20241022","display_name":"Claude 3.5 Sonnet"}
            ]}
        """.trimIndent()
        val options = ModelCatalog.parse(body).getOrThrow()
        assertEquals("claude-3-5-sonnet-20241022", options[0].id)
        assertEquals("Claude 3.5 Sonnet", options[0].name)
    }

    @Test
    fun `没有 display 名时 name 回落为 id`() {
        val options = ModelCatalog.parse("""{"data":[{"id":"x"}]}""").getOrThrow()
        // name 与 id 相同时 UI 只显示一行（见 ModelRow 的注释）。
        assertEquals("x", options[0].name)
    }

    // ── 失败路径 ────────────────────────────────────────────────────

    @Test
    fun `空响应给出 EmptyResponse`() {
        val failure = ModelCatalog.parse("   ").exceptionOrNull() as ModelCatalog.ParseFailure
        assertTrue(failure.reason is ModelCatalog.Failure.EmptyResponse)
    }

    @Test
    fun `HTML 错误页给出 Parse 失败而不是崩`() {
        // 常见于中转站把未知路径返回成 HTML 首页。
        val failure = ModelCatalog.parse("<html><body>Not Found</body></html>")
            .exceptionOrNull() as ModelCatalog.ParseFailure
        assertTrue(failure.reason is ModelCatalog.Failure.Parse)
    }

    @Test
    fun `没有数组字段给出 Parse 失败`() {
        val failure = ModelCatalog.parse("""{"error":{"message":"invalid key"}}""")
            .exceptionOrNull() as ModelCatalog.ParseFailure
        assertTrue(failure.reason is ModelCatalog.Failure.Parse)
    }

    @Test
    fun `数组里全是没有 id 的项给出 Parse 失败`() {
        // 有的实现在无权限时返回 `{"data":[{}]}`。此时"成功解析出 0 个模型"
        // 会让 UI 显示一个空列表并且不报错，用户不知道该做什么。
        val failure = ModelCatalog.parse("""{"data":[{},{}]}""")
            .exceptionOrNull() as ModelCatalog.ParseFailure
        assertTrue(failure.reason is ModelCatalog.Failure.Parse)
    }

    @Test
    fun `失败文案非空且带原因`() {
        // 这句话会直接进弹窗，不能是空串或纯英文异常名。
        assertTrue(ModelCatalog.Failure.EmptyBaseUrl.description.isNotBlank())
        assertTrue(ModelCatalog.Failure.Http(401, "unauthorized").description.contains("401"))
        assertTrue(ModelCatalog.Failure.Network(null).description.contains("网络"))
        assertTrue(ModelCatalog.Failure.EmptyResponse.description.isNotBlank())
    }
}