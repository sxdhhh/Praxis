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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
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
import top.yukonga.miuix.kmp.basic.Slider
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Memory
import top.yukonga.miuix.kmp.basic.Switch

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
    /**
     * 模型池（`ModelSettings.modelList`）。
     *
     * 空列表时「模型」分区里只留「管理模型」入口，不画一个空列表 ——
     * 与全仓「不假设按钮」的纪律一致。
     */
    models: List<String> = emptyList(),
    /** 当前选中的下标（`ModelSettings.modelIndex`）。 */
    activeModelIndex: Int = 0,
    /** 就地切换模型；null = 不提供（预览/单测）。 */
    onSelectModel: ((Int) -> Unit)? = null,
    /** 进模型设置页。「管理模型」那一行用它。 */
    onManageModels: (() -> Unit)? = null,
    toolTier: PermissionTier? = null,
    onSetToolTier: ((PermissionTier) -> Unit)? = null,
    onOpenToolSettings: (() -> Unit)? = null,
    /** 是否自动朗读（null = 设备无 TTS，整行不显示）。 */
    autoReadReplies: Boolean? = null,
    onToggleAutoReadReplies: ((Boolean) -> Unit)? = null,
    disableStreamOutput: Boolean = false,
    onToggleDisableStreamOutput: ((Boolean) -> Unit)? = null,
    /**
     * 是否自动保存记忆（对应 Operit 记忆组的 `enableMemoryAutoUpdate`）。
     *
     * null = 上层没接这条能力。与其它可选参数同一纪律：宁可少一行，
     * 也不显示一个点了没反应的开关。
     */
    autoMemoryEnabled: Boolean = true,
    onToggleAutoMemory: ((Boolean) -> Unit)? = null,
    /**
     * 已加载的技能名（插件组数据源，对应 Operit `pluginInputMenuToggles`）。
     *
     * 空列表时整个「插件」分区不显示：没有技能却给一个空分区，
     * 用户会以为功能坏了。
     */
    skillNames: List<String> = emptyList(),
    /** 被关掉的技能名集合。 */
    disabledSkills: Set<String> = emptySet(),
    onToggleSkill: ((String, Boolean) -> Unit)? = null,
    /**
     * 全部工具名（工具组里逐个开关的数据源）。
     *
     * 对应 Operit 工具组的 `toolSlotToggles`。Praxis 的 `disabledTools`
     * 与权限档位是独立维度（见 `ToolPermissionState` KDoc）：档位是
     * 「能力上限」，这里的开关是「用户就是不想让它跑」。
     */
    toolNames: List<String> = emptyList(),
    /** 被禁用的工具名集合。 */
    disabledTools: Set<String> = emptySet(),
    onToggleTool: ((String, Boolean) -> Unit)? = null,
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
    // ## 默认全部折叠（2026-10-29，对齐 Operit）
    //
    // 旧值模型分区是 **true**：一打开面板就是「模型分区全展开」。
    // 而它内部有模型池 + 管理入口 + 思考强度，展开后直接顶满卡片，
    // 用户看到的第一眼是一堆行，这正是“乱”的直接来源。
    //
    // Operit 五个分区全部默认折叠（ClassicChatSettingsBar.kt:140-144 全是
    // mutableStateOf(false)），折叠时每行右侧显示当前值 ——
    // 「折叠隐藏的是选项，不是信息」。
    var showModelSection by remember { mutableStateOf(false) }
    var showToolsSection by remember { mutableStateOf(false) }
    var showBehaviorSection by remember { mutableStateOf(false) }
    // 思考强度四档的二级展开（对应上游 `ThinkingSettingsItem` 的 expanded）。
    var showThinkingList by remember { mutableStateOf(false) }

    /** 模型池子列表是否展开（二级折叠，见模型分区的注释）。 */
    var showModelList by remember { mutableStateOf(false) }
    var showMemorySection by remember { mutableStateOf(false) }
    var showPluginsSection by remember { mutableStateOf(false) }

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
                alignment = Alignment.TopStart,
                onDismissRequest = { showMenu = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    // ## dismissOnClickOutside 必须是 false
                    //
                    // 改成「满屏 Box 自己接点击」之后，Popup 的 outside 区域
                    // 已经不存在了（内容铺满整屏），这个开关只会让
                    // 「点卡片内部」也被判成 outside 而误关。
                    dismissOnClickOutside = false,
                ),
            ) {
                // ── 为什么这里必须是「满屏 Box」而不是直接放 Card ──────
                //
                // ## 这是「展开收起时一抖一抖」的真因
                //
                // 旧写法把 Card 直接作为 Popup 的内容。于是 **Popup 窗口的
                // 尺寸 = 卡片尺寸**，而卡片里有 `FoldableContent`
                // （expandVertically）—— 展开动画的 400ms 里，卡片高度
                // 每帧都在变，Popup 窗口就每帧重新测量、重新定位。
                //
                // Popup 是独立 Window，重新定位不是单纯的重绘：它每帧都在
                // 申请新的窗口区域。观感就是面板一边长高一边抖，且因为要等
                // 窗口 relayout，动画显得很慢。
                //
                // 上游（AgentChatInputSection.kt:2299-2315）用的是完全不同的
                // 结构：Popup 内容是一个 `fillMaxSize` 的 Box（**尺寸恒定**），
                // 卡片在 Box 里 `contentAlignment = BottomEnd` 定位。
                // 这样折叠内容怎么变，Popup 窗口始终是整屏，不需要重新定位。
                //
                // 这里照搬该结构。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { showMenu = false },
                        ),
                    // 向上弹出：卡片贴底（BottomEnd），不是贴顶。
                    contentAlignment = Alignment.BottomEnd,
                ) {
                // 280dp 宽、最多 420dp 高、超出可滚 —— 与上游 300dp 同量级。
                //
                // 高度上限必须给：分区全展开时内容远超一屏。
                // `padding(bottom = 96.dp)` 把卡片抬到输入栏之上 ——
                // 这是「往上出现」的落点，与上游 44dp + 自身高度同思路，
                // Praxis 输入栏更高（两行），所以取 96dp。
                Card(
                    modifier = Modifier
                        .padding(bottom = 96.dp, end = 8.dp)
                        .width(280.dp)
                        .heightIn(max = 420.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                    ) {
                        // ── 记忆 ────────────────────────────────────────
                        //
                        // 对应 Operit `AgentMemorySelectorItem` +
                        // 记忆 slot 的 toggles（AgentChatInputSection.kt:2335）。
                        //
                        // ## 与 Operit 的差异（如实说明，不是漏做）
                        //
                        // Operit 的记忆组有两个东西：**记忆空间选择器**（下拉
                        // 切换 MemorySpace）和**自动更新开关**。
                        //
                        // Praxis 的 `MemoryPort` 是单库（`recall`/`remember`），
                        // 没有「多空间」概念，所以这里只有自动更新开关。
                        // 等底层支持多空间时，这一组加一个下拉即可。
                        if (onToggleAutoMemory != null) {
                            FoldSection(
                                title = "记忆",
                                value = if (autoMemoryEnabled) "自动保存" else "已关闭",
                                icon = Icons.Outlined.Memory,
                                expanded = showMemorySection,
                                showTopDivider = false,
                                onExpandedChange = { showMemorySection = it },
                            ) {
                                SwitchRow(
                                    title = "自动保存记忆",
                                    detail = if (autoMemoryEnabled) {
                                        "对话结束后自动提取值得记的内容"
                                    } else {
                                        "已关闭：只有手动保存才写入"
                                    },
                                    checked = autoMemoryEnabled,
                                    onCheckedChange = { onToggleAutoMemory(it) },
                                )
                            }
                        }


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
                                // ## 模型池改为二级折叠（2026-10-29，对齐 Operit）
                                //
                                // 旧写法把模型池里的**每一个模型**都铺成一行
                                // `ChoiceRow`。用户配了 20 个模型时，这个分区展开后
                                // 直接占掉大半屏 —— 截图里那种“乱”，主要就是它。
                                //
                                // Operit 的做法是两级：先一行「模型：当前名」的按钮，
                                // 点开才在**下方子区域**列出可选模型。它比这里还多一级
                                // （配置 → 模型），因为 Operit 支持多服务商；Praxis 只有
                                // 一个 `baseUrl`（见 `ModelSettings`），所以只保留
                                // 「当前选中」与「可选列表」这一级。
                                //
                                // 为什么不是弹窗：这是**在面板里选**，选完还要接着配
                                // 思考强度。弹窗会盖住分区、还得先关掉，多两步操作。
                                if (models.isNotEmpty() && onSelectModel != null) {
                                    ActionRow(
                                        icon = Icons.Outlined.DataObject,
                                        title = "选择模型",
                                        // 当前模型名已显示在**分区头**的 value 里
                                        // （`value = modelName`）。这里再写一遍就是
                                        // 同一屏出现两次同一个名字，纯重复。
                                        // 写数量既提示“还有别的可选”，也不重复信息。
                                        detail = "共 " + models.size + " 个可用",
                                        trailing = if (showModelList) "收起" else "展开",
                                        onClick = { showModelList = !showModelList },
                                    )
                                    FoldableContent(visible = showModelList) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                // 子区域背景再浅一档：与「展开的思考强度」
                                                // 用同一个层级语言，用户能看出
                                                // “这些是上一行的选项”。
                                                .background(
                                                    MiuixTheme.colorScheme
                                                        .surfaceVariant.copy(alpha = 0.3f),
                                                )
                                                .padding(start = 8.dp, end = 8.dp),
                                        ) {
                                            models.forEachIndexed { index, name ->
                                                ChoiceRow(
                                                    label = name,
                                                    selected = index == activeModelIndex,
                                                    onClick = { onSelectModel(index) },
                                                )
                                            }
                                        }
                                    }
                                }
                                if (onManageModels != null || onOpenModelSettings != null) {
                                    ActionRow(
                                        // 与上面「选择模型」区分：那个是 DataObject（数据对象），
                                        // 这个用 Settings（管理入口）。两行同图标会让人以为
                                        // 是同一个功能的两个入口。
                                        icon = Icons.Outlined.Settings,
                                        title = if (models.isEmpty()) {
                                            "去配置模型 / 服务商"
                                        } else {
                                            "管理模型 / 服务商"
                                        },
                                        detail = if (models.isEmpty()) {
                                            "还没有可用模型"
                                        } else {
                                            "改接口地址、密钥与模型池"
                                        },
                                        onClick = {
                                            showMenu = false
                                            (onManageModels ?: onOpenModelSettings)?.invoke()
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
                                    // ## 为什么是滑动条而不是四个选项（2026-10-25）
                                    //
                                    // 思考强度是**有顺序的量**（None < Low < Medium < High），
                                    // 不是并列的四选一。这类量用滑动条表达更准确：
                                    // 用户能一眼看出「还能往上调」，而不是面对四个
                                    // 同等权重的文字行。
                                    //
                                    // ## 动画
                                    //
                                    // 两层动画叠加：
                                    //  1. `FoldableContent` 管**展开**（高度 + 淡入）；
                                    //  2. miuix `Slider` 自己管**取值过渡**（拇指与
                                    //     轨道用动画跟随目标值，不是瞬间跳）。
                                    //
                                    // 展开必须常驻调用、只翻 `visible`，
                                    // 包 `if` 会让收起动画来不及播。
                                    FoldableContent(visible = showThinkingList) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 6.dp),
                                        ) {
                                            val efforts = ThinkingEffort.entries
                                            val index = efforts.indexOf(thinkingEffort)
                                                .coerceAtLeast(0)
                                            Slider(
                                                value = index.toFloat(),
                                                onValueChange = { v ->
                                                    // 就地吸附到最近的档位：
                                                    // `steps = 2` 已经给出 4 个停靠点，
                                                    // 这里 round 是防浮点抖动。
                                                    val i = v.toInt()
                                                        .coerceIn(0, efforts.lastIndex)
                                                    if (efforts[i] != thinkingEffort) {
                                                        onPickThinkingEffort(efforts[i])
                                                    }
                                                },
                                                valueRange = 0f..efforts.lastIndex.toFloat(),
                                                // 4 个位置 → 中间停靠点 2 个。
                                                steps = (efforts.size - 2).coerceAtLeast(0),
                                                showKeyPoints = true,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            // 档位文字：左端与右端是端点，中间两档均分。
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                efforts.forEach { effort ->
                                                    Text(
                                                        text = effort.chipLabel(),
                                                        fontSize = 11.sp,
                                                        color = if (effort == thinkingEffort) {
                                                            MiuixTheme.colorScheme.primary
                                                        } else {
                                                            MiuixTheme.colorScheme
                                                                .onSurfaceVariantSummary
                                                        },
                                                    )
                                                }
                                            }
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
                                // ## 为什么是就地开关而不是跳转（2026-10-25）
                                //
                                // 旧写法是一行 ActionRow「管理单个工具」，点了跳设置页。
                                // 但用户要的是「就在底栏里关掉某个工具」，跳页多一层。
                                // Operit 的 `AgentToolsPermissionGroupItem` 也是就地列出
                                // slot toggles（AgentChatInputSection.kt:2397）。
                                if (toolNames.isNotEmpty() && onToggleTool != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                            .height(1.dp)
                                            .background(
                                                MiuixTheme.colorScheme.outline.copy(alpha = 0.2f),
                                            ),
                                    )
                                    // ## 紧凑档 + 空 detail（2026-10-29）
                                    //
                                    // 原来每行写「可用 / 已禁用」。但右侧的 Switch
                                    // **本身就在表达这个状态** —— 再用一行文字重复一遍，
                                    // 等于每个工具多占 15dp 的高度去说一件开关已经说过的事。
                                    //
                                    // 几十个工具排下来，这行重复文字就是“乱”的主体。
                                    // Operit 的工具组也不写状态文字（`SettingItem` 只画
                                    // 图标 + 标题 + Switch）。
                                    toolNames.forEach { name ->
                                        val enabled = name !in disabledTools
                                        SwitchRow(
                                            title = name,
                                            detail = "",
                                            checked = enabled,
                                            onCheckedChange = { onToggleTool(name, it) },
                                            compact = true,
                                        )
                                    }
                                }
                                // 管理页保留：它能做这里做不到的事（看工具描述、参数表）。
                                if (onOpenToolSettings != null) {
                                    ActionRow(
                                        icon = Icons.Outlined.Security,
                                        title = "工具详情",
                                        detail = "看描述与参数，或管理全部工具",
                                        onClick = {
                                            showMenu = false
                                            onOpenToolSettings()
                                        },
                                    )
                                }
                            }
                        }

                        // ── 行为 ────────────────────────────────────────
                        if (onToggleDisableStreamOutput != null || onToggleAutoReadReplies != null) {
                            FoldSection(
                                title = "行为",
                                // 与上游 `behaviorSummary` 同口径：摘要直接写「现在是哪种」，
                                // 不写「点击切换」那种无信息量的说明。
                                value = listOfNotNull(
                                    if (disableStreamOutput) "非流式" else "流式",
                                    if (autoReadReplies == true) "朗读" else null,
                                ).joinToString(" · "),
                                icon = Icons.Outlined.Speed,
                                expanded = showBehaviorSection,
                                onExpandedChange = { showBehaviorSection = it },
                            ) {
                                // ## 为什么要再包一层 null 检查
                                //
                                // 外层条件已经是「任一非空」（||），Kotlin 无法从
                                // `a != null || b != null` 推出 `a != null`，所以启用
                                // smart cast 会失败，`onToggleDisableStreamOutput(...)` 报
                                // 「Smart cast to 'Function1<Boolean, Unit>' is impossible」。
                                if (onToggleDisableStreamOutput != null) {
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

                                // ── 自动朗读 ──
                                //
                                // 用 SwitchRow 而不是 ActionRow：这是纯状态（开/关），
                                // 没有「点了会跳转」的语义。
                                if (autoReadReplies != null && onToggleAutoReadReplies != null) {
                                    SwitchRow(
                                        title = "自动朗读回复",
                                        detail = if (autoReadReplies) {
                                            "回复生成完后念出来"
                                        } else {
                                            "已关闭"
                                        },
                                        checked = autoReadReplies,
                                        onCheckedChange = { onToggleAutoReadReplies(it) },
                                    )
                                }
                            }
                        }

                        // ── 插件 ────────────────────────────────────────
                        //
                        // 对应 Operit `AgentPluginSettingsGroupItem`
                        // （AgentChatInputSection.kt:2431）。
                        //
                        // ## 数据源
                        //
                        // Operit 的插件槽位来自 `InputMenuTogglePluginRegistry`
                        // （第三方能往里注册开关）。Praxis 没有那套插件注册表，
                        // 但**有** `SkillRegistry` —— 技能就是 Praxis 的插件。
                        // 所以这里列出已加载的技能，逐个开关。
                        //
                        // ## 为什么关掉是「记黑名单」
                        //
                        // 见 `SettingsRepository.disabledSkills` 的注释：
                        // 技能是扫盘出来的，存「开着的」会让新技能默认关闭，
                        // 用户会以为没生效。存「关掉的」，新技能默认开启。
                        if (skillNames.isNotEmpty() && onToggleSkill != null) {
                            FoldSection(
                                title = "插件",
                                value = "${skillNames.size - disabledSkills.count { it in skillNames }}"
                                    + "/${skillNames.size}",
                                icon = Icons.Outlined.Extension,
                                expanded = showPluginsSection,
                                onExpandedChange = { showPluginsSection = it },
                            ) {
                                // 与工具组同理：技能名本身就说明了一切，
                                // 「启用中 / 已禁用」与右侧 Switch 重复（2026-10-29）。
                                skillNames.forEach { name ->
                                    val enabled = name !in disabledSkills
                                    SwitchRow(
                                        title = name,
                                        detail = "",
                                        checked = enabled,
                                        onCheckedChange = { onToggleSkill(name, it) },
                                        compact = true,
                                    )
                                }
                            }
                        }

                    }
                }
            }
        }
    }
}
}


