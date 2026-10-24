package dev.praxis.core.model.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功能级模型覆盖的解析语义。
 *
 * ## 为什么这套 fallback 必须单测
 *
 * `resolve` 看起来只是「空则取全局」，但它决定的是**真实发出去的模型名**。
 * 写错一处的症状是「某个功能悄悄用了错误的模型」—— 界面上看不出来，
 * 只在账单或回答质量上体现。这类 bug 用眼睛读代码很难发现，必须锁住。
 */
class FunctionModelSettingsTest {

    private val global = ModelSettings(
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat",
        temperature = 0.5,
        provider = ApiProvider.DeepSeek,
    )

    @Test
    fun `默认全部跟随全局`() {
        val settings = FunctionModelSettings()
        FunctionSlot.entries.forEach { slot ->
            val resolved = settings.resolve(slot, global)
            assertEquals(global.model, resolved.model)
            assertEquals(global.baseUrl, resolved.baseUrl)
            assertEquals(global.provider, resolved.provider)
            assertFalse(resolved.isOverridden)
        }
    }

    @Test
    fun `覆盖模型后只换模型地址服务商仍跟随全局`() {
        val settings = FunctionModelSettings().with(
            FunctionSlot.TITLE,
            FunctionModelOverride(model = "qwen-turbo"),
        )
        val resolved = settings.resolve(FunctionSlot.TITLE, global)
        assertEquals("qwen-turbo", resolved.model)
        // 只覆盖模型时，地址与服务商不能被顺手改掉：
        // 否则会拼出「deepseek 地址 + qwen 模型」的必然 404 组合。
        assertEquals(global.baseUrl, resolved.baseUrl)
        assertEquals(global.provider, resolved.provider)
        assertTrue(resolved.isOverridden)
        // 温度始终跟全局：功能级没有单独的温度字段（见 FunctionModelOverride 的说明）。
        // Double 断言必须给 delta：JUnit 的 assertEquals(Object, Object)
        // 对装箱 Double 走的是 equals，而 0.5 经过一层 copy 后语义上仍应精确相等 ——
        // 用 0.0 delta 表示「必须精确相同」，比放宽到 1e-9 更严格。
        assertEquals(global.temperature, resolved.temperature, 0.0)
    }

    @Test
    fun `覆盖服务商时地址一起换`() {
        val settings = FunctionModelSettings().with(
            FunctionSlot.CONTEXT_SUMMARY,
            FunctionModelOverride(
                model = "qwen-turbo",
                provider = ApiProvider.Aliyun,
                baseUrl = ApiProvider.Aliyun.defaultEndpoint,
            ),
        )
        val resolved = settings.resolve(FunctionSlot.CONTEXT_SUMMARY, global)
        assertEquals("qwen-turbo", resolved.model)
        assertEquals(ApiProvider.Aliyun, resolved.provider)
        assertEquals(ApiProvider.Aliyun.defaultEndpoint, resolved.baseUrl)
    }

    @Test
    fun `清空覆盖后回到跟随全局`() {
        val overridden = FunctionModelSettings().with(
            FunctionSlot.CHAT,
            FunctionModelOverride(model = "gpt-4o"),
        )
        assertTrue(overridden[FunctionSlot.CHAT].isOverridden)

        val reset = overridden.with(FunctionSlot.CHAT, FunctionModelOverride())
        assertFalse(reset[FunctionSlot.CHAT].isOverridden)
        assertEquals(global.model, reset.resolve(FunctionSlot.CHAT, global).model)
        // 存储里不留空壳对象：这样「跟随全局」只有一种表示。
        assertTrue(reset.overrides.isEmpty())
    }

    @Test
    fun `全局未配置模型时解析结果为空`() {
        val blank = ModelSettings()
        val resolved = FunctionModelSettings().resolve(FunctionSlot.CHAT, blank)
        assertEquals("", resolved.model)
        assertNull(blank.model.ifBlank { null })
    }

    @Test
    fun `功能位 key 唯一且可按 key 查回`() {
        val keys = FunctionSlot.entries.map { it.key }
        assertEquals("key 必须唯一，否则存储会互相覆盖", keys.size, keys.toSet().size)
        FunctionSlot.entries.forEach { slot ->
            assertEquals(slot, FunctionSlot.byKey(slot.key))
        }
        assertNull(FunctionSlot.byKey("不存在的 key"))
        assertNull(FunctionSlot.byKey(null))
    }
}