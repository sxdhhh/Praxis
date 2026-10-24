package dev.praxis.feature.chat.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import dev.praxis.feature.chat.BubbleRole
import dev.praxis.feature.chat.ChatBubble
import dev.praxis.feature.chat.ChatCopyContent
import dev.praxis.feature.chat.ChatCopyText
import dev.praxis.feature.chat.MessageAction
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 长按消息弹出的上下文菜单 —— 1:1 移植 Operit `ChatArea.kt:706-1200`。
 *
 * ## 这一处**破例不用 miuix**（用户 2026-10-23 要求）
 *
 * 用户原话：「长按 ai 卡片的弹窗直接使用 operit 的，研读 operit 的实现然后
 * 一比一移植，包括逻辑」。所以版式与逻辑逐项对齐上游：
 *
 * ```
 * Box(气泡) {
 *   ├ 消息内容（onLongClick → 展开菜单）
 *   └ DropdownMenu(width 180dp, surface, 圆角 6dp,
 *                  PopupProperties(focusable, backPress, clickOutside))
 *      └ DropdownMenuItem(高 36dp, 文字 13sp, leadingIcon 16dp)
 * }
 * ```
 *
 * ## 为什么从 miuix `OverlayListPopup` 换掉
 *
 * 旧实现走 miuix 的 `OverlayListPopup`（`ChatContextUi.kt`），锚点取的是
 * **父布局**位置（`ListPopupLayout` 用
 * `Spacer.onGloballyPositioned { parentLayoutCoordinates }`），于是弹窗位置
 * 取决于菜单放在哪一层 —— 上一轮为此把它从页面顶层挪进气泡的 `Box`
 * 才修好「长按没反应」。
 *
 * Material3 的 [DropdownMenu] 走 **Popup**（不是 Dialog Window），由锚定它的
 * 那个布局节点定位，天然就是「贴着被长按的那条」。而且它不新建 Window ——
 * 这是旧 KDoc 担心 `WindowListPopup` 与 NavDisplay entry / 输入法打架的根因，
 * DropdownMenu 没有这个问题。
 *
 * ## 与上游的差异（都写在对应位置）
 *
 * 1. 上游 14 项中有 4 项依赖 Praxis 不存在的底层能力（TTS / 变体 / 分支 /
 *    多选），不给 —— 见 [MessageAction] 的映射表；
 * 2. Praxis 多一个「引用」（上游用「回复」覆盖类似需求，这里两者都留，
 *    语义不同）；
 * 3. 上游「删除」之后还有「回复 / 插入摘要 / 信息 / 多选」，本菜单的
 *    相对顺序照抄，只是少了被砍掉的项。
 *
 * @param bubble 被长按的那条（null 时不渲染菜单项）
 * @param show 是否展开
 * @param onAction 选中动作
 * @param onDismiss 关闭
 */
@Composable
fun MessageContextMenu(
    bubble: ChatBubble?,
    show: Boolean,
    onAction: (MessageAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = show,
        onDismissRequest = onDismiss,
        modifier = modifier
            // 180dp 是上游写死的宽度。不改成自适应：菜单里最长文案
            // （「回滚到此处」）与最短的（「复制」）差 3 个字，自适应会让
            // 同一个菜单在不同消息上宽度不同，看起来像两个东西。
            .width(180.dp)
            .background(
                // 取 miuix 的 surface 而不是 M3 的：见 PraxisTheme 的
                // `rememberM3FromMiuix` —— 现在两者已经同源，但这里直接写
                // miuix 更能表达「这是本项目的主色源」。
                // 用户在 2026-10-23 报「长按弹窗没跟随莫奈」，根因是它读的是
                // 当时恒为蓝的 M3 色板。
                MiuixTheme.colorScheme.surface,
                shape = RoundedCornerShape(6.dp),
            ),
        // 三个参数都有语义，缺一个就是行为退化：
        //  - focusable：能接收按键事件（否则焦点留在输入框，按返回键没反应）；
        //  - dismissOnBackPress：返回键关菜单而不是退出页面；
        //  - dismissOnClickOutside：点空白处关闭。
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val target = bubble ?: return@DropdownMenu
        actionsForBubble(target).forEach { action ->
            MessageMenuItem(
                action = action,
                onClick = {
                    // 先回调再关菜单：回滚/删除这类动作会把这条气泡从列表里
                    // 移除，先执行动作再关，语义上是「对还在的对象做事」。
                    onAction(action)
                    onDismiss()
                },
            )
        }
    }
}

