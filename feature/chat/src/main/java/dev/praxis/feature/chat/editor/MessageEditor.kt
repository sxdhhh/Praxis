package dev.praxis.feature.chat.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 消息的一个片段（1:1 对齐 Operit `ParsedMessagePart`）。
 *
 * ## 为什么要把消息切成片段
 *
 * 模型的回复里混着大量结构化段落：`<think>…</think>`、`<tool>…</tool>`、
 * `<html>…</html>`。整段丢进一个 TextField 编辑，用户面对的是几百行
 * 尖括号标签，改一个错字都找不到位置。
 *
 * 切成片段后：文本片段给普通输入框，标签片段给「可折叠卡片 + 单独编辑」。
 *
 * [attributes] 单独存而不是拼在 [tag] 里：`<font color="red">` 的
 * `color="red"` 要原样回写（不能规范化、不能排序），但它不是标签名，
 * 混在一起就没法在 UI 上分开展示。
 */
data class ParsedMessagePart(
    val type: PartType,
    val content: String,
    val tag: String? = null,
    val attributes: String? = null,
)

/** 片段类型。 */
enum class PartType {
    /** 纯文本。 */
    TEXT,

    /** XML 标签包裹的一段。 */
    XML,
}

/** 标签候选（对齐上游 `XmlTagSuggestion`，只是把文案换成本地字符串）。 */
private data class XmlTagSuggestion(
    val name: String,
    val description: String,
)

/**
 * 常用标签候选。
 *
 * 顺序照抄上游：`think` 在前（最常用），其余按「模型最常产出的顺序」排。
 * `thinking` 与 `think` 都给：不同模型厂商用的名字不一样。
 */
private val XmlTagSuggestions = listOf(
    XmlTagSuggestion("think", "思考过程"),
    XmlTagSuggestion("thinking", "思考过程"),
    XmlTagSuggestion("search", "搜索内容"),
    XmlTagSuggestion("tool", "工具调用"),
    XmlTagSuggestion("tool_result", "工具结果"),
    XmlTagSuggestion("status", "状态信息"),
    XmlTagSuggestion("html", "HTML 内容"),
    XmlTagSuggestion("mood", "情绪标签"),
    XmlTagSuggestion("font", "字体样式"),
    XmlTagSuggestion("details", "折叠详情"),
    XmlTagSuggestion("detail", "折叠详情"),
    XmlTagSuggestion("meta", "元信息"),
)

/**
 * 把消息原文切成片段（1:1 照抄上游 `parseMessageContentForEditor`）。
 *
 * ## 正则为什么长这样
 *
 * ```
 * <([a-zA-Z0-9_-]+)([^>]*)>([\s\S]*?)</\1>
 * ```
 *
 * - `\1` 反向引用：`<think>…</tool>` 这类**不配对**的标签不能当成一对。
 *   不写反向引用的话，任何 `<a>` 后面最近的 `</b>` 都会被配成一对，
 *   回写时标签会被改掉。
 * - `[\s\S]*?` 而不是 `.*?`：`DOT_MATCHES_ALL` 与它是两种写法，
 *   这里用显式字符类，即使调用方忘了 flag 也能跨行匹配。
 *   少了这个，多行的 `<think>` 内容会被切碎。
 * - `([^>]*)` 捕获属性：`<font color="red">` 的 ` color="red"` 要保留。
 *
 * ## 空片段为什么要丢弃
 *
 * 相邻两个标签之间的空白（换行、缩进）会产出 `isBlank` 的文本片段。
 * 留著它们会让界面上出现一堆空白输入框，且回写时多出空行。
 * 注意只丢 `isBlank`（纯空白），不丢空串以外的**有内容**片段。
 */
