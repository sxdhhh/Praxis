// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
// 移植自 InstallerX-Revived:
//   app/src/main/java/com/rosan/installer/ui/animation/predictiveback/AospNavTransition.kt（260 行）
package dev.praxis.ui.navigation.transition

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import top.yukonga.miuix.kmp.nav.transition.NavGesture
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavRole
import top.yukonga.miuix.kmp.nav.transition.NavSettle
import top.yukonga.miuix.kmp.nav.transition.NavSettlePhase
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavSwipeEdge
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navDirectionalTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

/**
 * AOSP 观感的预测性返回转场（InstallerX 原版，逐行移植）。
 *
 * ## 它和 miuix 自带 `MiuixDefault` 的差别
 *
 * `MiuixDefault` 是「平台风格」：整页平移 + 轻微淡化。
 * 这一套是**系统返回**的观感，四件事同时发生：
 *
 *  1. **跟手缩放**：底层页从 1.0 缩到 0.9，进度直接用手指位移驱动（不是定时动画）；
 *  2. **96dp 漂移**：越接近完成，底层页往右漂得越多（模仿系统「往边上让」）；
 *  3. **贴边 hug**：底层页缩小后左右各有内缩，它的边缘会「贴」到屏幕边距上；
 *  4. **松手弹跳**：按松手速度注入一次阻尼正弦（bounceScale），
 *     快速划过去时能看到极轻微的过冲回弹。
 *
 * 少了第 4 项就「松手后硬停」，少了第 2 项就「只是缩小」。所以不能简化。
 *
 * ## 三段结构（照抄）
 *
 * ```
 * AospNavTransition = navDirectionalTransition(
 *     push          = ClassicActivityOpen,      // 正常进入
 *     pop           = ClassicActivityClose,     // 程序化返回（点返回键）
 *     predictivePop = CrossActivityPredictive,  // 手势返回（跟手 + 弹跳）
 * )
 * ```
 *
 * 为什么返回要分两种：手势返回需要读 `gesture`/`settle` 才能跟手；
 * 程序化返回（点顶栏返回键）没有手势上下文，走固定的 tween 才自然。
 * 混成一套会导致「点返回键时动画从手指位置开始」——那个位置根本不存在。
 */

// ── 弹跳参数（照抄上游常量，改任何一个都会让手感变味）────────────────
private const val BOUNCE_STIFFNESS = 200f
private const val BOUNCE_DAMPING = 0.75f
private const val BOUNCE_MAX_KICK = 1000f
private const val BOUNCE_MIN_KICK = 120f

// ── 渐隐窗口（毫秒/比例，用于程序化返回的淡出时序）──────────────────
private const val OPEN_FADE_START = 0.12f
private const val OPEN_FADE_SPAN = 0.71f
private const val CLOSE_FADE_START = 0.21f
private const val CLOSE_FADE_SPAN = 0.74f
private const val CLASSIC_FADE_DURATION = 83f
private const val OPEN_FADE_OFFSET = 50f
private const val CLOSE_FADE_OFFSET = 35f

/** 底层页最小缩放：系统返回里被压到 0.9。 */
private const val CROSS_ACTIVITY_MIN_SCALE = 0.9f

/** 漂移距离：系统返回里底层页让出的 96dp。 */
private val CrossActivityDrift = 96.dp

/** 贴边留白：hug 到边时保留的 8dp。 */
private val CrossActivityEdgeMargin = 8.dp

private val ClassicActivityMotion = NavMotion(
    programmatic = NavSettleSpec.Tween(durationMillis = 450, easing = FastOutExtraSlowIn),
)

/** 正常压栈：新页从右侧淡入 + 96dp 漂移，无 scrim。 */
private val ClassicActivityOpen: NavTransition = navGraphicsTransition(
    motion = ClassicActivityMotion,
    scrim = { 0f },
) { scope ->
    val depth = scope.relativeDepth
    val driftPx = with(scope.density) { CrossActivityDrift.toPx() }
    if (depth <= 0f) {
        val progress = topProgress(depth)
        translationX = (1f - progress) * driftPx
        alpha = if (scope.role == NavRole.Incoming) {
            val settle = scope.settle
            if (settle != null) {
                // 有 settle 时钟时用它：淡入与运动解耦，观感更接近系统。
                ((settle.elapsedMillis - OPEN_FADE_OFFSET) / CLASSIC_FADE_DURATION)
                    .coerceIn(0f, 1f)
            } else {
                ((progress - OPEN_FADE_START) / OPEN_FADE_SPAN).coerceIn(0f, 1f)
            }
        } else {
            1f
        }
    } else {
        translationX = -coverProgress(depth) * driftPx
    }
}

