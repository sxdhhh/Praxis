package dev.praxis.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件入口（照搬 Operit 的 registerToolPkg* 四个注册面）。
 *
 * ## 守的是什么
 *
 * 「兼容插件入口」这个页面列的就是这些东西。如果解析悄悄丢了一个入口，
 * 用户看到的是「装了包但页面里是空的」，而没有任何报错 ——
 * 这正是最难自查的一类故障。
 *
 * 所以每个失败路径都必须**留下警告**，而不是静默跳过。
 */
class PluginEntryPointTest {

    private fun manifestOf(json: String, exists: (String) -> Boolean = { true }): PluginManifest {
        val parsed = ManifestParser.parse(json).getOrThrow()
        return ManifestMapper.map(parsed, exists).manifest
    }

    private val fourKinds = """
        {
          "toolpkg_id": "demo.pkg",
          "main": "main.js",
          "entries": [
            {"id": "nav1", "kind": "navigation", "title": "入口一", "entry": "ui/nav.js", "order": 5},
            {"id": "page1", "kind": "ui_route", "title": {"zh": "页面一", "en": "Page One"}, "entry": "ui/page.js"},
            {"id": "box1", "kind": "toolbox", "title": "工具箱卡片", "entry": "ui/box.js"},
            {"id": "w1", "kind": "widget", "title": "桌面组件", "entry": "ui/widget.js"}
          ]
        }
    """.trimIndent()

    @Test
    fun 四种入口都能解析() {
        val m = manifestOf(fourKinds)
        assertEquals(4, m.entries.size)
        assertEquals(
            listOf(
                PluginEntryPoint.Kind.Navigation,
                PluginEntryPoint.Kind.UiRoute,
                PluginEntryPoint.Kind.Toolbox,
                PluginEntryPoint.Kind.DesktopWidget,
            ),
            m.entries.map { it.kind },
        )
        assertEquals("入口一", m.entries[0].title.resolveZh())
        assertEquals(5, m.entries[0].order)
        // 多语言取值：中文走 zh，英文走 en。
        assertEquals("页面一", m.entries[1].title.resolveZh())
        assertEquals("Page One", m.entries[1].title.resolve("en"))
    }

    @Test
    fun 未知类型被跳过并记警告() {
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "entries": [
                {"id": "x", "kind": "hologram", "title": "未来入口", "entry": "ui/x.js"}
              ]
            }
        """.trimIndent()
        val parsed = ManifestParser.parse(json).getOrThrow()
        val mapped = ManifestMapper.map(parsed)

        assertTrue("未知类型不该进清单", mapped.manifest.entries.isEmpty())
        assertTrue(
            "必须留下警告，否则用户不知道入口为什么没出现",
            mapped.warnings.any { it.field.contains("kind") },
        )
    }

    @Test
    fun 入口脚本不存在只记警告而不是致命() {
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "entries": [
                {"id": "nav1", "kind": "navigation", "title": "入口", "entry": "ui/missing.js"}
              ]
            }
        """.trimIndent()
        val parsed = ManifestParser.parse(json).getOrThrow()
        val mapped = ManifestMapper.map(parsed) { relative -> relative != "ui/missing.js" }

        assertTrue("整包仍应可用", mapped.isUsable)
        assertTrue("这个入口不该进列表", mapped.manifest.entries.isEmpty())
        assertTrue(
            "警告里要说清是哪个入口的脚本不存在",
            mapped.warnings.any { it.message.contains("ui/missing.js") },
        )
    }

    @Test
    fun ui_entries_是_entries_的别名() {
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "ui_entries": [
                {"id": "nav1", "kind": "navigation", "title": "入口", "entry": "ui/nav.js"}
              ]
            }
        """.trimIndent()
        val m = manifestOf(json)
        assertEquals(1, m.entries.size)
        assertEquals("nav1", m.entries[0].id)
    }

    @Test
    fun 同包重名只留第一个() {
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "entries": [
                {"id": "dup", "kind": "navigation", "title": "第一个", "entry": "a.js"},
                {"id": "dup", "kind": "toolbox", "title": "第二个", "entry": "b.js"}
              ]
            }
        """.trimIndent()
        val m = manifestOf(json)
        assertEquals(1, m.entries.size)
        assertEquals("第一个", m.entries[0].title.resolveZh())
    }

    @Test
    fun 聚合按权重排序并标记可用性() {
        val enabledManifest = manifestOf(fourKinds)
        val disabledManifest = manifestOf(
            """
            {
              "toolpkg_id": "other.pkg",
              "main": "main.js",
              "entries": [
                {"id": "z", "kind": "navigation", "title": "停用包的入口", "entry": "ui/z.js", "order": 1}
              ]
            }
            """.trimIndent(),
        )
        val installed = listOf(
            InstalledPlugin(
                manifest = enabledManifest,
                installPath = "/p/demo",
                enabled = true,
            ),
            InstalledPlugin(
                manifest = disabledManifest,
                installPath = "/p/other",
                enabled = false,
            ),
        )

        val points = installed.collectEntryPoints()

        assertEquals(5, points.size)
        // 排序键是 order，其次包 id、入口 id。
        // 默认 order 是 0，所以 order=5 的 nav1 排在最后。
        val orders = points.map { it.point.order }
        assertEquals("必须按 order 升序", orders.sorted(), orders)
        assertEquals("nav1", points.last().point.id)
        // 停用包里的入口仍然列出 —— 用户要能看见「装了但没生效」。
        assertFalse("停用包里的入口要标出来", points.first { it.point.id == "z" }.enabled)
        assertTrue(points.first { it.point.id == "nav1" }.enabled)
        // key 跨包唯一，Compose 列表拿它做 key。
        assertEquals("demo.pkg/nav1", points.first { it.point.id == "nav1" }.key)
    }
}
