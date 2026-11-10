package dev.praxis.core.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网关系统设置（文档 §5.18 的可用子集）。 */
class GatewaySystemSettingsTest {

    @Test
    fun `默认值等于移植前的写死值`() {
        // 默认值一改，所有用户的网络行为都变了 —— 那不该是「加个设置」的副作用。
        val defaults = GatewaySystemSettings()
        assertEquals(15, defaults.connectTimeoutSeconds)
        assertEquals(120, defaults.readTimeoutSeconds)
        assertEquals(3, defaults.maxSwitches)
        assertEquals(false, defaults.debugRawTraffic)
    }

    @Test
    fun `排队等待默认不限（不改用户既有行为）`() {
        assertEquals(0, GatewaySystemSettings().queueWaitSeconds)
    }

    @Test
    fun `排队等待越界被夹到 0 到 300`() {
        assertEquals(
            GatewaySystemSettings.MIN_QUEUE_WAIT_SECONDS,
            GatewaySystemSettingsRules.normalize(
                GatewaySystemSettings(queueWaitSeconds = -5),
            ).queueWaitSeconds,
        )
        assertEquals(
            GatewaySystemSettings.MAX_QUEUE_WAIT_SECONDS,
            GatewaySystemSettingsRules.normalize(
                GatewaySystemSettings(queueWaitSeconds = 9999),
            ).queueWaitSeconds,
        )
    }

    @Test
    fun `排队等待能往返`() {
        val settings = GatewaySystemSettings(queueWaitSeconds = 45)
        assertEquals(
            settings,
            GatewaySystemSettingsCodec.decode(GatewaySystemSettingsCodec.encode(settings))?.getOrNull(),
        )
    }

    @Test
    fun `旧 JSON 缺排队字段时用默认值`() {
        // schema 演进：老设置文件没有这个键，读出来应当是「不限」。
        val decoded = GatewaySystemSettingsCodec.decode("""{"maxSwitches":5}""")?.getOrNull()
        assertEquals(0, decoded?.queueWaitSeconds)
    }

    @Test
    fun `越界被夹到边界`() {
        val normalized = GatewaySystemSettingsRules.normalize(
            GatewaySystemSettings(connectTimeoutSeconds = 0, readTimeoutSeconds = 99999, maxSwitches = 0),
        )
        assertEquals(GatewaySystemSettings.MIN_CONNECT_SECONDS, normalized.connectTimeoutSeconds)
        assertEquals(GatewaySystemSettings.MAX_READ_SECONDS, normalized.readTimeoutSeconds)
        assertEquals(GatewaySystemSettings.MIN_SWITCHES, normalized.maxSwitches)
    }

    @Test
    fun `往返`() {
        val settings = GatewaySystemSettings(
            connectTimeoutSeconds = 30,
            readTimeoutSeconds = 300,
            maxSwitches = 5,
            debugRawTraffic = true,
        )
        assertEquals(
            settings,
            GatewaySystemSettingsCodec.decode(GatewaySystemSettingsCodec.encode(settings))?.getOrNull(),
        )
    }

    @Test
    fun `没配过返回 null`() {
        assertNull(GatewaySystemSettingsCodec.decode(null))
        assertNull(GatewaySystemSettingsCodec.decode("   "))
    }

    @Test
    fun `缺字段用默认值 类型错算坏内容`() {
        val decoded = GatewaySystemSettingsCodec.decode("""{"maxSwitches":5}""")?.getOrNull()
        assertEquals(5, decoded?.maxSwitches)
        assertEquals(15, decoded?.connectTimeoutSeconds)
        assertTrue(
            GatewaySystemSettingsCodec.decode("""{"maxSwitches":"三次"}""")?.isFailure == true,
        )
    }

    @Test
    fun `反序列化时也归一化`() {
        // 手改过设置文件也不会让超时变成 0（那会让所有请求立刻超时）。
        val decoded = GatewaySystemSettingsCodec
            .decode("""{"connectTimeoutSeconds":1}""")
            ?.getOrNull()
        assertEquals(GatewaySystemSettings.MIN_CONNECT_SECONDS, decoded?.connectTimeoutSeconds)
    }

    @Test
    fun `换号上限能拦住 0（0 等于一次都不换）`() {
        assertEquals(
            GatewaySystemSettings.MIN_SWITCHES,
            GatewaySystemSettingsRules.normalize(GatewaySystemSettings(maxSwitches = 0)).maxSwitches,
        )
    }
}
