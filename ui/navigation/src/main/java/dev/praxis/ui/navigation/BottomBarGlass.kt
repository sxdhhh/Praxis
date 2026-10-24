// SPDX-License-Identifier: GPL-3.0-only
package dev.praxis.ui.navigation

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.praxis.ui.design.glass.GlassBackend
import dev.praxis.ui.design.glass.LocalPageBackdrop
import dev.praxis.ui.design.glass.liquid.lens
import dev.praxis.ui.design.glass.liquid.vibrancy
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop

/**
 * 底栏玻璃：**唯一引用 miuix-blur 类的地方**（底栏相关）。
 *
 * ## 2026-10-21 修正：底色绝不能设成全透明
 *
 * 上一版把 `FloatingNavigationBar` 的 `color` 设成 `Color.Transparent`，
 * 指望 `drawBackdrop` 来撑起整个背景。**装机后底栏整个消失了** ——
 * 只剩几个在暗色底上几乎看不见的图标。
 *
 * 原因是 `FloatingNavigationBar` 内部只有一处背景：
 * `Row(... .squircleBackground(color).then(modifier))`。
 * 一旦 `color` 透明、而 `drawBackdrop` 因为任何原因没画出东西
 * （shader 不支持 / 采样源为空 / 折射参数把内容推到形状外），
 * 就**没有任何兜底**了。
 *
 * 现在的做法是两层叠加，任何一层失效都不会让底栏消失：
 *   1. **底色半透明**（`surface.copy(alpha = 0.72f)`）—— 永远存在，保证可读；
 *   2. **玻璃折射叠在它上面** —— 有采样源时才画，画出来是「磨砂 + 边缘高光」。
 *
 * 这也是 iOS / miuix 官方示例的做法：玻璃是**覆盖在半透明底上的一层效果**，
 * 而不是「背景本身」。
 *
 * ## 为什么住在 ui:navigation 而不是 :app
 *
 * 底栏是导航层的产物（它只认 [MainTab] 与导航层的采样源），
 * 与具体业务页无关。留在 `:app` 会让「谁的 UI 放哪」这条规矩失效 ——
 * `:app` 只该有 Activity / DI / 组装。
 */

/** 玻璃生效时的底色：半透明，不是全透明。 */
internal fun bottomBarSurface(glass: Boolean, surface: Color): Color =
    if (glass) surface.copy(alpha = 0.72f) else surface

/**
 * 读出采样源。返回 null 表示不挂玻璃（低版本 / 对照模式 / 页面未下发）。
 *
 * 这里只有一个 return 分支，`GlassBackend.mode` 在运行时可变，
 * Composable 内部不要中途 return 多次 —— 会让 slot table 结构跳动。
 */
@Composable
internal fun rememberBottomBarBackdrop(): Backdrop? {
    if (GlassBackend.noGlassAtAll || GlassBackend.useLowPipeline) return null
    return LocalPageBackdrop.current
}

/**
 * 底栏玻璃修饰符。`backdrop` 为 null 时返回空 Modifier（不碰任何 blur 类）。
 *
 * 效果顺序：`vibrancy ⇒ blur ⇒ lens`（与上游 Kyant 文档一致）。
 *  - 先提饱和：不先提，模糊后的画面会糊成一片灰；
 *  - 再模糊：折射取样需要的是已模糊的画面，顺序反了会折射出清晰的锐利内容；
 *  - 最后镜片：`chromaticAberration` 给边缘一层轻微彩虹。
 *
 * 参数取**保守值**：底栏只有 60dp 高，折射深度过大就会把内容推出形状外、
 * 看起来像一块空白。`refractionHeight=10 / amount=12` 在这个尺度上是肉眼刚好的量。
 */
@Composable
internal fun Modifier.bottomBarGlass(backdrop: Backdrop?, cornerRadius: Dp): Modifier {
    if (backdrop == null) return this
    return this.then(
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(cornerRadius) },
            effects = {
                vibrancy()
                blur(radiusX = 16f.dp.toPx(), radiusY = 16f.dp.toPx())
                lens(
                    refractionHeight = 10f.dp.toPx(),
                    refractionAmount = 12f.dp.toPx(),
                    chromaticAberration = 0.08f,
                )
            },
        ),
    )
}