/**
 * 单个菜单项：行高 36dp / 图标 16dp / 文案 13sp（全部与上游一致）。
 *
 * 上游同时传了 `style = bodyMedium` 与 `fontSize = 13.sp`，而 fontSize 会
 * 覆盖 style 的字号。这里只写显式字号，避免读代码时误以为字号由主题决定。
 */
@Composable
private fun MessageMenuItem(
    action: MessageAction,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = action.label,
                fontSize = 13.sp,
            )
        },
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = action.icon,
                contentDescription = action.label,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(16.dp),
            )
        },
        modifier = Modifier.height(36.dp),
    )
}

/** 每个动作的图标（与上游一一对应）。 */
private val MessageAction.icon: ImageVector
    get() = when (this) {
        MessageAction.Copy -> Icons.Default.ContentCopy
        // 上游「回复」用的就是 Reply。
        MessageAction.Reply -> Icons.Default.Reply
        MessageAction.Edit -> Icons.Default.Edit
        // 上游「回滚到此处」用 DeleteSweep —— 语义是「从这里往后扫掉」，
        // 不是单纯删除，所以与 Delete 用不同图标。
        MessageAction.RollbackHere -> Icons.Default.DeleteSweep
        MessageAction.Regenerate -> Icons.Default.Refresh
        // 「继续」用 PlayArrow：它是 core 图标集里的（不依赖 extended），
        // 语义是「接着往下跑」，与 Refresh（推倒重来）形成明确对比 ——
        // 两者挨着放，图标必须一眼能分开。
        MessageAction.Continue -> Icons.Default.PlayArrow
        MessageAction.EditAssistant -> Icons.Default.AutoFixHigh
        MessageAction.InsertSummary -> Icons.Default.Summarize
        MessageAction.ShowInfo -> Icons.Default.Info
        MessageAction.Delete -> Icons.Default.Delete
    }

/**
 * 哪些角色能用哪些动作（1:1 对齐 Operit 的 sender 分支顺序）。
 *
 * ## 顺序逐项对照上游（`ChatArea.kt`）
 *
 * ```
 * 复制 → [朗读] → [插件项] → 编辑并重发 → 回滚到此处
 *      → 重新生成 → 修改记忆 → [删除当前变体] → 删除
 *      → 回复 → 插入摘要 → [创建分支] → 信息 → [多选]
 * ```
 *
 * 方括号是 Praxis 不提供的项（底层能力缺失，见 [MessageAction] 的映射表）。
 * 注意上游把**删除放在「信息」之前**，没有放在最后 —— 这是有意的：
 * 最后一项在手指自然落点上，把破坏性动作放那里更容易误触。
 *
 * ## Praxis 比上游多 Tool / Error 两个角色
 *
 * 它们没有「重新生成」「修改记忆」「回复」的语义（工具卡片不是模型说的话，
 * 错误气泡也没有可改的正文），单独给更短的列表。
 *
 * ## 「引用」插在复制之后
 *
 * 上游没有这一项。放在复制旁边是因为两者都是「把这条内容带走」，
 * 紧邻符合直觉；放到末尾会让用户在「删除」附近找它。
 */
