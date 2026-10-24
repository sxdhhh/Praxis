package dev.praxis.feature.settings.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工作流节点卡片。
 *
 * ## 为什么这个控件收「原始字段」而不是 `WorkflowNode`
 *
 * `ui:widgets` 是纯 UI 模块，不依赖 `core:workflow`。同一个取舍在
 * [ToolCallCard] 上也做过（收 `name/argumentsJson/result` 而不是 `ToolInvocation`）。
 *
 * 好处很具体：`core:workflow` 改字段时不需要动这个文件；单测不需要构造业务对象；
 * 而映射逻辑留在 `feature:settings`，那里本来就依赖 `core:workflow`。
 *
 * ## 为什么 `details` 是 `List<Pair<String, String>>` 而不是具名字段
 *
 * 五类节点（Trigger/Tool/Condition/Transform/Output）参数完全不同：
 * Tool 有 `toolName/argsTemplate/outputKey`，Condition 有 `expression/thenId/elseId`。
 * 为每类写一个具名参数会让签名膨胀成「大部分字段对大部分调用是 null」。
 * 用有序键值对，由调用方决定显示哪几行，控件只管排版。
 */
@Composable
fun WorkflowNodeCard(
    kindLabel: String,
    title: String,
    details: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    /** 节点类型色（由调用方按类型给，控件不猜业务语义）。 */
    kindColor: Color = MiuixTheme.colorScheme.primary,
    /** 后继节点 id；null 表示流程在此结束。 */
    nextId: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {

                // ── 头行：类型徽章 + 标题 ──────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(kindColor.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = kindLabel,
                            color = kindColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                // ── 参数行 ────────────────────────────────────────
                // 每行 `键  值`，值可长按复制（模板里常需要原样抄出去改）。
                if (details.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Column {
                            details.forEach { (key, value) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 1.dp),
                                ) {
                                    Text(
                                        text = key,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.width(76.dp),
                                    )
                                    Text(
                                        text = value.ifEmpty { "（空）" },
                                        // 等宽字体：模板与表达式里的空格/引号必须可辨。
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                // ── 去向 ──────────────────────────────────────────
                // 工作流最容易出错的地方是「指向了不存在的节点」，
                // 所以每个节点都明确写出它的后继，而不是等引擎报错。
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (nextId == null) "→ 流程在此结束" else "→ $nextId",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        }
    }
}

/** 条件节点的双分支去向（then/else 各一个，不能用单个 nextId 表达）。 */
@Composable
fun WorkflowBranchRow(
    thenId: String?,
    elseId: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BranchChip(label = "成立", target = thenId, highlighted = true)
        BranchChip(label = "不成立", target = elseId, highlighted = false)
    }
}

@Composable
private fun BranchChip(label: String, target: String?, highlighted: Boolean) {
    val color = if (highlighted) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Text(
        text = if (target == null) "$label → 结束" else "$label → $target",
        style = MiuixTheme.textStyles.footnote1,
        color = color,
    )
}