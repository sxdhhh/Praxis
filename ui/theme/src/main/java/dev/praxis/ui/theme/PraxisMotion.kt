package dev.praxis.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * 动效令牌：全项目唯一的时长/曲线/弹簧来源。
 *
 * 硬约束：页面里不得内联 [androidx.compose.animation.core.tween] 的毫秒数，
 * 也不得内联 `spring(stiffness = 900f)`，一律引用本对象，保证全局节奏一致、
 * 后期只改一处就能整体调。
 *
 * 两个历史坑（都是真实踩过的，别改名）：
 *  1. 内层对象不要叫 `Easing`。它会遮蔽 `androidx.compose.animation.core.Easing`
 *     这个导入类型，让 `val Standard: Easing` 解析到内部 object，引发连锁类型错误。
 *  2. 返回类型必须是 [SpringSpec]，不是 `Spring<T>`。
 *     `Spring` 是只持有常量的 object，不是泛型类，写 `Spring<T>` 直接编译失败。
 */
object PraxisMotion {

    /** 时长（毫秒）。 */
    object Durations {
        /** 按下/抬起等即时反馈。 */
        const val Instant = 80

        /** 常规状态切换。 */
        const val Fast = 120

        /** 标准页面内过渡。 */
        const val Base = 240

        /** 强调型过渡（弹层、展开）。 */
        const val Slow = 380

        /** 大范围转场（进页/退页）。 */
        const val Emphasized = 500
    }

    /** 曲线。 */
    object Curves {
        val Linear = LinearEasing
        val Standard = FastOutSlowInEasing
        val Decelerate = LinearOutSlowInEasing
        val Accelerate = FastOutLinearInEasing

        /** Material 3 强调曲线，用于客制化转场。 */
        val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /** 快出极慢入，用于折叠/展开这类需要“先动再稳”的场景。 */
        val FastOutExtraSlowIn = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    }

    /** 弹簧规格。 */
    object Springs {
        /** 点击反馈：硬且快，无回弹。 */
        fun <T> Click(): SpringSpec<T> = spring(stiffness = 900f, dampingRatio = 0.9f)

        /** 位置跟随（丝滑滚动/指示器）。 */
        fun <T> Follow(): SpringSpec<T> = spring(stiffness = 450f, dampingRatio = 0.85f)

        /** 归位：略带回弹。 */
        fun <T> Settle(): SpringSpec<T> = spring(stiffness = 320f, dampingRatio = 0.72f)

        /** 无回弹归位，用于尺寸/透明度。 */
        fun <T> NoBounce(): SpringSpec<T> = spring(stiffness = 420f, dampingRatio = 1f)
    }

    /** 缩放系数。 */
    object Scales {
        /** 按下时的收缩比例（1f 为原尺寸）。 */
        const val Pressed = 0.96f

        /** 强调元素的轻微放大。 */
        const val Emphasis = 1.04f
    }

    /** 位移（dp）。 */
    object Offsets {
        /** 进入时的上浮距离。 */
        const val EnterSlide = 12

        /** 轻量内容推入距离。 */
        const val Nudge = 4
    }
}
