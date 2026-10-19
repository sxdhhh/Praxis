package dev.praxis.ui.design.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * 日志/流水列表的**自动跟随滚动**（★ 2026-09-30 v5：dsh 同款「弹簧跟随引擎」）。
 *
 * ## 用户要的语义（逐字）
 *
 * > 「当到达底部时会介入自动滚动，当用户上滑后关闭自动滚动，
 * >   当下滑到底后又介入自动滚动，接着滚动要有丝滑的动画」
 * >
 * > 「延迟 1 条日志当缓冲然后按照日志速度匹配滚动速度连贯起来」
 * > 「滚动的速度应该和日志输出的速度匹配，做到连贯性下滑，和 DeepSeek 一样」
 *
 * ## 依据：dsh-smooth-stream 的「弹簧跟随引擎」
 *
 * 用**二阶阻尼弹簧**把「内容高度的离散变化」转成**每帧连续的速度/位移轨迹**：
 *
 * ```
 * a = (k * (target - x) - c * v) / m     // 弹簧力 - 阻尼力
 * v += a * dt
 * x += v * dt
 * ```
 *
 * k=130 / c=24 / m=1（与 dsh 一致）；掉帧时 `dt` 钳位 ≤ 32ms（掉帧自愈）。
 *
 * ## ★★ v4 的死因（真机症状：「日志就不丝滑了，又变成闪帧式的」）
 *
 * v4 把弹簧状态定义为 **`LaunchedEffect(itemCount)` 里的局部变量** —— 这是**错的**：
 * 每来一条日志 `itemCount` 都变 → **协程被取消重启** → 局部变量 `v` 每次归零。
 * 弹簧因此永远停在「起步加速」的头几帧，永远进不到匀速段 →
 * 视觉上就是**一帧一停的闪帧式**，而不是连续下滑。
 *
 * ## v5 的修法：**弹簧状态放进 `remember`，任何重启都丢不掉速度**
 *
 *  - `x` / `v` / `lastFrame` 存在 [Spring] 里，通过 `remember { }` 持有；
 *  - 协程只做「推进」这一件事，且**重复启动是幂等的**（多一层 `busy` 闸门，
 *    同一时刻只有一帧引擎在跑）；
 *  - 新日志到达时：若引擎已在跑 → 什么都不做（它每帧会自己读最新 `maxValue`）；
 *    若已停 → 唤醒它继续跑。**速度 `v` 从不归零** → 连贯。
 *  - 空闲（追到目标）即 **break 停车**，不再请求帧（不空转、不阻止休眠）。
 *
 * ## 另外两个前几版踩过的坑（v5 保留修复）
 *
 *  - **自己的滚动被当成「用户在翻阅」→ 跟随永久关闭**：`scrollBy` 期间
 *    `isScrollInProgress` 也为 true，用 [SelfScrollFlag] 把「我们自己发起的滚动」
 *    从开关判断里排除掉（前几版「滚两下就不滚了」的死因）。
 *  - **`withFrameNanos` 的 lambda 不是 suspend**：积分在帧内、`scrollBy` 在帧外。
 */
private const val SPRING_K = 130f
private const val SPRING_C = 24f
private const val SPRING_M = 1f

/** 掉帧钳位：单帧最多按 32ms 积分（对齐 dsh）。 */
private const val MAX_DT = 0.032f

/** 目标与当前之差小于它即认为到位（像素）。 */
private const val REST_DIST = 0.5f

/** 位移小于它就忽略（亚像素抖动）。 */
private const val MIN_STEP = 0.5f

/** 首帧 dt 兜底（约 60fps）。 */
private const val FALLBACK_DT = 1f / 60f

/**
 * 弹簧状态容器 —— **跨协程重启存活**（v5 的核心）。
 *
 * `busy` 是「当前是否已有一个帧引擎在推进」的闸门：
 * 同一时刻只允许一个推进循环，重复的启动请求直接返回。
 */
private class Spring {
    var x = 0f          // 当前位置（px / index）
    var v = 0f          // 当前速度 → 关键：协程重启也不重置
    var lastFrame = 0L
    var busy = false
}

/** 「当前滚动是我们自己发起的」标记（不参与重组，故不用 Compose 状态）。 */
private class SelfScrollFlag {
    @Volatile
    var on = false
}

