package dev.praxis.ui.design.glass.kyant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import dev.praxis.ui.design.glass.animation.DampedDragAnimation
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 液态玻璃开关（Kyant backdrop 版）—— **Praxis 所有开关的统一实现**。
 *
 * 逐行移植自 apixiaoyuan `core/design/glass/kyant/LiquidToggle.kt`（296 行），
 * 仅改包名与低版本分流目标。
 *
 * ## 来源与「莫奈重写」
 *
 * 原始出处是 Kyant0/AndroidLiquidGlass 的 `catalog/components/LiquidToggle.kt`（Apache-2.0）。
 * 上游示例里颜色是**硬编码**的：
 *
 * ```kotlin
 * val accentColor = if (isLightTheme) Color(0xFF34C759) else Color(0xFF30D158)  // 苹果绿
 * val trackColor  = ...Color(0xFF787878).copy(0.2f)...
 * ```
 *
 * 直接抄会得到「永远是苹果绿」的开关，**与莫奈取色无关**。
 * 本实现（与上游 apixiaoyuan 的改造一致）全部换成 [MiuixTheme] 语义色：
 *
 * | 用途 | 上游硬编码 | 本实现（莫奈） |
 * |---|---|---|
 * | 选中轨道 | `0xFF34C759` 苹果绿 | `colorScheme.primary` |
 * | 未选中轨道 | `0xFF787878` @0.2 | `colorScheme.surfaceContainerHighest` |
 * | 滑块表面 | `Color.White` | `colorScheme.surfaceContainerHigh` |
 *
 * 于是 `ColorSchemeMode.MonetSystem` 下开关颜色跟随壁纸提取色；
 * 换主题/换壁纸时开关立即变色，与全局一致。
 * 注意：**不读 `isSystemInDarkTheme()`** —— 一律走主题语义（与 miuix 行为一致），
 * 否则「App 强制暗色但系统是亮色」时开关会取错配色。
 *
 * ## 视觉结构（三层）
 *
 * 1. **轨道**：`Capsule` 裁剪 + `drawBehind` 纯色，颜色 `lerp(track, accent, fraction)`；
 * 2. **滑块**：`drawBackdrop` 液态玻璃。静止时几乎全透明（只采样不模糊，省算力），
 *    按下时 `blur/lens/高光/内阴影` 随 `pressProgress` 从 0 渐入；
 * 3. **拖拽**：`DampedDragAnimation` 弹簧驱动，带按压缩放 1.5 与速度修正的挤压变形。
 *
 * ## 为什么滑块要 combined backdrop
 *
 * 滑块叠在轨道上，它要折射的是「轨道 + 轨道下面的页面内容」两层。
 * 只传外部 backdrop 会漏掉轨道那一层 → 滑块看起来「浮在轨道外面」。
 * 所以用 `rememberCombinedBackdrop(外部, 轨道自身)` 把两层合起来。
 *
 * ## 无障碍
 *
 * 语义 `Role.Switch`；点按与拖拽都走 `onDragStopped`（点按 = 拖拽 0 距离）。
 *
 * @param selected 当前是否开启（读值，供组合期快照）
 * @param onSelect 状态切换回调（拖拽结束或点按时触发一次）
 * @param backdrop 采样源。调用方一般用 [rememberPageBackdropOrFallback]：
 *   页面没有 provide [LocalKyantBackdrop] 时回退到纯色采样 —— 仍然渲染完整玻璃形状，
 *   只是不折射动态内容（这样调用点零改动、不会因为漏 provide 而变成黑块）
 * @param enabled 禁用时整体降透明度且不响应点击/拖拽
 */
