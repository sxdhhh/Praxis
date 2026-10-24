package dev.praxis.ui.design.glass.low

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp as lerpFloat
import dev.praxis.ui.design.glass.animation.DampedDragAnimation
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * **低版本开关** —— `kyant/LiquidToggle` 的降级实现（API 24–32）。
 *
 * 逐行移植自 apixiaoyuan `core/design/glass/low/LowLiquidToggle.kt`（175 行），
 * 仅改包名。
 *
 * ## 为什么低版本不做玻璃
 *
 * 上游的实测结论（2026-10-05，用户指令「算了低版本还是做降级处理吧」）：
 * 开关只有 64×28dp、玻璃可见部分极小，而采样开销（每个开关一个采样泵 +
 * 全屏抓图）与收益完全不成比例。所以低版本降级为**纯色滑块**，
 * **保留全部交互与动画**（拖拽 / 弹簧 / 点击 / 禁用态），只是不渲染玻璃。
 *
 * 这个取舍很关键：交互一致、视觉降级。反过来（视觉保留、交互退化）
 * 会让低版本用户觉得「这个 App 是残的」。
 *
 * ## 颜色为什么全部走 MiuixTheme 语义色
 *
 * Kyant 上游示例是硬编码（苹果绿 `0xFF34C759` / 灰 `0xFF787878`）。
 * 直接抄会**丢掉莫奈取色** —— 用户明确要求「重写莫奈取色」。
 * 本实现（与高版本分支完全同源）：
 *
 * | 用途 | 上游硬编码 | 本实现（莫奈） |
 * |---|---|---|
 * | 选中轨道 | 苹果绿 34C759 | `colorScheme.primary` |
 * | 未选中轨道 | 787878 @0.2 | `colorScheme.surfaceContainerHighest` |
 * | 滑块表面 | `Color.White` | `colorScheme.surfaceContainerHigh` |
 */
@Composable
internal fun LowLiquidToggle(
    selected: () -> Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // `DampedDragAnimation` 的回调是**构造时捕获**的（`remember` 只跑一次）。
    // 直接闭包引用会永久持有首帧的 lambda —— 开关多、每个带自己的回调时，
    // 改了别的行会调错回调。用 rememberUpdatedState 保证每次读最新。
    val currentSelected = rememberUpdatedState(selected)
    val currentOnSelect = rememberUpdatedState(onSelect)
    val currentEnabled = rememberUpdatedState(enabled)

    val colors = MiuixTheme.colorScheme
    val accentColor = colors.primary
    val trackColor = colors.surfaceContainerHighest
    val thumbSurface = colors.surfaceContainerHigh

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    // 滑块可移动距离 = 轨道内宽（64 - 40 - 2*2）→ 20dp。
    // 写死 20 而不是用 weight 之类：与上游同值，保证拖拽量与视觉位移 1:1。
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
            // 按下放大到 1.5：手指按住时滑块变大，是「抓住了」的反馈。
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
                    // 越过中点即判定为目标态（不是「反向」）：
                    // 拖到 60% 松手应该开，不该弹回。
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
                // RTL 下拖拽方向镜像，否则「往右拖」会让滑块往左走。
                fraction =
                    if (isLtr) (fraction + delta).fastCoerceIn(0f, 1f)
                    else (fraction - delta).fastCoerceIn(0f, 1f)
            },
        )
    }

    // 拖拽值 → 动画器（单向同步，供弹簧插值读取）。
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }.collectLatest { dampedDragAnimation.updateValue(it) }
    }
    // 外部 selected 变化 → 动画器（双向同步的另一半：代码改状态也要动）。
    LaunchedEffect(Unit) {
        snapshotFlow { currentSelected.value() }.collectLatest { isSelected ->
            val target = if (isSelected) 1f else 0f
            if (target != fraction) {
                fraction = target
                dampedDragAnimation.animateToValue(target)
            }
        }
    }

    Box(
        // 禁用态整体降透明度（与 miuix Switch(enabled=false) 的观感一致）。
        modifier.then(if (enabled) Modifier else Modifier.alpha(0.38f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        // ── 轨道：纯色，随 fraction 从灰渐变到主题色 ──────────────────
        Box(
            Modifier
                .clip(RoundedCornerShape(percent = 50))
                .drawBehind {
                    // 读值放在 draw 阶段：避免组合期重组，只触发重绘。
                    val f = dampedDragAnimation.value
                    drawRect(lerp(trackColor, accentColor, f))
                }
                .size(64.dp, 28.dp),
        )

        // ── 滑块：纯色胶囊 + 柔和阴影（降级：无玻璃采样）─────────────
        Box(
            Modifier
                .graphicsLayer {
                    val f = dampedDragAnimation.value
                    val padding = 2f.dp.toPx()
                    translationX =
                        if (isLtr) lerpFloat(padding, padding + dragWidth, f)
                        else lerpFloat(-padding, -(padding + dragWidth), f)
                }
                .then(dampedDragAnimation.modifier)
                .drawBehind {
                    // 柔和阴影（近似）：两层半透明黑圆，偏移在滑块下缘。
                    // 不用 elevation：那会引出真正的 shadow 通道，
                    // 在低版本 + 半透明背景下反而更脏。
                    val r = size.minDimension / 2f
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.10f),
                        radius = r + 0.75.dp.toPx(),
                        center = Offset(cx, cy + 2.25.dp.toPx()),
                    )
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.16f),
                        radius = r,
                        center = Offset(cx, cy + 1.5.dp.toPx()),
                    )
                }
                .clip(RoundedCornerShape(percent = 50))
                .background(thumbSurface)
                .size(40.dp, 24.dp),
        )
    }
}