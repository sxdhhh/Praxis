package dev.praxis.feature.settings.character
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.praxis.core.model.character.PromptTag
import dev.praxis.core.model.character.PromptTagType
import dev.praxis.feature.settings.common.SettingsList
import dev.praxis.ui.theme.PraxisMotion
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 提示词标签库。
 *
 * ## 标签存在的意义
 *
 * 同一段「回答必须给出可执行命令」可能要被 5 张角色卡引用。写 5 遍不仅麻烦，
 * 更糟的是**改一处要改 5 处**，用户必然会漏。标签把这段提示词抽成一处，
 * 改一次全部生效。
 *
 * ## 编辑正文的「远程副作用」怎么处理（2026-10-29 补）
 *
 * 很久一版只做「新建 + 列表 + 删除」。理由是编辑正文会影响所有引用它的
 * 角色卡：用户改一句、5 张卡的人设都变了，而这种副作用看不见。
 *
 * 结果就是 `CharacterViewModel.updateTag` 零调用 —— 标签只能看不能改，
 * 打错一个字只能删掉重建（还会连带清掉所有卡的引用）。这比「有副作用的
 * 编辑」更糟。
 *
 * 所以现在补编辑，副作用用**三步可见化**处理，而不是靠禁止编辑：
 *
 *  1. 列表每行一直显示「N 张卡在用」，点「编辑」时能看到影响面；
 *  2. 编辑弹窗里再写一次「修改会立即影响 N 张卡」；
 *  3. 删除按钮的文案带后果（「删除（并从 N 张卡移除）」），与编辑形成对照 ——
 *     用户能分清「改」和「删」的代价。
 */
