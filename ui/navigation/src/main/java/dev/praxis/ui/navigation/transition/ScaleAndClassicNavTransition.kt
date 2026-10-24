// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
// 移植自 InstallerX-Revived:
//   app/src/main/java/com/rosan/installer/ui/animation/predictiveback/
//     ScaleNavTransition.kt（124 行）/ ClassicNavTransition.kt（63 行）
package dev.praxis.ui.navigation.transition

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import dev.praxis.core.model.settings.PredictiveBackExitDirection
import top.yukonga.miuix.kmp.nav.transition.NavGesture
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavRole
import top.yukonga.miuix.kmp.nav.transition.NavSettlePhase
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeEdge
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.transition.navDirectionalTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

/**
 * Scale 档位：缩小 + 按手势方向移出屏幕（照抄 InstallerX `ScaleNavTransition`）。
 *
 * ## 与 AOSP 的差别
 *
 * | | AOSP | Scale |
 * |---|---|---|
 * | 缩放轴心 | 居中固定 | **跟随手指位置**（上下也会偏） |
 * | 移出方向 | 固定往右 | 可配置（跟手势 / 固定左 / 固定右） |
 * | 缩放下限 | 0.9 | **0.85**（更狠） |
 *
 * 这一档适合「分屏/大屏」观感：页面像卡片被推走，而不是被"擦"走。
 *
 * 轴心偏移是关键细节：手指在屏幕下半部划时，页面从**下方**那个点缩放，
 * 而不是几何中心 —— 这与系统返回里「内容从手指处收拢」的观感一致。
 * 轴心固定 0.5 会让动画显得「整块板子在动」。
 */
private val ScaleExitMotion = NavMotion(
    commit = NavSettleSpec.Tween(
        durationMillis = 200,
        easing = FastOutSlowInEasing,
    ),
    cancel = NavSettleSpec.Spring(stiffness = 1500f),
    programmatic = NavSettleSpec.Tween(
        durationMillis = 200,
        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    ),
)

/** 构造 Scale 档位的转场。[exitDirection] 决定退场往哪一侧。 */
fun scaleNavTransition(exitDirection: PredictiveBackExitDirection): NavTransition {
    val pop = navGraphicsTransition(
        opaqueDepth = 1f,
        motion = ScaleExitMotion,
        scrim = { scope ->
            when {
                // 松手提交：scrim 在 200ms 内退场（与 motion.commit 同长）。
                scope.settle?.phase == NavSettlePhase.Commit ->
                    (1f - (scope.settle?.elapsedMillis ?: 0f) / 200)
                        .coerceIn(0f, 1f)
                // 跟手时保持全黑底：这一档的缩放更狠，露出的底色必须稳定。
                scope.gesture != null -> 1f
                else -> coverProgress(scope.relativeDepth)
            }
        },
    ) { scope ->
        val depth = scope.relativeDepth
        val widthPx = scope.layoutSize.width.toFloat()
        val heightPx = scope.layoutSize.height.toFloat()
        val gesture = scope.gesture
        val sign = exitDirectionSign(exitDirection, scope)
        val committing = scope.settle?.phase == NavSettlePhase.Commit
        val outgoingCommit = scope.role == NavRole.Outgoing && committing && gesture != null

        if (depth <= 0f) {
            val progress = topProgress(depth)
            val pageScale = if (outgoingCommit) {
                // 松手已提交：缩放从「手势释放时的大小」起步，再随时钟补完。
                val releaseProgress = (1f - gesture.progress).coerceAtLeast(0.01f)
                val post = (1f - progress / releaseProgress).coerceIn(0f, 1f)
                val releaseEasedProgress = shapedTopProgress(releaseProgress, gesture)
                val committedScale = 0.85f + (1f - 0.85f) * releaseEasedProgress
                committedScale + (0.85f - committedScale) * post
            } else {
                val easedProgress = shapedTopProgress(progress, gesture)
                0.85f + (1f - 0.85f) * easedProgress
            }
            // 轴心：左边缘起手时偏右（内容从左侧收），否则偏左。
            val pivotX = if (gesture?.swipeEdge == NavSwipeEdge.Left) 0.8f else 0.2f
            val pivotY = gesturePivotY(gesture, heightPx)
            scaleX = snapScaleToPixelExtent(pageScale, widthPx)
            scaleY = scaleX
            transformOrigin = TransformOrigin(
                pivotFractionX = pivotX,
                pivotFractionY = pivotY,
            )
            val rawTranslationX = if (gesture != null && scope.settle == null) {
                // 纯跟手阶段：不额外位移，只靠缩放 + 轴心让内容「被手指带走」。
                0f
            } else if (outgoingCommit) {
                val releaseProgress = (1f - gesture.progress).coerceAtLeast(0.01f)
                val post = (1f - progress / releaseProgress).coerceIn(0f, 1f)
                sign * post * widthPx
            } else {
                sign * (1f - progress) * widthPx
            }
            translationX = snapTranslationToPixelEdge(
                translation = rawTranslationX,
                scale = scaleX,
                extent = widthPx,
                pivotFraction = pivotX,
            )
            translationY = snapTranslationToPixelEdge(
                translation = 0f,
                scale = scaleY,
                extent = heightPx,
                pivotFraction = pivotY,
            )
        }
    }
    return navDirectionalTransition(
        push = NavTransitions.MiuixDefault,
        pop = pop,
        predictivePop = pop,
    )
}