@Composable
fun LiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // ── 高低版本分流 ────────────────────────────────────────────────
    //
    // kyant 的 `blur()` / `RuntimeShader` 在低版本是**直接 return、什么都不画**的
    // （isRenderEffectSupported = SDK≥31、isRuntimeShaderSupported = SDK≥33），
    // 于是 Android 7/8 上滑块玻璃会整个消失，只剩一个纯色圆点 —— 那比「没有玻璃」更糟。
    // 低版本改走自写 CPU 管线（[dev.praxis.ui.design.glass.low.LowLiquidToggle]），
    // 参数与下面这段完全同源（8dp 模糊 / 5–10dp 折射 / Capsule）。
    // 高版本一行未改，继续走下面的 kyant 实现。
    if (dev.praxis.ui.design.glass.GlassBackend.useLowPipeline) {
        dev.praxis.ui.design.glass.low.LowLiquidToggle(
            selected = selected,
            onSelect = onSelect,
            modifier = modifier,
            enabled = enabled,
        )
        return
    }

    // `DampedDragAnimation` 的回调是**构造时捕获**的（remember 只跑一次）。
    // 直接闭包引用会永久持有首帧的 lambda —— 开关数量多、每个带自己的回调查时，
    // 改了别的行会调到旧回调。rememberUpdatedState 保证每次读到最新。
    val currentSelected = rememberUpdatedState(selected)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentEnabled = rememberUpdatedState(enabled)

    // ── 莫奈色源（替代上游硬编码苹果绿/灰）──────────────────────────
    val colors = MiuixTheme.colorScheme
    val accentColor = colors.primary
    val trackColor = colors.surfaceContainerHighest
    val thumbSurface = colors.surfaceContainerHigh

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    // 滑块可移动距离 = 轨道内宽（64 - 40 - 2*2）= 20dp，保证拖拽量与视觉位移 1:1。
    val dragWidth = with(density) { 20f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (selected()) 1f else 0f) }

    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            // 按下放大到 1.5：手指按住时变大，是「抓住了」的反馈。
            pressedScale = 1.5f,
            onDragStarted = {},
            onDragStopped = {
                if (didDrag) {
                    // 禁用态：把拖拽结果还原，不触发回调。
                    if (!currentEnabled.value) {
                        fraction = if (currentSelected.value()) 1f else 0f
                        didDrag = false
                        return@DampedDragAnimation
                    }
                    // 越过中点即目标态（拖到 60% 松手应该开，不该弹回）。
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    currentOnSelect.value(fraction == 1f)
                    didDrag = false
                } else {
                    if (!currentEnabled.value) return@DampedDragAnimation
                    // 点按 = 拖拽 0 距离 → 直接翻转。
                    fraction = if (currentSelected.value()) 0f else 1f
                    currentOnSelect.value(fraction == 1f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) didDrag = dragAmount.x != 0f
                val delta = dragAmount.x / dragWidth
                // RTL 下镜像，否则「往右拖」会让滑块往左走。
                fraction =
                    if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                    else (fraction - delta).fastCoerceIn(0f, 1f)
            },
        )
    }

    // 拖拽值 → 动画器。
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }
            .collectLatest { f -> dampedDragAnimation.updateValue(f) }
    }
    // 外部 selected 变化 → 动画器（代码改状态也要动）。
    LaunchedEffect(Unit) {
        snapshotFlow { currentSelected.value() }
            .collectLatest { isSelected ->
                val target = if (isSelected) 1f else 0f
                if (target != fraction) {
                    fraction = target
                    dampedDragAnimation.animateToValue(target)
                }
            }
    }

    // 滑块的采样源：轨道（纯色层）。与外部 backdrop 组合后，
    // 滑块既折射轨道本色、又折射底下页面内容。
    val trackBackdrop = rememberLayerBackdrop()

    Box(
        // 禁用态整体降透明度（miuix disabled 观感）。
        modifier.then(if (enabled) Modifier else Modifier.alpha(0.38f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        // ── 轨道 ──────────────────────────────────────────────────
        Box(
            Modifier
                // 轨道被录进 trackBackdrop，供滑块采样。
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind {
                    // 读值放在 draw 阶段（对齐上游）：避免组合期重组，只触发重绘。
                    val f = dampedDragAnimation.value
                    drawRect(lerp(trackColor, accentColor, f))
                }
                .size(64f.dp, 28f.dp),
        )

        // ── 滑块（液态玻璃）────────────────────────────────────────
        Box(
            Modifier
                .graphicsLayer {
                    val f = dampedDragAnimation.value
                    val padding = 2f.dp.toPx()
                    translationX =
                        if (isLtr) lerp(padding, padding + dragWidth, f)
                        else lerp(-padding, -(padding + dragWidth), f)
                }
                .semantics { role = Role.Switch }
                // 拖拽/点按手势（DampedDragAnimation 提供的 modifier）。
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            // 按下时横向铺开：模拟液体被挤压前的「铺展」。
                            // 静止时 scaleY = 0 → 轨道那层完全不参与采样，
                            // 滑块边缘因此是干净的（不会带一圈轨道色的晕）。
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 0.75f, progress)
                            val scaleY = lerp(0f, 0.75f, progress)
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        },
                    ),
                    shape = { Capsule() },
                    effects = {
                        // 按下才出现模糊/折射（静止时是透亮玻璃，省算力）。
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            5f.dp.toPx() * progress,
                            10f.dp.toPx() * progress,
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress,
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f),
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress,
                        )
                    },
                    layerBlock = {
                        // 速度修正的挤压变形：往一个方向拖时横向拉伸、纵向压扁，
                        // 停下来回弹。系数上限 ±0.2，避免拖太快变成一条线。
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        // 滑块表面：常态用主题容器色（莫奈）；按下时透出更多玻璃质感。
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(thumbSurface.copy(alpha = 1f - progress * 0.6f))
                    },
                )
                .size(40f.dp, 24f.dp),
        )
    }
}