fun actionsForBubble(bubble: ChatBubble): List<MessageAction> = when (bubble.role) {
    BubbleRole.User -> listOf(
        MessageAction.Copy,
        MessageAction.Edit,
        MessageAction.RollbackHere,
        MessageAction.Delete,
        MessageAction.InsertSummary,
        MessageAction.ShowInfo,
    )

    BubbleRole.Assistant -> listOf(
        MessageAction.Copy,
        // 「继续」紧跟「重新生成」：两者都是「让模型再写一段」，用户想找的
        // 就是这一对。放到末尾会让它在「删除」附近，与语义不符。
        MessageAction.Regenerate,
        MessageAction.Continue,
        MessageAction.EditAssistant,
        MessageAction.Delete,
        // 回复**只给助手消息**：与上游 `ChatArea.kt:1076` 的
        // `if (message.sender == "ai")` 同判据。
        MessageAction.Reply,
        MessageAction.InsertSummary,
        MessageAction.ShowInfo,
    )

    BubbleRole.Tool -> listOf(
        MessageAction.Copy,
        // 工具卡片后面常常「没有总结」：模型发起调用、结果回来了就断了。
        // 这里给「继续」让他基于已拿到的结果往下写，而不是重新跑一遍工具。
        MessageAction.Continue,
        MessageAction.ShowInfo,
        MessageAction.Delete,
    )

    BubbleRole.Error -> listOf(
        MessageAction.Copy,
        MessageAction.Delete,
    )

    /**
     * 摘要块：只给「复制 / 删除」。
     *
     * ## 为什么比助手消息少那么多项
     *
     * 摘要是一段**已固化的历史压缩**，不是正在进行的对话：
     *
     * | 项 | 为什么不能给 |
     * |---|---|
     * | 重新生成 | 它不是模型回答，重跑等于再生成一次摘要，与用户意图不符 |
     * | 继续 | 它不是被截断的回复，接不下去 |
     * | 回复 | 回复一段摘要毫无意义 |
     * | 修改记忆（EditAssistant） | 改摘要正文会破坏它与「被压缩那段」的对应关系 |
     * | 插入摘要 | 对摘要再摘要，语义循环 |
     *
     * 留给「复制」（想把这段拿走）与「删除」（不想要这段压缩了）——
     * 这与 Operit 对 `sender == "summary"` 的处理同口径。
     */
    BubbleRole.Summary -> listOf(
        MessageAction.Copy,
        MessageAction.Delete,
    )
}

/** 复制预览的三档（对齐上游 `MessageCopyMode`）。 */
enum class MessageCopyMode(val label: String) {
    /** 洗白 + 去掉 Markdown 记号（粘到聊天软件 / 备忘录用这档）。 */
    PlainText("纯文本"),

    /** 洗白但保留 Markdown 记号。 */
    MarkdownSource("Markdown"),

    /** 只剥供应商元数据，保留全部结构化标签（贴回给模型用）。 */
    XmlSource("XML 原文"),
}

