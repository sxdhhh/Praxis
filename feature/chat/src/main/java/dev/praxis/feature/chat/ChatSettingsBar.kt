package dev.praxis.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.praxis.core.model.settings.ThinkingEffort
import dev.praxis.core.tool.PermissionTier
import dev.praxis.ui.widgets.FoldableContent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 输入区右下角的设置入口 —— 1:1 对齐 Operit `ClassicChatSettingsBar`。
 *
 * ## 上一版为什么被判「根本没做」（2026-10-24 重写）
 *
 * 上一版是一条横向 chip 行：`[模型名] [思考强度] ... [Tune]`，只有两项。
 * 用户原话：「注意到底部的模型选择等等我要求做的对齐 operit 底栏的功能
 * 你根本就没做」。这个判断是对的 —— **形态就不对**，不是少了几个按钮。
 *
 * Operit 的真实结构（`ClassicChatSettingsBar.kt`，1809 行）是：
 *
 * ```
 * Box(padding(end = 右边距)) {
 *     IconButton(Tune, 28.dp, align BottomEnd)        ← 底栏上唯一的可见控件
 *     if (showMenu) Popup(TopEnd, 280.dp 宽卡片) {
 *         Column(verticalScroll) {
 *             FoldSection("记忆",   value = 当前记忆空间) { ... }
 *             FoldSection("模型",   value = 当前模型名)   { 模型选择 + 思考设置 }
 *             FoldSection("工具",   value = 权限档位)     { 权限档位 + 换档 }
 *             FoldSection("行为",   value = 行为摘要)     { 流式开关 + 自动朗读 }
 *             FoldSection("插件",   value = "n/m")        { 各 toggle }
 *         }
 *     }
 * }
 * ```
 *
 * | | Operit | 上一版 Praxis |
 * |---|---|---|
 * | 底栏可见内容 | 只有一个 28dp 的 Tune | 一排 chip + Tune |
 * | 展开形态 | 280dp 卡片、分区可折叠、右侧带当前值 | 无（两个独立小下拉） |
 * | 分组语义 | 记忆 / 模型 / 工具 / 行为 / 插件 | 无 |
 * | 常驻信息量 | 折叠时也看得到当前模型/档位 | 只有 chip 上那两项 |
 *
 * 所以这次照结构重做，不是在旧 chip 行上加按钮。
 *
 * ## 取交集：Praxis 没有的能力不画空分区
 *
 * | 上游分区 | Praxis 现状 | 本轮 |
 * |---|---|---|
 * | 记忆 | `MemoryPort` 只是端口，没有用户可见的记忆空间 | 不做 |
 * | 模型 | 有 `ModelSettings.model` | 做（模型 + 思考强度） |
 * | 工具 | 有 `ToolPermissionState`（三档运行时可改） | 做（档位切换） |
 * | 行为 | 本轮新增流式开关的存储位 | 做（流式输出） |
 * | 插件 | Praxis 无插件系统 | 不做 |
 *
 * 「不做」的三个不是忘记：没有底层能力就不画入口。画一个点开是空的
 * 折叠区，比没有更糟 —— 那是本项目反复否掉的「假按钮」。
 *
 * ## 不假设按钮
 *
 * 每个分区靠「回调是否为 null」决定渲染与否，与 `PendingQueuePanel`
 * 同一纪律。`@Preview` / Storybook 不传任何回调时整个 Tune 都不出现，
 * 不会留一个按了没反应的齿轮。
 *
 * @param modelName 当前模型名（空串时显示「未配置」）。
 * @param thinkingEffort 当前思考强度。
 * @param onPickThinkingEffort 切换思考强度；null 表示不提供该入口。
 * @param onOpenModelSettings 进模型设置页；null 表示不提供。
 * @param toolTier 工具权限档位；null 表示不提供工具分区。
 * @param onSetToolTier 切换工具档位。
 * @param onOpenToolSettings 进工具管理页（看禁用列表）。
 * @param disableStreamOutput 是否关闭流式输出。
 * @param onToggleDisableStreamOutput 切换流式输出。
 */
