package dev.praxis.core.model.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键帽标签规范化的单测。
 *
 * ## 为什么这一层必须严格
 *
 * `sanitize` 是「脏数据」与「UI 布局」之间唯一的闸门。它放过去的每一个
 * 畸形值都会以极难诊断的方式表现出来：
 *
 *  - 数量不对 → `weight(1f)` 那一排对不齐，看起来像「键盘歪了」；
 *  - 空标签 → 一个看不见的键帽（点了有反应但没字），像幽灵按钮；
 *  - 多行标签 → 单格变高，整排被顶歪；
 *  - 超长标签 → 键帽文字溢出/被截成「…」，用户以为自己的字被吃了。
 */
class VirtualKeyboardLayoutCodecTest {

    // ── 默认值 ────────────────────────────────────────────────

    @Test
    fun `默认标签数量等于行列乘积`() {
        assertEquals(
            VirtualKeyboardLayoutCodec.ROW_COUNT * VirtualKeyboardLayoutCodec.COLUMN_COUNT,
            VirtualKeyboardLayoutCodec.DEFAULT_LABELS.size,
        )
    }

    @Test
    fun `默认标签无空白项`() {
        // 默认布局里出现空标签意味着某个键从出生起就是不可见的。
        VirtualKeyboardLayoutCodec.DEFAULT_LABELS.forEach { label ->
            assertTrue("默认标签不应为空", label.isNotBlank())
        }
    }

    @Test
    fun `默认值自身是默认布局`() {
        assertTrue(VirtualKeyboardLayoutCodec.isDefault(VirtualKeyboardLayoutCodec.DEFAULT_LABELS))
    }

    // ── 数量校验 ──────────────────────────────────────────────

    @Test
    fun `数量不足整体回退默认`() {
        // 13 格布局会让第一排第二排各少一个，`weight` 分配全变。
        // 半修（补一格）比整体回退更让人困惑，所以选择整体回退。
        val short = List(13) { "X" }
        assertEquals(
            VirtualKeyboardLayoutCodec.DEFAULT_LABELS,
            VirtualKeyboardLayoutCodec.sanitize(short),
        )
    }

    @Test
    fun `数量过多整体回退默认`() {
        val long = List(15) { "X" }
        assertEquals(
            VirtualKeyboardLayoutCodec.DEFAULT_LABELS,
            VirtualKeyboardLayoutCodec.sanitize(long),
        )
    }

    @Test
    fun `空列表回退默认`() {
        // DataStore 里读到空串时会 split 成 [""]，但 null 分支会直接给默认。
        // 这里锁的是「显式传空列表」也不该产生 0 格键盘。
        assertEquals(
            VirtualKeyboardLayoutCodec.DEFAULT_LABELS,
            VirtualKeyboardLayoutCodec.sanitize(emptyList()),
        )
    }

    // ── 逐格清洗 ──────────────────────────────────────────────

    @Test
    fun `空白标签回退到该格默认值`() {
        val labels = VirtualKeyboardLayoutCodec.DEFAULT_LABELS.toMutableList()
        labels[3] = ""      // 第 4 格（HOME）
        labels[8] = "   "   // 第 9 格（CTRL），纯空格

        val result = VirtualKeyboardLayoutCodec.sanitize(labels)

        assertEquals("HOME", result[3])
        assertEquals("CTRL", result[8])
    }

    @Test
    fun `超长标签被截断到上限`() {
        val labels = VirtualKeyboardLayoutCodec.DEFAULT_LABELS.toMutableList()
        labels[0] = "这是一个非常长的标签文字"

        val result = VirtualKeyboardLayoutCodec.sanitize(labels)

        assertEquals(
            VirtualKeyboardLayoutCodec.MAX_LABEL_LENGTH,
            result[0].length,
        )
    }

    @Test
    fun `标签内的换行与制表符被替换为空格`() {
        // 多行标签会让该键帽变高，而 `Row` 高度取子项最大值 ——
        // 结果是整排键都被顶歪。
        //
        // 索引 0 = ESC，索引 1 = "/"。用这两个而不是别的位置，
        // 是为了让断言里的期望值一眼可读（不必去数 14 个格子）。
        val labels = VirtualKeyboardLayoutCodec.DEFAULT_LABELS.toMutableList()
        labels[0] = "a\nb"
        labels[1] = "c\td"

        val result = VirtualKeyboardLayoutCodec.sanitize(labels)

        assertFalse("不应残留换行", result[0].contains('\n'))
        assertFalse("不应残留制表", result[1].contains('\t'))
        assertEquals("a b", result[0])
        assertEquals("c d", result[1])
    }

    @Test
    fun `正常自定义标签原样保留`() {
        val labels = VirtualKeyboardLayoutCodec.DEFAULT_LABELS.toMutableList()
        labels[0] = "退出"
        labels[10] = "上"

        val result = VirtualKeyboardLayoutCodec.sanitize(labels)

        assertEquals("退出", result[0])
        assertEquals("上", result[10])
        // 未改的格子必须保持默认（不能因为 mapIndexed 出错而整排变空）。
        assertEquals(VirtualKeyboardLayoutCodec.DEFAULT_LABELS[5], result[5])
    }

    @Test
    fun `恰好等于上限长度的标签不被截断`() {
        // 边界：`> MAX` 才截断，`== MAX` 应原样保留。
        val exact = "A".repeat(VirtualKeyboardLayoutCodec.MAX_LABEL_LENGTH)
        val labels = VirtualKeyboardLayoutCodec.DEFAULT_LABELS.toMutableList()
        labels[0] = exact

        assertEquals(exact, VirtualKeyboardLayoutCodec.sanitize(labels)[0])
    }

    // ── isDefault ─────────────────────────────────────────────

    @Test
    fun `任何一格不同都不算默认`() {
        val labels = VirtualKeyboardLayoutCodec.DEFAULT_LABELS.toMutableList()
        labels[6] = "上翻"

        assertFalse(VirtualKeyboardLayoutCodec.isDefault(labels))
    }

    @Test
    fun `长度不同的列表不算默认`() {
        // 防止 `isDefault` 用 `startsWith` 之类实现导致 13 格被判为默认。
        assertFalse(VirtualKeyboardLayoutCodec.isDefault(List(13) { "X" }))
    }
}