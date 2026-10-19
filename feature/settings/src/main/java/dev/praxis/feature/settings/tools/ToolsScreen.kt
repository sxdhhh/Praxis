package dev.praxis.feature.settings.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec
import dev.praxis.core.tool.allows
import dev.praxis.ui.widgets.MiuixBadge
import dev.praxis.ui.widgets.MiuixSwitchWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 工具列表页。
 *
 * 展示**全部已注册工具**（包括当前档位用不了的），并明确标出「为什么用不了」。
 * 只列可用工具是错的：用户永远不知道这个 App 到底具备哪些能力，
 * 也就无从判断该不该提权。
 *
 * 每行给三件事：
 *  - 名称与说明（模型看到的原文，保持一致）；
 *  - 所需档位 + 风险等级（用 Badge 标出，高危一眼可见）；
 *  - 启用开关（用户级禁用，与档位独立）。
 */
@Composable
fun ToolsScreen(
    tools: List<ToolSpec>,
    currentTier: PermissionTier,
    isEnabled: (String) -> Boolean,
    onToggle: (name: String, enabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Spacer(Modifier.height(12.dp)) }
        item { SmallTitle("当前权限：${currentTier.displayName}") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = "档位决定能力上限。低于所需档位的工具不会提供给模型，" +
                        "避免它反复尝试被拒的操作。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        item { SmallTitle("已注册工具（${tools.size}）") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                if (tools.isEmpty()) {
                    Text(
                        text = "还没有注册任何工具。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    tools.forEach { spec ->
                        ToolRow(
                            spec = spec,
                            usable = currentTier.allows(spec.tier),
                            enabled = isEnabled(spec.name),
                            onToggle = { onToggle(spec.name, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolRow(
    spec: ToolSpec,
    usable: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        MiuixSwitchWidget(
            title = spec.name,
            // 没权限时把原因写进副标题，而不是简单置灰 —— 置灰让人不知道为什么。
            description = if (usable) spec.description else "${spec.description}（需 ${spec.tier.displayName}）",
            enabled = usable,
            checked = enabled && usable,
            onCheckedChange = onToggle,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixBadge(
                text = spec.tier.displayName,
                textColor = if (usable) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            MiuixBadge(
                text = spec.risk.displayName,
                textColor = when (spec.risk) {
                    ToolRisk.Safe -> MiuixTheme.colorScheme.primary
                    ToolRisk.Moderate -> MiuixTheme.colorScheme.onSurface
                    ToolRisk.Dangerous -> MiuixTheme.colorScheme.error
                },
                containerColor = when (spec.risk) {
                    ToolRisk.Safe -> MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    ToolRisk.Moderate -> MiuixTheme.colorScheme.surfaceContainerHigh
                    ToolRisk.Dangerous -> MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                },
            )
        }
    }
}

/** 档位文案。 */
internal val PermissionTier.displayName: String
    get() = when (this) {
        PermissionTier.STANDARD -> "标准"
        PermissionTier.DEBUGGER -> "调试"
        PermissionTier.ROOT -> "Root"
    }

/** 风险文案。 */
internal val ToolRisk.displayName: String
    get() = when (this) {
        ToolRisk.Safe -> "只读"
        ToolRisk.Moderate -> "写入"
        ToolRisk.Dangerous -> "高危"
    }
