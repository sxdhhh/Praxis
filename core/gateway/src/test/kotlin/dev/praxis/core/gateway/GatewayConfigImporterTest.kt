package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关 config.json 的键映射（文档 8.4）。
 *
 * ## 两条比映射本身更重要的事
 *
 * 1. 没写的键要留 null，不能变成 false 或 0 —— 否则用户关掉的开关会在导入后
 *    自己打开，或者超时被改成一个谁也没选过的值。
 * 2. 没导入的键必须**列出来**。文档原话：用户看到「导入完成」却少了配置，
 *    是最容易引发不信任的体验。
 */
class GatewayConfigImporterTest {

    private val fullJson = """
        {
          "routingStrategy": "order",
          "routingSticky": "auto",
          "routingFailoverBackoff": true,
          "routingSink": false,
          "routingBreakerEnabled": true,
          "routingBreakerThreshold": 3,
          "routingBreakerTimeoutSeconds": 60,
          "routingBreakerSuccessThreshold": 2,
          "retryCount": 1,
          "retryCrossProviderCount": 3,
          "retryIntervalSeconds": 5,
          "noRetryStatusCodes": "400,402,413",
          "connectTimeoutSeconds": 15,
          "headersTimeoutSeconds": 30,
          "streamIdleTimeoutSeconds": 120,
          "bodyTimeoutSeconds": 60,
          "queueMaxWaits": 10,
          "queueWaitSeconds": 30,
          "imageCompressEnabled": true,
          "imageCompressMaxEdge": 1568,
          "imageCompressQuality": 80,
          "imageCompressThresholdKb": 512,
          "autoContinue": false,
          "autoContinueMaxRounds": 3,
          "debugMode": false
        }
    """.trimIndent()

    @Test
    fun A_类键全部映射() {
        val result = GatewayConfigImporter.parse(fullJson).getOrThrow()
        val c = result.config

        assertEquals("order", c.routingStrategy)
        assertEquals("auto", c.routingSticky)
        assertEquals(true, c.routingFailoverBackoff)
        assertEquals(false, c.routingSink)
        assertEquals(true, c.breakerEnabled)
        assertEquals(3, c.breakerThreshold)
        assertEquals(1, c.retryCount)
        assertEquals(3, c.retryCrossProviderCount)
        assertEquals(15, c.connectTimeoutSeconds)
        assertEquals(1568, c.imageCompressMaxEdge)
        assertEquals(false, c.autoContinue)
        assertEquals(3, c.autoContinueMaxRounds)
        assertEquals(false, c.debugMode)
    }

    @Test
    fun 没写的键保持_null_而不是默认值() {
        val result = GatewayConfigImporter.parse("{}").getOrThrow()

        // null 表示「网关文件里没写」，调用方据此保留 Praxis 自己的默认值。
        assertNull(result.config.routingStrategy)
        assertNull(result.config.breakerEnabled)
        assertNull(result.config.autoContinue)
        assertNull(result.config.connectTimeoutSeconds)
        assertTrue(result.appliedKeys.isEmpty())
    }

    @Test
    fun 不重试状态码从逗号串解析() {
        val result = GatewayConfigImporter.parse(fullJson).getOrThrow()

        assertEquals(listOf(400, 402, 413), result.config.noRetryStatusCodes)
    }

    @Test
    fun 服务端专属键逐条列出() {
        val text = """
            { "githubToken": "x", "corsEnabled": true, "logDir": "/var/log", "zcodeCaptchaPoolA": [] }
        """.trimIndent()

        val result = GatewayConfigImporter.parse(text).getOrThrow()

        assertEquals(
            listOf("corsEnabled", "githubToken", "logDir", "zcodeCaptchaPoolA"),
            result.serverOnlyKeys,
        )
        assertTrue("服务端键不该进 unknown", result.unknownKeys.isEmpty())
    }

    @Test
    fun 完全不认识的键进_unknown() {
        val text = """
            { "someNewKeyFromFuture": 1 }
        """.trimIndent()

        val result = GatewayConfigImporter.parse(text).getOrThrow()

        assertEquals(listOf("someNewKeyFromFuture"), result.unknownKeys)
        assertTrue(result.serverOnlyKeys.isEmpty())
    }

    @Test
    fun 类型不对的键进_invalid_而不是被当成没写() {
        val text = """
            { "retryCount": "abc" }
        """.trimIndent()

        val result = GatewayConfigImporter.parse(text).getOrThrow()

        assertEquals(listOf("retryCount"), result.invalidKeys)
        assertNull(result.config.retryCount)
        assertTrue("已识别为类型错误的键不该再进 unknown", result.unknownKeys.isEmpty())
    }

    @Test
    fun 网关新增键不会挤掉已识别的键() {
        val text = """
            { "routingStrategy": "weighted", "brandNewKey": true }
        """.trimIndent()

        val result = GatewayConfigImporter.parse(text).getOrThrow()

        assertEquals("weighted", result.config.routingStrategy)
        assertEquals(listOf("brandNewKey"), result.unknownKeys)
    }

    @Test
    fun 非_JSON_对象视为失败() {
        assertTrue(GatewayConfigImporter.parse("[1,2,3]").isFailure)
        assertTrue(GatewayConfigImporter.parse("not json").isFailure)
    }

    @Test
    fun applied_里是实际生效的键() {
        val result = GatewayConfigImporter.parse(fullJson).getOrThrow()

        assertTrue(result.appliedKeys.contains("routingStrategy"))
        assertTrue(result.appliedKeys.contains("noRetryStatusCodes"))
        assertEquals(25, result.appliedKeys.size)
    }
}