fun parseMessageContentForEditor(content: String): List<ParsedMessagePart> {
    val parts = mutableListOf<ParsedMessagePart>()
    val regex = "<([a-zA-Z0-9_-]+)([^>]*)>([\\s\\S]*?)</\\1>"
        .toRegex(RegexOption.DOT_MATCHES_ALL)
    var lastIndex = 0

    regex.findAll(content).forEach { matchResult ->
        val startIndex = matchResult.range.first
        if (startIndex > lastIndex) {
            val textPart = content.substring(lastIndex, startIndex)
            if (textPart.isNotBlank()) {
                parts.add(ParsedMessagePart(PartType.TEXT, textPart))
            }
        }
        val tag = matchResult.groupValues[1]
        val attributes = matchResult.groupValues[2]
        val tagContent = matchResult.groupValues[3]
        parts.add(
            ParsedMessagePart(
                type = PartType.XML,
                content = tagContent,
                tag = tag,
                attributes = attributes,
            ),
        )
        lastIndex = matchResult.range.last + 1
    }

    // 尾部残余文本：最后一个标签之后的内容（或整段无标签的纯文本）。
    if (lastIndex < content.length) {
        val trailing = content.substring(lastIndex)
        if (trailing.isNotBlank()) {
            parts.add(ParsedMessagePart(PartType.TEXT, trailing))
        }
    }
    return parts
}

/**
 * 把片段拼回原文（1:1 照抄上游 `recomposeMessageFromParts`）。
 *
 * 文本片段原样输出；XML 片段还原成 `<tag attrs>content</tag>`。
 *
 * ## `attributes` 为什么直接拼在 tag 后面
 *
 * 上游解析时捕获的 `([^>]*)` 已经**包含**前导空格（`<font color="red">`
 * 的第二组是 ` color="red"`）。若这里再补一个空格会得到
 * `<font  color="red">`（双空格）—— 多数解析器能容忍，但会让
 * 「编辑一次、内容就变一次」的 diff 永远不干净。
 */
fun recomposeMessageFromParts(parts: List<ParsedMessagePart>): String =
    parts.joinToString(separator = "") { part ->
        if (part.type == PartType.TEXT) {
            part.content
        } else {
            "<${part.tag}${part.attributes ?: ""}>${part.content}</${part.tag}>"
        }
    }

