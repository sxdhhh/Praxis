// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
// 移植自 InstallerX-Revived: ui/page/main/widget/card/AnimatedFluidBackground.kt（352 行）
package dev.praxis.ui.design.effect

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 关于页顶部的**流光背景**（四层径向渐变，随时间流动）。
 *
 * ## 来源
 *
 * 逐行移植自 InstallerX-Revived `AnimatedFluidBackground.kt`（352 行），
 * 保留全部数值：4 个 flow 颜色的 4500/3800/5200/4200ms 周期、
 * 三层的 8000/12000/10000ms alpha 呼吸、以及四层 Canvas 的圆心/半径公式。
 *
 * ## 为什么不能简化成「一个渐变动画」
 *
 * 用户明确反馈「背景的流光效果也没有」。这个效果的观感来自**多层不同频率叠加**：
 *
 * | 层 | 内容 | 观感贡献 |
 * |---|---|---|
 * | 1 | 3 个大圆（0.75–0.9 倍屏宽） | 整体的色相漂移 |
 * | 2 | 5 个中圆（0.55 倍） | 颜色的互相渗透 |
 * | 3 | 8 个快速小圆（0.2 倍，2.5 倍速） | 表面的细微涌动 |
 * | 4 | 1 个中心柔光 | 让整体不发灰 |
 *
 * 只留第 1 层就是「一个呼吸的色块」，正是此前被批评「一点都不像」的原因。
 * 所以这里不省任何一层。
 *
 * ## 为什么用 `withFrameNanos` 自己算时间
 *
 * 上游没用 `infiniteRepeatable` 驱动位移（那只能做 0→1→0 的往复，做不出
 * 无接缝的连续旋转）。它用一个自增的秒数 `timeState` 直接喂进 sin/cos，
 * 所以运动是**永不重复**的。这是效果「活」的关键。
 *
 * ## 性能：为什么合并成 1 个 Canvas + 限 30fps（2026-10-23 修）
 *
 * 旧实现是**四层独立 `Canvas`**，其中前三层各挂一层 `Modifier.alpha(...)`：
 *
 * ```kotlin
 * Canvas(Modifier.fillMaxSize().alpha(layerAlpha1)) { ... }   // ← 建 offscreen RenderNode
 * Canvas(Modifier.fillMaxSize().alpha(layerAlpha2)) { ... }   // ← 建 offscreen RenderNode
 * Canvas(Modifier.fillMaxSize().alpha(layerAlpha3)) { ... }   // ← 建 offscreen RenderNode
 * ```
 *
 * `Modifier.alpha` 不是「乘个系数」——它要求 Compose 为该节点**建一个离屏
 * RenderNode**，把内容先画到离屏缓冲，再整体按 alpha 合成回父层。
 * 三个这样的层 = 每帧 3 次离屏重绘 + 3 次离屏合成，而且每层都是全屏尺寸。
 *
 * 所以这个控件的瓶颈**不是填充率**（不是「画了太多像素」），而是
 * **离屏层数量**。据此有两条对应的修法：
 *
 * | 改法 | 收益 |
 * |---|---|
 * | 四层合并成 1 个 `Canvas` | 3 个离屏 RenderNode 全部消失 |
 * | `layerAlpha` 折进各 brush 颜色的 alpha | 呼吸效果保留，不再需要 `Modifier.alpha` |
 * | 帧循环限到 30fps | 重组次数减半（本效果周期是秒级，30fps 完全够） |
 *
 * ## 折 alpha 会不会改变观感
 *
 * 严格数学上不等价：分层的做法是「层内几个圆先以全 alpha 互相叠加，
 * 再整层乘 alpha」，合并后是「每个圆各自乘 alpha 再叠加」。差异只在
 * **同一层内圆的交叠处**，而这里的圆都是边缘渐变到全透明的径向渐变，
 * 交叠区的 alpha 本就很低 —— 实测肉眼无法区分。
 *
 * 反过来说，如果哪天真要精确还原分层合成，那也不该退回 `Modifier.alpha`
 * （代价太大），而应该把整层用 `drawIntoCanvas` + 离屏 Bitmap 手绘一次。
 *
 * @param baseColor 基调色。上游用 `primaryContainer`（跟随主题），
 *   Praxis 同样由调用方传入，控件不猜业务色。
 * @param enabled false 时完全不组合，也不启动帧循环。
 */
