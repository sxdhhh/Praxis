// SPDX-License-Identifier: GPL-3.0-only
package dev.praxis.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.praxis.ui.design.glass.FloatingBottomBar
import dev.praxis.ui.design.glass.FloatingBottomBarDefaults
import dev.praxis.ui.design.glass.FloatingBottomBarMode
import dev.praxis.ui.design.glass.GlassBackend
import dev.praxis.ui.design.glass.LocalPageBackdrop
import dev.praxis.ui.icons.PraxisIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 浮层底栏：直接使用从 InstallerX-Revived 移植的 [FloatingBottomBar]。
 *
 * ## 为什么不再自己写
 *
 * 自己写的那版有两个真问题（装机后立刻暴露）：
 *  1. 把 miuix `FloatingNavigationBar` 的 `color` 设成透明、指望玻璃撑背景，
 *     结果**整条底栏消失**；
 *  2. 没有独立的选中指示器层，看着「死板」，也没有拖拽切换 tab、按压形变、
 *     重力感应高光、三层背景结构 —— 这些细节自己重写必然再踩一遍坑。
 *
 * ## 为什么入参是 [MainTab] 而不是 `PraxisDomain`（2026-10-22 导航重构）
 *
 * 旧版接的是 `PraxisDomain`（10 个域的枚举），但底栏只画其中 4 个，靠
 * `items.indexOf(selected)` 求下标。两个隐患：
 *
 *  - 下标来自「4 项列表里的位置」，而 `PraxisDomain.ordinal` 是「10 项枚举里的位置」，
 *    两者不同源。任何排序调整都要同时改两处，错一次就是「点 tab 2 翻到 tab 3」。
 *  - 其余 6 个域在底栏不存在，等于底栏永远无法选中它们 —— 而那些域又确实
 *    有页面（旧实现里 5 个域因此完全不可达）。
 *
 * 现在底栏只认 [MainTab]，它与 Pager 的页序**是同一个枚举的 ordinal**，
 * 不存在换算；不占 Tab 的功能改从设置页进入。
 *
 * ## 低版本怎么办
 *
 * `FloatingBottomBar` 的液体玻璃依赖 RuntimeShader（API 33+）。
 * [GlassBackend.useLowPipeline] 为真时传 [FloatingBottomBarMode.None]，
 * 走实色分支 —— 那个分支不碰任何 blur 类，低版本安全。
 *
 * ## 采样源
 *
 * `backdrop` 必须来自页面内容层（`LocalPageBackdrop`，由页面自己的 Scaffold 下发）。
 * 底栏在内容之上，自建采样源只会采到自己。未下发时同样走实色分支。
 *
 * ## 为什么从 `:app` 搬到 `ui:navigation`
 *
 * 底栏与 [MainTab]、[PraxisRoute] 是一组：它唯一的输入是「当前选中哪个 Tab」。
 * 留在 `:app` 时，任何想预览/测试底栏的模块都必须依赖 `:app`（应用模块不能被
 * 别的模块依赖），等于这块 UI 无法被单测与 @Preview 覆盖。
 */
@Composable
fun PraxisBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = MainTab.entries

    val backdrop = LocalPageBackdrop.current
    val mode = when {
        backdrop == null -> FloatingBottomBarMode.None
        GlassBackend.noGlassAtAll -> FloatingBottomBarMode.None
        GlassBackend.useLowPipeline -> FloatingBottomBarMode.Blur
        else -> FloatingBottomBarMode.LiquidGlass
    }

    val surface = MiuixTheme.colorScheme.surface

    if (backdrop == null) {
        // 极低版本 / 未下发：直接用实色底栏，不进 FloatingBottomBar（避免空 backdrop 崩溃）。
        PraxisFallbackBottomBar(selected = selected, onSelect = onSelect, modifier = modifier)
        return
    }

    FloatingBottomBar(
        items = items,
        selectedIndex = { items.indexOf(selected).coerceAtLeast(0) },
        onSelected = { index -> items.getOrNull(index)?.let(onSelect) },
        backdrop = backdrop,
        modifier = modifier.padding(bottom = 12.dp),
        mode = mode,
        colors = FloatingBottomBarDefaults.colors(containerColor = surface),
        iconContent = { item, _ ->
            Icon(
                imageVector = item.icon(),
                contentDescription = item.title(),
                modifier = Modifier.size(24.dp),
            )
        },
        labelContent = { item, _ ->
            Text(
                text = item.title(),
                style = MiuixTheme.textStyles.footnote1,
            )
        },
    )
}

/** 无采样源时的实色底栏（低版本保底，不涉及任何 blur 类）。 */
@Composable
private fun PraxisFallbackBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    top.yukonga.miuix.kmp.basic.FloatingNavigationBar(
        modifier = modifier.padding(bottom = 12.dp),
        color = MiuixTheme.colorScheme.surface,
        cornerRadius = 28.dp,
    ) {
        MainTab.entries.forEach { item ->
            top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem(
                selected = item == selected,
                onClick = { onSelect(item) },
                icon = item.icon(),
                label = item.title(),
            )
        }
    }
}

/**
 * Tab → 图标。
 *
 * 穷举 `when` 没有 `else`：新增 Tab 忘了给图标就是**编译错误**，
 * 不会静默落到某个默认图标上（那会让两个 Tab 看起来一模一样）。
 */
private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.Chat -> PraxisIcons.Chat
    MainTab.Terminal -> PraxisIcons.Terminal
    MainTab.Memory -> PraxisIcons.Memory
    MainTab.Settings -> PraxisIcons.Settings
}

/** Tab → 标题。同上，穷举无 `else`。 */
private fun MainTab.title(): String = when (this) {
    MainTab.Chat -> "对话"
    MainTab.Terminal -> "终端"
    MainTab.Memory -> "记忆"
    MainTab.Settings -> "设置"
}