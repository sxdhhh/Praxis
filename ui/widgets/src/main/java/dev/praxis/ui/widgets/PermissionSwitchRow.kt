package dev.praxis.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工具这次调用会得到的**实际判定结果**。
 *
 * ## 为什么这是「展示」而不是「可切换的三态」
 *
 * 设计文档写的是「工具权限行（ALLOW/ASK/FORBID 三态）」，容易被理解成
 * 「用户逐工具选三态」。**但底层模型不支持**：
 * `ToolPermissionState` 只有 `tier` 与 `disabledTools: Set<String>`，
 * 而 ALLOW/ASK/FORBID 是 `DefaultPermissionPolicy.decide(granted, required)`
 * 按两档差距**实时算**出来的（够 → ALLOW，差一档 → ASK，差两档 → FORBID）。
 *
 * 若做成三态开关，用户选了 ASK 却因档位足够而直接放行，或选了 ALLOW 却被
 * FORBID 拦下 —— 控件在骗人。所以这里的语义是：
 *
 *  - **判定结果**：只读展示（由 `feature:settings` 用真实策略算好传进来）；
 *  - **开关**：真实存在的用户级禁用（`disabledTools`）。
 *
 * 两个维度都是真的，用户看到什么就是运行时会发生什么。
 */
enum class ToolAccessLevel {
    /** 档位足够，直接执行。 */
    Allow,

    /** 差一档：会弹框询问。当前 `askHandler` 传 null，实际降级为拒绝。 */
    Ask,

    /** 差两档：一律拒绝，问都不问。 */
    Forbid,
}

/**
 * 工具权限行。
 *
 * @param name 工具名（模型看到的原文）
 * @param description 工具说明
 * @param requiredTierLabel 所需档位文案（如「Root」）
 * @param access 实际判定结果
 * @param userEnabled 用户是否启用（`disabledTools` 的反面）
 * @param onUserEnabledChange 切换用户级禁用
 */
@Composable
fun PermissionSwitchRow(
    name: String,
    description: String,
    requiredTierLabel: String,
    access: ToolAccessLevel,
    userEnabled: Boolean,
    onUserEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocked = access == ToolAccessLevel.Forbid
    // 被策略拦下时开关必须置灰：可点却无效，比置灰更让人困惑。
    // 但**不禁用整行** —— 用户仍应能读到这个工具存在。
    val switchEnabled = !blocked

    // ## 必须用 content = 显式命名
    //
    // `BasicComponent` 有两个重载：
    //  1. `(title, summary, startAction, endActions, ..., interactionSource)` —— 无尾插槽；
    //  2. `(startAction, endActions, ..., content: @Composable ColumnScope.() -> Unit)`。
    //
    // 只要传了 `title`/`summary` 命名参数，重载解析就锁定到第 1 个，
    // 于是尾随 lambda 会落到最后一个形参 `interactionSource` 上，报
    // 「actual type is '() -> Unit', but 'MutableInteractionSource?' was expected」，
    // 连带后面所有 @Composable 调用都失效。所以这里既不能传 title/summary，
    // 也必须显式写 `content =`。
    BasicComponent(
        modifier = modifier.fillMaxWidth(),
        enabled = true,
        endActions = {
            Switch(
                checked = userEnabled && !blocked,
                onCheckedChange = onUserEnabledChange,
                enabled = switchEnabled,
            )
        },
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MiuixTheme.textStyles.body1,
                    color = if (blocked) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                AccessBadge(access)
            }

            Text(
                text = description,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )

            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "需 $requiredTierLabel",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
                if (!userEnabled && !blocked) {
                    // 「档位够了但用户自己关了」必须说清，否则看到 ASK/ALLOW 却没执行会以为是 bug。
                    Text(
                        text = "· 已被你停用",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        },
    )
}

/** 判定徽章：颜色承担语义（绿=放行 / 橙=要问 / 红=拒绝）。 */
@Composable
private fun AccessBadge(access: ToolAccessLevel) {
    val (label, color) = when (access) {
        ToolAccessLevel.Allow -> "放行" to MiuixTheme.colorScheme.primary
        ToolAccessLevel.Ask -> "需确认" to MiuixTheme.colorScheme.onSurface
        ToolAccessLevel.Forbid -> "拒绝" to MiuixTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = color,
        )
    }
}
