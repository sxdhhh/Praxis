package dev.praxis.feature.settings.tools

import dev.praxis.feature.settings.common.SettingsList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.core.model.permission.CapabilitySnapshot
import dev.praxis.core.model.permission.CapabilityStatus
import dev.praxis.core.model.permission.PraxisCapability
import dev.praxis.core.model.permission.PraxisPermissionLevel
import dev.praxis.core.tool.DefaultPermissionPolicy
import dev.praxis.core.tool.PermissionDecision
import dev.praxis.core.tool.PermissionTier
import dev.praxis.core.tool.PermissionTier.ROOT
import dev.praxis.core.tool.ToolRisk
import dev.praxis.core.tool.ToolSpec
import dev.praxis.core.tool.allows
import dev.praxis.ui.widgets.MiuixBadge
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Unlock
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 权限页：**真实授权状态 + 能力档位策略**，两块内容，不能互相冒充。
 *
 * ## 这一页此前是错的（2026-10-22 修）
 *
 * 用户原话：「权限管理 tmd 就跟没有一样连个正经的授权检测都没有」。
 *
 * 旧实现只有下面第二块（三档策略），并且把「已授予 / 可提升 / 不可直达」
 * 直接从枚举推出来当状态显示。问题不在措辞，在**信息是假的**：
 *
 *  - `manifest` 里当时只有 INTERNET 等 4 个普通权限，**一个运行时权限都没声明**；
 *  - 界面却在档位切到 ROOT 时显示「已解锁 Root 级能力」；
 *  - 用户真去用文件工具时立刻 `PermissionDenied`，因为连 `/sdcard` 都没被授权。
 *
 * 也就是说：这一页在**没有问过系统任何一次**的情况下，报告了权限状态。
 *
 * ## 现在的结构（两块，职责不重叠）
 *
 * ```
 * ┌ 顶部状态卡 ─────────────────────┐
 * │ 必需能力 1/1 已授予 / 缺 1 项      │ ← 真实检测结果
 * └──────────────────────────────────┘
 * ┌ 系统授权 ───────────────────────┐
 * │ 通知         已授予 / 未授予      │ ← 逐项，带「去授予」
 * │ 存储访问     未授予      [去授予] │
 * │ 高级执行通道 不支持               │
 * └──────────────────────────────────┘
 * ┌ 工具能力档位 ───────────────────┐
 * │ STANDARD / DEBUGGER / ROOT       │ ← 纯策略，只决定发给模型哪些工具
 * └──────────────────────────────────┘
 * ```
 *
 * 两块**必须视觉上分开**，否则又会回到「用户以为提权就等于拿到系统权限」
 * 的老问题。第二块的一组文案里明确写了它不授予任何系统权限。
 *
 * ## 为什么状态卡不显示「几个工具可用」
 *
 * 那是第二块的信息。放在顶上会让用户以为「工具数少 = 权限没给」，
 * 但实际可能是他自己把工具关掉了。顶部只回答一个问题：
 * **系统层面还缺什么**。
 *
 * @param capabilities 真实检测结果（由 `CapabilityDetector` 提供）
 * @param currentTier 当前档位（策略上限）
 * @param allTools 全部已注册工具
 * @param isEnabled 用户级启用状态
 * @param onRequestCapability 点「去授予」——真正发起申请/跳系统设置
 * @param onSelect 切换档位
 */
