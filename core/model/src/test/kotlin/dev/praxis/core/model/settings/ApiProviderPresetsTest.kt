package dev.praxis.core.model.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务商预设表的护栏。
 *
 * 这张表的价值全在「默认值对」上：地址或模型名写错一个字符，
 * 用户选了预设之后拿到的是 404 / model not found，比没有预设更糟 ——
 * 因为他会以为「选了就配好了」。
 */
class ApiProviderPresetsTest {

    @Test
    fun `每个预设的端点都是合法 http 地址`() {
        ApiProvider.entries
            .filter { it.defaultEndpoint.isNotBlank() }
            .forEach { provider ->
                val endpoint = provider.defaultEndpoint
                assertTrue(
                    "${provider.name} 的端点缺少 http(s) scheme：$endpoint",
                    endpoint.startsWith("https://") || endpoint.startsWith("http://"),
                )
                assertFalse(
                    "${provider.name} 的端点带了尾斜杠：$endpoint",
                    endpoint.endsWith("/"),
                )
                // 端点里不该已经带 /chat/completions：那会让「默认值」与
                // 「用户填完整端点」两种语义混在一起，UI 上的「已自定义」判定会失真。
                assertFalse(
                    "${provider.name} 的端点不该含 /chat/completions：$endpoint",
                    endpoint.contains("/chat/completions"),
                )
            }
    }

    @Test
    fun `云端服务都有完整默认值且需要 key`() {
        // 「完整默认值」= 选了就能用（只差贴 Key）。
        val cloud = listOf(
            ApiProvider.DeepSeek,
            ApiProvider.ZhiPu,
            ApiProvider.Moonshot,
            ApiProvider.Aliyun,
            ApiProvider.SiliconFlow,
            ApiProvider.OpenAI,
            ApiProvider.XAi,
            ApiProvider.Mistral,
            ApiProvider.OpenRouter,
        )
        cloud.forEach { provider ->
            assertTrue("${provider.name} 应有默认端点", provider.defaultEndpoint.isNotBlank())
            assertTrue("${provider.name} 应有默认模型", provider.defaultModel.isNotBlank())
            assertTrue("${provider.name} 需要 API Key", provider.requiresApiKey)
            assertTrue("${provider.name} 应满足 hasFullDefaults", provider.hasFullDefaults)
        }
    }

    @Test
    fun `本地服务不需要 key`() {
        // 本地推理没有鉴权。逼用户填假 Key 会让「保存」按钮逻辑变得莫名其妙。
        listOf(ApiProvider.Ollama, ApiProvider.LmStudio, ApiProvider.LlamaCpp).forEach { provider ->
            assertFalse("${provider.name} 不该要求 API Key", provider.requiresApiKey)
            assertTrue("${provider.name} 应有默认端点", provider.defaultEndpoint.isNotBlank())
            // LM Studio / llama.cpp 的模型名取决于用户本地装了什么，给不出默认值。
            // Ollama 有稳定的默认模型名。
            if (provider == ApiProvider.Ollama) {
                assertTrue("Ollama 应有默认模型", provider.defaultModel.isNotBlank())
            }
        }
    }

    @Test
    fun `自定义项没有任何默认值且需要 key`() {
        // Custom 用于中转站：地址千奇百怪，给默认值等于误导。
        assertEquals("", ApiProvider.Custom.defaultEndpoint)
        assertEquals("", ApiProvider.Custom.defaultModel)
        assertFalse(ApiProvider.Custom.hasFullDefaults)
        // 中转站通常仍需要 Key（虽然也有无鉴权的自建站）。
        // 选 true 是安全的默认：给输入框、允许留空，不会阻止保存。
        assertTrue(ApiProvider.Custom.requiresApiKey)
    }

    @Test
    fun `按名还原找不到时回落到 Custom`() {
        // 不能用 valueOf：降级安装或枚举改名时它会抛异常，把设置页打挂。
        assertEquals(ApiProvider.DeepSeek, ApiProvider.fromNameOrDefault("DeepSeek"))
        assertEquals(ApiProvider.Custom, ApiProvider.fromNameOrDefault(null))
        assertEquals(ApiProvider.Custom, ApiProvider.fromNameOrDefault(""))
        assertEquals(ApiProvider.Custom, ApiProvider.fromNameOrDefault("NotAProvider"))
        // 大小写敏感是有意的：写入端只写 name，不会写出变体。
        assertEquals(ApiProvider.Custom, ApiProvider.fromNameOrDefault("deepseek"))
    }

    @Test
    fun `label 非空且不重复`() {
        val labels = ApiProvider.entries.map { it.label }
        labels.forEach { assertTrue("label 不能为空", it.isNotBlank()) }
        assertEquals("label 重复会让下拉框出现两项看起来一样的选项", labels.size, labels.toSet().size)
    }
}