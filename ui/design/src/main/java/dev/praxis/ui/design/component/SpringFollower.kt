package dev.praxis.ui.design.component

/**
 * 二阶阻尼弹簧逐帧积分器（照抄 pk-node 的实现）。
 *
 * ## 来源
 *
 * `/root/pk-node/public/app.js:50-102`。用户 2026-10-22 指定：
 * 「现在我们的丝滑滚动实现改为参考 pk-node 的实现，pk-node 的会更好」。
 *
 * ## 为什么框架动画（`animateScrollToItem` / `animateScrollBy`）不够
 *
 * 框架动画是**一次性、定长**的：给定起点、终点、时长，走完就结束。
 * 而流式输出/日志追加的场景里，**终点每来一批新内容就变一次**。
 * 于是每次新增都重启一次动画：
 *
 * ```
 * 新增 → 起动画(0→100) → 走到 30 → 又新增 → 重启动画(30→180) → 走到 60 → ...
 * ```
 *
 * 每次重启都把速度归零，观感就是一冲一停。pk-node 的做法是
 * **只启动一次、永不重启**：目标值每帧重新读取，弹簧自然接着追。
 * 速度连续，所以连贯；
 *
 * ## 三个参数为什么是这三个值
 *
 * 来自 pk-node 的 `app.js:50-52`，是调过的手感值：
 *
 * | 常量 | 值 | 作用 |
 * |---|---|---|
 * | [SPRING_K] | 130 | 刚度。越大越快，过大则过冲明显 |
 * | [SPRING_C] | 24 | 阻尼。临界阻尼 = 2√(k·m) ≈ 22.8，24 略过阻尼 → **不过冲** |
 * | [SPRING_M] | 1 | 质量。与刚度一起决定响应频率 |
 *
 * 选 24 而不是 22.8：略过阻尼保证「永远不冲过头再弹回来」，
 * 日志场景里过冲会让人误以为滚到了错误位置。
 *
 * ## 两个钳位都不是可选优化
 *
 * - [SPRING_MAX_DT]：掉帧钳位。设备卡一下（比如 200ms 没出帧），
 *   不钳位时 `dt=0.2` 会让弹簧一步跳出很远，表现为「突然瞬移」。
 * - [SPRING_REST_V]：速度阈值。**必须与位置阈值同时满足**才停止。
 *   只看位置会在目标附近无限微振（速度还很大但位置已经够近了），
 *   表现为「到底后还在轻微抖」。
 */
internal object SpringFollower {
    /** 刚度。 */
    const val SPRING_K = 130f

    /** 阻尼系数（略大于临界阻尼 2√(k·m) ≈ 22.8，保证不过冲）。 */
    const val SPRING_C = 24f

    /** 质量。 */
    const val SPRING_M = 1f

    /** 掉帧钳位（秒）：单帧 dt 不超过它。 */
    const val SPRING_MAX_DT = 0.032f

    /** 速度静止阈值（px/s）。 */
    const val SPRING_REST_V = 1.0f

    /** 位置静止阈值（px）。 */
    const val SPRING_REST_X = 0.5f

    /**
     * 单帧积分一步。
     *
     * 公式与 pk-node 逐字一致：
     * ```
     * a = (k * (target - x) - c * v) / m
     * v += a * dt
     * x += v * dt
     * ```
     *
     * @param x 当前位置（px）
     * @param v 当前速度（px/s），会被本函数按新值返回
     * @param target 目标位置（px）。调用方每帧现取 —— 内容变高时它会变大，
     *   弹簧自然接着追，**不需要重启**
     * @param dt 距上一帧的秒数，调用方负责钳位到 [SPRING_MAX_DT]
     * @return 新的 `(位置, 速度)`，以及是否已静止
     */
    fun step(x: Float, v: Float, target: Float, dt: Float): StepResult {
        val a = (SPRING_K * (target - x) - SPRING_C * v) / SPRING_M
        var nextV = v + a * dt
        var nextX = x + nextV * dt

        // 位置夹取：不许越过两端。
        // 越过起点时速度归零（pk-node 原实现 `if (s.x < 0) { s.x = 0; s.v = 0 }`）：
        // 不归零的话下一帧弹簧会带着负速度继续往外拉，表现为「贴住顶部抖动」。
        if (nextX < 0f) {
            nextX = 0f
            nextV = 0f
        }
        // 越过终点只夹位置、不清速度：pk-node 原实现是 `if (s.x > target) { s.x = target }`。
        // 保留速度是有意的 —— 目标随后变大时（新内容到达）能平滑接上。
        if (nextX > target) nextX = target

        val rest = kotlin.math.abs(target - nextX) < SPRING_REST_X &&
            kotlin.math.abs(nextV) < SPRING_REST_V
        return StepResult(position = nextX, velocity = nextV, atRest = rest)
    }

    /** 一步积分的结果。 */
    data class StepResult(
        val position: Float,
        val velocity: Float,
        /** 位置与速度都进入静止阈值内。 */
        val atRest: Boolean,
    )
}