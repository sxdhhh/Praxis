package dev.praxis.feature.settings.tools

import dev.praxis.feature.settings.common.SettingsList
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.tool.DefaultPermissionPolicy
import dev.praxis.core.tool.PermissionDecision
import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec
import dev.praxis.ui.widgets.MiuixBadge
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
    SettingsList(
        modifier = modifier.fillMaxSize(),
    ) {
        item { Spacer(Modifier.height(12.dp)) }
        item { SmallTitle("当前权限：${currentTier.tierLabel}") }
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
                            // 与 ToolExecutor 同一个策略函数：UI 说的就是运行时做的。
                            access = DefaultPermissionPolicy
                                .decide(currentTier, spec.tier)
                                .toAccessLevel(),
                            enabled = isEnabled(spec.name),
                            onToggle = { onToggle(spec.name, it) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单行工具。
 *
 * ## `access` 必须来自真实策略，不能在这里猜
 *
 * 用 [DefaultPermissionPolicy.decide] 算出运行时会得到的判定，
 * 与 `ToolExecutor` 走**同一个函数**。自己写一遍 `if (tier.allows)` 看似等价，
 * 但策略一旦调整（比如把「差一档 ASK」改成「差一档也 FORBID」），
 * 这里就会开始显示错的徽章，而运行时行为已经变了 —— 用户按 UI 判断会判断错。
 */
@Composable
private fun ToolRow(
    spec: ToolSpec,
    access: ToolAccessLevel,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        PermissionSwitchRow(
            name = spec.name,
            description = spec.description,
            requiredTierLabel = spec.tier.tierLabel,
            access = access,
            userEnabled = enabled,
            onUserEnabledChange = onToggle,
        )

        // 风险徽章单独一行：风险是「工具本身多危险」，与「这次能不能跑」正交，
        // 混判定徽章里会让人把 Dangerous 误读成「被拒绝」。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixBadge(
                text = spec.risk.riskLabel,
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

/**
 * 档位文案。
 *
 * ## 为什么不叫 `displayName`
 *
 * [ToolRisk] 也需要一个文案扩展。两个都叫 `displayName` 时，
 * 同一文件里 import 两个会直接冲突（Kotlin 扩展属性按名字解析，不看接收者），
 * 测试里也没法同时引用。分开命名后调用点一眼能看出取的是哪个维度。
 */
internal val PermissionTier.tierLabel: String
    get() = when (this) {
        PermissionTier.STANDARD -> "标准"
        PermissionTier.DEBUGGER -> "调试"
        PermissionTier.ROOT -> "Root"
    }

/** 风险文案。 */
internal val ToolRisk.riskLabel: String
    get() = when (this) {
        ToolRisk.Safe -> "只读"
        ToolRisk.Moderate -> "写入"
        ToolRisk.Dangerous -> "高危"
    }

/** 策略判定 → 控件展示枚举。写在全仓唯一处，避免各页各自 map 出不一致的文案。 */
internal fun PermissionDecision.toAccessLevel(): ToolAccessLevel = when (this) {
    PermissionDecision.ALLOW -> ToolAccessLevel.Allow
    PermissionDecision.ASK -> ToolAccessLevel.Ask
    PermissionDecision.FORBID -> ToolAccessLevel.Forbid
}
