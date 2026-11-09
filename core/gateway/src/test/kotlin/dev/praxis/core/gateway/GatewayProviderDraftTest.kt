package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 自定义提供商的校验（文档 §5.3）。 */
class GatewayProviderDraftTest {

    private fun draft(
        id: String = "mygateway",
        name: String = "",
        baseUrl: String = "https://api.example.com",
        models: List<String> = emptyList(),
    ) = GatewayProviderDraft(id = id, name = name, baseUrl = baseUrl, models = models)

    @Test
    fun `合法草案通过`() {
        assertNull(GatewayProviderDraftRules.validate(draft()))
    }

    @Test
    fun `标识不能为空`() {
        assertEquals(GatewayProviderDraftError.MissingId, GatewayProviderDraftRules.validate(draft(id = "   ")))
    }

    @Test
    fun `标识不能带空格`() {
        // 它是账号表里的引用键，带空格会让「哪家」变得含糊。
        assertEquals(
            GatewayProviderDraftError.IdWithWhitespace,
            GatewayProviderDraftRules.validate(draft(id = "my gateway")),
        )
    }

    @Test
    fun `与内置家重名要当场拒绝`() {
        // 内置地址优先，这类自定义家永远不会被用到；不拦的话用户配完发现不生效。
        assertEquals(
            GatewayProviderDraftError.IdCollidesWithBuiltin,
            GatewayProviderDraftRules.validate(draft(id = "openai")),
        )
        assertEquals(
            GatewayProviderDraftError.IdCollidesWithBuiltin,
            GatewayProviderDraftRules.validate(draft(id = "Anthropic")),
        )
    }

    @Test
    fun `地址必须带 http 前缀`() {
        assertEquals(
            GatewayProviderDraftError.BadBaseUrlScheme,
            GatewayProviderDraftRules.validate(draft(baseUrl = "api.example.com")),
        )
        assertEquals(
            GatewayProviderDraftError.MissingBaseUrl,
            GatewayProviderDraftRules.validate(draft(baseUrl = "  ")),
        )
    }

    @Test
    fun `归一化去掉尾部斜杠`() {
        // 留着会拼出 //v1/models，而服务器对双斜杠的处理并不一致。
        assertEquals(
            "https://api.example.com",
            GatewayProviderDraftRules.normalize(draft(baseUrl = "https://api.example.com///")).baseUrl,
        )
    }

    @Test
    fun `名字留空时用标识`() {
        assertEquals("mygateway", GatewayProviderDraftRules.normalize(draft(name = "  ")).name)
    }

    @Test
    fun `模型清单去掉空行`() {
        val normalized = GatewayProviderDraftRules
            .normalize(draft(models = listOf("gpt-x", "  ", "", " gpt-y ")))
        assertEquals(listOf("gpt-x", "gpt-y"), normalized.models)
    }

    @Test
    fun `每种错误都有人话`() {
        GatewayProviderDraftError.entries.forEach { error ->
            assertEquals(true, error.userMessage().isNotBlank())
        }
    }
}
