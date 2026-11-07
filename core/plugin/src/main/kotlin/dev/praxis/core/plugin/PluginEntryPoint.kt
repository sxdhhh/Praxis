package dev.praxis.core.plugin

/**
 * 插件入口。
 *
 * ## 字段来自 Operit 的运行时注册载荷
 *
 * 真实插件在 main 脚本里注册（见 examples/sidebar_sillytavern/src/main.ts），
 * 宿主侧由 ToolPkgMainRegistrationScriptParser 解析。四个注册面的必填项：
 *
 * | 注册函数 | 必填 | 本类对应 |
 * |---|---|---|
 * | registerUiRoute | id + screen（route 可省，按规则生成） | kind = UiRoute |
 * | registerNavigationEntry | id + surface +（route 或 action） | kind = Navigation |
 * | registerToolPkgDesktopWidget | id + route | kind = DesktopWidget |
 * | registerToolPkgToolboxUiModule | id + screen | kind = Toolbox |
 *
 * ## 为什么 routeId 可空
 *
 * 导航项允许「不跳页面，点一下执行一个脚本函数」（action）。那种入口没有路由，
 * Operit 也不给它生成 —— 校验规则就是 route 与 action **二选一**。
 *
 * ## 为什么没有 params
 *
 * registerUiRoute 的载荷里确实有 params（示例里写的是空对象），但 Operit 的
 * 解析器只读 id/screen/route/runtime/title/keepAlive —— params 不被宿主消费。
 * 照着示例猜一个字段进来，等于给以后的人一个「写了也没用」的坑，所以不建模。
 */
data class PluginEntryPoint(
    /** 包内唯一。同包重名只保留第一个。 */
    val id: String,
    val kind: Kind,
    /**
     * 路由标识：toolpkg:包id:ui:入口id。
     * 纯 action 入口为 null（那种入口不跳页面）。
     */
    val routeId: String? = null,
    /**
     * 挂载面。Operit 目前有 toolbox 与 main_sidebar_plugins。
     * 只有 Navigation 必填；其余为 null。
     */
    val surface: String? = null,
    /** 渲染运行时。当前只有 compose_dsl。 */
    val runtime: String = RUNTIME_COMPOSE_DSL,
    /** 页面脚本相对包根的路径。纯 action 入口为空。 */
    val screen: String = "",
    val title: LocalizedText,
    val description: LocalizedText = LocalizedText.EMPTY,
    /** 图标名，来自宿主提供的图标集（Operit 的 Icons.Chat 等）。 */
    val icon: String? = null,
    val order: Int = 0,
    /** 离开页面是否保留运行时。 */
    val keepAlive: Boolean = false,
    /** 点一下执行的脚本函数；与 [routeId] 二选一。 */
    val action: PluginActionHook? = null,
) {
    enum class Kind(val storage: String, val label: String) {
        /** registerToolPkgNavigationEntry：挂在某个 surface 上的导航项。 */
        Navigation("navigation", "导航入口"),

        /** registerToolPkgUiRoute：一个可跳转的页面。 */
        UiRoute("ui_route", "页面"),

        /** registerToolPkgToolboxUiModule：工具箱卡片（旧接口）。 */
        Toolbox("toolbox", "工具箱"),

        /** registerToolPkgDesktopWidget：桌面小组件。 */
        DesktopWidget("widget", "桌面组件"),
        ;

        companion object {
            fun fromStorage(value: String?): Kind? {
                val v = value?.trim().orEmpty()
                if (v.isEmpty()) return null
                return entries.firstOrNull { it.storage.equals(v, ignoreCase = true) }
            }
        }
    }

    /** 有跳转目标或可执行动作，才算一个能用的入口。 */
    val hasTarget: Boolean get() = routeId != null || action != null

    /** 给用户看的目标描述：页面脚本，或「执行 fn()」。 */
    fun targetLabel(): String = when {
        action != null -> "执行 " + action.function + "()"
        screen.isNotBlank() -> screen
        routeId != null -> routeId
        else -> ""
    }

    companion object {
        const val RUNTIME_COMPOSE_DSL: String = "compose_dsl"

        /** 与 Operit 的 buildToolPkgRouteId 同构。 */
        fun routeIdOf(packageId: String, entryId: String): String =
            "toolpkg:" + packageId + ":ui:" + entryId
    }
}

