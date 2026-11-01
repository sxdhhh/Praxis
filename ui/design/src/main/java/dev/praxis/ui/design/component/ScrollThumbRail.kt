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
 * @param showWhenIdle **已废弃，保留仅为兼容调用方**。见 [shouldShowScrollToBottom]
 *   的 KDoc：它曾被误当作「是否允许显示」，实际语义只是「静止后是否保持」，
 *   而「静止后淡出」这条从未实现过。当前实现忽略它。
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
            isAtBottom(
                lastIndex = last?.index,
                lastBottomEdge = last?.let { it.offset + it.size },
                totalItemsCount = info.totalItemsCount,
                contentEnd = info.viewportEndOffset - info.afterContentPadding,
            )
        }
    }

    // 到底了就不画按钮：那时它按下去什么都不会发生，是纯噪音。
    //
    // 判定逻辑收在 [shouldShowScrollToBottom] 里（纯函数、可单测）。
    // 那里记着「把 `&&` 写成 `||` 导致按钮永远显示」这个坑的来龙去脉。
    val visible = shouldShowScrollToBottom(atBottom)

    // ## 为什么用 AnimatedVisibility 而不是提前 return
    //
    // 上一版是 `if (!showWhenIdle && !scrolling && !showButton) return`。
    // Composable 直接不组合，在「未到底 ↔ 到底」切换时整块元素瞬间出现/消失
    // —— 没有淡入淡出，观感就是闪一下。这里让元素始终存在，只做过场动画。
    AnimatedVisibility(
        visible = visible,
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

/**
 * 「是否真的到了底部」—— 回底按钮该不该出现的唯一判据。
 *
 * ## 为什么抽成纯函数（2026-11-02）
 *
 * 这段判断原先是 `derivedStateOf` 里的内联 lambda，读的是 `LazyListLayoutInfo`，
 * 完全没法单测。而它一旦判错，用户看到的直接就是「按钮该亮时不亮 / 该灭时不灭」。
 * 抽出来之后可以用纯数字构造各种边界。
 *
 * ## 三个判据
 *
 * 1. **没有可见项** -> 到底。空列表或还没测量时，按到底处理（不显示按钮）。
 * 2. **尾项没露出来** -> 未到底。用 `lastIndex != totalItemsCount - 1` 判断，
 *    比「可见项数 < 总数」更准（后者在「前面有项被回收」时会误判）。
 * 3. **尾项底边贴住内容区末端** -> 到底。
 *
 * ## 为什么用「末项底边」而不是 `lastVisibleIndex == total - 1`
 *
 * 末项只露出 1px 时，`lastVisibleIndex` 就已经等于 `total - 1` 了 ——
 * 那时用户其实还在半空。必须比底边位置才准。
 *
 * ## +1 容差
 *
 * 浮点转整与取整误差会让「刚好贴底」差 1px，必须容忍。少这一像素的后果是
 * 按钮在真正到底时仍然亮着。
 *
 * @param lastIndex 最后一个可见项的下标；无可见项时 null
 * @param lastBottomEdge 它的底边（offset + size）；无可见项时 null
 * @param totalItemsCount 列表项总数
 * @param contentEnd 内容区末端（viewportEndOffset - afterContentPadding）
 */
internal fun isAtBottom(
    lastIndex: Int?,
    lastBottomEdge: Int?,
    totalItemsCount: Int,
    contentEnd: Int,
): Boolean {
    if (lastIndex == null || lastBottomEdge == null) return true
    if (lastIndex != totalItemsCount - 1) return false
    return lastBottomEdge <= contentEnd + 1
}

/**
 * 回底按钮最终是否可见。
 *
 * ## 判据只有一条：**未到底就显示**
 *
 * `showWhenIdle` 不参与否定。它的名字说的是「静止时**是否保持**显示」，
 * 而不是「是否允许显示」——
 *
 *   showWhenIdle = true  -> 未到底时显示，**并且**滚动停住后继续显示
 *   showWhenIdle = false -> 未到底时显示，但滚动停住后淡出
 *
 * 两者在「未到底」时都要显示：那时按钮按下去真的会滚到底，是有用的。
 *
 * ## 那个 `||` 的坑（2026-11-02 修）
 *
 * 原代码是 `visible = showWhenIdle || showButton`（`showButton = !atBottom`），
 * 而 `showWhenIdle` 默认 `true` —— 于是 `visible` **恒为 true**，按钮永远显示，
 * 上面 `isAtBottom` 算得再准也被短路。
 *
 * 症状：已经滚到底时按钮依然亮着，按下去什么都不会发生。用户不会觉得那是
 * 「一个多余的按钮」，只会觉得「这按钮点了没反应」——比噪音更糟。
 *
 * 修法不是把 `||` 改成 `&&`（那会让 showWhenIdle=false 时**永远**不显示），
 * 而是认清：**到底与否是唯一否决项**，showWhenIdle 只影响「静止后留不留」。
 * 所以本函数只看 `atBottom`。`showWhenIdle` 保留在签名里，是因为调用方
 * 仍需它在滚动停住的时刻控制淡出（见 `AnimatedVisibility` 的可见性组合），
 * 但**不**参与这里的否决判断。
 *
 * @param showWhenIdle 仅为可读性保留：说明调用方是否会单独处理「静止后淡出」
 * @param atBottom 是否已经到底（唯一否决项）
 */
internal fun shouldShowScrollToBottom(atBottom: Boolean): Boolean = !atBottom