/**
 * 模型胶囊 —— 对齐 Operit `AgentChatInputSection.kt:891-931` 的 Surface。
 *
 * ## 为什么放在本文件而不是 ChatScreen.kt
 *
 * 它需要 `Icons` / `Popup` / `PopupProperties` / `TextOverflow` 这些符号，
 * 而本文件**已经**全部 import（`ChatSettingsBar` 自己就要用）。
 * 放进 `ChatScreen.kt` 得再补 8 个 import，且那里用的是 miuix 的 `Icon`
 * 而非 Material 的 —— 两套图标体系混在一个文件里最容易出错。
 *
 * ## 它弹的是**自己的**面板（这是本轮修的错）
 *
 * Operit 底栏三个按钮弹三个不同面板：
 *
 * | 按钮 | 弹出 | 上游行号 |
 * |---|---|---|
 * | 模型胶囊 | `AgentModelSelectorPopup`（选模型 / 思考 / MaxContext） | 1392 |
 * | Tune | `AgentExtraSettingsPopup`（记忆 / 工具 / 行为 / 插件） | 1417 |
 * | 加号 | `AttachmentSelectorPanel`（9 类附件） | 750 |
 *
 * 三者互斥：`onModelSelectorClick` 先关 ExtraSettings（第 569 行），
 * Tune 的 onClick 也先关模型面板（第 941 行）。展开中的那个 tint 变 primary。
 *
 * ## 面板结构（照上游 1550-1560 行）
 *
 * `Popup(TopStart)` + 内部 `Box(fillMaxSize).clickable(onDismiss)` +
 * `Card` 贴右下角。**卡片尺寸恒定、只改透明度** —— 若对卡片做 scale，
 * Popup 每帧重新测量并重新定位，观感就是持续抖动。
 */
