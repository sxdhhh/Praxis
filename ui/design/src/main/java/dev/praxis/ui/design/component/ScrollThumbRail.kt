package dev.praxis.ui.design.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 轨道宽度。4dp 是「看得见但不像一条缝」的下限；更宽会显笨。 */
private val RAIL_WIDTH = 4.dp

/** 圆点直径。比轨道宽 2 倍 —— 这是让「点」脱离「棍」的关键，同宽会糊成一条。 */
private val DOT_SIZE = 8.dp

/** 圆点外圈宽度。用页面底色描一圈，圆点与轨道之间才有「浮起」的间隙感。 */
private val DOT_RING = 2.dp

/** 回底按钮直径。与圆点同轴、同色系，只是尺寸放大一档。 */
private val BUTTON_SIZE = 30.dp

/** 轨道与按钮之间的净空。小于 6dp 两者会在视觉上粘连成一根柱。 */
private val GAP = 10.dp

/**
 * 右侧滚动指示条 + 「回到底部」按钮。
 *
 * ## 它曾经长什么样（2026-10-31 重构的直接原因）
 *
 * 上一版是三样东西硬拼在一起，实测截图里完全不成体系：
 *
 * 1. **轨道几乎看不见**：`onSurface.copy(alpha = 0.12f)` 再乘静止态
 *    `railAlpha = 0.55`，实际 alpha ≈ 0.066，深色主题下等于没有；
 * 2. **圆点比轨道还窄**：轨道 6dp、圆点 4dp，圆点**嵌在**轨道里，
 *    看起来像棍子上一个脏点，而不是「当前位置」；
 * 3. **按钮 30dp 圆压在 6dp 轨道正下方、同轴居中**：一根细棍接一颗大球，
 *    中间没有任何过渡，读起来像两个不相干的控件叠在一起；
 * 4. **最致命的是位置**：它和消息导航浮条（`ChatScrollNavigator`）都用
 *    `Alignment.CenterEnd`，只靠 `padding(bottom = 96.dp)` 往下挪。
 *    屏幕矮一点、或导航浮条展开时，两者直接重叠 —— 截图里按钮下面
 *    那团半透明影子就是导航胶囊被压住了。
 *
 * ## 重构后的形态
 *
 * 一条**从底部往上长**的竖轨（4dp 宽、高度按可用空间自适应），
 * 已经滚过的部分用 `primary` 淡色填充，当前位置是轨道里一个 8dp 的
 * `primary` 圆点（带页面底色外圈，与轨道有 2dp 间隙）。
 * 未到底时，竖轨下方隔 10dp 浮一个 30dp 的圆形回底按钮。
 *
 * 三者同色系（都是 `primary`）、同一竖轴、尺寸递进（4 → 8 → 30），
 * 读起来是「一根进度条 + 它的按钮」，而不是三个各自为政的图形。
 *
 * ## 为什么改成贴右下角（`BottomEnd`）
 *
 * 消息导航浮条（`ChatScrollNavigator`）占据屏幕右缘的**垂直中点**，
 * 那是它语义上的正确位置（「我在整段对话的哪里」）。滚动条属于
 * **底部操作区**，贴右下角既符合通用习惯，也彻底消除了两者抢同一条
 * 竖轴的可能 —— 靠 padding 错开只是把冲突往后推，位置本身不该重合。
 *
 * ## 为什么不画「可变长 thumb」而保留「固定轨道 + 圆点」
 *
 * 聊天气泡高度差异十倍以上（一条工具卡可能占半屏），可变长 thumb
 * 依赖 `visibleItemsInfo` 的像素口径，条目高度剧烈变化时会忽长忽短地抖。
 * 固定轨道 + 圆点只改变圆点位置，抖动不可见。
 *
 * ## 「到底了」的判据
 *
 * 必须比较「末项底边」与「视口内容区末端」（扣掉 contentPadding），
 * 不能用 `lastVisibleIndex == total - 1` —— 末项只露出 1px 时后者也成立，
 * 那时用户其实还在半空。这里与 [AutoFollowScrollLazy] 内部
 * `remainingDeltaPx` 的口径保持一致，否则会出现「弹簧认为到底了
 * 但按钮还亮着」。
 *
 * @param lazyState 目标列表
 * @param modifier 外部布局（聊天页传 `Modifier.align(Alignment.BottomEnd)`）
 * @param showWhenIdle 静止时是否保持显示。false 时只在滚动中/未到底才出现
 * @param preferredRailHeight 轨道期望高度；父容器高度有限时按 42% 缩放并夹在
 *   120dp..240dp 之间。父容器高度无界（例如被 wrapContent 包裹）时直接用此值
 */
