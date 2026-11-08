package dev.praxis.feature.gateway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Card
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.praxis.ui.widgets.MiuixNavigationItemWidget
import dev.praxis.ui.widgets.MiuixSwitchWidget
import dev.praxis.core.gateway.RoutingStrategy
import dev.praxis.core.gateway.GatewayQueue
import dev.praxis.core.gateway.GatewayQueueEntry
import dev.praxis.ui.widgets.SettingsSectionHeader
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 路由页（文档 9.4）。
 *
 * 本轮只做「候选队列」分区：它回答用户最常问的两个问题 —— 「这次会先用谁」
 * 「那些没参与的是为什么」。
 *
 * ## 为什么「策略」与「容错」两区还没做
 *
 * 9.4 让它们用 MiuixSelectableRow 与 Switch×3 + MiuixNumPickerWidget。但
 * 它们要改的是**选路策略与熔断参数**，而这两组配置在 Praxis 侧还没有落地的地方
 * （RoutePlanner 现在是 priority 升序 + 粘性 + 垫底，没有 order/roundRobin 的分支；
 * 熔断四参只有内存里的默认值，没有配置来源）。
 *
 * 摆一组控件却没有东西承接，就是「点了没反应」的假控件 —— 铁律 5 明确禁止。
 * 所以这一版**如实写着未实现**，等配置层落地再补。
 *
 * ## 队列行不做动画（9.4 原话）
 *
 * 这一块是「看一眼现状」，不是操作入口；加动画只会让它更难读。
 */
