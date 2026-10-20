package dev.praxis.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.domain.model.PraxisDomain
import dev.praxis.ui.icons.PraxisIcons
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 浮层底栏。
 *
 * ## 三条硬约束（既有项目踩过的坑）
 *
 *  1. **底栏是浮层，内容从下方穿过**：不能靠给内容区加 padding 抬高来「让位」。
 *     内容必须真的从底栏底下经过，玻璃才有东西可折射。
 *  2. **玻璃只在 API 33+ 启用**：miuix-blur 的 AAR 声明 `minSdk=33`，低版本
 *     碰它就会 `NoClassDefFoundError`。分流判断在 [rememberBottomBarBackdrop] 里。
 *  3. **采样源来自页面内容层**，不能让底栏自己建。底栏在内容之上、内部没有内容，
 *     自建 backdrop 采到的是它自己 —— 折射出来只是一块半透明实色。
 *     采样源由 `AppScaffold` 通过 `LocalPageBackdrop` 下发。
 *
 * ## 本文件不 import 任何 miuix-blur 类型
 *
 * 玻璃相关代码全部封在 `BottomBarGlass.kt` 里。这里只调
 * [rememberBottomBarBackdrop] 与 [bottomBarGlass] 两个函数，
 * 且 `backdrop` 为 null 时后者立即返回空 Modifier —— 这样 API 28–32 的设备上，
 * 引用 `android.graphics.RuntimeShader` 的类**永远不会被解析**。
 *
 * ## miuix 0.9.4 真实签名（核对过，勿凭印象写）
 *
 *  - `FloatingNavigationBar(modifier, color, cornerRadius, horizontalAlignment, ...)`
 *  - `FloatingNavigationBarItem(selected, onClick, icon: ImageVector, label: String, ...)`
 *    注意 `label` 是 String，不是 composable 槽。
 *
 * ## 玻璃为什么画在 `modifier` 里而不是替换 `color`
 *
 * `FloatingNavigationBar` 只收 `color: Color`，没有背景槽。它内部的顺序是
 * `squircleBackground(color) → then(modifier)`，所以传进 `modifier` 的
 * `drawBackdrop` 会画在底色**之上**。于是玻璃生效时把 `color` 设为全透明，
 * 由 `drawBackdrop` 负责全部背景。
 */
@Composable
fun PraxisBottomBar(
    selected: PraxisDomain,
    onSelect: (PraxisDomain) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 四个 Tab 是硬约束（WeKit/老挂 同款 76.dp 最小宽度，四 tab 才排得下）：
    // 工具与终端都是「动手」类，各占一个会让底栏超宽；这里让终端上位，
    // 工具页仍从设置入口进入（设置首页已登记 Tools 条目）。
    val items = listOf(
        PraxisDomain.CHAT to PraxisIcons.Chat,
        PraxisDomain.TERMINAL to PraxisIcons.Terminal,
        PraxisDomain.MEMORY to PraxisIcons.Memory,
        PraxisDomain.SETTINGS to PraxisIcons.Settings,
    )

    val cornerRadius = 28.dp
    val glass = rememberBottomBarBackdrop()
    val useGlass = glass.backdrop != null

    FloatingNavigationBar(
        // 玻璃作为扩展接在 modifier 链上：backdrop 为 null 时它原样返回 this。
        modifier = modifier
            .padding(bottom = 12.dp)
            .bottomBarGlass(glass.backdrop, cornerRadius),
        // 玻璃生效时底色必须全透明，否则实色会盖住折射层；
        // 退回实色时用主题 surface，与改造前观感一致。
        color = if (useGlass) BottomBarGlassTint else MiuixTheme.colorScheme.surface,
        cornerRadius = cornerRadius,
    ) {
        items.forEach { (domain, icon) ->
            FloatingNavigationBarItem(
                selected = domain == selected,
                onClick = { onSelect(domain) },
                icon = icon,
                label = domain.title,
            )
        }
    }
}