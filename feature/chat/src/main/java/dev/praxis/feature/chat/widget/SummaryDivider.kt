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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.praxis.ui.theme.PraxisRadius
import dev.praxis.ui.widgets.FoldableContent

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
    /** 点「看全文」时打开弹窗（就地展开之外的备选，摘要很长时用）。 */
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 撤销本次压缩（null = 不提供）。
     *
     * ## 为什么需要撤销入口
     *
     * 压缩是**有损**的，而触发它的时机（上下文接近上限、内存压力）
     * 往往是自动的 —— 用户可能在没同意的情况下丢掉正用着的细节。
     * 没有撤销时唯一的补救是「重新说一遍」，等于再花一次钱。
     */
    onUndo: (() -> Unit)? = null,
) {
    // ## 就地折叠/展开（2026-11-05）
    //
    // 用户的要求：「摘要不用显示全文，折叠起来能展开和收回并且有动画」。
    //
    // 旧实现只有「点徽标弹窗看全文」：要看一眼摘要必须开弹窗、再关掉，
    // 而用户往往只想扫一眼前几行确认模型记住了什么。
    //
    // 现在默认折叠、点徽标就地展开，动画交给 `FoldableContent`
    //（它内部是 AnimatedVisibility + PraxisMotion 的时长与曲线，
    // 全项目折叠面板共用同一套手感，不自绘）。
    //
    // `remember` 不带 key：摘要内容变化时用户既有的展开状态应当保留 ——
    // 他刚点开看，摘要因为新一轮压缩而更新，面板突然自己收起来会很怪。
    var expanded by remember { mutableStateOf(false) }
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
                // 点徽标 = 就地展开/收起（主动作）。
                .clickable { expanded = !expanded }
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
                        shape = PraxisRadius.MediumShape,
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
            // ## 从「压成了多少」改成「净省了多少」（2026-11-06）
            //
            // 旧文案只报摘要自身的 token 数。用户真正关心的是**这次压缩
            // 省了多少上下文** —— 而那要减去摘要本身的开销。
            //
            // 净收益可能为负（只折叠了两条短消息时摘要反而更长）。
            // 这种情况必须如实显示：用户会疑惑「压完怎么更大了」，
            // 而答案正是「这次压得不值」。掩盖负值会让他失去判断依据。
            text = if (compacted.coveredTokens > 0) {
                val saved = compacted.savedTokens
                val sign = if (saved >= 0) "省" else "多占"
                val abs = kotlin.math.abs(saved)
                "以上 ${compacted.coveredCount} 条已折叠，$sign 约 $abs token" +
                    if (expanded) " · 点上方收起" else " · 点上方展开"
            } else {
                "以上 ${compacted.coveredCount} 条已折叠为摘要（约 $tokensLabel token）" +
                    if (expanded) " · 点上方收起" else " · 点上方展开"
            },
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        // 就地展开的摘要正文。限高 + 滚动：摘要可能几千字（提示词要求
        // 「宁可内容多一点」），不限高会把整页推得很长。
        FoldableContent(visible = expanded, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = compacted.summary.ifBlank { "（摘要为空）" },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                    // 全文弹窗保留：摘要很长时，360dp 高的就地面板看不完整，
                    // 弹窗能给出更大的阅读面积。
                    TextButton(
                        text = "看全文",
                        onClick = onClick,
                        modifier = Modifier.weight(1f),
                    )
                    if (onUndo != null) {
                        TextButton(
                            text = "撤销压缩",
                            onClick = onUndo,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
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