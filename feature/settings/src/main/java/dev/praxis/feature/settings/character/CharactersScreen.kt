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
import dev.praxis.core.model.character.CharacterCard
import dev.praxis.feature.settings.common.SettingsList
import dev.praxis.ui.theme.PraxisMotion
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 角色卡列表页。
 *
 * ## 为什么把「当前生效」做成显式徽章而不是高亮
 *
 * 高亮（背景色/边框）在莫奈取色下与选中态、按压态容易混淆，
 * 而且色弱用户看不出差别。用文字徽章「使用中」是**语义**表达，
 * 不依赖颜色。
 *
 * ## 为什么删除放在编辑页而不是列表长按
 *
 * 长按删除在列表里没有任何视觉提示，用户不知道有这个功能；
 * 而一旦误触就直接丢一张卡（卡里是几千字人设）。
 * 放在编辑页需要「进页 → 再点删除」，多一步但不会再误删。
 */
@Composable
fun CharactersScreen(
    state: CharactersUiState,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onCreate: () -> Unit,
    onImportJson: (String) -> Unit,
    onOpenTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 导入草稿。放在页面级状态而不是 ViewModel：
    // 它只在对话框存活期间有意义，离开页面就该丢弃
    // （用户取消导入后，下次再点导入不该看到上次粘的半截 JSON）。
    var importText by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }

    SettingsList(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            SmallTitle("角色卡")
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "共 ${state.cards.size} 张",
                        style = MiuixTheme.textStyles.body2,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // 说清「同时只有一张生效」，否则用户会以为能叠加。
                        text = "同时只有一张生效。切换后下一句消息就会按新卡的人设回答，" +
                            "已发出的消息不受影响。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // 说清共用系统提示词的关系，避免用户以为两者互斥。
                        text = "角色卡内容会和设置里的系统提示词一起生效：" +
                            "全局规则在前，角色设定在后。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "新建角色",
                    onClick = onCreate,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "导入",
                    onClick = { showImport = true },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "标签库",
                    onClick = onOpenTags,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.loading) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = "正在读取…",
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else if (state.cards.isEmpty()) {
            // 正常不会出现（默认卡删不掉，且启动会补建），
            // 但恢复备份失败时可能出现，必须给可操作的空态而不是一片空白。
            item(key = "__empty_characters__") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "还没有角色卡", style = MiuixTheme.textStyles.body2)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "点上面的「新建角色」开始，或用「导入」粘贴酒馆角色卡 JSON。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        items(state.cards, key = { it.id }) { card ->
            // ## `animateItem`（2026-10-24）
            //
            // 角色卡可以删除/导入，删一张之后下面每张都要上移一格 ——
            // 没有位移动画时是「啪」地重排。
            //
            // 包一层 Box 而不是给 `CharacterRow` 加 modifier 形参：
            // 那个 composable 内部是 Card，动画挂在它外层才不会与
            // 阴影/圆角的重绘叠加（与 `ChatListUi` 同一处理）。
            Box(
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(
                        durationMillis = PraxisMotion.Durations.Base,
                        easing = PraxisMotion.Curves.Decelerate,
                    ),
                    placementSpec = spring(stiffness = 400f, dampingRatio = 0.85f),
                    fadeOutSpec = tween(
                        durationMillis = PraxisMotion.Durations.Fast,
                        easing = PraxisMotion.Curves.Accelerate,
                    ),
                ),
            ) {
                CharacterRow(
                    card = card,
                    isActive = card.id == state.activeId,
                    tagCount = state.tagCountOf(card.id),
                    onSelect = { onSelect(card.id) },
                    onEdit = { onEdit(card.id) },
                )
            }
        }

        item(key = "__tail_spacer__") {
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showImport) {
        ImportDialog(
            text = importText,
            onTextChange = { importText = it },
            onDismiss = {
                // 取消时清空草稿：下次点导入是干净的输入框，
                // 而不是上次粘了一半的 JSON（用户会以为导入坏了）。
                showImport = false
                importText = ""
            },
            onConfirm = {
                onImportJson(importText)
                showImport = false
                importText = ""
            },
        )
    }
}

/**
 * 粘贴酒馆角色卡 JSON 的对话框。
 *
 * ## 为什么不做「选文件」
 *
 * 酒馆角色卡最常见的来源是网页/聊天里复制的 JSON 文本。
 * 做文件选择还要处理 SAF、PNG 内嵌卡（要解析 tEXt chunk），
 * 那是另一大块工作；先把「粘贴 JSON」这条路做通，覆盖 90% 场景。
 *
 * ## 为什么确认按钮不禁用空输入
 *
 * 禁用按钮在空输入时看起来像卡死。允许点，由 ViewModel 返回
 * 「导入失败：内容为空」的明确提示更好 —— 用户能立刻知道要做什么。
 */
@Composable
private fun ImportDialog(
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "导入角色卡",
                    style = MiuixTheme.textStyles.body1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "粘贴酒馆（SillyTavern）角色卡 JSON，支持 V1 与 V2 格式。" +
                        "带 operit 扩展的卡会完整保留全部字段。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = "{\"spec\":\"chara_card_v2\", \"data\":{…}}",
                    useLabelAsPlaceholder = true,
                    minLines = 6,
                    maxLines = 10,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    TextButton(text = "导入", onClick = onConfirm)
                }
            }
        }
    }
}

/** 单张角色卡。整行点击 = 切换生效，右侧「编辑」进详情。 */
@Composable
private fun CharacterRow(
    card: CharacterCard,
    isActive: Boolean,
    tagCount: Int,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = card.name,
                        style = MiuixTheme.textStyles.body1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isActive) {
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(
                            text = "使用中",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    if (card.isBuiltinDefault) {
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(
                            text = "内置",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                // 副标题优先显示简介；没有简介就显示「附着 N 个标签」，
                // 两者都没有才显示占位 —— 避免出现一行空白的行高跳动。
                val subtitle = card.description.ifBlank {
                    if (tagCount > 0) "附着 $tagCount 个标签" else "未填写简介"
                }
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!isActive) {
                TextButton(
                    text = "使用",
                    onClick = onSelect,
                )
            }
            Spacer(Modifier.padding(horizontal = 2.dp))
            TextButton(
                text = "编辑",
                onClick = onEdit,
            )
        }
    }
}