/** 程序化返回（点返回键）：当前页往右漂出 + 渐隐。 */
private val ClassicActivityClose: NavTransition = navGraphicsTransition(
    motion = ClassicActivityMotion,
    scrim = { 0f },
) { scope ->
    val depth = scope.relativeDepth
    val driftPx = with(scope.density) { CrossActivityDrift.toPx() }
    if (depth <= 0f) {
        val progress = topProgress(depth)
        translationX = (1f - progress) * driftPx
        alpha = if (scope.role == NavRole.Outgoing) {
            val settle = scope.settle
            if (settle != null) {
                (1f - (settle.elapsedMillis - CLOSE_FADE_OFFSET) / CLASSIC_FADE_DURATION)
                    .coerceIn(0f, 1f)
            } else {
                ((progress - CLOSE_FADE_START) / CLOSE_FADE_SPAN).coerceIn(0f, 1f)
            }
        } else {
            1f
        }
    } else {
        translationX = -coverProgress(depth) * driftPx
    }
}

/**
 * 手势返回（预测性返回主体）。
 *
 * 这是整份移植里唯一「复杂」的部分，复杂在**三种时间源要混用**：
 *
 *  - `gesture`：手指正在划，进度由手指决定；
 *  - `settle.phase == Commit`：松手后动画接管，进度由时钟决定；
 *  - 都没有：静止或纯程序化。
 *
 * 每一处 `if (gesture != null)` 都是在切换时间源，不是在写防御代码。
 * 「看起来可以合并」的地方合并后会立刻出现跳变 —— 上游这些分支都是调出来的。
 */
