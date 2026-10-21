package dev.praxis.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.domain.model.PraxisDomain
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
 *  2. 没有独立的选中指示器层，看着「死板」，也没有拖拽切换 / 按压放大 / 镜面高光。
 *
 * 上游 `FloatingBottomBar` 是 583 行的成熟实现（含拖拽切换 tab、按压形变、
 * 重力感应高光、三层背景结构），这些细节自己重写必然再踩一遍坑。
 *
 * ## 低版本怎么办
 *
 * `FloatingBottomBar` 的液体玻璃依赖 RuntimeShader（API 33+）。
 * [GlassBackend.useLowPipeline] 为真时传 [FloatingBottomBarMode.None]，
 * 走实色分支 —— 那个分支不碰任何 blur 类，低版本安全。
 *
 * ## 采样源
 *
 * `backdrop` 必须来自页面内容层（`LocalPageBackdrop`，由 AppScaffold 下发）。
 * 底栏在内容之上，自建采样源只会采到自己。
 * 未下发时（低版本）同样走实色分支。
 */
@Composable
fun PraxisBottomBar(
    selected: PraxisDomain,
    onSelect: (PraxisDomain) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        PraxisDomain.CHAT,
        PraxisDomain.TERMINAL,
        PraxisDomain.MEMORY,
        PraxisDomain.SETTINGS,
    )

    val backdrop = LocalPageBackdrop.current
    val mode = when {
        backdrop == null -> FloatingBottomBarMode.None
        GlassBackend.noGlassAtAll -> FloatingBottomBarMode.None
        GlassBackend.useLowPipeline -> FloatingBottomBarMode.Blur
        else -> FloatingBottomBarMode.LiquidGlass
    }

    // 无采样源时给一个保底：FloatingBottomBar 要求非空 backdrop。
    // 用一张纯色 canvas 采样源，效果等同 None 模式的实底。
    val surface = MiuixTheme.colorScheme.surface
    val effectiveBackdrop = backdrop

    if (effectiveBackdrop == null) {
        // 极低版本/未下发：直接用实色底栏，不进 FloatingBottomBar（避免空 backdrop 崩溃）。
        PraxisFallbackBottomBar(selected = selected, items = items, onSelect = onSelect, modifier = modifier)
        return
    }

    FloatingBottomBar(
        items = items,
        selectedIndex = { items.indexOf(selected).coerceAtLeast(0) },
        onSelected = { index -> items.getOrNull(index)?.let(onSelect) },
        backdrop = effectiveBackdrop,
        modifier = modifier.padding(bottom = 12.dp),
        mode = mode,
        colors = FloatingBottomBarDefaults.colors(containerColor = surface),
        iconContent = { item, _ ->
            Icon(
                imageVector = when (item) {
                    PraxisDomain.CHAT -> PraxisIcons.Chat
                    PraxisDomain.TERMINAL -> PraxisIcons.Terminal
                    PraxisDomain.MEMORY -> PraxisIcons.Memory
                    else -> PraxisIcons.Settings
                },
                contentDescription = item.title,
                modifier = Modifier.size(24.dp),
            )
        },
        labelContent = { item, _ ->
            Text(
                text = item.title,
                style = MiuixTheme.textStyles.footnote1,
            )
        },
    )
}

/** 无采样源时的实色底栏（低版本保底，不涉及任何 blur 类）。 */
@Composable
private fun PraxisFallbackBottomBar(
    selected: PraxisDomain,
    items: List<PraxisDomain>,
    onSelect: (PraxisDomain) -> Unit,
    modifier: Modifier = Modifier,
) {
    top.yukonga.miuix.kmp.basic.FloatingNavigationBar(
        modifier = modifier.padding(bottom = 12.dp),
        color = MiuixTheme.colorScheme.surface,
        cornerRadius = 28.dp,
    ) {
        items.forEach { item ->
            top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem(
                selected = item == selected,
                onClick = { onSelect(item) },
                icon = when (item) {
                    PraxisDomain.CHAT -> PraxisIcons.Chat
                    PraxisDomain.TERMINAL -> PraxisIcons.Terminal
                    PraxisDomain.MEMORY -> PraxisIcons.Memory
                    else -> PraxisIcons.Settings
                },
                label = item.title,
            )
        }
    }
}