/** 配 `Modifier.verticalScroll(state)` 使用。 */
@Composable
fun AutoFollowScroll(
    state: ScrollState,
    itemCount: Int,
    thresholdPx: Int = 48,
) {
    var autoFollow by remember { mutableStateOf(true) }
    val selfScroll = remember { SelfScrollFlag() }
    val spring = remember { Spring() }

    // ① 手势 / 位置 → 开关（只在「用户主动拖拽」时关闭跟随）。
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.isScrollInProgress, state.value, state.maxValue) }
            .collect { (inProgress, value, max) ->
                val atBottom = max <= 0 || value >= max - thresholdPx
                if (atBottom) {
                    autoFollow = true
                } else if (inProgress && !selfScroll.on) {
                    autoFollow = false
                }
            }
    }

    // ② 内容追加 → 推进弹簧（状态在 [Spring] 里，重启不丢速度；空闲即停）。
    LaunchedEffect(state, itemCount) {
        if (itemCount <= 0) return@LaunchedEffect
        if (spring.busy) return@LaunchedEffect   // ★ 已有引擎在跑 → 它会自己追上
        spring.busy = true
        // 若引擎停过，把 x 同步到真实位置（用户可能滚过），但**保留 v**（连续性）。
        spring.x = state.value.toFloat()
        spring.lastFrame = 0L
        try {
            while (autoFollow) {
                var step = 0f
                var done = false
                withFrameNanos { now ->
                    val dt = if (spring.lastFrame == 0L) {
                        FALLBACK_DT
                    } else {
                        ((now - spring.lastFrame) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                    }
                    spring.lastFrame = now
                    val target = state.maxValue.toFloat()
                    if (abs(target - spring.x) < REST_DIST) {
                        spring.x = target
                        spring.v = 0f
                        done = true
                        return@withFrameNanos
                    }
                    val a = (SPRING_K * (target - spring.x) - SPRING_C * spring.v) / SPRING_M
                    spring.v += a * dt
                    spring.x += spring.v * dt
                    if (spring.x > target) { spring.x = target; spring.v = 0f }  // 贴底不冲过头
                    if (spring.x < 0f) { spring.x = 0f; spring.v = 0f }
                    step = spring.x - state.value
                }
                if (abs(step) >= MIN_STEP) {
                    selfScroll.on = true
                    runCatching { state.scrollBy(step) }
                    selfScroll.on = false
                }
                if (done) break
            }
        } finally {
            spring.busy = false
        }
    }
}

/** 配 `LazyColumn(state = listState)` 使用。 */
@Composable
fun AutoFollowScrollLazy(
    lazyState: LazyListState,
    itemCount: Int,
) {
    var autoFollow by remember { mutableStateOf(true) }
    val selfScroll = remember { SelfScrollFlag() }
    val spring = remember { Spring() }

    // ① 视口是否到尾项 + 手势 → 开关。
    LaunchedEffect(lazyState) {
        snapshotFlow {
            val info = lazyState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            Triple(
                lazyState.isScrollInProgress,
                last == null || last.index >= info.totalItemsCount - 1,
                info.totalItemsCount,
            )
        }.collect { (inProgress, atBottom, _) ->
            if (atBottom) {
                autoFollow = true
            } else if (inProgress && !selfScroll.on) {
                autoFollow = false
            }
        }
    }

    // ② 内容追加 → 推进弹簧（同上；按「距尾项像素」建模）。
    LaunchedEffect(lazyState, itemCount) {
        if (itemCount <= 0) return@LaunchedEffect
        if (spring.busy) return@LaunchedEffect
        spring.busy = true
        spring.lastFrame = 0L
        try {
            while (autoFollow) {
                var step = 0f
                var done = false
                withFrameNanos { now ->
                    val dt = if (spring.lastFrame == 0L) {
                        FALLBACK_DT
                    } else {
                        ((now - spring.lastFrame) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                    }
                    spring.lastFrame = now
                    val info = lazyState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                    if (last == null) {
                        done = true
                        return@withFrameNanos
                    }
                    val atEnd = last.index >= info.totalItemsCount - 1
                    // 剩余距离：到尾项 = 尾项底边 - 视口底边；没看到尾项 = 给一个视口高的推力。
                    val remaining = if (atEnd) {
                        ((last.offset + last.size) - info.viewportEndOffset).toFloat()
                    } else {
                        info.viewportEndOffset.toFloat()
                    }
                    if (remaining <= REST_DIST) {
                        spring.v = 0f
                        done = true
                        return@withFrameNanos
                    }
                    val a = (SPRING_K * remaining - SPRING_C * spring.v) / SPRING_M
                    spring.v += a * dt
                    step = (spring.v * dt).coerceAtMost(remaining)   // 不冲过头
                }
                if (step >= MIN_STEP) {
                    selfScroll.on = true
                    runCatching { lazyState.scrollBy(step) }
                    selfScroll.on = false
                }
                if (done) break
            }
        } finally {
            spring.busy = false
        }
    }
}