private val CrossActivityPredictive: NavTransition = navGraphicsTransition(
    opaqueDepth = 1f,
    motion = NavMotion(
        commit = NavSettleSpec.Tween(durationMillis = 450, easing = FastOutExtraSlowIn),
        cancel = NavSettleSpec.Spring(stiffness = 1500f),
    ),
    scrim = { scope ->
        val settle = scope.settle
        val gesture = scope.gesture
        when {
            // 松手提交：scrim 随时钟线性退场（450ms 内从 1 到 0）。
            settle?.phase == NavSettlePhase.Commit ->
                (1f - settle.elapsedMillis / 450f).coerceIn(0f, 1f)
            // 跟手中：把相对深度换算成「已完成比例」，避免跟手时背景忽明忽暗。
            gesture != null ->
                (
                    scope.relativeDepth.coerceIn(0f, 1f) /
                        (1f - gesture.progress).coerceAtLeast(0.01f)
                    ).coerceIn(0f, 1f)
            else -> scope.relativeDepth.coerceIn(0f, 1f)
        }
    },
) { scope ->
    val depth = scope.relativeDepth
    val gesture = scope.gesture
    val settle = scope.settle
    val committing = settle?.phase == NavSettlePhase.Commit
    val widthPx = scope.layoutSize.width.toFloat()
    val heightPx = scope.layoutSize.height.toFloat()
    val driftPx = with(scope.density) { CrossActivityDrift.toPx() }
    val bounce = bounceScale(settle, gesture)
    // hug 上限：缩放留出的左右内缩，减去贴边留白。
    val hugMax = (
        widthPx * (1f - CROSS_ACTIVITY_MIN_SCALE) / 2f -
            with(scope.density) { CrossActivityEdgeMargin.toPx() }
        ).coerceAtLeast(0f)
    // 右边缘起手时不做 hug：那一侧本来就要被手指带着走。
    val hugs = gesture?.swipeEdge != NavSwipeEdge.Right

    if (depth <= 0f) {
        // ── 顶层页（正在离开的那一页）────────────────────────────
        val progress = topProgress(depth)
        if (scope.role == NavRole.Outgoing && committing && gesture != null) {
            // 松手已提交：先按手势进度冻结缩放，再随时钟补完最后一段。
            val releaseProgress = (1f - gesture.progress).coerceAtLeast(0.01f)
            val post = (1f - progress / releaseProgress).coerceIn(0f, 1f)
            val releaseEasedProgress = shapedTopProgress(releaseProgress, gesture)
            val committedScale =
                CROSS_ACTIVITY_MIN_SCALE + (1f - CROSS_ACTIVITY_MIN_SCALE) * releaseEasedProgress
            val grown = committedScale + (1f - committedScale) * post
            scaleX = snapScaleToPixelExtent(grown * bounce, widthPx)
            scaleY = scaleX
            var tx = if (hugs) (1f - releaseEasedProgress) * hugMax else 0f
            tx += post * driftPx
            // 提交后 450ms 内快速淡出：底层页要「浮上来」，上层必须让位。
            alpha = (1f - 5f * (settle.elapsedMillis / 450f)).coerceAtLeast(0f)
            translationX = snapTranslationToPixelEdge(tx, scaleX, widthPx)
            translationY = snapTranslationToPixelEdge(
                translation = crossActivityYShift(
                    gesture = gesture,
                    height = heightPx,
                    scale = scaleX,
                    density = scope.density,
                ),
                scale = scaleY,
                extent = heightPx,
            )
        } else {
            val easedProgress = shapedTopProgress(progress, gesture)
            scaleX = snapScaleToPixelExtent(
                scale = (
                    CROSS_ACTIVITY_MIN_SCALE + (1f - CROSS_ACTIVITY_MIN_SCALE) * easedProgress
                    ) * bounce,
                extent = widthPx,
            )
            scaleY = scaleX
            translationX = snapTranslationToPixelEdge(
                translation = if (hugs) (1f - easedProgress) * hugMax else 0f,
                scale = scaleX,
                extent = widthPx,
            )
            alpha = when {
                // 跟手中离开：随手指退得比缩放更快，避免「半透明大字」拖在屏上。
                scope.role == NavRole.Outgoing && gesture != null -> {
                    val releaseProgress = (1f - gesture.progress).coerceAtLeast(0.01f)
                    (1f - (1f - progress / releaseProgress).coerceIn(0f, 1f) * 3.5f)
                        .coerceAtLeast(0f)
                }
                gesture != null -> 1f
                // 无手势的自然进入：前 20% 内淡入。
                else -> (progress / 0.2f).coerceIn(0f, 1f)
            }
            translationY = snapTranslationToPixelEdge(
                translation = crossActivityYShift(
                    gesture = gesture,
                    height = heightPx,
                    scale = scaleX,
                    density = scope.density,
                ),
                scale = scaleX,
                extent = heightPx,
            )
        }
    } else {
        // ── 被盖住的底层页（返回时正在浮上来的那一页）──────────────
        val cover = coverProgress(depth)
        val post = if (gesture != null) {
            val releaseProgress = gesture.progress
            if (releaseProgress >= 1f) {
                1f
            } else {
                (((1f - cover) - releaseProgress) / (1f - releaseProgress)).coerceIn(0f, 1f)
            }
        } else {
            1f - cover
        }
        val rawTranslationX = -(1f - post) * driftPx
        if (gesture != null) {
            // 跟手时底层页也要放大回 1.0：手势越往后，它越大。
            val travel = if (committing) gesture.progress else (1f - cover)
            val eased = BackGestureEasing.transform(travel.coerceIn(0f, 1f))
            val liveScale =
                CROSS_ACTIVITY_MIN_SCALE + (1f - CROSS_ACTIVITY_MIN_SCALE) * (1f - eased)
            scaleX = snapScaleToPixelExtent(
                (liveScale + (1f - liveScale) * post) * bounce,
                widthPx,
            )
            scaleY = scaleX
        }
        translationX = snapTranslationToPixelEdge(rawTranslationX, scaleX, widthPx)
        translationY = snapTranslationToPixelEdge(
            translation = crossActivityYShift(
                gesture = gesture,
                height = heightPx,
                scale = scaleX,
                density = scope.density,
            ),
            scale = scaleX,
            extent = heightPx,
        )
    }
}