@Composable
fun ScrollThumbRail(
    lazyState: LazyListState,
    modifier: Modifier = Modifier,
    showWhenIdle: Boolean = true,
    preferredRailHeight: Dp = 180.dp,
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

    /** 是否真的到了底部（末项完整可见且贴边）。 */
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

    // ## 为什么用 AnimatedVisibility 而不是提前 return
    //
    // 上一版是 `if (!showWhenIdle && !scrolling && !showButton) return`。
    // Composable 直接不组合，在「滚动中 ↔ 静止」切换时整块元素瞬间出现/消失
    // —— 没有淡入淡出，观感就是闪一下。这里让元素始终存在，只做过场动画。
    AnimatedVisibility(
        visible = showWhenIdle || scrolling || showButton,
        modifier = modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(160)) { it / 8 },
        exit = fadeOut(tween(120)),
    ) {
        // 滚动中全亮，静止时压暗：静止它只是背景信息，不该抢正文。
        val railAlpha by animateFloatAsState(
            targetValue = if (scrolling) 1f else 0.72f,
            animationSpec = tween(200),
            label = "scrollRailAlpha",
        )

        val trackColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f * railAlpha)
        val progressColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.28f * railAlpha)
        val dotColor = MiuixTheme.colorScheme.primary.copy(alpha = railAlpha)
        val ringColor = MiuixTheme.colorScheme.surface

        BoxWithConstraints {
            // 父容器高度有限才按比例算；无界时退回 preferredRailHeight，
            // 否则 `maxHeight * 0.42f` 会得到无穷大并在测量阶段崩。
            val railHeight = if (constraints.hasBoundedHeight) {
                val scaled = maxHeight * 0.42f
                if (scaled < 120.dp) 120.dp else if (scaled > 240.dp) 240.dp else scaled
            } else {
                preferredRailHeight
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                // ── 轨道 ──
                Box(
                    modifier = Modifier
                        .width(RAIL_WIDTH)
                        .height(railHeight)
                        .background(
                            color = trackColor,
                            shape = RoundedCornerShape(RAIL_WIDTH / 2),
                        ),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // 已滚过的部分：从顶部往下长，与圆点位置严格一致。
                    // 有它之后「进度」是一条连续的带，而不是只能靠圆点猜。
                    if (fraction > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight(fraction)
                                .width(RAIL_WIDTH)
                                .background(
                                    color = progressColor,
                                    shape = RoundedCornerShape(RAIL_WIDTH / 2),
                                ),
                        )
                    }

                    // 当前位置圆点。行程 = 轨道高 − 圆点直径，两端正好贴边。
                    Box(
                        modifier = Modifier
                            .offset(y = (railHeight - DOT_SIZE) * fraction)
                            .size(DOT_SIZE)
                            // 先描页面底色的环，再铺 primary：圆点与轨道之间
                            // 被「挖」出 2dp 间隙，视觉上是浮在轨道上的滑块。
                            .border(DOT_RING, ringColor, CircleShape)
                            .background(dotColor, CircleShape),
                    )
                }

                // ── 回到底部 ──
                if (showButton) {
                    Spacer(Modifier.height(GAP))
                    Box(
                        modifier = Modifier
                            .size(BUTTON_SIZE)
                            .background(
                                color = MiuixTheme.colorScheme.surfaceContainerHigh
                                    .copy(alpha = 0.94f),
                                shape = CircleShape,
                            )
                            .border(
                                width = 1.dp,
                                color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.18f),
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
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}