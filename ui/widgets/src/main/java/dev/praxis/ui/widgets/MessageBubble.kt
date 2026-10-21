package dev.praxis.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 气泡角色。与 `core:ai` 的 `ChatRole` 分开：UI 多一个 Error 态。 */
enum class BubbleKind {
    User,
    Assistant,
    Tool,
    Error,
}

/**
 * 聊天气泡：用户 / AI / 工具 / 错误四种。
 *
 * ## 为什么放在 ui:widgets 而不是聊天页里
 *
 * 设计文档把它列为 `ui:widgets` 的控件（4.2 节）。放聊天页里会导致：
 *  - 别的页面想复用（比如将来的「对话片段引用」）必须依赖 `feature:chat`；
 *  - 气泡视觉改动会散落在多个页面文件里。
 *
 * ## 四种形态
 *
 * | 角色 | 背景 | 内容色 | 布局 |
 * |---|---|---|---|
 * | User | `primary` | `onPrimary` | 右对齐，最大 300dp |
 * | Assistant | `surfaceContainerHigh` | `onSurface` | 左对齐，最大 360dp |
 * | Tool | 走 [ToolCallCard]，不用气泡 | — | 占满整行 |
 * | Error | `errorContainer` | `onErrorContainer` | 左对齐 + 重试按钮 |
 *
 * 宽度用**上限 dp**而不是百分比： 在窄屏上会自然收缩到可用宽度，
 * 宽屏上才落到上限。300/360dp 是「读起来不用来回扫视」的经验值，
 * 且与底栏 tab 的 76dp 基准同源（老挂与 InstallerX 的间距体系）。
 *
 * ## 工具为什么不用气泡
 *
 * 工具卡片要显示参数、结果、耗时、状态，塞进气泡会被气泡的圆角与
 * 内边距夹住，长内容换行很难看。工具直接占满整行。
 */
@Composable
fun MessageBubble(
    kind: BubbleKind,
    text: String,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    val isUser = kind == BubbleKind.User
    val isError = kind == BubbleKind.Error

    val container: Color = when {
        isError -> MiuixTheme.colorScheme.errorContainer
        isUser -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val content: Color = when {
        isError -> MiuixTheme.colorScheme.onErrorContainer
        isUser -> MiuixTheme.colorScheme.onPrimary
        else -> MiuixTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(
                // 用户消息更紧凑（通常短），AI 消息放宽到 92%（代码/列表需要宽度）。
                max = if (isUser) 300.dp else 360.dp,
            ),
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (isError) {
                    // 错误气泡：正文 + 可点的重试。错误信息本身常带技术细节，
                    // 直接给用户看，不做「出错了，请重试」这种没有信息量的话术。
                    StreamingText(text = text, streaming = false, color = content)
                    if (onRetry != null) {
                        RetryRow(onRetry)
                    }
                } else if (isUser) {
                    // 用户消息不会流式，直接 Text 更省一层组合。
                    top.yukonga.miuix.kmp.basic.Text(
                        text = text,
                        style = MiuixTheme.textStyles.body2,
                        color = content,
                    )
                } else {
                    StreamingText(
                        text = text,
                        streaming = streaming,
                        color = content,
                    )
                }
            }
        }
    }
}

@Composable
private fun RetryRow(onRetry: () -> Unit) {
    Row(Modifier.padding(top = 6.dp)) {
        // miuix TextButton 收 `text: String`，没有内容槽（与 M3 不同）。
        top.yukonga.miuix.kmp.basic.TextButton(
            text = "重试",
            onClick = onRetry,
            textStyle = MiuixTheme.textStyles.footnote1,
        )
    }
}