/** AOSP 档位的对外出口。 */
val AospNavTransition: NavTransition = navDirectionalTransition(
    push = ClassicActivityOpen,
    pop = ClassicActivityClose,
    predictivePop = CrossActivityPredictive,
)

/**
 * 松手弹跳：一次阻尼正弦叠加到缩放上。
 *
 * ## 公式为什么是这样
 *
 * 上游要的是「像被推了一下、回弹到 1.0」的观感，所以用**衰减正弦**：
 *
 * ```
 * overlay = -(kick / ωd) * e^(-ζωt) * sin(ωd·t)
 * ```
 *
 *  - `kick` 由松手速度换算（`releaseVelocity * 100 * (1-0.9) * factor`），
 *    也就是「划得越快、弹得越明显」，但有上下限；
 *  - 下限 `BOUNCE_MIN_KICK` 只在划得很短（progress < 0.1）时给，
 *    否则「轻轻一划」会完全没有反馈；
 *  - 边缘手势（swipeEdge != None）乘以 2：边缘滑动本来就更「脆」。
 *
 * 结果 `(100 + overlay)/100` 并 `coerceAtMost(1f)`：只允许压小、不允许撑大 ——
 * 放大超过 1.0 会让内容溢出到圆角外，出现色块。
 */
private fun bounceScale(settle: NavSettle?, gesture: NavGesture?): Float {
    if (settle == null || settle.phase != NavSettlePhase.Commit || gesture == null) return 1f
    val factor = if (gesture.swipeEdge != NavSwipeEdge.None) 2f else 1f
    val floorKick = if (gesture.progress < 0.1f) BOUNCE_MIN_KICK else 0f
    val kick = (abs(settle.releaseVelocity) * 100f * (1f - CROSS_ACTIVITY_MIN_SCALE) * factor)
        .coerceIn(floorKick, BOUNCE_MAX_KICK)
    if (kick <= 0f) return 1f
    val omega = sqrt(BOUNCE_STIFFNESS)
    val omegaD = omega * sqrt(1f - BOUNCE_DAMPING * BOUNCE_DAMPING)
    val t = settle.elapsedMillis / 1000f
    val overlay =
        -(kick / omegaD) * exp(-BOUNCE_DAMPING * omega * t) * sin(omegaD * t)
    return ((100f + overlay) / 100f).coerceAtMost(1f)
}

/**
 * 跟手进度整形。
 *
 * 手指的位移是线性的，但动画不能线性：线性会让「划一点、页面动一点」显得发木。
 * 用 [BackGestureEasing] 把「离目标的剩余距离」重映射一遍，
 * 得到「开始就动得多、接近完成时变缓」的手感（与系统一致）。
 */
private fun shapedTopProgress(progress: Float, gesture: NavGesture?): Float =
    if (gesture == null) {
        progress
    } else {
        1f - BackGestureEasing.transform((1f - progress).coerceIn(0f, 1f))
    }

/**
 * 纵向跟手偏移：手指上下划时，页面跟着上下让一点。
 *
 * 阻尼用 `1 - (1-r)²`：手指刚动时几乎不动，越往后跟得越多 ——
 * 线性跟随会让页面「粘在手指上」，观感廉价。
 * 上限受「缩放后剩余的上下空白 - 贴边留白」限制，保证不会被划出边界。
 */
private fun crossActivityYShift(
    gesture: NavGesture?,
    height: Float,
    scale: Float,
    density: Density,
): Float {
    if (gesture == null || height <= 0f) return 0f
    val rawDelta = gesture.touchY - gesture.initialTouchY
    val half = height / 2f
    val ratio = min(half, abs(rawDelta)) / half
    val damped = 1f - (1f - ratio) * (1f - ratio)
    val marginPx = with(density) { CrossActivityEdgeMargin.toPx() }
    val maxShift = ((height - height * scale) / 2f - marginPx).coerceAtLeast(0f)
    return maxShift * damped * (if (rawDelta < 0f) -1f else 1f)
}