/**
 * 导航项的动作钩子（对齐 Operit 的 ToolPkgNavigationActionHookRuntime）。
 *
 * 载荷有两种写法，Operit 两种都收：
 *
 *     action: "onClick"
 *     function_source: "function onClick() { ... }"
 *
 *     action: { function: "onClick", function_source: "..." }
 */
data class PluginActionHook(
    val function: String,
    /** 函数源码（可选）。有它才能在没预先定义函数的包里执行。 */
    val functionSource: String? = null,
)

/** Operit 的挂载面常量。 */
object EntrySurfaces {
    const val TOOLBOX: String = "toolbox"
    const val MAIN_SIDEBAR_PLUGINS: String = "main_sidebar_plugins"

    /** 给用户看的面名。未知值原样返回 —— 插件作者可能先用了新面。 */
    fun labelOf(surface: String?): String = when (surface) {
        TOOLBOX -> "工具箱"
        MAIN_SIDEBAR_PLUGINS -> "侧边栏"
        null, "" -> ""
        else -> surface
    }
}

/** 入口的来源。页面上必须能看出来，否则「为什么这条不见了」无从判断。 */
enum class EntryOrigin(val label: String) {
    /** 包在 main 脚本里注册的（registerUiRoute 等）——Operit 的真实来源。 */
    Runtime("运行时"),

    /** 清单 entries 里声明的（Praxis 的声明式通道）。 */
    Manifest("清单"),
}

/**
 * 已装插件的一个入口（聚合视图）。
 *
 * 带插件名与可用性：「兼容插件入口」是跨插件的页面，用户看的是
 * 「有哪些入口可用」，每条都必须说清来自谁。
 */
data class InstalledEntryPoint(
    val pluginId: String,
    val pluginTitle: String,
    val point: PluginEntryPoint,
    /** 包已启用且无致命问题时才可用。 */
    val enabled: Boolean,
    /** 这条入口从哪来。见 [EntryOrigin]。 */
    val origin: EntryOrigin = EntryOrigin.Manifest,
) {
    val key: String get() = pluginId + "/" + point.id
    val title: String get() = point.title.resolveZh().ifBlank { point.id }

    /** 排序：先 order，再包 id、入口 id，保证列表顺序稳定。 */
    companion object {
        val ORDER: Comparator<InstalledEntryPoint> =
            compareBy({ it.point.order }, { it.pluginId }, { it.point.id })
    }
}

/**
 * 把所有已装插件的入口摊平。
 *
 * ## 两条来源的优先级：**运行时整体优先**
 *
 * | 情况 | 采用 |
 * |---|---|
 * | 该包有运行时捕获 | 只用捕获结果，清单 entries 一律不采用 |
 * | 没有捕获 | 用清单 entries |
 *
 * ## 为什么不逐条混合
 *
 * Operit 的入口**只存在于运行时注册**，清单里根本没有这个字段 —— 也就是说
 * 包一旦跑起来，它注册的东西就是唯一真相。逐条混合会带来两个具体问题：
 *
 *  1. 同一个页面可能被声明一次、注册一次（id 不同），列表里出现两条；
 *  2. 清单里写废的条目会在运行时成功之后继续显示，而它早已不存在。
 *
 * 所以判据是「这个包跑起来了没有」，而不是「这条 id 撞没撞上」。
 *
 * @param captured 包 id → 运行时捕获到的入口（见 PluginRuntimeHost.capturedEntries）
 */
fun List<InstalledPlugin>.collectEntryPoints(
    captured: Map<String, List<PluginEntryPoint>> = emptyMap(),
): List<InstalledEntryPoint> =
    flatMap { plugin ->
        val usable = plugin.enabled && plugin.problems.none { it is ManifestProblem.Fatal }
        val runtime = captured[plugin.id].orEmpty()
        val source = if (runtime.isNotEmpty()) runtime else plugin.manifest.entries
        val origin = if (runtime.isNotEmpty()) EntryOrigin.Runtime else EntryOrigin.Manifest
        source.map { entry ->
            InstalledEntryPoint(
                pluginId = plugin.id,
                pluginTitle = plugin.title,
                point = entry,
                enabled = usable,
                origin = origin,
            )
        }
    }.sortedWith(InstalledEntryPoint.ORDER)
