package dev.praxis.ui.design.component

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 揭示节奏引擎：控制「文字如何出现」。
 *
 * ## 为什么需要它（2026-11-06）
 *
 * 流式回复是一次 append 一整段 delta。慢速网络下 delta 很小、看不出问题；
 * 但一旦网关合并推送（一次送几百字），界面上就是**一屏文字瞬间刷出来**，
 * 眼睛跟不上，也失去了「模型正在写」的感觉。
 *
 * 更糟的是换行：一行文字到达时高度一次性跳 24~28px，滚动跟着跳，
 * 视觉上像被「踢」了一下。
 *
 * 这个引擎解决两件事：
 *
 *  1. **出字速度自适应积压** —— `v = 90 + backlog^1.25 × P`。
 *     积压少时从容（接近舒适阅读速度），积压多时平稳追赶，
 *     但**绝不一次性倾泻整段**。
 *  2. **换行位移平滑** —— 单帧视觉位移不超过 [MAX_FRAME_DX]，
 *     把一次 24~28px 的跳跃摊到数帧完成，消除「踢移」。
 *
 * ## 为什么用 `backlog^1.25` 而不是线性
 *
 * 线性（`v = 90 + backlog × P`）在积压大时速度会线性暴涨 ——
 * 积压 1000 字时速度直接飙到每秒上千字，等同于「一次性倾泻」。
 * 指数 1.25 让追赶是**加速但不失控**：积压翻 10 倍，速度只涨约 17.8 倍
 * 的平方根量级，用户仍能跟读。
 */
object RevealEngine {

    /** 基础出字速度（字符/秒）。接近舒适阅读速度，低负载时就是这个值。 */
    const val BASE_SPEED = 90.0

    /** 积压系数 P。越大追赶越快、但越容易「刷屏」。 */
    const val BACKLOG_FACTOR = 0.9

    /** 积压指数。见类文档「为什么用 1.25」。 */
    const val BACKLOG_EXPONENT = 1.25

    /** 单帧视觉位移上限（px）。超过就摊到后续帧。 */
    const val MAX_FRAME_DX = 8f

    /**
     * 背压最低倍速。
     *
     * ## 为什么是 0.55 而不是更低
     *
     * 背压的目的是「让跟随引擎追得上」，不是「停下来」。
     * 低于 0.5 时用户会明显感到卡顿，反而比不揭示更糟。
     */
    const val MIN_BACKPRESSURE = 0.55f

    /**
     * 单帧最多揭示的字符数（绝对上限）。
     *
     * ## 为什么必须有它（单测抓出来的真实缺陷）
     *
     * 只有 `v = 90 + backlog^1.25 × 0.9` 时，backlog = 1000 算出 5151 字/秒，
     * 32ms 一帧就是 165 字 —— 那已经不是「追赶」而是「倾泻」，
     * 眼睛完全跟不上，揭示引擎等于没做。
     *
     * 60 字/帧 ≈ 1875 字/秒：一帧最多填满一行多，是「能跟读」的速度上限。
     */
    const val MAX_CHARS_PER_FRAME = 60

    /** 低于此帧率视为卡顿（用于暂停揭示）。 */
    const val LOW_FPS = 30.0

    /**
     * 单帧时间上限（秒）。与 `SpringFollower.SPRING_MAX_DT` 同值。
     *
     * ## 为什么必须钳位
     *
     * 主线程卡了 200ms 才出一帧时，`dt = 0.2` 会让揭示一次吐出
     * `0.2 × v` 个字 —— 画面恢复后出现一个突兀的大跳。
     * 钳到 32ms 相当于「卡顿期间时间静止」，恢复后接着走。
     */
    const val MAX_DT = 0.032f

