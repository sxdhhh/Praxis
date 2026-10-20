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
 * 底栏玻璃背景：**唯一引用 miuix-blur 类的地方**。
 *
 * ## 为什么要单独一个文件
 *
 * `miuix-blur` 的 AAR 声明 `minSdkVersion=33`，而本项目 minSdk 是 28。
 * 其中 `internal/RenderEffectCompat_androidKt` 直接引用了 `android.graphics.RuntimeShader`
 * （API 33 才有）。API 28–32 的设备上，**一旦该类被触碰就会 `NoClassDefFoundError`**。
 *
 * JVM 的类解析是惰性的：只要不执行到引用它的方法体，就不会解析。
 * 把这段代码与调用方放在同一个文件里是可以的，但**同一个 Composable 里
 * `if (useGlass) drawBackdrop(...)` 这种写法是安全的** —— 前提是 `useGlass`
 * 为 false 时那块代码真的不会被执行。
 *
 * 拆成独立文件是更硬的保证：调用点在 [PraxisBottomBar] 里，用
 * `if (GlassBackend.useLowPipeline || backdrop == null)` 先短路，
 * 真正的 blur 调用被封在本文件的两个函数后面。
 *
 * ## 低版本为什么不给底栏做磨砂降级
 *
 * 顶栏在低版本有 `LowFrostedTopBar`（静态半透明磨砂）。底栏没有做，原因是
 * 底栏高度只有约 64dp，磨砂与实色的观感差异在这个尺度上几乎看不出来，
 * 而多一层半透明会让「浮层」边界变糊、图标对比度下降。
 * 所以低版本直接用主题实色 `surface`，与改造前完全一致。
 */

/** 玻璃生效时的底色：必须全透明，否则实色会盖住折射层。 */
internal val BottomBarGlassTint: Color = Color.Transparent

/**
 * 一次读出「该不该挂玻璃」以及要用的采样源，避免在调用方重复读 CompositionLocal。
 *
 * 返回值里的 `backdrop` 为 null 表示「不挂玻璃」。
 *
 * 这里只有**一个** return、没有中途 return：Compose 的 Composable 在同一页面上
 * 若因条件变化走到不同数量的分支，会让 slot table 结构跳动。
 * `GlassBackend.mode` 是运行时可切的调试开关，必须假设它会变。
 */
internal data class BottomBarGlassBackdrop(val backdrop: Backdrop?)

@Composable
internal fun rememberBottomBarBackdrop(): BottomBarGlassBackdrop {
    // 低版本 / 对照模式：连 CompositionLocal 都不用读，直接给 null。
    if (GlassBackend.noGlassAtAll || GlassBackend.useLowPipeline) {
        return BottomBarGlassBackdrop(null)
    }
    return BottomBarGlassBackdrop(LocalPageBackdrop.current)
}

/**
 * 底栏玻璃修饰符。`backdrop` 为 null 时返回空 Modifier（不碰任何 blur 类）。
 *
 * 效果顺序：`vibrancy ⇒ blur ⇒ lens`（与上游 Kyant 文档一致）。
 *  - 先提饱和：不先提，模糊后的画面会糊成一片灰；
 *  - 再模糊：折射取样需要的是已模糊的画面，顺序反了会折射出清晰的锐利内容；
 *  - 最后镜片：`chromaticAberration` 给边缘一层轻微彩虹，0.1 已是肉眼刚好能察觉的量。
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
                blur(radiusX = 18f.dp.toPx(), radiusY = 18f.dp.toPx())
                lens(
                    refractionHeight = 16f.dp.toPx(),
                    refractionAmount = 22f.dp.toPx(),
                    chromaticAberration = 0.1f,
                )
            },
        ),
    )
}