/**
 * 复制预览底部表（1:1 移植上游 `MessageCopyPreviewBottomSheet`）。
 *
 * ## 为什么复制要多这一步
 *
 * 一条回复里同时存在三种「用户可能想要的东西」：
 *
 * | 档位 | 内容 | 使用场景 |
 * |---|---|---|
 * | 纯文本 | 去掉 `##` / `**` / `-` 的正文 | 粘到聊天软件、备忘录 |
 * | Markdown | 保留记号、去掉内部标签 | 粘回 Markdown 编辑器、issue |
 * | XML 原文 | 只去 base64 元数据 | 把带标签的原始格式贴回给模型 |
 *
 * 只给一档就等于替用户决定了要哪种。上游的做法是「算好三份、让用户点一下」，
 * 这是这个预览表存在的唯一理由。
 *
 * ## 为什么纯文本档是「进表时算一次」而不是每帧算
 *
 * `markdownToPlainText` 要走一遍块级 + 行内解析。放在 `remember(content)` 里
 * 只算一次：用户在表里来回切档位不该触发重算。
 *
 * @param content 三档的来源（洗白后 Markdown + 只剥元数据的 XML）
 * @param onDismiss 关闭
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageCopyPreviewSheet(
    content: ChatCopyContent,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mode by remember(content) { mutableStateOf(MessageCopyMode.PlainText) }

    // 纯文本按需算一次。key 是 markdownSource —— 换一条消息才重算。
    val plainText = remember(content.markdownSource) {
        ChatCopyText.markdownToPlainText(content.markdownSource)
    }

    val shownText = when (mode) {
        MessageCopyMode.PlainText -> plainText
        MessageCopyMode.MarkdownSource -> content.markdownSource
        MessageCopyMode.XmlSource -> content.xmlSource
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 高度上限夹在 200..720dp：
                // 太矮看不清上下文（三行以上就要滚），太高会盖住用户想对照的
                // 原消息。上游按 0.7 屏高算，这里给绝对上下限 —— 小屏 0.7
                // 可能不足 200dp，平板 0.7 会到 900dp（大半屏空白）。
                .heightIn(min = 200.dp, max = 720.dp)
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text(
                text = "复制",
                style = MaterialTheme.typography.titleMedium,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // ## `FlowRow` 而不是 `Row`（2026-10-24 修）
            //
            // 复制模式有 3 档（纯文本 / Markdown / XML 原文），
            // 而 `ModalBottomSheet` 的内容区宽度 = 屏宽 - 40dp。
            // 三个 `FilterChip` 竖排文字加起来在窄屏上会超过这个宽度，
            // 用 `Row` 时最后一个 chip 被**裁在屏幕外**（看不到也点不到）。
            //
            // `FlowRow` 放不下就换行 —— 与 `MemoryCard` 里标签的
            // 处理是同一个判断（那边注释已写明「用 Row 会溢出」）。
            //
            // 顺带把间距改成语义正确的两个方向：水平 8dp（chip 之间）、
            // 垂直 8dp（换行后的行距）。原来只有 `spacedBy(8.dp)`
            // 管水平，换行后行与行会贴在一起。
            FlowRow(
                modifier = Modifier.padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MessageCopyMode.entries.forEach { candidate ->
                    FilterChip(
                        selected = mode == candidate,
                        onClick = { mode = candidate },
                        label = { Text(candidate.label) },
                    )
                }
            }

            BasicTextField(
                value = shownText,
                onValueChange = {},
                readOnly = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MiuixTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                // ## 为什么用 BasicTextField 而不是 Text
                //
                // 只读文本也需要**可选中、可长按复制其中一段**。Material3 的
                // `Text` 默认不可选中；`SelectionContainer` 能做到，但它自己
                // 不滚动 —— 选区拖到边缘时不会自动滚。BasicTextField 拥有
                // 选择拖拽逻辑，拖到边缘会自己滚并保持选区，这正是上游选它的
                // 原因。
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(bottom = 12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(shownText))
                        onDismiss()
                    },
                ) {
                    // 按钮文案带档位名（上游 `copyButtonText` 同一做法）：
                    // 用户切了档却点了按钮，能立刻从文案上确认复制的是哪档。
                    Text("复制${mode.label}")
                }
            }
        }
    }
}

/**
 * 删除确认弹窗（对齐上游 `showDeleteMessageConfirmDialog` 分支）。
 *
 * ## 为什么必须有这一步
 *
 * 上游点「删除」只置 `showDeleteMessageConfirmDialog = true`，真正删除在弹窗
 * 的确认按钮里。而长按菜单是**贴着手指弹出的**，删除项又在列表中段 ——
 * 没有确认就会「手一抖，几百字的回复没了」，且 Praxis 没有撤销。
 */
@Composable
fun MessageDeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认删除") },
        text = { Text("这条消息将被删除，且无法恢复。") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 消息信息弹窗（对齐 Operit `MessageInfoDialog`）。
 *
 * 菜单项要能一眼扫完；把角色 / 时间 / 字符数 / 工具名塞进菜单会让一项变四行。
 * 上游也是单开一个弹窗。字段只做只读展示。
 */
@Composable
fun MessageInfoDialog(
    bubble: ChatBubble?,
    onDismiss: () -> Unit,
) {
    val target = bubble ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "消息信息",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoRow("角色", roleLabel(target.role))
                InfoRow(
                    label = "时间",
                    value = if (target.timestamp > 0L) {
                        formatTimestamp(target.timestamp)
                    } else {
                        "（未记录）"
                    },
                )
                InfoRow("字符数", target.text.length.toString())
                InfoRow("是否流式中", if (target.streaming) "是" else "否")
                val tool = target.tool
                if (tool != null) {
                    InfoRow("工具名", tool.name)
                    InfoRow("工具耗时", tool.elapsedMs?.let { "$it ms" } ?: "进行中")
                    InfoRow("工具结果", if (tool.isError) "失败" else "成功")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 角色可读名。 */
private fun roleLabel(role: BubbleRole): String = when (role) {
    BubbleRole.User -> "用户"
    BubbleRole.Assistant -> "AI"
    BubbleRole.Tool -> "工具"
    BubbleRole.Error -> "错误"
    BubbleRole.Summary -> "摘要"
}

/** 时间格式化（固定 yyyy-MM-dd HH:mm:ss，与上游一致）。 */
private fun formatTimestamp(ts: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(ts))