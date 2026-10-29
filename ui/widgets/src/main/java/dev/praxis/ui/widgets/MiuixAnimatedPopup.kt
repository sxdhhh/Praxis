package dev.praxis.ui.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.praxis.ui.theme.PraxisMotion

/** 进场时长：与 [MiuixAnimatedDialog] 一致（240ms）。 */
private const val POPUP_ENTER_MS = PraxisMotion.Durations.Base

/** 退场时长：比进场短（160ms），理由同 [MiuixAnimatedDialog]。 */
private const val POPUP_EXIT_MS = 160

/** 进场起始缩放。0.94 = 「微微长出」，不从远处飞来。 */
private const val POPUP_ENTER_SCALE = 0.94f

/** 退场终态缩放。 */
private const val POPUP_EXIT_SCALE = 0.97f

/**
 * 带进场/退场动画的 [Popup]。
 *
 * ## 为什么需要这个组件
 *
 * 项目里所有**弹窗**（Dialog）都走 [MiuixAnimatedDialog]，有进场/退场；
 * 但 `Popup`（贴在控件旁边的浮层，例如对话设置面板、模型胶囊列表）
 * 一直是 `if (show) { Popup { ... } }` —— 挂上就出现、关掉就整块消失。
 * 同一个应用里两种手感，用户能直接感觉出来。
 *
 * ## 与 [MiuixAnimatedDialog] 同一套「mounted / visible 分离」
 *
 * `visible` 是调用方想要的**终态**；组件内部用 `mounted` 记住「window 还挂着」。
 * 关闭时：`visible` 先变 false → 仍按 `mounted = true` 继续播退场动画 →
 * 播完才把 `mounted` 置 false，拆掉 window。
 *
 * ## ⚠️ 调用点必须常驻挂载
 *
 * ```kotlin
 * // ✗ 错：visible 变 false 那一刻组件就被移除，退场动画来不及播
 * if (show) { MiuixAnimatedPopup(visible = show) { ... } }
 *
 * // ✓ 对：组件一直在，visible 只是一个参数
 * MiuixAnimatedPopup(visible = show) { ... }
 * ```
 *
 * ## 为什么动画作用在内容上而不是 window 上
 *
 * `Popup` 的 window 尺寸由内容决定。若按 window 做缩放，窗口本身要重新
 * 申请区域 —— 与 `ChatSettingsBar` 里那段「Popup 窗口每帧重新定位导致抖动」
 * 是同一种问题。所以这里 window 尺寸恒定为内容**终态**的尺寸，
 * 只用 `graphicsLayer` 做缩放与透明度（纯绘制变换，不参与测量）。
 *
 * 副作用：进场时占位已经是最终大小，周围控件不会跟着动画移动。
 * 对「贴在控件旁的小浮层」而言这正是期望行为（它本来就该盖在内容上）。
 *
 * @param visible 是否应该显示（调用方持有状态）
 * @param onDismissRequest 点外部 / 返回键时回调。**不改** `visible`，
 *   由调用方置 false，避免出现两个真相。
 * @param alignment 浮层相对屏幕的对齐
 * @param properties 透传给 [Popup]
 * @param content 内容
 */
@Composable
fun MiuixAnimatedPopup(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopStart,
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable () -> Unit,
) {
    // ## 初值取 visible
    //
    // 与 `MiuixAnimatedDialog` 同一个理由：调用方若「组合时 visible 就是 true」
    // （页面一进来就打开），初值写 false 会让第一帧不挂 window，闪一下。
    var mounted by remember { mutableStateOf(visible) }
    val progress = remember { Animatable(if (visible) 1f else 0f) }

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = POPUP_ENTER_MS,
                    easing = PraxisMotion.Curves.Decelerate,
                ),
            )
        } else {
            // 进来时 mounted 是 false（从未显示过），不必播退场。
            if (!mounted) return@LaunchedEffect
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = POPUP_EXIT_MS,
                    easing = PraxisMotion.Curves.Accelerate,
                ),
            )
            // 播完才拆 window。
            mounted = false
        }
    }

    if (!mounted) return

    Popup(
        alignment = alignment,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress.value
                    // 缩放：从 0.94 → 1（进场）；退场 1 → 0.97。
                    // 两个终态不同，所以按方向插值而不是固定公式 ——
                    // 公式化的 `0.94 + 0.06 * p` 在退场时会退到 0.94，
                    // 比出场终态更小，观感是「缩过头」。
                    scaleX = if (progress.targetValue == 1f) {
                        POPUP_ENTER_SCALE + (1f - POPUP_ENTER_SCALE) * p
                    } else {
                        POPUP_EXIT_SCALE + (1f - POPUP_EXIT_SCALE) * p
                    }
                    scaleY = scaleX
                    alpha = p
                },
        ) {
            content()
        }
    }
}