/**
 * 消息编辑器（1:1 复刻 Operit `MessageEditor`，794 行）。
 *
 * ## 为什么这一块破例不用 miuix
 *
 * 它是 Operit 里交互最密的控件：顶部「可视化 / 纯文本」切换、中间
 * 可折叠的标签卡片、底部三段式按钮（取消 / 保存 / 保存并重发）。
 * 用 miuix 拼会得到不同的按钮高度与卡片内边距，而这一块的版式是
 * Operit 反复调过的。用户 2026-10-22 明确要求这三块 1:1 复刻。
 *
 * ## 两种编辑模式及其陷阱
 *
 * | 模式 | 编辑对象 | 陷阱 |
 * |---|---|---|
 * | 可视化 | 片段列表 | 片段变化要实时回写到 [editingMessageContent] |
 * | 纯文本 | 全文 | 切回可视化时必须**重新解析**，否则片段是旧的 |
 *
 * 两个 `LaunchedEffect` 正是处理这两件事，缺一个就会出现
 * 「改完切回纯文本还是旧的」或「纯文本改完切回可视化丢失修改」。
 *
 * ## `onSave` 与 `onResend` 的区别
 *
 * - `onSave`：只改这条消息的内容（不动对话流）。用于「编辑历史」——
 *   用户想改错字，不想重跑一轮（重跑要花钱、要等）。
 * - `onResend`：改完**从这条重新发**。用于「这条问错了」——
 *   他要把后面的回答全部作废重来。
 *
 * 两个都给：只给一个必然逼用户去用另一个场景。
 *
 * @param editingMessageContent 正在编辑的文本（双向绑定，外层持有）
 * @param showResendButton 是否显示「保存并重发」（编辑用户消息时为 true）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MessageEditor(
    editingMessageContent: MutableState<String>,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onResend: () -> Unit,
    showResendButton: Boolean,
) {
    val initialParts = remember(editingMessageContent.value) {
        parseMessageContentForEditor(editingMessageContent.value)
    }
    var partsState by remember { mutableStateOf(initialParts) }
    var partToEdit by remember { mutableStateOf<Pair<Int, ParsedMessagePart>?>(null) }
    var showCreateTagDialog by remember { mutableStateOf(false) }
    var isRawEditMode by remember { mutableStateOf(false) }

    // ① 片段变化 → 回写全文（只在可视化模式）。
    LaunchedEffect(partsState) {
        if (!isRawEditMode) {
            editingMessageContent.value = recomposeMessageFromParts(partsState)
        }
    }

    // ② 切回可视化 → 重新解析全文。
    //
    // ## 为什么这里必须重新解析，而不能继续用旧片段
    //
    // 纯文本模式下用户改的是 `editingMessageContent`，`partsState` 没动。
    // 不重新解析的话，切回可视化看到的是「进入纯文本之前的样子」，
    // 用户会以为自己的修改被丢弃了。
    LaunchedEffect(isRawEditMode) {
        if (!isRawEditMode) {
            partsState = parseMessageContentForEditor(editingMessageContent.value)
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.9f)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = MiuixTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column {
                EditorHeader(
                    title = if (showResendButton) "编辑消息" else "修改记忆",
                    isRawEditMode = isRawEditMode,
                    onToggleMode = { isRawEditMode = !isRawEditMode },
                    onCancel = onCancel,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 450.dp),
                ) {
                    if (isRawEditMode) {
                        RawEditPane(content = editingMessageContent)
                    } else {
                        VisualEditPane(
                            partsState = partsState,
                            onPartsChange = { partsState = it },
                            onEditPart = { index, part -> partToEdit = index to part },
                            onAddText = {
                                partsState = partsState + ParsedMessagePart(PartType.TEXT, "")
                            },
                            onAddTag = { showCreateTagDialog = true },
                        )
                    }
                }

                EditorActions(
                    showResendButton = showResendButton,
                    onCancel = onCancel,
                    onSave = onSave,
                    onResend = onResend,
                )
            }
        }
    }

    if (showCreateTagDialog || partToEdit != null) {
        val editingIndex = partToEdit?.first
        val editingPart = partToEdit?.second
        TagEditorDialog(
            part = editingPart,
            onDismiss = {
                partToEdit = null
                showCreateTagDialog = false
            },
            onSave = { updatedPart ->
                partsState = if (editingIndex != null) {
                    partsState.toMutableList().apply { set(editingIndex, updatedPart) }
                } else {
                    partsState + updatedPart
                }
                partToEdit = null
                showCreateTagDialog = false
            },
        )
    }
}

/** 顶部栏：标题 + 模式切换 + 关闭（照抄上游版式）。 */
@Composable
private fun EditorHeader(
    title: String,
    isRawEditMode: Boolean,
    onToggleMode: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onToggleMode) {
                // 按钮文案是「切过去会得到什么」而不是「当前是什么」：
                // 当前模式已经体现在界面上（有没有标签卡片），按钮要说明动作。
                Text(if (isRawEditMode) "可视化" else "纯文本")
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(32.dp).clip(CircleShape),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "取消",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * 纯文本编辑面板（照抄上游 `isRawEditMode` 分支）。
 *
 * 用 `Monospace` 字体：纯文本模式编辑的是「带标签的原文」，
 * 等宽字体让 `<think>` 这类标签与正文在视觉上分开，也方便对齐缩进。
 */
@Composable
private fun RawEditPane(content: MutableState<String>) {
    OutlinedTextField(
        value = content.value,
        onValueChange = { content.value = it },
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        label = { Text("纯文本内容") },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        // 16 行而不是无限：这段内容在弹窗里，太高会把「保存」按钮挤出屏幕。
        maxLines = 16,
        minLines = 6,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MiuixTheme.colorScheme.primary,
            unfocusedBorderColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
            focusedContainerColor = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            unfocusedContainerColor = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    )
}

/**
 * 可视化编辑面板：一组片段 + 底部两个「添加」按钮。
 *
 * 外层 `verticalScroll`：片段多时（十几段）必须能滚，否则底部按钮被挤出屏幕。
 */
@Composable
private fun VisualEditPane(
    partsState: List<ParsedMessagePart>,
    onPartsChange: (List<ParsedMessagePart>) -> Unit,
    onEditPart: (Int, ParsedMessagePart) -> Unit,
    onAddText: () -> Unit,
    onAddTag: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(bottom = 8.dp, top = 4.dp, start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "内容片段",
                style = MaterialTheme.typography.labelMedium,
                color = MiuixTheme.colorScheme.primary,
            )
        }

        partsState.forEachIndexed { index, part ->
            when (part.type) {
                PartType.TEXT -> {
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        OutlinedTextField(
                            value = part.content,
                            onValueChange = { newText ->
                                onPartsChange(
                                    partsState.toMutableList().apply {
                                        set(index, part.copy(content = newText))
                                    },
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp, max = 260.dp),
                            label = {
                                Text("文本", style = MaterialTheme.typography.bodySmall)
                            },
                            placeholder = { Text("输入文本内容") },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MiuixTheme.colorScheme.primary,
                                unfocusedBorderColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                                focusedContainerColor = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedContainerColor = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium,
                            maxLines = 12,
                            minLines = 1,
                        )
                        ActionIconButton(
                            icon = Icons.Default.Delete,
                            contentDescription = "删除",
                            onClick = {
                                onPartsChange(
                                    partsState.toMutableList().apply { removeAt(index) },
                                )
                            },
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 4.dp),
                        )
                    }
                }

                PartType.XML -> {
                    XmlTagItem(
                        part = part,
                        onClick = { onEditPart(index, part) },
                        onDelete = {
                            onPartsChange(partsState.toMutableList().apply { removeAt(index) })
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(
                onClick = onAddText,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MiuixTheme.colorScheme.primary,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加文本", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("添加文本", style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = onAddTag,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MiuixTheme.colorScheme.primary,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Outlined.Tag, contentDescription = "添加标签", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("添加标签", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * 一个 XML 标签片段卡片（1:1 复刻 Operit `XmlTagItem`）。
 *
 * ## 默认折叠，点标题行展开
 *
 * 标签内容常常很长（`<think>` 能有大几百字）。默认展开会让「4 个片段」的
 * 编辑器直接变成一屏文字，用户要滚很久才能找到下面那个「添加标签」按钮。
 * 折叠时只占一行：图标 + 标签名 + 属性 + 三个图标。
 *
 * ## 旋转箭头 150ms 而不是 300ms
 *
 * 箭头旋转是「点击的即时反馈」，不是内容展开动画。它必须比内容动画
 * （也是 150ms）**同样快或更快**，否则用户会觉得点下去「没反应，过一会儿才动」。
 * 这是上游的取值，照抄。
 *
 * ## 属性单独一行而不是拼在标签名后
 *
 * `<font color="red" size="14">` 的属性可能很长。拼在标签名后面会被
 * 省略号截断，用户就分不清「是哪个 font」。压到第二行（`labelSmall` + 单行省略）
 * 至少能看清开头几个字。
 */
@Composable
private fun XmlTagItem(
    part: ParsedMessagePart,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // 箭头旋转：与内容展开同步（150ms / FastOutSlowIn）。
    val rotationState by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "xmlTagArrowRotation",
    )

    Card(
        // `animateContentSize()`：折叠/展开时高度平滑变化。
        // 没有它时高度是瞬间跳变，列表下方的内容会「跳一下」。
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        // 用 primary 8% 做底色：与「文本片段」的普通输入框形成区分，
        // 让用户一眼看出「这个块是特殊结构，不是普通文字」。
        colors = CardDefaults.cardColors(
            containerColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.08f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column {
            // ── 标题行：整行可点，切换展开 ──────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { expanded = !expanded })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = part.tag ?: "XML",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    if (!part.attributes.isNullOrBlank()) {
                        Text(
                            text = part.attributes,
                            style = MaterialTheme.typography.labelSmall,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionIconButton(
                        icon = Icons.Default.Edit,
                        contentDescription = "编辑",
                        onClick = onClick,
                    )
                    ActionIconButton(
                        icon = Icons.Default.Delete,
                        contentDescription = "删除",
                        onClick = onDelete,
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier
                            .rotate(rotationState)
                            .size(22.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // ── 展开后的内容区 ────────────────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = tween(150)) +
                    expandVertically(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(150)) +
                    shrinkVertically(animationSpec = tween(150)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // surface 50% 叠在 primary 8% 上：内容区比标题行更淡，
                        // 视觉上「凹进去」，读起来像引用的正文。
                        .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.5f)),
                ) {
                    Text(
                        text = part.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * 小图标按钮（1:1 复刻 Operit `ActionIconButton`）。
 *
 * 28dp 触区 / 16dp 图形（上游取值）。比 Material 建议的 48dp 小，
 * 但这是卡片标题行里的密集操作条 —— 48dp 会把标签名挤成两三个字。
 * 判定依据同待发队列面板：这些操作都能补救（改错了能再改、删了能重加）。
 */
@Composable
private fun ActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 底部动作条（1:1 复刻 Operit 的 Action buttons 行）。
 *
 * ## 为什么按钮随 `showResendButton` 变化（而不是两个都显示）
 *
 * 两条路径的语义完全不同，同时显示会诱使用户点错：
 *
 * ```
 * 编辑用户消息（showResendButton = true）
 *   取消 | 保存 | 保存并重发 ⇗
 *
 * 编辑助手/工具消息（showResendButton = false）
 *   取消 | 保存
 * ```
 *
 * 编辑助手消息时**不能**给「重发」：那条是模型说的，重发等于让它重答一遍，
 * 而用户的意图通常是「改一下记录里的错字」，不该触发新的一轮请求（花钱、花时间，
 * 而且上下文会被再推一遍）。
 */
@Composable
private fun EditorActions(
    showResendButton: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onResend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 与顶部栏同一个浅底色：上下两栏在视觉上把内容区「夹」起来。
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onCancel,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                "取消",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        if (showResendButton) {
            // 「保存」用描边按钮、「保存并重发」用实心按钮：
            // 实心 = 主要动作。用户改完想重发时，多半就是想点右边那个。
            OutlinedButton(
                onClick = onSave,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.6f)),
            ) {
                Text(
                    "保存",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Button(
                onClick = onResend,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MiuixTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    "保存并重发",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            Button(
                onClick = onSave,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    "保存",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * 标签编辑对话框（1:1 复刻 Operit `TagEditorDialog`）。
 *
 * ## 同一个对话框兼两职
 *
 * `part == null` → 新建标签（标题「新建标签」）；否则 → 编辑已有标签。
 * 合在一起而不是写两个：三处输入（标签名 / 属性 / 内容）与校验规则完全一致，
 * 拆成两个必然出现「新建时能输属性、编辑时不能」这类不一致。
 *
 * ## 标签名右侧的下拉是「候选」而不是「必须选」
 *
 * 输入框本身可自由输入（模型可能产出本项目没列出的标签）。下拉只提供
 * 常用标签的快速填写 + 一句说明（「思考过程」/「工具调用」），
 * 目的是让用户在不知道有哪些标签时也能填对。
 *
 * ## 内容框为什么固定 120dp 而不自适应
 *
 * 对话框整体高度要稳定：内容框若随文字增高，用户每输入一个字，
 * 下方的「保存」按钮就会往下走一点 —— 点它的时候经常点空。
 * 固定高度 + 框内滚动是这里的正确取舍。
 *
 * @param part 要编辑的片段；`null` 表示新建
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagEditorDialog(
    part: ParsedMessagePart?,
    onDismiss: () -> Unit,
    onSave: (ParsedMessagePart) -> Unit,
) {
    // 用 `part` 作初值：对话框在「切换编辑对象」时会被重建（调用方控制显隐），
    // 所以这里不需要 `remember(part)` 去同步外部变化。
    var tagName by remember { mutableStateOf(part?.tag ?: "") }
    var attributes by remember { mutableStateOf(part?.attributes ?: "") }
    var content by remember { mutableStateOf(part?.content ?: "") }
    var tagMenuExpanded by remember { mutableStateOf(false) }
    val isNewTag = part == null

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            color = MiuixTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // ── 标题区 ──────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = if (isNewTag) "新建标签" else "编辑标签",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (isNewTag) {
                                "创建一个新的 XML 标签片段"
                            } else {
                                "修改标签名、属性或内容"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    // 圆形图标底：与标题形成主次（标题是内容，这个是装饰）。
                    Surface(
                        modifier = Modifier.size(36.dp),
                        shape = CircleShape,
                        color = MiuixTheme.colorScheme.primary.copy(alpha = 0.15f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.Tag,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── 标签名（带候选下拉）───────────────────────────
                OutlinedTextField(
                    value = tagName,
                    onValueChange = { tagName = it },
                    label = {
                        Text("标签名", style = MaterialTheme.typography.bodySmall)
                    },
                    placeholder = { Text("例如 think") },
                    trailingIcon = {
                        Box {
                            IconButton(onClick = { tagMenuExpanded = true }) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "选择常用标签",
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            DropdownMenu(
                                expanded = tagMenuExpanded,
                                onDismissRequest = { tagMenuExpanded = false },
                                // 260dp 宽 + 最高 360dp：12 个候选项一屏放不下，
                                // 给滚动而不是继续加宽（加宽会超出手机屏宽）。
                                modifier = Modifier
                                    .width(260.dp)
                                    .heightIn(max = 360.dp),
                            ) {
                                Text(
                                    text = "常用标签",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                HorizontalDivider()
                                XmlTagSuggestions.forEach { suggestion ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = suggestion.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    // 等宽字体：`tool_result` 这类下划线
                                                    // 名字在比例字体里很难快速扫读。
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    text = suggestion.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        },
                                        onClick = {
                                            tagName = suggestion.name
                                            tagMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MiuixTheme.colorScheme.primary,
                        unfocusedBorderColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    ),
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ── 属性（可选）──────────────────────────────────
                OutlinedTextField(
                    value = attributes,
                    onValueChange = { attributes = it },
                    label = {
                        Text("属性（可选）", style = MaterialTheme.typography.bodySmall)
                    },
                    placeholder = { Text("例如 color=\"red\"") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MiuixTheme.colorScheme.primary,
                        unfocusedBorderColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                    ),
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ── 内容 ─────────────────────────────────────────
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = {
                        Text("内容", style = MaterialTheme.typography.bodySmall)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MiuixTheme.colorScheme.primary,
                        unfocusedBorderColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                        // 四个容器色统一成 surface：不统一时聚焦瞬间底色会「闪一下」。
                        focusedContainerColor = MiuixTheme.colorScheme.surface,
                        unfocusedContainerColor = MiuixTheme.colorScheme.surface,
                        disabledContainerColor = MiuixTheme.colorScheme.surface,
                        errorContainerColor = MiuixTheme.colorScheme.surface,
                    ),
                )

                Spacer(modifier = Modifier.height(20.dp))

                // ── 动作按钮 ────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.6f)),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "取消",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Button(
                        onClick = {
                            // attributes 为空串时存 null（不是空串）：
                            // `recomposeMessageFromParts` 会拼 `<tag${attributes ?: ""}>`，
                            // 空串与 null 结果相同，但 null 在 `XmlTagItem` 里
                            // 会跳过「属性行」的渲染（`isNullOrBlank` 判断），
                            // 空串也能跳过 —— 统一用 null 让数据更干净。
                            onSave(
                                ParsedMessagePart(
                                    type = PartType.XML,
                                    content = content,
                                    tag = tagName.trim(),
                                    attributes = attributes.ifBlank { null },
                                ),
                            )
                        },
                        // 标签名不能为空：空标签名回写成 `<></>`，解析时无法配对，
                        // 整条消息的结构就坏了。
                        enabled = tagName.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "保存",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
