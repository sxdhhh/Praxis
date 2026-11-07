package dev.praxis.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件入口（对齐 Operit 的运行时注册载荷）。
 *
 * ## 用例形状取自真实插件
 *
 * examples/sidebar_sillytavern 先 registerUiRoute 建一个页面，再
 * registerNavigationEntry 把它挂到 main_sidebar_plugins 上。下面的 JSON
 * 就是这两个调用的声明式副本 —— 字段名必须和那份载荷一致，否则「兼容」
 * 只是一句口号。
 *
 * ## 守的是什么
 *
 * 「兼容插件入口」页列的就是这些。解析悄悄丢一个入口，用户看到的是
 * 「装了包但页面是空的」，且没有任何报错 —— 最难自查的一类故障。
 * 所以每个失败路径都必须留下警告，而不是静默跳过。
 */
class PluginEntryPointTest {

    private fun manifestOf(json: String, exists: (String) -> Boolean = { true }): PluginManifest {
        val parsed = ManifestParser.parse(json).getOrThrow()
        return ManifestMapper.map(parsed, exists).manifest
    }

    /** 与真实插件同构：一个页面 + 一个挂到侧边栏的导航项。 */
    private val sidebarStyle = """
        {
          "toolpkg_id": "com.operit.sidebar_demo",
          "main": "dist/main.js",
          "entries": [
            {
              "id": "demo_dashboard",
              "kind": "ui_route",
              "runtime": "compose_dsl",
              "screen": "dist/ui/demo/index.ui.js",
              "keepAlive": true,
              "title": {"zh": "面板", "en": "Dashboard"}
            },
            {
              "id": "demo_sidebar",
              "kind": "navigation",
              "surface": "main_sidebar_plugins",
              "route": "toolpkg:com.operit.sidebar_demo:ui:demo_dashboard",
              "icon": "chat",
              "order": 122,
              "screen": "dist/ui/demo/index.ui.js",
              "title": {"zh": "面板", "en": "Dashboard"}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun 页面入口解析出运行时与保活() {
        val m = manifestOf(sidebarStyle)
        val route = m.entries.first { it.id == "demo_dashboard" }

        assertEquals(PluginEntryPoint.Kind.UiRoute, route.kind)
        assertEquals("compose_dsl", route.runtime)
        assertEquals("dist/ui/demo/index.ui.js", route.screen)
        assertTrue("keepAlive 要收", route.keepAlive)
        // 纯路由没有挂载面。
        assertNull(route.surface)
        // 多语言：中文走 zh，英文走 en。
        assertEquals("面板", route.title.resolveZh())
        assertEquals("Dashboard", route.title.resolve("en"))
    }

    @Test
    fun 导航入口解析出挂载面与图标() {
        val m = manifestOf(sidebarStyle)
        val nav = m.entries.first { it.id == "demo_sidebar" }

        assertEquals(PluginEntryPoint.Kind.Navigation, nav.kind)
        assertEquals(EntrySurfaces.MAIN_SIDEBAR_PLUGINS, nav.surface)
        assertEquals("侧边栏", EntrySurfaces.labelOf(nav.surface))
        assertEquals("toolpkg:com.operit.sidebar_demo:ui:demo_dashboard", nav.routeId)
        assertEquals("chat", nav.icon)
        assertEquals(122, nav.order)
    }

    @Test
    fun route_缺省时按_Operit_规则生成() {
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "entries": [
                {"id": "panel", "kind": "ui_route", "screen": "ui/panel.js"}
              ]
            }
        """.trimIndent()
        val e = manifestOf(json).entries.single()
        // 与 Operit 的 buildToolPkgRouteId 同构，插件跳转时不会找不到页面。
        assertEquals("toolpkg:demo.pkg:ui:panel", e.routeId)
    }

    @Test
    fun 未知类型被跳过并记警告() {
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "entries": [
                {"id": "x", "kind": "hologram", "screen": "ui/x.js"}
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
                {"id": "nav1", "kind": "navigation", "screen": "ui/missing.js"}
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
                {"id": "nav1", "kind": "ui_route", "screen": "ui/nav.js"}
              ]
            }
        """.trimIndent()
        val e = manifestOf(json).entries.single()
        assertEquals("nav1", e.id)
    }

    @Test
    fun 同包重名只留第一个() {
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "entries": [
                {"id": "dup", "kind": "ui_route", "title": "第一个", "screen": "a.js"},
                {"id": "dup", "kind": "toolbox", "title": "第二个", "screen": "b.js"}
              ]
            }
        """.trimIndent()
        val m = manifestOf(json)
        assertEquals(1, m.entries.size)
        assertEquals("第一个", m.entries[0].title.resolveZh())
    }

    @Test
    fun keep_alive_的两种写法都收() {
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "entries": [
                {"id": "a", "kind": "ui_route", "screen": "a.js", "keepAlive": true},
                {"id": "b", "kind": "ui_route", "screen": "b.js", "keep_alive": true},
                {"id": "c", "kind": "ui_route", "screen": "c.js"}
              ]
            }
        """.trimIndent()
        val m = manifestOf(json)
        assertTrue(m.entries.first { it.id == "a" }.keepAlive)
        assertTrue("snake_case 也要认", m.entries.first { it.id == "b" }.keepAlive)
        assertFalse(m.entries.first { it.id == "c" }.keepAlive)
    }

    @Test
    fun 聚合按权重排序并标记可用性() {
        val enabled = manifestOf(sidebarStyle)
        val disabled = manifestOf(
            """
            {
              "toolpkg_id": "other.pkg",
              "main": "main.js",
              "entries": [
                {"id": "z", "kind": "navigation", "surface": "toolbox", "route": "toolpkg:other.pkg:ui:z", "title": "停用包的入口", "screen": "ui/z.js", "order": 1}
              ]
            }
            """.trimIndent(),
        )
        val installed = listOf(
            InstalledPlugin(manifest = enabled, installPath = "/p/demo", enabled = true),
            InstalledPlugin(manifest = disabled, installPath = "/p/other", enabled = false),
        )

        val points = installed.collectEntryPoints()

        assertEquals(3, points.size)
        // 排序键是 order：z=1，两个 demo 入口都是默认 0。
        val orders = points.map { it.point.order }
        assertEquals("必须按 order 升序", orders.sorted(), orders)
        assertEquals("order=122 的排最后", "demo_sidebar", points.last().point.id)
        // 停用包里的入口仍然列出 —— 用户要能看见「装了但没生效」。
        assertFalse(points.first { it.point.id == "z" }.enabled)
        assertTrue(points.first { it.point.id == "demo_sidebar" }.enabled)
        // key 跨包唯一，Compose 列表拿它做动画。
        assertEquals("com.operit.sidebar_demo/demo_sidebar", points.first { it.point.id == "demo_sidebar" }.key)
    }

    // ── action：不跳页面、点一下执行函数（Operit 的 ToolPkgNavigationActionHookRuntime） ──

    @Test
    fun `action 字符串写法`() {
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "entries": [
                {"id": "act", "kind": "navigation", "surface": "toolbox", "action": "onClick"}
              ]
            }
        """.trimIndent()
        val e = manifestOf(json).entries.single()

        assertEquals("onClick", e.action!!.function)
        // 纯 action 入口不跳页面，因此没有路由 —— 生成一个会让界面显示点不开的跳转目标。
        assertNull(e.routeId)
        assertTrue("有 action 就算能用", e.hasTarget)
        assertEquals("执行 onClick()", e.targetLabel())
    }

    @Test
    fun `action 对象写法带函数源码`() {
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "entries": [
                {
                  "id": "act", "kind": "navigation", "surface": "toolbox",
                  "action": {"function": "onClick", "function_source": "function onClick() {}"}
                }
              ]
            }
        """.trimIndent()
        val e = manifestOf(json).entries.single()

        assertEquals("onClick", e.action!!.function)
        assertEquals("function onClick() {}", e.action!!.functionSource)
    }

    @Test
    fun `导航入口缺 surface 会被跳过并记警告`() {
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "entries": [
                {"id": "nav", "kind": "navigation", "screen": "ui/nav.js"}
              ]
            }
        """.trimIndent()
        val parsed = ManifestParser.parse(json).getOrThrow()
        val mapped = ManifestMapper.map(parsed)

        assertTrue("没有 surface 就不知道该挂哪", mapped.manifest.entries.isEmpty())
        assertTrue(mapped.warnings.any { it.field.contains("surface") })
    }

    @Test
    fun `导航入口 route 与 action 都没有会被跳过`() {
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "entries": [
                {"id": "nav", "kind": "navigation", "surface": "toolbox"}
              ]
            }
        """.trimIndent()
        val parsed = ManifestParser.parse(json).getOrThrow()
        val mapped = ManifestMapper.map(parsed)

        assertTrue("点了没反应的入口不该进列表", mapped.manifest.entries.isEmpty())
        assertTrue(mapped.warnings.any { it.message.contains("route") || it.message.contains("action") })
    }

    @Test
    fun `params 不被消费所以不建模`() {
        // Operit 的 registerUiRoute 载荷里有 params，但它的解析器只读
        // id/screen/route/runtime/title/keepAlive —— params 不被宿主消费。
        // 这里显式锁住「写进来也不会产生字段」，避免以后有人以为它生效了。
        val json = """
            {
              "toolpkg_id": "demo.pkg",
              "main": "main.js",
              "entries": [
                {"id": "p", "kind": "ui_route", "screen": "ui/p.js", "params": {"x": 1}}
              ]
            }
        """.trimIndent()
        val m = manifestOf(json)
        assertEquals(1, m.entries.size)
        assertTrue("params 是透明字段", m.entries[0].hasTarget)
    }

    // ── 运行时捕获与清单声明的合并（R3） ──

    private fun runtimePoint(pluginId: String, id: String): PluginEntryPoint = PluginEntryPoint(
        id = id,
        kind = PluginEntryPoint.Kind.UiRoute,
        routeId = PluginEntryPoint.routeIdOf(pluginId, id),
        screen = "ui/" + id + ".js",
        title = LocalizedText.ofZh(id),
    )

    @Test
    fun `没有捕获时用清单并标注来源`() {
        val plugin = InstalledPlugin(
            manifest = manifestOf(sidebarStyle),
            installPath = "/p/demo",
            enabled = true,
        )

        val points = listOf(plugin).collectEntryPoints()

        assertEquals(2, points.size)
        assertTrue(
            "没有运行时捕获时，来源应当是清单",
            points.all { it.origin == EntryOrigin.Manifest },
        )
    }

    @Test
    fun `有捕获时整体以捕获为准`() {
        val plugin = InstalledPlugin(
            manifest = manifestOf(sidebarStyle),
            installPath = "/p/demo",
            enabled = true,
        )
        val captured = mapOf(plugin.id to listOf(runtimePoint(plugin.id, "only_runtime")))

        val points = listOf(plugin).collectEntryPoints(captured)

        // 包一旦跑起来，它注册的东西就是唯一真相：清单里那两条不该再出现。
        assertEquals("清单条目必须被整体让位", 1, points.size)
        assertEquals("only_runtime", points[0].point.id)
        assertEquals(EntryOrigin.Runtime, points[0].origin)
    }

    @Test
    fun `捕获到的入口同样受启停影响`() {
        val plugin = InstalledPlugin(
            manifest = manifestOf(sidebarStyle),
            installPath = "/p/demo",
            enabled = false,
        )
        val captured = mapOf(plugin.id to listOf(runtimePoint(plugin.id, "panel")))

        val point = listOf(plugin).collectEntryPoints(captured).single()

        assertEquals(EntryOrigin.Runtime, point.origin)
        assertFalse("包停用了，入口必须标为不可用", point.enabled)
    }

    @Test
    fun `未安装包的捕获被忽略`() {
        val plugin = InstalledPlugin(
            manifest = manifestOf(sidebarStyle),
            installPath = "/p/demo",
            enabled = true,
        )
        // 捕获表里有别的包（例如已卸载但运行时还没释放）——不能出现在列表里。
        val captured = mapOf("gone.pkg" to listOf(runtimePoint("gone.pkg", "ghost")))

        val points = listOf(plugin).collectEntryPoints(captured)

        assertEquals(2, points.size)
        assertTrue(points.none { it.point.id == "ghost" })
    }
}