    /**
     * 本帧应揭示的字符数。
     *
     * @param backlog 待显示字符数（已收到但还没揭示的）。
     * @param dt 距上帧秒数，调用方须钳位到 [MAX_DT]。
     * @param backpressure 背压倍速，取值在 [MIN_BACKPRESSURE] 到 1 之间。
     * @param paused 是否因低帧率 + 屏幕外而暂停。
     * @return 本帧应追加的字符数（0 表示本帧不动）。
     */
    fun charsThisFrame(
        backlog: Int,
        dt: Float,
        backpressure: Float = 1f,
        paused: Boolean = false,
    ): Int {
        if (backlog <= 0) return 0
        if (paused) return 0
        val safeDt = dt.coerceIn(0.001f, MAX_DT)
        val speed = BASE_SPEED + backlog.toDouble().pow(BACKLOG_EXPONENT) * BACKLOG_FACTOR
        val scaled = speed * backpressure.coerceIn(MIN_BACKPRESSURE, 1f)

        // ## 上界：每帧最多 [MAX_CHARS_PER_FRAME] 字（2026-11-06，单测抓出来的）
        //
        // 只有公式时，backlog = 1000 会算出 5151 字/秒 —— 32ms 一帧就是 **165 字**。
        // 那已经不是「追赶」而是「倾泻」：一屏文字在一两帧内刷完，
        // 眼睛完全跟不上，揭示引擎等于没做。
        //
        // 所以除了「随积压加速」之外还必须有一个**绝对上限**。
        // 60 字/帧 ≈ 1875 字/秒，一帧内最多填满一行多；再快就不是读得下来的速度了。
        // 积压再多也只是「持续以最高速追赶」，而不是一次吐完。
        val capped = (scaled * safeDt).roundToInt().coerceAtMost(MAX_CHARS_PER_FRAME)

        // 至少 1 个字符：否则积压很小（1-2 字）时 `speed × dt` 会取整成 0，
        // 文字永远出不来 —— 表现为「回复卡住不动」。
        return capped.coerceIn(1, backlog)
    }

    /**
     * 由「视觉滞后 / 预留空间」算背压倍速。
     *
     * ## 闭环背压的意义
     *
     * 揭示引擎决定「文字出多快」，跟随引擎决定「视口滚多快」。两者独立跑时，
     * 文字可能长得比弹簧追得更快 —— 滞后越积越多，最终文字冲出视口底部，
     * 用户看到的是「字在屏幕上乱飞」。
     *
     * 所以把跟随引擎的滞后反馈给揭示引擎：滞后接近预留空间时降速到 0.55×，
     * 让弹簧追上再恢复正常速度。
     *
     * @param lagPx 跟随引擎当前的视觉滞后（px）。
     * @param reservePx 为跟随预留的空间（px）。
     */
    fun backpressure(lagPx: Float, reservePx: Float): Float {
        if (reservePx <= 0f) return 1f
        val ratio = (lagPx / reservePx).coerceIn(0f, 1f)
        return 1f - (1f - MIN_BACKPRESSURE) * ratio
    }

    /**
     * 换行位移平滑。
     *
     * ## 为什么需要它
     *
     * 一行文字到达时，内容高度一次性增加一整行（24~28px）。
     * 滚动容器随之跳一格，观感是「被踢了一下」。
     *
     * 做法：把这次跳跃记成「欠账」carry，本帧只放出 [MAX_FRAME_DX]，
     * 剩下的留到后续帧 —— 于是 28px 的跳跃摊成 4 帧完成。
     *
     * @param deltaPx 本帧新增的视觉位移（可能是负的：折行会把行数减回去）。
     * @param carry 上一帧遗留的欠账（px）。
     * @return 第一个元素是本帧应放出的位移，第二个是新的欠账。
     */
    fun smoothLineAdvance(deltaPx: Float, carry: Float): Pair<Float, Float> {
        val total = carry + deltaPx
        // 负向（内容变矮）不需要平滑：往回缩是瞬时的，用户不会觉得被踢。
        if (total <= MAX_FRAME_DX && total >= -MAX_FRAME_DX) return total to 0f
        if (total < 0f) return total to 0f
        return MAX_FRAME_DX to (total - MAX_FRAME_DX)
    }

    /**
     * 是否需要暂停揭示。
     *
     * ## 判据是「低帧率**且**回复在屏幕外」
     *
     * 只判低帧率会误伤：用户在读屏幕上的内容时，设备偶尔掉帧是正常的，
     * 此时暂停揭示会让文字卡住不动，反而更难读。
     *
     * 只有「设备卡 + 反正也看不见」时才值得停 —— 等条件改善后一次性补上，
     * 用户无感。
     */
    fun shouldPause(fps: Double, bubbleVisible: Boolean): Boolean =
        fps < LOW_FPS && !bubbleVisible

    /**
     * 帧率估计（指数滑动平均）。
     *
     * ## 为什么用 EMA 而不是取瞬时值
     *
     * 瞬时帧率抖动极大（一帧 16ms、下一帧 33ms 就翻倍），直接拿来判
     * 「是否暂停」会让揭示频繁启停，表现为文字一卡一卡。
     *
     * @param previous 上次估计值；首次传 0 表示还没估计过。
     * @param frameSeconds 本帧耗时（秒）。
     */
    fun estimateFps(previous: Double, frameSeconds: Float): Double {
        if (frameSeconds <= 0f) return previous
        val instant = 1.0 / frameSeconds
        if (previous <= 0.0) return instant
        // α = 0.2：约 5 帧追上变化，足够平滑又不至于迟钝。
        return previous * 0.8 + instant * 0.2
    }
}