@Composable
fun AnimatedFluidBackground(
    baseColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return
    // 不再套一层 `Box`（2026-10-23）。
    //
    // 旧写法是 `Box(modifier) { AnimatedFluidBackgroundLayers(...) }` ——
    // 那一层只是为了让内部四个 `Canvas` 能共用同一尺寸。现在只有**一个**
    // `Canvas`，而它自己 `fillMaxSize()`，多出来的 `Box` 是纯冗余节点：
    // 每帧多一次测量/布局，且在 `Box` 上挂 `modifier` 会把尺寸约束
    // 与 `Canvas` 分开算，容易出现「Box 铺满、Canvas 按外层约束」的差异。
    AnimatedFluidBackgroundLayers(baseColor = baseColor, modifier = modifier)
}

@Composable
private fun AnimatedFluidBackgroundLayers(
    baseColor: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "fluid_background_transition")

    // ── 四个流动色 ────────────────────────────────────────────────
    // 每个色都是「基调色」与一个高饱和色 compositeOver 出来的。
    // 直接用高饱和色会让卡片变成霓虹灯；先压到 0.5–0.9 alpha 再叠，
    // 才能既看出色相变化、又不脱离主题色。
    val primaryFlow by transition.animateColor(
        initialValue = baseColor.copy(alpha = 0.9f),
        targetValue = baseColor.copy(alpha = 0.6f)
            .compositeOver(Color.Magenta.copy(alpha = 0.2f)),
        animationSpec = infiniteRepeatable(
            animation = tween(4500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "primary_flow",
    )
    val secondaryFlow by transition.animateColor(
        initialValue = baseColor.copy(alpha = 0.7f)
            .compositeOver(Color.Cyan.copy(alpha = 0.25f)),
        targetValue = baseColor.copy(alpha = 0.85f)
            .compositeOver(Color.Blue.copy(alpha = 0.15f)),
        animationSpec = infiniteRepeatable(
            animation = tween(3800, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "secondary_flow",
    )
    val accentFlow by transition.animateColor(
        initialValue = baseColor.copy(alpha = 0.6f)
            .compositeOver(Color.Yellow.copy(alpha = 0.1f)),
        targetValue = baseColor.copy(alpha = 0.8f)
            .compositeOver(Color.Green.copy(alpha = 0.18f)),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 5200,
                easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f),
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "accent_flow",
    )
    val complementFlow by transition.animateColor(
        initialValue = baseColor.copy(alpha = 0.5f)
            .compositeOver(Color.Red.copy(alpha = 0.12f)),
        targetValue = baseColor.copy(alpha = 0.75f)
            .compositeOver(Color(0xFFFF6B35).copy(alpha = 0.2f)),
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "complement_flow",
    )

    // ── 连续时间（永不重复的位移来源）─────────────────────────────
    //
    // ## 为什么限到 30fps（2026-10-23）
    //
    // 相对时间而不是绝对时间：`frameTimeNanos` 是开机以来的纳秒数，
    // 直接取秒会得到 1e5 量级的 float，`sin(t * 0.1)` 的精度会退化到
    // 肉眼可见的「卡顿式跳变」。用 `origin` 做基准后 t 从 0 开始，
    // 一次引导页最多跑几分钟，float 精度绰绰有余。
    //
    // 30fps 而不是 60：这个效果的周期是秒级（最慢 0.1 倍速 = 周期约 63 秒），
    // 30fps 对它的观感完全相同，而重组次数与 Canvas 重绘次数都减半。
    // 低版本（API 28~32）本来就是这条管线兜底，省一半是有意义的。
    //
    // 代价：`timeState` 每帧写一次 → 每帧重组。这是这个效果本身的模型
    // （圆心位置是 sin/cos 的函数，不可能用 State 之外的手段表达），
    // 所以优化点是「减少每帧做的事」，而不是「减少每帧」——
    // 后者靠合并离屏层已经做到了。
    var timeState by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val minDeltaNanos = 1_000_000_000L / 30L
        val origin = withFrameNanos { it }
        var lastEmit = origin
        while (true) {
            val now = withFrameNanos { it }
            if (now - lastEmit < minDeltaNanos) continue
            lastEmit = now
            timeState = (now - origin) / 1_000_000_000f
        }
    }
    // 三个不同倍率：不同层以不同速度移动，才不会有「整块一起晃」的廉价感。
    val time1 = timeState * 0.1f
    val time2 = timeState * 0.133f
    val time3 = timeState * 0.167f
    val microTime = timeState * 0.2f

    // ── 三层的 alpha 呼吸 ────────────────────────────────────────
    val layerAlpha1 by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "layer_alpha_1",
    )
    val layerAlpha2 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "layer_alpha_2",
    )
    val layerAlpha3 by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = FastOutLinearInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "layer_alpha_3",
    )

    // ── 单 Canvas 绘制四层（2026-10-23 合并）───────────────────────
    //
    // ## 为什么挤在一个 Canvas 里、画四段
    //
    // 旧实现是 4 个 `Canvas` 各画一层，前三层各挂 `Modifier.alpha`。
    // `Modifier.alpha` 会为节点建离屏 RenderNode（见文件头 KDoc 的性能说明），
    // 三个全屏离屏层在 API 28~32 的中低端机上是这个控件的全部开销来源。
    //
    // 合并后：一个 RenderNode、一次离屏（其实连一次都不需要，因为不再有 alpha），
    // 四段绘制按顺序在同一个 canvas 上叠。`layerAlpha` 乘进各段的 brush 颜色。
    //
    // ## 绘制顺序不能改
    //
    // 1（大）→ 2（中）→ 3（小）→ 4（中心柔光）：后画的盖在前面的上面。
    // 小圆必须在大圆之上，否则「表面涌动」会被大气团糊住；中心柔光必须最后，
    // 它的作用是「把中间提亮一档、防发灰」。
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerX = width / 2
        val centerY = height / 2
        val maxRadius = maxOf(width, height)

        // ══ 第 1 层：3 个大气团（整体色相漂移）═══════════════════
        run {
            val a = layerAlpha1
            val flowCenters = listOf(
                Offset(
                    x = centerX +
                        width * 0.45f * sin(time1 * 0.8f + 0.5f) +
                        width * 0.15f * cos(time2 * 0.7f),
                    y = centerY +
                        height * 0.4f * cos(time1 * 0.9f) +
                        height * 0.12f * sin(time2 * 1.1f + 1.2f),
                ),
                Offset(
                    x = centerX +
                        width * 0.5f * cos(time1 * 0.6f + 2.1f) +
                        width * 0.18f * sin(time2 * 0.9f + 0.8f),
                    y = centerY +
                        height * 0.42f * sin(time1 * 0.7f + 1.5f) +
                        height * 0.15f * cos(time2 * 0.8f + 2f),
                ),
                Offset(
                    x = centerX +
                        width * 0.38f * sin(time1 * 0.75f + 3.8f) +
                        width * 0.2f * cos(microTime * 1.2f),
                    y = centerY +
                        height * 0.35f * cos(time1 * 0.85f + 2.7f) +
                        height * 0.17f * sin(microTime * 1f + 1.1f),
                ),
            )
            val radii = listOf(
                maxRadius * 0.8f + maxRadius * 0.12f * sin(microTime * 0.8f),
                maxRadius * 0.75f + maxRadius * 0.15f * cos(microTime * 0.9f + 1f),
                maxRadius * 0.85f + maxRadius * 0.1f * sin(microTime * 1.1f + 2.3f),
            )
            val colors = listOf(primaryFlow, secondaryFlow, accentFlow)
            for (i in flowCenters.indices) {
                val fluidColor = colors[i]
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            fluidColor.copy(alpha = fluidColor.alpha * a),
                            fluidColor.copy(alpha = fluidColor.alpha * 0.6f * a),
                            fluidColor.copy(alpha = 0f),
                        ),
                        center = flowCenters[i],
                        radius = radii[i],
                    ),
                    center = flowCenters[i],
                    radius = radii[i],
                )
            }
        }

        // ══ 第 2 层：5 个互相渗透的中气团（颜色互相渗透）═════════
        run {
            val a = layerAlpha2
            val fluidCenters = (0..4).map { i ->
                val phase = i * PI.toFloat() / 2.5f
                Offset(
                    x = centerX +
                        width * 0.35f * sin(time2 * 0.6f + phase) +
                        width * 0.2f * cos(time3 * 0.5f + phase * 1.5f) +
                        width * 0.08f * sin(microTime * 1.5f + phase * 0.8f),
                    y = centerY +
                        height * 0.32f * cos(time2 * 0.7f + phase * 1.2f) +
                        height * 0.25f * sin(time3 * 0.6f + phase * 0.7f) +
                        height * 0.1f * cos(microTime * 1.3f + phase * 1.3f),
                )
            }
            val fluidRadii = (0..4).map { i ->
                val baseRadius = maxRadius * (0.55f + 0.15f * sin(i.toFloat()))
                baseRadius + maxRadius * 0.12f * cos(microTime * (0.8f + i * 0.2f))
            }
            val fluidColors = listOf(
                secondaryFlow.copy(alpha = secondaryFlow.alpha * 0.8f * a),
                accentFlow.copy(alpha = accentFlow.alpha * 0.7f * a),
                complementFlow.copy(alpha = complementFlow.alpha * 0.9f * a),
                primaryFlow.copy(alpha = primaryFlow.alpha * 0.6f * a),
                secondaryFlow.copy(alpha = secondaryFlow.alpha * 0.75f * a),
            )
            for (i in fluidCenters.indices) {
                val fluidColor = fluidColors[i]
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            fluidColor,
                            fluidColor.copy(alpha = fluidColor.alpha * 0.4f),
                            fluidColor.copy(alpha = fluidColor.alpha * 0.1f),
                            fluidColor.copy(alpha = 0f),
                        ),
                        center = fluidCenters[i],
                        radius = fluidRadii[i],
                    ),
                    center = fluidCenters[i],
                    radius = fluidRadii[i],
                )
            }
        }

        // ══ 第 3 层：8 个快速涌动的小气团（表面质感）═════════════
        run {
            val a = layerAlpha3
            val fastTime = microTime * 2.5f
            val mediumTime = time1 * 1.5f
            val texturePoints = (0..7).map { i ->
                val angle = i * 2 * PI.toFloat() / 8
                val dynamicRadius =
                    width * 0.3f + width * 0.2f * sin(fastTime + angle * 1.5f)
                val turbulentOffset =
                    width * 0.06f * cos(fastTime * 0.8f + angle * 2f)
                Offset(
                    x = centerX +
                        dynamicRadius * cos(angle + mediumTime * 0.3f) +
                        turbulentOffset,
                    y = centerY +
                        dynamicRadius * sin(angle + mediumTime * 0.3f) +
                        height * 0.05f * sin(fastTime * 1.2f + angle),
                )
            }
            for (i in texturePoints.indices) {
                val dynamicRadius =
                    maxRadius * (0.2f + 0.1f * sin(fastTime + i * 0.5f))
                // 这里的 alpha 已经含呼吸，再乘层 alpha。
                val opacity =
                    (0.25f + 0.15f * cos(fastTime * 0.7f + i * 0.3f)) * a
                val textureColor = when (i % 4) {
                    0 -> primaryFlow
                    1 -> secondaryFlow
                    2 -> accentFlow
                    else -> complementFlow
                }.copy(alpha = opacity)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            textureColor,
                            textureColor.copy(alpha = 0f),
                        ),
                        center = texturePoints[i],
                        radius = dynamicRadius,
                    ),
                    center = texturePoints[i],
                    radius = dynamicRadius,
                )
            }
        }

        // ══ 第 4 层：中心柔光（防止整体发灰）═════════════════════
        run {
            val glowTime = time1 * 0.5f
            val glowCenter = Offset(width / 2, height / 2)
            val glowRadius =
                maxRadius * (0.75f + 0.15f * sin(glowTime * 0.6f))
            val glowIntensity =
                0.08f + 0.04f * cos(glowTime * 0.8f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        baseColor.copy(alpha = glowIntensity),
                        baseColor.copy(alpha = glowIntensity * 0.5f),
                        Color.Transparent,
                    ),
                    center = glowCenter,
                    radius = glowRadius,
                ),
                center = glowCenter,
                radius = glowRadius,
            )
        }
    }
}