@Composable
fun PromptTagsScreen(
    tags: List<PromptTag>,
    /** 每个标签被多少张角色卡引用（key = tag id）。 */
    usageCounts: Map<String, Int>,
    onCreate: (name: String, content: String, type: PromptTagType) -> Unit,
    /**
     * 保存对已有标签的修改。
     *
     * 与 [onCreate] 分开而不是合成一个「upsert」：两者语义差很多 ——
     * 新建会**复用内容相同**的旧标签（`createOrReuseTag`），而编辑必须
     * 落到指定的那一条上。合成一个的话，编辑后内容恰好与另一条相同时
     * 会被静默合并，用户看到的是「我改的这个没了」。
     */
    onUpdate: (PromptTag) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draftName by remember { mutableStateOf("") }
    var draftContent by remember { mutableStateOf("") }

    // 正在编辑的标签 id。null = 没在编辑。存 id 而不是整个 tag：
    // 列表在编辑期间可能因外部改动刷新，存整个对象会让弹窗显示过期内容。
    var editingTagId by remember { mutableStateOf<String?>(null) }

    // ── 编辑弹窗 ──
    //
    // 放在 SettingsList 之前渲染（Dialog 走独立 window，位置不影响层级）。
    // `editingTagId` 换成对象后再交给弹窗：弹窗自己只负责「编辑一份草稿」，
    // 不查库、不关心列表怎么刷新。
    val editingTag = editingTagId?.let { id -> tags.firstOrNull { it.id == id } }
    if (editingTag != null) {
        EditTagDialog(
            tag = editingTag,
            usageCount = usageCounts[editingTag.id] ?: 0,
            onDismiss = { editingTagId = null },
            onSave = { updated ->
                onUpdate(updated)
                editingTagId = null
            },
        )
    }

    SettingsList(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SmallTitle("新建标签")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "标签是可复用的提示词片段。内容相同的标签会自动复用，" +
                            "不会重复创建。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "标签名（如：简洁回答）",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = draftContent,
                        onValueChange = { draftContent = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "提示词内容（会原样拼进角色卡提示词）",
                        useLabelAsPlaceholder = true,
                        minLines = 4,
                        maxLines = 10,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = "添加",
                            onClick = {
                                onCreate(draftName, draftContent, PromptTagType.Custom)
                                // 成功后清空草稿：否则用户再点一次「添加」会建出
                                // 一条内容相同的标签（被复用，看起来像「没反应」）。
                                draftName = ""
                                draftContent = ""
                            },
                            enabled = draftContent.isNotBlank(),
                        )
                    }
                }
            }
        }

        item(key = "__title_tags__") { SmallTitle("已有标签（${tags.size}）") }

        if (tags.isEmpty()) {
            item(key = "__empty_tags__") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = "还没有标签。上面新建一条后，就能在角色卡编辑页挂上去。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        items(tags, key = { "tag:${it.id}" }) { tag ->
            // ## `animateItem`（2026-10-24）
            //
            // 标签可以删除，删一条之后下面每条上移一格。这是本列表唯一的
            // 动态变化，也是最容易被注意到的（用户点删除后盯着列表看结果）。
            //
            // 只给 `placementSpec` + fadeOut，**不给 fadeIn**：
            // 新标签是用户在顶部输入框里新建的，它出现时上方还有内容，
            // 淡入会与「刚敲完回车、眼睛已在顶部」的注意力错位。
            // 而删除是「东西消失」，淡出是必要的（否则凭空不见）。
            Box(
                modifier = Modifier.animateItem(
                    placementSpec = spring(stiffness = 400f, dampingRatio = 0.85f),
                    fadeOutSpec = tween(
                        durationMillis = PraxisMotion.Durations.Fast,
                        easing = PraxisMotion.Curves.Accelerate,
                    ),
                ),
            ) {
                TagRow(
                    tag = tag,
                    usageCount = usageCounts[tag.id] ?: 0,
                    onEdit = { editingTagId = tag.id },
                    onDelete = { onDelete(tag.id) },
                )
            }
        }

        item(key = "__tail_spacer__") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TagRow(
    tag: PromptTag,
    usageCount: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tag.name,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 引用数是删除前必须看到的信息：删掉一个被 8 张卡引用的标签，
                // 8 张卡的人设都会少一段。不显示的话用户无法判断后果。
                if (usageCount > 0) {
                    Text(
                        text = "$usageCount 张卡在用",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
            if (tag.promptContent.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = tag.promptContent.take(120).replace("\n", " "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // 「编辑」放在「删除」左边：两个按钮都是对同一条标签的破坏性
                // 操作，把轻的那个放前面，手指先碰到的是可撤销的。
                TextButton(text = "编辑", onClick = onEdit)
                TextButton(
                    // 文案带上后果，而不是干巴巴的「删除」。
                    text = if (usageCount > 0) "删除（并从 $usageCount 张卡移除）" else "删除",
                    onClick = onDelete,
                )
            }
        }
    }
}

/**
 * 标签编辑弹窗。
 *
 * ## 为什么用 Dialog 而不是「把列表某一行变成输入框」
 *
 * 行内编辑会让列表在编辑期间高度跳变（多出一个多行输入框），而用户
 * 正在盯着自己刚点的位置 —— 内容会从手指下面移走。弹一层则完全不改
 * 列表布局，且「取消」有明确边界（关掉就恢复原样）。
 *
 * ## 关掉时丢弃草稿
 *
 * `draftName` / `draftContent` 用 `remember(tag.id)` 初始化：换成另一条
 * 标签会重建状态，不会把上一条的草稿带过来。点「取消」不写回，
 * 因为用户没点保存 —— 这与「行内编辑自动保存」是相反的取舍，
 * 对影响多张卡的字段来说，显式保存更安全。
 */
@Composable
private fun EditTagDialog(
    tag: PromptTag,
    usageCount: Int,
    onDismiss: () -> Unit,
    onSave: (PromptTag) -> Unit,
) {
    // key 用 tag.id：切换编辑对象时重建草稿，不残留上一条的内容。
    var name by remember(tag.id) { mutableStateOf(tag.name) }
    var content by remember(tag.id) { mutableStateOf(tag.promptContent) }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "编辑标签", style = MiuixTheme.textStyles.body1)
                Spacer(Modifier.height(4.dp))

                // 引用数必须在弹窗里再说一次：用户可能是从列表滚动中段
                // 点进来的，那一行的「N 张卡在用」已经滚出视野了。
                Text(
                    text = if (usageCount > 0) {
                        "这条标签正被 $usageCount 张角色卡引用。保存后它们的人设会立即变化。"
                    } else {
                        "还没有角色卡引用这条标签。"
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (usageCount > 0) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
                Spacer(Modifier.height(8.dp))

                TextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "标签名",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "提示词内容",
                    useLabelAsPlaceholder = true,
                    minLines = 4,
                    maxLines = 10,
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    TextButton(
                        text = "保存",
                        // 空内容禁用：`saveTag` 会把空名兜成「未命名标签」，
                        // 但空正文的标签挂到卡上等于什么都没加，让它先别存。
                        enabled = content.isNotBlank(),
                        onClick = {
                            onSave(tag.copy(name = name, promptContent = content))
                        },
                    )
                }
            }
        }
    }
}
