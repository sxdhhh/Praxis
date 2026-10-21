package dev.praxis.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.praxis.ui.design.component.AutoFollowScrollLazy
import dev.praxis.ui.design.component.LocalScrollBottomLimit
import dev.praxis.ui.design.component.LocalTopBarInset
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import dev.praxis.ui.widgets.BubbleKind
import dev.praxis.ui.widgets.MessageBubble
import dev.praxis.ui.widgets.ToolCallCard

/**
 * 聊天页。
 *
 * 两个关键点：
 *
 * 1. **消息列表用 [AutoFollowScroll]**：从 apixiaoyuan 移植的二阶阻尼弹簧跟随。
 *    语义是「到顶自动跟随 / 用户上滑关跟随 / 回底重新跟随」，
 *    正是模型流式输出时的体验要求（不能一帧一帧跳）。
 *
 * 2. **输入区不是浮层**：聊天页是 `AppFullscreen` 形态，输入框占据布局空间，
 *    消息列表的高度自己收缩 —— 不然键盘弹出会盖住最后一条消息。
 */
@Composable
fun ChatScreen(
    state: ChatState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 列表顶部的可选内容（当前用于模型配置摘要）。
     *
     * ## 为什么用插槽而不是直接依赖 ModelSettings
     *
     * `feature:chat` 不该知道「模型配置」长什么样 —— 那是 `ui:widgets` +
     * `:app` 的职责。插槽让聊天页保持纯粹的「消息流 + 输入」结构，
     * 而把「我现在在跟谁说话」这件事交给上层决定要不要显示、显示什么。
     *
     * ## 为什么是 LazyColumn 的 item 而不是固定头部
     *
     * 固定头部会永久占掉一块屏幕。作为 item 时它随消息滚走 ——
     * 用户看完一眼就不再需要它，但往上滚回顶部时它还在。
     */
    header: (@Composable () -> Unit)? = null,
) {
    val listState = rememberLazyListState()

    val topInset = LocalTopBarInset.current
    val scrollLimit = LocalScrollBottomLimit.current

    Column(modifier = modifier.fillMaxSize()) {

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = topInset + 12.dp,
                // 底部限位：滚动区只是「给输入栏让位」不够 —— 输入栏本身也向上抬了
                // scrollLimit（浮层底栏高度），所以列表最后一条会被底栏压住。
                // 这里再加一次 scrollLimit，最后一条才能滚到输入栏上方。
                bottom = 12.dp + scrollLimit,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (header != null) {
                item(key = "chat-header") { header() }
            }

            items(state.bubbles, key = { it.id }) { bubble ->
                // 工具气泡走卡片（可折叠、带耗时），其余走聊天气泡。
                val tool = bubble.tool
                if (tool != null) {
                    ToolCallCard(
                        name = tool.name,
                        argumentsJson = tool.argumentsJson,
                        result = tool.result,
                        isError = tool.isError,
                        elapsedMs = tool.elapsedMs,
                        summary = tool.result?.lineSequence()?.firstOrNull(),
                    )
                } else {
                    MessageBubble(
                        kind = when (bubble.role) {
                            BubbleRole.User -> BubbleKind.User
                            BubbleRole.Assistant -> BubbleKind.Assistant
                            BubbleRole.Error -> BubbleKind.Error
                            BubbleRole.Tool -> BubbleKind.Tool
                        },
                        text = bubble.text,
                        streaming = bubble.streaming,
                        onRetry = if (bubble.role == BubbleRole.Error) onRetry else null,
                    )
                }
            }
        }

        // 丝滑跟随：itemCount 变化时推进弹簧，不是 animateScrollToItem 的逐次动画。
        AutoFollowScrollLazy(
            lazyState = listState,
            itemCount = state.bubbles.size,
        )

        ChatInputBar(
            state = state,
            onInputChange = onInputChange,
            onSend = onSend,
            onRetry = onRetry,
            bottomPadding = scrollLimit,
        )
    }
}

@Composable
private fun ChatInputBar(
    state: ChatState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MiuixTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = bottomPadding + 8.dp,
                ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(20.dp),
            ) {
                BasicTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    textStyle = MiuixTheme.textStyles.body2.copy(
                        color = MiuixTheme.colorScheme.onSurface,
                    ),
                    maxLines = 5,
                    decorationBox = { inner ->
                        if (state.input.isEmpty()) {
                            Text(
                                text = "说点什么…",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        inner()
                    },
                )
            }

            if (state.error != null) {
                TextButton(text = "重试", onClick = onRetry)
            } else {
                TextButton(
                    text = if (state.isStreaming) "生成中" else "发送",
                    enabled = state.canSend,
                    onClick = onSend,
                )
            }
        }
    }
}
