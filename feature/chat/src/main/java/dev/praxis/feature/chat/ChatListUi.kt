package dev.praxis.feature.chat

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.praxis.ui.design.component.AutoFollowScrollLazy
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.design.component.LocalScrollBottomLimit
import dev.praxis.ui.design.component.LocalTopBarInset
import dev.praxis.ui.theme.PraxisMotion
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表页。
 *
 * 排序已经由 DAO 的 `ORDER BY updatedAt DESC` 保证，这里不再排一次 ——
 * 两处排序规则不一致时会出现「列表顺序与点击行为对不上」。
 */
@Composable
fun ChatListScreen(
    sessions: List<SessionItem>,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 底栏是浮层：内容要能从它下方穿过（玻璃才有东西折射），
    // 但滚动终点必须抬高，否则最后一条会话永远滚不到底栏上方。
    // 高度由 :app 通过 LocalBottomBarInset → LocalScrollBottomLimit 下发。
    val scrollLimit = LocalScrollBottomLimit.current
    // 顶部内边距：内容初始位置要落在顶栏下方，否则「历史会话」会被顶栏压住。
    // 顶栏高度由 AppScaffold 通过 LocalTopBarInset 下发（含状态栏）。
    // 用 contentPadding 而不是容器 padding：滚动时内容能穿过顶栏下方，
    // 顶栏的渐变模糊才有内容可采。
    val topInset = LocalTopBarInset.current

    val listState = rememberLazyListState()
    // 丝滑滚动：与聊天/日志/终端同一个弹簧引擎（apixiaoyuan `AutoFollowScroll`）。
    // `initialAutoFollow = false` —— 会话列表是索引型页面，打开应停在顶部。
    AutoFollowScrollLazy(
        lazyState = listState,
        itemCount = sessions.size + 1,
        initialAutoFollow = false,
    )
    // 页面自铺底色（同 ChatScreen）。
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface),
        contentPadding = PaddingValues(
            top = topInset,
            bottom = 24.dp + scrollLimit,
        ),
    ) {
        item { Spacer(Modifier.height(12.dp)) }

        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                TextButton(
                    text = "新建对话",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCreate,
                )
            }
        }

        item(key = "__title_history__") { SmallTitle("历史会话（${sessions.size}）") }

        if (sessions.isEmpty()) {
            // ## 空态 item 必须给 key（2026-10-24 修，理由见 ChatSessionsPage 同名注释）
            //
            // 这里尤其明显：上面还有 `item { SmallTitle(...) }` 也没有 key，
            // 它占下标 0；空态项若也没 key 就占下标 1；而 `items(sessions)` 的
            // 第一条在空态消失后也落到下标 1 —— 三者共用同一批下标身份，
            // 「空态 → 首条会话」的切换会被当成「同一 item 换内容」，
            // `animateItem` 的 fadeIn 因此不会触发（看起来就是硬切）。
            item(key = "__empty_history__") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Text(
                        text = "还没有历史会话。点上方「新建对话」开始。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(sessions, key = { it.id }) { session ->
                // ## `animateItem` 包一层 Box，而不是直接加在 `Card` 上（2026-10-24）
                //
                // `animateItem` 是 `LazyItemScope` 的扩展，它作用于**被包裹的
                // 那个节点**的 placement 与 alpha。`Card` 是 miuix 的自定义
                // composable，它内部会把自己的 modifier 交给 Surface ——
                // 虽然能传进去，但 Surface 自己会插一层 clip / 背景，
                // 位移动画作用在那一层时会与阴影/圆角重绘叠加，
                // 快速滚动时可能出现「卡片先跳位、阴影后跟上」的错位。
                //
                // 外面包一个无背景的 Box，把 animateItem 挂在这一层：
                // 它只管位置与透明度，卡片自身的外观一点不变。
                Box(
                    modifier = Modifier.animateItem(
                        // 出现：删除一条后其余项上移，新项淡入。
                        // 淡入而不是滑入：会话列表按时间排序，新会话通常插在
                        // 顶部，滑入方向与「从哪里来」没有直觉对应关系。
                        fadeInSpec = tween(
                            durationMillis = PraxisMotion.Durations.Base,
                            easing = PraxisMotion.Curves.Decelerate,
                        ),
                        // 位移（重排）：这条是列表项动画里最重要的一项 ——
                        // 删掉一条后，下面每一条都要「滑上来」，而不是瞬移。
                        placementSpec = spring(
                            stiffness = 400f,
                            dampingRatio = 0.85f,
                        ),
                        fadeOutSpec = tween(
                            durationMillis = PraxisMotion.Durations.Fast,
                            easing = PraxisMotion.Curves.Accelerate,
                        ),
                    ),
                ) {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Column {
                        MiuixNavigationItemWidget(
                            title = session.title.ifBlank { "未命名对话" },
                            description = formatTime(session.updatedAt),
                            onClick = { onOpen(session.id) },
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                text = "删除",
                                onClick = { onDelete(session.id) },
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

/** 时间格式：今天只显示时分，更早显示月日。 */
private fun formatTime(epochMillis: Long): String {
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60 * 60 * 1000
    val sameDay = (now - epochMillis) < dayMs
    val pattern = if (sameDay) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMillis))
}