@Composable
fun GatewayRoutingScreen(
    /** 当前对外模型名；空 = 还没选模型。 */
    modelName: String,
    entries: List<GatewayQueueEntry>,
    /** 账号 id 到显示名的映射。 */
    accountNames: Map<String, String>,
    /** 当前策略（顺序/轮询 + 粘性）。 */
    policy: dev.praxis.core.gateway.GatewayRoutingPolicy =
        dev.praxis.core.gateway.GatewayRoutingPolicy(),
    /** 策略配置读不出来（坏内容）。true 时页面顶部明说，不静默用默认值。 */
    policyBroken: Boolean = false,
    onPolicyChange: (dev.praxis.core.gateway.GatewayRoutingPolicy) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 顶栏是浮层：内容要能滚到它下面（否则渐进模糊采不到内容，退化成一块实色）。
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    // 底栏同理：滚动终点必须抬高，否则最后一项被压住看不见。
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current
    var faultDialogVisible by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(top = topInset, bottom = 24.dp + scrollLimit),
    ) {
        item(key = "__routing_strategy__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.AltRoute,
                title = "策略",
                subtitle = if (policy.strategy == RoutingStrategy.RoundRobin) {
                    "轮询：把请求摊到多个账号"
                } else {
                    "顺序：一直用优先级最高的可用账号"
                },
            )
        }
        if (policyBroken) {
            item(key = "__routing_broken__") {
                GatewayCard {
                    Text(
                        text = "策略配置读不出来（内容坏了），当前按默认策略走。" +
                            "在上面重选一次即可覆盖。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
        item(key = "__routing_strategy_pick__") {
            GatewayCard {
                MiuixNavigationItemWidget(
                    title = "顺序",
                    description = if (policy.strategy == RoutingStrategy.RoundRobin) {
                        "按优先级挑可用的第一个"
                    } else {
                        "当前：按优先级挑可用的第一个"
                    },
                    onClick = {
                        onPolicyChange(
                            policy.copy(strategy = RoutingStrategy.Sequential),
                        )
                    },
                )
                MiuixNavigationItemWidget(
                    title = "轮询",
                    description = if (policy.strategy == RoutingStrategy.RoundRobin) {
                        "当前：每个请求从下一个账号开始"
                    } else {
                        "每个请求从下一个账号开始，把请求摊开"
                    },
                    onClick = {
                        onPolicyChange(
                            policy.copy(strategy = RoutingStrategy.RoundRobin),
                        )
                    },
                )
            }
        }
        item(key = "__routing_sticky__") {
            GatewayCard {
                MiuixSwitchWidget(
                    title = "粘性",
                    description = "同一会话尽量固定用同一个账号；关掉就每个请求重新挑",
                    checked = policy.stickyEnabled,
                    onCheckedChange = { enabled ->
                        onPolicyChange(policy.copy(stickyEnabled = enabled))
                    },
                )
            }
        }

        // ── 容错（§9.4）──
        item(key = "__routing_fault_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Rule,
                title = "容错",
                subtitle = "熔断：连续失败到阈值后暂时跳过该账号",
            )
        }
        item(key = "__routing_fault_switch__") {
            GatewayCard {
                MiuixSwitchWidget(
                    title = "启用熔断",
                    description = "关掉时上游错误仍会退避换号，只是不会把账号「熔断打开」",
                    checked = policy.breakerEnabled,
                    onCheckedChange = { enabled ->
                        onPolicyChange(policy.copy(breakerEnabled = enabled))
                    },
                )
            }
        }
        item(key = "__routing_fault_numbers__") {
            GatewayCard {
                MiuixNavigationItemWidget(
                    title = "熔断参数",
                    description = "阈值 " + policy.breakerFailureThreshold + " 次 · 冷却 " +
                        (policy.breakerOpenMillis / 1000) + " 秒 · 恢复 " +
                        policy.breakerSuccessThreshold + " 次",
                    onClick = { faultDialogVisible = true },
                )
            }
        }

        item(key = "__routing_queue_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Rule,
                title = "候选队列",
                subtitle = if (modelName.isBlank()) "还没选模型" else "对外模型 " + modelName,
            )
        }

        item(key = "__routing_queue__") {
            GatewayCard {
                if (entries.isEmpty()) {
                    Text(
                        text = if (modelName.isBlank()) {
                            "先在模型页选一个模型，这里就能看出这次会走哪个账号。"
                        } else {
                            "这个模型没有可用候选：先导入账号，或在导入页粘贴一份 modelRules 配上映射。"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    entries.forEachIndexed { index, entry ->
                        val name = accountNames[entry.accountId] ?: entry.accountId
                        val head = if (entry.usable) {
                            "第 " + entry.position + " 位"
                        } else {
                            "跳过"
                        }
                        Text(
                            text = head + " · " + name + " · " + entry.wireModel,
                            style = MiuixTheme.textStyles.body2,
                            color = if (entry.usable) {
                                MiuixTheme.colorScheme.onSurface
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                            modifier = Modifier.padding(top = if (index == 0) 0.dp else 6.dp),
                        )
                        // 先取局部变量：skipReason 是另一个模块里的属性，拿不到智能转换。
                        val reason = entry.skipReason
                        val detail = when {
                            reason != null -> GatewayQueue.reasonText(reason)
                            entry.sink -> "垫底：仍可用，但排在最后"
                            else -> null
                        }
                        if (detail != null) {
                            Text(
                                text = detail,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (faultDialogVisible) {
        FaultParamsDialog(
            policy = policy,
            onDismiss = { faultDialogVisible = false },
            onConfirm = { updated ->
                onPolicyChange(updated)
                faultDialogVisible = false
            },
        )
    }
}

/**
 * 熔断参数弹窗（文档 §9.4 的三个数值）。
 *
 * ## 为什么用文本框而不是步进器
 *
 * 仓库里没有 `MiuixNumPicker` 的封装，自写一个步进器就违反了「不自写控件」这条纪律。
 * 文本框是既有控件，配一句范围说明足够用；数值不合理（<=0）时**不保存并说明**。
 */
@Composable
private fun FaultParamsDialog(
    policy: dev.praxis.core.gateway.GatewayRoutingPolicy,
    onDismiss: () -> Unit,
    onConfirm: (dev.praxis.core.gateway.GatewayRoutingPolicy) -> Unit,
) {
    var threshold by remember {
        mutableStateOf(policy.breakerFailureThreshold.toString())
    }
    var cooldownSeconds by remember {
        mutableStateOf((policy.breakerOpenMillis / 1000).toString())
    }
    var successes by remember {
        mutableStateOf(policy.breakerSuccessThreshold.toString())
    }
    var error by remember { mutableStateOf<String?>(null) }

    MiuixAnimatedDialog(visible = true, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "熔断参数", style = MiuixTheme.textStyles.body1)
                Text(
                    text = "三个值都必须大于 0；改完从下一次判定开始生效（不会重置已有的冷却）",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = threshold,
                    onValueChange = { threshold = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "连续失败多少次后打开",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = cooldownSeconds,
                    onValueChange = { cooldownSeconds = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "打开后保持多少秒",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = successes,
                    onValueChange = { successes = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "恢复所需的成功次数（当前只记录）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                error?.let { text ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                MiuixNavigationItemWidget(
                    title = "保存",
                    description = "不重置已有的冷却与熔断状态",
                    onClick = {
                        val t = threshold.trim().toIntOrNull()
                        val c = cooldownSeconds.trim().toLongOrNull()
                        val s = successes.trim().toIntOrNull()
                        if (t == null || c == null || s == null || t <= 0 || c <= 0 || s <= 0) {
                            error = "三个值都必须是大于 0 的整数"
                        } else {
                            onConfirm(
                                policy.copy(
                                    breakerFailureThreshold = t,
                                    breakerOpenMillis = c * 1000L,
                                    breakerSuccessThreshold = s,
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
}
