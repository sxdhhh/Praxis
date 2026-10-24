package dev.praxis.core.model.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型池语义的回归护栏（2026-10-24 引入，对齐 Operit 的 `modelName` + `modelIndex`）。
 *
 * ## 为什么这些测试值得写
 *
 * 本轮把 `ModelSettings.model` 从「单个模型名」改成「逗号分隔的模型池」
 * 之后，**最容易出的错不是 UI，而是取值**：一旦哪个调用点仍然直接用
 * `settings.model`，就会把 `"a,b,c"` 整串发给服务端 —— 那是一个
 * 必然 400 的请求，而且报错信息（model not found）指不到根因上。
 *
 * 这些断言把三件事钉住：
 *  1. 切分规则（含脏输入）；
 *  2. [ModelSettings.activeModel] 的取值与**越界兜底**；
 *  3. 拼接顺序与去重（`joinModelList`）。
 */
class ModelSettingsTest {

    // ── 切分 ──────────────────────────────────────────────────

    @Test
    fun `parse single model`() {
        assertEquals(listOf("gpt-4o-mini"), parseModelList("gpt-4o-mini"))
    }

    @Test
    fun `parse multiple models keeps order`() {
        assertEquals(
            listOf("a", "b", "c"),
            parseModelList("a,b,c"),
        )
    }

    @Test
    fun `parse trims whitespace around each entry`() {
        // 用户手填时很容易写成 "a, b , c"。
        assertEquals(listOf("a", "b", "c"), parseModelList(" a , b , c "))
    }

    @Test
    fun `parse drops empty entries`() {
        // `a,,b` 与尾随逗号都是手填时的常见输入。若不丢空项，
        // 会产生空字符串模型名，发出去就是 `model: ""` → 400。
        assertEquals(listOf("a", "b"), parseModelList("a,,b"))
        assertEquals(listOf("a", "b"), parseModelList("a,b,"))
    }

    @Test
    fun `parse empty string returns empty list`() {
        assertEquals(emptyList<String>(), parseModelList(""))
        // 全空白也应得到空列表（而不是 [""]）。
        assertEquals(emptyList<String>(), parseModelList("   "))
        assertEquals(emptyList<String>(), parseModelList(" , , "))
    }

    // ── 取值 ──────────────────────────────────────────────────

    @Test
    fun `active model picks by index`() {
        val s = ModelSettings(model = "a,b,c", modelIndex = 1)
        assertEquals("b", s.activeModel)
        assertEquals(listOf("a", "b", "c"), s.modelList)
    }

    @Test
    fun `active model index zero is first`() {
        assertEquals("a", ModelSettings(model = "a,b,c", modelIndex = 0).activeModel)
    }

    @Test
    fun `active model falls back to first when index out of range`() {
        // 越界回落第 0 个（与 Operit `getValidModelIndex` 同口径）。
        // 场景：用户删掉了池里最后一个模型，索引暂时还指向它。
        assertEquals("a", ModelSettings(model = "a,b,c", modelIndex = 9).activeModel)
        // 负数同样回落。
        assertEquals("a", ModelSettings(model = "a,b,c", modelIndex = -3).activeModel)
    }

    @Test
    fun `active model is empty when pool is empty`() {
        // 空池不该返回 "a" 之类的东西，也不该崩。
        assertEquals("", ModelSettings(model = "", modelIndex = 0).activeModel)
        assertEquals("", ModelSettings(model = "  ", modelIndex = 2).activeModel)
    }

    @Test
    fun `single legacy model still works`() {
        // 老用户的库里有单个模型、没有 model_index。
        // `model_index` 缺失时默认 0，所以这里必须原样返回那一个模型名。
        val legacy = ModelSettings(model = "deepseek-chat")
        assertEquals("deepseek-chat", legacy.activeModel)
        assertEquals(0, legacy.modelIndex)
    }

    @Test
    fun `isConfigured uses active model not raw pool`() {
        // 关键：`isConfigured` 以前判 `model.isNotBlank()`。
        // 若池子是 "a,b,c" 而 `activeModel` 为空（不可能，但语义上要保持
        // 一致），就不该报「已配置」。这里验证正向与空池两种情况。
        assertTrue(ModelSettings(baseUrl = "https://x", model = "a,b").isConfigured)
        assertFalse(ModelSettings(baseUrl = "https://x", model = "").isConfigured)
        assertFalse(ModelSettings(baseUrl = "", model = "a").isConfigured)
    }

    // ── 拼接 ──────────────────────────────────────────────────

    @Test
    fun `join keeps given order`() {
        // 顺序由调用方（弹窗）按服务端列表排好，这里只负责拼。
        assertEquals("a,b,c", joinModelList(listOf("a", "b", "c")))
        assertEquals("c,a,b", joinModelList(listOf("c", "a", "b")))
    }

    @Test
    fun `join trims and drops empties`() {
        assertEquals("a,b", joinModelList(listOf(" a ", "b", "")))
    }

    @Test
    fun `join deduplicates keeping first occurrence`() {
        // 去重必须"保留第一次出现的位置"，否则索引会漂移。
        assertEquals("a,b", joinModelList(listOf("a", "b", "a")))
    }

    @Test
    fun `join empty list gives empty string`() {
        assertEquals("", joinModelList(emptyList()))
    }

    @Test
    fun `roundtrip join then parse`() {
        // 拼接后再解析必须回到同一序列 —— 这是「保存后重开设置页」的路径。
        val picked = listOf("gpt-4o", "gpt-4o-mini", "o3")
        assertEquals(picked, parseModelList(joinModelList(picked)))
    }
}