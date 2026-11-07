package dev.praxis.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * compose_dsl 渲染树解析。
 *
 * ## 契约出处
 *
 * Operit 的 ToolPkgComposeDslParser：脚本返回 { tree, state, memo }，
 * tree 是 { type, props, children, slots }。children 接受数组或单对象，
 * slots 是「具名插槽 → 节点」。
 *
 * ## 守的是什么
 *
 * 真机上解析错了只表现为「页面一片空白」，看不出是脚本的错还是宿主的错。
 * 所以字段映射、结构体丢弃、未知类型区分都要在单测里锁住。
 */
class ComposeDslParserTest {

    @Test
    fun 能解析出树与状态() {
        val raw = """
            {
              "tree": {
                "type": "Column",
                "props": { "spacing": "8" },
                "children": [
                  { "type": "Text", "props": { "text": "你好" } }
                ]
              },
              "state": { "count": "1" },
              "memo": { "k": "v" }
            }
        """.trimIndent()

        val result = ComposeDslParser.parse(raw).getOrThrow()

        assertEquals("Column", result.tree.type)
        assertEquals("8", result.tree.prop("spacing"))
        assertEquals(1, result.tree.children.size)
        assertEquals("你好", result.tree.children[0].prop("text"))
        assertEquals("1", result.state["count"])
        assertEquals("v", result.memo["k"])
    }

    @Test
    fun 单对象_children_也接受() {
        val raw = """
            { "tree": { "type": "Box", "children": { "type": "Text", "props": { "text": "x" } } } }
        """.trimIndent()

        val node = ComposeDslParser.parse(raw).getOrThrow().tree

        assertEquals(1, node.children.size)
        assertEquals("Text", node.children[0].type)
    }

    @Test
    fun 具名插槽被解析() {
        val raw = """
            {
              "tree": {
                "type": "Scaffold",
                "slots": { "topBar": [ { "type": "Text", "props": { "text": "标题" } } ] }
              }
            }
        """.trimIndent()

        val node = ComposeDslParser.parse(raw).getOrThrow().tree

        assertEquals(1, node.slots["topBar"]!!.size)
        assertEquals("标题", node.slots["topBar"]!![0].prop("text"))
    }

    @Test
    fun modifier_单独保留其余结构体丢弃() {
        // 布局信息全在 modifier 里（padding / fillMaxWidth）：丢掉等于
        // 「所有组件按默认尺寸画」，用户看到的是「我写的 padding 没生效」。
        // 其余嵌套对象仍丢弃 —— 硬转成字符串只会让渲染器拿到无法解释的文本。
        val raw = """
            {
              "tree": {
                "type": "Text",
                "props": {
                  "text": "x",
                  "modifier": { "padding": 8, "fillMaxWidth": true, "inner": { "a": 1 } },
                  "list": [1, 2]
                }
              }
            }
        """.trimIndent()

        val node = ComposeDslParser.parse(raw).getOrThrow().tree

        assertEquals("x", node.prop("text"))
        assertNull("modifier 不该混进标量表", node.prop("modifier"))
        assertNull("数组不该进来", node.prop("list"))
        assertEquals(8, node.paddingDp)
        assertTrue(node.fillMaxWidth)
        assertNull("modifier 里更深的结构体丢弃，不猜", node.modifier["inner"])
    }

    @Test
    fun 间距与内边距取值() {
        val raw = """
            {
              "tree": {
                "type": "Column",
                "props": { "spacing": "12" },
                "children": [
                  { "type": "Text", "props": { "text": "a", "modifier": { "padding": "4" } } }
                ]
              }
            }
        """.trimIndent()

        val node = ComposeDslParser.parse(raw).getOrThrow().tree

        assertEquals(12, node.spacingDp)
        assertEquals(4, node.children[0].paddingDp)
        // 没写就是 null，不是 0：渲染器据此决定「不加 padding」，
        // 而不是加一个 0dp 的修饰符把默认值覆盖掉。
        assertNull(node.paddingDp)
    }

    @Test
    fun 动作_id_三种写法都认() {
        val direct = """
            { "tree": { "type": "TextButton", "props": { "__actionId": "a1" } } }
        """.trimIndent()
        assertEquals("a1", ComposeDslParser.parse(direct).getOrThrow().tree.actionId)

        val prefixed = """
            { "tree": { "type": "TextButton", "props": { "action": "__action:a2" } } }
        """.trimIndent()
        assertEquals("a2", ComposeDslParser.parse(prefixed).getOrThrow().tree.actionId)

        val bare = """
            { "tree": { "type": "TextButton", "props": { "action": "a3" } } }
        """.trimIndent()
        assertEquals("a3", ComposeDslParser.parse(bare).getOrThrow().tree.actionId)
    }

    @Test
    fun 缺_tree_或_type_为空时失败() {
        assertTrue(
            ComposeDslParser.parse("""{"state":{}}""").isFailure,
        )
        assertTrue(
            ComposeDslParser.parse("""{"tree":{"type":""}}""").isFailure,
        )
        assertTrue(
            ComposeDslParser.parse("not json").isFailure,
        )
    }

    @Test
    fun 未知组件与未实现组件要能区分() {
        // 「Operit 认识但本轮没实现」和「根本不是组件」是两回事：
        // 前者是功能缺口，后者是脚本写错了，提示措辞不该一样。
        assertTrue(ComposeDslComponents.isRenderable("Column"))
        assertTrue(ComposeDslComponents.isKnown("Scaffold"))
        assertTrue("Scaffold 认识但还没实现", !ComposeDslComponents.isRenderable("Scaffold"))
        assertTrue("Hologram 根本不在组件表里", !ComposeDslComponents.isKnown("Hologram"))
    }
}
