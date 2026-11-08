package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * config 片段的导出（文档 8.5 的「双向可逆」）。
 *
 * ## 为什么只测往返还不够
 *
 * 往返相等只证明「我读得懂自己写的」，不证明「网关读得懂」。所以另有三条：
 * 只写非空键（不许替用户做选择）、键名是网关原生 camelCase、状态码是逗号串。
 */
class GatewayConfigExporterTest {

    private val config = GatewayRoutingConfig(
        routingStrategy = "order",
        routingSticky = "auto",
        routingFailoverBackoff = true,
        routingSink = false,
        breakerEnabled = true,
        breakerThreshold = 3,
        breakerTimeoutSeconds = 60,
        breakerSuccessThreshold = 2,
        retryCount = 1,
        retryCrossProviderCount = 3,
        retryIntervalSeconds = 5,
        noRetryStatusCodes = listOf(400, 402, 413),
        connectTimeoutSeconds = 15,
        headersTimeoutSeconds = 30,
        streamIdleTimeoutSeconds = 120,
        bodyTimeoutSeconds = 60,
        queueMaxWaits = 10,
        queueWaitSeconds = 30,
        imageCompressEnabled = true,
        imageCompressMaxEdge = 1568,
        imageCompressQuality = 80,
        imageCompressThresholdKb = 512,
        autoContinue = false,
        autoContinueMaxRounds = 3,
        debugMode = false,
    )

    @Test
    fun 往返后配置相等() {
        val encoded = GatewayConfigExporter.encode(config)

        val roundTripped = GatewayConfigImporter.parse(encoded).getOrThrow().config

        assertEquals(config, roundTripped)
    }

    @Test
    fun 只写非空键() {
        val encoded = GatewayConfigExporter.encode(GatewayRoutingConfig(routingStrategy = "order"))

        assertTrue(encoded.contains("routingStrategy"))
        assertFalse("没写的键不该出现，否则等于替用户做选择", encoded.contains("retryCount"))
    }

    @Test
    fun 全空写出空对象() {
        val encoded = GatewayConfigExporter.encode(GatewayRoutingConfig())

        assertFalse(encoded.contains("routingStrategy"))
        assertFalse(encoded.contains("autoContinue"))
    }

    @Test
    fun false_也是要写出来的值() {
        // false 与 null 是两件事：前者是用户明确关掉，后者是没配过。
        val encoded = GatewayConfigExporter.encode(GatewayRoutingConfig(autoContinue = false))

        assertTrue(encoded.contains("autoContinue"))
    }

    @Test
    fun 状态码写成逗号串() {
        val encoded = GatewayConfigExporter.encode(
            GatewayRoutingConfig(noRetryStatusCodes = listOf(400, 402, 413)),
        )

        assertTrue(encoded.contains("400,402,413"))
    }

    @Test
    fun 键名是网关原生的驼峰而不是下划线() {
        val encoded = GatewayConfigExporter.encode(config)

        assertTrue(encoded.contains("routingStrategy"))
        assertFalse("不该写 Praxis 内部的下划线键名", encoded.contains("routing_strategy"))
    }

    @Test
    fun 空状态码列表不写出() {
        val encoded = GatewayConfigExporter.encode(
            GatewayRoutingConfig(noRetryStatusCodes = emptyList()),
        )

        // 空列表与「没写」在网关侧等价（都是没有免重试状态码），写空串反而会被解析成非法值。
        assertFalse(encoded.contains("noRetryStatusCodes"))
    }
}
