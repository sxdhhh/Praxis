package dev.praxis.feature.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * token 用量条：会话级累计消耗。
 *
 * ## 为什么用「千」做单位而不是原数
 *
 * 单次对话动辄几万 token，原数字（`12,3456`）在窄条里挤成一片，
 * 用户看不出量级。`12.3k` 一眼能比。
 *
 * ## 为什么要标上下文占用率
 *
 * 超过模型窗口就会触发压缩（`AiContextBudget` 开始丢历史），
 * 那是**用户能明显感知的行为变化**（AI 突然忘了前面的对话）。
 * 提前在 UI 上给出百分比，用户才知道「该开新会话了」。
 *
 * 占用率超过 80% 时变红：那是即将开始丢历史的阈值。
 *
 * @param promptTokens 本次请求的输入 token
 * @param completionTokens 本次请求的输出 token
 * @param contextWindow 模型上下文窗口大小；null 表示不知道，不显示占用率
 */
@Composable
fun TokenUsageBadge(
    promptTokens: Int,
    completionTokens: Int,
    modifier: Modifier = Modifier,
    contextWindow: Int? = null,
) {
    val total = promptTokens + completionTokens
    if (total <= 0) return

    val ratio = contextWindow?.takeIf { it > 0 }?.let { total.toFloat() / it }
    val warn = ratio != null && ratio >= 0.8f

    val bg = if (warn) {
        MiuixTheme.colorScheme.errorContainer
    } else {
        MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val fg = if (warn) {
        MiuixTheme.colorScheme.onErrorContainer
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BadgeText("↑${formatTokens(promptTokens)}", fg)
        BadgeText("↓${formatTokens(completionTokens)}", fg)
        if (ratio != null) {
            BadgeText("${(ratio * 100).toInt()}%", fg)
        }
    }
}

@Composable
private fun BadgeText(text: String, color: Color) {
    Text(text = text, style = MiuixTheme.textStyles.footnote1, color = color)
}

/**
 * token 数格式化。
 *
 * 一万以下给原数（`850`、`3200`），一万以上给千位（`12.3k`）。
 * 不用 `10,000` 这种千分位：中文界面里逗号分隔读起来更割裂。
 */
internal fun formatTokens(n: Int): String = when {
    n < 10_000 -> n.toString()
    n < 1_000_000 -> String.format("%.1fk", n / 1000.0)
    else -> String.format("%.1fM", n / 1_000_000.0)
}