package dev.praxis.core.plugin

/**
 * 插件入口。
 *
 * ## 字段来自 Operit 的运行时注册载荷，不是清单
 *
 * 真实插件不在清单里声明入口，而是在 main 脚本里注册。以
 * examples/sidebar_sillytavern/src/main.ts 为例：
 *
 *     ToolPkg.registerUiRoute({
 *       id: "sillytavern_dashboard",
 *       route: "toolpkg:com.operit.sidebar_sillytavern:ui:sillytavern_dashboard",
 *       runtime: "compose_dsl", screen: module, params: {}, keepAlive: true,
 *       title: { zh: "酒馆", en: "SillyTavern" },
 *     })
 *     ToolPkg.registerNavigationEntry({
 *       id: "sillytavern_dashboard_sidebar",
 *       route: "toolpkg:...:ui:sillytavern_dashboard",
 *       surface: "main_sidebar_plugins",
 *       title: { zh: "酒馆", en: "SillyTavern" },
 *       icon: Icons.Chat, order: 122,
 *     })
 *
 * 宿主侧对应的模型是 ToolPkgParser 里的 ToolPkgUiRouteRuntime 与
 * ToolPkgNavigationEntryRuntime。本类把两者合成一个视图 —— 一个入口
 * 要么只是一个路由（UiRoute），要么同时挂到某个 surface 上（Navigation）。
 *
 * ## 为什么 Praxis 还要一条清单声明通道
 *
 * 运行时捕获要求先跑包的 main 脚本。Praxis 的 Compose DSL 渲染尚未接上，
 * 包可能跑不起来 —— 那时如果只依赖捕获，用户会看到「装了包但入口页是空的」。
 *
 * 所以清单 entries 是声明式补充：能跑就以捕获为准，跑不起来还能列出来。
 * 两条来源字段名一致（screen / surface / route / keepAlive），
 * 只是清单里没有函数值，所以 screen 写的是脚本路径。
 */
data class PluginEntryPoint(
    /** 包内唯一。同包重名只保留第一个。 */
    val id: String,
    /** Operit 的路由标识：toolpkg:包id:ui:入口id。 */
    val routeId: String,
    val kind: Kind,
    /**
     * 挂载面。Operit 目前有两个：toolbox 与 main_sidebar_plugins。
     * 只有 Navigation 才有；纯路由（UiRoute）为 null。
     */
    val surface: String? = null,
    /** 渲染运行时。当前只有 compose_dsl。 */
    val runtime: String = RUNTIME_COMPOSE_DSL,
    /** 页面脚本相对包根的路径（运行时捕获时是模块句柄）。 */
    val screen: String,
    val title: LocalizedText,
    val description: LocalizedText = LocalizedText.EMPTY,
    /** 图标名，来自宿主提供的图标集（Operit 的 Icons.Chat 等）。 */
    val icon: String? = null,
    val order: Int = 0,
    /** 离开页面是否保留运行时。 */
    val keepAlive: Boolean = false,
) {
    enum class Kind(val storage: String, val label: String) {
        /** registerToolPkgNavigationEntry：侧边栏/工具箱里的一个导航项。 */
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

    companion object {
        const val RUNTIME_COMPOSE_DSL: String = "compose_dsl"

        /** 与 Operit 的 buildToolPkgRouteId 同构。 */
        fun routeIdOf(packageId: String, entryId: String): String =
            "toolpkg:" + packageId + ":ui:" + entryId
    }
}

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

/**
 * 已装插件的一个入口（聚合视图）。
 *
 * 带插件名与可用性：「兼容插件入口」是跨插件的页面，用户看的是
 * 「有哪些入口可用」，每条都必须说清来自谁 —— 否则两个包都叫「设置」时，
 * 用户点进去才发现不是想要的那个。
 */
data class InstalledEntryPoint(
    val pluginId: String,
    val pluginTitle: String,
    val point: PluginEntryPoint,
    /** 包已启用且无致命问题时才可用。 */
    val enabled: Boolean,
) {
    val key: String get() = pluginId + "/" + point.id
    val title: String get() = point.title.resolveZh().ifBlank { point.id }

    /** 排序：先 order，再包 id、入口 id，保证列表顺序稳定。 */
    companion object {
        val ORDER: Comparator<InstalledEntryPoint> =
            compareBy({ it.point.order }, { it.pluginId }, { it.point.id })
    }
}

/** 把所有已装插件的入口摊平。 */
fun List<InstalledPlugin>.collectEntryPoints(): List<InstalledEntryPoint> =
    flatMap { plugin ->
        val usable = plugin.enabled && plugin.problems.none { it is ManifestProblem.Fatal }
        plugin.manifest.entries.map { entry ->
            InstalledEntryPoint(
                pluginId = plugin.id,
                pluginTitle = plugin.title,
                point = entry,
                enabled = usable,
            )
        }
    }.sortedWith(InstalledEntryPoint.ORDER)
