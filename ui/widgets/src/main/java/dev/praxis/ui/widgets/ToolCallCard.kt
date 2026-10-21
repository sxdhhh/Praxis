package dev.praxis.ui.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工具调用卡片：可折叠的「工具名 + 参数 + 结果 + 耗时」。
 *
 * ## 为什么必须能折叠
 *
 * 工具返回体经常是几千字的文件内容或终端输出。默认展开会让聊天流被
 * 一屏屏代码淹没，用户找不到自己在问什么。上游 Operit 的处理是：
 * **默认收起，只显示一行摘要；点开才看全文**。这里照做。
 *
 * ## 为什么参数与结果都要显示
 *
 * 只显示结果会让用户无法判断「模型到底查了什么」。反过来只显示参数
 * 则看不出成功与否。两者都要，但都放在折叠区里。
 *
 * ## 状态色
 *
 *  - 执行中：`primary`（蓝）—— 表示进行中
 *  - 成功：`onSurfaceVariantSummary`（灰）—— 不抢注意力
 *  - 失败：`error`（红）—— 唯一需要立刻看见的
 *
 * @param name 工具名（与模型看到的原文一致）
 * @param argumentsJson 原始 JSON 参数，**不截断**
 * @param result 返回体；null 表示还在执行
 * @param isError 是否失败
 * @param elapsedMs 耗时毫秒；未完成时为 null
 * @param summary 折叠时显示的一行摘要（通常是结果首行）
 */
@Composable
fun ToolCallCard(
    name: String,
    argumentsJson: String,
    result: String?,
    isError: Boolean,
    elapsedMs: Long? = null,
    summary: String? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val running = result == null && !isError
    val statusColor = when {
        isError -> MiuixTheme.colorScheme.error
        running -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val statusText = when {
        isError -> "失败"
        running -> "执行中"
        else -> elapsedMs?.let { formatElapsed(it) } ?: "完成"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
        cornerRadius = 12.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // ── 标题行：状态点 + 工具名 + 状态 ──────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MiuixTheme.textStyles.body2,
                    color = statusColor,
                )
                Text(
                    text = name,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(text = statusText, style = MiuixTheme.textStyles.footnote1, color = statusColor)
            }

            // ── 折叠时的一行摘要 ────────────────────────────────────
            if (!expanded && summary != null) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 18.dp, top = 4.dp),
                )
            }

            // ── 展开：参数 + 结果 ──────────────────────────────────
            if (expanded) {
                SectionLabel("参数")
                CodeBlock(argumentsJson)
                if (result != null) {
                    SectionLabel(if (isError) "错误" else "结果")
                    CodeBlock(result, isError)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** 代码块：等宽感 + 可换行的浅底容器。 */
@Composable
private fun CodeBlock(text: String, isError: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 2.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = if (isError) {
                MiuixTheme.colorScheme.error
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )
    }
}

/** 耗时格式化：小于 1 秒显示毫秒，否则显示一位小数的秒。 */
private fun formatElapsed(ms: Long): String =
    if (ms < 1000) "${ms}ms" else String.format("%.1fs", ms / 1000.0)