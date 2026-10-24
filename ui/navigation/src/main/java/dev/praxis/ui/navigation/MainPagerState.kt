package dev.praxis.ui.navigation

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * 底栏 Tab 的 Pager 状态机。
 *
 * ## 为什么不直接用 `pagerState.animateScrollToPage`
 *
 * 四个具体问题，每个都对应一段代码（照 InstallerX `ui/navigation/PagerState.kt`，
 * 上游注释写明部分派生自 KernelSU）：
 *
 * 1. **选中态必须先亮**。`animateScrollToPage` 要跑完动画才更新
 *    `currentPage`；底栏的选中指示器如果跟着它走，用户点 Tab 后要等几百毫秒
 *    才看到高亮，手感像「点了没反应」。所以 [selectedPage] 在启动动画**之前**
 *    就改成目标值，与 Pager 的 `currentPage` 解耦。
 * 2. **要能打断用户正在拖的手势**。用默认优先级发起滚动会被 Pager 自己的
 *    动画/手势互斥挡住；`MutatePriority.UserInput` 才能抢到滚动控制权。
 * 3. **终点必须精确**。`scrollBy` 返回的是「**实际**消费的像素」——靠近边界
 *    时会被截断。若直接 `scrollBy(目标增量)`，误差会累积，多点几次 Tab 就会
 *    偏出半页。这里用 `previousValue += scrollBy(current - previousValue)`，
 *    以「累计发起值」减「累计实际值」的方式补齐。
 * 4. **动画时长要跟距离相关**。固定时长下，跨 3 屏切 Tab 会一闪而过、
 *    相邻两屏又显得拖沓。`100 * distance + 100` 是上游的取舍：
 *    相邻一屏 200ms、跨三屏 400ms。
 *
 * ## 连点为什么不会互相踩
 *
 * `navJob?.cancel()` 先取消上一次；`finally` 里只在 `navJob == myJob` 时
 * 才清 `isNavigating`。没有这层判断时，旧协程被取消后仍会执行 `finally`
 * 并把 `isNavigating` 置 false —— 于是新动画进行中被误判为「没在导航」，
 * 手势同步逻辑会立刻把 `selectedPage` 拽回 `currentPage`，表现为选中的 Tab
 * 自己弹回去。
 */
class MainPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
) {
    /** 底栏选中的页。与 `pagerState.currentPage` 有意解耦（见 KDoc 第 1 条）。 */
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    /** 动画进行中。手势同步逻辑据此避让（见 KDoc「连点」一节）。 */
    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    /** 切到第 [targetIndex] 页，带缓动；重复点同一页直接返回。 */
    fun animateToPage(targetIndex: Int) {
        if (targetIndex == selectedPage) return

        navJob?.cancel()
        selectedPage = targetIndex
        isNavigating = true

        navJob = coroutineScope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.scroll(MutatePriority.UserInput) {
                    // 距离至少按 2 算：相邻页如果按 1 算会只有 200ms，
                    // 在多页快速连点时显得太急；这是上游的取值。
                    val distance = abs(targetIndex - pagerState.currentPage).coerceAtLeast(2)
                    val duration = 100 * distance + 100
                    val layoutInfo = pagerState.layoutInfo
                    val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
                    // 用「页数差 - 当前页内的偏移比例」算真实像素距离：
                    // 手势拖到一半时 currentPage 还没变，只按整数页差算会跳一下。
                    val currentDistanceInPages =
                        targetIndex - pagerState.currentPage - pagerState.currentPageOffsetFraction
                    val scrollPixels = currentDistanceInPages * pageSize

                    var previousValue = 0f
                    animate(
                        initialValue = 0f,
                        targetValue = scrollPixels,
                        animationSpec = tween(easing = EaseInOut, durationMillis = duration),
                    ) { currentValue, _ ->
                        previousValue += scrollBy(currentValue - previousValue)
                    }
                }

                // 兜底对齐：边界被截断或页面尺寸在动画中途变化时，
                // 上面的累加可能差几像素，用非动画的 scrollToPage 收尾。
                if (pagerState.currentPage != targetIndex) {
                    pagerState.scrollToPage(targetIndex)
                }
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    // 动画没能真正落到目标页（被边界挡住/被手势抢走）时，
                    // 让选中态回到真实位置，避免底栏高亮与实际页不符。
                    if (pagerState.currentPage != targetIndex) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    /**
     * 手势滑动结束后同步选中态。
     *
     * `isNavigating` 为真时**不**同步：那一瞬间 `currentPage` 还是动画途中的值，
     * 同步会把刚点亮的选中态拽回去（见 KDoc「连点」一节）。
     */
    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}

/** 记住一个 [MainPagerState]。pagerState 或 scope 变化时重建。 */
@Composable
fun rememberMainPagerState(
    pagerState: PagerState,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
): MainPagerState = remember(pagerState, coroutineScope) {
    MainPagerState(pagerState, coroutineScope)
}