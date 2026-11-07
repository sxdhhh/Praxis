package dev.praxis.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本注册结果的解析。
 *
 * ## 守的是什么
 *
 * WebView 运行时只能在真机/模拟器上跑，而「注册结果怎么变成模型」是纯逻辑。
 * 抽成纯函数之后，字段映射与容错都能在这里锁住 —— 否则插件注册出问题时
 * 设备上只有一句「没反应」，无从排查。
 *
 * JSON 形状由 [PluginRuntimeBootstrapScript] 决定，所以这些用例同时也是
 * 引导脚本的契约测试。
 */
class PluginRegistrationCodecTest {

    private val sample = """
        {
          "tools": [],
          "uiRoutes": [
            {
              "id": "dashboard",
              "route": "toolpkg:com.demo:ui:dashboard",
              "runtime": "compose_dsl",
              "title": {"zh": "面板", "en": "Dashboard"},
              "keep_alive": true,
              "has_screen": true
            }
          ],
          "navEntries": [
            {
              "id": "dashboard_sidebar",
              "route": "toolpkg:com.demo:ui:dashboard",
              "surface": "main_sidebar_plugins",
              "title": {"zh": "面板", "en": "Dashboard"},
              "icon": "chat",
              "order": 122
            },
            {
              "id": "quick_action",
              "surface": "toolbox",
              "title": {"zh": "快速动作"},
              "action_function": "onQuickAction",
              "action_source": "function onQuickAction() {}"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun 页面与导航入口都能解析() {
        val r = PluginRegistrationCodec.parse("com.demo", sample)

        assertTrue("不该有问题：" + r.errors, r.errors.isEmpty())
        assertEquals(3, r.entries.size)

        val route = r.entries.first { it.id == "dashboard" }
        assertEquals(PluginEntryPoint.Kind.UiRoute, route.kind)
        assertEquals("compose_dsl", route.runtime)
        assertEquals("面板", route.title.resolveZh())
        assertEquals("Dashboard", route.title.resolve("en"))
        assertTrue("keepAlive 要收", route.keepAlive)
        // 运行时的 screen 是模块句柄，只能记「有没有」，没有路径可显示。
        assertEquals(PluginRegistrationCodec.SCREEN_FROM_SCRIPT, route.screen)

        val nav = r.entries.first { it.id == "dashboard_sidebar" }
        assertEquals(PluginEntryPoint.Kind.Navigation, nav.kind)
        assertEquals("main_sidebar_plugins", nav.surface)
        assertEquals("chat", nav.icon)
        assertEquals(122, nav.order)
    }

    @Test
    fun 纯动作入口没有路由但有钩子() {
        val r = PluginRegistrationCodec.parse("com.demo", sample)

        val action = r.entries.first { it.id == "quick_action" }
        assertNull("action 入口不跳页面", action.routeId)
        assertEquals("onQuickAction", action.action!!.function)
        assertEquals("function onQuickAction() {}", action.action!!.functionSource)
        assertTrue(action.hasTarget)
    }

    @Test
    fun 导航入口缺挂载面会被跳过并记问题() {
        val json = """
            {"navEntries": [{"id": "x", "route": "toolpkg:a:ui:x"}]}
        """.trimIndent()
        val r = PluginRegistrationCodec.parse("a", json)

        assertTrue(r.entries.isEmpty())
        assertTrue(
            "必须说清是哪个入口缺了 surface",
            r.errors.any { it.contains("surface") && it.contains("x") },
        )
    }

    @Test
    fun 导航入口既无路由也无动作会被跳过() {
        val json = """
            {"navEntries": [{"id": "y", "surface": "toolbox"}]}
        """.trimIndent()
        val r = PluginRegistrationCodec.parse("a", json)

        assertTrue(r.entries.isEmpty())
        assertTrue(r.errors.any { it.contains("route") || it.contains("action") })
    }

    @Test
    fun route_缺省时按_Operit_规则生成() {
        val json = """
            {"uiRoutes": [{"id": "panel", "has_screen": true}]}
        """.trimIndent()
        val r = PluginRegistrationCodec.parse("com.demo", json)

        assertEquals("toolpkg:com.demo:ui:panel", r.entries.single().routeId)
    }

    @Test
    fun 缺_id_的注册项被跳过并记问题() {
        val json = """
            {"uiRoutes": [{"has_screen": true}], "navEntries": [{"surface": "toolbox"}]}
        """.trimIndent()
        val r = PluginRegistrationCodec.parse("a", json)

        assertTrue(r.entries.isEmpty())
        assertEquals(2, r.errors.size)
        assertTrue(r.errors.all { it.contains("id") })
    }

    @Test
    fun 工具定义会被解析() {
        val json = """
            {
              "tools": [
                {
                  "name": "windows_list",
                  "description": "列出窗口",
                  "parameters": "{\"type\":\"object\",\"properties\":{}}",
                  "entry_function": "listWindows"
                },
                {"description": "缺名字"}
              ]
            }
        """.trimIndent()
        val r = PluginRegistrationCodec.parse("a", json)

        assertEquals(1, r.tools.size)
        assertEquals("windows_list", r.tools[0].name)
        assertEquals("listWindows", r.tools[0].entryFunction)
        assertTrue(r.tools[0].parametersJson.contains("properties"))
        // 缺名字的那条要留原因，而不是静默丢。
        assertTrue(r.errors.any { it.contains("tools[1]") })
    }

    @Test
    fun 无参工具的_schema_用规范空对象() {
        val json = """
            {"tools": [{"name": "ping"}]}
        """.trimIndent()
        val r = PluginRegistrationCodec.parse("a", json)

        assertEquals(PluginToolDeclaration.EMPTY_SCHEMA, r.tools.single().parametersJson)
        assertEquals("ping", r.tools.single().entryFunction)
    }

    @Test
    fun 非法_JSON_返回问题而不是抛异常() {
        val r = PluginRegistrationCodec.parse("a", "not a json")

        assertTrue(r.tools.isEmpty())
        assertTrue(r.entries.isEmpty())
        assertEquals(1, r.errors.size)
    }
}
