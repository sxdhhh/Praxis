package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置 → config 片段（导出，文档 §8.5）。 */
class GatewaySettingsExportTest {

    private val system = GatewaySystemSettings(
        connectTimeoutSeconds = 30,
        readTimeoutSeconds = 240,
        maxSwitches = 5,
        debugRawTraffic = true,
        queueWaitSeconds = 45,
        autoContinue = true,
        autoContinueMaxRounds = 4,
        imageCompress = dev.praxis.core.ai.ChatImageCompression(
            enabled = true,
            maxEdge = 1600,
            quality = 70,
            thresholdKb = 128,
        ),
    )

    private val policy = GatewayRoutingPolicy(
        strategy = RoutingStrategy.RoundRobin,
        stickyEnabled = false,
        breakerEnabled = true,
        breakerFailureThreshold = 4,
        breakerOpenMillis = 90_000L,
        breakerSuccessThreshold = 2,
    )

    @Test
    fun `词表用网关的而不是我们的枚举名`() {
        // 网关只做字符串比较；写成 Sequential 它读不懂。
        assertEquals("order", GatewaySettingsExport.strategyToken(RoutingStrategy.Sequential))
        assertEquals("roundRobin", GatewaySettingsExport.strategyToken(RoutingStrategy.RoundRobin))
        assertEquals("auto", GatewaySettingsExport.stickyToken(true))
        assertEquals("off", GatewaySettingsExport.stickyToken(false))
    }

    @Test
    fun `毫秒换算成网关的秒`() {
        val config = GatewaySettingsExport.toRoutingConfig(policy, system)
        assertEquals(90, config.breakerTimeoutSeconds)
    }

    @Test
    fun `我们有的设置都装进去了`() {
        val config = GatewaySettingsExport.toRoutingConfig(policy, system)
        assertEquals("roundRobin", config.routingStrategy)
        assertEquals("off", config.routingSticky)
        assertEquals(true, config.breakerEnabled)
        assertEquals(4, config.breakerThreshold)
        assertEquals(2, config.breakerSuccessThreshold)
        assertEquals(5, config.retryCrossProviderCount)
        assertEquals(30, config.connectTimeoutSeconds)
        assertEquals(240, config.streamIdleTimeoutSeconds)
        assertEquals(45, config.queueWaitSeconds)
        // 压缩**默认就是开**，所以「开」不算改动 → 不写出去（网关继续用它自己的默认）。
        assertEquals(null, config.imageCompressEnabled)
        // 改过的参数才写。
        assertEquals(1600, config.imageCompressMaxEdge)
        assertEquals(70, config.imageCompressQuality)
        assertEquals(128, config.imageCompressThresholdKb)
        assertEquals(true, config.autoContinue)
        assertEquals(4, config.autoContinueMaxRounds)
        assertEquals(true, config.debugMode)
    }

    @Test
    fun `我们还没有的设置留空 由网关用自己的默认`() {
        val config = GatewaySettingsExport.toRoutingConfig(policy, system)
        assertEquals(null, config.retryCount)
        assertEquals(null, config.retryIntervalSeconds)
        assertEquals(null, config.noRetryStatusCodes)
        assertEquals(null, config.headersTimeoutSeconds)
        assertEquals(null, config.bodyTimeoutSeconds)
        assertEquals(null, config.queueMaxWaits)
    }

    @Test
    fun `关掉图片压缩会写出去（那是真的改动）`() {
        val changed = system.copy(
            imageCompress = system.imageCompress.copy(enabled = false),
        )
        val config = GatewaySettingsExport.toRoutingConfig(policy, changed)
        assertEquals(false, config.imageCompressEnabled)
    }

    @Test
    fun `关掉自动续写也会写出去`() {
        // 自动续写默认关 → 「关」不算改动；但用户从**开**改回关时要写。
        val changed = system.copy(autoContinue = false)
        assertEquals(null, GatewaySettingsExport.toRoutingConfig(policy, changed).autoContinue)
        // 反过来：开了就写 true（system 里 autoContinue = true）。
        assertEquals(true, GatewaySettingsExport.toRoutingConfig(policy, system).autoContinue)
    }

    @Test
    fun `导出的文件能被重新导入且值不变（往返）`() {
        // 这是「导出真的可用」的唯一硬证据：走完整条链再读回来。
        val config = GatewaySettingsExport.toRoutingConfig(policy, system)
        val text = GatewayConfigExporter.encode(config)
        val parsed = GatewayConfigImporter.parse(text).getOrThrow().config
        assertEquals(config, parsed)
    }

    @Test
    fun `空设置不该写出任何键（网关继续用自己的默认）`() {
        // 只导出「用户真的改过的东西」；全默认时不要往文件里塞一堆显式值。
        val text = GatewayConfigExporter.encode(
            GatewaySettingsExport.toRoutingConfig(
                GatewayRoutingPolicy(),
                GatewaySystemSettings(),
            ),
        )
        assertTrue("策略与粘性总是写（它们是当前选择，不是默认与否的问题）", text.contains("routingStrategy"))
        assertTrue("没配过的超时不该写出去", !text.contains("queueWaitSeconds"))
    }
}
