package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * config 片段的**往返**（文档 §8.5 的验收）。
 *
 * > 双向可逆：Praxis 导出的 `gateway-config.json` 要能被 Agent2API 原样导入；反之亦然。
 *
 * 单测能覆盖的是「我们这边不自相矛盾」：把一份**每个字段都有值**的配置导出、再导入，
 * 得到的应当**逐字段相等**。任何一侧漏写/写错键名，这条都会红。
 *
 * 为什么强调「每个字段都有值」：导出侧只写非空键（见 GatewayConfigExporter 的 KDoc），
 * 所以字段留 null 时往返「相等」是假通过 —— 它压根没被测到。
 */
class GatewayConfigRoundTripTest {

    /** 每个字段都填上，任何一侧漏写都会让往返不等。 */
    private val full = GatewayRoutingConfig(
        routingStrategy = "roundRobin",
        routingSticky = "auto",
        routingFailoverBackoff = true,
        routingSink = false,
        breakerEnabled = true,
        breakerThreshold = 3,
        breakerTimeoutSeconds = 60,
        breakerSuccessThreshold = 2,
        retryCount = 2,
        retryCrossProviderCount = 4,
        retryIntervalSeconds = 5,
        noRetryStatusCodes = listOf(400, 402, 413),
        connectTimeoutSeconds = 15,
        headersTimeoutSeconds = 20,
        streamIdleTimeoutSeconds = 120,
        bodyTimeoutSeconds = 300,
        queueMaxWaits = 8,
        queueWaitSeconds = 30,
        imageCompressEnabled = true,
        imageCompressMaxEdge = 1600,
        imageCompressQuality = 80,
        imageCompressThresholdKb = 512,
        autoContinue = true,
        autoContinueMaxRounds = 3,
        debugMode = false,
    )

    @Test
    fun `全字段导出再导入逐字段相等`() {
        val encoded = GatewayConfigExporter.encode(full)
        val parsed = GatewayConfigImporter.parse(encoded).getOrThrow()
        assertEquals(full, parsed.config)
    }

    @Test
    fun `导出的键名是网关原生 camelCase`() {
        // 这个文件是给网关吃的，不是给 Praxis 自己读的。
        val encoded = GatewayConfigExporter.encode(full)
        assertTrue("应当含 routingStrategy", encoded.contains("\"routingStrategy\""))
        assertTrue("应当含 retryCrossProviderCount", encoded.contains("\"retryCrossProviderCount\""))
        assertTrue("应当含 connectTimeoutSeconds", encoded.contains("\"connectTimeoutSeconds\""))
    }

    @Test
    fun `未设置的键不写出去`() {
        // null 在导入侧的语义是「网关文件里原本没有这个键」；
        // 写成 false/0 等于替用户做了一次他从未做过的选择。
        val encoded = GatewayConfigExporter.encode(GatewayRoutingConfig(routingStrategy = "order"))
        assertTrue(encoded.contains("\"routingStrategy\""))
        assertTrue("不该写出未设置的键", !encoded.contains("\"autoContinue\""))
        assertTrue("不该写出未设置的键", !encoded.contains("\"breakerEnabled\""))
    }

    @Test
    fun `空列表与没写等价 都不写出`() {
        val encoded = GatewayConfigExporter.encode(GatewayRoutingConfig(noRetryStatusCodes = emptyList()))
        assertTrue("空列表不该写出", !encoded.contains("noRetryStatusCodes"))
    }

    @Test
    fun `不重试状态码列表往返保持`() {
        val single = GatewayRoutingConfig(noRetryStatusCodes = listOf(400, 413))
        val parsed = GatewayConfigImporter.parse(GatewayConfigExporter.encode(single)).getOrThrow()
        assertEquals(listOf(400, 413), parsed.config.noRetryStatusCodes)
    }

    @Test
    fun `服务端专属键被明确告知未导入`() {
        // 「导入完成」却少了配置是最容易引发不信任的体验，所以要逐条列出。
        val text = "{" + "\"routingStrategy\":\"order\"," + "\"corsEnabled\":true," + "\"logDir\":\"/x\"" + "}"
        val parsed = GatewayConfigImporter.parse(text).getOrThrow()
        assertEquals("order", parsed.config.routingStrategy)
        assertTrue("应当列出 corsEnabled", parsed.serverOnlyKeys.contains("corsEnabled"))
        assertTrue("应当列出 logDir", parsed.serverOnlyKeys.contains("logDir"))
    }
}
