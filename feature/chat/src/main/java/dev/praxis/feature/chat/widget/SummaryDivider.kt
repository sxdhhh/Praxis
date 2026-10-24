package dev.praxis.feature.chat.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.praxis.core.ai.CompactedContext
import dev.praxis.core.ai.TokenEstimator
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 压缩摘要的分割线（1:1 移植 Operit `SummaryMessageComposable`，
 * `ui/features/chat/components/style/cursor/SummaryMessageComposable.kt`）。
 *
 * ## 上游的版式（逐项对应）
 *
 * ```
 * ────────────  ⓘ 历史对话摘要  ────────────
 * ```
 *
 * 一条左右贯通的分割线，中间一个胶囊徽标。点徽标弹全文。
 *
 * ## 为什么这条线必须存在（不是装饰）
 *
 * 压缩生效后，历史里前面若干条消息**不再发给模型**（它们的信息在摘要里）。
 * 但 Praxis 的 UI 并不删除那些气泡 —— 用户仍然能看到自己说过的话。
 * 于是必须有一条明确的分界，回答用户心里那个问题：
 *
 * > 「上面那些话，AI 还记得吗？」
 *
 * 有了这条线，答案是「记得，但只在这份摘要的粒度上」，而且点开就能看到
 * 它到底记住了什么。没有这条线，用户只能猜；等他发现模型忘了细节，
 * 会归因为「这个 App 的记忆坏了」。
 *
 * ## 为什么显示覆盖条数与 token 数
 *
 * 上游只写了「历史对话摘要」四个字。这里多两行元信息，因为 Praxis 的
 * 压缩是**可手动触发**的（`compactNow`），用户会想知道「这次压了多少」。
 * 两个数字都是现成的：`coveredCount` 与 `summaryTokens`。
 *
 * ## 与上游的版式差异
 *
 * 上游用 Material3 的 `Primary.copy(alpha=0.1f)` 作徽标底。这里按本项目
 * 的既定口径改用 miuix 语义色（`primary` / `dividerLine`），
 * 结构与尺寸一致。
 */
@Composable
fun SummaryDivider(
    compacted: CompactedContext,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokensLabel = remember(compacted.summaryTokens) {
        TokenEstimator.format(compacted.summaryTokens)
    }
    Column(
        modifier = modifier
            // 上下留白与普通气泡的间距（10dp）拉开一点：这是一条「结构线」，
            // 贴着气泡会让它看起来像是某条消息的一部分。
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.5f),
            )
            Box(
                modifier = Modifier
                    .background(
                        color = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 用一个字符当图标：miuix 里没有直接对应的「信息」图标，
                    // 而为一个 14dp 的位置引入 material-icons-extended 的
                    // Icons.Default.Info（:feature:chat 已有该依赖，但这里是
                    // widget 包，保持零额外依赖更干净）。
                    Text(
                        text = "ⓘ",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "历史对话摘要",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.5f),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            // 两个数字一起说清「发生了什么」：折叠了多少条、压成了多少 token。
            text = "以上 ${compacted.coveredCount} 条已折叠为摘要（约 $tokensLabel token）",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 摘要全文弹窗（点分割线后弹出，对齐上游 `SummaryMessageComposable` 的 Dialog 分支）。
 *
 * ## 为什么要可滚动 + 限高
 *
 * 摘要按提示词要求「不要限制字数」——一篇几千字的摘要完全正常。
 * 不限高会把弹窗撑出屏幕，按钮被推出可视区；而摘要本身必须能完整读到
 * （它的用途就是「让用户核对我记住了什么」）。
 *
 * 限高 520dp + `verticalScroll`：与上游一致（上游 `heightIn(max = 520.dp)`
 * 用在列表上，这里是正文，取值相同）。
 */
@Composable
fun SummaryDetailDialog(
    compacted: CompactedContext,
    onDismiss: () -> Unit,
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val createdLabel = remember(compacted.createdAt) {
        if (compacted.createdAt > 0) formatter.format(Date(compacted.createdAt)) else ""
    }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = "历史对话摘要",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (createdLabel.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "$createdLabel · 折叠 ${compacted.coveredCount} 条",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
                Spacer(Modifier.height(10.dp))

                // 正文：只读、可滚动。不加全选复制（正文很长，用户要的是读，
                // 而复制摘要这种需求可以用长按菜单里的「复制」对任一条消息做）。
                Text(
                    text = compacted.summary.ifBlank { "（摘要为空）" },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "关闭", onClick = onDismiss)
                }
            }
        }
    }
}