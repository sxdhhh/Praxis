// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
// 移植自 InstallerX-Revived:
//   app/src/main/java/com/rosan/installer/ui/animation/predictiveback/
//     NavTransitionEasing.kt / NavTransitionGeometry.kt
package dev.praxis.ui.navigation.transition

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import kotlin.math.roundToInt

/**
 * 转场用的缓动与几何工具（照抄 InstallerX，仅改包名）。
 *
 * 这两个文件在 InstallerX 里各只有 31 / 13 行，但**每一个都是必需的**，
 * 删掉任何一个都会让下面的转场「差一点」：
 *
 *  - [FastOutExtraSlowIn]：AOSP 预测性返回的原生曲线。单个 `CubicBezierEasing`
 *    表达不了「先极快、后极慢」，所以上游用两段贝塞尔在 `knot` 处拼接。
 *    换成普通的 `FastOutSlowIn` 会明显「启动慢」，跟系统返回动画对不上。
 *  - [snapScaleToPixelExtent] / [snapTranslationToPixelEdge]：把缩放与位移
 *    **吸附到整像素**。不吸附时，非整数缩放会让内容边缘每帧抖动 1px，
 *    滚动内容在手势跟手时看得出「毛边在抖」。
 */

/** AOSP 预测性返回的官方曲线：两段贝塞尔拼接（先极快、后极慢）。 */
internal val FastOutExtraSlowIn: Easing = run {
    val knotX = 0.166666f
    val knotY = 0.4f
    val first = CubicBezierEasing(0.05f / knotX, 0f, 0.133333f / knotX, 0.06f / knotY)
    val second = CubicBezierEasing(
        (0.208333f - knotX) / (1f - knotX),
        (0.82f - knotY) / (1f - knotY),
        (0.25f - knotX) / (1f - knotX),
        (1f - knotY) / (1f - knotY),
    )
    Easing { fraction ->
        if (fraction < knotX) {
            knotY * first.transform(fraction / knotX)
        } else {
            knotY + (1f - knotY) * second.transform((fraction - knotX) / (1f - knotX))
        }
    }
}

/** 返回手势的跟手曲线：起点缓慢、中段几乎线性、末端收住。 */
internal val BackGestureEasing: Easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/**
 * 顶层页的「进度」。
 *
 * `relativeDepth` 的约定：0 = 已稳定的栈顶，负数 = 正在进入/离开，
 * 正数 = 被盖住。所以顶层进度是 `1 + depth`（depth = -0.5 时进度 0.5）。
 */
internal fun topProgress(depth: Float): Float = (1f + depth).coerceIn(0f, 1f)

/** 被盖住那一层的「被覆盖进度」：depth 0→1 对应 0→1。 */
internal fun coverProgress(depth: Float): Float = depth.coerceIn(0f, 1f)

/**
 * 把缩放吸附到整像素。
 *
 * 例：extent = 1080px、scale = 0.9037 → 0.9037 * 1080 = 976.0 → 976/1080 = 0.90370…
 * 虽然看起来差不多，但**每帧的取整值会跳变**，边缘就会抖。
 * 吸附后缩放**只能取 1080 个离散值**，每一档都对应整数像素宽度。
 */
internal fun snapScaleToPixelExtent(scale: Float, extent: Float): Float =
    if (extent > 0f) (scale * extent).roundToInt() / extent else scale

/**
 * 把位移吸附到「缩放后内容边缘落在整像素」的位置。
 *
 * ## 为什么不能只吸附缩放
 *
 * 缩放以中心为轴时，左右两边各向内收 `extent*pivot*(1-scale)`，这个量通常不是整数。
 * 只吸附 scale，平移仍是小数 → 边缘还是落在半个像素上 → 仍然有毛边。
 *
 * 所以先把「平移 + 缩放造成的边缘偏移」当作整体取整，再减回去。
 * 这样**缩放后内容的边缘坐标**是整数，亚像素插值不会再发生。
 *
 * @param pivotFraction 缩放轴心在宽度上的比例（0.5 = 居中）。
 */
internal fun snapTranslationToPixelEdge(
    translation: Float,
    scale: Float,
    extent: Float,
    pivotFraction: Float = 0.5f,
): Float {
    if (extent <= 0f) return translation
    val scaledEdgeOffset = extent * pivotFraction * (1f - scale)
    return (translation + scaledEdgeOffset).roundToInt() - scaledEdgeOffset
}