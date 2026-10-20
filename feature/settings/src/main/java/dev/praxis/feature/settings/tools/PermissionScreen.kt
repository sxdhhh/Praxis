package dev.praxis.feature.settings.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec
import dev.praxis.core.tool.allows
import dev.praxis.feature.settings.SettingsList
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 权限档位选择页。
 *
 * 三档并列，每档写清「能干什么 / 有什么后果」，而不是只给一个名字。
 * 用户点 Root 档之前必须知道这意味着「模型可以执行任意系统命令」——
 * 只写「Root」两个字等于什么都没说。
 */
@Composable
fun PermissionScreen(
    currentTier: PermissionTier,
    allTools: List<ToolSpec>,
    onSelect: (PermissionTier) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsList(
        modifier = modifier.fillMaxSize(),
    ) {
        item { Spacer(Modifier.height(12.dp)) }
        item { SmallTitle("权限档位") }

        PermissionTier.entries.forEach { tier ->
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    MiuixNavigationItemWidget(
                        title = tier.displayName + if (tier == currentTier) "（当前）" else "",
                        description = tier.summary(),
                        onClick = { onSelect(tier) },
                    )
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                        Text(
                            text = tier.detail(allTools),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        item { SmallTitle("说明") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "档位是能力上限。低于所需档位的工具不会出现在模型的可调用列表里，" +
                            "模型也就不会尝试它们。\n\n" +
                            "提权后，上一档位可用的工具继续可用；降权后，超出档位的工具立即从列表移除。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

/** 一句话概括（列表行用）。 */
private fun PermissionTier.summary(): String = when (this) {
    PermissionTier.STANDARD -> "读写应用工作目录、基础查询"
    PermissionTier.DEBUGGER -> "在标准之上增加诊断与调试能力"
    PermissionTier.ROOT -> "在调试之上增加系统级操作"
}

/** 详细说明（含该档位能解锁哪些工具）。 */
private fun PermissionTier.detail(allTools: List<ToolSpec>): String {
    val unlocked = allTools.filter { this.allows(it.tier) }
    val dangerous = unlocked.count { it.risk == ToolRisk.Dangerous }

    val base = "可用工具 ${unlocked.size} 个"
    return if (dangerous > 0) "$base，其中 $dangerous 个为高风险操作" else base
}
