package dev.praxis.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
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

        /**
         * `sinOut` —— 正弦减速（2026-10-24 为 1:1 复刻 HyperCeiler 引入）。
         *
         * HyperCeiler 的开屏动画用 `fan.animation.FolmeEase.sinOut(ms)`。
         * Compose 的 `Easing` 是一组**固定曲线**，没有 sine 族，所以这里
         * 用 `Easing { }` 直接给出函数：`y = sin(x · π/2)`。
         *
         * 与原版一致的地方：起点斜率为 π/2（视觉上「立刻起步」），
         * 终点斜率为 0（平滑停住）。这正是开屏按钮淡入看起来「很轻」的原因。
         *
         * 夹一次 `coerceIn`：浮点误差可能让 x=1 时给出 1.0000001，
         * alpha 会被裁剪但 scale 会真的放大一点点 —— 肉眼可见地「抖一下」。
         */
        val SineOut = Easing { x ->
            kotlin.math.sin(x * (Math.PI / 2)).toFloat().coerceIn(0f, 1f)
        }

        /**
         * `cubicOut` —— 三次减速，等价 CSS `ease-out`。
         *
         * 原版 `FolmeEase.cubicOut(450L)` 用它做按钮入场、`cubicOut(700L)`
         * 做图标落定。Compose 没有现成的 cubicOut（`LinearOutSlowInEasing`
         * 是 Material 的近似，形状不同），标准控制点 (0.215, 0.61, 0.355, 1)。
         */
        val CubicOut = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

        /**
         * `quartOut` —— 四次减速，比 cubicOut 更「先快后极慢」。
         *
         * 原版 `FolmeEase.quartOut(1500L)` 用它做完成页 logo 入场：
         * 前 1/3 时间走完 70% 位移，后 2/3 只走 30% ——
         * 「一下子滑到位，然后缓缓贴合」。标准控制点 (0.165, 0.84, 0.44, 1)。
         */
        val QuartOut = CubicBezierEasing(0.165f, 0.84f, 0.44f, 1f)
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

    /**
     * 展开 / 收起类过渡（`AnimatedVisibility` 的 enter / exit）。
     *
     * ## 为什么单独一组，而不是复用 [Durations]
     *
     * 这四个数值不是「随便挑的时长」，是**上游 Operit 的原值**：
     * 进入 fade 300 + expand 400，退出 fade 250 + shrink 350。
     *
     * 它们与 [Durations] 的刻度（120 / 240 / 380 / 500）对不上，而且不对称
     * ——退出比进入快，这是刻意的：收起时用户已经决定了，多等 50ms 就嫌慢；
     * 展开时多看 50ms 会觉得「有内容长出来」。
     *
     * 项目规矩是「照抄不重写」，所以这里保留原值不动，只是把它们从 8 处
     * 散落的 `tween(300, easing = FastOutSlowInEasing)` 收成一个具名常量。
     * 改一处即可全局生效，且不再需要每处都重复写 easing。
     */
    object Expansion {
        /** 进入：淡入时长。 */
        const val FadeIn = 300

        /** 进入：纵向展开时长（比淡入慢，先见轮廓再见内容）。 */
        const val Expand = 400

        /** 退出：淡出时长。 */
        const val FadeOut = 250

        /** 退出：纵向收起时长。 */
        const val Shrink = 350
    }
}
