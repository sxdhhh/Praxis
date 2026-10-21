package dev.praxis.ui.design.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.ScrollState

/**
 * 列表流式跟随：内容追加时自动滚到底部，用户上滑后停止，滑回底部后恢复。
 *
 * ## 为什么改成这个写法（2026-10-21）
 *
 * 上一版是自研的二阶阻尼弹簧（`withFrameNanos` 里手推速度、按剩余像素建模），
 * 245 行。装机后用户反馈「一点都不丝滑」。原因不是参数没调好，而是**方向错了**：
 *
 *  - 弹簧每帧都在 `scrollBy`，与 `LazyColumn` 自身的 fling/吸附逻辑打架，
 *    表现为「追一下就顿一下」；
 *  - 流式追加时 `itemCount` 每 100ms 变一次，`LaunchedEffect(itemCount)` 反复重启，
 *    弹簧的 `lastFrame` 被重置，速度积累不起来 —— 越追加越卡。
 *
 * 现在直接用 apixiaoyuan（`CustomScoreScreen.kt:307-319`）验证过的写法：
 *
 * ```kotlin
 * LaunchedEffect(listState) {
 *     snapshotFlow { listState.layoutInfo }.collect { info ->
 *         val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
 *         autoFollow = last >= info.totalItemsCount - 2
 *     }
 * }
 * LaunchedEffect(itemCount, autoFollow) {
 *     if (autoFollow && itemCount > 0) listState.animateScrollToItem(itemCount - 1)
 * }
 * ```
 *
 * 关键差异：
 *  1. **用原生 `animateScrollToItem`**，动画由 Compose 自己做插入/取消/衔接，
 *     不会和 fling 打架；
 *  2. **`-2` 而不是 `-1`**：留一条余量，避免「差一点点到底」被判定为「不在底部」
 *     而关掉跟随（这是上一版频繁误判的来源）；
 *  3. **只在 `itemCount` 变化时滚**，不是每帧推 —— 没有内容变化就不打扰用户。
 *
 * ## 为什么不用 `derivedStateOf` 包 autoFollow
 *
 * apixiaoyuan 原版就是普通 `mutableStateOf`。改成 derived 会让「用户上滑」
 * 这个一次性事件无法落成状态，反而更难停。
 */
@Composable
fun AutoFollowScrollLazy(
    lazyState: LazyListState,
    itemCount: Int,
) {
    var autoFollow by remember { mutableStateOf(true) }

    // ① 视口是否贴底 → 开关自动跟随。
    //    用户上滑离开底部即停（不跟他抢滚动条），滑回底部自动恢复。
    LaunchedEffect(lazyState) {
        snapshotFlow { lazyState.layoutInfo }.collect { info ->
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            // -2：留一条余量。差一条也算「在底部」，
            // 否则最后一项刚好被底栏遮住一点时会误判成「用户离开了」。
            autoFollow = last >= info.totalItemsCount - 2
        }
    }

    // ② 内容追加 → 滚到尾项。原生动画，Compose 自己处理插入/取消。
    LaunchedEffect(itemCount, autoFollow) {
        if (autoFollow && itemCount > 0) {
            lazyState.animateScrollToItem(itemCount - 1)
        }
    }
}

/**
 * 非 Lazy 的 `Column + verticalScroll` 版本。
 *
 * 用同一套判据：`value >= maxValue - 一个视口高度` 视为贴底。
 * `Column` 没有「项」的概念，只能按像素判断，所以留 1dp 余量给浮点误差，
 * 外加固定 slack —— 与 Lazy 版的 `-2 项` 语义对齐。
 */
@Composable
fun AutoFollowScroll(
    scrollState: ScrollState,
    contentHeight: Int,
    viewportHeight: Int,
) {
    var autoFollow by remember { mutableStateOf(true) }

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value to scrollState.maxValue }.collect { (value, max) ->
            // 允许 2px 浮点误差 + 一个「项」的余量（这里用 48px 近似）。
            autoFollow = value >= max - 2 || max - value <= VIEWPORT_SLACK_PX
        }
    }

    LaunchedEffect(contentHeight, viewportHeight, autoFollow) {
        if (autoFollow && scrollState.maxValue > 0) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
}

/** 距底小于这个像素数就算「在底部」（与 Lazy 版 `-2 项` 对齐）。 */
private const val VIEWPORT_SLACK_PX = 48