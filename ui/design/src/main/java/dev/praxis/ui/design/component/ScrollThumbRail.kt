package dev.praxis.ui.design.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 回底按钮直径。30dp 是「能点中」与「不压住正文」的折中。 */
private val BUTTON_SIZE = 30.dp

/**
 * 列表右下方的「回到底部」按钮。
 *
 * ## 2026-11-01：轨道被删掉了，只剩按钮
 *
 * 用户拿标注截图指出右侧那根「带上下两个圆点的竖轨」要去掉，
 * 并画箭头把底部的圆形按钮**往上挪**。红叉划掉的正是本组件原先画的
 * 固定高度轨道 + 进度圆点那一整套（一条 4dp 宽的圆角竖条 + 一个 8dp 的
 * 定位圆点 + `preferredRailHeight` 参数），蓝箭头指的是这里这个圆形回底按钮。
 *
 * ## 为什么轨道该删（不只是一句「用户不喜欢」）
 *
 * 1. **语义与导航浮条重复**：右缘垂直中点的 `ChatScrollNavigator` 已经用
 *    「竖线 + 圆点」表达「我在整段对话的哪里」，而且那个能点开定位器跳转。
 *    再画一根同读法的进度轴，用户分不清哪根是能点的。
 * 2. **位置与语义矛盾**：轨道被钉在右下角，而它表达的是**整段对话**的比例 ——
 *    「刚开头」时圆点贴在右下角，读起来像「已经到底了」。
 * 3. **它是正文边缘的视觉噪音**：长文本对话里那列 4dp 灰条只隔 2dp 就挨着
 *    最后一个字，滚动时与正文抢注意力。
 *
 * ## 按钮为什么从「贴底」改到「右缘 62%」
 *
 * 位置由调用方给的 modifier 决定（本组件自己不假设父容器），聊天页传的是
 * `Alignment.CenterEnd` + `fillMaxHeight` + `padding(bottom = 24%)` ——
 * 见 `ChatScreen` 里 `ScrollThumbRail` 调用点的 KDoc。定在垂直 62% 的理由：
 *  - 低于导航浮条（右缘正中），两者不再抢同一条竖轴的中段；
 *  - 高于输入栏与底栏，不会被它们盖住；
 *  - 与用户标注截图里蓝箭头指向的高度一致。
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
 * @param modifier 外部布局（聊天页传定位相关的 modifier）
 * @param showWhenIdle 静止且未到底时是否保持显示。false 时只在滚动中/未到底才出现
 */
@Composable
fun ScrollThumbRail(
    lazyState: LazyListState,
    modifier: Modifier = Modifier,
    showWhenIdle: Boolean = true,
) {
    val scope = rememberCoroutineScope()

    val info by remember { derivedStateOf { lazyState.layoutInfo } }

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

    // 到底了就不画按钮：那时它按下去什么都不会发生，是纯噪音。
    val showButton = !atBottom

    // ## 为什么用 AnimatedVisibility 而不是提前 return
    //
    // 上一版是 `if (!showWhenIdle && !scrolling && !showButton) return`。
    // Composable 直接不组合，在「未到底 ↔ 到底」切换时整块元素瞬间出现/消失
    // —— 没有淡入淡出，观感就是闪一下。这里让元素始终存在，只做过场动画。
    AnimatedVisibility(
        visible = showWhenIdle || showButton,
        modifier = modifier,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(120)),
    ) {
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
            // ## 为什么用 material3 的 Icon 而不是 miuix 的
            //
            // 上面那个 Box 用的是 `contentAlignment = Alignment.Center`，
            // 而 miuix 的 `Icon` 不接受 `Modifier.align` 以外的居中方式
            // （它是自绘的 Box，没有 contentAlignment 形参）。material3 的
            // `Icon` 走标准 `Modifier.size`，放在 Box 里由父级居中即可 ——
            // 与 2026-10-29 那版的选型保持一致，Windows 上无法编译验证时
            // 少引入一个不确定项。
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "滚到底部",
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
