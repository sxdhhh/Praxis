package dev.praxis.ui.design.glass.low

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.basic.SmallTopAppBar

/** 顶栏磨砂层的不透明度（唯一可调点）。α 越小越「透」，越大越接近实色顶栏。 */
private const val TOPBAR_FROSTED_ALPHA = 0.85f

/**
 * 低版本顶栏 —— **毛玻璃降级实现**（静态半透明磨砂，**不采样、不模糊**）。
 *
 * ★★ 2026-10-05（用户指令）：
 *
 * > 「顶部 scaffold 用毛玻璃不用 blur」
 *
 * 此前低版本曾 1:1 复刻高版本的 `progressiveTextureBlur` 渐变模糊
 * （GPU 管线 + 背景采样 + `PROGRESSIVE_MASK`），但那条链路长、成本高，
 * 低版本设备上不值得继续维护。现在降级为**静态半透明磨砂**：
 *
 *  - 与页面同色的表面色（surfaceContainer）以 **α ≈ 0.85** 覆盖顶栏区域；
 *  - **不采样背景、不做模糊** —— 内容从顶栏下方滑过时轻微透出（约 15%），
 *    形成「磨砂」观感；
 *  - 无 overhang 下撑（不再需要给模糊带预留渐隐段；
 *    `LowAppScaffold` 也不再需要从 `innerPadding` 里减去额外高度）。
 *
 * # 与高版本的对照（降级口径）
 *
 * | 参数 | 高版本（AppScaffold.BlurredTopBar） | 本实现（降级） |
 * |---|---|---|
 * | 顶栏背景 | `progressiveTextureBlur(blurRadius=10f, curve=2.2f)` 渐变模糊 | 半透明表面色（无模糊） |
 * | 采样源 | `LayerBackdrop`（内容层） | **无**（不采样） |
 * | 下撑 | `BlurOverhang = 28.dp` | 无 |
 *
 * 如需调整「磨砂感」，只改 [TOPBAR_FROSTED_ALPHA] 一个值即可。
 */
@Composable
internal fun LowFrostedTopBar(
    title: String,
    onBack: (() -> Unit)?,
    surfaceColor: Color,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth()) {
        // 半透明磨砂底：与 Scaffold 页面底色同色的表面色、略透。
        Box(
            Modifier
                .matchParentSize()
                .background(surfaceColor.copy(alpha = TOPBAR_FROSTED_ALPHA)),
        )
        // 顶栏本体：透明（让下面的磨砂层透出）—— 与高版本 blurSupported 分支同结构。
        Column {
            SmallTopAppBar(
                title = title,
                modifier = Modifier.fillMaxWidth(),
                color = Color.Transparent,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Regular.Back,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
                actions = {
                    if (actions != null) actions()
                },
            )
        }
    }
}
