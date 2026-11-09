package dev.praxis.feature.gateway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.praxis.ui.widgets.MiuixAnimatedDialog
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Card
import dev.praxis.ui.widgets.MiuixIntNumberPickerWidget
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
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
    /**
     * 路由回放（文档 §5.5 的降级形态）：最近若干次决策。
     *
     * 只在内存里，所以**进程重启后是空的** —— 这一点写在页面上，
     * 否则用户会以为「回放坏了」。
     */
    /**
     * 现取回放条目（不是 List）：回放是**内存里的列表，没有可观察的 Flow**，
     * 用 List 就得靠调用方猜什么时候重算（猜错就是「明明刚发过请求，这页还是空的」）。
     * 每次组合读一次，进这一页就是最新的。
     */
    /** 路由组（文档 §5.5）：把跨家的几个组合并成一个对外名。 */
    routeGroups: List<dev.praxis.core.gateway.GatewayRouteGroup> = emptyList(),
    onAddRouteGroup: (dev.praxis.core.gateway.GatewayRouteGroup) -> Unit = {},
    onRemoveRouteGroup: (String) -> Unit = {},
    replayEntriesProvider: () -> List<dev.praxis.core.gateway.RouteReplayEntry> = {
        emptyList()
    },
    modifier: Modifier = Modifier,
) {
    // 顶栏是浮层：内容要能滚到它下面（否则渐进模糊采不到内容，退化成一块实色）。
    val topInset = dev.praxis.ui.design.component.LocalTopBarInset.current
    // 底栏同理：滚动终点必须抬高，否则最后一项被压住看不见。
    val scrollLimit = dev.praxis.ui.design.component.LocalScrollBottomLimit.current
    var faultDialogVisible by remember { mutableStateOf(false) }
    var budgetDialogVisible by remember { mutableStateOf(false) }
    var groupDialogVisible by remember { mutableStateOf(false) }
    // 每次组合现取一次（见 replayEntriesProvider 的说明）。
    val replayEntries = replayEntriesProvider()

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

        // ── 路由组（§5.5）──
        item(key = "__routing_groups_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Rule,
                title = "路由组",
                subtitle = "把跨家的几个组合并成一个对外名",
            )
        }
        item(key = "__routing_groups__") {
            GatewayCard {
                if (routeGroups.isEmpty()) {
                    Text(
                        text = "还没有组。加一个之后，模型页填那个对外名就会按组内顺序试。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    routeGroups.forEachIndexed { index, group ->
                        Column(modifier = Modifier.padding(top = if (index == 0) 0.dp else 10.dp)) {
                            Text(text = group.name, style = MiuixTheme.textStyles.body2)
                            Text(
                                text = group.members.joinToString("、") { member ->
                                    member.provider + "/" + member.model
                                } + "　（点这里删除）",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable { onRemoveRouteGroup(group.name) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "__routing_groups_add__") {
            GatewayCard {
                MiuixNavigationItemWidget(
                    title = "添加路由组",
                    description = "对外名 + 成员（每行一个，写成 家/模型）",
                    onClick = { groupDialogVisible = true },
                )
            }
        }

        // ── 回放（§5.5 的降级形态）──
        item(key = "__routing_replay_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.AltRoute,
                title = "最近的路由决策",
                subtitle = "排障用：只看内存里最近 50 条，重启后是空的",
            )
        }
        item(key = "__routing_replay__") {
            GatewayCard {
                if (replayEntries.isEmpty()) {
                    Text(
                        text = "还没有记录。发一次请求（失败的那次最有用）就会出现。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    replayEntries.forEachIndexed { index, entry ->
                        Column(modifier = Modifier.padding(top = if (index == 0) 0.dp else 10.dp)) {
                            Text(
                                text = entry.model + " · " + entry.outcome,
                                style = MiuixTheme.textStyles.body2,
                            )
                            Text(
                                text = replayTimeText(entry.atMillis),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            if (entry.tried.isNotEmpty()) {
                                Text(
                                    text = "尝试：" + entry.tried.joinToString("、"),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            if (entry.skipped.isNotEmpty()) {
                                Text(
                                    text = "跳过：" + entry.skipped.joinToString("、") { skipped ->
                                        skipped.accountId + "（" +
                                            dev.praxis.core.gateway.GatewayQueue.reasonText(
                                                skipped.reason,
                                            ) + "）"
                                    },
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
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

        // ── 预算（§7.2 第 6 条）──
        item(key = "__routing_budget_title__") {
            SettingsSectionHeader(
                icon = Icons.Outlined.Rule,
                title = "token 预算",
                subtitle = "一个账号在窗口内用满就不再选它",
            )
        }
        item(key = "__routing_budget_row__") {
            GatewayCard {
                MiuixNavigationItemWidget(
                    title = "每账号预算",
                    description = if (policy.tokenBudgetPerAccount <= 0L) {
                        "不限（点这里设置）"
                    } else {
                        budgetText(policy.tokenBudgetPerAccount) + " / " +
                            policy.budgetWindowHours + " 小时"
                    },
                    onClick = { budgetDialogVisible = true },
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

    if (budgetDialogVisible) {
        BudgetDialog(
            policy = policy,
            onDismiss = { budgetDialogVisible = false },
            onConfirm = { updated ->
                onPolicyChange(updated)
                budgetDialogVisible = false
            },
        )
    }

    if (groupDialogVisible) {
        AddRouteGroupDialog(
            existing = routeGroups,
            onDismiss = { groupDialogVisible = false },
            onConfirm = { group ->
                onAddRouteGroup(group)
                groupDialogVisible = false
            },
        )
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
 * ## 用 `MiuixIntNumberPickerWidget`（滑块）而不是文本框
 *
 * 这三个值都是**有界的小整数**，滑块天然把取值限制在合法区间里 ——
 * 用户不可能填出「0 次」或「三次」这种需要再报错的东西。
 *
 * 此前这里用的是文本框 + 保存时校验，那是我**误判「仓库没有数值控件」**造成的：
 * `ui/widgets/MiuixNumPickerWidget.kt` 一直都在（函数名 `MiuixIntNumberPickerWidget`），
 * 文档 §9.0 的控件表里也明确列了它。
 */
@Composable
private fun FaultParamsDialog(
    policy: dev.praxis.core.gateway.GatewayRoutingPolicy,
    onDismiss: () -> Unit,
    onConfirm: (dev.praxis.core.gateway.GatewayRoutingPolicy) -> Unit,
) {
    // 滑块把取值限制在区间里，所以这里**不需要 error 状态** —— 非法值不可表达。
    // 初始值也夹一次：老配置里可能存着区间外的数（比如 cooldown 5 分钟 = 300 秒没问题，
    // 但阈值存了 0 就有可能来自手改过的设置文件）。
    var threshold by remember {
        mutableIntStateOf(policy.breakerFailureThreshold.coerceIn(1, 20))
    }
    var cooldownSeconds by remember {
        mutableIntStateOf((policy.breakerOpenMillis / 1000).coerceIn(10L, 600L).toInt())
    }
    var successes by remember {
        mutableIntStateOf(policy.breakerSuccessThreshold.coerceIn(1, 10))
    }

    MiuixAnimatedDialog(visible = true, onDismissRequest = onDismiss) {
        Card {
            // 滑块自带 16dp 内边距，这里只留纵向间距，避免双重缩进。
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "熔断参数",
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = "改完从下一次判定开始生效（不会重置已有的冷却）",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                MiuixIntNumberPickerWidget(
                    title = "连续失败多少次后打开",
                    value = threshold,
                    startInt = 1,
                    endInt = 20,
                    valueSuffix = " 次",
                    onValueChange = { threshold = it },
                )
                MiuixIntNumberPickerWidget(
                    title = "打开后保持多久",
                    description = "这段时间里不会再选它",
                    value = cooldownSeconds,
                    startInt = 10,
                    endInt = 600,
                    valueSuffix = " 秒",
                    onValueChange = { cooldownSeconds = it },
                )
                MiuixIntNumberPickerWidget(
                    title = "恢复所需的成功次数",
                    description = "当前实现只记录，不据此自动恢复",
                    value = successes,
                    startInt = 1,
                    endInt = 10,
                    valueSuffix = " 次",
                    onValueChange = { successes = it },
                )
                Spacer(Modifier.height(12.dp))
                MiuixNavigationItemWidget(
                    title = "保存",
                    description = "不重置已有的冷却与熔断状态",
                    onClick = {
                        onConfirm(
                            policy.copy(
                                breakerFailureThreshold = threshold,
                                breakerOpenMillis = cooldownSeconds * 1000L,
                                breakerSuccessThreshold = successes,
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** 预算数字的显示：带千分位，读得快。 */
private fun budgetText(tokens: Long): String {
    if (tokens <= 0L) return "不限"
    return if (tokens >= 1000L) (tokens / 1000).toString() + "k tokens" else tokens.toString() + " tokens"
}

/**
 * token 预算弹窗（文档 §7.2 第 6 条）。
 *
 * ## 0 是「不限」，不是一个特殊的错误值
 *
 * 默认就是不限：给个默认额度会让用户在完全不知情的情况下撞上「额度用完了」。
 *
 * ## 上限为什么以「k tokens」为单位
 *
 * 滑块是整数步进的（`MiuixIntNumberPickerWidget`）。真实预算是十万到百万量级，
 * 用 1 为单位会让「每个像素跳几千」且读数毫无意义；用 k（0~2000k）既保留了
 * 合理精度，显示的也是人能读的数字。存进设置时再乘 1000。
 */
@Composable
private fun BudgetDialog(
    policy: dev.praxis.core.gateway.GatewayRoutingPolicy,
    onDismiss: () -> Unit,
    onConfirm: (dev.praxis.core.gateway.GatewayRoutingPolicy) -> Unit,
) {
    // 单位是 k tokens；0 = 不限。
    var tokensK by remember {
        mutableIntStateOf((policy.tokenBudgetPerAccount / 1000L).coerceIn(0L, 2000L).toInt())
    }
    var hours by remember { mutableIntStateOf(policy.budgetWindowHours.coerceIn(1, 720)) }

    MiuixAnimatedDialog(visible = true, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "token 预算",
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = "每个账号在窗口内用满这个数就不再选它。" +
                        "统计只含真实请求，连通性测试不计入。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                MiuixIntNumberPickerWidget(
                    title = "每个账号的上限",
                    description = "0 就是不限",
                    value = tokensK,
                    startInt = 0,
                    endInt = 2000,
                    valueSuffix = "k tokens",
                    onValueChange = { tokensK = it },
                )
                MiuixIntNumberPickerWidget(
                    title = "窗口长度",
                    description = "滚动的：从此刻往回数这么多小时",
                    value = hours,
                    startInt = 1,
                    endInt = 720,
                    valueSuffix = " 小时",
                    onValueChange = { hours = it },
                )
                Spacer(Modifier.height(12.dp))
                MiuixNavigationItemWidget(
                    title = "保存",
                    description = "统计只含真实请求（连通性测试不计入）",
                    onClick = {
                        onConfirm(
                            policy.copy(
                                tokenBudgetPerAccount = tokensK * 1000L,
                                budgetWindowHours = hours,
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** 回放条目里的时刻（本地时区，秒级就够）。 */
private fun replayTimeText(atMillis: Long): String =
    java.time.Instant.ofEpochMilli(atMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))

/**
 * 添加路由组：对外名 + 成员（每行「家/模型」）。
 *
 * 成员用「家/模型」这种紧凑写法而不是两个输入框一排：
 * 一个组通常三五个成员，一行一个更好读也更好改（粘贴一串就行）。
 * 校验交给 core 的 `GatewayRouteGroupRules`（与存储侧同一套）。
 */
@Composable
private fun AddRouteGroupDialog(
    existing: List<dev.praxis.core.gateway.GatewayRouteGroup>,
    onDismiss: () -> Unit,
    onConfirm: (dev.praxis.core.gateway.GatewayRouteGroup) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var members by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    MiuixAnimatedDialog(visible = true, onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "添加路由组",
                    style = MiuixTheme.textStyles.body1,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = "模型页填「对外名」就会按成员顺序试；成员写成 家/模型，" +
                        "例如 openai/gpt-4o-mini。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    label = "对外名（如 fast）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = members,
                    onValueChange = { members = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    label = "成员（每行一个，家/模型）",
                    useLabelAsPlaceholder = true,
                    minLines = 3,
                )
                error?.let { text ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                MiuixNavigationItemWidget(
                    title = "保存",
                    description = "同一个对外名不能再建一次",
                    onClick = {
                        val parsed = members.split("\n")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .map { line ->
                                val slash = line.indexOf("/")
                                if (slash <= 0) {
                                    dev.praxis.core.gateway.RouteGroupMember("", "")
                                } else {
                                    dev.praxis.core.gateway.RouteGroupMember(
                                        provider = line.substring(0, slash).trim(),
                                        model = line.substring(slash + 1).trim(),
                                    )
                                }
                            }
                        val draft = dev.praxis.core.gateway.GatewayRouteGroup(
                            name = name,
                            members = parsed,
                        )
                        val invalid = dev.praxis.core.gateway.GatewayRouteGroupRules
                            .validate(draft, existing)
                        if (invalid != null) {
                            error = invalid.userMessage()
                        } else {
                            onConfirm(dev.praxis.core.gateway.GatewayRouteGroupRules.normalize(draft))
                        }
                    },
                )
            }
        }
    }
}
