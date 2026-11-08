package dev.praxis.core.gateway

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出计划的规格（文档 8.5）。
 *
 * 「导出了哪些文件、叫什么名字、里面有没有明文凭证」全在这里断言 ——
 * 这一层能在无设备环境跑，而真正写盘的 :app 侧不能。
 */
class GatewayExportPlanTest {

    private val date = "20261108"

    private fun account(
        id: String = "a1",
        provider: String = "openai",
        name: String = "工作号",
        apiKey: String = "sk-secret",
    ) = GatewayAccountFile(
        format = ACCOUNT_FORMAT,
        id = id,
        provider = provider,
        name = name,
        apiKey = apiKey,
    )

    private fun plan(
        accounts: List<GatewayAccountFile> = listOf(account()),
        routing: GatewayRoutingConfig? = null,
        modelRules: String? = null,
        includeSecrets: Boolean = false,
    ) = GatewayExportPlanner.build(
        accounts = accounts,
        routing = routing,
        modelRules = modelRules?.let { Json.parseToJsonElement(it) },
        includeSecrets = includeSecrets,
        date = date,
    )

    private fun fileNamed(plan: GatewayExportPlan, name: String) =
        plan.files.firstOrNull { it.relativePath.endsWith(name) }
            ?: error("没有这个文件：" + name + "（实际：" + plan.files.map { it.relativePath } + "）")

    @Test
    fun `账号文件名按文档形状生成且编号递增`() {
        val p = plan(accounts = listOf(account(id = "a1", name = "工作号"), account(id = "a2", name = "备用")))
        val names = p.files.filter { it.relativePath.startsWith("accounts/") }.map { it.relativePath }
        assertEquals("accounts/openai-工作号-20261108-001.json", names[0])
        assertEquals("accounts/openai-备用-20261108-002.json", names[1])
    }

    @Test
    fun `名字里的路径分隔符与非法字符被替换`() {
        val p = plan(listOf(account(provider = "openai/工作号", name = "bad:name*1")))
        assertEquals("accounts/openai_工作号-bad_name_1-20261108-001.json", p.files[0].relativePath)
        assertFalse(p.files[0].relativePath.substring("accounts/".length).contains("/"))
    }

    @Test
    fun `名字为空时省掉那一段而不是留空横线`() {
        val p = plan(listOf(account(name = "")))
        assertEquals("accounts/openai-20261108-001.json", p.files[0].relativePath)
    }

    @Test
    fun `仅配置粒度打码凭证且文件名不带 secrets`() {
        val p = plan(includeSecrets = false)
        val f = p.files[0]
        assertFalse(f.relativePath.contains("-secrets"))
        assertTrue(f.content.contains(GatewayExporter.REDACTED))
        assertFalse(f.content.contains("sk-secret"))
    }

    @Test
    fun `含凭证粒度写明文且文件名强制带 secrets`() {
        val p = plan(includeSecrets = true)
        val f = p.files[0]
        assertTrue(f.relativePath.endsWith("-secrets.json"))
        assertTrue(f.content.contains("sk-secret"))
        assertTrue(p.notes.any { it.contains("明文凭证") })
    }

    @Test
    fun `配置片段含路由键与模型映射原文`() {
        val p = plan(
            routing = GatewayRoutingConfig(routingStrategy = "order", retryCount = 3),
            modelRules = "{\"mappings\":[{\"alias\":\"fast\",\"target\":\"gpt-x\"}]}",
        )
        val cfg = fileNamed(p, GatewayExportPlanner.CONFIG_FILE_NAME)
        assertTrue(cfg.content.contains("\"routingStrategy\": \"order\""))
        assertTrue(cfg.content.contains("\"retryCount\": 3"))
        assertTrue(cfg.content.contains("\"modelRules\""))
        assertTrue(cfg.content.contains("\"alias\": \"fast\""))
    }

    @Test
    fun `路由与映射都为空时不产出配置片段并如实说明`() {
        val p = plan(routing = GatewayRoutingConfig())
        assertTrue(p.files.none { it.relativePath == GatewayExportPlanner.CONFIG_FILE_NAME })
        assertTrue(p.notes.any { it.contains("没有可导出的网关配置") })
    }

    @Test
    fun `账号池为空时如实说明而不是产出一个空计划`() {
        val p = plan(accounts = emptyList())
        assertTrue(p.files.none { it.relativePath.startsWith("accounts/") })
        assertTrue(p.notes.any { it.contains("账号池是空的") })
    }

    @Test
    fun `导出的账号文件能被导入器原样读回`() {
        val original = account(id = "custom-acct-1", provider = "custom-3f2a91b04c7e", name = "我的中转-1")
        val p = plan(accounts = listOf(original), includeSecrets = true)
        val parsed = GatewayImporter.parse(p.files[0].content).getOrThrow()
        assertEquals(original, parsed)
    }

    @Test
    fun `notes 明示 apiKeys 与完整备份都未包含`() {
        val p = plan()
        assertTrue(p.notes.any { it.contains("apiKeys 未包含") })
        assertTrue(p.notes.any { it.contains("praxis-gateway-backup.json") })
    }
}