private fun shapedTopProgress(progress: Float, gesture: NavGesture?): Float =
    if (gesture == null) {
        progress
    } else {
        1f - BackGestureEasing.transform((1f - progress).coerceIn(0f, 1f))
    }

/**
 * 退场方向的符号。
 *
 * `FOLLOW_GESTURE` 时取「手势起手边缘」的反面：从左边缘划 → 内容往右退（+1），
 * 从右边缘划 → 往左退（-1）。这与系统「把页面推回它来的方向」的直觉一致。
 */
private fun exitDirectionSign(
    direction: PredictiveBackExitDirection,
    scope: NavTransitionScope,
): Float = when (direction) {
    PredictiveBackExitDirection.FollowGesture ->
        if (scope.gesture?.swipeEdge == NavSwipeEdge.Left) 1f else -1f
    PredictiveBackExitDirection.AlwaysRight -> 1f
    PredictiveBackExitDirection.AlwaysLeft -> -1f
}

/** 缩放轴心纵向位置：跟手指走，但夹在 0.1–0.9 之间（否则会缩成一条线）。 */
private fun gesturePivotY(gesture: NavGesture?, height: Float): Float =
    if (gesture != null && height > 0f) {
        (gesture.touchY / height).coerceIn(0.1f, 0.9f)
    } else {
        0.5f
    }

// ---------------------------------------------------------------------------
//  Classic：KernelSU 风格的轻量缩放 + 侧移
// ---------------------------------------------------------------------------

private val ClassicScaleMotion = NavMotion(
    commit = NavSettleSpec.Tween(
        durationMillis = 200,
        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    ),
    cancel = NavSettleSpec.Spring(stiffness = 1500f),
    programmatic = NavSettleSpec.Tween(
        durationMillis = 200,
        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    ),
)

/**
 * Classic 档位：**顶层页缩到 0.9 并淡出，底层页从左侧滑回原位**。
 *
 * 与 Scale 的差别在这一档用 `zIndex` 显式分层：
 * 底层页（relativeDepth > 0）被抬到上层，从 -width 滑回 0。
 * 这样观感是「下一层盖上来」而不是「当前层缩下去」，接近早期 KernelSU 管理器。
 *
 * 为什么不用 `graphicsLayer` 之外的手段：`zIndex` 只对同一父布局内的兄弟生效，
 * NavDisplay 的 entry 正好是兄弟，所以这一招成立且代价为零。
 */
private val ClassicScalePop: NavTransition = object : NavTransition {
    override val opaqueDepth: Float = 1f
    override val motion: NavMotion = ClassicScaleMotion

    override fun scrimFraction(scope: NavTransitionScope): Float =
        coverProgress(scope.relativeDepth)

    override fun Modifier.transformEntry(scope: NavTransitionScope): Modifier {
        // 被盖住的那一层要画在上面，才能「盖上来」。
        val zIndex = if (scope.relativeDepth > 0f) 1f else 0f
        return graphicsLayer {
            val depth = scope.relativeDepth
            val widthPx = scope.layoutSize.width.toFloat()
            val heightPx = scope.layoutSize.height.toFloat()
            if (depth <= 0f) {
                val progress = topProgress(depth)
                scaleX = snapScaleToPixelExtent(0.9f + 0.1f * progress, widthPx)
                scaleY = scaleX
                translationX = snapTranslationToPixelEdge(0f, scaleX, widthPx)
                translationY = snapTranslationToPixelEdge(0f, scaleY, heightPx)
                // 顶层页随进度淡出：这一档不看 scrim，靠自身 alpha 让位。
                alpha = progress
            } else {
                translationX = snapTranslationToPixelEdge(
                    translation = -coverProgress(depth) * widthPx,
                    scale = 1f,
                    extent = widthPx,
                )
            }
        }.zIndex(zIndex)
    }
}

/** Classic 档位的对外出口：压栈仍走 miuix 默认，返回走 ClassicScalePop。 */
val ClassicNavTransition: NavTransition = navDirectionalTransition(
    push = NavTransitions.MiuixDefault,
    pop = ClassicScalePop,
    predictivePop = ClassicScalePop,
)