@Composable
internal fun ChatSettingsBar(
    modelName: String,
    thinkingEffort: ThinkingEffort,
    onPickThinkingEffort: ((ThinkingEffort) -> Unit)?,
    onOpenModelSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    toolTier: PermissionTier? = null,
    onSetToolTier: ((PermissionTier) -> Unit)? = null,
    onOpenToolSettings: (() -> Unit)? = null,
    disableStreamOutput: Boolean = false,
    onToggleDisableStreamOutput: ((Boolean) -> Unit)? = null,
) {
    // 一个能力都没有就整个入口不渲染。
    // 判据用「有没有任何可用能力」而不是「有没有模型回调」——
    // 将来只接工具分区时这一条仍然正确。
    val hasAnySection = onOpenModelSettings != null ||
        onPickThinkingEffort != null ||
        (toolTier != null && onSetToolTier != null) ||
        onToggleDisableStreamOutput != null
    if (!hasAnySection) return

    var showMenu by remember { mutableStateOf(false) }

    // 三个分区的展开状态各自独立。
    //
    // Operit 同样是三个独立 boolean（`showMemorySection` / `showModelSection` /
    // `showToolsSection` …），不是「同时只开一个」的手风琴：
    // 用户常要同时对照「模型」与「思考强度」，互斥会逼他反复点开点关。
    var showModelSection by remember { mutableStateOf(true) }
    var showToolsSection by remember { mutableStateOf(false) }
    var showBehaviorSection by remember { mutableStateOf(false) }
    // 思考强度四档的二级展开（对应上游 `ThinkingSettingsItem` 的 expanded）。
    var showThinkingList by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { showMenu = !showMenu },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = "对话设置",
                // 上游用 onSurfaceVariant 而非 primary：这是次要入口，
                // 用 primary 会和发送键抢注意力。
                //
                // 注意：miuix 的 `Colors` 没有 `onSurfaceVariant`，只有
                // `onSurfaceVariantSummary`（编译期实测 Unresolved reference）。
                // 语义上它就是 miuix 的「弱一档前景」，等价于上游的意图。
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(22.dp),
            )
        }

        if (showMenu) {
            Popup(
                alignment = Alignment.TopEnd,
                onDismissRequest = { showMenu = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                // 280dp 宽、最多 420dp 高、超出可滚 —— 前两个值与上游同源。
                //
                // 高度上限必须给：分区全展开时内容远超一屏，不设上限
                // 会让 Popup 顶到状态栏之外，最后几个开关永远点不到。
                Card(
                    modifier = Modifier
                        .width(280.dp)
                        .heightIn(max = 420.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                    ) {
                        // ── 模型 ────────────────────────────────────────
                        if (onOpenModelSettings != null || onPickThinkingEffort != null) {
                            FoldSection(
                                title = "模型",
                                value = modelName.ifBlank { "未配置" },
                                icon = Icons.Outlined.DataObject,
                                expanded = showModelSection,
                                showTopDivider = false,
                                onExpandedChange = { showModelSection = it },
                            ) {
                                if (onOpenModelSettings != null) {
                                    ActionRow(
                                        icon = Icons.Outlined.DataObject,
                                        title = "选择模型 / 服务商",
                                        detail = modelName.ifBlank { "尚未配置模型" },
                                        onClick = {
                                            showMenu = false
                                            onOpenModelSettings()
                                        },
                                    )
                                }
                                if (onPickThinkingEffort != null) {
                                    ActionRow(
                                        icon = Icons.Outlined.Psychology,
                                        title = "思考强度",
                                        detail = thinkingEffort.chipLabel(),
                                        trailing = if (showThinkingList) "收起" else "展开",
                                        onClick = { showThinkingList = !showThinkingList },
                                    )
                                    if (showThinkingList) {
                                        ThinkingEffort.entries.forEach { effort ->
                                            ChoiceRow(
                                                label = effort.menuLabel(),
                                                selected = effort == thinkingEffort,
                                                onClick = {
                                                    showThinkingList = false
                                                    onPickThinkingEffort(effort)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // ── 工具权限 ────────────────────────────────────
                        if (toolTier != null && onSetToolTier != null) {
                            FoldSection(
                                title = "工具",
                                value = toolTier.label(),
                                icon = Icons.Outlined.Security,
                                expanded = showToolsSection,
                                onExpandedChange = { showToolsSection = it },
                            ) {
                                // 三档单选。档位是「能力上限」，切换后作用于
                                // 下一批工具调用（见 `ToolPermissionState` 的 KDoc：
                                // 执行中的那一批不会被改，避免同批前后权限不一致）。
                                PermissionTier.entries.forEach { tier ->
                                    ChoiceRow(
                                        label = tier.rowLabel(),
                                        selected = tier == toolTier,
                                        onClick = { onSetToolTier(tier) },
                                    )
                                }
                                if (onOpenToolSettings != null) {
                                    ActionRow(
                                        icon = Icons.Outlined.Security,
                                        title = "管理单个工具",
                                        detail = "禁用不想用的工具",
                                        onClick = {
                                            showMenu = false
                                            onOpenToolSettings()
                                        },
                                    )
                                }
                            }
                        }

                        // ── 行为 ────────────────────────────────────────
                        if (onToggleDisableStreamOutput != null) {
                            FoldSection(
                                title = "行为",
                                // 与上游 `behaviorSummary` 同口径：摘要直接写「现在是哪种」，
                                // 不写「点击切换」那种无信息量的说明。
                                value = if (disableStreamOutput) "非流式" else "流式",
                                icon = Icons.Outlined.Speed,
                                expanded = showBehaviorSection,
                                onExpandedChange = { showBehaviorSection = it },
                            ) {
                                ActionRow(
                                    icon = Icons.Outlined.Speed,
                                    title = "关闭流式输出",
                                    detail = if (disableStreamOutput) {
                                        "已关闭：等整段生成完再显示"
                                    } else {
                                        "开启中：边生成边显示"
                                    },
                                    trailing = if (disableStreamOutput) "已关" else "已开",
                                    trailingEmphasis = disableStreamOutput,
                                    onClick = {
                                        onToggleDisableStreamOutput(!disableStreamOutput)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 可折叠分区 —— 1:1 对齐 Operit `ClassicSettingsFoldSection`。
 *
 * ## 布局顺序照抄
 *
 * ```
 * [分隔线 0.5dp]
 * [icon 16dp] [标题 13sp] [值 weight(1f) 右对齐 primary Bold] [箭头 20dp]
 * ```
 *
 * 「值」放在标题右边、右对齐、primary + Bold：折叠时用户也能一眼看到
 * 当前是什么（`模型  gpt-4o-mini`、`工具  调试`）。这是整个折叠设计的
 * 关键 —— 折叠隐藏的是**选项**，不是信息。
 */
@Composable
private fun FoldSection(
    title: String,
    value: String,
    icon: ImageVector,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showTopDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (showTopDivider) {
            // 0.5dp 而不是 1dp：更接近上游的「细线」，也更像原生分区而非边框。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .height(0.5.dp)
                    .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.18f)),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                color = MiuixTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
        }
        // ## 展开/收起必须有过渡（2026-10-24 修）
        //
        // 旧写法是 `if (expanded) { Column(...) }`：点击后整个分区内容
        // **在一帧内**被塞进布局或抽走，观感是「面板闪了一下」。
        //
        // 换成 `FoldableContent`：高度 `expandVertically` + 透明度 `fadeIn`
        // 同时过渡，时长取 `PraxisMotion.Expansion`（上游 Operit 原值，
        // 退出比进入快）。函数内部用 `AnimatedVisibility`，所以这里
        // **必须常驻调用**、只翻 `visible` —— 包一层 `if` 就又没退场动画了。
        FoldableContent(
            visible = expanded,
            // 展开区底色浅一档（上游是 surface.copy(alpha=0.5f)）：
            // 让「哪些行属于这个分区」在视觉上成立，否则分区之间只靠一条细线。
            //
            // 底色挂在 `FoldableContent` 的 Column 上而不是内层：
            // 这样底色跟着高度一起长出来，不会先出现一整块空白再填内容。
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
                .padding(bottom = 4.dp),
        ) {
            content()
        }
    }
}

/**
 * 一行「动作」：点了会打开别处、或就地切换某事。
 *
 * 与 [ChoiceRow] 的区别是右侧显示**状态文字**而不是选中圆点：
 * 「关闭流式输出」表达的是「有个开关」，不是「四选一」。
 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
    trailing: String? = null,
    trailingEmphasis: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                // 说明用 footnote2 + 弱前景：它不是主信息，但必须存在 ——
                // 「关闭流式」这五个字本身不解释代价（要等很久才见到字）。
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = trailing,
                fontSize = 12.sp,
                color = if (trailingEmphasis) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                maxLines = 1,
            )
        }
    }
}

/**
 * 一行「单选项」：右侧圆点填实表示当前选中。
 *
 * 与权限页的 `PermissionLevelCard` 同一视觉语言（选中 = primary 填实 +
 * 文字 primary），两处的「单选」看起来才是同一个控件。
 *
 * 未选中时画**描边圈**而不是留空：空透明块在浅色卡片底上完全看不见，
 * 用户不会知道那里是个单选项。
 */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (selected) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .size(14.dp)
                .then(
                    if (selected) {
                        Modifier.background(MiuixTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier.border(1.dp, MiuixTheme.colorScheme.outline, CircleShape)
                    },
                ),
        )
    }
}

/** 摘要里显示的短标签（只有一行 13sp，用最短能区分的写法）。 */
private fun ThinkingEffort.chipLabel(): String = when (this) {
    ThinkingEffort.None -> "不思考"
    ThinkingEffort.Low -> "低"
    ThinkingEffort.Medium -> "中"
    ThinkingEffort.High -> "高"
}

/** 菜单里的完整标签（有整行宽度，写清楚代价）。 */
private fun ThinkingEffort.menuLabel(): String = when (this) {
    ThinkingEffort.None -> "不思考（最快、最省）"
    ThinkingEffort.Low -> "低（短任务）"
    ThinkingEffort.Medium -> "中（日常问答）"
    ThinkingEffort.High -> "高（复杂推理，更慢更贵）"
}

/** 分区标题右侧显示的档位名（短）。 */
private fun PermissionTier.label(): String = when (this) {
    PermissionTier.STANDARD -> "基础"
    PermissionTier.DEBUGGER -> "调试"
    PermissionTier.ROOT -> "Root"
}

/** 选项行里的完整说明（带代价，不只写名字）。 */
private fun PermissionTier.rowLabel(): String = when (this) {
    PermissionTier.STANDARD -> "基础（只碰应用自己的数据）"
    PermissionTier.DEBUGGER -> "调试（ADB 级，可看日志）"
    PermissionTier.ROOT -> "Root（系统级，风险最高）"
}