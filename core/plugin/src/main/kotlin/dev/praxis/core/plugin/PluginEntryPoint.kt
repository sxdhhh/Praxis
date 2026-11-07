package dev.praxis.core.plugin

/**
 * 插件声明的「入口」。
 *
 * ## 照搬的是 Operit 的四个注册面
 *
 * Operit 的包在 registerToolPkg() 里可以注册四类 UI 产物
 * （见 ToolPkgCommonPluginConstants 里的常量名）：
 *
 * | Operit 注册函数 | 产物 | 本文件的 Kind |
 * |---|---|---|
 * | registerToolPkgNavigationEntry | 侧边栏 / 设置里的一个导航项 | Navigation |
 * | registerToolPkgUiRoute | 一个完整页面 | UiRoute |
 * | registerToolPkgToolboxUiModule | 工具箱里的一张卡片 | Toolbox |
 * | registerToolPkgDesktopWidget | 桌面小组件 | DesktopWidget |
 *
 * ## 为什么在清单里声明，而不是解析 JS
 *
 * Operit 靠执行包的主脚本、捕获那些注册调用来得知入口 —— 那需要先跑一遍
 * 第三方 JS。Praxis 把这一步**声明化**：入口写在清单的 entries 里，
 * 装包时就读得出来，不必为了「知道它有哪些入口」而执行包代码。
 *
 * 真正干活仍然是脚本的事（entry 指向包内路径）；这里只回答
 * 「有哪些入口、叫什么、排在哪」。
 */
data class PluginEntryPoint(
    /** 包内唯一。同包重名只保留第一个。 */
    val id: String,
    val kind: Kind,
    val title: LocalizedText,
    val description: LocalizedText = LocalizedText.EMPTY,
    /** 图标：resources 里声明的 key，或内置图标名。 */
    val icon: String? = null,
    /** 相对包根的脚本路径。 */
    val entry: String,
    /** 排序权重，小的在前；同值按包 id、入口 id 排。 */
    val order: Int = 0,
) {
    enum class Kind(val storage: String, val label: String) {
        Navigation("navigation", "导航入口"),
        UiRoute("ui_route", "页面"),
        Toolbox("toolbox", "工具箱"),
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
}

/**
 * 已装插件的一个入口（聚合视图）。
 *
 * ## 为什么要带插件名
 *
 * 「兼容插件入口」这个页面是**跨插件**的：用户看到的是「有哪些入口可用」，
 * 而不是「哪些包装了什么」。所以每条都必须能说清它来自谁 ——
 * 否则两个包都叫「设置」时，用户点进去才发现不是想要的那个。
 */
data class InstalledEntryPoint(
    val pluginId: String,
    val pluginTitle: String,
    val point: PluginEntryPoint,
    /** 包与入口都可用的前提：包已启用且没有致命问题。 */
    val enabled: Boolean,
) {
    val key: String get() = pluginId + "/" + point.id
    val title: String get() = point.title.resolveZh().ifBlank { point.id }

    /** 同 kind 内排序：先按声明权重，再按包名与入口 id，保证顺序稳定。 */
    companion object {
        val ORDER: Comparator<InstalledEntryPoint> =
            compareBy({ it.point.order }, { it.pluginId }, { it.point.id })
    }
}

/**
 * 把所有已装插件的入口摊平成一张列表。
 *
 * 摊平后按 [InstalledEntryPoint.ORDER] 排序：Compose 列表用 key 做动画，
 * 顺序不稳定会让每帧都重排。
 */
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