@Composable
fun PermissionScreen(
    capabilities: CapabilitySnapshot,
    currentTier: PermissionTier,
    allTools: List<ToolSpec>,
    isEnabled: (String) -> Boolean,
    onRequestCapability: (PraxisCapability) -> Unit,
    onSelect: (PermissionTier) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 档位层统计（与旧实现同口径，保持不变）。
    val grantedTierCount = currentTier.ordinal + 1
    val usableTools = allTools.count { currentTier.allows(it.tier) && isEnabled(it.name) }
    val lockedTools = allTools.count { !currentTier.allows(it.tier) }

    // 授权层统计：只数「必需」能力，非必需的缺失不该让顶卡变红。
    val requiredStates = capabilities.states.filter { it.capability.required }
    val requiredGranted = requiredStates.count { it.isGranted }
    val allRequiredOk = capabilities.allRequiredGranted

    SettingsList(
        modifier = modifier.fillMaxSize(),
    ) {
        item { Spacer(Modifier.height(12.dp)) }

        // ── 顶部：真实授权结论 ─────────────────────────────────────
        item {
            PrivilegeStatusCard(
                // 必需能力没齐 -> 红；齐了 -> 绿。这里**只**看真实授权，
                // 不看档位（档位高但没授权的情况太常见，混在一起会误报为正常）。
                active = allRequiredOk,
                title = if (allRequiredOk) {
                    "系统授权：已就绪"
                } else {
                    "系统授权：缺 ${requiredStates.size - requiredGranted} 项"
                },
                description = if (allRequiredOk) {
                    "必需权限均已授予，工具可以正常读写文件。"
                } else {
                    capabilities.missingRequired.joinToString("、") { it.capability.label } +
                        " 未授予，相关工具会失败。"
                },
                statusIcon = if (allRequiredOk) {
                    MiuixIcons.Regular.Lock
                } else {
                    MiuixIcons.Regular.Unlock
                },
                statusDetail = "以下状态每次进入本页都会重新向系统确认一次。",
            )
        }

        // ── 当前实际权限层级（对齐 Operit 的「当前使用中」结论）──
        //
        // ## 为什么这一块与下面的「工具能力档位」分开
        //
        // 两者答的是不同问题（见 `PraxisPermissionLevel` 的 KDoc）：
        //  - 这里：**本机本应用实际能到什么程度**（由真实检测推导，用户改不了）；
        //  - 下面：**把哪些工具描述发给模型**（用户选的策略）。
        //
        // 合并显示过，结果就是「切到 Root 档位，界面说已解锁 Root 级能力，
        // 实际连 /sdcard 都读不了」那个割裂状态。分开之后，
        // 用户能一眼看到「我选了 Root 档，但真实层级还是基础」。
        //
        // 只算一次：同一份快照推导两次会得到同一个值，但在
        // `LazyColumn` 的 item 里各算一遍会让「两处显示不一致」
        // 变成可能（比如将来有人只改一处）。提到外面算好再传。
        val actualLevel = PraxisPermissionLevel.deriveFrom(capabilities)
        item {
            PrivilegeStatRow(
                leftTitle = "实际权限层级",
                leftValue = actualLevel.label,
                rightTitle = "已授予",
                rightValue = "${capabilities.states.count { it.isGranted }} / ${capabilities.states.size}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = actualLevel.summary,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ── 系统授权（逐项，可操作）───────────────────────────────
        item { SmallTitle("系统授权") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                capabilities.states.forEach { state ->
                    CapabilityRow(
                        label = state.capability.label,
                        // detail 优先：它比状态枚举信息量大。
                        // 「Shizuku 已安装但未运行」比「未授予」有用得多。
                        description = state.detail.ifBlank { state.capability.rationale },
                        statusText = when (state.status) {
                            CapabilityStatus.GRANTED -> "已授予"
                            CapabilityStatus.DENIED -> "未授予"
                            CapabilityStatus.UNSUPPORTED -> "不支持"
                        },
                        statusColor = when (state.status) {
                            CapabilityStatus.GRANTED -> MiuixTheme.colorScheme.primary
                            CapabilityStatus.DENIED -> MiuixTheme.colorScheme.error
                            CapabilityStatus.UNSUPPORTED ->
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        // 只有 DENIED 才给按钮：
                        //  - GRANTED 不需要；
                        //  - UNSUPPORTED 给了也没用（点了不弹任何东西），
                        //    那种「点了没反应」正是用户之前抱怨的按钮。
                        actionText = if (state.canRequest) "去授予" else null,
                        onAction = { onRequestCapability(state.capability) },
                    )
                }
            }
        }

        // ── 工具能力档位（纯策略）──────────────────────────────────
        item { SmallTitle("工具能力档位") }
        item {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        // 这段是把两块的分工讲清楚，避免再被误解成「提权=拿到系统权限」。
                        text = "档位决定把哪些工具交给模型调用，**不授予任何系统权限**。" +
                            "系统权限只由上面「系统授权」决定。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item {
            PrivilegeStatRow(
                leftTitle = "已授予档位",
                leftValue = "$grantedTierCount",
                rightTitle = "可用工具",
                rightValue = "$usableTools",
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        PermissionTier.entries.forEach { tier ->
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    MiuixNavigationItemWidget(
                        title = tier.tierLabel + if (tier == currentTier) "（当前）" else "",
                        description = tier.summary(),
                        onClick = { onSelect(tier) },
                    )
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val decision = DefaultPermissionPolicy.decide(currentTier, tier)
                            MiuixBadge(
                                text = when (decision) {
                                    PermissionDecision.ALLOW -> "已授予"
                                    PermissionDecision.ASK -> "可直接提升"
                                    PermissionDecision.FORBID -> "不可直达"
                                },
                                textColor = when (decision) {
                                    PermissionDecision.ALLOW ->
                                        MiuixTheme.colorScheme.primary
                                    PermissionDecision.ASK ->
                                        MiuixTheme.colorScheme.onSurface
                                    PermissionDecision.FORBID ->
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                            Text(
                                text = tier.detail(allTools),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }

        // ── 未覆盖的工具 ───────────────────────────────────────────
        if (lockedTools > 0) {
            item { SmallTitle("尚未解锁") }
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "还有 $lockedTools 个工具需要更高档位。",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "提升档位只会让这些工具出现在模型的可调用列表里。" +
                                "若它们需要系统权限（例如读写 /sdcard），" +
                                "还要在上面的「系统授权」里授予对应权限。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一行能力状态：名称 + 说明 + 状态字 + 可选操作。
 *
 * 为什么不用 `MiuixNavigationItemWidget`：那个组件的语义是「点整行进下一页」，
 * 而这里只有右侧按钮可点（点整行去做授予是很糟的交互 —— 用户只是想看说明时
 * 会被弹一个系统框）。所以自己拼一行，视觉上仍用 miuix 的排版尺度。
 */
@Composable
private fun CapabilityRow(
    label: String,
    description: String,
    statusText: String,
    statusColor: androidx.compose.ui.graphics.Color,
    actionText: String?,
    onAction: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            // ★ 这里原本写的是 `Modifier.fillMaxSize()`（2026-10-22 修）。
            //
            // `Row` 是**横向**容器，`fillMaxSize()` 会让它同时填满宽度和**高度**。
            // 后果不是「看起来宽一点」，而是布局直接错乱：
            //  - 每一行都会撑到父容器的高度，3 个能力项就把整张 Card 撑成
            //    3 倍屏高，列表里出现大片空白；
            //  - 行内容（两段 Text）在竖直方向被居中到那一片空白中间，
            //    看起来像「文字飘在半空、行距巨大」；
            //  - 卡片之间互相挤压，后面的「工具能力档位」被推出屏幕。
            //
            // 正确写法是 `fillMaxWidth()`：横向容器只需要撑满宽度，
            // 高度由内容（Text 的高度）决定。这也是 Compose 里 Row/Column
            // 唯一常需要的 fill 方向 —— 需要双向撑满的场景应该用 Box。
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text(
                text = statusText,
                style = MiuixTheme.textStyles.footnote1,
                color = statusColor,
            )
        }
        if (actionText != null) {
            Spacer(Modifier.height(8.dp))
            // 用 miuix 的 TextButton 语义而不是自绘 Row + clickable：
            // 自绘的行没有水波纹、没有最小点击区、无障碍拿不到 role=Button。
            top.yukonga.miuix.kmp.basic.TextButton(
                text = actionText,
                onClick = onAction,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** 一句话概括（列表行用）。 */
private fun PermissionTier.summary(): String = when (this) {
    PermissionTier.STANDARD -> "读写应用工作目录、基础查询"
    PermissionTier.DEBUGGER -> "在标准之上增加诊断与调试能力"
    ROOT -> "在调试之上增加系统级操作"
}

/** 详细说明（含该档位能解锁哪些工具）。 */
private fun PermissionTier.detail(allTools: List<ToolSpec>): String {
    val unlocked = allTools.filter { this.allows(it.tier) }
    val dangerous = unlocked.count { it.risk == ToolRisk.Dangerous }

    val base = "可用 ${unlocked.size} 个工具"
    return if (dangerous > 0) "$base，其中 $dangerous 个高风险" else base
}