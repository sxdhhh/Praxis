package dev.praxis.ui.design.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 右侧滚动指示条 + 「回到底部」按钮（对齐 Operit 的滚动指示器）。
 *
 * ## 两者为什么放在同一个 Column
 *
 * 截图里 Operit 那根竖条与下面的圆按钮共享同一垂直轴 —— 竖条表示进度、
 * 按钮负责一键到底。分成两个组件就要各算一套间距，滚动时还会互相挤；
 * 合成一列之后它们才看起来是「一根东西」。
 *
 * ## 为什么自己画，不用现成 scrollbar
 *
 * 现成的（Material / miuix）都是给 `Modifier.verticalScroll` 设计的，
 * 对 `LazyListState` 只能读到 `layoutInfo` 的聚合值，thumb 长度算不准
 * （条目高度差异大时尤其乱）。这里直接读 `visibleItemsInfo`，
 * 算的是「第一项可见下标 / 可滚项数」，落位准确 —— 用户看它只为知道
 * 「我大致在哪个位置、能滑多远」。
 *
 * ## 「到底了」的判据为什么不用 `lastVisibleIndex == total - 1`
 *
 * 那个条件在末项只露出 1px 时也成立，那时用户其实还在半空。
 * 必须比较「末项底边」与「视口内容区末端」（扣掉 contentPadding），
 * 与 [AutoFollowScrollLazy] 内部 `remainingDeltaPx` 的口径完全一致 ——
 * 两处若各用一套判据，就会出现「弹簧认为到底了但按钮还亮着」。
 *
 * ## 为什么进度用「固定轨道 + 滑动圆点」而不是「可变长 thumb」
 *
 * 聊天气泡高度相差十倍以上（一条工具卡可能占半屏），可变长 thumb
 * 会因为 `visibleItemsInfo` 抖动而忽长忽短。固定轨道 + 圆点只改变位置，
 * 视觉上稳定得多。
 *
 * @param lazyState 目标列表
 * @param modifier 外部布局（一般传 `Modifier.align(Alignment.CenterEnd)`）
 * @param showWhenIdle 静止时是否保持显示。Operit 常显，这里跟随
 */
@Composable
fun ScrollThumbRail(
    lazyState: LazyListState,
    modifier: Modifier = Modifier,
    showWhenIdle: Boolean = true,
) {
    val scope = rememberCoroutineScope()

    val info by remember { derivedStateOf { lazyState.layoutInfo } }

    /** 可滚动部分的进度（0..1）。 */
    val fraction by remember {
        derivedStateOf {
            val total = info.totalItemsCount
            if (total <= 0) {
                0f
            } else {
                val visible = info.visibleItemsInfo.size.coerceAtLeast(1)
                val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
                // 可滚距离按「项数」近似：只有一项能满屏时 denom 为 1，分数恒为 0。
                val denom = (total - visible).coerceAtLeast(1)
                (first.toFloat() / denom).coerceIn(0f, 1f)
            }
        }
    }

    /** 是否真的到了底部（末项完整可见）。 */
    val atBottom by remember {
        derivedStateOf {
            val last = info.visibleItemsInfo.lastOrNull()
            if (last == null) {
                true
            } else if (last.index != info.totalItemsCount - 1) {
                false
            } else {
                val contentEnd = info.viewportEndOffset - info.afterContentPadding
                // +1 容差：浮点转整与取整误差会让「刚好贴底」差 1px。
                last.offset + last.size <= contentEnd + 1
            }
        }
    }

    var scrolling by remember { mutableStateOf(false) }
    LaunchedEffect(lazyState) {
        snapshotFlow { lazyState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling = it }
    }

    // 到底了就不画按钮：那时它按下去什么都不会发生，是纯噪音。
    val showButton = !atBottom
    if (!showWhenIdle && !scrolling && !showButton) return

    val railAlpha by animateFloatAsState(
        targetValue = if (scrolling || !showWhenIdle) 1f else 0.55f,
        label = "scrollRailAlpha",
    )

    Column(
        modifier = modifier.padding(end = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        // ── 轨道 ──
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(96.dp)
                .background(
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f * railAlpha),
                    shape = RoundedCornerShape(3.dp),
                ),
        ) {
            // 圆点在轨道内的行程 = 96 - 8（自身直径）= 88dp。
            Box(
                modifier = Modifier
                    .offset(y = (88 * fraction).dp)
                    .padding(horizontal = 1.dp)
                    .size(4.dp)
                    .background(
                        color = MiuixTheme.colorScheme.primary.copy(alpha = railAlpha),
                        shape = CircleShape,
                    ),
            )
        }

        // ── 触底按钮 ──
        if (showButton) {
            Box(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        shape = CircleShape,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        // 小圆按钮连涟漪容器都放不下，铺开会变成一块方形高亮。
                        indication = null,
                        onClick = {
                            scope.launch {
                                // ## 为什么是 scrollToItem 而不是动画
                                //
                                // `animateScrollToItem` 会起一个长时间动画，
                                // 期间它和 [AutoFollowScrollLazy] 的弹簧同时在写
                                // `LazyListState` —— 两股位移叠加就是用户报告的
                                // 「时而抽搐」。一次性定死位置反而没有冲突：
                                // 落到底之后弹簧发现 remaining=0，自己就停了。
                                val last = lazyState.layoutInfo.totalItemsCount - 1
                                if (last >= 0) lazyState.scrollToItem(last)
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "滚到底部",
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