@Composable
fun ModelPill(
    modelName: String,
    models: List<String>,
    activeIndex: Int,
    onClickModel: (Int) -> Unit,
    onManageModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    // 展开时文字变 primary（上游 950-955 行同一判据）。
    val pillColor = if (expanded) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurface
    }

    Box(modifier = modifier) {
        // 胶囊本体：12dp 圆角 + 1dp 描边（上游 widthIn max 220dp）。
        Row(
            modifier = Modifier
                .padding(end = 2.dp)
                .widthIn(max = 220.dp)
                .height(34.dp)
                .border(
                    width = 1.dp,
                    color = MiuixTheme.colorScheme.outline.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = modelName.ifBlank { "未配置模型" },
                style = MiuixTheme.textStyles.footnote1,
                color = pillColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (expanded) {
                    Icons.Default.KeyboardArrowUp
                } else {
                    Icons.Default.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(18.dp),
            )
        }

        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    // 上游同样 false：靠外层 Box 的 clickable 关闭，
                    // 这样点卡片内部不会误关。
                    dismissOnClickOutside = false,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { expanded = false },
                        ),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Card(
                        modifier = Modifier
                            // ## 三块浮层共用同一套尺寸（2026-10-29 修）
                            //
                            // 旧值 `bottom = 44.dp / end = 12.dp / width = 260.dp /
                            // max = 380.dp` 与功能弹窗（96 / 8 / 280 / 420）不一致，
                            // 于是点开模型选择时卡片明显**比功能面板低一截、窄一圈**，
                            // 底边不在同一条线上。
                            //
                            // 三块浮层现在共用同一组值，切换时卡片位置不动，
                            // 只有内容变 —— 这才是“同一块板的三种内容”。
                            .padding(bottom = 96.dp, end = 8.dp)
                            .width(280.dp)
                            .heightIn(max = 420.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                        ) {
                            if (models.isEmpty()) {
                                Text(
                                    text = "还没有模型，先去设置里填一个",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(
                                        horizontal = 12.dp,
                                        vertical = 6.dp,
                                    ),
                                )
                            } else {
                                models.forEachIndexed { index, name ->
                                    ChoiceRow(
                                        label = name,
                                        selected = index == activeIndex,
                                        onClick = {
                                            onClickModel(index)
                                            expanded = false
                                        },
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .height(1.dp)
                                    .background(
                                        MiuixTheme.colorScheme.outline.copy(alpha = 0.2f),
                                    ),
                            )
                            ActionRow(
                                icon = Icons.Outlined.DataObject,
                                title = "管理模型 / 服务商",
                                detail = "改接口地址、密钥与模型池",
                                onClick = {
                                    expanded = false
                                    onManageModels()
                                },
                            )
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
        // ## 展开/收起：保留动画，但**从底边向上展开**（2026-10-25）
        //
        // 用户要求「瞬时动画太屎了还是用我们的好」，所以保留现在的过渡，
        // 只修抖动与速度。
        //
        // ## 抖动的真因：展开方向与卡片对齐方向相反
        //
        // 卡片是 `BottomEnd` 定位的 —— 底边固定、高度向上增长。
        // 而上一版 `FoldableContent` 用 `expandFrom = Top`：内容按顶边向下长。
        // 展开的那 200ms 里两个运动同时进行且方向相反：
        //
        //   卡片：底边不动，顶边持续上移（整体向上长）
        //   内容：顶边不动，底边持续下移（相对卡片向下长）
        //
        // 叠加结果就是内容在展开期间不断上下弹 —— 用户看到的「一抖一抖」。
        //
        // 改成 `expandFromBottom = true` 后两者同向（都向上长），抖动消失。
        // 时长也一并缩短（见 `PraxisMotion.Expansion` 的注释：400/300 → 220/150）。
        FoldableContent(
            visible = expanded,
            // 贴底面板：从底边向上展开。
            expandFromBottom = true,
            modifier = Modifier
                .fillMaxWidth()
                // 展开区底色浅一档（上游是 surface.copy(alpha = 0.5f)）：
                // 让「哪些行属于这个分区」在视觉上成立，否则分区之间只靠一条细线。
                .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        ) {
            content()
        }
}
}

/**
 * 一行开关：右侧是 Switch，不是状态文字。
 *
 * ## 与 [ActionRow] 的区别
 *
 * `ActionRow` 是「点了会去做某事」——它是**动作**。
 * `SwitchRow` 是「这一项现在是开还是关」——它是**状态**。
 * 混用会让用户点开关时以为要跳页。
 *
 * ## 为什么整行可点
 *
 * 只让 Switch 自身可点，手指要精确落在右侧那一小块。材料设计中
 * 行式开关的标准做法是整行可切换，命中区更大。
 */
@Composable
private fun SwitchRow(
    title: String,
    /** 副说明。传空串则不占第二行（见 [compact]）。 */
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /**
     * 紧凑档：单行 36dp，不画副说明。
     *
     * ## 为什么需要它（2026-10-29，对齐 Operit）
     *
     * Operit 的 `SettingItem` 是 `heightIn(min = 36.dp)` + **单行标题**
     * （`maxLines = 2` 只在标题过长时换行，不会有一行固定的副标题）。
     *
     * 而 Praxis 旧版统一 44dp + 两行（标题 + 副说明）。在「工具」分区里
     * 几十个工具名一个一个排下去时，每行多出的 8dp 与一行副文字会把它撑成
     * 一大片，扫起来非常累 —— 这就是用户说的“内容太乱”。
     *
     * 开关类是**状态**，副说明只在真的需要解释代价时才加（比如
     * 「关闭流式输出」）。工具/技能这类“名字即含义”的项用紧凑档。
     */
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 36.dp else 44.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 紧凑档不画副说明：那一行在「几十个工具名」的场景里是纯噪音。
            if (!compact && detail.isNotBlank()) {
                Text(
                    text = detail,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // onCheckedChange = null 表示「由父容器接管点击」，
        // 避免一次点击触发两遍。
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
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